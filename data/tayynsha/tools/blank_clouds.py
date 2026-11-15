"""Cloud-covered pixels blanked in a season stack to save disk (owner, 2026-11-15: "that takes too much space").

Every step reads only pixels whose scene class (scl.tif) is clear land, water or snow; pixels the class marks as cloud
shadow (3), cloud (8, 9) or cirrus (10) are never used. They are set to the stack's nodata value 0 in every band, so
they compress to almost nothing: about a third of a square's photos. scl.tif, georeference, tiling and compression
are kept. A band is rewritten only when something changes, through a temporary file, so a stopped run leaves no
broken file and a second run does nothing.

python blank_clouds.py SEASON_DIR       e.g. data/tayynsha/E480N5940/season-2026
"""
import csv
import os
import sys

import numpy as np
import tifffile

CLOUD = (3, 8, 9, 10)
BANDS = ('blue', 'green', 'red', 'nir', 'swir16', 'swir22')


def _mask_for(mask20, shape):
    if mask20.shape == shape:
        return mask20
    up = np.repeat(np.repeat(mask20, 2, axis=0), 2, axis=1)
    out = np.zeros(shape, bool)
    h, w = min(shape[0], up.shape[0]), min(shape[1], up.shape[1])
    out[:h, :w] = up[:h, :w]
    return out


def blank_date(folder):
    """Blanks one date folder; returns the number of band pixels set to 0."""
    cloud = np.isin(tifffile.imread(f'{folder}/scl.tif'), CLOUD)
    if not cloud.any():
        return 0
    changed = 0
    for b in BANDS:
        path = f'{folder}/{b}.tif'
        if not os.path.isfile(path):
            continue
        with tifffile.TiffFile(path) as tf:
            page = tf.pages[0]
            a = page.asarray()
            extratags = [(code, page.tags[code].dtype, page.tags[code].count, page.tags[code].value, False)
                         for code in (33550, 33922, 34735, 42113) if code in page.tags]
            tile = (page.tilelength, page.tilewidth) if page.is_tiled else None
            comp, pred = int(page.compression), int(page.predictor)
        m = _mask_for(cloud, a.shape) & (a != 0)
        n = int(m.sum())
        if not n:
            continue
        a[m] = 0
        tmp = path + '.tmp'
        tifffile.imwrite(tmp, a, tile=tile, compression=comp, predictor=pred, extratags=extratags)
        os.replace(tmp, path)
        changed += n
    return changed


def main():
    season = sys.argv[1].rstrip('/')
    before = after = 0
    for r in csv.DictReader(open(f'{season}/index.csv', encoding='utf-8')):
        folder = f'{season}/{r["date"]}'
        if r['status'] != 'stored' or not os.path.isdir(folder):
            continue
        size = lambda: sum(os.path.getsize(f'{folder}/{f}') for f in os.listdir(folder))  # noqa: E731
        before += size()
        blank_date(folder)
        after += size()
    print(f'{season}: {before / 1e9:.2f} GB -> {after / 1e9:.2f} GB')


if __name__ == '__main__':
    main()
