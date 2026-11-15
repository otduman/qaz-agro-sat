"""Samples field/fallow regions of version NEW that differ from version OLD (best-matching OLD region covers under
95% of it, or it under 95% of that region), with a chance in proportion to area, and renders NEW over OLD chips.
python fields_changed.py NEW OLD K SEED SQUARE [...]"""
import csv, os, random, sys
import numpy as np
import fields_lib as L, fields_chips as C

new_v, old_v, K, seed = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
old_arg = None if old_v == 'current' else old_v
rows = []
for sq in sys.argv[5:]:
    ln, lo = L.labels(sq, 2026, new_v), L.labels(sq, 2026, old_arg)
    lands = L.land(sq, 2026, new_v)
    m = (ln > 0) & (lo > 0)
    key = ln[m].astype(np.int64) * 70000 + lo[m]
    uk, cnt = np.unique(key, return_counts=True)
    an = np.bincount(ln.ravel()); ao = np.bincount(lo.ravel())
    best = {}
    for k, c in zip(uk, cnt):
        a, b = int(k // 70000), int(k % 70000)
        if c > best.get(a, (0, 0))[0]:
            best[a] = (c, b)
    for r, (land, area) in lands.items():
        if land not in ('field', 'fallow') or r not in best:
            continue
        c, b = best[r]
        if c < 0.95 * an[r] or c < 0.95 * ao[b]:
            rows.append((sq, r, area))
print(len(rows), 'changed field regions,', round(sum(r[2] for r in rows)), 'ha')
rng = random.Random(seed)
pick = []
while len(pick) < min(K, len(rows)):
    x = rng.choices(rows, weights=[r[2] for r in rows])[0]
    if x not in pick:
        pick.append(x)
out = L.EXPERIMENT + f'chips/changed-{new_v}-{seed}/'
os.makedirs(out, exist_ok=True)
with open(out + 'picked.csv', 'w', newline='') as f:
    w = csv.writer(f); w.writerow(['n', 'square', 'region', 'area_ha', 'verdict'])
    for i, (sq, r, a) in enumerate(pick):
        w.writerow([i + 1, sq, r, a, ''])
ims = []
for i, (sq, r, a) in enumerate(pick):
    ims.append(C.chip(sq, 2026, r, new_v, old_v, title=f'#{i+1} {sq} new F{r} {a} ha (top: {new_v}, bottom: {old_v})'))
    if len(ims) == 2 or i == len(pick) - 1:
        C.stack_vertically(ims).save(out + f'view-{i // 2 + 1:02d}.png'); ims = []
