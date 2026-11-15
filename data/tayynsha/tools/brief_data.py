"""Data for a district summary page (district map, holders, harvest pace), as one JSON file.

Fields: every field of the joined 2026 district table, outline simplified to about 20 m, on a flat kilometre grid
around the district centre; crop group, harvest status and window, holder. Companies (TOO) are named; peasant farms
and private holders are shown only by kind (the owner's rule for this page). Names come from data/ and the output
holds them, so write it outside the repository. Run from the repository root:
python data/tayynsha/tools/brief_data.py data/tayynsha 2026 data/tayynsha/holders.csv OUT.json
Throwaway experiment code.
"""
import csv
import datetime as dt
import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import holder_pace  # noqa: E402
import market  # noqa: E402

TOLERANCE_KM = 0.02
KIND = {'farm': 'farm', 'peasant farm': 'farm', 'holder hidden': 'private', 'shared lease': 'lease',
        'state college': 'college'}
GROUP = {'short season': 'grain', 'long season': 'oilseed', 'yellow flowers': 'oilseed', 'perennial grass': 'other',
         'unclear': 'none', 'no crop seen': 'none'}


def projector(lat0, lon0):
    kx = 111.32 * math.cos(math.radians(lat0))
    return lambda lon, lat: (round((lon - lon0) * kx, 3), round((lat0 - lat) * 110.57, 3))


def simplify(points, tol):
    """Douglas-Peucker on a closed ring of (x, y)."""
    if len(points) < 5:
        return points

    def dp(pts):
        a, b = pts[0], pts[-1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        norm = math.hypot(dx, dy) or 1e-12
        best, idx = -1.0, 0
        for i in range(1, len(pts) - 1):
            d = abs(dy * (pts[i][0] - a[0]) - dx * (pts[i][1] - a[1])) / norm
            if d > best:
                best, idx = d, i
        if best > tol:
            return dp(pts[:idx + 1])[:-1] + dp(pts[idx:])
        return [a, b]

    half = len(points) // 2
    out = dp(points[:half + 1])[:-1] + dp(points[half:])
    return out if len(out) >= 4 else points


def name(holder, kind):
    """Companies (TOO) by name; everyone else only by kind, which the page labels."""
    if kind == 'company':
        n = holder.replace('ТОО ', '').strip().strip('"').replace('""', '"')
        return f'ТОО «{n}»'
    return None


_A = 6378137.0
_F = 1 / 298.257223563
_N = _F / (2 - _F)
_AA = _A / (1 + _N) * (1 + _N ** 2 / 4 + _N ** 4 / 64)
_AL = [None, _N / 2 - 2 * _N ** 2 / 3 + 5 * _N ** 3 / 16, 13 * _N ** 2 / 48 - 3 * _N ** 3 / 5, 61 * _N ** 3 / 240]
_E = math.sqrt(_F * (2 - _F))


def _utm(lon, lat, lon0=69.0):
    phi, lam = math.radians(lat), math.radians(lon - lon0)
    t = math.sinh(math.atanh(math.sin(phi)) - _E * math.atanh(_E * math.sin(phi)))
    xi = math.atan2(t, math.cos(lam))
    eta = math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    x = xi + sum(_AL[j] * math.sin(2 * j * xi) * math.cosh(2 * j * eta) for j in range(1, 4))
    y = eta + sum(_AL[j] * math.cos(2 * j * xi) * math.sinh(2 * j * eta) for j in range(1, 4))
    return 500000 + 0.9996 * _AA * y, 0.9996 * _AA * x


def from_utm(east, north):
    """UTM zone 42N (EPSG 32642) to (lon, lat), by iterating the forward projection."""
    lon, lat = 69.0, 54.0
    for _ in range(40):
        e, n = _utm(lon, lat)
        lon += (east - e) / (111320 * math.cos(math.radians(lat)))
        lat += (north - n) / 111200
    return lon, lat


def utm_box(proj, e0, n0, e1, n1, steps=8):
    """A UTM rectangle as a ring on the page grid, with points along each edge."""
    ring = []
    for i in range(steps):
        ring.append((e0 + (e1 - e0) * i / steps, n1))
    for i in range(steps):
        ring.append((e1, n1 - (n1 - n0) * i / steps))
    for i in range(steps):
        ring.append((e1 - (e1 - e0) * i / steps, n0))
    for i in range(steps):
        ring.append((e0, n0 + (n1 - n0) * i / steps))
    return [round(v, 2) for e, n in ring for v in proj(*from_utm(e, n))]


def main():
    district, year, holders_csv, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    holders, types = {}, {}
    for r in csv.DictReader(open(holders_csv, encoding='utf-8-sig')):
        holders[(r['square'], r['field'])] = r['holder']
        types[r['holder']] = r['holder_type']
    groups_file = f'{district}/company-groups.csv'
    in_group = {r['holder'] for r in csv.DictReader(open(groups_file, encoding='utf-8'))}
    table = [r for r in csv.DictReader(open(f'{district}/district-harvest-{year}.csv', encoding='utf-8'))
             if r['land'] == 'field']
    owner = holder_pace.district_owners(district, 2026, holders, int(year), table)
    crops = {}
    for sq in os.listdir(district):
        p = f'{district}/{sq}/crops-{year}.csv'
        if os.path.isfile(p):
            for c in csv.DictReader(open(p, encoding='utf-8')):
                crops[(sq, c['field'])] = c['group']

    boundary = json.load(open('docs/districts/tayynsha.geojson', encoding='utf-8'))
    geom = boundary['features'][0]['geometry'] if 'features' in boundary else boundary['geometry']
    rings = geom['coordinates'] if geom['type'] == 'Polygon' else [p[0] for p in geom['coordinates']]
    if geom['type'] == 'Polygon':
        rings = [rings[0]]
    lats = [p[1] for ring in rings for p in ring]
    lons = [p[0] for ring in rings for p in ring]
    lat0, lon0 = (min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2
    proj = projector(lat0, lon0)

    want = defaultdict(set)
    for r in table:
        want[r['square']].add(r['field'])
    shapes = {}
    for sq, ids in want.items():
        g = json.load(open(f'{district}/{sq}/fields-{year}/fields-{year}.geojson', encoding='utf-8'))
        for f in g['features']:
            fid = f['properties']['id']
            if fid in ids:
                ring = f['geometry']['coordinates'][0] if f['geometry']['type'] == 'Polygon' \
                    else max((p[0] for p in f['geometry']['coordinates']), key=len)
                pts = simplify([proj(lon, lat) for lon, lat in ring], TOLERANCE_KM)
                shapes[(sq, fid)] = [round(v, 2) for p in pts for v in p]

    read = {sq for sq, _ in holders}
    fields = []
    for r in table:
        k = (r['square'], r['field'])
        if k not in shapes:
            continue
        h = owner.get(k)
        kind = types.get(h, '') if h else ''
        fields.append(dict(p=shapes[k], ha=round(float(r['area_ha'])), c=GROUP.get(crops.get(k, 'unclear'), 'none'),
                           s=r['status'], f=r['cut_from'], t=r['cut_to'], m=r['cut_middle'],
                           h=name(h, kind) if h else None,
                           k=('group' if h in in_group else 'company' if kind == 'company' else KIND.get(kind, 'farm'))
                           if h else None,
                           r=1 if r['square'] in read else 0))

    squares = []
    for r in csv.DictReader(open(f'{district}/squares.csv', encoding='utf-8')):
        if r['square'] in want:
            box = [float(r[x]) for x in ('min_e', 'min_n', 'max_e', 'max_n')]
            squares.append(dict(id=r['square'], read=r['square'] in read, box=box, p=utm_box(proj, *box)))
    rb = [q['box'] for q in squares if q['read']]
    read_area = utm_box(proj, min(b[0] for b in rb), min(b[1] for b in rb), max(b[2] for b in rb),
                        max(b[3] for b in rb), 24) if rb else []
    town = proj(69.764, 53.848)  # Tayynsha town

    outline = [[v for lon, lat in simplify_ring(ring) for v in proj(lon, lat)] for ring in rings]

    # Accounts: companies in the read squares, largest first, with the group flagged.
    rows = []
    for r in table:
        h = owner.get((r['square'], r['field']))
        if h:
            rows.append((h, types.get(h, ''), crops.get((r['square'], r['field']), 'unclear'), float(r['area_ha'])))
    public = {}
    p = f'{district}/company-public.csv'
    if os.path.isfile(p):
        public = {r['holder']: r for r in csv.DictReader(open(p, encoding='utf-8'))}
    accounts = []
    for a in market.summarise(rows):
        if a['type'] != 'company':
            continue
        combine_ha = a['grain_ha'] + a['oilseed_ha']
        accounts.append(dict(name=name(a['holder'], 'company'), group=a['holder'] in in_group, ha=a['field_ha'],
                             grain=a['grain_ha'], oil=a['oilseed_ha'], avg=round(combine_ha / market.HA_PER_COMBINE, 1),
                             modern=[round(combine_ha / 2000, 1), round(combine_ha / 1600, 1)],
                             whole=public.get(a['holder'], {}).get('whole_farm'),
                             src=public.get(a['holder'], {}).get('source'), url=public.get(a['holder'], {}).get('url')))

    # Harvest pace 2026: share of short-season area with a dated or cloud-gap cut by each date.
    grain = [(r['cut_middle'], float(r['area_ha'])) for r in table if crops.get((r['square'], r['field'])) == 'short season']
    total = sum(a for _, a in grain)
    days = [dt.date(int(year), 7, 25) + dt.timedelta(days=i) for i in range(0, 100, 1)]
    pace = [[d.isoformat(), round(sum(a for m, a in grain if m and m <= d.isoformat()) / total, 4)] for d in days]

    data = dict(fields=fields, squares=squares, read_area=read_area, town=town, outline=outline, accounts=accounts, pace=pace,
                totals=dict(fields=len(fields), ha=round(sum(x['ha'] for x in fields)),
                            linked_ha=round(sum(x['ha'] for x in fields if x['h'])),
                            group_ha=round(sum(x['ha'] for x in fields if x['k'] == 'group')),
                            grain_ha=round(total)))
    json.dump(data, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print(f'{len(fields)} fields, {len(accounts)} companies, {os.path.getsize(out) / 1e6:.1f} MB -> {out}')


def simplify_ring(ring):
    pts = [(lon, lat) for lon, lat in ring]
    return pts if len(pts) < 400 else pts[::max(1, len(pts) // 400)]


if __name__ == '__main__':
    main()
