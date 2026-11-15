"""Photo check for crop groups: true-colour cut-outs of sample fields from each group, on the clearest date of
mid-July (flowering) and of late August (grain ripe or cut, oilseeds often still green). The field is outlined.

Run from the repository root:
python data/tayynsha/tools/photo_check.py data/tayynsha 2026 out.png
Samples PER_GROUP fields of at least 50 ha per group at random (fixed seed) from the joined district table.
Throwaway experiment code.
"""
import csv
import random
import sys
from collections import defaultdict

import numpy as np
import tifffile
from PIL import Image, ImageDraw

PER_GROUP = 5
GROUPS = ['short season', 'long season', 'yellow flowers', 'perennial grass']
WINDOWS = (('07-05', '07-25', 'mid-July'), ('08-20', '09-10', 'late Aug'))
TILE = 220
MARGIN = 15  # pixels (150 m) around the field


def best_date(colour, field, lo, hi):
    best = None
    for r in colour.get(field, []):
        if lo <= r[0][5:] <= hi and (best is None or r[1] > best[1]):
            best = r
    return best[0] if best and best[1] >= 0.9 else None


def cutout(square, year, date, labels, region, index):
    ys, xs = np.nonzero(labels == region)
    y0, y1 = max(ys.min() - MARGIN, 0), min(ys.max() + MARGIN + 1, labels.shape[0])
    x0, x1 = max(xs.min() - MARGIN, 0), min(xs.max() + MARGIN + 1, labels.shape[1])
    scale, offset = index[date]
    rgb = []
    for b in ('red', 'green', 'blue'):
        raw = tifffile.imread(f'{square}/season-{year}/{date}/{b}.tif')[y0:y1, x0:x1].astype(np.float64)
        rgb.append(np.clip((raw * scale + offset) / 0.25 * 255, 0, 255))
    img = np.dstack(rgb).astype(np.uint8)
    m = labels[y0:y1, x0:x1] == region
    edge = m & ~(np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1))
    img[edge] = (255, 255, 255)
    im = Image.fromarray(img)
    im.thumbnail((TILE, TILE))
    return im


def main():
    district, year, out = sys.argv[1], sys.argv[2], sys.argv[3]
    table = [r for r in csv.DictReader(open(f'{district}/district-harvest-{year}.csv', encoding='utf-8'))
             if r['land'] == 'field' and float(r['area_ha']) >= 50]
    groups = {}
    for sq in {r['square'] for r in table}:
        for r in csv.DictReader(open(f'{district}/{sq}/crops-{year}.csv', encoding='utf-8')):
            groups[(sq, r['field'])] = r['group']
    by = defaultdict(list)
    for r in table:
        by[groups.get((r['square'], r['field']))].append(r)
    rng = random.Random(int(year))
    rows = []
    for g in GROUPS:
        pool = sorted(by.get(g, []), key=lambda r: (r['square'], r['field']))
        rows += [(g, r) for r in rng.sample(pool, min(PER_GROUP, len(pool)))]

    sheet = Image.new('RGB', (2 * TILE + 260, len(rows) * (TILE + 6) + 30), (30, 30, 30))
    draw = ImageDraw.Draw(sheet)
    for j, (_, _, name) in enumerate(WINDOWS):
        draw.text((260 + j * TILE + 6, 8), name, fill=(230, 230, 230))
    cache = {}
    for i, (g, r) in enumerate(rows):
        sq = f'{district}/{r["square"]}'
        if sq not in cache:
            colour = defaultdict(list)
            for c in csv.DictReader(open(f'{sq}/colour-{year}.csv', encoding='utf-8')):
                colour[c['field']].append((c['date'], float(c['clear_share'])))
            index = {d['date']: (float(d['scale']), float(d['offset']))
                     for d in csv.DictReader(open(f'{sq}/season-{year}/index.csv', encoding='utf-8'))}
            labels = tifffile.imread(f'{sq}/fields-{year}/fields-{year}.tif')
            cache = {sq: (colour, index, labels)}
        colour, index, labels = cache[sq]
        top = 30 + i * (TILE + 6)
        draw.text((8, top + 8), g, fill=(255, 220, 120))
        draw.text((8, top + 26), f'{r["square"]} {r["field"][5:]}', fill=(230, 230, 230))
        draw.text((8, top + 44), f'{float(r["area_ha"]):.0f} ha, {r["status"]}', fill=(230, 230, 230))
        if r['cut_middle']:
            draw.text((8, top + 62), f'cut about {r["cut_middle"][5:]}', fill=(230, 230, 230))
        region = int(r['field'].split('-F')[1])
        for j, (lo, hi, _) in enumerate(WINDOWS):
            date = best_date(colour, r['field'], lo, hi)
            if date:
                sheet.paste(cutout(sq, year, date, labels, region, index), (260 + j * TILE, top))
                draw.text((260 + j * TILE + 4, top + TILE - 16), date[5:], fill=(255, 255, 255))
    sheet.save(out)
    print(f'{len(rows)} fields -> {out}')


if __name__ == '__main__':
    main()
