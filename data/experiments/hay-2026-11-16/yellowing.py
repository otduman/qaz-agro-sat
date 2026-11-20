"""Largest share of a region's pixels that turn from green to pale between two clear dates <= GAPd apart in
1 Jun - 10 Aug (see NOTE.md). python yellowing.py SAMPLE_KEY.csv LABELS.csv DISTRICT_DIR -> prints per label."""
import csv, datetime as dt, os, sys, collections
import numpy as np, tifffile
G, P, GAP, CLEAR = 0.40, 0.28, 10, 0.6
key = {r['code']: r for r in csv.DictReader(open(sys.argv[1], encoding='utf-8'))}
lab = {r['code']: r['label'] for r in csv.DictReader(open(sys.argv[2], encoding='utf-8'))}
ddir = sys.argv[3]
by_sq = collections.defaultdict(list)
for c, k in key.items():
    if c in lab: by_sq[k['square']].append((c, int(k['region'])))
res = collections.defaultdict(list)
for sq, items in sorted(by_sq.items()):
    d = f'{ddir}/{sq}'
    labr = tifffile.imread(f'{d}/fields-2026/fields-2026.tif')
    ids = {r: labr == r for _, r in items}
    dates = sorted(x for x in os.listdir(f'{d}/season-2026') if x[:4] == '2026' and dt.date(2026, 6, 1) <= dt.date.fromisoformat(x) <= dt.date(2026, 8, 10))
    nd = {}
    for x in dates:
        p = f'{d}/season-2026/{x}'
        if not os.path.isfile(p + '/red.tif'): continue
        red = tifffile.imread(p + '/red.tif').astype(np.float32); nir = tifffile.imread(p + '/nir.tif').astype(np.float32)
        scl = np.kron(tifffile.imread(p + '/scl.tif'), np.ones((2, 2), np.uint8))[:red.shape[0], :red.shape[1]]
        ok = np.isin(scl, (4, 5)) & (red > 0)
        r_, n_ = red * 1e-4 - 0.1, nir * 1e-4 - 0.1
        v = np.where(ok, (n_ - r_) / np.maximum(n_ + r_, 1e-6), np.nan)
        nd[x] = v
    keys = sorted(nd)
    for c, r in items:
        m = ids[r]; best = 0.0
        for i, a in enumerate(keys):
            for b in keys[i + 1:]:
                if (dt.date.fromisoformat(b) - dt.date.fromisoformat(a)).days > GAP: break
                va, vb = nd[a][m], nd[b][m]
                both = ~np.isnan(va) & ~np.isnan(vb)
                if both.mean() < CLEAR: continue
                share = ((va >= G) & (vb <= P) & both).sum() / both.sum()
                best = max(best, share)
        res[lab[c]].append((round(best, 3), c))
for k, v in sorted(res.items()):
    s = sorted(x for x, _ in v)
    print(k, len(v), 'median', s[len(s)//2] if s else '-', 'p90', s[int(len(s)*0.9)] if s else '-', 'max', s[-1] if s else '-')
    if k == 'hay': print('  hay values', sorted(v))
import json; json.dump({k: [float(x) for x, _ in v] for k, v in res.items()}, open("yellowing-esil.json", "w"))
