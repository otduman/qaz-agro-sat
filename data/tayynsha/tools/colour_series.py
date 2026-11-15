"""Colour series per field: mean blue, green, red and near-infrared reflectance of each field's clear interior
pixels on every date of the season stack. The season series (series-<year>.csv) has no green and red, which the
crop-group experiment needs (yellow flowers of rapeseed and sunflower show as high green and red).

Interior = field pixels whose four neighbours belong to the same field (drops the mixed edge). Clear = the 20 m
scene classification says vegetation, bare soil or water, as the Java stack reader does, and every band has a value.

Run from the repository root:
python data/tayynsha/tools/colour_series.py data/tayynsha/E560N5960 2026
Writes <square>/colour-<year>.csv (field,date,clear_share,blue,green,red,nir). Throwaway experiment code.
"""
import csv
import os
import sys

import numpy as np
import tifffile

USABLE = (4, 5, 6)
BANDS = ('blue', 'green', 'red', 'nir')


def interior(labels):
    inner = labels > 0
    inner[1:, :] &= labels[1:, :] == labels[:-1, :]
    inner[:-1, :] &= labels[:-1, :] == labels[1:, :]
    inner[:, 1:] &= labels[:, 1:] == labels[:, :-1]
    inner[:, :-1] &= labels[:, :-1] == labels[:, 1:]
    inner[0, :] = inner[-1, :] = False
    inner[:, 0] = inner[:, -1] = False
    return inner


def reflectance(raw, scale, offset, nodata):
    out = raw.astype(np.float64) * scale + offset
    out[raw == nodata] = np.nan
    return out


def field_means(labels, inner, clear, bands, regions):
    """Per band, the mean over each region's clear interior pixels (NaN when none); and the clear share of each
    region's interior."""
    lab = labels[inner].ravel()
    ok = clear[inner].ravel()
    for b in bands:
        ok &= ~np.isnan(b[inner].ravel())
    total = np.bincount(lab, minlength=regions).astype(np.float64)
    n = np.bincount(lab[ok], minlength=regions).astype(np.float64)
    means = []
    for b in bands:
        s = np.bincount(lab[ok], weights=b[inner].ravel()[ok], minlength=regions)
        with np.errstate(invalid='ignore', divide='ignore'):
            means.append(np.where(n > 0, s / n, np.nan))
    with np.errstate(invalid='ignore', divide='ignore'):
        share = np.where(total > 0, n / total, 0.0)
    return means, share


def main():
    square, year = sys.argv[1].rstrip('/'), sys.argv[2]
    labels = tifffile.imread(f'{square}/fields-{year}/fields-{year}.tif').astype(np.int64)
    regions = int(labels.max()) + 1
    inner = interior(labels)
    keep = []
    for r in csv.DictReader(open(f'{square}/series-{year}/fields-{year}.csv', encoding='utf-8')):
        if r['land'] in ('field', 'fallow') and r['in_series'] == 'yes':
            keep.append((int(r['id'].split('-F')[1]), r['id']))
    stack = f'{square}/season-{year}'
    out = []
    for d in csv.DictReader(open(f'{stack}/index.csv', encoding='utf-8')):
        if d['status'] != 'stored':
            continue
        folder = f'{stack}/{d["date"]}'
        scale, offset, nodata = float(d['scale']), float(d['offset']), int(d['nodata'])
        bands = [reflectance(tifffile.imread(f'{folder}/{b}.tif'), scale, offset, nodata) for b in BANDS]
        scl = tifffile.imread(f'{folder}/scl.tif')
        up = np.repeat(np.repeat(scl, 2, axis=0), 2, axis=1)[:labels.shape[0], :labels.shape[1]]
        clear = np.isin(up, USABLE)
        means, share = field_means(labels, inner, clear, bands, regions)
        for k, fid in keep:
            if k < regions and share[k] > 0:
                out.append([fid, d['date'], f'{share[k]:.4f}'] + [f'{m[k]:.4f}' for m in means])
        print(d['date'], end=' ', flush=True)
    path = f'{square}/colour-{year}.csv'
    with open(path, 'w', encoding='utf-8', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['field', 'date', 'clear_share'] + list(BANDS))
        w.writerows(out)
    print(f'\n{len(out)} rows -> {os.path.normpath(path)}')


if __name__ == '__main__':
    main()
