"""Squares first, part J v2 "straight outlines" (data/experiments/square-fields-2026-11-18/NOTE.md).

A drawn field (unit) of at least MIN_HA that fills its smallest turned rectangle at least FILL (with other drawn fields
taking at most OTHER_MAX of it) is drawn as that rectangle; else, filling its convex hull at least FILL, as the hull
(corners simplified). Pixels of other drawn fields are cut out again; the new outline may add at most MAX_ADDED of
non-field land, else the field keeps its outline. A smaller drawn field lying at least PIECE_INSIDE inside the new
outline joins it when nothing says they differ (holders, grain vs oilseed, cut more than CUT_DAYS apart).
Hectares stay the sown pixels.

python square_fields.py DISTRICT_DIR YEAR    keeps joins-YEAR.geojson as joins-YEAR-j5.geojson (once); writes
                                             joins-YEAR.geojson (all groups + one-member outline changes) and
                                             square-fields-YEAR.csv (every change)
"""
import csv
import datetime as dt
import json
import os
import shutil
import sys
from collections import defaultdict

import cv2
import numpy as np
import tifffile
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import field_joins as FJ  # noqa: E402
import fields_lib as L  # noqa: E402
import land_lib as LL  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../site/build'))
import geo  # noqa: E402

PX = 10.0
MIN_HA, FILL, OTHER_MAX, MAX_ADDED, PIECE_INSIDE, CUT_DAYS, CORNER_PX = 20.0, 0.85, 0.03, 0.15, 0.60, 14, 2.0
GROUP = {'short season': 'grain', 'long season': 'oilseed', 'yellow flowers': 'oilseed', 'perennial grass': 'other'}


def agree(a, b):
    """Nothing says the two units are different fields."""
    if a['holders'] and b['holders'] and not (a['holders'] & b['holders']):
        return False
    if {a['crop'], b['crop']} == {'grain', 'oilseed'}:
        return False
    if a['cut'] and b['cut']:
        if abs((dt.date.fromisoformat(a['cut']) - dt.date.fromisoformat(b['cut'])).days) > CUT_DAYS:
            return False
    return True


def full(rec, shape):
    """A result's mask (kept on its box) as a full-size raster."""
    out = np.zeros(shape, bool)
    y0, x0 = rec['at']
    h, w = rec['mask'].shape
    out[y0:y0 + h, x0:x0 + w] = rec['mask']
    return out


def _poly_mask(poly, shape):
    m = np.zeros(shape, np.uint8)
    cv2.fillPoly(m, [np.round(poly).astype(np.int32)], 1)
    return m.astype(bool)


SHALLOW_PX, CURVE_GAP, CURVE_DATES = 3.0, 0.10, 5


def added_patches(new, m, sub):
    """Patches of non-field land (sub == 0) the new outline adds to the field mask m: [{mask, why}] with why
    'enclosed' (a hole of the field), 'shallow' (no pixel more than SHALLOW_PX from the field) or 'deep'."""
    add = new & ~m & (sub == 0)
    if not add.any():
        return []
    lab, n = ndimage.label(add, structure=np.ones((3, 3)))
    filled = ndimage.binary_fill_holes(m)
    dist = ndimage.distance_transform_edt(~m)
    out = []
    for i, s in enumerate(ndimage.find_objects(lab), 1):
        pm = np.zeros(add.shape, bool)
        pm[s] = lab[s] == i
        if not (pm & ~filled).any():
            why = 'enclosed'
        elif dist[pm].max() <= SHALLOW_PX:
            why = 'shallow'
        else:
            why = 'deep'
        out.append(dict(mask=pm, why=why))
    return out


def carve(new, patches, same_cover):
    """The new outline with every deep patch that does not grow the field's cover (same_cover(patch) False) cut out."""
    out = new.copy()
    for p in patches:
        if p['why'] == 'deep' and not same_cover(p):
            out &= ~p['mask']
    return out


def curves_agree(a, b):
    """Two NDVI curves (date -> value) agree: third-largest gap over common dates at most CURVE_GAP, at least
    CURVE_DATES common dates."""
    d = sorted((abs(a[t] - b[t]) for t in a if t in b), reverse=True)
    return len(d) >= CURVE_DATES and d[2] <= CURVE_GAP


def straighten(lab, info, max_added=MAX_ADDED, px=PX, same_cover=None):
    """unit -> {kind: 'rectangle'|'hull', poly: corners (x, y in pixels of the box), at: (y0, x0) of the box, mask:
    the new outline's pixels on the box, joined: units joined in, added: non-field pixels added} for the units whose
    outline changes. Bigger units first; a unit joined into another is not changed itself.
    same_cover(u, patch, (y0, x0)) -> bool: v3, deep patches of other cover are cut out (None: v2, nothing cut)."""
    sl = ndimage.find_objects(lab)
    order = sorted((u for u in info if u <= len(sl) and sl[u - 1] is not None),
                   key=lambda u: -int((lab[sl[u - 1]] == u).sum()))
    gone, out = set(), {}
    min_px = MIN_HA * 1e4 / (px * px)
    for u in order:
        if u in gone:
            continue
        ys, xs = sl[u - 1]
        pad = 3
        y0, x0 = max(ys.start - pad, 0), max(xs.start - pad, 0)
        y1, x1 = min(ys.stop + pad, lab.shape[0]), min(xs.stop + pad, lab.shape[1])
        sub = lab[y0:y1, x0:x1]
        m = sub == u
        n = int(m.sum())
        if n < min_px:
            continue
        cs, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        pts = np.vstack([c[:, 0, :] for c in cs]).astype(np.float32)
        rect = cv2.boxPoints(cv2.minAreaRect(pts))
        r = _poly_mask(rect, m.shape) | m
        other = (sub > 0) & ~m
        kind = None
        if n / r.sum() >= FILL and (r & other).sum() <= OTHER_MAX * r.sum():
            kind, poly, shape = 'rectangle', rect, r
        else:
            hull = cv2.approxPolyDP(cv2.convexHull(pts), CORNER_PX, True)[:, 0, :].astype(np.float32)
            h = _poly_mask(hull, m.shape) | m
            if n / h.sum() >= FILL:
                kind, poly, shape = 'hull', hull, h
        if kind is None:
            continue
        joined = []
        for v in np.unique(sub[shape & other]).tolist():
            if v in gone or v == u or v not in info:
                continue
            mv = lab[sl[v - 1]] == v
            nv = int(mv.sum())
            inside = int((shape & (sub == v)).sum())
            if nv < n and inside >= PIECE_INSIDE * nv and agree(info[u], info[v]):
                joined.append(v)
        keep = m | np.isin(sub, joined)
        new = shape & ~((sub > 0) & ~keep)
        patches = []
        if same_cover is not None:
            patches = added_patches(new, m, sub)
            new = carve(new, patches, lambda p, u=u, at=(y0, x0): same_cover(u, p, at))
        added = int((new & (sub == 0)).sum())
        if added > max_added * new.sum():
            continue
        if added == 0 and not joined:
            continue
        gone.update(joined)
        out[u] = dict(kind=kind, poly=poly, at=(y0, x0), mask=new, joined=joined, added=added,
                      clean=bool((new == shape).all()), cut=sum(1 for p in patches if p['why'] == 'deep'
                                                                and not (p['mask'] & new).any()))
    return out


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    jpath = f'{ddir}/joins-{year}.geojson'
    if not os.path.isfile(f'{ddir}/joins-{year}-j5.geojson'):
        shutil.copy(jpath, f'{ddir}/joins-{year}-j5.geojson')
    old = defaultdict(list)
    for f in json.load(open(f'{ddir}/joins-{year}-j5.geojson', encoding='utf-8'))['features']:
        old[f['properties']['square']].append(f)
    table, cut, area = defaultdict(set), {}, {}
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        if r['land'] == 'field':
            table[r['square']].add(r['field'])
            cut[(r['square'], r['field'])] = r['cut_middle']
            area[(r['square'], r['field'])] = float(r['area_ha'])
    dup = f'{ddir}/duplicates-{year}.csv'
    if os.path.isfile(dup):
        for r in csv.DictReader(open(dup, encoding='utf-8')):
            table[r['square']].discard(r['region'])
    holders = {}
    if os.path.isfile(f'{ddir}/holders.csv'):
        for r in csv.DictReader(open(f'{ddir}/holders.csv', encoding='utf-8-sig')):
            holders[(r['square'], r['field'])] = r['holder']
    feats, rows = [], []
    for sq, ids in sorted(table.items()):
        tif = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
        if not os.path.isfile(tif):
            feats += old.get(sq, [])
            continue
        t = tifffile.TiffFile(tif)
        reg = t.pages[0].asarray().astype(np.int64)
        e0, n0 = t.pages[0].tags[33922].value[3:5]
        lon0 = FJ.central_meridian(FJ.geokey_epsg(t.pages[0].tags[34735].value) or 32642)
        crops = {}
        cp = f'{ddir}/{sq}/crops-{year}-v2.csv'
        if os.path.isfile(cp):
            crops = {r['field']: GROUP.get(r['group'], 'none') for r in csv.DictReader(open(cp, encoding='utf-8'))}
        unit_of, members, old_feat = {}, {}, {}
        for f in old.get(sq, []):
            g = [m for m in f['properties']['members'] if m in ids]
            if g:
                u = len(members) + 1
                members[u] = g
                old_feat[u] = f
                unit_of.update({m: u for m in g})
        for m in sorted(ids - set(unit_of)):
            u = len(members) + 1
            members[u] = [m]
            unit_of[m] = u
        look = np.zeros(int(reg.max()) + 1, np.int64)
        for m, u in unit_of.items():
            k = int(m.split('-F')[1])
            if k < look.size:
                look[k] = u
        lab = look[reg]
        info = {}
        for u, ms in members.items():
            big = max(ms, key=lambda m: area.get((sq, m), 0))
            info[u] = dict(holders={holders[(sq, m)] for m in ms if holders.get((sq, m))},
                           crop=crops.get(big, 'none'), cut=cut.get((sq, big)) or None)
        # v3 pass 1: the deep patches whose cover must be compared with their field's
        need, sl = {}, ndimage.find_objects(lab)

        def pkey(u, p, at):
            return (u, at, int(np.flatnonzero(p['mask'])[0]), int(p['mask'].sum()))

        def record(u, p, at):
            ys, xs = np.nonzero(p['mask'])
            need[pkey(u, p, at)] = (ys + at[0], xs + at[1])
            return True

        straighten(lab, info, same_cover=record)
        rng = np.random.default_rng(0)
        fpix = {}
        for u in {k[0] for k in need}:
            ys, xs = np.nonzero(lab[sl[u - 1]] == u)
            take = rng.choice(len(ys), min(len(ys), 3000), replace=False)
            fpix[('field', u)] = (ys[take] + sl[u - 1][0].start, xs[take] + sl[u - 1][1].start)
        curves = defaultdict(dict)
        if need:
            for date, share, sc, off in L.dates(sq, year, 0.3):
                red = L.reflectance(sq, year, date, 'red', sc, off)
                nir = L.reflectance(sq, year, date, 'nir', sc, off)
                ndvi = (nir - red) / (nir + red + 1e-6)
                for key, (ys, xs) in list(need.items()) + list(fpix.items()):
                    v = ndvi[ys, xs]
                    if np.isfinite(v).mean() >= 0.6:
                        curves[key][date] = float(np.nanmedian(v))
        same = {k: curves_agree(curves[k], curves[('field', k[0])]) for k in need}
        changed = straighten(lab, info, same_cover=lambda u, p, at: same.get(pkey(u, p, at), False))
        gone = {v for c in changed.values() for v in c['joined']}

        def lonlat(ring_px, y0, x0):
            ring = [(e0 + (x0 + x + 0.5) * PX, n0 - (y0 + y + 0.5) * PX) for x, y in ring_px]
            ll = [list(geo.from_utm42(e, n, lon0=lon0)) for e, n in ring]
            return [[round(a, 6), round(b, 6)] for a, b in ll + ll[:1]]

        for u, ms in members.items():
            if u in gone:
                continue
            c = changed.get(u)
            if c is None:
                if u in old_feat:
                    feats.append(old_feat[u])
                continue
            allm = sorted(ms + [m for v in c['joined'] for m in members[v]])
            y0, x0 = c['at']
            if c['clean']:
                ring = [(float(x), float(y)) for x, y in c['poly']]
            else:
                cs, _ = cv2.findContours(c['mask'].astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                ring = [tuple(q) for q in LL.simplify([(float(x), float(y)) for x, y in max(cs, key=cv2.contourArea)[:, 0, :]], 1.0)]
            feats.append(dict(type='Feature', properties=dict(square=sq, members=allm, keep=max(allm, key=lambda m: area.get((sq, m), 0)),
                                                              straight=c['kind']),
                              geometry=dict(type='Polygon', coordinates=[lonlat(ring, y0, x0)])))
            rows.append(dict(square=sq, kind=c['kind'], members=' '.join(ms),
                             joined=' '.join(m for v in c['joined'] for m in members[v]),
                             ha=round(sum(area.get((sq, m), 0) for m in ms), 1), added_ha=round(c['added'] * PX * PX / 1e4, 1),
                             bays_cut=c['cut']))
        print(sq, len(members), 'units,', len(changed), 'straightened,', len(gone), 'joined', flush=True)
    with open(jpath, 'w', encoding='utf-8') as fh:
        json.dump(dict(type='FeatureCollection', features=feats), fh)
    with open(f'{ddir}/square-fields-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'kind', 'members', 'joined', 'ha', 'added_ha', 'bays_cut'])
        w.writeheader()
        w.writerows(rows)
    print(f'{ddir} {year}: {len(rows)} fields straightened ({sum(r["kind"] == "rectangle" for r in rows)} rectangles), '
          f'{sum(len(r["joined"].split()) for r in rows if r["joined"])} pieces joined, '
          f'{sum(r["added_ha"] for r in rows):.0f} ha of bays and groves taken in')


if __name__ == '__main__':
    main()
