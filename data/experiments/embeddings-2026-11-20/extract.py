"""Mean satellite embedding (64 bands) per field (NOTE.md step 2), Earth Engine project qazagrosat-ee.
Field outlines (fields-Y.geojson) are lon/lat (EPSG:4326), not UTM: checked 2026-11-20 before the first run.
Each field is shrunk 20 m; ee area of the shrunk outline is written beside our area_ha as a placement check.
python extract.py OUT.csv          (reads the dev keys n/m/k in ../esil-nocrop-2024-11-20 and g-key.csv here)"""
import csv
import json
import os
import sys

import ee

D = 'C:/Programming Projects/qaz-agro-sat/data/'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
DEV = D + 'experiments/esil-nocrop-2024-11-20/'
BANDS = [f'A{i:02d}' for i in range(64)]


def items():
    """(code, set, district, year, square, field, ha) for the 120 dev fields and the 40 blind-check fields."""
    out = []
    for p in 'nmk':
        for r in csv.DictReader(open(f'{DEV}{p}-key.csv')):
            out.append((r['code'], 'dev', r.get('district', 'esil-akmola'), int(r.get('year', 2024)), r['square'],
                        r['field'], float(r['ha'])))
    for r in csv.DictReader(open(HERE + 'g-key.csv')):
        out.append((r['code'], 'test', 'esil-akmola', 2024, r['square'], r['field'], float(r['ha'])))
    return out


def geom(district, sq, field, year):
    g = json.load(open(f'{D}{district}/{sq}/fields-{year}/fields-{year}.geojson', encoding='utf-8'))
    f = next(x for x in g['features'] if x['properties']['id'] == field)
    c = f['geometry']['coordinates']
    rings = c if f['geometry']['type'] == 'Polygon' else max(c, key=lambda p: len(p[0]))
    return ee.Geometry.Polygon(rings, 'EPSG:4326', False).buffer(-20, 1)


def main():
    out = sys.argv[1]
    ee.Initialize(project='qazagrosat-ee')
    rows = items()
    with open(out, 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['code', 'set', 'district', 'year', 'square', 'field', 'ha', 'ee_ha'] + BANDS)
        for year in sorted({r[3] for r in rows}):
            if year > 2025:  # the collection holds 2017-2025 (checked 2026-11-20): 2026 dev fields are left out
                print(year, 'no embeddings, left out:', sum(r[3] == year for r in rows), flush=True)
                continue
            img = (ee.ImageCollection('GOOGLE/SATELLITE_EMBEDDING/V1/ANNUAL')
                   .filterDate(f'{year}-01-01', f'{year + 1}-01-01').mosaic())
            mine = [r for r in rows if r[3] == year]
            fc = ee.FeatureCollection([ee.Feature(geom(r[2], r[4], r[5], year), {'code': r[0]}) for r in mine])
            fc = fc.map(lambda f: f.set('ee_ha', f.geometry().area(1).divide(1e4)))
            res = img.reduceRegions(fc, ee.Reducer.mean(), 10).getInfo()['features']
            got = {f['properties']['code']: f['properties'] for f in res}
            for r in mine:
                p = got[r[0]]
                w.writerow(list(r) + [round(p['ee_ha'], 1)] + [p.get(b) for b in BANDS])
            print(year, len(mine), flush=True)


if __name__ == '__main__':
    main()
