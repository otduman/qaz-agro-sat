"""Probe 2 (NOTE.md): mown patches inside the grass layer, stricter sign. python mow_probe2.py SQUARE [SQUARE ...]
Writes probe2-<square>.png (summer photo before / after, patches magenta, grass layer blue, fields yellow)."""
import csv
import datetime as dt
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mow_probe as P  # noqa: E402

L, LS, chips = P.L, P.LS, P.chips
GREEN, DROP, FLOOR, GAP, MIN_HA, SPREAD, FILL = 0.45, 0.18, 0.12, 10, 5, 6, 0.6


def mown(sq, year):
    ds = [x for x in L.dates(sq, year, 0.05) if f'{year}-06-15' <= x[0] <= f'{year}-08-10']
    seq = []
    for d, _, sc, off in ds:
        seq.append((dt.date.fromisoformat(d).timetuple().tm_yday, P.ndvi(sq, year, d, sc, off)))
    best = np.zeros(seq[0][1].shape, np.float32)
    day = np.zeros(best.shape, np.int16)
    for i, (da, va) in enumerate(seq):
        for db, vb in seq[i + 1:]:
            if db - da > GAP:
                break
            drop = va - vb
            hit = (va >= GREEN) & (vb >= FLOOR) & (drop >= DROP) & (drop > best)
            best[hit] = drop[hit]
            day[hit] = db
    return day, len(seq)


def grass_mask(sq, ddir, reg):
    gone = set()
    if os.path.isfile(f'{ddir}/grass-was-field-2026.csv'):
        gone = {int(r['region']) for r in csv.DictReader(open(f'{ddir}/grass-was-field-2026.csv')) if r['square'] == sq}
    ids = [int(r['region']) for r in csv.DictReader(open(f'{ddir}/grass-2026.csv'))
           if r['square'] == sq and int(r['region']) not in gone]
    shapes = f'{ddir}/{sq}/other-2026.geojson'
    if not os.path.isfile(shapes):
        return np.isin(reg, ids)
    import json   # Tayynsha: grass outlines in their own file (site/build/district_data.grass_regions)
    want = {f'2026-F{i:04d}' for i in ids}
    m = np.zeros(reg.shape, np.uint8)
    for f in json.load(open(shapes, encoding='utf-8'))['features']:
        if f['properties']['id'] in want:
            g = f['geometry']
            ring = g['coordinates'][0] if g['type'] == 'Polygon' else max((q[0] for q in g['coordinates']), key=len)
            pts = [L.pixel_of(sq, 2026, lo, la) for lo, la in ring]
            cv2.fillPoly(m, [np.array([[c, r] for r, c in pts], np.int32)], 1)
    return m.astype(bool)


def patches(day, g):
    m = ndimage.binary_closing(day > 0, iterations=1) & g
    lab, n = ndimage.label(m, structure=np.ones((3, 3)))
    keep = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        pm = lab[sl] == i
        if pm.sum() * 0.01 < MIN_HA:
            continue
        dd = day[sl][pm & (day[sl] > 0)]
        q1, q3 = np.percentile(dd, (25, 75))
        if q3 - q1 > SPREAD:
            continue
        cs, _ = cv2.findContours(pm.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        (_, _), (w, h), _ = cv2.minAreaRect(np.vstack(cs))
        if pm.sum() / max(w * h, 1) < FILL:
            continue
        keep.append(i)
    return np.isin(lab, keep), len(keep)


def main():
    for sq in sys.argv[1:]:
        ddir = L.DATA + L.district_of(sq)
        units, _, _ = LS.units_of(ddir, sq, 2026)
        reg = L.labels(sq, 2026)
        g = grass_mask(sq, ddir, reg)
        f = units > 0
        d26, n26 = mown(sq, 2026)
        d25, n25 = mown(sq, 2025)
        k26, c26 = patches(d26, g)
        k25, c25 = patches(d25, g) if d25.shape == g.shape else (np.zeros(g.shape, bool), 0)
        both = k26 & ndimage.binary_dilation(k25, iterations=2)
        dd = d26[k26 & (d26 > 0)]
        print(f'{sq}: grass {g.sum() / 100:,.0f} ha; dates {n26}/{n25}; mown pixels in grass 2026 {((d26 > 0) & g).sum() / 100:,.0f} ha;'
              f' patches 2026 {c26} = {k26.sum() / 100:,.0f} ha (days {np.percentile(dd, 10) if dd.size else 0:.0f}-'
              f'{np.percentile(dd, 90) if dd.size else 0:.0f}); 2025 {c25} = {k25.sum() / 100:,.0f} ha; both years {both.sum() / 100:,.0f} ha', flush=True)
        s = 600
        acc = ndimage.uniform_filter(k26.astype(np.float32), s)
        cy, cx = np.unravel_index(np.argmax(acc), acc.shape)
        y0, x0 = int(np.clip(cy - s // 2, 0, g.shape[0] - s)), int(np.clip(cx - s // 2, 0, g.shape[1] - s))
        win = (y0, y0 + s, x0, x0 + s)
        dds = L.dates(sq, 2026, 0.9)
        mid = int(np.median(dd)) if dd.size else 200
        pics = []
        for lo, hi in ((mid - 25, mid - 3), (mid + 2, mid + 25)):
            a = (dt.date(2026, 1, 1) + dt.timedelta(days=lo - 1)).isoformat()
            b = (dt.date(2026, 1, 1) + dt.timedelta(days=hi - 1)).isoformat()
            c = [x for x in dds if a <= x[0] <= b]
            if not c:
                continue
            d = max(c, key=lambda x: x[1])
            rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], bnd, d[2], d[3], win), 0.2) for bnd in ('red', 'green', 'blue')])
            im = rgb.copy()
            for m, col in ((g, (80, 200, 255)), (f, (255, 230, 0)), (k26, (255, 0, 255))):
                w = m[y0:y0 + s, x0:x0 + s]
                e = w & ~ndimage.binary_erosion(w)
                im[ndimage.binary_dilation(e) if col == (255, 0, 255) else e] = col
            t = Image.fromarray(im)
            ImageDraw.Draw(t).text((4, 4), d[0], fill=(255, 255, 255))
            pics.append(t)
        if pics:
            sh = Image.new('RGB', (len(pics) * (s + 6), s), (0, 0, 0))
            for i, t in enumerate(pics):
                sh.paste(t, (i * (s + 6), 0))
            sh.save(P.HERE + f'probe2-{sq}.png')


if __name__ == '__main__':
    main()
