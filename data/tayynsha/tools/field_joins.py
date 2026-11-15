"""Field joins, rule v3 (docs/superpowers/notes/2026-11-13-field-joins.md): pieces of one field put back together.

Two field regions of one square that touch along at least 200 m join when
  small piece  one is under 15 ha, the other at least 5 times its size, and they touch along at least 30% of the
               small one's outline; or
  puzzle       they touch along at least 15% of the smaller one's outline, together they are at least as rectangular
               (rectangle fill) as the more rectangular of the two, and their NDVI curves agree (third-largest gap
               over common clear dates at most 0.15, at least 5 dates);
and, either way, the border between them is crooked (its straight extent under 0.7 of its length), or the other
season's field map has one field over most of both, or both were cut within 3 days of each other; or (v3, the
owner's call for fewer, whole fields)
  same field   they touch along at least 15% of the smaller one's outline, their curves agree closely (gap at most
               0.10) and both were cut within 3 days -- or, when either has no cut date, their curves agree very
               closely (gap at most 0.08) -- and together they are almost as rectangular as the better one (within
               0.05); across a straight border too, so strips of one crop cut together may join.
Never two pieces with different known holders, nor a copy of a field the neighbouring square counts (duplicates-YEAR.csv).
Joins chain into groups.

python field_joins.py DISTRICT_DIR YEAR [OTHER_YEAR]     writes DISTRICT_DIR/joins-YEAR.geojson (one feature per
                                                          group: square, members, keep = the largest member, outline)
"""
import csv
import datetime as dt
import json
import os
import sys
from collections import Counter, defaultdict

import cv2
import numpy as np
import tifffile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import land_lib as LL  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../site/build'))
import geo  # noqa: E402

PX = 10.0
MIN_TOUCH_M = 200.0
SMALL_HA, SMALL_RATIO, SMALL_TOUCH = 15.0, 5.0, 0.30
TOUCH, GAP, GAP_DATES, CLEAR = 0.15, 0.15, 5, 0.8
CROOKED, CUT_DAYS, OTHER_SHARE = 0.7, 3, 0.6
SAME_GAP, SAME_GAP_UNDATED, SAME_FILL_SLACK = 0.10, 0.08, 0.05


def decide(p):
    """'small piece', 'puzzle' or None for one touching pair (a dict of its measures)."""
    if p['holder_a'] and p['holder_b'] and p['holder_a'] != p['holder_b']:
        return None
    lo, hi = sorted((p['ha_a'], p['ha_b']))
    kind = None
    if lo < SMALL_HA and hi >= SMALL_RATIO * lo and p['touch_share'] >= SMALL_TOUCH:
        kind = 'small piece'
    elif (p['touch_share'] >= TOUCH and p['fill_join'] >= max(p['fill_a'], p['fill_b']) and p['gap'] is not None
          and p['gap'] <= GAP):
        kind = 'puzzle'
    if kind and (p['border_straight'] < CROOKED or p['one_field_other_year']
                 or (p['cut_days'] is not None and p['cut_days'] <= CUT_DAYS)):
        return kind
    # v3 (owner, 2026-11-13: fewer, whole fields): neighbours whose curves agree closely and that were cut within a
    # few days join even across a straight border, when together they still make one regular shape
    dated = p['cut_days'] is not None
    if (p['touch_share'] >= TOUCH and p['gap'] is not None
            and (p['gap'] <= SAME_GAP and dated and p['cut_days'] <= CUT_DAYS or p['gap'] <= SAME_GAP_UNDATED and not dated)
            and p['fill_join'] >= max(p['fill_a'], p['fill_b']) - SAME_FILL_SLACK):
        return 'same field'
    return None


def groups(links):
    parent = {}

    def find(x):
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b in links:
        parent[find(a)] = find(b)
    out = defaultdict(list)
    for x in list(parent):
        out[find(x)].append(x)
    return sorted(sorted(g) for g in out.values())


def outline_px(lab, members):
    """Outer ring (pixel column, row) of the members' pixels together."""
    mask = np.isin(lab, members).astype(np.uint8)
    cs, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    c = max(cs, key=cv2.contourArea)[:, 0, :]
    return [(int(x), int(y)) for x, y in c]


def geokey_epsg(keys):
    """The projected CRS code (GeoKey 3072) from a GeoTIFF GeoKeyDirectory."""
    keys = list(keys)
    return {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072)


def central_meridian(epsg):
    return (epsg - 32600) * 6 - 183


def to_utm(ring, x0, y0):
    return [(x0 + (x + 0.5) * PX, y0 - (y + 0.5) * PX) for x, y in ring]


def _ring_m(mask):
    cs, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    c = max(cs, key=cv2.contourArea)[:, 0, :].astype(float) * PX
    return np.vstack([c, c[:1]])


def _curves(path):
    c = defaultdict(dict)
    for r in csv.DictReader(open(path, encoding='utf-8')):
        if r['ndvi_median'] and float(r['clear_share'] or 0) >= CLEAR:
            c[r['field']][r['date']] = float(r['ndvi_median'])
    return c


def _gap(ca, cb):
    d = sorted((abs(ca[t] - cb[t]) for t in ca if t in cb), reverse=True)
    return d[2] if len(d) >= GAP_DATES else None


def square_pairs(lab, ids, year, curves, other, other_land, cuts, holders):
    """Measures of every touching pair of the given field ids in one square's region raster."""
    num = {int(i.split('-F')[1]): i for i in ids}
    is_f = np.isin(lab, list(num))
    touch, edge = defaultdict(int), defaultdict(list)
    for a, b, sl in ((lab[:, :-1], lab[:, 1:], (slice(None), slice(None, -1))),
                     (lab[:-1, :], lab[1:, :], (slice(None, -1), slice(None)))):
        m = (a != b) & is_f[sl] & np.isin(b, list(num))
        ys, xs = np.nonzero(m)
        for y, x, u, v in zip(ys, xs, a[m], b[m]):
            k = (min(u, v), max(u, v))
            touch[k] += 1
            edge[k].append((x, y))
    info = {}
    out = []
    for (u, v), n in touch.items():
        if n * PX < MIN_TOUCH_M:
            continue
        pts = np.asarray(edge[(u, v)], float)
        c = pts - pts.mean(0)
        proj = c @ np.linalg.svd(c, full_matrices=False)[2][0]
        ys, xs = np.nonzero((lab == u) | (lab == v))
        sub = lab[max(ys.min() - 1, 0):ys.max() + 2, max(xs.min() - 1, 0):xs.max() + 2]
        for w in (u, v):
            if w not in info:
                mw = lab == w
                ring = _ring_m(sub == w)
                dom = None
                if other is not None:
                    vals = other[mw]
                    vals = vals[vals > 0]
                    if len(vals):
                        k, cnt = Counter(vals.tolist()).most_common(1)[0]
                        if cnt / mw.sum() >= OTHER_SHARE and other_land.get(k) in ('field', 'fallow'):
                            dom = k
                info[w] = dict(perim=float(cv2.arcLength(ring.astype(np.float32), True)),
                               ha=float(mw.sum()) * PX * PX / 1e4, fill=LL.rectangle_fill(ring), dom=dom)
        ia, ib = num[u], num[v]
        ca, cb = cuts.get(ia), cuts.get(ib)
        days = abs((dt.date.fromisoformat(ca) - dt.date.fromisoformat(cb)).days) if ca and cb else None
        out.append(dict(a=ia, b=ib, ha_a=info[u]['ha'], ha_b=info[v]['ha'],
                        touch_share=n * PX / min(info[u]['perim'], info[v]['perim']),
                        border_straight=(proj.max() - proj.min()) / n,
                        fill_a=info[u]['fill'], fill_b=info[v]['fill'],
                        fill_join=LL.rectangle_fill(_ring_m((sub == u) | (sub == v))),
                        gap=_gap(curves.get(ia, {}), curves.get(ib, {})),
                        one_field_other_year=info[u]['dom'] is not None and info[u]['dom'] == info[v]['dom'],
                        cut_days=days, holder_a=holders.get(ia, ''), holder_b=holders.get(ib, '')))
    return out


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    oyear = int(sys.argv[3]) if len(sys.argv) > 3 else (2025 if year == 2026 else 2026)
    table = defaultdict(dict)
    cuts = defaultdict(dict)
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        if r['land'] == 'field':
            table[r['square']][r['field']] = float(r['area_ha'])
            if r['cut_middle']:
                cuts[r['square']][r['field']] = r['cut_middle']
    dup = f'{ddir}/duplicates-{year}.csv'
    if os.path.isfile(dup):    # a second copy of a field from the neighbouring square is not drawn: never join it
        for r in csv.DictReader(open(dup, encoding='utf-8')):
            table[r['square']].pop(r['region'], None)
            cuts[r['square']].pop(r['region'], None)
    holders = defaultdict(dict)
    hp = f'{ddir}/holders.csv'
    if os.path.isfile(hp):
        for r in csv.DictReader(open(hp, encoding='utf-8-sig')):
            holders[r['square']][r['field']] = r['holder']
    feats, kinds = [], Counter()
    for sq, ids in sorted(table.items()):
        tif = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
        if not os.path.isfile(tif):
            continue
        t = tifffile.TiffFile(tif)
        lab = t.pages[0].asarray().astype(np.int64)
        x0, y0 = t.pages[0].tags[33922].value[3:5]
        lon0 = central_meridian(geokey_epsg(t.pages[0].tags[34735].value) or 32642)    # the raster's own zone
        otif = f'{ddir}/{sq}/fields-{oyear}/fields-{oyear}.tif'
        other, oland = None, {}
        if os.path.isfile(otif):
            other = tifffile.imread(otif).astype(np.int64)
            oland = {int(f['properties']['id'].split('-F')[1]): f['properties']['land'] for f in json.load(open(
                f'{ddir}/{sq}/fields-{oyear}/fields-{oyear}.geojson', encoding='utf-8'))['features']}
        curves = _curves(f'{ddir}/{sq}/series-{year}/series-{year}.csv')
        links = []
        for p in square_pairs(lab, ids, year, curves, other, oland, cuts[sq], holders[sq]):
            k = decide(p)
            if k:
                kinds[k] += 1
                links.append((p['a'], p['b']))
        for g in groups(links):
            ring = to_utm(outline_px(lab, [int(i.split('-F')[1]) for i in g]), x0, y0)
            ring = [tuple(q) for q in LL.simplify(ring, PX)]    # within one pixel; far fewer points
            ll = [list(geo.from_utm42(e, n, lon0=lon0)) for e, n in ring]
            feats.append(dict(type='Feature', properties=dict(square=sq, members=g, keep=max(g, key=lambda i: ids[i])),
                              geometry=dict(type='Polygon', coordinates=[[[round(a, 6), round(b, 6)] for a, b in ll + ll[:1]]])))
        print(sq, len(ids), 'fields,', len(links), 'joins', flush=True)
    with open(f'{ddir}/joins-{year}.geojson', 'w', encoding='utf-8') as fh:
        json.dump(dict(type='FeatureCollection', features=feats), fh)
    print(f'{ddir} {year}: {sum(kinds.values())} joins ({dict(kinds)}), {len(feats)} groups, '
          f'{sum(len(f["properties"]["members"]) - 1 for f in feats)} fewer fields')


if __name__ == '__main__':
    main()
