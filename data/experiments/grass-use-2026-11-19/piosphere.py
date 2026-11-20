"""Probe: does grass get greener with distance from livestock places (density detector)? Median NDVI of grass pixels
per distance ring, on the clearest date of 25 Jul - 25 Aug and of 10 Jun - 30 Jun. python piosphere.py SQ ..."""
import sys
import numpy as np
from scipy import ndimage
import mow_probe as P
import places_overview as PO
import tracks_probe as TP
L = P.L
RINGS = [(0, 0.5), (0.5, 1), (1, 2), (2, 3), (3, 5), (5, 99)]


def best(sq, lo, hi):
    ds = [x for x in L.dates(sq, 2026, 0.9) if lo <= x[0] <= hi]
    d = max(ds, key=lambda x: x[1])
    return P.ndvi(sq, 2026, d[0], d[2], d[3]), d[0]


for sq in sys.argv[1:]:
    lab, built, farmland, _ = PO.detect(sq)
    g = TP.grass_pieces(sq) > 0
    dist = ndimage.distance_transform_edt(lab == 0) * 0.01
    out = []
    for lo, hi, name in (('2026-06-10', '2026-06-30', 'Jun'), ('2026-07-25', '2026-08-25', 'Aug')):
        v, d = best(sq, lo, hi)
        row = []
        for a, b in RINGS:
            m = g & (dist >= a) & (dist < b) & ~np.isnan(v)
            row.append(f'{np.median(v[m]):.3f}({m.sum() // 100})' if m.sum() > 500 else '  -  ')
        out.append(f'{name} {d}: ' + ' '.join(row))
    print(sq, 'rings km', RINGS); [print('   ', o) for o in out]
