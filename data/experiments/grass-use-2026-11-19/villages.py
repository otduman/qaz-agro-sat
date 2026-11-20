"""Probe: villages from texture. July NDVI local spread (std in 70 m) averaged over 310 m, plus never-green density.
python villages.py SQ STD DENS -> villages-<sq>.png (half res; villages red, livestock places orange)"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage
import places_overview as PO
import places_probe as PP
L, chips, P = PO.L, PO.chips, PP.P


def local_std(v, k=7):
    v = np.nan_to_num(v, nan=np.nanmedian(v))
    m = ndimage.uniform_filter(v, k)
    return np.sqrt(np.maximum(ndimage.uniform_filter(v * v, k) - m * m, 0))


def detect_villages(sq, std_min=0.07, dens_min=0.02, min_ha=10):
    share, clear = PP.never_green(sq)
    wet, d = PP.water(sq, share.shape)
    reg = L.labels(sq, 2026)
    land = L.land(sq, 2026)
    farmland = np.isin(reg, [r for r, (k, ha) in land.items() if k == 'field' or (k == 'fallow' and ha >= 30)])
    v = P.ndvi(sq, 2026, d[0], d[2], d[3])
    s = ndimage.uniform_filter(local_std(v), 31)
    built = (share < PP.NEVER) & (clear >= 5) & ~wet & ~farmland
    den = ndimage.uniform_filter(built.astype(np.float32), 31)
    farm_share = ndimage.uniform_filter(farmland.astype(np.float32), 31)
    vm = (s >= std_min) & (den >= dens_min) & (farm_share < 0.5) & ~wet
    vm = ndimage.binary_opening(vm, iterations=3)
    lab, n = ndimage.label(vm)
    size = np.bincount(lab.ravel())
    keep = np.zeros(n + 1, bool); keep[1:] = size[1:] >= min_ha * 100
    return np.where(keep[lab], lab, 0), d, farmland


if __name__ == '__main__':
    sq, sd, dn = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    vl, d, farmland = detect_villages(sq, sd, dn)
    pl, _, _, _ = PO.detect(sq)
    print(sq, 'villages', len(np.unique(vl)) - 1, 'ha', sorted(int((vl == i).sum() / 100) for i in np.unique(vl) if i))
    rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3]), 0.2) for b in ('red', 'green', 'blue')])
    im = rgb.copy()
    for m, col in ((pl > 0, (255, 150, 0)), (vl > 0, (255, 0, 0))):
        im[ndimage.binary_dilation(m & ~ndimage.binary_erosion(m), iterations=2)] = col
    Image.fromarray(im).resize((im.shape[1] // 2, im.shape[0] // 2), Image.BILINEAR).save(f'villages-{sq}.png')
