"""Simple outlines v4 (data/experiments/lines-first-2026-11-18/NOTE.md, owner 2026-11-19): fields drawn across small
bays at their edges -- drawing only; hectares, crops, dates and holders stay those of the sown land.

A field filling its smallest turned rectangle at least FILL is drawn as the rectangle, else (filling its convex hull at
least FILL) as the hull; other drawn fields' pixels are cut out, each added patch of other land over MAX_PATCH_HA is cut
back out, and when the outline would add more than MAX_ADDED of the field it keeps its own. Bigger fields first; land
one field's outline took is not taken again.

python simple_outlines.py DISTRICT_DIR YEAR     writes DISTRICT_DIR/outlines-YEAR.geojson (square, keep, piece, poly)
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
import line_splits as LS  # noqa: E402

FILL, MAX_PATCH_HA, MAX_ADDED, CORNER_PX, PX = 0.85, 10.0, 0.15, 2.0, 10.0


def full(rec, shape):
    out = np.zeros(shape, bool)
    y0, x0 = rec['at']
    h, w = rec['mask'].shape
    out[y0:y0 + h, x0:x0 + w] = rec['mask']
    return out


def _poly(points, shape):
    m = np.zeros(shape, np.uint8)
    cv2.fillPoly(m, [np.round(points).astype(np.int32)], 1)
    return m.astype(bool)


def simple(lab, fill=FILL, max_patch_ha=MAX_PATCH_HA, max_added=MAX_ADDED, px=PX):
    """label -> {mask (box-local), at (y0, x0), kind, added (px)} for the fields whose drawn outline changes."""
    sl = ndimage.find_objects(lab)
    sizes = np.bincount(lab.ravel())
    order = sorted((u for u in range(1, len(sl) + 1) if sl[u - 1] is not None), key=lambda u: -sizes[u])
    taken = np.zeros(lab.shape, bool)
    max_patch = max_patch_ha * 1e4 / (px * px)
    out = {}
    for u in order:
        ys, xs = sl[u - 1]
        pad = 3
        y0, x0 = max(ys.start - pad, 0), max(xs.start - pad, 0)
        y1, x1 = min(ys.stop + pad, lab.shape[0]), min(xs.stop + pad, lab.shape[1])
        sub = lab[y0:y1, x0:x1]
        m = sub == u
        n = int(m.sum())
        cs, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        pts = np.vstack([c[:, 0, :] for c in cs]).astype(np.float32)
        shape = _poly(cv2.boxPoints(cv2.minAreaRect(pts)), m.shape) | m
        kind = 'rectangle'
        if n / shape.sum() < fill:
            hull = cv2.approxPolyDP(cv2.convexHull(pts), CORNER_PX, True)[:, 0, :].astype(np.float32)
            shape = _poly(hull, m.shape) | m
            kind = 'hull'
            if n / shape.sum() < fill:
                continue
        new = shape & ~((sub > 0) & ~m) & ~(taken[y0:y1, x0:x1] & ~m)
        add = new & ~m
        if add.any():
            pl, k = ndimage.label(add, structure=np.ones((3, 3)))
            big = np.flatnonzero(np.bincount(pl.ravel())[1:] > max_patch) + 1
            if len(big):
                new &= ~np.isin(pl, big)
                add = new & ~m
        # keep the part of the outline that holds the field (a cut-back patch may leave stray corners)
        cl, k = ndimage.label(new)
        if k > 1:
            keep = np.bincount(cl[m]).argmax()
            new = cl == keep
            new |= m
            add = new & ~m
        added = int(add.sum())
        if added == 0 or added > max_added * new.sum():
            continue
        taken[y0:y1, x0:x1] |= add
        out[u] = dict(mask=new, at=(y0, x0), kind=kind, added=added)
    return out


def main():
    ddir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    import line_splits  # noqa: F401
    feats, rows = [], []
    for r in csv.DictReader(open(f'{ddir}/squares.csv', encoding='utf-8')):
        sq = r['square']
        if not os.path.isfile(f'{ddir}/{sq}/fields-{year}/fields-{year}.tif') or not os.path.isfile(f'{ddir}/district-harvest-{year}.csv'):
            continue
        units, members, keep = LS.units_of(ddir, sq, year)
        if units.max() == 0:
            continue
        # final drawn fields: units, with line-split pieces taking their own labels
        final = units.copy()
        key = {u: (keep[u], 0) for u in members}
        npz = f'{ddir}/line-splits-{year}/{sq}.npz'
        if os.path.isfile(npz):
            z = np.load(npz)
            nxt = int(final.max()) + 1
            for k, piece in zip(z['keep'].tolist(), z['piece'].tolist()):
                final[z['pieces'] == piece] = nxt
                key[nxt] = (k, piece)
                nxt += 1
        done = set()
        sp = f'{ddir}/splits-{year}.geojson'
        if os.path.isfile(sp):
            done = {f['properties']['keep'] for f in json.load(open(sp, encoding='utf-8'))['features']
                    if f['properties']['square'] == sq}
        skip = [u for u, (k, p) in key.items() if k in done and p == 0]
        work = np.where(np.isin(final, skip), 0, final)
        changed = simple(work)
        tif = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
        for u, rec in changed.items():
            k, piece = key[u]
            feats.append(dict(type='Feature', geometry=dict(type='Polygon', coordinates=[LS._outline(full(rec, work.shape), tif)]),
                              properties=dict(square=sq, keep=k, piece=piece, kind=rec['kind'],
                                              added_ha=round(rec['added'] * PX * PX / 1e4, 1))))
            rows.append(rec['added'])
        print(sq, len(changed), 'outlines', flush=True)
    json.dump(dict(type='FeatureCollection', features=feats), open(f'{ddir}/outlines-{year}.geojson', 'w', encoding='utf-8'))
    print(f'{ddir} {year}: {len(feats)} outlines simplified, {sum(rows) * PX * PX / 1e4:.0f} ha of bays drawn in')


if __name__ == '__main__':
    main()
