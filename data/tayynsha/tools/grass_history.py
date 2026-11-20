"""Grass history v1 (data/experiments/grass-history-2026-11-18/NOTE.md): a grass region that lay at least SHARE under
crop fields of another season of the same square (same 10 m grid) was a crop field then, so it leaves the grass layer.

Crop fields = field-map regions whose crop group (crops-X-v2.csv) is in CROP; perennial grass (sown hay) does not count.
Region masks: <square>/fields-YEAR/fields-YEAR.tif, or for Tayynsha the L2 labels in REGIONS (ids >= 100000).

python grass_history.py DISTRICT_DIR YEAR     writes DISTRICT_DIR/grass-was-field-YEAR.csv
"""
import csv
import os
import sys

import numpy as np
import tifffile

SHARE = 0.5
CROP = ('short season', 'long season', 'yellow flowers')
REGIONS = 'C:/Programming Projects/qaz-agro-sat/data/experiments/grass-tayynsha-2026-11-17/{sq}/labels-{year}.npz'


def crop_pixels(lab, groups, year):
    """Bool raster: pixels of regions whose crop group (groups: field id like 2025-F0001 -> group) is in CROP."""
    hit = np.zeros(int(lab.max()) + 1, bool)
    for fid, g in groups.items():
        k = int(fid.split('-F')[1])
        if g in CROP and k < hit.size:
            hit[k] = True
    return hit[lab]


def shares(lab, ids, crop):
    """region id -> share of its pixels (whole region) that are crop pixels (on the part both rasters cover)."""
    n = np.bincount(lab.ravel(), minlength=int(lab.max()) + 1)
    h, w = min(lab.shape[0], crop.shape[0]), min(lab.shape[1], crop.shape[1])
    m = np.bincount(lab[:h, :w].ravel(), weights=crop[:h, :w].ravel(), minlength=n.size)
    return {k: m[k] / n[k] for k in ids if k < n.size and n[k]}


def moved(lab, ids, crops_by_year):
    """region id -> (share, season) for regions at least SHARE crop in some other season (the largest share)."""
    best = {}
    for year, crop in sorted(crops_by_year.items(), reverse=True):
        for k, s in shares(lab, ids, crop).items():
            if s >= SHARE and s > best.get(k, (0, 0))[0]:
                best[k] = (s, year)
    return best


def region_raster(sq_dir, sq, year):
    p = REGIONS.format(sq=sq, year=year)
    if os.path.isfile(p) and os.path.isfile(f'{sq_dir}/other-{year}.geojson'):
        return np.load(p)['lab'].astype(np.int64)
    return tifffile.imread(f'{sq_dir}/fields-{year}/fields-{year}.tif').astype(np.int64)


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    grass = {}
    for r in csv.DictReader(open(f'{ddir}/grass-{year}.csv', encoding='utf-8')):
        grass.setdefault(r['square'], {})[int(r['region'])] = float(r['area_ha'])
    rows = []
    for sq, ids in sorted(grass.items()):
        sq_dir = f'{ddir}/{sq}'
        crops = {}
        for other in (year - 1, year - 2, year + 1):
            t, c = f'{sq_dir}/fields-{other}/fields-{other}.tif', f'{sq_dir}/crops-{other}-v2.csv'
            if os.path.isfile(t) and os.path.isfile(c):
                groups = {x['field']: x['group'] for x in csv.DictReader(open(c, encoding='utf-8'))}
                crops[other] = crop_pixels(tifffile.imread(t).astype(np.int64), groups, other)
        if not crops:
            continue
        for k, (s, other) in sorted(moved(region_raster(sq_dir, sq, year), set(ids), crops).items()):
            rows.append(dict(square=sq, region=k, area_ha=ids[k], share=round(s, 3), season=other))
    with open(f'{ddir}/grass-was-field-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'region', 'area_ha', 'share', 'season'])
        w.writeheader()
        w.writerows(rows)
    n, ha = sum(len(v) for v in grass.values()), sum(sum(v.values()) for v in grass.values())
    print(f'{ddir} {year}: {len(rows)} of {n} grass regions were crop fields '
          f'({sum(r["area_ha"] for r in rows):.0f} of {ha:.0f} ha)')


if __name__ == '__main__':
    main()
