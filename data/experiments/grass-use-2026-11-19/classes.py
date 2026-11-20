"""Grass by use v1 (NOTE.md stage 2): per grass piece (>= 20 ha) of the live layer, class pasture / old field / steppe.
  places   villages and livestock yards (places2.detect)
  pasture  at least PAST_SHARE of the piece within PAST_KM of a place
  oldfield not pasture, rectangle fill >= FILL and >= OLD_HA
  steppe   the rest
python classes.py OUT.csv SQUARE ...   (also usable as a module: classify(sq) -> rows, pieces raster, places raster)"""
import csv, sys
import cv2
import numpy as np
from scipy import ndimage
import mow_probe as P
import places2 as P2
import tracks_probe as TP
L = P.L
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import land_lib as LL
PAST_KM, PAST_SHARE, FILL, OLD_HA = 1.0, 0.5, 0.80, 50   # v2 (v1: 2.0 km, failed blind 8/12)


def fill_of(m):
    cs, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    ring = max(cs, key=cv2.contourArea)[:, 0, :].astype(float) * 10
    return LL.rectangle_fill(np.vstack([ring, ring[:1]]))


def classify(sq):
    vill, yards, _, _ = P2.detect(sq)
    lab = (vill | yards).astype(np.int32)
    g = TP.grass_pieces(sq)
    near = ndimage.distance_transform_edt(lab == 0) * 0.01 <= PAST_KM
    rows = []
    for i, sl in enumerate(ndimage.find_objects(g), 1):
        if sl is None:
            continue
        m = g[sl] == i
        if m.sum() < 2000:
            continue
        share = float(near[sl][m].mean())
        f = fill_of(m)
        ha = m.sum() / 100
        cls = 'pasture' if share >= PAST_SHARE else ('oldfield' if f >= FILL and ha >= OLD_HA else 'steppe')
        rows.append(dict(square=sq, region=i, ha=round(ha, 1), near=round(share, 2), fill=round(f, 3), cls=cls))
    return rows, g, lab


if __name__ == '__main__':
    rows = []
    for sq in sys.argv[2:]:
        r, _, _ = classify(sq)
        rows += r
        tot = sum(x['ha'] for x in r)
        print(sq, {c: f"{sum(1 for x in r if x['cls'] == c)} / {sum(x['ha'] for x in r if x['cls'] == c) / tot:.0%}" for c in ('pasture', 'oldfield', 'steppe')}, flush=True)
    w = csv.DictWriter(open(sys.argv[1], 'w', newline=''), fieldnames=list(rows[0]))
    w.writeheader(); w.writerows(rows)
