"""Blind check of grass by use v1 (NOTE.md): sample from check-classes.csv (seed 20261124), up to 15 pasture, 10 old
field, 15 steppe pieces, mixed, coded G01..; sheets sheets/g-NN.png (5 rows each: early June, late July, mid
September 2026, August 2025; window 2.5 times the piece; piece outlined cyan; no classes or places shown).
Key g-key.csv (code, square, region, cls, ha). python gcheck.py"""
import csv
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

import mow_probe as P
import tracks_probe as TP

L, chips = P.L, P.chips
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
WIN = [(2026, '06-01', '06-20'), (2026, '07-15', '08-05'), (2026, '09-01', '09-25'), (2025, '08-01', '08-31')]
PANEL = 300


def best(sq, year, lo, hi):
    ds = [x for x in L.dates(sq, year, 0.8) if f'{year}-{lo}' <= x[0] <= f'{year}-{hi}']
    return max(ds, key=lambda x: x[1]) if ds else None


def row(code, sq, m):
    ys, xs = np.nonzero(m)
    s = int(max(np.ptp(ys), np.ptp(xs)) * 2.5) + 60
    cy, cx = (ys.min() + ys.max()) // 2, (xs.min() + xs.max()) // 2
    y0, x0 = int(np.clip(cy - s // 2, 0, max(0, m.shape[0] - s))), int(np.clip(cx - s // 2, 0, max(0, m.shape[1] - s)))
    win = (y0, min(m.shape[0], y0 + s), x0, min(m.shape[1], x0 + s))
    mm = m[win[0]:win[1], win[2]:win[3]]
    edge = mm & ~ndimage.binary_erosion(mm)
    img = Image.new('RGB', (60 + len(WIN) * (PANEL + 4), PANEL + 4), (30, 30, 30))
    ImageDraw.Draw(img).text((4, 6), code, fill=(255, 255, 120))
    for k, (year, lo, hi) in enumerate(WIN):
        x = best(sq, year, lo, hi)
        if x is None:
            continue
        rgb = np.dstack([chips.stretch(L.reflectance(sq, year, x[0], b, x[2], x[3], win), 0.2) for b in ('red', 'green', 'blue')])
        if rgb.shape[:2] != mm.shape:
            continue
        sc = PANEL / max(rgb.shape[:2])
        size = (max(1, int(rgb.shape[1] * sc)), max(1, int(rgb.shape[0] * sc)))
        im = np.array(Image.fromarray(rgb).resize(size, Image.BILINEAR))
        eb = np.array(Image.fromarray(ndimage.binary_dilation(edge).astype(np.uint8) * 255).resize(size, Image.NEAREST)) > 0
        im[eb] = (0, 230, 255)
        p = Image.fromarray(im)
        ImageDraw.Draw(p).text((3, 2), x[0], fill=(255, 255, 255))
        img.paste(p, (60 + k * (PANEL + 4), 2))
    return img


def main():
    src, pre, seed, n_steppe = (sys.argv[1:5] if len(sys.argv) > 4 else ('check-classes.csv', 'g', '20261124', '15'))
    rows = list(csv.DictReader(open(HERE + src)))
    rng = random.Random(int(seed))
    pick = []
    for c, n in (('pasture', 15), ('oldfield', 10), ('steppe', int(n_steppe))):
        rs = [r for r in rows if r['cls'] == c]
        pick += rng.sample(rs, min(n, len(rs)))
    rng.shuffle(pick)
    os.makedirs(HERE + 'sheets', exist_ok=True)
    cache, imgs, key = {}, [], []
    for i, r in enumerate(pick, 1):
        sq = r['square']
        if sq not in cache:
            cache[sq] = TP.grass_pieces(sq)
        code = f'{pre.upper()}{i:02d}'
        imgs.append(row(code, sq, cache[sq] == int(r['region'])))
        key.append([code, sq, r['region'], r['cls'], r['ha']])
        print(code, flush=True)
    for k in range(0, len(imgs), 5):
        part = imgs[k:k + 5]
        sh = Image.new('RGB', (max(i.width for i in part), sum(i.height + 6 for i in part)), (0, 0, 0))
        y = 0
        for im in part:
            sh.paste(im, (0, y))
            y += im.height + 6
        sh.save(HERE + f'sheets/{pre}-{k // 5 + 1:02d}.png')
    with open(HERE + f'{pre}-key.csv', 'w', newline='') as fh:
        csv.writer(fh).writerows([['code', 'square', 'region', 'cls', 'ha']] + key)


if __name__ == '__main__':
    main()
