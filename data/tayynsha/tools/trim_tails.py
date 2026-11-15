"""Thin tails trimmed off fields (owner's answer key, 2026-11-15: "it takes a bit of the road, making it look like a
tail", "the tail-like appendix at the top is not necessary").

For each field or fallow region: its body is what survives an opening with a WIDTH x WIDTH square (parts narrower than
WIDTH pixels go). Removed parts at least MIN_TAIL_PX large are tails: a tail pixel goes to the field or fallow region
it touches most among its neighbours, or to no region (0) when it touches none. A region whose body is empty (a thin
strip field on its own) is kept whole. Other land is never trimmed. Bits of a region left apart by the cut, under
FRAGMENT_PX, are tidied as in straighten_borders.py.

python trim_tails.py SQUARE_DIR YEAR      writes SQUARE_DIR/fields-YEAR-tt/ (tif + geojson, ids and properties kept)
"""
import json
import os
import sys

import numpy as np
import tifffile
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hybrid_fields as HF  # noqa: E402
import straighten_borders as SB  # noqa: E402

WIDTH = 7            # 70 m: parts narrower than this are not field body
MIN_TAIL_PX = 15     # 0.15 ha
CROP = ('field', 'fallow')


def trim(lab, land):
    out = lab.copy()
    crop_ids = [r for r, v in land.items() if v in CROP]
    is_crop = np.zeros(int(lab.max()) + 1, bool)
    for r in crop_ids:
        if r < is_crop.size:
            is_crop[r] = True
    square = np.ones((WIDTH, WIDTH), bool)
    objs = ndimage.find_objects(lab)
    for r in crop_ids:
        if r >= len(objs) + 1 or objs[r - 1] is None:
            continue
        sl = tuple(slice(max(0, s.start - WIDTH), s.stop + WIDTH) for s in objs[r - 1])
        m = out[sl] == r
        body = ndimage.binary_opening(m, structure=square)
        if not body.any():
            continue
        cut = m & ~body
        parts, n = ndimage.label(cut)
        if n == 0:
            continue
        sizes = np.bincount(parts.ravel())
        win = out[sl]
        for k in range(1, n + 1):
            if sizes[k] < MIN_TAIL_PX:
                continue
            bit = parts == k
            ring = ndimage.binary_dilation(bit) & ~bit
            vals = win[ring]
            vals = vals[(vals != r) & (vals > 0)]
            vals = vals[is_crop[vals]] if vals.size else vals
            if vals.size:
                u, c = np.unique(vals, return_counts=True)
                win[bit] = u[np.argmax(c)]
            else:
                win[bit] = 0
    return SB.tidy(out)


def main():
    sq_dir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    src = f'{sq_dir}/fields-{year}'
    page = tifffile.TiffFile(f'{src}/fields-{year}.tif').pages[0]
    lab = page.asarray().astype(np.int64)
    feats = json.load(open(f'{src}/fields-{year}.geojson', encoding='utf-8'))['features']
    props = {int(f['properties']['id'].split('-F')[1]): f['properties'] for f in feats}
    land = {r: p['land'] for r, p in props.items()}
    new = trim(lab, land)
    out = f'{sq_dir}/fields-{year}-tt'
    os.makedirs(out, exist_ok=True)
    tags = page.tags
    keys = list(tags[34735].value)
    epsg = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
    extratags = [(33550, 'd', 3, tuple(tags[33550].value), False), (33922, 'd', 6, tuple(tags[33922].value), False),
                 (34735, 'H', len(keys), tuple(keys), False)]
    tifffile.imwrite(f'{out}/fields-{year}.tif', new.astype(page.dtype), extratags=extratags + [(42113, 's', 0, '0', False)],
                     tile=(256, 256), compression='deflate', predictor=True)
    x0, y0 = tags[33922].value[3:5]
    present = {r for r in np.unique(new) if r}
    gj = HF.polygons(new, {r: HF.CODE[v] for r, v in land.items() if r in present}, x0, y0, (epsg - 32600) * 6 - 183, year)
    for f in gj['features']:
        r = int(f['properties']['id'].split('-F')[1])
        f['properties'] = {**props[r], 'area_ha': f['properties']['area_ha']}
    with open(f'{out}/fields-{year}.geojson', 'w', encoding='utf-8') as fh:
        json.dump(gj, fh)
    moved = int((new != lab).sum())
    print(f'{os.path.basename(sq_dir)} {year}: {moved} pixels ({moved * 0.01:,.0f} ha) trimmed or moved')


if __name__ == '__main__':
    main()
