"""Evidence joins v1 and small fields (data/experiments/lines-first-2026-11-18/NOTE.md): two fields as the site shows
them join only on positive evidence that they are one field -- the border between them is not a seen line (E1), same
crop group (E2), cut within CUT_DAYS (E3), NDVI curves agree (E4), not two known holders (E5), and together a shape
no worse than the better one (E6). A field still under SMALL_HA afterwards is kept on the map when it is at least
MIN_SMALL_HA and its outline is mostly a seen line.

python evidence_joins.py DISTRICT_DIR YEAR [SQUARE ...]
    rewrites DISTRICT_DIR/joins-YEAR.geojson (first run keeps the old one as joins-YEAR-j6.geojson and always starts
    from it), writes small-fields-YEAR.csv and evidence-joins-YEAR.csv (every join with its measures)
"""
import csv
import datetime as dt
import json
import os
import shutil
import sys

import cv2
import numpy as np
import tifffile
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import field_joins as FJ  # noqa: E402
import land_lib as LL  # noqa: E402
import line_splits as LS  # noqa: E402
import lines_first as LF  # noqa: E402

sys.path.insert(0, os.path.join(HERE, '../../../site/build'))
import geo  # noqa: E402

SEEN_MAX, CUT_DAYS, CURVE_GAP, SMALL_HA, MIN_SMALL_HA, SMALL_SEEN, SMALL_FILL = 0.5, 5, 0.12, 50.0, 20.0, 0.6, 0.75
MIN_BORDER = 10
JOINS = False      # 2026-11-19: joins failed the blind check (NOTE.md); small fields passed and stay


def same_field(a, b, seen, fill_join):
    """E1-E6 for two fields (dicts: ha, crop, cut, holders, fill, curve) with a border seen on `seen` of its length
    and a joint fill."""
    if seen >= SEEN_MAX:
        return False
    if a['crop'] != b['crop']:
        unclear = [f for f in (a, b) if f['crop'] == 'none']
        if len(unclear) != 1 or unclear[0]['ha'] >= SMALL_HA:
            return False
    if bool(a['cut']) != bool(b['cut']):
        return False
    if a['cut'] and abs((dt.date.fromisoformat(a['cut']) - dt.date.fromisoformat(b['cut'])).days) > CUT_DAYS:
        return False
    gap = FJ._gap(a['curve'], b['curve'])
    if gap is None or gap > CURVE_GAP:
        return False
    if a['holders'] and b['holders'] and not (a['holders'] & b['holders']):
        return False
    slack = 0.05 if min(a['ha'], b['ha']) < SMALL_HA else 0.02
    return fill_join >= max(a['fill'], b['fill']) - slack


def real_small_field(mask, seen, px=LF.PX):
    """A field under SMALL_HA worth showing: at least MIN_SMALL_HA, a simple shape (rectangle fill at least SMALL_FILL)
    and its outline seen on at least SMALL_SEEN of its length."""
    if mask.sum() * px * px / 1e4 < MIN_SMALL_HA or union_fill(mask.astype(np.int64), [1]) < SMALL_FILL:
        return False
    if mask[:2].any() or mask[-2:].any() or mask[:, :2].any() or mask[:, -2:].any():
        return False                     # cut by the square's edge: only part of it is here
    edge = mask & ~ndimage.binary_erosion(mask, structure=LF.CROSS)
    return bool(edge.any()) and float(seen[edge].mean()) >= SMALL_SEEN


def union_fill(lab, us):
    """Rectangle fill of units us together; holes that hold no other field count as filled."""
    m = np.isin(lab, us)
    ys, xs = np.nonzero(m)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    sub, mm = lab[y0:y1, x0:x1], m[y0:y1, x0:x1]
    filled = ndimage.binary_fill_holes(mm) & ~((sub > 0) & ~mm)
    (_, _), (w, h), _ = cv2.minAreaRect(np.column_stack([xs, ys]).astype(np.float32))
    return float(filled.sum()) / ((w + 1) * (h + 1))


def square(ddir, sq, year, crops, cut, area, holders):
    units, members, keep = LS.units_of(ddir, sq, year, joins=f'{ddir}/joins-{year}-j6.geojson')
    count = LF.load_count(ddir, sq, year).astype(np.float32)
    seen = ndimage.maximum_filter(count, size=3) >= LF.STRONG
    curves = FJ._curves(f'{ddir}/{sq}/series-{year}/series-{year}.csv')
    facts = {}
    for u, ms in members.items():
        k = keep[u]
        facts[u] = dict(ha=sum(area.get(m, 0) for m in ms), crop=crops.get(k, 'none'), cut=cut.get(k, ''),
                        holders={holders[m] for m in ms if m in holders}, curve=curves.get(k, {}))
    parent = {u: u for u in members}

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    group = {u: [u] for u in members}
    gfacts = {u: dict(facts[u], fill=union_fill(units, [u])) for u in members}
    pairs = []
    for (u, v), (ys, xs) in LF.shared_borders(units).items():
        if len(ys) >= MIN_BORDER and u in members and v in members:
            pairs.append((min(facts[u]['ha'], facts[v]['ha']), u, v, float(seen[np.array(ys), np.array(xs)].mean())))
    joins = []
    for _, u, v, sh in sorted(pairs) if JOINS else []:
        a, b = find(u), find(v)
        if a == b:
            continue
        fj = union_fill(units, group[a] + group[b])
        if not same_field(gfacts[a], gfacts[b], sh, fj):
            continue
        big = a if gfacts[a]['ha'] >= gfacts[b]['ha'] else b
        new = dict(gfacts[big], ha=gfacts[a]['ha'] + gfacts[b]['ha'], holders=gfacts[a]['holders'] | gfacts[b]['holders'],
                   fill=fj)
        parent[b if big == a else a] = big
        group[big] = group[a] + group[b] if big == a else group[b] + group[a]
        gfacts[big] = new
        joins.append(dict(square=sq, a=' '.join(members[u]), b=' '.join(members[v]), ha_a=round(facts[u]['ha']),
                          ha_b=round(facts[v]['ha']), seen=round(sh, 2), fill_join=round(fj, 3)))
    groups = {}
    for u in members:
        groups.setdefault(find(u), []).append(u)
    small = []
    for g, us in groups.items():
        ha = sum(facts[u]['ha'] for u in us)
        if ha < SMALL_HA and real_small_field(np.isin(units, us), seen):
            ms = [m for u in us for m in members[u]]
            small.append(dict(square=sq, keep=max(ms, key=lambda m: area.get(m, 0)), ha=round(ha, 1)))
    joined = [[m for u in us for m in members[u]] for us in groups.values() if len(us) > 1]
    return joined, joins, small


def outline_feature(ddir, sq, year, ms, area):
    t = tifffile.TiffFile(f'{ddir}/{sq}/fields-{year}/fields-{year}.tif')
    reg = t.pages[0].asarray().astype(np.int64)
    x0, y0 = t.pages[0].tags[33922].value[3:5]
    lon0 = FJ.central_meridian(FJ.geokey_epsg(t.pages[0].tags[34735].value) or 32642)
    ring = FJ.to_utm(FJ.outline_px(reg, [int(m.split('-F')[1]) for m in ms]), x0, y0)
    ring = [tuple(q) for q in LL.simplify(ring, FJ.PX)]
    ll = [list(geo.from_utm42(e, n, lon0=lon0)) for e, n in ring]
    return dict(type='Feature', properties=dict(square=sq, members=sorted(ms), keep=max(ms, key=lambda m: area.get(m, 0)),
                                                joined='evidence'),
                geometry=dict(type='Polygon', coordinates=[[[round(a, 6), round(b, 6)] for a, b in ll + ll[:1]]]))


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    want = sys.argv[3:] or [r['square'] for r in csv.DictReader(open(f'{ddir}/squares.csv', encoding='utf-8'))]
    jpath, base = f'{ddir}/joins-{year}.geojson', f'{ddir}/joins-{year}-j6.geojson'
    if not os.path.isfile(base):
        shutil.copy(jpath, base)
    crops, cut, area, holders = {}, {}, {}, {}
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        cut[(r['square'], r['field'])] = r['cut_middle']
        area[(r['square'], r['field'])] = float(r['area_ha'])
    if os.path.isfile(f'{ddir}/holders.csv'):
        for r in csv.DictReader(open(f'{ddir}/holders.csv', encoding='utf-8-sig')):
            if r['holder']:
                holders[(r['square'], r['field'])] = r['holder']
    feats = json.load(open(base, encoding='utf-8'))['features']
    all_joins, all_small = [], []
    for sq in want:
        if not os.path.isfile(LF.count_path(ddir, sq, year)) or not os.path.isfile(f'{ddir}/{sq}/crops-{year}-v2.csv'):
            continue
        cr = {r['field']: LS.GROUP.get(r['group'], 'none')
              for r in csv.DictReader(open(f'{ddir}/{sq}/crops-{year}-v2.csv', encoding='utf-8'))}
        loc = lambda d: {k[1]: v for k, v in d.items() if k[0] == sq}
        joined, joins, small = square(ddir, sq, year, cr, loc(cut), loc(area), loc(holders))
        a = loc(area)
        gone = {m for g in joined for m in g}
        feats = [f for f in feats if not (f['properties']['square'] == sq and set(f['properties']['members']) & gone)]
        feats += [outline_feature(ddir, sq, year, g, a) for g in joined]
        all_joins += joins
        all_small += small
        print(sq, len(joins), 'joins,', len(small), 'small fields kept', flush=True)
    json.dump(dict(type='FeatureCollection', features=feats), open(jpath, 'w', encoding='utf-8'))
    for name, rows, cols in ((f'evidence-joins-{year}.csv', all_joins, ['square', 'a', 'b', 'ha_a', 'ha_b', 'seen', 'fill_join']),
                             (f'small-fields-{year}.csv', all_small, ['square', 'keep', 'ha'])):
        with open(f'{ddir}/{name}', 'w', newline='', encoding='utf-8') as fh:
            w = csv.DictWriter(fh, fieldnames=cols)
            w.writeheader()
            w.writerows(rows)
    print(f'{ddir} {year}: {len(all_joins)} joins, {len(all_small)} small fields kept')


if __name__ == '__main__':
    main()
