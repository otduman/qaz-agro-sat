"""Small gaps inside and between fields filled (owner's land key, 2026-11-15: "blob inside a crop field", 11 of 15
undrawn spots in Esil and Sandyktau were such gaps, and his earlier rule: islands belong to the field around them).

A gap is a compact piece of unlabelled land (0) under GAP_PX (at least COMPACT of it survives an opening with a 50 m
square, so roads, tracks and tree belts between fields stay), not touching the square's edge, not water, whose border
lies at least FIELD_SHARE on field or fallow regions. It joins the field or fallow region it shares the most border with.
Water: pixels whose median summer short-wave infrared (SWIR1, June to August clear dates) is under WATER_SWIR, the
site's water guard; a gap that is more than half water is left.

v3: a gap that looks different from the field it would join on the clearest late-summer photo (NDVI differs by more
than NDVI_DIFF, or darker than BRIGHT_RATIO of it: groves, ponds, grassy patches) stays apart.

python fill_gaps.py SQUARE_DIR YEAR      writes SQUARE_DIR/fields-YEAR-fg/ (tif + geojson, ids and properties kept)
"""
import csv
import json
import os
import sys

import numpy as np
import tifffile
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hybrid_fields as HF  # noqa: E402

GAP_PX = 1000          # 10 ha
COMPACT = 0.5          # share of a gap left after an opening with a 5 x 5 square: thin strips (roads, tracks, belts) stay
FIELD_SHARE = 0.6
WATER_SWIR = 0.12
CROP = ('field', 'fallow')
NDVI_DIFF = 0.08       # v3: a gap greener (or browner) than the field it would join by more than this stays apart
BRIGHT_RATIO = 0.8     # v3: a gap darker than this share of the field's brightness (trees, wet hollows) stays apart


def fill(lab, land, water=None, photo=None):
    """photo: (ndvi, brightness) on the late-summer clear date, or None."""
    out = lab.copy()
    is_crop = np.zeros(int(lab.max()) + 1, bool)
    for r, v in land.items():
        if v in CROP and r < is_crop.size:
            is_crop[r] = True
    four = ndimage.generate_binary_structure(2, 1)
    gaps, n = ndimage.label(lab == 0, four)
    if n == 0:
        return out
    sizes = np.bincount(gaps.ravel())
    edge = set(np.unique(np.concatenate([gaps[0], gaps[-1], gaps[:, 0], gaps[:, -1]])).tolist())
    for g, sl in enumerate(ndimage.find_objects(gaps), start=1):
        if sl is None or sizes[g] >= GAP_PX or g in edge:
            continue
        sl2 = tuple(slice(max(0, s.start - 1), s.stop + 1) for s in sl)
        m = gaps[sl2] == g
        if water is not None and water[sl2][m].mean() > 0.5:
            continue
        if ndimage.binary_opening(m, structure=np.ones((5, 5), bool)).sum() < COMPACT * m.sum():
            continue
        ring = ndimage.binary_dilation(m, four) & ~m
        vals = lab[sl2][ring]
        crop_vals = vals[is_crop[vals]]
        if crop_vals.size < FIELD_SHARE * vals.size:
            continue
        u, c = np.unique(crop_vals, return_counts=True)
        target = u[np.argmax(c)]
        if photo is not None:
            ndvi, bright = photo
            fm = lab == target
            g_n, f_n = np.nanmedian(ndvi[sl2][m]), np.nanmedian(ndvi[fm])
            g_b, f_b = np.nanmedian(bright[sl2][m]), np.nanmedian(bright[fm])
            if np.isfinite(g_n) and np.isfinite(f_n) and abs(g_n - f_n) > NDVI_DIFF:
                continue
            if np.isfinite(g_b) and np.isfinite(f_b) and g_b < BRIGHT_RATIO * f_b:
                continue
        out[sl2][m] = target
    return out


def water_mask(sq_dir, year, shape):
    """Median summer SWIR1 under WATER_SWIR, on the field grid (the band is 20 m)."""
    idx = f'{sq_dir}/season-{year}/index.csv'
    stack = []
    for r in csv.DictReader(open(idx, encoding='utf-8')):
        if r['status'] == 'stored' and float(r['clear_share']) >= 0.9 and r['date'][5:7] in ('06', '07', '08'):
            a = tifffile.imread(f'{sq_dir}/season-{year}/{r["date"]}/swir16.tif').astype(np.float32)
            v = a * float(r['scale']) + float(r['offset'])
            v[a == 0] = np.nan
            stack.append(v)
    if not stack:
        return None
    med = np.nanmedian(np.stack(stack), axis=0)
    big = np.repeat(np.repeat(med, 2, axis=0), 2, axis=1)
    out = np.zeros(shape, bool)
    h, w = min(shape[0], big.shape[0]), min(shape[1], big.shape[1])
    out[:h, :w] = big[:h, :w] < WATER_SWIR
    return out


def late_photo(sq_dir, year, shape):
    """(NDVI, visible brightness) on the clearest date from 10 August to 10 September, on the field grid."""
    best = None
    for r in csv.DictReader(open(f'{sq_dir}/season-{year}/index.csv', encoding='utf-8')):
        if r['status'] == 'stored' and f'{year}-08-10' <= r['date'] <= f'{year}-09-10':
            if best is None or float(r['clear_share']) > float(best['clear_share']):
                best = r
    if best is None or float(best['clear_share']) < 0.8:
        return None
    sc, off = float(best['scale']), float(best['offset'])
    b = {}
    for band in ('red', 'green', 'blue', 'nir'):
        a = tifffile.imread(f'{sq_dir}/season-{year}/{best["date"]}/{band}.tif').astype(np.float32)
        v = a * sc + off
        v[a == 0] = np.nan
        z = np.full(shape, np.nan, np.float32)
        h, w = min(shape[0], v.shape[0]), min(shape[1], v.shape[1])
        z[:h, :w] = v[:h, :w]
        b[band] = z
    ndvi = (b['nir'] - b['red']) / (b['nir'] + b['red'])
    return ndvi, (b['red'] + b['green'] + b['blue']) / 3


def main():
    sq_dir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    src = f'{sq_dir}/fields-{year}'
    page = tifffile.TiffFile(f'{src}/fields-{year}.tif').pages[0]
    lab = page.asarray().astype(np.int64)
    feats = json.load(open(f'{src}/fields-{year}.geojson', encoding='utf-8'))['features']
    props = {int(f['properties']['id'].split('-F')[1]): f['properties'] for f in feats}
    land = {r: p['land'] for r, p in props.items()}
    new = fill(lab, land, water_mask(sq_dir, year, lab.shape), late_photo(sq_dir, year, lab.shape))
    out = f'{sq_dir}/fields-{year}-fg'
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
    print(f'{os.path.basename(sq_dir)} {year}: {moved} pixels ({moved * 0.01:,.0f} ha) of small gaps filled')


if __name__ == '__main__':
    main()
