"""Probe: tracks inside grass pieces. Track = pixel on a line seen on >= T dates (lines-2026/count.npz, 2025+2026).
Per grass piece (>= 20 ha): share of its inside (>= 30 m from its outline) on tracks. Pictures of the 6 highest and
6 lowest pieces: python tracks_probe.py T SQUARE [SQUARE ...] -> tracks-<sq>.png"""
import csv, json, os, sys
import cv2
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
import mow_probe as P
L, chips = P.L, P.chips
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import lines_first as LF


def grass_pieces(sq):
    ddir = L.DATA + L.district_of(sq)
    reg = L.labels(sq, 2026)
    gone = set()
    if os.path.isfile(f'{ddir}/grass-was-field-2026.csv'):
        gone = {int(r['region']) for r in csv.DictReader(open(f'{ddir}/grass-was-field-2026.csv')) if r['square'] == sq}
    ids = [int(r['region']) for r in csv.DictReader(open(f'{ddir}/grass-2026.csv')) if r['square'] == sq and int(r['region']) not in gone]
    shapes = f'{ddir}/{sq}/other-2026.geojson'
    if not os.path.isfile(shapes):
        return np.where(np.isin(reg, ids), reg, 0)
    want = {f'2026-F{i:04d}': i for i in ids}
    m = np.zeros(reg.shape, np.int32)
    for f in json.load(open(shapes, encoding='utf-8'))['features']:
        i = want.get(f['properties']['id'])
        if i:
            g = f['geometry']
            ring = g['coordinates'][0] if g['type'] == 'Polygon' else max((q[0] for q in g['coordinates']), key=len)
            pts = [L.pixel_of(sq, 2026, lo, la) for lo, la in ring]
            cv2.fillPoly(m, [np.array([[c, r] for r, c in pts], np.int32)], int(i))
    return m


def main():
    T = int(sys.argv[1])
    for sq in sys.argv[2:]:
        ddir = L.DATA + L.district_of(sq)
        g = grass_pieces(sq)
        cnt = LF.load_count(ddir, sq, 2026)
        trk = cnt >= T
        inside = ndimage.binary_erosion(g > 0, iterations=3) & (g > 0)
        ids = np.unique(g[g > 0])
        rows = []
        for i in ids:
            m = g == i
            if m.sum() < 2000:
                continue
            ins = inside & m
            rows.append((trk[ins].mean() if ins.sum() else 0, i, m.sum() / 100))
        rows.sort()
        sh = np.array([r[0] for r in rows])
        print(f'{sq} T={T}: {len(rows)} pieces >= 20 ha; track share p10/50/90: {np.percentile(sh,10):.3f} {np.median(sh):.3f} {np.percentile(sh,90):.3f}')
        ds = [x for x in L.dates(sq, 2026, 0.9) if '2026-07-25' <= x[0] <= '2026-08-25']
        d = max(ds, key=lambda x: x[1])
        tiles = []
        for share, i, ha in rows[-6:][::-1] + rows[:6]:
            ys, xs = np.nonzero(g == i)
            s = int(max(np.ptp(ys), np.ptp(xs)) * 1.2) + 40
            y0, x0 = max(0, (ys.min() + ys.max()) // 2 - s // 2), max(0, (xs.min() + xs.max()) // 2 - s // 2)
            win = (y0, min(g.shape[0], y0 + s), x0, min(g.shape[1], x0 + s))
            rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3], win), 0.2) for b in ('red', 'green', 'blue')])
            m = (g == i)[win[0]:win[1], win[2]:win[3]]
            im = rgb.copy()
            im[ndimage.binary_dilation(m & ~ndimage.binary_erosion(m))] = (80, 200, 255)
            t = Image.fromarray(im).resize((260, 260), Image.BILINEAR)
            ImageDraw.Draw(t).text((4, 4), f'{ha:.0f} ha tracks {share:.0%}', fill=(255, 255, 255))
            tiles.append(t)
        out = Image.new('RGB', (6 * 264, 2 * 264), (0, 0, 0))
        for k, t in enumerate(tiles):
            out.paste(t, ((k % 6) * 264, (k // 6) * 264))
        out.save(f'tracks-{sq}.png')


if __name__ == '__main__':
    main()
