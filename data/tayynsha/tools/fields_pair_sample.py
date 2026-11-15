"""Draws the two hand-check pair samples from the tuning squares' probes, K per square (seeded by square):
J: joins the current method makes across a border lying on a straight line (median line strength >= 0.025);
S: neighbouring field/fallow regions left apart whose NDVI curves are close (3rd highest gap 0.05-0.12).
One chip per pair: A red, B magenta. Appends to pairs-J.csv / pairs-S.csv; chips in chips/pairs/.

python fields_pair_sample.py K SQUARE [SQUARE ...]"""
import csv, os, random, sys
import numpy as np
import fields_sim as S, fields_lib as L, fields_pairs as F, fields_chips as C

K = int(sys.argv[1])
out = L.EXPERIMENT + 'chips/pairs/'
os.makedirs(out, exist_ok=True)
for sq in sys.argv[2:]:
    p = S.load(sq)
    rec = []
    S.merge(p, record=rec)
    lands = L.land(sq, 2026)
    cur = L.labels(sq, 2026)
    J, Sp = [], []
    for a, b, ridge, line, g in rec:
        ys, xs = np.nonzero(p['ws'] == a)
        fr = cur[ys[0], xs[0]] if ys.size else 0
        if line >= 0.025 and fr and lands.get(fr, ('other',))[0] in ('field', 'fallow'):
            J.append((sq, a, b, round(ridge, 3), round(line, 3), round(g, 3), (p['ws'] == a).sum() / 100,
                      (p['ws'] == b).sum() / 100))
    for q in F.final_pairs(sq, p, cur):
        if q['la'] in ('field', 'fallow') and q['lb'] in ('field', 'fallow') and q['k'] >= 20 and 0.05 < q['gap'] <= 0.12:
            Sp.append((sq, q['a'], q['b'], round(q['ridge'], 3), round(q['line'], 3), round(q['gap'], 3),
                       q['aa'], q['ab']))
    rng = random.Random(sq)
    js, ss = rng.sample(J, min(K, len(J))), rng.sample(Sp, min(K, len(Sp)))
    for name, rows, total in (('J', js, len(J)), ('S', ss, len(Sp))):
        path = L.EXPERIMENT + f'pairs-{name}.csv'
        new = not os.path.exists(path)
        with open(path, 'a', newline='') as f:
            w = csv.writer(f)
            if new:
                w.writerow(['square', 'a', 'b', 'ridge', 'line', 'gap', 'area_a', 'area_b', 'population'])
            for r in rows:
                w.writerow([*r, total])
    for r in js:
        C.pair_chip(sq, 2026, cur, p['ws'] == r[1], p['ws'] == r[2],
                    f'J {sq} ws{r[1]}/ws{r[2]} joined: ridge {r[3]} line {r[4]} gap {r[5]}').save(
            out + f'J-{sq}-{r[1]}-{r[2]}.png')
    for r in ss:
        C.pair_chip(sq, 2026, cur, cur == r[1], cur == r[2],
                    f'S {sq} F{r[1]}/F{r[2]} apart: ridge {r[3]} line {r[4]} gap {r[5]}').save(
            out + f'S-{sq}-{r[1]}-{r[2]}.png')
    print(sq, 'J', len(J), 'S', len(Sp), flush=True)
