"""Overview of livestock-place candidates on a whole square (probe): built pixels (never green, not water, not
field/fallow) -> density in a 310 m window -> places where density >= DENS, parts >= MIN_HA.
python places_overview.py SQUARE DENS MIN_HA -> overview-<sq>.png (half resolution)"""
import sys
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
import places_probe as PP
L, chips = PP.L, PP.chips


def detect(sq, dens=0.06, min_ha=2.0):
    share, clear = PP.never_green(sq)
    wet, d = PP.water(sq, share.shape)
    reg = L.labels(sq, 2026)
    land = L.land(sq, 2026)
    farmland = np.isin(reg, [r for r, (k, ha) in land.items() if k == 'field' or (k == 'fallow' and ha >= 30)])  # small 'fallow' = yards
    built = (share < PP.NEVER) & (clear >= 5) & ~wet & ~farmland
    den = ndimage.uniform_filter(built.astype(np.float32), 31)
    pl = ndimage.binary_closing(den >= dens, iterations=3)
    lab, n = ndimage.label(pl)
    size = np.bincount(lab.ravel())
    keep = np.zeros(n + 1, bool)
    keep[1:] = size[1:] >= min_ha * 100
    lab = np.where(keep[lab], lab, 0)
    return lab, built, farmland, d


if __name__ == '__main__':
    sq, dens, mh = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    lab, built, farmland, d = detect(sq, dens, mh)
    ids = [i for i in np.unique(lab) if i]
    sizes = sorted(round((lab == i).sum() / 100) for i in ids)
    print(sq, len(ids), 'places, ha:', sizes)
    rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3]), 0.2) for b in ('red', 'green', 'blue')])
    im = rgb.copy()
    im[farmland & ~ndimage.binary_erosion(farmland)] = (255, 230, 0)
    m = lab > 0
    im[ndimage.binary_dilation(m & ~ndimage.binary_erosion(m), iterations=2)] = (255, 0, 0)
    Image.fromarray(im).resize((im.shape[1] // 2, im.shape[0] // 2), Image.BILINEAR).save(f'overview-{sq}.png')
    np.savez_compressed(f'placesv-{sq}.npz', lab=lab.astype(np.int32))
