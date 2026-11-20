"""Lines first: fields as the site shows them split along lines seen on many photos (lines_first.split_by_lines,
blind check passed 24/24 decided, data/experiments/lines-first-2026-11-18/NOTE.md), each piece with its own crop
group and harvest date (piece_facts). Fields already split along inner corners (splits-YEAR.geojson) are left alone.

python line_splits.py DISTRICT_DIR YEAR [SQUARE ...] [--redo]
    per square: DISTRICT_DIR/line-splits-YEAR/<square>.geojson (kept between runs unless --redo);
    then DISTRICT_DIR/line-splits-YEAR.geojson (all squares) and line-splits-YEAR.csv (one row per piece)
"""
import csv
import json
import os
import sys

import cv2
import numpy as np
import tifffile
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import field_joins as FJ  # noqa: E402
import land_lib as LL  # noqa: E402
import lines_first as LF  # noqa: E402
import piece_facts as PF  # noqa: E402

sys.path.insert(0, os.path.join(HERE, '../../../site/build'))
import geo  # noqa: E402

GROUP = {'short season': 'grain', 'long season': 'oilseed', 'yellow flowers': 'oilseed', 'perennial grass': 'other',
         'unclear': 'none', 'no crop seen': 'none'}


def units_of(ddir, sq, year, joins=None):
    """(unit raster, members {unit: [field ids]}, keep {unit: field id}): the fields as the site shows them, joins
    applied (joins: a joins file, default joins-YEAR.geojson), from the square's region raster."""
    ids, area = set(), {}
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        if r['square'] == sq and r['land'] == 'field':
            ids.add(r['field'])
            area[r['field']] = float(r['area_ha'])
    members, keep = {}, {}
    for f in json.load(open(joins or f'{ddir}/joins-{year}.geojson', encoding='utf-8'))['features']:
        q = f['properties']
        if q['square'] != sq:
            continue
        g = [m for m in q['members'] if m in ids]
        if g and not any(m in k for k in members.values() for m in g):
            u = len(members) + 1
            members[u] = g
            keep[u] = q['keep'] if q['keep'] in g else max(g, key=lambda m: area.get(m, 0))
    taken = {m for g in members.values() for m in g}
    for m in sorted(ids - taken):
        u = len(members) + 1
        members[u] = [m]
        keep[u] = m
    reg = tifffile.imread(f'{ddir}/{sq}/fields-{year}/fields-{year}.tif').astype(np.int64)
    look = np.zeros(int(reg.max()) + 1, np.int64)
    for u, g in members.items():
        for m in g:
            k = int(m.split('-F')[1])
            if k < look.size:
                look[k] = u
    return look[reg], members, keep


def without(units, keep, done):
    """The unit raster with the units whose keep field is in `done` set to 0."""
    gone = [u for u, k in keep.items() if k in done]
    return np.where(np.isin(units, gone), 0, units)


def piece_records(units, out, members, keep):
    """One record per piece of every unit that `out` divides: unit, label (in out), keep, members, pixel share."""
    recs = []
    for u in sorted(members):
        labs, cnt = np.unique(out[units == u], return_counts=True)
        keep_l = [(l, c) for l, c in zip(labs.tolist(), cnt.tolist()) if l > 0]
        if len(keep_l) < 2:
            continue
        tot = sum(c for _, c in keep_l)
        for l, c in keep_l:
            recs.append(dict(unit=u, label=l, keep=keep[u], members=members[u], share=c / tot))
    return recs


def _outline(mask, tif):
    """Outer ring of a mask on the square's raster as lon/lat."""
    t = tifffile.TiffFile(tif)
    e0, n0 = t.pages[0].tags[33922].value[3:5]
    lon0 = FJ.central_meridian(FJ.geokey_epsg(t.pages[0].tags[34735].value) or 32642)
    t.close()
    cs, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    ring = [(float(x), float(y)) for x, y in max(cs, key=cv2.contourArea)[:, 0, :]]
    ring = [(e0 + (x + 0.5) * LF.PX, n0 - (y + 0.5) * LF.PX) for x, y in LL.simplify(ring, 1.0)]
    ll = [list(geo.from_utm42(e, n, lon0=lon0)) for e, n in ring]
    return [[round(a, 6), round(b, 6)] for a, b in ll + ll[:1]]


def square(ddir, sq, year):
    """The square's pieces as GeoJSON features (empty when nothing is split)."""
    units, members, keep = units_of(ddir, sq, year)
    done = set()
    sp = f'{ddir}/splits-{year}.geojson'
    if os.path.isfile(sp):
        done = {f['properties']['keep'] for f in json.load(open(sp, encoding='utf-8'))['features']
                if f['properties']['square'] == sq}
    count = LF.load_count(ddir, sq, year)
    lines = LF.lines_of(count, units > 0)
    out, splits = LF.split_by_lines(without(units, keep, done), lines)
    recs = piece_records(units, out, members, keep)
    if not recs:
        return []
    pieces = np.zeros(out.shape, np.int64)
    for i, r in enumerate(recs, 1):
        pieces[out == r['label']] = i
        r['n'] = i
    np.savez_compressed(f'{ddir}/line-splits-{year}/{sq}.npz', pieces=pieces.astype(np.uint16),
                        keep=np.array([r['keep'] for r in recs]), piece=np.array([r['n'] for r in recs]))
    facts = PF.run(f'{ddir}/{sq}', year, pieces)
    tif = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
    feats = []
    for r in recs:
        f = facts.get(r['n'], {})
        feats.append(dict(type='Feature', geometry=dict(type='Polygon', coordinates=[_outline(pieces == r['n'], tif)]),
                          properties=dict(square=sq, keep=r['keep'], members=r['members'], piece=r['n'],
                                          share=round(r['share'], 4), group=f.get('group', ''),
                                          c=GROUP.get(f.get('group', 'unclear'), 'none'), s=f.get('status', ''),
                                          f=f.get('cut_from', ''), t=f.get('cut_to', ''), m=f.get('cut_middle', ''))))
    return feats


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    ddir, year = args[0].rstrip('/'), int(args[1])
    want = args[2:] or [r['square'] for r in csv.DictReader(open(f'{ddir}/squares.csv', encoding='utf-8'))]
    os.makedirs(f'{ddir}/line-splits-{year}', exist_ok=True)
    for sq in want:
        p = f'{ddir}/line-splits-{year}/{sq}.geojson'
        if (os.path.isfile(p) and '--redo' not in sys.argv) or not os.path.isfile(LF.count_path(ddir, sq, year)):
            continue
        feats = square(ddir, sq, year)
        json.dump(dict(type='FeatureCollection', features=feats), open(p, 'w', encoding='utf-8'))
        print(sq, len({f['properties']['keep'] for f in feats}), 'fields split into', len(feats), 'pieces', flush=True)
    feats = []
    for name in sorted(n for n in os.listdir(f'{ddir}/line-splits-{year}') if n.endswith('.geojson')):
        feats += json.load(open(f'{ddir}/line-splits-{year}/{name}', encoding='utf-8'))['features']
    json.dump(dict(type='FeatureCollection', features=feats), open(f'{ddir}/line-splits-{year}.geojson', 'w', encoding='utf-8'))
    with open(f'{ddir}/line-splits-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.writer(fh)
        w.writerow(['square', 'keep', 'piece', 'share', 'group', 'status', 'cut_middle'])
        for f in feats:
            q = f['properties']
            w.writerow([q['square'], q['keep'], q['piece'], q['share'], q['group'], q['s'], q['m']])
    print(f'{ddir} {year}: {len({(f["properties"]["square"], f["properties"]["keep"]) for f in feats})} fields split into '
          f'{len(feats)} pieces')


if __name__ == '__main__':
    main()
