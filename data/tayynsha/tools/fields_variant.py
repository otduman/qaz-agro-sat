"""Builds a joining variant for squares from their probes and saves it as experiment version sim-TAG.
python fields_variant.py TAG G LMAX SMALL_HA RATIO SQUARE [...]   (use 'none' to switch a limit off)"""
import sys
import fields_sim as S
tag, G, Lm = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
small = None if sys.argv[4] == 'none' else float(sys.argv[4])
ratio = None if sys.argv[5] == 'none' else float(sys.argv[5])
import os
for sq in sys.argv[6:]:
    for year in (2026, 2025):
        if year == 2025 and not os.path.exists(S.PROBE + f'2025/{sq}/meta.txt'):
            continue
        p = S.load(sq, year)
        ids = S.join_pieces(p, S.merge(p), G=G, Lmax=Lm, max_small_ha=small, max_ratio=ratio) if G > 0 else S.merge(p)
        S.save(sq, p, S.finish(p, ids), 'sim-' + tag, year)
    print(sq, flush=True)
