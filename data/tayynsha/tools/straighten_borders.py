"""Straight borders: a field's border that runs along a straight line but is drawn ragged is redrawn as that line
(2026-11-14, after the owner's "make them square and straight").

Our field map groups 10 m pixels, so where two crops or a crop and the steppe meet, the border wobbles by a pixel or
two, and where two fields look alike near their edge it can zigzag. Fields here are bounded by straight lines. So,
for every field or fallow region, its outer border is split into runs by the region across, and each run into
pieces between corners (corners found on the run smoothed over SMOOTH pixels, keeping within CORNER pixels). A piece
of at least MIN_PX pixels gets the straight line that fits it best (least squares across the line), and is redrawn
on that line when:
  - it is near that line: the root mean square distance at most RMS_MAX and no pixel farther than DEV_MAX;
  - it is not a curve: a parabola fitted along it bends away from the line by at most SAG_MAX over its length;
  - it is not already straight: root mean square distance over ALREADY.
Redrawing: inside the strip along the piece (as long as the piece, DEV_MAX + 1 wide each side), pixels of the two
regions go to the region on their side of the line; other regions' pixels are never moved.

Two passes. The fine pass (wobble of a pixel or two) is for borders with any region, except small regions that are
not field or fallow (under BIG_OTHER_PX: lakes, marsh, hollows keep their own shape). The coarse pass (zigzags up to
100 m) is for borders between two field or fallow regions only. Last, a bit of a region cut off from it, under
FRAGMENT_PX, goes to the region it touches most.

python straighten_borders.py SQUARE_DIR YEAR     writes SQUARE_DIR/fields-YEAR-st/fields-YEAR.tif and .geojson (ids and
                                                properties kept, area_ha and outline new)
"""
import json
import os
import sys

import cv2
import numpy as np
import tifffile
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hybrid_fields as HF  # noqa: E402

FINE = dict(smooth=9, corner=3.0, min_px=30, rms_max=2.5, sag_max=1.0, dev_max=6.0, fields_only=False)
COARSE = dict(smooth=31, corner=4.0, min_px=50, rms_max=4.0, sag_max=1.5, dev_max=10.0, fields_only=True)
ALREADY = 0.5
BIG_OTHER_PX = 5000      # 50 ha
FRAGMENT_PX = 100        # 1 ha
CROP = ('field', 'fallow')
_AROUND = ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1))


def runs(lab, a):
    """[(region across, (n, 2) row-col pixels in order)] along the outer border of region a; -1: nothing across."""
    m = np.pad((lab == a).astype(np.uint8), 1)
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    h, w = lab.shape
    out = []
    for c in cs:
        p = c[:, 0, ::-1] - 1
        if len(p) < 3:
            continue
        across = np.full(len(p), -1)
        for i, (r, q) in enumerate(p):
            vals = [lab[r + dr, q + dq] for dr, dq in _AROUND
                    if 0 <= r + dr < h and 0 <= q + dq < w and lab[r + dr, q + dq] != a]
            if vals:
                u, k = np.unique(vals, return_counts=True)
                across[i] = u[np.argmax(k)]
        starts = np.nonzero(across != np.roll(across, 1))[0]
        if starts.size == 0:
            out.append((int(across[0]), p))
            continue
        p, across = np.roll(p, -starts[0], 0), np.roll(across, -starts[0])
        cut = list(np.nonzero(across != np.roll(across, 1))[0]) + [len(p)]
        out += [(int(across[s]), p[s:e]) for s, e in zip(cut[:-1], cut[1:])]
    return out


def _dp(points, tol):
    keep, stack = [0, len(points) - 1], [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        p, d = points[i], points[j] - points[i]
        seg = points[i + 1:j]
        n = np.hypot(*d)
        dist = np.hypot(*(seg - p).T) if n == 0 else np.abs(d[0] * (seg[:, 1] - p[1]) - d[1] * (seg[:, 0] - p[0])) / n
        k = int(np.argmax(dist))
        if dist[k] > tol:
            keep.append(i + 1 + k)
            stack += [(i, i + 1 + k), (i + 1 + k, j)]
    return sorted(set(keep))


def pieces(run, smooth, corner, min_px):
    """The run split at its corners: pieces of at least min_px pixels."""
    if len(run) < min_px:
        return []
    q = run.astype(float)
    if len(run) > smooth:
        k = smooth // 2
        pad = np.vstack([np.repeat(q[:1], k, 0), q, np.repeat(q[-1:], k, 0)])
        ker = np.ones(smooth) / smooth
        q = np.column_stack([np.convolve(pad[:, 0], ker, 'valid'), np.convolve(pad[:, 1], ker, 'valid')])
    idx = _dp(q, corner)
    return [run[i:j + 1] for i, j in zip(idx[:-1], idx[1:]) if j - i + 1 >= min_px]


def fit(pts):
    """(centre, unit along, unit across, positions along, distances across, parabola bend over the length)."""
    c = pts.mean(0)
    _, _, vt = np.linalg.svd(pts - c, full_matrices=False)
    t, d = (pts - c) @ vt[0], (pts - c) @ vt[1]
    co = np.linalg.lstsq(np.column_stack([t ** 2, t, np.ones_like(t)]), d, rcond=None)[0]
    return c, vt[0], vt[1], t, d, abs(co[0]) * np.ptp(t) ** 2 / 8


def one_pass(lab, land, smooth, corner, min_px, rms_max, sag_max, dev_max, fields_only):
    out = lab.copy()
    size = np.bincount(lab.ravel())
    h, w = lab.shape
    for a in sorted(r for r, v in land.items() if v in CROP and r < size.size and size[r]):
        for b, run in runs(out, a):
            if b < 0:
                continue
            crop_b = land.get(b) in CROP
            if (fields_only and not crop_b) or (b > 0 and not crop_b and size[b] < BIG_OTHER_PX):
                continue
            for pc in pieces(run, smooth, corner, min_px):
                c, ut, un, t, d, sag = fit(pc.astype(float))
                rms, dev = float(np.sqrt((d ** 2).mean())), float(np.abs(d).max())
                if not (ALREADY < rms <= rms_max and dev <= dev_max and sag <= sag_max):
                    continue
                m = int(np.ceil(dev)) + 1
                r0, r1 = max(0, int(pc[:, 0].min()) - m), min(h, int(pc[:, 0].max()) + m + 1)
                q0, q1 = max(0, int(pc[:, 1].min()) - m), min(w, int(pc[:, 1].max()) + m + 1)
                rr, qq = np.mgrid[r0:r1, q0:q1]
                rel = np.stack([rr - c[0], qq - c[1]], -1)
                tt, dd = rel @ ut, rel @ un
                win = out[r0:r1, q0:q1]
                band = (tt >= t.min()) & (tt <= t.max()) & (np.abs(dd) <= dev + 1)
                mine = band & (win == a)
                if not mine.any():
                    continue
                side = np.sign(dd[mine].mean()) or 1.0
                sel = band & ((win == a) | (win == b))
                win[sel] = np.where(np.sign(dd[sel]) == side, a, b)
    return out


def tidy(lab):
    """Every piece of a region other than its largest, under FRAGMENT_PX, goes to the region it touches most."""
    out = lab.copy()
    four = ndimage.generate_binary_structure(2, 1)
    for r, sl in enumerate(ndimage.find_objects(lab), start=1):
        if sl is None:
            continue
        sl = tuple(slice(max(0, s.start - 1), s.stop + 1) for s in sl)
        comp, k = ndimage.label(out[sl] == r, four)
        if k < 2:
            continue
        sizes = np.bincount(comp.ravel())
        sizes[0] = 0
        big = int(np.argmax(sizes))
        for i in range(1, k + 1):
            if i == big or sizes[i] >= FRAGMENT_PX:
                continue
            bit = comp == i
            ring = ndimage.binary_dilation(bit, four) & ~bit
            vals = out[sl][ring]
            vals = vals[vals != r]
            if vals.size:
                u, n = np.unique(vals, return_counts=True)
                out[sl][bit] = u[np.argmax(n)]
    return out


def straighten(lab, land):
    """The map with straight borders: fine pass, coarse pass, cut-off bits tidied. land: {region: field|fallow|other}."""
    return tidy(one_pass(one_pass(lab, land, **FINE), land, **COARSE))


def main():
    sq_dir, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    src = f'{sq_dir}/fields-{year}'
    page = tifffile.TiffFile(f'{src}/fields-{year}.tif').pages[0]
    lab = page.asarray().astype(np.int64)
    feats = json.load(open(f'{src}/fields-{year}.geojson', encoding='utf-8'))['features']
    props = {int(f['properties']['id'].split('-F')[1]): f['properties'] for f in feats}
    land = {r: p['land'] for r, p in props.items()}
    new = straighten(lab, land)
    out = f'{sq_dir}/fields-{year}-st'
    os.makedirs(out, exist_ok=True)
    tags = page.tags
    keys = list(tags[34735].value)
    epsg = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
    extratags = [(33550, 'd', 3, tuple(tags[33550].value), False), (33922, 'd', 6, tuple(tags[33922].value), False),
                 (34735, 'H', len(keys), tuple(keys), False)]
    tifffile.imwrite(f'{out}/fields-{year}.tif', new.astype(page.dtype), extratags=extratags + [(42113, 's', 0, '0', False)],
                     tile=(256, 256), compression='deflate', predictor=True)
    x0, y0 = tags[33922].value[3:5]
    codes = {r: HF.CODE[v] for r, v in land.items()}
    gj = HF.polygons(new, codes, x0, y0, (epsg - 32600) * 6 - 183, year)
    for f in gj['features']:
        r = int(f['properties']['id'].split('-F')[1])
        f['properties'] = {**props[r], 'area_ha': f['properties']['area_ha']}
    with open(f'{out}/fields-{year}.geojson', 'w', encoding='utf-8') as fh:
        json.dump(gj, fh)
    moved = int((new != lab).sum())
    print(f'{os.path.basename(sq_dir)} {year}: {moved} pixels ({moved * 0.01:,.0f} ha) moved to a straight border')


if __name__ == '__main__':
    main()
