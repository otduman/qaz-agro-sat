"""Sentinel-1 field series (NOTE.md): mean linear VV, VH over each field shrunk 20 m, every IW pass 1 Jul - 31 Oct of
YEAR (COPERNICUS/S1_GRD_FLOAT), Earth Engine project qazagrosat-ee.

python radar.py sample DISTRICT YEAR N SEED OUT_KEY.csv     draw N grain fields with a confident optical cut
python radar.py series KEY.csv YEAR OUT.csv                 code,date,orbit,vv,vh (linear) for every pass
"""
import csv
import json
import os
import random
import sys

D = 'C:/Programming Projects/qaz-agro-sat/data/'


def grain_ids(district, sq, year):
    p = f'{D}{district}/{sq}/crops-{year}-v2.csv'
    if not os.path.isfile(p):
        return set()
    return {r['field'] for r in csv.DictReader(open(p, encoding='utf-8')) if r['group'] == 'short season'}


def sample(district, year, n, seed, out):
    rows = [r for r in csv.DictReader(open(f'{D}{district}/district-harvest-{year}.csv', encoding='utf-8'))
            if r['status'] == 'dated' and r['window_days'] and float(r['window_days']) <= 5 and float(r['area_ha']) >= 50]
    grain = {}
    rows = [r for r in rows if r['field'] in grain.setdefault(r['square'], grain_ids(district, r['square'], year))]
    pick = random.Random(seed).sample(rows, min(n, len(rows)))
    with open(out, 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['code', 'district', 'square', 'field', 'ha', 'cut_from', 'cut_to', 'cut_middle'])
        for i, r in enumerate(pick, 1):
            w.writerow([f'{district[:1].upper()}{i:03d}', district, r['square'], r['field'], r['area_ha'],
                        r['cut_from'], r['cut_to'], r['cut_middle']])
    print(out, len(pick), 'of', len(rows))


def geom(ee, district, sq, field, year):
    g = json.load(open(f'{D}{district}/{sq}/fields-{year}/fields-{year}.geojson', encoding='utf-8'))
    f = next(x for x in g['features'] if x['properties']['id'] == field)
    c = f['geometry']['coordinates']
    rings = c if f['geometry']['type'] == 'Polygon' else max(c, key=lambda p: len(p[0]))
    return ee.Geometry.Polygon(rings, 'EPSG:4326', False).buffer(-20, 1)


def series(key, year, out, chunk=100):
    import ee
    ee.Initialize(project='qazagrosat-ee')
    rows = list(csv.DictReader(open(key)))
    col = (ee.ImageCollection('COPERNICUS/S1_GRD_FLOAT').filter(ee.Filter.eq('instrumentMode', 'IW'))
           .filterDate(f'{year}-07-01', f'{year}-11-01').select(['VV', 'VH']))
    with open(out, 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['code', 'date', 'orbit', 'vv', 'vh'])
        for k in range(0, len(rows), chunk):
            part = rows[k:k + chunk]
            fc = ee.FeatureCollection([ee.Feature(geom(ee, r['district'], r['square'], r['field'], int(year)),
                                                  {'code': r['code']}) for r in part])
            imgs = col.filterBounds(fc.geometry())

            def one(img):
                tag = {'date': img.date().format('YYYY-MM-dd'),
                       'orbit': ee.String(img.get('relativeOrbitNumber_start')).cat(ee.String(img.get('orbitProperties_pass')).slice(0, 1))}
                return img.reduceRegions(fc, ee.Reducer.mean(), 10).map(lambda f: f.set(tag))

            res = imgs.map(one).flatten().filter(ee.Filter.notNull(['VV', 'VH'])).getInfo()['features']
            for f in res:
                p = f['properties']
                w.writerow([p['code'], p['date'], p['orbit'], p['VV'], p['VH']])
            print(k + len(part), 'fields,', len(res), 'readings', flush=True)


if __name__ == '__main__':
    if sys.argv[1] == 'sample':
        sample(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), sys.argv[6])
    else:
        series(sys.argv[2], sys.argv[3], sys.argv[4])
