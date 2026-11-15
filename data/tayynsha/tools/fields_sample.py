"""Draws the hand-check sample of field regions from the tuning squares' current 2026 maps: SIZE regions with
probability in proportion to area (for area shares) and SIZE with equal probability (for counts), fixed seed.
Writes sample.csv in the experiment folder.

python fields_sample.py [SIZE]
"""
import csv
import random
import sys

import fields_lib as L

size = int(sys.argv[1]) if len(sys.argv) > 1 else 30
rows = []
for sq in L.TUNING:
    for r, (land, area) in L.land(sq, 2026).items():
        if land in ('field', 'fallow'):
            rows.append((sq, r, land, area))
rng = random.Random(20261112)
by_area = []
pool = list(rows)
while len(by_area) < size:
    pick = rng.choices(pool, weights=[p[3] for p in pool])[0]
    if pick not in by_area:
        by_area.append(pick)
by_count = []
while len(by_count) < size:
    pick = rng.choice(rows)
    if pick not in by_count and pick not in by_area:
        by_count.append(pick)
out = L.EXPERIMENT + 'sample.csv'
with open(out, 'w', newline='', encoding='utf-8') as f:
    w = csv.writer(f)
    w.writerow(['n', 'draw', 'square', 'region', 'land', 'area_ha'])
    for i, (sq, r, land, area) in enumerate(by_area):
        w.writerow([i + 1, 'by_area', sq, r, land, area])
    for i, (sq, r, land, area) in enumerate(by_count):
        w.writerow([size + i + 1, 'by_count', sq, r, land, area])
print(out, len(rows), 'field and fallow regions,', sum(p[3] for p in rows), 'ha')
