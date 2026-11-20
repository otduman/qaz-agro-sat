"""Livestock places v2 (probe): livestock yards (places_overview.detect) plus villages: on the clearest date 20 Jun -
15 Jul, bright bare pixels (red > 0.10, NDVI < 0.25: roofs, yards, streets) at least ROOF of a 310 m window, NDVI
spread (std in 70 m, averaged over 310 m) at least TEX, under half of the window field or large fallow, opened 3 px,
parts >= 5 ha, at least 100 m from edge to middle, at most 4 times as long as wide. python places2.py SQ ... -> places2-<sq>.png (half res; villages red, yards orange)"""
import sys
import cv2
import numpy as np
from PIL import Image
from scipy import ndimage
import places_overview as PO
import villages as V
L, chips, P = PO.L, PO.chips, V.P
ROOF, TEX, MIN_HA, MIN_RAD, MAX_ASPECT, RED_SPREAD, GROUP_HA = 0.01, 0.08, 5, 10, 4, 0.012, 25   # compact parts only: roads and river banks are long


def detect(sq):
    yards, built, farmland, _ = PO.detect(sq)
    ds = [x for x in L.dates(sq, 2026, 0.9) if '2026-06-20' <= x[0] <= '2026-07-15'] or \
         [x for x in L.dates(sq, 2026, 0.8) if '2026-06-10' <= x[0] <= '2026-07-31']
    d = max(ds, key=lambda x: x[1])
    red = L.reflectance(sq, 2026, d[0], 'red', d[2], d[3])
    nir = L.reflectance(sq, 2026, d[0], 'nir', d[2], d[3])
    nd = (nir - red) / np.maximum(nir + red, 1e-6)
    roof = ndimage.uniform_filter(((red > 0.10) & (nd < 0.25) & ~farmland).astype(np.float32), 31)
    tex = ndimage.uniform_filter(V.local_std(nd), 31)
    fs = ndimage.uniform_filter(farmland.astype(np.float32), 31)
    vm = ndimage.binary_opening((roof >= ROOF) & (tex >= TEX) & (fs < 0.5), iterations=3)
    lab, n = ndimage.label(vm)
    size = np.bincount(lab.ravel())
    keep = np.zeros(n + 1, bool)
    rad = ndimage.distance_transform_edt(lab > 0)
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        m = lab[sl] == i
        if m.sum() < MIN_HA * 100 or rad[sl][m].max() < MIN_RAD:
            continue
        ys, xs = np.nonzero(m)
        (_, _), (w, h), _ = cv2.minAreaRect(np.stack([xs, ys], 1).astype(np.float32))
        keep[i] = max(w, h) / max(min(w, h), 1) <= MAX_ASPECT
    vill = keep[lab] & (lab > 0)
    # not water banks (river sand, lake shores): drop parts mostly within 100 m of open water; not uniform bright
    # patches (salt flats, eroded spots): a part must hold mixed bright and dark pixels (red spread >= RED_SPREAD)
    g = L.reflectance(sq, 2026, d[0], 'green', d[2], d[3])
    s16 = L.reflectance(sq, 2026, d[0], 'swir16', d[2], d[3])
    if s16.shape != g.shape:
        s16 = np.kron(s16, np.ones((2, 2), np.float32))[:g.shape[0], :g.shape[1]]
    wet = ndimage.binary_opening((g - s16) / np.maximum(g + s16, 1e-6) > 0, iterations=1)
    bank = ndimage.binary_dilation(wet, iterations=10)
    rs = V.local_std(red)
    out = []
    for m0 in (vill, yards > 0):
        lab2, n2 = ndimage.label(m0)
        keep2 = np.zeros(n2 + 1, bool)
        for i, sl in enumerate(ndimage.find_objects(lab2), 1):
            m = lab2[sl] == i
            keep2[i] = bank[sl][m].mean() < 0.5 and float(np.nanmedian(rs[sl][m])) >= RED_SPREAD
        out.append(keep2[lab2] & (lab2 > 0))
    # a village is a group of parts (within 300 m of each other) of GROUP_HA or more; single river or lake-shore bits
    # are small and far apart
    grp, ng = ndimage.label(ndimage.binary_dilation(out[0], iterations=15))
    area = ndimage.sum(out[0], grp, index=np.arange(ng + 1))
    vill = out[0] & (area[grp] >= GROUP_HA * 100)
    return vill, out[1], d, farmland


if __name__ == '__main__':
    for sq in sys.argv[1:]:
        vill, yards, d, farmland = detect(sq)
        nv = ndimage.label(vill)[1]
        print(sq, 'village parts', nv, 'ha', int(vill.sum() / 100), '; yard parts', ndimage.label(yards)[1], flush=True)
        rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3]), 0.2) for b in ('red', 'green', 'blue')])
        im = rgb.copy()
        for m, col in ((yards, (255, 150, 0)), (vill, (255, 0, 0))):
            im[ndimage.binary_dilation(m & ~ndimage.binary_erosion(m), iterations=2)] = col
        Image.fromarray(im).resize((im.shape[1] // 2, im.shape[0] // 2), Image.BILINEAR).save(f'places2-{sq}.png')
