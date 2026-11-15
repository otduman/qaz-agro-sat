"""After rule v2 is on a square's field map (apply_land.py, then series, harvest and crop groups): a region that rule v2
made a new field, but where the crop step sees no crop ("no crop seen"), goes back to its old label (other or fallow).
New fields stay only where the satellite also sees a crop (owner's decision, 2026-11-13). Edits the field map, the
series field table, the harvest table and both crop tables; series and harvest values of other regions are unchanged.

python drop_no_crop.py SQUARE_DIR YEAR LAND_V2_CSV      prints the ids sent back, one per line, with their label
"""
import copy
import csv
import json
import sys


def reverts(table, groups):
    """{id: old label} for new fields (old label not field, v2 field) whose crop group is 'no crop seen'."""
    return {r['id']: r['land'] for r in table
            if r['land'] != 'field' and r['land_v2'] == 'field' and groups.get(r['id']) == 'no crop seen'}


def relabel_rows(rows, key, back):
    out = []
    for r in rows:
        r = dict(r)
        if r[key] in back:
            r['land'] = back[r[key]]
        out.append(r)
    return out


def drop_rows(rows, key, back):
    return [dict(r) for r in rows if r[key] not in back]


def _read(path):
    with open(path, encoding='utf-8') as fh:
        rd = csv.DictReader(fh)
        return rd.fieldnames, list(rd)


def _write(path, names, rows):
    with open(path, 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=names)
        w.writeheader()
        w.writerows(rows)


def main():
    square, year, table = sys.argv[1].rstrip('/'), sys.argv[2], sys.argv[3]
    _, crops = _read(f'{square}/crops-{year}-v2.csv')
    back = reverts(_read(table)[1], {c['field']: c['group'] for c in crops})
    if not back:
        return
    gp = f'{square}/fields-{year}/fields-{year}.geojson'
    g = copy.deepcopy(json.load(open(gp, encoding='utf-8')))
    for f in g['features']:
        if f['properties']['id'] in back:
            f['properties']['land'] = back[f['properties']['id']]
    with open(gp, 'w', encoding='utf-8') as fh:
        json.dump(g, fh)
    p = f'{square}/series-{year}/fields-{year}.csv'
    names, rows = _read(p)
    _write(p, names, relabel_rows(rows, 'id', back))
    for p in (f'{square}/harvest-{year}/harvest-{year}.csv', f'{square}/crops-{year}.csv',
              f'{square}/crops-{year}-v2.csv'):
        names, rows = _read(p)
        _write(p, names, drop_rows(rows, 'field', back))
    for i, lab in sorted(back.items()):
        print(f'{i},{lab}')


if __name__ == '__main__':
    main()
