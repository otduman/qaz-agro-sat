"""Owner sheet for one district square: its field and fallow regions, largest first, each with a point deep inside
it in QazTRF-23 / Gauss-Kruger zone 12 (east, north, as the public cadastral map shows), the cut status from the
harvest step, the running share of field area, and blank columns for the plot card. Writes
<square>/owners-<year>.csv. Run from the repository root:
python data/tayynsha/tools/owner_sheet_square.py E560N5960 2026
"""
import csv
import math
import sys

import numpy as np
import tifffile

BASE = 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/'
square, year = sys.argv[1], sys.argv[2]
SQ = BASE + square + '/'
props = dict(l.strip().split('=', 1) for l in open(SQ + f'season-{year}/' + 'stack.properties', encoding='utf-8') if '=' in l)
_, ORIGIN_E, ORIGIN_N, SIZE, _, _ = props['grid'].split(',')
ORIGIN_E, ORIGIN_N, SIZE = float(ORIGIN_E), float(ORIGIN_N), float(SIZE)

a = 6378137.0
f = 1 / 298.257222101
n = f / (2 - f)
A = a / (1 + n) * (1 + n ** 2 / 4 + n ** 4 / 64 + n ** 6 / 256)
al = [None, n / 2 - 2 * n ** 2 / 3 + 5 * n ** 3 / 16 + 41 * n ** 4 / 180 - 127 * n ** 5 / 288 + 7891 * n ** 6 / 37800,
      13 * n ** 2 / 48 - 3 * n ** 3 / 5 + 557 * n ** 4 / 1440 + 281 * n ** 5 / 630 - 1983433 * n ** 6 / 1935360,
      61 * n ** 3 / 240 - 103 * n ** 4 / 140 + 15061 * n ** 5 / 26880 + 167603 * n ** 6 / 181440,
      49561 * n ** 4 / 161280 - 179 * n ** 5 / 168 + 6601661 * n ** 6 / 7257600,
      34729 * n ** 5 / 80640 - 3418889 * n ** 6 / 1995840, 212378941 * n ** 6 / 319334400]
e = math.sqrt(f * (2 - f))


def tm(lon, lat, k0, fe):
    phi = math.radians(lat)
    lam = math.radians(lon - 69)
    t = math.sinh(math.atanh(math.sin(phi)) - e * math.atanh(e * math.sin(phi)))
    xi = math.atan2(t, math.cos(lam))
    eta = math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    x = xi + sum(al[j] * math.sin(2 * j * xi) * math.cosh(2 * j * eta) for j in range(1, 7))
    y = eta + sum(al[j] * math.cos(2 * j * xi) * math.sinh(2 * j * eta) for j in range(1, 7))
    return fe + k0 * A * y, k0 * A * x


def from_utm(east, north):
    lon, lat = 68.85, 53.9
    for _ in range(30):
        ee, nn = tm(lon, lat, 0.9996, 500000)
        lon += (east - ee) / (111320 * math.cos(math.radians(lat)))
        lat += (north - nn) / 111200
    return lon, lat


def gk(lon, lat):
    return tm(lon, lat, 1.0, 12500000)



labels = tifffile.imread(SQ + f'fields-{year}/fields-{year}.tif').astype(np.int32)
fields = {}
for row in csv.DictReader(open(SQ + f'series-{year}/fields-{year}.csv', encoding='utf-8')):
    if row['land'] in ('field', 'fallow') and row['in_series'] == 'yes':
        fields[int(row['id'].split('-F')[1])] = row
cuts = {r['field']: r for r in csv.DictReader(open(SQ + f'harvest-{year}/harvest-{year}.csv', encoding='utf-8'))}


def deepest(mask):
    """The pixel of the mask farthest (in whole-pixel steps) from its edge."""
    depth = mask.copy()
    last = mask
    while True:
        e1 = last.copy()
        e1[1:, :] &= last[:-1, :]
        e1[:-1, :] &= last[1:, :]
        e1[:, 1:] &= last[:, :-1]
        e1[:, :-1] &= last[:, 1:]
        e1[0, :] = e1[-1, :] = False
        e1[:, 0] = e1[:, -1] = False
        if not e1.any():
            ys, xs = np.nonzero(last)
            i = len(ys) // 2
            return ys[i], xs[i]
        last = e1



rows = []
for region, row in sorted(fields.items(), key=lambda kv: -float(kv[1]['area_ha'])):
    ys, xs = np.nonzero(labels == region)
    if len(ys) == 0:
        continue
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    crop = np.pad(labels[y0:y1, x0:x1] == region, 1)
    y, x = deepest(crop)
    y += y0 - 1
    x += x0 - 1
    lon, lat = from_utm(ORIGIN_E + (x + 0.5) * SIZE, ORIGIN_N - (y + 0.5) * SIZE)
    east, north = gk(lon, lat)
    c = cuts.get(row['id'], {})
    rows.append([row['id'], row['land'], row['area_ha'], c.get('status', ''), c.get('cut_middle', ''),
                 f'{east:.1f}', f'{north:.1f}', f'{lat:.6f}', f'{lon:.6f}'])
total = sum(float(r[2]) for r in rows if r[1] == 'field')
run = 0.0
out = []
for r in rows:
    if r[1] == 'field':
        run += float(r[2])
    out.append(r + [f'{run / total:.3f}', '', '', '', '', ''])
header = ['field', 'land', 'area_ha', 'status', 'cut_middle', 'map_east', 'map_north', 'lat', 'lon',
          'field_area_share_so_far', 'cadastral_number', 'land_user', 'user_type', 'right', 'notes']
with open(SQ + f'owners-{year}.csv', 'w', encoding='utf-8-sig', newline='') as fh:
    w = csv.writer(fh)
    w.writerow(header)
    w.writerows(out)
n80 = next(i for i, r in enumerate(out) if r[1] == 'field' and float(r[9]) >= 0.8) + 1
print(f'{len(out)} regions, {total:.0f} ha of field; the largest {n80} rows reach 80% of the field area')
