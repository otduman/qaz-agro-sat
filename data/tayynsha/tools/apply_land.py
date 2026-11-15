"""Put the land labels of rule v2 (land_rule.py) on a square's field map, so the series, harvest and crop-group steps
run on the new fields. Only the `land` property of each region in fields-<year>.geojson changes; outlines and ids stay.

python apply_land.py SQUARE_DIR YEAR LAND_V2_CSV
"""
import copy
import csv
import json
import sys


def relabel(geojson, rows):
    """(geojson with land = land_v2 for every region, number changed). Refuses (ValueError) a table that does not fit
    this field map: a region missing, or a current label that differs from the map's."""
    by_id = {r['id']: r for r in rows}
    for f in geojson['features']:
        p = f['properties']
        r = by_id.get(p['id'])
        if r is None or r['land'] != p['land']:
            raise ValueError(f"land table does not fit the field map at region {p['id']}")
    geojson = copy.deepcopy(geojson)
    changed = 0
    for f in geojson['features']:
        p = f['properties']
        r = by_id[p['id']]
        if r['land_v2'] != p['land']:
            p['land'] = r['land_v2']
            changed += 1
    return geojson, changed


def main():
    square, year, table = sys.argv[1].rstrip('/'), sys.argv[2], sys.argv[3]
    path = f'{square}/fields-{year}/fields-{year}.geojson'
    g, changed = relabel(json.load(open(path, encoding='utf-8')),
                         list(csv.DictReader(open(table, encoding='utf-8'))))
    with open(path, 'w', encoding='utf-8') as fh:
        json.dump(g, fh)
    print(f'{square}: {changed} of {len(g["features"])} regions relabelled')


if __name__ == '__main__':
    main()
