"""Plot lines of Sandyktau / Esil (2026-11-15, owner: "cadastral map check is also yours") from map views at 26.05 m per
screen pixel (viewport 1536 x 674; python lines26.py DISTRICT VIEWS_NAME, e.g. sandyktau views26s). Adapted from
lines25.py (Tayynsha, 62 views at 25.4 m per screen pixel) (views25.csv: the map
coordinates, QazTRF-23 / GK zone 12, of the screen point (900, 380); scale measured by hovering).

Each view: the blue plot lines (lake fills and village house blocks, which are wide, are taken out; double road lines
stay) and the red lines (district edge, selected plot) form a mask on the screenshot. Every 10 m cell of each square's
UTM grid then looks up the screen pixel it falls in (UTM to GK by an affine fit per view, checked against pyproj), so a
one-pixel line stays one unbroken line on the grid. All views are merged.
Writes lines/<square>.tif (1 = plot line) and seen/<square>.tif (1 = some view showed it). ml-env python."""
import csv
import os

import numpy as np
import tifffile
from PIL import Image
from pyproj import Transformer
from scipy import ndimage

D = 'C:/Programming Projects/qaz-agro-sat/data/'
HERE = D + 'experiments/cadastre-edges-2026-11-15/'
SCALES = {'views26s': (26.049, 26.068), 'views28e': (28.241, 28.261), 'views28all': (28.241, 28.261)}   # metres per screen pixel, east and south (hovered)                         # metres per screen pixel, east and south
CX, CY = 900, 380                                 # the hovered screen point
MAP_BOX = (292, 107, 1513, 652)
MASKS = [(312, 128, 716, 174), (1450, 128, 1503, 472), (1385, 568, 1503, 640), (1275, 612, 1372, 640),
         (326, 598, 356, 628), (893, 375, 915, 405)]   # search, selector, buttons, readout, scale bar, corner icon, cursor
import sys
DIST, VIEWS = sys.argv[1], sys.argv[2]
SX, SY = SCALES[VIEWS]


def lines_of(path):
    im = np.asarray(Image.open(path).convert('RGB')).astype(int)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    blue = ((r + g + b) < 705) & (b > r + 18) & (b >= g - 2)
    wide = ndimage.binary_opening(blue, structure=np.ones((5, 5)))   # lakes, village house blocks; double road lines stay
    blue &= ~ndimage.binary_dilation(wide, iterations=2)
    red = (r > g + 60) & (r > b + 50)
    keep = np.zeros(blue.shape, bool)
    x0, y0, x1, y1 = MAP_BOX
    keep[y0:y1, x0:x1] = True
    for a, bb, c, d in MASKS:
        keep[bb:d, a:c] = False
    return (blue | red) & keep, keep


def affine_utm_to_gk(e0, n0, e1, n1, to_gk):
    """Least-squares affine UTM -> GK over a box, and its worst error at the fit points (m)."""
    ee, nn = np.meshgrid(np.linspace(e0, e1, 7), np.linspace(n0, n1, 7))
    ge, gn = to_gk.transform(ee.ravel(), nn.ravel())
    A = np.column_stack([ee.ravel(), nn.ravel(), np.ones(ee.size)])
    ce = np.linalg.lstsq(A, ge, rcond=None)[0]
    cn = np.linalg.lstsq(A, gn, rcond=None)[0]
    err = np.hypot(A @ ce - ge, A @ cn - gn).max()
    return ce, cn, err


def main():
    views = list(csv.DictReader(open(HERE + f'{VIEWS}.csv', encoding='utf-8')))
    sqs, epsg = {}, {}
    for sq in sorted(os.listdir(D + DIST)):
        p = D + f'{DIST}/{sq}/fields-2026/fields-2026.tif'
        if sq.startswith('E') and os.path.isfile(p):
            page = tifffile.TiffFile(p).pages[0]
            x0, y0 = page.tags[33922].value[3:5]
            keys = list(page.tags[34735].value)
            epsg[sq] = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
            sqs[sq] = (x0, y0, page.shape)
    out_l = {sq: np.zeros(s[2], bool) for sq, s in sqs.items()}
    out_s = {sq: np.zeros(s[2], bool) for sq, s in sqs.items()}
    to_utm = {z: Transformer.from_crs(10946, z, always_xy=True) for z in set(epsg.values())}
    to_gk = {z: Transformer.from_crs(z, 10946, always_xy=True) for z in set(epsg.values())}
    worst = 0.0
    for v in views:
        E, N = float(v['E']), float(v['N'])
        lines, keep = lines_of(HERE + f'{VIEWS}/{v["view"]}.jpg')
        ge = [E + (x - CX) * SX for x in (MAP_BOX[0], MAP_BOX[2])]
        gn = [N - (y - CY) * SY for y in (MAP_BOX[1], MAP_BOX[3])]
        for sq, (gx0, gy0, shape) in sqs.items():
            # the view's footprint in this square's UTM zone
            ue, un = to_utm[epsg[sq]].transform([ge[0], ge[1], ge[0], ge[1]], [gn[0], gn[0], gn[1], gn[1]])
            ue0, ue1, un0, un1 = min(ue) - 50, max(ue) + 50, min(un) - 50, max(un) + 50
            c0, c1 = max(0, int((ue0 - gx0) / 10)), min(shape[1], int((ue1 - gx0) / 10) + 1)
            r0, r1 = max(0, int((gy0 - un1) / 10)), min(shape[0], int((gy0 - un0) / 10) + 1)
            if c1 <= c0 or r1 <= r0:
                continue
            ce, cn, err = affine_utm_to_gk(gx0 + c0 * 10, gy0 - r1 * 10, gx0 + c1 * 10, gy0 - r0 * 10, to_gk[epsg[sq]])
            worst = max(worst, err)
            cc, rr = np.meshgrid(np.arange(c0, c1), np.arange(r0, r1))
            e = gx0 + (cc + 0.5) * 10
            n = gy0 - (rr + 0.5) * 10
            gke = ce[0] * e + ce[1] * n + ce[2]
            gkn = cn[0] * e + cn[1] * n + cn[2]
            x = np.rint(CX + (gke - E) / SX).astype(int)
            y = np.rint(CY - (gkn - N) / SY).astype(int)
            ok = (x >= 0) & (x < lines.shape[1]) & (y >= 0) & (y < lines.shape[0])
            k = np.zeros(x.shape, bool)
            ln = np.zeros(x.shape, bool)
            k[ok] = keep[y[ok], x[ok]]
            ln[ok] = lines[y[ok], x[ok]]
            out_s[sq][r0:r1, c0:c1] |= k
            out_l[sq][r0:r1, c0:c1] |= ln
        print(v['view'], int(lines.sum()), 'line px', flush=True)
    print('worst affine error (m):', round(worst, 3))
    os.makedirs(HERE + f'lines/{DIST}', exist_ok=True)
    os.makedirs(HERE + f'seen/{DIST}', exist_ok=True)
    for sq in sqs:
        seen = ndimage.binary_erosion(out_s[sq], iterations=2)
        lines = out_l[sq] & seen
        tifffile.imwrite(HERE + f'lines/{DIST}/{sq}.tif', lines.astype(np.uint8), compression='zlib')
        tifffile.imwrite(HERE + f'seen/{DIST}/{sq}.tif', seen.astype(np.uint8), compression='zlib')
        print(sq, 'seen %.2f' % seen.mean(), 'line cells', int(lines.sum()))


if __name__ == '__main__':
    main()
