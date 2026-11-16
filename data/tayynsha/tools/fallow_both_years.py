"""Fallow two seasons running is idle land, not fallow (owner 2026-11-15, Esil: "a lot of fallow fields ... for 2 years
straight"). In northern Kazakhstan a field lies fallow one season in four or five; land the map calls fallow in both
seasons is steppe, pasture or abandoned land, so the site shows it as not farmland.

A fallow region of YEAR is listed when at least SHARE of its pixels were fallow in OTHER too (same square, same grid).
python fallow_both_years.py DISTRICT_DIR YEAR OTHER   writes DISTRICT_DIR/fallow-both-YEAR.csv (square, region)
"""
import csv
import json
import os
import sys

import numpy as np
import tifffile

SHARE = 0.6


def both_years(lab, land, other, other_land):
    """Region ids of `lab` labelled fallow whose pixels lie at least SHARE on fallow of `other`."""
    h, w = min(lab.shape[0], other.shape[0]), min(lab.shape[1], other.shape[1])
    lab, other = lab[:h, :w], other[:h, :w]
    fallow_other = np.zeros(int(other.max()) + 1, bool)
    for k, v in other_land.items():
        if v == 'fallow' and k < fallow_other.size:
            fallow_other[k] = True
    hit = fallow_other[other]
    n = np.bincount(lab.ravel(), minlength=int(lab.max()) + 1)
    m = np.bincount(lab.ravel(), weights=hit.ravel(), minlength=int(lab.max()) + 1)
    return {k for k, v in land.items() if v == 'fallow' and k < n.size and n[k] and m[k] / n[k] >= SHARE}


def read(sq_dir, year):
    p = f'{sq_dir}/fields-{year}/fields-{year}.tif'
    if not os.path.isfile(p):
        return None, None
    lab = tifffile.imread(p).astype(np.int64)
    land = {int(f['properties']['id'].split('-F')[1]): f['properties']['land']
            for f in json.load(open(p.replace('.tif', '.geojson'), encoding='utf-8'))['features']}
    return lab, land


def main():
    ddir, year, other = sys.argv[1].rstrip('/'), int(sys.argv[2]), int(sys.argv[3])
    rows, total = [], 0
    for sq in sorted(os.listdir(ddir)):
        if not sq.startswith('E'):
            continue
        a, la = read(f'{ddir}/{sq}', year)
        b, lb = read(f'{ddir}/{sq}', other)
        if a is None or b is None:
            continue
        ids = both_years(a, la, b, lb)
        total += sum(1 for v in la.values() if v == 'fallow')
        rows += [{'square': sq, 'region': f'{year}-F{k:04d}'} for k in sorted(ids)]
    with open(f'{ddir}/fallow-both-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'region'])
        w.writeheader()
        w.writerows(rows)
    print(f'{ddir} {year}: {len(rows)} of {total} fallow regions were fallow in {other} too')


if __name__ == '__main__':
    main()
