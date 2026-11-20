"""Tuning look: pieces of each class on a late-July photo, piece blue, places red. python class_sheet.py CSV N OUT.png"""
import csv, random, sys
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
import classes as C
L, chips = C.L, C.P.chips
rows = list(csv.DictReader(open(sys.argv[1])))
n = int(sys.argv[2])
rng = random.Random(7)
pick = []
for c in ('pasture', 'oldfield', 'steppe'):
    rs = [r for r in rows if r['cls'] == c]
    pick += rng.sample(rs, min(n, len(rs)))
cache = {}
tiles = []
for r in pick:
    sq = r['square']
    if sq not in cache:
        _, g, lab = C.classify(sq)
        ds = [x for x in L.dates(sq, 2026, 0.9) if '2026-07-20' <= x[0] <= '2026-08-20']
        cache[sq] = (g, lab, max(ds, key=lambda x: x[1]))
    g, lab, d = cache[sq]
    m = g == int(r['region'])
    ys, xs = np.nonzero(m)
    s = int(max(np.ptp(ys), np.ptp(xs)) * 2.2) + 80
    y0, x0 = max(0, (ys.min() + ys.max()) // 2 - s // 2), max(0, (xs.min() + xs.max()) // 2 - s // 2)
    win = (y0, min(g.shape[0], y0 + s), x0, min(g.shape[1], x0 + s))
    rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3], win), 0.2) for b in ('red', 'green', 'blue')])
    im = rgb.copy()
    mm = m[win[0]:win[1], win[2]:win[3]]
    pl = lab[win[0]:win[1], win[2]:win[3]] > 0
    im[ndimage.binary_dilation(mm & ~ndimage.binary_erosion(mm))] = (80, 200, 255)
    im[ndimage.binary_dilation(pl & ~ndimage.binary_erosion(pl))] = (255, 0, 0)
    t = Image.fromarray(im).resize((300, 300), Image.BILINEAR)
    ImageDraw.Draw(t).text((4, 4), f"{r['cls']} {float(r['ha']):.0f} ha near {r['near']} fill {r['fill']}", fill=(255, 255, 255))
    tiles.append(t)
out = Image.new('RGB', (n * 304, 3 * 304), (0, 0, 0))
for k, t in enumerate(tiles):
    out.paste(t, ((k % n) * 304, (k // n) * 304))
out.save(sys.argv[3])
