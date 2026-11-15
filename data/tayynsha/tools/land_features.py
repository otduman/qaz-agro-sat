"""Evidence per region of one square's field map, for the land-label check (2026-11-13). Reads the square's season
stack date by date and its current field map; writes into the experiment folder:

  <district>/<square>/shape-<year>.csv   region, land, area, centre, core (centre inside the square's box less
                                         0.5 km), outline measures (land_lib), enclosure and the enclosing region,
                                         distance to lasting water, nearest lasting water's size
  <district>/<square>/curves-<year>.csv  region, date, clear share and the means over its clear pixels of NDVI (and
                                         its spread), MNDWI, NDMI, the share of pixels that are open water
                                         (MNDWI > 0), and the bands blue, green, red, nir, swir16, swir22

Clear = the 20 m scene classification says vegetation, bare soil or water, no snow, and the 10 m bands and NDVI have
a value, as in the field method (NDVI and the water indices count light below 0 as 0). A region's
means count on a date only when at least 60% of it is clear. Lasting water = pixels open water (MNDWI > 0) on at
least half their clear dates, in patches of at least 1 ha.

python land_features.py SQUARE YEAR
"""
import csv
import json
import math
import os
import sys
import time

import numpy as np
import tifffile
from scipy import ndimage

import fields_lib as L
import land_lib as ll

OUT = L.DATA + 'experiments/land-labels-2026-11-13/'
MIN_CLEAR = 0.6
BANDS10 = ('blue', 'green', 'red', 'nir')
BANDS20 = ('swir16', 'swir22')


def out_dir(square):
    d = OUT + L.district_of(square) + '/' + square + '/'
    os.makedirs(d, exist_ok=True)
    return d


def square_box(square):
    for r in csv.DictReader(open(L.DATA + L.district_of(square) + '/squares.csv', encoding='utf-8')):
        if r['square'] == square:
            return tuple(float(r[k]) for k in ('min_e', 'min_n', 'max_e', 'max_n'))
    raise KeyError(square)


def polygons(geom):
    return [geom['coordinates']] if geom['type'] == 'Polygon' else geom['coordinates']


def shapes(square, year):
    """Region id number -> (land, outline measures of its largest polygon, holes of all polygons counted)."""
    e0, n0, size, w, h, epsg = L.grid(square, year)
    central = (epsg - 32600) * 6 - 183
    g = json.load(open(L.fields_dir(square, year) + f'fields-{year}.geojson', encoding='utf-8'))
    out = {}
    for f in g['features']:
        p = f['properties']
        rid = int(p['id'].split('-F')[1])
        polys = [[[L.to_utm(lon, lat, central) for lon, lat in ring] for ring in poly] for poly in polygons(f['geometry'])]
        main = max(polys, key=lambda poly: abs(ll.ring_area(poly[0])))
        rings = [r for poly in polys for r in poly]
        m = ll.shape_measures([main[0]] + [r for r in rings if r is not main[0]])
        out[rid] = dict(land=p['land'], uniformity=p.get('uniformity', ''), parts=len(polys), holes=len(rings) - len(polys),
                        poly_ha=round(m['area_ha'], 2), perimeter_m=round(m['perimeter_m'], 1),
                        compactness=round(m['compactness'], 4),
                        straight=round(ll.straight_share(main[0]), 4),
                        rect_fill=round(ll.rectangle_fill(main[0]), 4),
                        solidity=round(ll.solidity(main[0]), 4))
    return out


def main():
    square, year = sys.argv[1], int(sys.argv[2])
    t0 = time.time()
    od = out_dir(square)
    lab = L.labels(square, year)
    regions = int(lab.max()) + 1
    shape = lab.shape
    e0, n0, size, w, h, epsg = L.grid(square, year)
    water_n = np.zeros(shape, np.uint8)
    clear_n = np.zeros(shape, np.uint8)
    rows = []
    for date, clear_share, scale, offset in L.dates(square, year):
        scl = ll.upsample(tifffile.imread(L.square_dir(square) + f'season-{year}/{date}/scl.tif'), shape)
        clear = np.isin(scl, (4, 5, 6))
        del scl
        b = {k: L.reflectance(square, year, date, k, scale, offset) for k in BANDS10}
        for k in BANDS20:
            b[k] = ll.upsample(L.reflectance(square, year, date, k, scale, offset), shape)
        for k in BANDS10:
            clear &= np.isfinite(b[k])
        clear &= ~ll.snow(b['green'], b['nir'], b['swir16'])
        ndvi = ll.normalised_difference(b['nir'], b['red'])
        mndwi = ll.normalised_difference(b['green'], b['swir16'])
        ndmi = ll.normalised_difference(b['nir'], b['swir16'])
        clear &= np.isfinite(ndvi)
        water = mndwi > 0
        clear_n += clear
        water_n += clear & water
        vals = [ndvi, ndvi * ndvi, mndwi, ndmi] + [b[k] for k in BANDS10 + BANDS20]
        means, share = ll.region_means(lab, clear, vals, regions, MIN_CLEAR)
        ws = ll.region_share(lab, clear, water, regions)
        del b, vals, ndvi, mndwi, ndmi, water
        for r in range(1, regions):
            m = [x[r] for x in means]
            sd = math.sqrt(max(0.0, m[1] - m[0] ** 2)) if np.isfinite(m[0]) else float('nan')
            rows.append([r, date, round(float(share[r]), 4)] +
                        ['' if not np.isfinite(v) else round(float(v), 4) for v in [m[0], sd, m[2], m[3]]] +
                        ['' if not np.isfinite(m[0]) else round(float(ws[r]), 4)] +
                        ['' if not np.isfinite(v) else round(float(v), 4) for v in m[4:]])
    with open(od + f'curves-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        wr = csv.writer(fh)
        wr.writerow(['region', 'date', 'clear_share', 'ndvi', 'ndvi_sd', 'mndwi', 'ndmi', 'water_share'] +
                    list(BANDS10 + BANDS20))
        wr.writerows(rows)

    # lasting water: open water on at least half the clear dates, patches of at least 1 ha (100 pixels)
    lasting = (clear_n >= 5) & (water_n * 2 >= clear_n)
    pl, npl = ndimage.label(lasting)
    sizes = np.bincount(pl.ravel(), minlength=npl + 1)
    big = sizes >= 100
    big[0] = False
    lasting = big[pl]
    np.savez_compressed(od + f'water-{year}.npz', clear_n=clear_n, water_n=water_n)
    if lasting.any():
        dist, idx = ndimage.distance_transform_edt(~lasting, return_indices=True)
        near_patch = pl[idx[0], idx[1]]
        del idx
    else:
        dist = np.full(shape, np.inf)
        near_patch = np.zeros(shape, np.int32)
    dmin = np.asarray(ndimage.minimum(dist, lab, index=np.arange(regions))) * size
    # size of the nearest lasting water patch, read at the region's closest pixel
    flat_lab = lab.ravel()
    flat_d = dist.ravel()
    order = np.lexsort((flat_d, flat_lab))
    first = np.searchsorted(flat_lab[order], np.arange(regions))
    patch_ha = np.zeros(regions)
    for r in range(1, regions):
        k = first[r]
        if k < len(order) and flat_lab[order[k]] == r and np.isfinite(flat_d[order[k]]):
            patch_ha[r] = sizes[near_patch.ravel()[order[k]]] * size * size / 1e4
    del dist, near_patch, order
    lasting_share = ll.region_share(lab, np.ones(shape, bool), lasting, regions)

    enc, enc_by = ll.enclosure(lab, regions)
    counts = np.bincount(lab.ravel(), minlength=regions)
    ys, xs = np.indices(shape)
    cy = np.bincount(lab.ravel(), weights=ys.ravel(), minlength=regions) / np.maximum(counts, 1)
    cx = np.bincount(lab.ravel(), weights=xs.ravel(), minlength=regions) / np.maximum(counts, 1)
    del ys, xs
    sh = shapes(square, year)
    bx = square_box(square)
    central = (epsg - 32600) * 6 - 183
    out = []
    for r in range(1, regions):
        if counts[r] == 0 or r not in sh:
            continue
        east, north = e0 + (cx[r] + 0.5) * size, n0 - (cy[r] + 0.5) * size
        # the square's box is in UTM zone 42; convert when the stack is on another zone
        if epsg != 32642:
            lon, lat = utm_to_lonlat(east, north, central)
            east, north = L.to_utm(lon, lat, 69)
        core = bx[0] + 500 <= east < bx[2] - 500 and bx[1] + 500 <= north < bx[3] - 500
        row = dict(region=r, area_ha=round(counts[r] * size * size / 1e4, 2), core='yes' if core else 'no',
                   east=round(east), north=round(north), enclosure=round(float(enc[r]), 4), enclosed_by=int(enc_by[r]),
                   water_dist_m=round(float(dmin[r]), 1) if np.isfinite(dmin[r]) else '',
                   water_patch_ha=round(float(patch_ha[r]), 2), lasting_water_share=round(float(lasting_share[r]), 4))
        row.update(sh[r])
        out.append(row)
    with open(od + f'shape-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        wr = csv.DictWriter(fh, fieldnames=list(out[0].keys()))
        wr.writeheader()
        wr.writerows(out)
    print(f'{square} {year}: {len(out)} regions, {len(L.dates(square, year))} dates, {time.time() - t0:.0f} s')


def utm_to_lonlat(east, north, central):
    """Inverse of fields_lib.to_utm by Newton steps (accurate to millimetres here)."""
    lon, lat = central, 53.0
    for _ in range(20):
        e, n = L.to_utm(lon, lat, central)
        de, dn = east - e, north - n
        if abs(de) < 1e-3 and abs(dn) < 1e-3:
            break
        lat += dn / 111320.0
        lon += de / (111320.0 * math.cos(math.radians(lat)))
    return lon, lat


if __name__ == '__main__':
    main()
