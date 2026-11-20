"""Squares first, part S v1: splits along inner corners (data/experiments/square-fields-2026-11-18/NOTE.md).

A drawn field (unit) of at least MIN_HA with an inner corner (a turn inward of 60-120 degrees, both sides at least
SIDE_PX long, outline simplified by SIMPLIFY_PX) may be cut along one of that corner's sides carried straight on. A cut
is kept when it leaves two pieces of at least PIECE_HA, each filling its turned rectangle at least PIECE_FILL, together
at least GAIN more rectangular than the field (S1), and the line shows on at least MIN_DATES clear photos of the two
seasons (S2: in at least CHUNK_SHARE of its 200 m chunks the median NDVI 20-40 m either side differs by NDVI_STEP,
same sign; or SWIR 1.6 by SWIR_STEP). Best cut first (most dates); pieces tried again, at most MAX_CUTS per field.

python field_splits.py DISTRICT_DIR YEAR      writes DISTRICT_DIR/splits-YEAR.geojson and splits-YEAR.csv (every
                                              cut that passed S1, kept or not)
"""
import csv
import json
import math
import os
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
MIN_HA, PIECE_HA, CRUMB_HA, PIECE_FILL, GAIN = 60.0, 20.0, 1.0, 0.80, 0.10
SIMPLIFY_PX, SIDE_PX, TURN = 2.0, 20.0, (60.0, 120.0)
NEAR, FAR, CHUNK_PX, CHUNK_SHARE, CLEAR = 2, 4, 20, 0.70, 0.60
NDVI_STEP, SWIR_STEP, MIN_DATES, MAX_CUTS = 0.06, 0.02, 2, 3


def _ring(mask):
    cs, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    c = max(cs, key=cv2.contourArea)
    return cv2.approxPolyDP(c, SIMPLIFY_PX, True)[:, 0, :].astype(float)


def inner_corners(mask):
    """[((x, y), [direction, direction])] for the inner corners of the mask's outer outline; each direction is a side
    of the corner carried on past it (unit vector, x right, y down)."""
    p = _ring(mask)
    n = len(p)
    if n < 4:
        return []
    area = 0.5 * sum(p[i, 0] * p[(i + 1) % n, 1] - p[(i + 1) % n, 0] * p[i, 1] for i in range(n))
    out = []
    for i in range(n):
        a, v, b = p[i - 1], p[i], p[(i + 1) % n]
        e1, e2 = v - a, b - v
        l1, l2 = np.hypot(*e1), np.hypot(*e2)
        if l1 < SIDE_PX or l2 < SIDE_PX:
            continue
        cross = e1[0] * e2[1] - e1[1] * e2[0]
        turn = math.degrees(math.acos(max(-1.0, min(1.0, float(e1 @ e2) / (l1 * l2)))))
        if cross * area < 0 and TURN[0] <= turn <= TURN[1]:      # turns against the outline's own direction: inward
            out.append(((float(v[0]), float(v[1])), [e1 / l1, -e2 / l2]))
    return out


def carry_on(mask, start, d, step=0.5):
    """The straight line from `start` along `d` while it stays in the mask: ((x0, y0), (x1, y1))."""
    h, w = mask.shape
    x, y = start
    last, out_run, t = (x, y), 0, 0.0
    while out_run < 6:
        t += step
        px, py = x + d[0] * t, y + d[1] * t
        ix, iy = int(round(px)), int(round(py))
        if not (0 <= ix < w and 0 <= iy < h):
            break
        if mask[iy, ix]:
            last, out_run = (px, py), 0
        else:
            out_run += 1
    return (float(start[0]), float(start[1])), (float(last[0]), float(last[1]))


def _fill(m):
    ys, xs = np.nonzero(m)
    pts = np.column_stack([xs, ys]).astype(np.float32)
    (_, _), (w, h), _ = cv2.minAreaRect(pts)
    return float(m.sum()) / ((w + 1) * (h + 1))


def shape_test(mask, line, px=PX):
    """S1 for one cut: {pieces: [mask, mask], fills, gain} or None."""
    (x0, y0), (x1, y1) = line
    if math.hypot(x1 - x0, y1 - y0) < SIDE_PX:
        return None
    cut = np.zeros(mask.shape, np.uint8)
    cv2.line(cut, (int(round(x0)), int(round(y0))), (int(round(x1)), int(round(y1))), 1, 1)
    lab, n = ndimage.label(mask & ~cut.astype(bool))
    sizes = np.bincount(lab.ravel())[1:]
    ha = sizes * px * px / 1e4
    big = [i + 1 for i in range(n) if ha[i] >= CRUMB_HA]
    if len(big) != 2 or any(ha[i - 1] < PIECE_HA for i in big):
        return None
    pieces = [lab == i for i in big]
    fills = [_fill(p) for p in pieces]
    whole = _fill(mask)
    joint = sum(f * p.sum() for f, p in zip(fills, pieces)) / sum(p.sum() for p in pieces)
    if min(fills) < PIECE_FILL or joint < whole + GAIN:
        return None
    return dict(pieces=pieces, fills=fills, gain=joint - whole, line=line)


def sample_points(line, a, b):
    """Pixel indices 20-40 m either side of the line, per 200 m chunk: [(rows_a, cols_a, rows_b, cols_b)] with side a
    lying in mask `a` and side b in mask `b` (points outside both are dropped)."""
    (x0, y0), (x1, y1) = line
    length = math.hypot(x1 - x0, y1 - y0)
    d = np.array([(x1 - x0) / length, (y1 - y0) / length])
    nrm = np.array([-d[1], d[0]])
    h, w = a.shape
    chunks = []
    for c0 in np.arange(0, length, CHUNK_PX):
        ts = np.arange(c0, min(c0 + CHUNK_PX, length), 1.0)
        sides = {1: ([], []), -1: ([], [])}
        for s in (1, -1):
            for k in range(NEAR, FAR + 1):
                xs = x0 + d[0] * ts + nrm[0] * k * s
                ys = y0 + d[1] * ts + nrm[1] * k * s
                ix, iy = np.round(xs).astype(int), np.round(ys).astype(int)
                ok = (ix >= 0) & (ix < w) & (iy >= 0) & (iy < h)
                sides[s][0].extend(iy[ok].tolist())
                sides[s][1].extend(ix[ok].tolist())
        ra, ca = map(np.array, sides[1])
        rb, cb = map(np.array, sides[-1])
        if len(ra) and a[ra, ca].sum() < b[ra, ca].sum():        # this chunk's + side lies in b: swap
            ra, ca, rb, cb = rb, cb, ra, ca
        ka = a[ra, ca] if len(ra) else np.zeros(0, bool)
        kb = b[rb, cb] if len(rb) else np.zeros(0, bool)
        if ka.sum() >= 3 and kb.sum() >= 3:
            chunks.append((ra[ka], ca[ka], rb[kb], cb[kb]))
    return chunks


def shows(chunks, ndvi, swir):
    """S2 on one photo: the line shows in at least CHUNK_SHARE of the clear chunks (NDVI or SWIR step, same sign)."""
    if not chunks:
        return False
    for img, step in ((ndvi, NDVI_STEP), (swir, SWIR_STEP)):
        diffs = []
        for ra, ca, rb, cb in chunks:
            va, vb = img[ra, ca], img[rb, cb]
            if np.isfinite(va).mean() < CLEAR or np.isfinite(vb).mean() < CLEAR:
                continue
            diffs.append(float(np.nanmedian(va) - np.nanmedian(vb)))
        if len(diffs) < max(1, 0.5 * len(chunks)):
            continue
        diffs = np.array(diffs)
        if max((diffs >= step).mean(), (diffs <= -step).mean()) >= CHUNK_SHARE:
            return True
    return False


def photos(sq, years):
    """Yield (date, ndvi, swir16) for the stored dates of the square's seasons (clouds are NaN)."""
    for year in years:
        if not os.path.isdir(L.square_dir(sq) + f'season-{year}'):
            continue
        for date, share, sc, off in L.dates(sq, year, 0.3):
            red = L.reflectance(sq, year, date, 'red', sc, off)
            nir = L.reflectance(sq, year, date, 'nir', sc, off)
            sw = L.reflectance(sq, year, date, 'swir16', sc, off)
            sw = np.repeat(np.repeat(sw, 2, 0), 2, 1)[:red.shape[0], :red.shape[1]]
            if sw.shape != red.shape:
                pad = np.full(red.shape, np.nan, np.float32)
                pad[:sw.shape[0], :sw.shape[1]] = sw
                sw = pad
            yield date, (nir - red) / (nir + red + 1e-6), sw


def plan_cuts(mask, shown):
    """Greedy cuts for one field: shown(line) -> number of dates the line shows. Returns (pieces, kept lines with
    dates, every S1 line with its dates)."""
    tried, kept = [], []
    pieces = [mask]
    for _ in range(MAX_CUTS):
        best = None
        for pi, m in enumerate(pieces):
            if m.sum() * PX * PX / 1e4 < 2 * PIECE_HA:
                continue
            for corner, dirs in inner_corners(m):
                for d in dirs:
                    line = carry_on(m, corner, d)
                    r = shape_test(m, line)
                    if r is None:
                        continue
                    n = shown(line, r['pieces'][0], r['pieces'][1])
                    tried.append((line, n))
                    if n >= MIN_DATES and (best is None or n > best[0]):
                        best = (n, pi, r)
        if best is None:
            break
        n, pi, r = best
        kept.append((r['line'], n))
        pieces = pieces[:pi] + r['pieces'] + pieces[pi + 1:]
    return pieces, kept, tried


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    years = (year - 1, year)
    table, area = defaultdict(set), {}
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        if r['land'] == 'field':
            table[r['square']].add(r['field'])
            area[(r['square'], r['field'])] = float(r['area_ha'])
    dup = f'{ddir}/duplicates-{year}.csv'
    if os.path.isfile(dup):
        for r in csv.DictReader(open(dup, encoding='utf-8')):
            table[r['square']].discard(r['region'])
    groups = defaultdict(list)
    for f in json.load(open(f'{ddir}/joins-{year}.geojson', encoding='utf-8'))['features']:
        groups[f['properties']['square']].append(f['properties']['members'])
    feats, rows = [], []
    for sq, ids in sorted(table.items()):
        tif = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
        if not os.path.isfile(tif):
            continue
        t = tifffile.TiffFile(tif)
        reg = t.pages[0].asarray().astype(np.int64)
        e0, n0 = t.pages[0].tags[33922].value[3:5]
        lon0 = FJ.central_meridian(FJ.geokey_epsg(t.pages[0].tags[34735].value) or 32642)
        members, unit_of = {}, {}
        for g in groups.get(sq, []):
            g = [m for m in g if m in ids]
            if g:
                members[len(members) + 1] = g
                unit_of.update({m: len(members) for m in g})
        for m in sorted(ids - set(unit_of)):
            members[len(members) + 1] = [m]
            unit_of[m] = len(members)
        look = np.zeros(int(reg.max()) + 1, np.int64)
        for m, u in unit_of.items():
            k = int(m.split('-F')[1])
            if k < look.size:
                look[k] = u
        lab = look[reg]
        sl = ndimage.find_objects(lab)
        # pass 1: every S1 cut of every big field (shape only), with its sample points
        cands = {}
        for u, ms in members.items():
            if u > len(sl) or sl[u - 1] is None:
                continue
            ys, xs = sl[u - 1]
            y0, x0 = max(ys.start - 2, 0), max(xs.start - 2, 0)
            m = lab[y0:ys.stop + 2, x0:xs.stop + 2] == u
            if m.sum() * PX * PX / 1e4 < MIN_HA or not inner_corners(m):
                continue
            cands[u] = (y0, x0, m)
        if not cands:
            print(sq, 'no candidates', flush=True)
            continue
        # gather every S1 line any greedy step could try (pieces of pieces too), then count photo dates per line once
        lines = {}

        def explore(u, m, depth):
            if depth >= MAX_CUTS or m.sum() * PX * PX / 1e4 < 2 * PIECE_HA:
                return
            for corner, dirs in inner_corners(m):
                for d in dirs:
                    line = carry_on(m, corner, d)
                    r = shape_test(m, line)
                    if r is None or (u, line) in lines:
                        continue
                    lines[(u, line)] = (line, sample_points(line, r['pieces'][0], r['pieces'][1]))
                    for piece in r['pieces']:
                        explore(u, piece, depth + 1)

        for u, (y0, x0, m) in cands.items():
            explore(u, m, 0)
        counts = defaultdict(int)
        for date, ndvi, sw in photos(sq, years):
            for key, (line, pts) in lines.items():
                y0, x0, _ = cands[key[0]]
                shifted = [(ra + y0, ca + x0, rb + y0, cb + x0) for ra, ca, rb, cb in pts]
                if shows(shifted, ndvi, sw):
                    counts[key] += 1
        for u, (y0, x0, m) in cands.items():
            pieces, kept, tried = plan_cuts(m, lambda line, a, b, u=u: counts[(u, line)])
            keep_lines = {ln for ln, _ in kept}
            for line, n in tried:
                rows.append(dict(square=sq, members=' '.join(members[u]), kept='yes' if line in keep_lines else 'no',
                                 dates=n, x0=round(line[0][0] + x0, 1), y0=round(line[0][1] + y0, 1),
                                 x1=round(line[1][0] + x0, 1), y1=round(line[1][1] + y0, 1)))
            if not kept:
                continue
            total = sum(p.sum() for p in pieces)
            for i, p in enumerate(pieces):
                cs, _ = cv2.findContours(p.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                ring = [(float(x), float(y)) for x, y in max(cs, key=cv2.contourArea)[:, 0, :]]
                ring = [(e0 + (x0 + x + 0.5) * PX, n0 - (y0 + y + 0.5) * PX) for x, y in LL.simplify(ring, 1.0)]
                ll = [list(geo.from_utm42(e, n, lon0=lon0)) for e, n in ring]
                ms = members[u]
                feats.append(dict(type='Feature', properties=dict(square=sq, members=ms,
                                                                  keep=max(ms, key=lambda q: area.get((sq, q), 0)),
                                                                  piece=i, share=round(float(p.sum()) / total, 4),
                                                                  dates=[n for _, n in kept]),
                                  geometry=dict(type='Polygon', coordinates=[[[round(a, 6), round(b, 6)] for a, b in ll + ll[:1]]])))
        print(sq, len(cands), 'fields with inner corners,', len(lines), 'cuts tried,',
              len({f['properties']['keep'] for f in feats if f['properties']['square'] == sq}), 'fields split', flush=True)
    with open(f'{ddir}/splits-{year}.geojson', 'w', encoding='utf-8') as fh:
        json.dump(dict(type='FeatureCollection', features=feats), fh)
    with open(f'{ddir}/splits-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'members', 'kept', 'dates', 'x0', 'y0', 'x1', 'y1'])
        w.writeheader()
        w.writerows(rows)
    split = {(f['properties']['square'], f['properties']['keep']) for f in feats}
    print(f'{ddir} {year}: {len(split)} fields split into {len(feats)} pieces; '
          f'{sum(r["kept"] == "yes" for r in rows)} cuts kept of {len(rows)} that passed the shape test')


if __name__ == '__main__':
    main()
