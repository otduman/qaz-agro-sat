"""Blind sheets (NOTE.md): 30 changed patches (seed 20261121, at most 3 per square), each a row of 4 panels:
photo 1 with border A, photo 1 with border B, photo 2 with A, photo 2 with B (A/B = old/snapped in random order).
-> sheets/s-NN.png (5 rows each), key.csv (code, square, patch, A, B, dates). python sheets.py"""
import csv
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import fields_lib as L  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
Y, PANEL, MARGIN, MINWIN = 2026, 300, 25, 90


def stretch(r, top=0.2):
    return np.nan_to_num(np.clip(r / top, 0, 1) ** 0.8 * 255).astype(np.uint8)


def photo(sq, d, sc, off, win):
    return np.dstack([stretch(L.reflectance(sq, Y, d, b, sc, off, win)) for b in ('red', 'green', 'blue')])


def clear_in(sq, d, sc, off, win):
    return 1 - np.isnan(L.reflectance(sq, Y, d, 'red', sc, off, win)).mean()


def panel(rgb, lab, tag):
    img = rgb.copy()
    img[L.boundaries(lab)] = (0, 230, 255)
    k = PANEL / max(img.shape[:2])
    p = Image.fromarray(img).resize((int(img.shape[1] * k), int(img.shape[0] * k)), Image.NEAREST)
    ImageDraw.Draw(p).text((4, 4), tag, fill=(255, 255, 0))
    return p


def main():
    rows = list(csv.DictReader(open(HERE + 'patches.csv')))
    rng = random.Random(20261121)
    rng.shuffle(rows)
    pick, per = [], {}
    for r in rows:
        if per.get(r['square'], 0) < 3:
            pick.append(r)
            per[r['square']] = per.get(r['square'], 0) + 1
        if len(pick) == 30:
            break
    os.makedirs(HERE + 'sheets', exist_ok=True)
    key, imgs = [], []
    for n, r in enumerate(pick, 1):
        sq, i = r['square'], int(r['patch'])
        old, new = np.load(HERE + f'snap/{sq}-old.npy'), np.load(HERE + f'snap/{sq}-new.npy')
        comp, _ = ndimage.label(old != new)
        sl = ndimage.find_objects(comp)[i - 1]
        cy, cx = (sl[0].start + sl[0].stop) // 2, (sl[1].start + sl[1].stop) // 2
        half = max(MINWIN, sl[0].stop - sl[0].start + 2 * MARGIN, sl[1].stop - sl[1].start + 2 * MARGIN) // 2
        win = (max(0, cy - half), min(old.shape[0], cy + half), max(0, cx - half), min(old.shape[1], cx + half))
        cands = [x for x in L.dates(sq, Y, 0.3) if f'{Y}-06-15' <= x[0] <= f'{Y}-08-31']
        early = [x for x in cands if x[0] <= f'{Y}-07-31']
        late = [x for x in cands if x[0] >= f'{Y}-08-01']
        chosen = []
        for group in (early, late):
            best = max(group, key=lambda x: clear_in(sq, x[0], x[2], x[3], win), default=None)
            if best is not None and clear_in(sq, best[0], best[2], best[3], win) >= 0.9:
                chosen.append(best)
        a_is_new = rng.random() < 0.5
        va, vb = (new, old) if a_is_new else (old, new)
        y0, y1, x0, x1 = win
        code = f'S{n:02d}'
        row = Image.new('RGB', (60 + 4 * (PANEL + 4), PANEL + 4), (25, 25, 25))
        ImageDraw.Draw(row).text((4, 6), code, fill=(255, 255, 120))
        k = 0
        for d, _, sc, off in chosen:
            rgb = photo(sq, d, sc, off, win)
            for tag, v in (('A ' + d[5:], va), ('B ' + d[5:], vb)):
                row.paste(panel(rgb, v[y0:y1, x0:x1], tag), (60 + k * (PANEL + 4), 2))
                k += 1
        imgs.append(row)
        key.append([code, sq, i, 'snapped' if a_is_new else 'old', 'old' if a_is_new else 'snapped',
                    ' '.join(x[0] for x in chosen)])
        print(code, sq, len(chosen), flush=True)
    for s in range(0, len(imgs), 5):
        part = imgs[s:s + 5]
        sh = Image.new('RGB', (max(i.width for i in part), sum(i.height + 8 for i in part)), (0, 0, 0))
        y = 0
        for im in part:
            sh.paste(im, (0, y))
            y += im.height + 8
        sh.save(HERE + f'sheets/s-{s // 5 + 1:02d}.png')
    with open(HERE + 'key.csv', 'w', newline='') as fh:
        csv.writer(fh).writerows([['code', 'square', 'patch', 'A', 'B', 'dates']] + key)


if __name__ == '__main__':
    main()
