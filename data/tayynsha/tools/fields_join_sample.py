"""Samples joins made by a second joining pass (fields_sim.join_pieces) and renders them: A red, B magenta,
outlines of the current map in yellow. python fields_join_sample.py TAG G LMAX K SQUARE [...]"""
import csv, os, random, sys
import numpy as np
import fields_sim as S, fields_lib as L, fields_chips as C

tag, G, Lm, K = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4])
out = L.EXPERIMENT + f'chips/joins-{tag}/'
os.makedirs(out, exist_ok=True)
path = L.EXPERIMENT + f'joins-{tag}.csv'
new = not os.path.exists(path)
f = open(path, 'a', newline='')
w = csv.writer(f)
if new:
    w.writerow(['square', 'chip', 'ridge', 'line', 'gap', 'crack', 'area_a', 'area_b', 'population', 'verdict'])
for sq in sys.argv[5:]:
    p = S.load(sq)
    rec = []
    S.join_pieces(p, S.merge(p), G=G, Lmax=Lm, record=rec)
    cur = L.labels(sq, 2026)
    lands = L.land(sq, 2026)
    # keep joins where at least one side is mostly field/fallow on the current map
    def fieldish(wsids):
        m = np.isin(p['ws'], wsids) & (cur > 0)
        if not m.any():
            return False
        r = np.bincount(cur[m]).argmax()
        return lands.get(r, ('other',))[0] in ('field', 'fallow')
    keep = [r for r in rec if fieldish(r[8]) or fieldish(r[9])]
    rng = random.Random(sq + tag)
    for i, r in enumerate(rng.sample(keep, min(K, len(keep)))):
        name = f'{sq}-{i + 1:02d}.png'
        C.pair_chip(sq, 2026, cur, np.isin(p['ws'], r[8]), np.isin(p['ws'], r[9]),
                    f'{tag} {sq} #{i + 1}: ridge {r[2]:.2f} line {r[3]:.3f} gap {r[4]:.3f} areas {r[6]:.0f}/{r[7]:.0f}').save(out + name)
        w.writerow([sq, name, round(r[2], 3), round(r[3], 3), round(r[4], 3), r[5], r[6], r[7], len(keep), ''])
    print(sq, len(rec), 'joins', len(keep), 'touching fields', flush=True)
f.close()
