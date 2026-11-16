"""Click targets for holders in Esil / Sandyktau (2026-11-15): the shared-border groups of neighbours.py (neighbouring
field or fallow regions whose shared border of 200 m or more has a plot line along under 20% of it are one plot),
per processed 2026 square; for each group of at least MIN_HA without a known holder, one point deep inside its largest
region, in map coordinates (QazTRF-23 / GK zone 12, EPSG:10946), taken only when it lies in the square's core (so two
overlapping squares do not both ask). Writes targets-<dist>.csv (target, square, fields, group_ha, field, E, N) and
groups-<dist>.csv (square, field, group). ml-env python: python targets.py DIST [MIN_HA]"""
import csv
import json
import os
import sys
from collections import Counter, defaultdict

import numpy as np
import tifffile
from pyproj import Transformer
from scipy import ndimage
from shapely.geometry import Point, shape
from shapely.ops import transform as shp_transform

D = 'C:/Programming Projects/qaz-agro-sat/data/'
HERE = D + 'experiments/cadastre-edges-2026-11-15/'
DIST = sys.argv[1]
MIN_HA = float(sys.argv[2]) if len(sys.argv) > 2 else 150
NEAR_M, SEPARATED, MIN_BORDER_PX = 30, 0.2, 20


def find(parent, i):
    while parent[i] != i:
        parent[i] = parent[parent[i]]
        i = parent[i]
    return i


def main():
    holder = set()
    hp = D + f'{DIST}/holders.csv'
    if os.path.isfile(hp):
        holder = {(r['square'], int(r['field'].split('-F')[1])) for r in csv.DictReader(open(hp, encoding='utf-8-sig'))}
    core = {r['square']: [float(r[k]) for k in ('min_e', 'min_n', 'max_e', 'max_n')]
            for r in csv.DictReader(open(D + f'{DIST}/squares.csv', encoding='utf-8'))}
    g = json.load(open(D + f'../docs/districts/{DIST}.geojson', encoding='utf-8'))
    district = shp_transform(Transformer.from_crs(4326, 10946, always_xy=True).transform, shape(g['features'][0]['geometry']))
    district = district.buffer(-300)     # the plot lines of the selected district only; a field across the edge is left
    targets, groups_out, tot = [], [], Counter()
    for sq in sorted(os.listdir(D + DIST)):
        p = D + f'{DIST}/{sq}/fields-2026/fields-2026.tif'
        if not (sq.startswith('E') and os.path.isfile(p) and os.path.isfile(D + f'{DIST}/{sq}/harvest-2026/harvest-2026.csv')
                and os.path.isfile(HERE + f'lines/{DIST}/{sq}.tif')):
            continue
        page = tifffile.TiffFile(p).pages[0]
        lab = page.asarray().astype(np.int64)
        x0, y0 = page.tags[33922].value[3:5]
        keys = list(page.tags[34735].value)
        epsg = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
        to_gk = Transformer.from_crs(epsg, 10946, always_xy=True)
        to42 = Transformer.from_crs(epsg, 32642, always_xy=True)
        land = {int(f['properties']['id'].split('-F')[1]): f['properties']['land']
                for f in json.load(open(p.replace('.tif', '.geojson'), encoding='utf-8'))['features']}
        n = lab.max() + 1
        crop = np.zeros(n, bool)
        for k, v in land.items():
            if k < n:
                crop[k] = v in ('field', 'fallow')
        lines = tifffile.imread(HERE + f'lines/{DIST}/{sq}.tif') > 0
        seen = tifffile.imread(HERE + f'seen/{DIST}/{sq}.tif') > 0
        near = ndimage.distance_transform_edt(~lines) * 10 <= NEAR_M
        pair_len, pair_near = Counter(), Counter()
        for dy, dx in ((0, 1), (1, 0)):
            a, b = lab[:lab.shape[0] - dy, :lab.shape[1] - dx], lab[dy:, dx:]
            m = (a != b) & crop[a] & crop[b] & seen[:lab.shape[0] - dy, :lab.shape[1] - dx]
            key = np.minimum(a[m], b[m]) * n + np.maximum(a[m], b[m])
            u, c = np.unique(key, return_counts=True)
            pair_len.update(dict(zip(u.tolist(), c.tolist())))
            u2, c2 = np.unique(key[near[:lab.shape[0] - dy, :lab.shape[1] - dx][m]], return_counts=True)
            pair_near.update(dict(zip(u2.tolist(), c2.tolist())))
        parent = list(range(n))
        for key, ln in pair_len.items():
            if ln >= MIN_BORDER_PX and pair_near.get(key, 0) / ln < SEPARATED:
                a, b = divmod(key, n)
                ra, rb = find(parent, a), find(parent, b)
                if ra != rb:
                    parent[ra] = rb
        area = np.bincount(lab.ravel(), minlength=n) * 0.01
        groups = defaultdict(list)
        for f in range(1, n):
            if crop[f]:
                groups[find(parent, f)].append(f)
        objs = ndimage.find_objects(lab)
        e0, n0, e1, n1 = core.get(sq, (-1e9, -1e9, 1e9, 1e9))
        for g, members in groups.items():
            for f in members:
                groups_out.append({'square': sq, 'field': f'2026-F{f:04d}', 'group': f'{sq}-{g}'})
            gha = sum(area[f] for f in members)
            tot['groups'] += 1
            tot['ha'] += gha
            if any((sq, f) in holder for f in members):
                tot['known_ha'] += gha
                continue
            if gha < MIN_HA:
                continue
            big = max(members, key=lambda f: area[f])
            sl = objs[big - 1]
            sub = lab[sl] == big
            dt = ndimage.distance_transform_edt(np.pad(sub, 1))[1:-1, 1:-1]
            r, c = np.unravel_index(np.argmax(dt), dt.shape)
            ux, uy = x0 + (sl[1].start + c + 0.5) * 10, y0 - (sl[0].start + r + 0.5) * 10
            ex, ny = to42.transform(ux, uy)
            if not (e0 + 500 <= ex < e1 - 500 and n0 + 500 <= ny < n1 - 500):
                continue
            ge, gn = to_gk.transform(ux, uy)
            if not district.contains(Point(ge, gn)):
                continue
            targets.append({'square': sq, 'fields': len(members), 'group_ha': round(gha), 'field': f'2026-F{big:04d}',
                            'group': f'{sq}-{g}', 'E': round(ge, 1), 'N': round(gn, 1)})
            tot['target_ha'] += gha
    targets.sort(key=lambda t: -t['group_ha'])
    for i, t in enumerate(targets, 1):
        t['target'] = f'{os.environ.get("PREFIX", DIST[0])}{i:04d}'
    with open(HERE + f'targets-{DIST}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['target', 'square', 'fields', 'group_ha', 'field', 'group', 'E', 'N'])
        w.writeheader()
        w.writerows(targets)
    with open(HERE + f'groups-{DIST}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'field', 'group'])
        w.writeheader()
        w.writerows(groups_out)
    print(f'{DIST}: {tot["groups"]} groups, {tot["ha"]:,.0f} ha (overlaps counted twice); holder known {tot["known_ha"]:,.0f} ha; '
          f'{len(targets)} targets of {MIN_HA:.0f} ha or more cover {tot["target_ha"]:,.0f} ha')


if __name__ == '__main__':
    main()
