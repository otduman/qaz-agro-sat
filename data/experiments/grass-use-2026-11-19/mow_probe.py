"""Probe 1 (NOTE.md): pixels mown in June-July (NDVI <= 0.28 on a clear date, >= 0.40 on a clear date at most 10 days
before), 2026 and 2025, on one square. Prints where the mown land lies (grass layer, other land outside fields,
fields) and writes probe-<square>.png: mown patches (>= 5 ha) on a photo before and after.
python mow_probe.py SQUARE"""
import csv
import datetime as dt
import os
import sys

import numpy as np
import tifffile
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/experiments/land-labels-2026-11-13/scripts')
import chips  # noqa: E402
import fields_lib as L  # noqa: E402
import line_splits as LS  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
GREEN, PALE, GAP, MIN_HA = 0.40, 0.28, 10, 5


def ndvi(sq, year, d, sc, off):
    red = L.reflectance(sq, year, d, 'red', sc, off)
    nir = L.reflectance(sq, year, d, 'nir', sc, off)
    scl = tifffile.imread(L.square_dir(sq) + f'season-{year}/{d}/scl.tif')
    scl = np.kron(scl, np.ones((2, 2), np.uint8))[:red.shape[0], :red.shape[1]]
    v = (nir - red) / np.maximum(nir + red, 1e-6)
    v[~np.isin(scl, (4, 5)) | np.isnan(red) | np.isnan(nir)] = np.nan
    return v


def mown(sq, year):
    """Day of year of the first mowing per pixel (0 = not mown), 1 June - 31 July."""
    ds = [x for x in L.dates(sq, year, 0.05) if f'{year}-06-01' <= x[0] <= f'{year}-07-31']
    last = None
    out = None
    for d, _, sc, off in ds:
        day = dt.date.fromisoformat(d).timetuple().tm_yday
        v = ndvi(sq, year, d, sc, off)
        if last is None:
            last = np.full(v.shape, -999, np.int16)
            out = np.zeros(v.shape, np.int16)
        hit = (v <= PALE) & (day - last <= GAP) & (out == 0)
        out[hit] = day
        last[v >= GREEN] = day
    return out, len(ds)


def main():
    sq = sys.argv[1]
    ddir = L.DATA + L.district_of(sq)
    units, members, keep = LS.units_of(ddir, sq, 2026)
    reg = L.labels(sq, 2026)
    grass = set()
    p = f'{ddir}/grass-2026.csv'
    gone = set()
    if os.path.isfile(f'{ddir}/grass-was-field-2026.csv'):
        gone = {int(r['region']) for r in csv.DictReader(open(f'{ddir}/grass-was-field-2026.csv')) if r['square'] == sq}
    for r in csv.DictReader(open(p)):
        if r['square'] == sq and int(r['region']) not in gone:
            grass.add(int(r['region']))
    g = np.isin(reg, list(grass))
    f = units > 0
    m26, n26 = mown(sq, 2026)
    m25, n25 = mown(sq, 2025)
    same = m25.shape == m26.shape
    ha = 0.01
    print(sq, 'dates Jun-Jul', n26, '(2026)', n25, '(2025)', 'grids match' if same else 'grids differ')
    for name, m in (('2026', m26 > 0), ('2025', m25 > 0)):
        if m.shape != f.shape:
            print(name, 'skipped (grid)')
            continue
        print(f'  mown {name}: {m.sum() * ha:,.0f} ha; in fields {(m & f).sum() * ha:,.0f}; in grass layer '
              f'{(m & g).sum() * ha:,.0f} of {g.sum() * ha:,.0f} ha grass; other outside fields {(m & ~f & ~g).sum() * ha:,.0f}')
    both = (m26 > 0) & (m25 > 0) if same else np.zeros(m26.shape, bool)
    print(f'  mown both years: {both.sum() * ha:,.0f} ha; of it in grass layer {(both & g).sum() * ha:,.0f}')
    # patches outside fields, 2026
    mm = ndimage.binary_closing(m26 > 0, iterations=1) & ~f
    lab, n = ndimage.label(mm)
    size = np.bincount(lab.ravel())
    big = [i for i in range(1, n + 1) if size[i] * ha >= MIN_HA]
    keep_ = np.isin(lab, big)
    days = m26[keep_ & (m26 > 0)]
    print(f'  patches >= {MIN_HA} ha outside fields (2026): {len(big)}, {keep_.sum() * ha:,.0f} ha; '
          f'in grass layer {(keep_ & g).sum() * ha:,.0f} ha; mowing days (median, 10-90%): '
          f'{np.median(days) if days.size else 0:.0f} {np.percentile(days, 10) if days.size else 0:.0f}-'
          f'{np.percentile(days, 90) if days.size else 0:.0f}')
    if same:
        bl = [i for i in big if (both[lab == i]).mean() >= 0.3]
        print(f'  of them with >= 30% mown in 2025 too: {len(bl)}')
    # picture: the 6 km window holding most patch area, photo early July vs early August
    s = 600
    acc = ndimage.uniform_filter(keep_.astype(np.float32), s)
    cy, cx = np.unravel_index(np.argmax(acc), acc.shape)
    y0, x0 = int(np.clip(cy - s // 2, 0, keep_.shape[0] - s)), int(np.clip(cx - s // 2, 0, keep_.shape[1] - s))
    win = (y0, y0 + s, x0, x0 + s)
    ds = L.dates(sq, 2026, 0.9)
    pics = []
    for lo, hi in (('2026-06-10', '2026-06-30'), ('2026-07-20', '2026-08-10')):
        c = [x for x in ds if lo <= x[0] <= hi]
        if not c:
            continue
        d = max(c, key=lambda x: x[1])
        rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3], win), 0.2) for b in ('red', 'green', 'blue')])
        w = keep_[y0:y0 + s, x0:x0 + s]
        e = w & ~ndimage.binary_erosion(w)
        fe = f[y0:y0 + s, x0:x0 + s]
        fe = fe & ~ndimage.binary_erosion(fe)
        ge = g[y0:y0 + s, x0:x0 + s]
        ge = ge & ~ndimage.binary_erosion(ge)
        im = rgb.copy()
        im[ge] = (80, 200, 255)
        im[fe] = (255, 230, 0)
        im[ndimage.binary_dilation(e)] = (255, 0, 255)
        t = Image.fromarray(im)
        ImageDraw.Draw(t).text((4, 4), d[0], fill=(255, 255, 255))
        pics.append(t)
    sh = Image.new('RGB', (len(pics) * (s + 6), s), (0, 0, 0))
    for i, t in enumerate(pics):
        sh.paste(t, (i * (s + 6), 0))
    sh.save(HERE + f'probe-{sq}.png')
    print('  picture window rows', win[:2], 'cols', win[2:])


if __name__ == '__main__':
    main()
