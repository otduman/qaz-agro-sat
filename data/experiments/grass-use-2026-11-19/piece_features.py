"""Per grass piece (>= 20 ha) features for the pasture rule (probe/tuning): track share (inside, lines seen on >= 8
dates), distance to the nearest livestock place (km), rectangle fill, hectares. python piece_features.py OUT.csv SQ..."""
import csv, sys
import cv2
import numpy as np
from scipy import ndimage
import mow_probe as P
import places_probe as PP
import tracks_probe as TP
L = P.L
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import land_lib as LL
import lines_first as LF
T, MIN_PLACE_PX = 8, 30


def places(sq):
    share, clear = PP.never_green(sq)
    wet, _ = PP.water(sq, share.shape)
    reg = L.labels(sq, 2026)
    land = L.land(sq, 2026)
    farmland = np.isin(reg, [r for r, (k, ha) in land.items() if k == 'field' or (k == 'fallow' and ha >= 30)])
    built = ndimage.binary_closing((share < PP.NEVER) & (clear >= 5) & ~wet & ~farmland, iterations=1) & ~farmland
    lab, n = ndimage.label(built, structure=np.ones((3, 3)))
    size = np.bincount(lab.ravel())
    return (size[lab] >= MIN_PLACE_PX) & (lab > 0)


def features(sq):
    ddir = L.DATA + L.district_of(sq)
    g = TP.grass_pieces(sq)
    trk = LF.load_count(ddir, sq, 2026) >= T
    inside = ndimage.binary_erosion(g > 0, iterations=3) & (g > 0)
    pl = places(sq)
    dist = ndimage.distance_transform_edt(~pl) * 0.01   # km
    out = []
    for i, sl in enumerate(ndimage.find_objects(g), 1):
        if sl is None:
            continue
        m = g[sl] == i
        if m.sum() < 2000:
            continue
        ins = inside[sl] & m
        cs, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        ring = max(cs, key=cv2.contourArea)[:, 0, :].astype(float) * 10
        ring = np.vstack([ring, ring[:1]])
        out.append(dict(square=sq, region=i, ha=round(m.sum() / 100, 1), tracks=round(float(trk[sl][ins].mean()) if ins.sum() else 0, 3),
                        dist_km=round(float(dist[sl][m].min()), 2), fill=round(LL.rectangle_fill(ring), 3)))
    return out


if __name__ == '__main__':
    rows = []
    for sq in sys.argv[2:]:
        r = features(sq)
        rows += r
        print(sq, len(r), flush=True)
    w = csv.DictWriter(open(sys.argv[1], 'w', newline=''), fieldnames=list(rows[0]))
    w.writeheader(); w.writerows(rows)
