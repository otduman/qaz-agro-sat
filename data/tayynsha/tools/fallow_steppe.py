"""Esil fallow sorter, step 1 (owner 2026-11-15; note 2026-11-13-land-labels.md section 8): a region the map calls
fallow that is green or damp in spring is steppe or meadow, not farmland. Tilled land here is dry and bare in spring.

A fallow region is listed when the median NDMI = (NIR - SWIR 1.6) / (NIR + SWIR 1.6) over its clear dates up to
15 June is at least NDMI_MIN. Clear dates and NDMI come from land_features.py (curves-YEAR.csv in the land-label
experiment folder). Checked blind on 60 fresh Esil regions: 17 of 24 non-crop out, no crop and 2 of 14 fallow moved.

python fallow_steppe.py DISTRICT_DIR YEAR   writes DISTRICT_DIR/fallow-steppe-YEAR.csv (square, region)
"""
import csv
import json
import os
import statistics
import sys

NDMI_MIN = -0.15
EXP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'experiments', 'land-labels-2026-11-13')


def steppe(rows, fallow_ids, year):
    """Region numbers among fallow_ids whose median spring NDMI over clear dates is at least NDMI_MIN."""
    spring = {}
    for r in rows:
        k = int(r['region'])
        if k in fallow_ids and r['ndvi'] and r['date'] <= f'{year}-06-15' and r['ndmi']:
            spring.setdefault(k, []).append(float(r['ndmi']))
    return {k for k, v in spring.items() if statistics.median(v) >= NDMI_MIN}


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    dist = os.path.basename(ddir)
    out, total, missing = [], 0, []
    for sq in sorted(os.listdir(ddir)):
        g = f'{ddir}/{sq}/fields-{year}/fields-{year}.geojson'
        if not sq.startswith('E') or not os.path.isfile(g):
            continue
        c = f'{EXP}/{dist}/{sq}/curves-{year}.csv'
        if not os.path.isfile(c):
            missing.append(sq)
            continue
        fallow = {int(f['properties']['id'].split('-F')[1]) for f in json.load(open(g, encoding='utf-8'))['features']
                  if f['properties']['land'] == 'fallow'}
        total += len(fallow)
        ids = steppe(csv.DictReader(open(c, encoding='utf-8')), fallow, year)
        out += [{'square': sq, 'region': f'{year}-F{k:04d}'} for k in sorted(ids)]
    with open(f'{ddir}/fallow-steppe-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'region'])
        w.writeheader()
        w.writerows(out)
    print(f'{ddir} {year}: {len(out)} of {total} fallow regions green or damp in spring;'
          f' no land evidence for {missing or "none"}')


if __name__ == '__main__':
    main()
