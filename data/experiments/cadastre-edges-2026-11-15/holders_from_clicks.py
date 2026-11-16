"""Holders from the map lookups (holder-clicks-<dist>.csv) carried to the shared-border group of each clicked field
(groups-<dist>.csv, see targets.py), appended to data/<dist>/holders.csv (backup holders-before-clicks.csv; rows already
there win). Only named companies (record.py hides farms, persons, partnerships). A group over BIG_HA has no second
click to confirm it, so there the holder goes only to members whose centre is within NEAR_M of the clicked point.
ml-env python: python holders_from_clicks.py DIST"""
import csv
import math
import os
import shutil
import sys
from collections import defaultdict

from pyproj import Transformer

D = 'C:/Programming Projects/qaz-agro-sat/data/'
HERE = D + 'experiments/cadastre-edges-2026-11-15/'
BIG_HA, NEAR_M = 2500, 3000


_rasters = {}


def field_at(dist, sq, e, n):
    """The 2026 field region under map point (e, n) (GK zone 12, EPSG:10946) in square sq, or None (2026-11-16: a
    square made again has new region numbers, so a lookup is placed by its point, not by the number it was logged
    with)."""
    import tifffile
    p = D + f'{dist}/{sq}/fields-2026/fields-2026.tif'
    if not os.path.isfile(p):
        return None
    if sq not in _rasters:
        page = tifffile.TiffFile(p).pages[0]
        keys = list(page.tags[34735].value)
        epsg = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
        x0, y0 = page.tags[33922].value[3:5]
        res = page.tags[33550].value[0]
        _rasters[sq] = (page.asarray(), x0, y0, res, Transformer.from_crs(10946, epsg, always_xy=True))
    lab, x0, y0, res, tr = _rasters[sq]
    x, y = tr.transform(e, n)
    c, r = int((x - x0) // res), int((y0 - y) // res)
    if 0 <= r < lab.shape[0] and 0 <= c < lab.shape[1] and lab[r, c] > 0:
        return f'2026-F{int(lab[r, c]):04d}'
    return None


def main():
    dist = sys.argv[1]
    to_gk = Transformer.from_crs(4326, 10946, always_xy=True)
    members = defaultdict(list)
    group_of = {}
    for r in csv.DictReader(open(HERE + f'groups-{dist}.csv', encoding='utf-8')):
        members[r['group']].append((r['square'], r['field']))
        group_of[(r['square'], r['field'])] = r['group']
    centre, area = {}, {}
    for sq in {s for g in members.values() for s, _ in g}:
        p = D + f'{dist}/{sq}/series-2026/fields-2026.csv'
        if os.path.isfile(p):
            for r in csv.DictReader(open(p, encoding='utf-8')):
                centre[(sq, r['id'])] = to_gk.transform(float(r['centroid_lon']), float(r['centroid_lat']))
                area[(sq, r['id'])] = float(r['area_ha'])
    hp = D + f'{dist}/holders.csv'
    old = list(csv.DictReader(open(hp, encoding='utf-8-sig'))) if os.path.isfile(hp) else []
    if old and not os.path.isfile(D + f'{dist}/holders-before-clicks.csv'):
        shutil.copy(hp, D + f'{dist}/holders-before-clicks.csv')
    if os.path.isfile(D + f'{dist}/holders-before-clicks.csv'):
        old = list(csv.DictReader(open(D + f'{dist}/holders-before-clicks.csv', encoding='utf-8-sig')))
    have = {(r['square'], r['field']) for r in old}
    new, ha = {}, defaultdict(float)
    for c in csv.DictReader(open(HERE + f'holder-clicks-{dist}.csv', encoding='utf-8')):
        if c['holder'] in ('hidden', 'none', 'skip'):
            continue
        f = field_at(dist, c['square'], float(c['E']), float(c['N']))
        if f:
            c['field'] = f
        # the group the clicked field is in now (group names change when the groups are made again)
        g = members.get(group_of.get((c['square'], c['field'])), [(c['square'], c['field'])])
        gha = sum(area.get(m, 0) for m in g)
        for m in g:
            if m in have or m in new:
                continue
            if gha > BIG_HA and m != (c['square'], c['field']):
                if m not in centre or math.hypot(centre[m][0] - float(c['E']), centre[m][1] - float(c['N'])) > NEAR_M:
                    continue
            new[m] = c['holder']
            ha[c['holder']] += area.get(m, 0)
    rows = old + [{'square': s, 'field': f, 'holder': h, 'holder_type': 'company'} for (s, f), h in sorted(new.items())]
    with open(hp, 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'field', 'holder', 'holder_type'])
        w.writeheader()
        w.writerows(rows)
    print(f'{dist}: {len(old)} rows kept, {len(new)} added ({sum(ha.values()):,.0f} ha, {len(ha)} companies)')


if __name__ == '__main__':
    main()
