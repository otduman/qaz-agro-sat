"""Hybrid field map: Delineate Anything's field outlines where they lie on our cropland, our own regions where the
model found no field (field-da-hybrid check, 2026-11-13).

The model (AGPL-3.0, run as its own step: models_da.py, maps in data/experiments/field-models-2026-11-12/maps/da/)
draws straighter, less broken outlines than our pixel grouping but does not know whether land is sown this year.
Our map knows the land label (field, fallow, other) of every region. So:
  1. a model field becomes a hybrid region when at least half of it lies on land our map calls field or fallow; it
     is field or fallow by which of the two covers more of it;
  2. our field or fallow region keeps its own outline where the model drew nothing: when at least half of it is
     outside the accepted model fields, that part becomes a hybrid region with our label; a smaller leftover (an
     edge the model cut off) is dropped;
  3. every 4-connected piece is its own region; pieces under 5 ha are dropped; regions are numbered 1..n.

python hybrid_fields.py SQUARE_DIR [YEAR]      writes SQUARE_DIR/fields-YEAR-da/fields-YEAR.tif and .geojson in
                                               the field command's format (ids YEAR-Fnnnn = raster value)
"""
import json
import os
import sys

import cv2
import numpy as np
import tifffile
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import land_lib as LL  # noqa: E402

FIELD, FALLOW, OTHER = 1, 2, 3
CODE = {'field': FIELD, 'fallow': FALLOW, 'other': OTHER}
NAME = {v: k for k, v in CODE.items()}
CROP_SHARE = 0.5
KEEP_SHARE = 0.5
MIN_HA = 5.0
PX = 10.0
DA_MAPS = 'C:/Programming Projects/qaz-agro-sat/data/experiments/field-models-2026-11-12/maps/da/'


def hybrid(ours, land, da, min_px=MIN_HA / 0.01):
    """(labels 1..n, {region: FIELD or FALLOW}) from our labels with {region: land code} and the model's labels."""
    n = int(ours.max())
    code = np.zeros(n + 1, np.int8)
    for r, c in land.items():
        code[r] = c
    pl = code[ours]
    m = int(da.max())
    tot = np.bincount(da.ravel(), minlength=m + 1)
    f = np.bincount(da[pl == FIELD], minlength=m + 1)
    w = np.bincount(da[pl == FALLOW], minlength=m + 1)
    accept = ((f + w) >= CROP_SHARE * tot) & (tot >= min_px)
    accept[0] = False
    covered = accept[da]
    size = np.bincount(ours.ravel(), minlength=n + 1)
    left = np.bincount(ours[~covered], minlength=n + 1)
    keep = np.isin(code, (FIELD, FALLOW)) & (left >= KEEP_SHARE * size) & (left >= min_px)
    keep[0] = False
    tmp = np.zeros(ours.shape, np.int64)
    tmp[covered] = da[covered]
    mine = ~covered & keep[ours]
    tmp[mine] = m + ours[mine]
    lut = np.zeros(m + n + 1, np.int8)
    lut[1:m + 1] = np.where(f[1:] >= w[1:], FIELD, FALLOW)
    lut[m + 1:] = code[1:]
    out = np.zeros(ours.shape, np.int32)
    lands = {}
    four = ndimage.generate_binary_structure(2, 1)
    k = 0
    for r, sl in enumerate(ndimage.find_objects(tmp), start=1):
        if sl is None:
            continue
        comp, c = ndimage.label(tmp[sl] == r, four)
        sizes = np.bincount(comp.ravel())
        for i in range(1, c + 1):
            if sizes[i] >= min_px:
                k += 1
                out[sl][comp == i] = k
                lands[k] = int(lut[r])
    # renumber in reading order so ids are stable for a given map
    flat = out.ravel()
    u, first = np.unique(flat, return_index=True)
    order = u[u > 0][np.argsort(first[u > 0])]
    new = np.zeros(k + 1, np.int32)
    new[order] = np.arange(1, order.size + 1)
    return new[out], {int(new[r]): c for r, c in lands.items()}


def _lonlat(e, n, lon0):
    """Vectorised UTM -> lon/lat (the iteration of site/build/geo.from_utm42, on arrays)."""
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../site/build'))
    import geo
    lon = np.full(e.shape, float(lon0))
    lat = np.full(e.shape, 54.0)
    for _ in range(40):
        ee, nn = geo.utm42(lon, lat, lon0)
        lon = lon + (e - ee) / (111320 * np.cos(np.radians(lat)))
        lat = lat + (n - nn) / 111200
    return lon, lat


def polygons(lab, lands, x0, y0, lon0, year):
    feats = []
    for r, sl in enumerate(ndimage.find_objects(lab), start=1):
        if sl is None:
            continue
        y_off, x_off = sl[0].start, sl[1].start
        m = np.pad((lab[sl] == r).astype(np.uint8), 1)
        cs, hier = cv2.findContours(m, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        rings = []
        for c, h in zip(cs, hier[0]):
            if len(c) < 3:
                continue
            pts = c[:, 0, :].astype(float) + [x_off - 1, y_off - 1]
            ring = np.column_stack([x0 + (pts[:, 0] + 0.5) * PX, y0 - (pts[:, 1] + 0.5) * PX])
            ring = LL.simplify(np.vstack([ring, ring[:1]]), 5.0)
            lon, lat = _lonlat(ring[:, 0], ring[:, 1], lon0)
            coords = [[round(a, 6), round(b, 6)] for a, b in zip(lon, lat)]
            if coords[0] != coords[-1]:
                coords.append(coords[0])
            rings.append((h[3] < 0, coords))
        outer = [c for is_outer, c in rings if is_outer]
        holes = [c for is_outer, c in rings if not is_outer]
        if not outer:
            continue
        geom = (dict(type='Polygon', coordinates=[outer[0]] + holes) if len(outer) == 1 else
                dict(type='MultiPolygon', coordinates=[[o] for o in outer]))
        feats.append(dict(type='Feature', properties=dict(id=f'{year}-F{r:04d}', area_ha=round(float((lab[sl] == r).sum()) * 0.01, 2),
                                                           land=NAME[lands[r]], uniformity=0.8), geometry=geom))
    return dict(type='FeatureCollection', features=feats)


def main():
    sq_dir, year = sys.argv[1].rstrip('/'), int(sys.argv[2]) if len(sys.argv) > 2 else 2026
    sq = os.path.basename(sq_dir)
    src = f'{sq_dir}/fields-{year}'
    t = tifffile.TiffFile(f'{src}/fields-{year}.tif')
    page = t.pages[0]
    ours = page.asarray().astype(np.int64)
    land = {int(f['properties']['id'].split('-F')[1]): CODE[f['properties']['land']]
            for f in json.load(open(f'{src}/fields-{year}.geojson', encoding='utf-8'))['features']}
    da = tifffile.imread(f'{DA_MAPS}{sq}/fields-{year}.tif').astype(np.int64)
    lab, lands = hybrid(ours, land, da)
    out = f'{sq_dir}/fields-{year}-da'
    os.makedirs(out, exist_ok=True)
    tags = page.tags
    keys = list(tags[34735].value)
    epsg = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
    extratags = [(33550, 'd', 3, tuple(tags[33550].value), False), (33922, 'd', 6, tuple(tags[33922].value), False),
                 (34735, 'H', len(keys), tuple(keys), False)]
    # tiled 256 x 256, deflate with horizontal predictor, as the field command writes (its reader needs tiles)
    tifffile.imwrite(f'{out}/fields-{year}.tif', lab.astype(np.uint16), extratags=extratags + [(42113, 's', 0, '0', False)],
                     tile=(256, 256), compression='deflate', predictor=True)
    x0, y0 = tags[33922].value[3:5]
    with open(f'{out}/fields-{year}.geojson', 'w', encoding='utf-8') as fh:
        json.dump(polygons(lab, lands, x0, y0, (epsg - 32600) * 6 - 183, year), fh)
    nf = sum(1 for c in lands.values() if c == FIELD)
    print(f'{sq}: {len(lands)} regions ({nf} field) from {len(land)} of ours and {int(da.max())} model fields')


if __name__ == '__main__':
    main()
