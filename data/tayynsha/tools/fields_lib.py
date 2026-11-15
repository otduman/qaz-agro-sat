"""Shared reading for the field-detection checks (2026-11-12): label maps, land labels, true-colour images and the
grid of a square. Reads the main checkout's data folder; writes nothing.
"""
import csv
import json
import math
import os

import numpy as np
import tifffile

DATA = 'C:/Programming Projects/qaz-agro-sat/data/'
EXPERIMENT = DATA + 'experiments/fields-2026-11-12/'

TUNING = ('E560N5960 E520N5960 E540N5960 E580N5980 E580N5960 E580N5940 E580N5920 E600N5940 E600N5960 E620N5960 '
          'E560N5940 E560N5980 E500N5980 E500N5960').split()
HELD_OUT_TAYYNSHA = sorted(set(os.listdir(DATA + 'tayynsha')) & {
    f'E{e}N{n}' for e in range(400, 700, 20) for n in range(5700, 6100, 20)} - set(TUNING))
SANDYKTAU = ['E440N5800', 'E460N5800', 'E460N5780']
TWO_SEASONS = ['E500N5960', 'E500N5980', 'E520N5960', 'E540N5960', 'E560N5940', 'E560N5960', 'E580N5920',
               'E580N5940', 'E580N5960', 'E620N5960']


def district_of(square):
    """The district folder holding the square (the first three Sandyktau squares by name, as before)."""
    if square in SANDYKTAU:
        return 'sandyktau'
    for d in ('tayynsha', 'sandyktau', 'esil-akmola'):
        if os.path.isdir(DATA + d + '/' + square):
            return d
    return 'tayynsha'


def square_dir(square):
    return DATA + district_of(square) + '/' + square + '/'


def grid(square, year):
    """(origin east, origin north, pixel size, width, height, epsg) of the square's season stack."""
    props = {}
    for line in open(square_dir(square) + f'season-{year}/stack.properties', encoding='utf-8'):
        if '=' in line:
            k, v = line.strip().split('=', 1)
            props[k] = v
    epsg, e0, n0, size, w, h = props['grid'].split(',')
    return float(e0), float(n0), float(size), int(w), int(h), int(epsg)


def fields_dir(square, year, version=None):
    """The current field map folder, or an experiment's version folder (e.g. 'fields-2026-v2')."""
    if version is None:
        return square_dir(square) + f'fields-{year}/'
    return EXPERIMENT + district_of(square) + '/' + square + '/' + version + '/'


def labels(square, year, version=None):
    return tifffile.imread(fields_dir(square, year, version) + f'fields-{year}.tif').astype(np.int32)


def land(square, year, version=None):
    """Region number -> (land, area_ha), from the map's GeoJSON."""
    g = json.load(open(fields_dir(square, year, version) + f'fields-{year}.geojson', encoding='utf-8'))
    out = {}
    for f in g['features']:
        p = f['properties']
        out[int(p['id'].split('-F')[1])] = (p['land'], p['area_ha'])
    return out


def dates(square, year, min_clear=0.0):
    rows = []
    for r in csv.DictReader(open(square_dir(square) + f'season-{year}/index.csv', encoding='utf-8')):
        if r['status'] == 'stored' and float(r['clear_share']) >= min_clear:
            rows.append((r['date'], float(r['clear_share']), float(r['scale']), float(r['offset'])))
    return rows


def reflectance(square, year, date, band, scale=1e-4, offset=-0.1, window=None):
    a = tifffile.imread(square_dir(square) + f'season-{year}/{date}/{band}.tif')
    if window is not None:
        y0, y1, x0, x1 = window
        a = a[y0:y1, x0:x1]
    r = a.astype(np.float32) * scale + offset
    r[a == 0] = np.nan
    return r


def boundaries(lab):
    """Pixels whose right or lower neighbour has another label (both sides of every crack, 0 included)."""
    b = np.zeros(lab.shape, bool)
    d = lab[:, 1:] != lab[:, :-1]
    b[:, 1:] |= d
    b[:, :-1] |= d
    d = lab[1:, :] != lab[:-1, :]
    b[1:, :] |= d
    b[:-1, :] |= d
    return b


# UTM zone 42 (EPSG 32642) <-> longitude, latitude, after owner_sheet_square.py
_a = 6378137.0
_f = 1 / 298.257223563
_n = _f / (2 - _f)
_A = _a / (1 + _n) * (1 + _n ** 2 / 4 + _n ** 4 / 64 + _n ** 6 / 256)
_al = [None, _n / 2 - 2 * _n ** 2 / 3 + 5 * _n ** 3 / 16 + 41 * _n ** 4 / 180 - 127 * _n ** 5 / 288,
       13 * _n ** 2 / 48 - 3 * _n ** 3 / 5 + 557 * _n ** 4 / 1440 + 281 * _n ** 5 / 630,
       61 * _n ** 3 / 240 - 103 * _n ** 4 / 140 + 15061 * _n ** 5 / 26880,
       49561 * _n ** 4 / 161280 - 179 * _n ** 5 / 168, 34729 * _n ** 5 / 80640]
_e = math.sqrt(_f * (2 - _f))


def to_utm(lon, lat, central=69):
    phi = math.radians(lat)
    lam = math.radians(lon - central)
    t = math.sinh(math.atanh(math.sin(phi)) - _e * math.atanh(_e * math.sin(phi)))
    xi = math.atan2(t, math.cos(lam))
    eta = math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    x = xi + sum(_al[j] * math.sin(2 * j * xi) * math.cosh(2 * j * eta) for j in range(1, 6))
    y = eta + sum(_al[j] * math.cos(2 * j * xi) * math.sinh(2 * j * eta) for j in range(1, 6))
    return 500000 + 0.9996 * _A * y, 0.9996 * _A * x


def pixel_of(square, year, lon, lat):
    e0, n0, size, w, h, epsg = grid(square, year)
    central = (epsg - 32600) * 6 - 183
    east, north = to_utm(lon, lat, central)
    return int((north - n0) / -size), int((east - e0) / size)
