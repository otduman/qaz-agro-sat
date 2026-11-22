"""Daily rain per district (data/experiments/radar-harvest-2026-11-21/NOTE.md, idea 2): ERA5-Land daily total
precipitation (ECMWF/ERA5_LAND/DAILY_AGGR), mean over the district outline, 15 Jul - 31 Oct, in millimetres.
Earth Engine project qazagrosat-ee (the owner's sign-in on this laptop).

python rain.py BOUNDARY.geojson YEAR OUT.csv      writes date,mm (one row per day the archive has)
"""
import csv
import json
import sys

FIRST, LAST = '07-15', '10-31'


def rows_from(features, year):
    """[(date, mm)] from reduceRegion results [{date, total_precipitation_sum (m)}], sorted, missing values left out."""
    out = []
    for f in features:
        v = f.get('total_precipitation_sum')
        if v is not None:
            out.append((f['date'], round(v * 1000, 1)))
    return sorted(out)


def main():
    import ee
    boundary, year, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    ee.Initialize(project='qazagrosat-ee')
    g = json.load(open(boundary, encoding='utf-8'))
    geom = ee.FeatureCollection([ee.Feature(ee.Geometry(f['geometry'])) for f in g['features']]).geometry()
    col = (ee.ImageCollection('ECMWF/ERA5_LAND/DAILY_AGGR').select('total_precipitation_sum')
           .filterDate(f'{year}-{FIRST}', ee.Date(f'{year}-{LAST}').advance(1, 'day')))

    def one(img):
        v = img.reduceRegion(ee.Reducer.mean(), geom, 11132, maxPixels=1e9)
        return ee.Feature(None, v).set('date', img.date().format('YYYY-MM-dd'))

    feats = [f['properties'] for f in ee.FeatureCollection(col.map(one)).getInfo()['features']]
    with open(out, 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['date', 'mm'])
        w.writerows(rows_from(feats, year))
    print(out, len(feats), 'days')


if __name__ == '__main__':
    main()
