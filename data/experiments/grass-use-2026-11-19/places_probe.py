"""Stage 1 probe (NOTE.md): livestock places (farms, villages) from the 2026 season stack of one square.
python places_probe.py SQUARE [SQUARE ...]  -> prints counts, writes places-<sq>.npz (labels) and places-<sq>.png
(8 farm candidates on a July photo, 3 km around each, candidate red, field/fallow land yellow)."""
import os
import sys

import numpy as np
import tifffile
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mow_probe as P  # noqa: E402

L, LS, chips = P.L, P.LS, P.chips
GREEN, NEVER, MIN_PX, VILLAGE_PX = 0.20, 0.10, 30, 5000


def never_green(sq):
    """Share of clear dates (15 May - 15 Sep) on which each pixel has NDVI >= GREEN, and the clear-date count."""
    ds = [x for x in L.dates(sq, 2026, 0.05) if '2026-05-15' <= x[0] <= '2026-09-15']
    green = clear = None
    for d, _, sc, off in ds:
        v = P.ndvi(sq, 2026, d, sc, off)
        if green is None:
            green = np.zeros(v.shape, np.int16)
            clear = np.zeros(v.shape, np.int16)
        ok = ~np.isnan(v)
        clear += ok
        green += ok & (v >= GREEN)
    return green / np.maximum(clear, 1), clear


def water(sq, shape):
    ds = [x for x in L.dates(sq, 2026, 0.9) if '2026-07-01' <= x[0] <= '2026-08-15']
    d = max(ds, key=lambda x: x[1])
    g = L.reflectance(sq, 2026, d[0], 'green', d[2], d[3])
    s = L.reflectance(sq, 2026, d[0], 'swir16', d[2], d[3])
    if s.shape != g.shape:
        s = np.kron(s, np.ones((2, 2), np.float32))[:g.shape[0], :g.shape[1]]
    return ((g - s) / np.maximum(g + s, 1e-6)) > 0, d


def places(sq):
    share, clear = never_green(sq)
    wet, d = water(sq, share.shape)
    reg = L.labels(sq, 2026)
    land = L.land(sq, 2026)
    farm_ids = [r for r, (k, _) in land.items() if k in ('field', 'fallow')]
    farmland = np.isin(reg, farm_ids)
    built = (share < NEVER) & (clear >= 5) & ~wet & ~farmland
    built = ndimage.binary_closing(built, iterations=1) & ~farmland
    lab, n = ndimage.label(built, structure=np.ones((3, 3)))
    size = np.bincount(lab.ravel())
    keep = np.zeros(n + 1, bool)
    keep[1:] = size[1:] >= MIN_PX
    lab = np.where(keep[lab], lab, 0)
    return lab, size, farmland, d


def main():
    for sq in sys.argv[1:]:
        lab, size, farmland, d = places(sq)
        ids = [i for i in np.unique(lab) if i]
        farms = [i for i in ids if size[i] < VILLAGE_PX]
        vill = [i for i in ids if size[i] >= VILLAGE_PX]
        print(f'{sq}: {len(farms)} farm candidates ({sum(size[i] for i in farms) / 100:,.0f} ha), '
              f'{len(vill)} village candidates ({sum(size[i] for i in vill) / 100:,.0f} ha); sizes ha '
              f'{sorted(round(size[i] / 100, 1) for i in farms)[-12:]}', flush=True)
        np.savez_compressed(P.HERE + f'places-{sq}.npz', lab=lab.astype(np.int32))
        rng = np.random.default_rng(20261123)
        pick = list(rng.choice(farms, min(8, len(farms)), replace=False)) if farms else []
        tiles = []
        for i in pick:
            ys, xs = np.nonzero(lab == i)
            cy, cx, s = int(ys.mean()), int(xs.mean()), 300
            y0, x0 = int(np.clip(cy - s // 2, 0, lab.shape[0] - s)), int(np.clip(cx - s // 2, 0, lab.shape[1] - s))
            win = (y0, y0 + s, x0, x0 + s)
            rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, d[0], b, d[2], d[3], win), 0.2) for b in ('red', 'green', 'blue')])
            im = rgb.copy()
            fl = farmland[y0:y0 + s, x0:x0 + s]
            im[fl & ~ndimage.binary_erosion(fl)] = (255, 230, 0)
            for j, col in ((None, (255, 120, 120)), (i, (255, 0, 0))):
                m = (lab[y0:y0 + s, x0:x0 + s] > 0) if j is None else (lab[y0:y0 + s, x0:x0 + s] == j)
                im[m & ~ndimage.binary_erosion(m)] = col
            t = Image.fromarray(im)
            ImageDraw.Draw(t).text((4, 4), f'{size[i] / 100:.1f} ha', fill=(255, 255, 255))
            tiles.append(t)
        if tiles:
            sh = Image.new('RGB', (4 * 304, ((len(tiles) + 3) // 4) * 304), (0, 0, 0))
            for k, t in enumerate(tiles):
                sh.paste(t, ((k % 4) * 304, (k // 4) * 304))
            sh.save(P.HERE + f'places-{sq}.png')


if __name__ == '__main__':
    main()
