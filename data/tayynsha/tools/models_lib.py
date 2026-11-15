"""Shared parts of the ready-made field-model test (2026-11-12): squares, date choice, band reading, label maps of
the models in the square's own 10 m UTM grid, and the clean-up every map gets before it is measured.

Reads the main checkout's data folder; writes only under data/experiments/field-models-2026-11-12/.
Scripts that also run a model must `import torch` before this module (Windows DLL order).
"""
import csv
import json
import os

import numpy as np
import tifffile
from scipy import ndimage

import fields_lib as L

EXP = L.DATA + 'experiments/field-models-2026-11-12/'
YEAR = 2026
HA = 0.01  # hectares per 10 m pixel

# step 2: settings chosen on these tuning squares only
TUNE = ['E540N5960', 'E560N5960', 'E500N5960', 'E600N5940']
# step 3: comparison set, none of them used in step 2 (and none in the tuning of our own method)
COMPARE = ['E480N5960', 'E520N5940', 'E540N5940', 'E560N6000', 'E580N6000', 'E600N5980', 'E440N5800', 'E460N5800']
# extra cadastre check: tuning squares of our method with the most plot areas on their cards, not used in step 2
CADASTRE_EXTRA = ['E580N5940', 'E580N5980', 'E580N5920', 'E580N5960', 'E560N5940']


def square_dir(square):
    return L.square_dir(square)


def grid(square):
    return L.grid(square, YEAR)


def index_rows(square):
    rows = []
    for r in csv.DictReader(open(square_dir(square) + f'season-{YEAR}/index.csv', encoding='utf-8')):
        if r['status'] == 'stored':
            rows.append((r['date'], float(r['clear_share'])))
    return rows


def dn(square, date, band):
    return tifffile.imread(square_dir(square) + f'season-{YEAR}/{date}/{band}.tif')


def pick_date(square, lo, hi, target):
    """Clearest stored date with lo <= date <= hi ('MM-DD'); ties (to 0.01) go to the date nearest target.
    A date whose red band has over 0.5% no-data pixels (swath edge) is passed over. When no date in the window is
    at least 95% clear, the window is widened by 7 days each side, up to three times."""
    from datetime import date as D, timedelta
    t = D.fromisoformat(f'{YEAR}-{target}')
    lo_d, hi_d = D.fromisoformat(f'{YEAR}-{lo}'), D.fromisoformat(f'{YEAR}-{hi}')
    rows = index_rows(square)
    best = None
    for widen in range(4):
        a, b = (lo_d - timedelta(7 * widen)).isoformat(), (hi_d + timedelta(7 * widen)).isoformat()
        cands = [(d, c) for d, c in rows if a <= d <= b]
        cands.sort(key=lambda r: (-round(r[1], 2), abs((D.fromisoformat(r[0]) - t).days)))
        for d, c in cands:
            if (dn(square, d, 'red') == 0).mean() <= 0.005:
                if best is None:
                    best = (d, c)
                if c >= 0.95:
                    return d, c
                break
    if best is None:
        raise ValueError(f'{square}: no full clear date near {lo}..{hi}')
    return best


# date windows (MM-DD lo, hi, target)
WINDOWS = {
    'june': ('05-20', '06-30', '06-10'),   # early season: sown or bare
    'july': ('07-01', '07-31', '07-15'),   # mid-season: green
    'aug': ('08-15', '09-05', '08-25'),    # harvest time: cut and standing fields side by side
    'sept': ('09-06', '09-30', '09-15'),   # after most of the harvest
}


def dates_for(square, names):
    return [pick_date(square, *WINDOWS[n])[0] for n in names]


def map_dir(method, square):
    return EXP + f'maps/{method}/{square}/'


def geo_tags(square):
    """GeoTIFF tags (tie point, pixel scale, keys for EPSG) so other tools see the square's grid."""
    e0, n0, size, w, h, epsg = grid(square)
    return [(33550, 'd', 3, (size, size, 0.0), True),
            (33922, 'd', 6, (0.0, 0.0, 0.0, e0, n0, 0.0), True),
            (34735, 'H', 16, (1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, epsg), True)]


def write_tif(path, arr, square):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tifffile.imwrite(path, arr, compression='zlib', extratags=geo_tags(square))


def clean(lab, min_ha=5.0, hole_ha=1.0):
    """The clean-up every model map gets, after our own method's (minRegionHectares 5, maxHoleHectares 1):
    each 4-connected piece of a region becomes its own region, regions under min_ha are dropped, and gaps under
    hole_ha wholly inside one region are filled into it. Returns labels 1..n (0 = no field), sorted by position."""
    out = np.zeros(lab.shape, np.int32)
    n = 0
    objs = ndimage.find_objects(lab)
    four = ndimage.generate_binary_structure(2, 1)
    min_px = min_ha / HA
    for r, sl in enumerate(objs, start=1):
        if sl is None:
            continue
        m = lab[sl] == r
        comp, k = ndimage.label(m, four)
        if k == 0:
            continue
        sizes = np.bincount(comp.ravel())
        for c in range(1, k + 1):
            if sizes[c] >= min_px:
                n += 1
                o = out[sl]
                o[comp == c] = n
    # holes: gaps (0) not touching the border whose ring is a single region
    gaps, k = ndimage.label(out == 0, four)
    if k:
        sizes = np.bincount(gaps.ravel())
        border = np.unique(np.concatenate([gaps[0], gaps[-1], gaps[:, 0], gaps[:, -1]]))
        small = np.nonzero((sizes <= hole_ha / HA) & (np.arange(k + 1) > 0))[0]
        small = np.setdiff1d(small, border)
        if small.size:
            g_objs = ndimage.find_objects(gaps)
            for g in small:
                sl = g_objs[g - 1]
                sl2 = tuple(slice(max(0, s.start - 1), s.stop + 1) for s in sl)
                m = gaps[sl2] == g
                ring = ndimage.binary_dilation(m, four) & ~m
                vals = np.unique(out[sl2][ring])
                if vals.size == 1 and vals[0] > 0:
                    o = out[sl2]
                    o[m] = vals[0]
    return renumber(out)


def renumber(lab):
    """Labels renumbered 1..n in order of first appearance (row by row); 0 stays 0."""
    flat = lab.ravel()
    u, first = np.unique(flat, return_index=True)
    keep = u > 0
    u, first = u[keep], first[keep]
    order = u[np.argsort(first)]
    lut = np.zeros(int(lab.max()) + 1, np.int32)
    lut[order] = np.arange(1, order.size + 1, dtype=np.int32)
    return lut[lab]


def ours(square):
    """Our current map (fields-2026/) as labels plus region -> land."""
    lab = L.labels(square, YEAR)
    lands = {r: v[0] for r, v in L.land(square, YEAR).items()}
    return lab, lands


def cropland_mask(square):
    """Pixels our map labels field or fallow."""
    lab, lands = ours(square)
    n = lab.max()
    k = np.zeros(n + 1, bool)
    for r, land in lands.items():
        k[r] = land in ('field', 'fallow')
    return k[lab]


def save_map(method, square, lab, meta, polygons=True):
    """Writes a model map: fields-2026.tif (labels), fields-2026.geojson (UTM polygons, with each region's share
    on our field/fallow land) and meta.json (the settings and dates)."""
    d = map_dir(method, square)
    os.makedirs(d, exist_ok=True)
    lab = lab.astype(np.int32)
    n = int(lab.max())
    write_tif(d + f'fields-{YEAR}.tif', lab.astype(np.uint16 if n < 65535 else np.uint32), square)
    crop = cropland_mask(square)
    area = np.bincount(lab.ravel(), minlength=n + 1)
    on = np.bincount(lab.ravel(), weights=crop.ravel(), minlength=n + 1)
    e0, n0, size, w, h, epsg = grid(square)
    feats = []
    if not polygons:
        json.dump(meta, open(d + 'meta.json', 'w', encoding='utf-8'), indent=1)
        return
    try:
        import rasterio.features
        from affine import Affine
        tr = Affine(size, 0, e0, 0, -size, n0)
        for geom, val in rasterio.features.shapes(lab.astype(np.int32), mask=lab > 0, transform=tr):
            r = int(val)
            feats.append({'type': 'Feature', 'geometry': geom,
                          'properties': {'id': f'{YEAR}-F{r:04d}', 'area_ha': round(area[r] * HA, 2),
                                         'land': 'field', 'cropland_share': round(on[r] / max(area[r], 1), 3)}})
    except ImportError:
        pass
    json.dump({'type': 'FeatureCollection', 'crs': {'type': 'name', 'properties': {'name': f'EPSG:{epsg}'}},
               'features': feats}, open(d + f'fields-{YEAR}.geojson', 'w', encoding='utf-8'))
    json.dump(meta, open(d + 'meta.json', 'w', encoding='utf-8'), indent=1)


def load_map(method, square):
    """(labels, kind) of a map; kind[r] = 1 for a field region on our cropland (at least half of it on our
    field/fallow land), 2 otherwise. method 'ours' is our current map with its own land labels."""
    if method == 'ours':
        lab, lands = ours(square)
        kind = np.zeros(lab.max() + 1, np.int8)
        for r, land in lands.items():
            kind[r] = 1 if land in ('field', 'fallow') else 2
        return lab, kind
    lab = tifffile.imread(map_dir(method, square) + f'fields-{YEAR}.tif').astype(np.int32)
    crop = cropland_mask(square)
    n = lab.max()
    area = np.bincount(lab.ravel(), minlength=n + 1)
    on = np.bincount(lab.ravel(), weights=crop.ravel(), minlength=n + 1)
    kind = np.where(on >= 0.5 * np.maximum(area, 1), 1, 2).astype(np.int8)
    kind[0] = 0
    return lab, kind
