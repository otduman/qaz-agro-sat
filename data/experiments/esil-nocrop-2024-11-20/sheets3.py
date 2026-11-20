"""Fresh blind sheets for crop seen v3 (seed 20261122, flips-v3.csv, not the 80 dev fields). Was: crop seen v2 (NOTE.md): 30 flipped + 10 kept fields over all district-seasons, seed 20261121,
not the 40 dev fields; every date with the field >= 40% clear, cloud pixels white. Was: 30 Esil 2024 'no crop seen' fields + 10 'dated' decoys (>= 50 ha, seed 20261120), coded
N01..N40, each a row of clear photos 1 May - 30 Sep 2024, field outlined. sheets/n-NN.png (4 rows each), key n-key.csv.
python sheets.py"""
import csv
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/experiments/land-labels-2026-11-13/scripts')
import chips  # noqa: E402
import fields_lib as L  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
D = 'C:/Programming Projects/qaz-agro-sat/data/esil-akmola/'
PANEL, MAXP = 150, 16
MINCLEAR = 0.40


def row(code, sq, region, Y):
    lab = L.labels(sq, Y)
    m = lab == region
    ys, xs = np.nonzero(m)
    s = int(max(np.ptp(ys), np.ptp(xs)) * 1.5) + 30
    cy, cx = (ys.min() + ys.max()) // 2, (xs.min() + xs.max()) // 2
    y0, x0 = max(0, cy - s // 2), max(0, cx - s // 2)
    win = (y0, min(m.shape[0], y0 + s), x0, min(m.shape[1], x0 + s))
    mm = m[win[0]:win[1], win[2]:win[3]]
    edge = ndimage.binary_dilation(mm & ~ndimage.binary_erosion(mm))
    panels, n_clear = [], 0
    for d, _, sc, off in L.dates(sq, Y, 0.05):
        if not f'{Y}-05-01' <= d <= f'{Y}-09-30':
            continue
        r = L.reflectance(sq, Y, d, 'red', sc, off, win)
        if 1 - np.isnan(r[mm]).mean() < MINCLEAR:
            continue
        n_clear += 1
        rgb = np.dstack([chips.stretch(L.reflectance(sq, Y, d, b, sc, off, win), 0.2) for b in ('red', 'green', 'blue')])
        rgb[np.isnan(r)] = (255, 255, 255)
        rgb[edge] = (0, 230, 255)
        k = PANEL / max(rgb.shape[:2])
        p = Image.fromarray(rgb).resize((max(1, int(rgb.shape[1] * k)), max(1, int(rgb.shape[0] * k))), Image.BILINEAR)
        ImageDraw.Draw(p).text((2, 2), d[5:], fill=(255, 255, 255))
        panels.append(p)
    if len(panels) > MAXP:
        idx = np.linspace(0, len(panels) - 1, MAXP).round().astype(int)
        panels = [panels[i] for i in sorted(set(idx))]
    img = Image.new('RGB', (50 + max(1, len(panels)) * (PANEL + 3), PANEL + 4), (30, 30, 30))
    ImageDraw.Draw(img).text((4, 6), code, fill=(255, 255, 120))
    for k, p in enumerate(panels):
        img.paste(p, (50 + k * (PANEL + 3), 2))
    return img, n_clear


def main():
    dev = {(r['square'], r['field']) for f in ('n-key.csv', 'm-key.csv') for r in csv.DictReader(open(HERE + f))}
    rows = [r for r in csv.DictReader(open(HERE + 'flips-v3.csv')) if float(r['ha']) >= 50 and (r['square'], r['field']) not in dev]
    rng = random.Random(20261122)
    flip = rng.sample([r for r in rows if r['v3'] == '1'], 30)
    kept = rng.sample([r for r in rows if r['v3'] == '0'], 10)
    items = [('flip', r) for r in flip] + [('kept', r) for r in kept]
    rng.shuffle(items)
    os.makedirs(HERE + 'sheets3', exist_ok=True)
    imgs, key = [], []
    for i, (kind, r) in enumerate(items, 1):
        code = f'K{i:02d}'
        img, n = row(code, r['square'], int(r['field'].split('-F')[1]), int(r['year']))
        imgs.append(img)
        key.append([code, kind, r['district'], r['year'], r['square'], r['field'], r['ha'], n])
        print(code, flush=True)
    for k in range(0, len(imgs), 4):
        part = imgs[k:k + 4]
        sh = Image.new('RGB', (max(i.width for i in part), sum(i.height + 6 for i in part)), (0, 0, 0))
        y = 0
        for im in part:
            sh.paste(im, (0, y))
            y += im.height + 6
        sh.save(HERE + f'sheets3/k-{k // 4 + 1:02d}.png')
    with open(HERE + 'k-key.csv', 'w', newline='') as fh:
        csv.writer(fh).writerows([['code', 'kind', 'district', 'year', 'square', 'field', 'ha', 'photos']] + key)


if __name__ == '__main__':
    main()
