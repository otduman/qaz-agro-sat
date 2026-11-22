"""Lines first v1 (data/experiments/lines-first-2026-11-18/NOTE.md): field borders are straight lines seen on many
photos; the land is divided along them.

1. line count: the line segment detector on every clear photo (NDVI and SWIR 1.6, two seasons); segments of at least
   MIN_SEG px drawn 3 px wide; count = dates a segment passed the pixel (line_count).
2. lines: LSD on the count, segments centred on their ridge and joined when collinear (merge); a line is kept for its
   longest stretch seen (count >= STRONG, holes up to HOLE px) when that is at least MIN_LINE px and SUPPORT seen
   (keep_stretch); kept lines grow along their direction while the count stays >= GROW (grow), then each end is
   carried on up to EXTEND px to the next line or the edge of the land (extend).
3. fields: the land minus the lines, 4-connected; line pixels to the nearest part; strips under 2 x SLIVER px across
   to the parts on either side; parts under MIN_HA to the neighbour with the longest shared border (partition).

python lines_first.py DISTRICT SQUARE YEAR OUT_DIR     (land = the fields the site shows; writes OUT_DIR/new-SQ.npy)
"""
import math
import os
import shutil
import sys

import cv2
import numpy as np
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PX = 10.0
MIN_SEG = 10                          # px (100 m), per-photo segments
SCALE = 30.0                          # count mapped to the LSD input (30 dates = white)
ANGLE, OFFSET, GAP = 3.0, 1.5, 10.0   # merge: degrees, px across, px along
STRONG, SUPPORT, MIN_LINE, HOLE = 10, 0.7, 30, 5
GROW, GROW_LIMIT = 15, 400
EXTEND = 15                           # px (150 m)
SLIVER = 3.0                          # px: parts narrower than 2 x this (60 m) are road or belt strips
MIN_HA = 5.0
CROSS = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]])


def line_count(sq, years, min_clear=0.5):
    """(count, seen): per pixel, dates a straight segment passed within 1 px, and clear dates."""
    import fields_lib as L
    lsd = cv2.createLineSegmentDetector(cv2.LSD_REFINE_STD)
    count = seen = None
    for year in years:
        for date, share, sc, off in L.dates(sq, year, min_clear):
            red = L.reflectance(sq, year, date, 'red', sc, off)
            nir = L.reflectance(sq, year, date, 'nir', sc, off)
            sw = L.reflectance(sq, year, date, 'swir16', sc, off)
            sw = np.repeat(np.repeat(sw, 2, 0), 2, 1)[:red.shape[0], :red.shape[1]]
            if sw.shape != red.shape:
                pad = np.full(red.shape, np.nan, np.float32)
                pad[:sw.shape[0], :sw.shape[1]] = sw
                sw = pad
            ndvi = (nir - red) / (nir + red + 1e-6)
            ok = np.isfinite(ndvi) & np.isfinite(sw)
            ok = cv2.erode(ok.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
            if count is None:
                count = np.zeros(red.shape, np.uint16)
                seen = np.zeros(red.shape, np.uint16)
            day = np.zeros(red.shape, np.uint8)
            for img, lo, hi in ((ndvi, -0.1, 0.9), (sw, 0.05, 0.45)):
                a = np.nan_to_num(img, nan=float(np.nanmedian(img)))
                a8 = (np.clip((a - lo) / (hi - lo), 0, 1) * 255).astype(np.uint8)
                seg = lsd.detect(a8)[0]
                if seg is None:
                    continue
                seg = seg.reshape(-1, 4)
                seg = seg[np.hypot(seg[:, 2] - seg[:, 0], seg[:, 3] - seg[:, 1]) >= MIN_SEG]
                for x0, y0, x1, y1 in seg:
                    cv2.line(day, (int(round(x0)), int(round(y0))), (int(round(x1)), int(round(y1))), 1, 3)
            count += (day > 0) & ok
            seen += ok
    return count, seen


def sample(img, x0, y0, x1, y1, off=0.0, width=1):
    """Values of img along the segment, shifted `off` px across; max over +-width px across."""
    n = max(2, int(math.hypot(x1 - x0, y1 - y0)) + 1)
    t = np.linspace(0, 1, n)
    dx, dy = x1 - x0, y1 - y0
    ln = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / ln, dx / ln
    h, w = img.shape
    best = None
    for k in range(-width, width + 1):
        xs = np.clip(np.round(x0 + dx * t + nx * (off + k)).astype(int), 0, w - 1)
        ys = np.clip(np.round(y0 + dy * t + ny * (off + k)).astype(int), 0, h - 1)
        v = img[ys, xs].astype(np.float32)
        best = v if best is None else np.maximum(best, v)
    return best


def centre(img, s):
    """The segment shifted across (-3..3 px) to where the mean of img along it is highest."""
    x0, y0, x1, y1 = s
    ln = math.hypot(x1 - x0, y1 - y0) or 1.0
    nx, ny = -(y1 - y0) / ln, (x1 - x0) / ln
    offs = np.arange(-3, 3.01, 0.5)
    o = offs[int(np.argmax([sample(img, x0, y0, x1, y1, o, 0).mean() for o in offs]))]
    return (x0 + nx * o, y0 + ny * o, x1 + nx * o, y1 + ny * o)


def merge(segs):
    """Collinear segments (angle within ANGLE, both ends within OFFSET px across, gap along at most GAP px) as one."""
    segs = [tuple(map(float, s)) for s in segs]
    n = len(segs)
    if n == 0:
        return []
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    ang = np.array([math.degrees(math.atan2(s[3] - s[1], s[2] - s[0])) % 180 for s in segs])
    order = np.argsort(ang)
    a_sorted = ang[order]
    for ii, i in enumerate(order):
        for jj in range(ii + 1, n + ii):
            j = order[jj % n]
            da = (a_sorted[jj % n] - a_sorted[ii]) % 180
            if ANGLE < da < 180 - ANGLE:
                if jj < n:
                    break
                continue
            if find(i) == find(j):
                continue
            x0, y0, x1, y1 = segs[i]
            ln = math.hypot(x1 - x0, y1 - y0) or 1.0
            ux, uy = (x1 - x0) / ln, (y1 - y0) / ln
            p = np.array(segs[j]).reshape(2, 2) - [x0, y0]
            if np.abs(p[:, 0] * -uy + p[:, 1] * ux).max() > OFFSET:
                continue
            along = p[:, 0] * ux + p[:, 1] * uy
            if max(along.min() - ln, -along.max(), 0.0) <= GAP:
                parent[find(i)] = find(j)
    groups = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(i)
    out = []
    for g in groups.values():
        pts = np.array([[segs[i][0], segs[i][1]] for i in g] + [[segs[i][2], segs[i][3]] for i in g])
        wts = np.array([math.hypot(segs[i][2] - segs[i][0], segs[i][3] - segs[i][1]) for i in g] * 2) + 1e-6
        c = (pts * wts[:, None]).sum(0) / wts.sum()
        q = pts - c
        u = np.linalg.svd(q * np.sqrt(wts)[:, None], full_matrices=False)[2][0]
        t = q @ u
        out.append((c[0] + u[0] * t.min(), c[1] + u[1] * t.min(), c[0] + u[0] * t.max(), c[1] + u[1] * t.max()))
    return out


def keep_stretch(img, s):
    """The longest stretch of the line seen (>= STRONG, holes up to HOLE px), or None when it is short or weak."""
    x0, y0, x1, y1 = s
    v = sample(img, x0, y0, x1, y1, 0, 1) >= STRONG
    n = len(v)
    best, i = None, 0
    while i < n:
        if not v[i]:
            i += 1
            continue
        j, last = i, i
        while j < n and (v[j] or j - last <= HOLE):
            if v[j]:
                last = j
            j += 1
        if best is None or last - i > best[1] - best[0]:
            best = (i, last)
        i = j
    if best is None:
        return None
    a, b = best
    if b - a + 1 < MIN_LINE or v[a:b + 1].mean() < SUPPORT:
        return None
    f0, f1 = a / (n - 1), b / (n - 1)
    return (x0 + (x1 - x0) * f0, y0 + (y1 - y0) * f0, x0 + (x1 - x0) * f1, y0 + (y1 - y0) * f1)


def grow(img, s):
    """The line carried on from both ends along its direction while still seen (>= GROW within 1 px across, holes up
    to HOLE px)."""
    x0, y0, x1, y1 = s
    ln = math.hypot(x1 - x0, y1 - y0) or 1.0
    ux, uy = (x1 - x0) / ln, (y1 - y0) / ln
    nx, ny = -uy, ux
    h, w = img.shape
    ends = []
    for px, py, sx, sy in ((x0, y0, -ux, -uy), (x1, y1, ux, uy)):
        last, miss = 0, 0
        for k in range(1, GROW_LIMIT):
            qx, qy = px + sx * k, py + sy * k
            if not (1 <= round(qx) < w - 1 and 1 <= round(qy) < h - 1):
                break
            v = max(img[int(round(qy + ny * d)), int(round(qx + nx * d))] for d in (-1, 0, 1))
            if v >= GROW:
                last, miss = k, 0
            else:
                miss += 1
                if miss > HOLE:
                    break
        ends.append((px + sx * last, py + sy * last))
    return (ends[0][0], ends[0][1], ends[1][0], ends[1][1])


def _pt(x, y):
    return int(round(x)), int(round(y))


def extend(lines, shape, land):
    """Each end carried on up to EXTEND px until it meets another line (within 1 px) or leaves the land."""
    ids = np.zeros(shape, np.int32)
    for i, s in enumerate(lines):
        cv2.line(ids, _pt(s[0], s[1]), _pt(s[2], s[3]), i + 1, 1)
    h, w = shape
    out = []
    for i, (x0, y0, x1, y1) in enumerate(lines):
        ln = math.hypot(x1 - x0, y1 - y0) or 1.0
        ux, uy = (x1 - x0) / ln, (y1 - y0) / ln
        ends = []
        for px, py, sx, sy in ((x0, y0, -ux, -uy), (x1, y1, ux, uy)):
            got = (px, py)
            for k in range(1, EXTEND + 1):
                qx, qy = px + sx * k, py + sy * k
                ix, iy = _pt(qx, qy)
                if not (0 <= ix < w and 0 <= iy < h):
                    break
                win = ids[max(iy - 1, 0):iy + 2, max(ix - 1, 0):ix + 2]
                if ((win > 0) & (win != i + 1)).any():
                    got = (qx, qy)
                    break
                if not land[iy, ix]:
                    got = (qx, qy)       # up to the edge of the land: no gap left to leak through
                    break
            ends.append(got)
        out.append((ends[0][0], ends[0][1], ends[1][0], ends[1][1]))
    return out


def partition(land, lines, px=PX):
    """The land divided by the lines (see module doc): a label raster, 0 off the land."""
    cut = np.zeros(land.shape, np.uint8)
    for s in lines:
        cv2.line(cut, _pt(s[0], s[1]), _pt(s[2], s[3]), 1, 1)
    lab, _ = ndimage.label(land & (cut == 0), structure=CROSS)
    if lab.max() == 0:
        return lab
    idx = ndimage.distance_transform_edt(lab == 0, return_distances=False, return_indices=True)
    lab = np.where(land, lab[idx[0], idx[1]], 0)
    # strips narrower than 2 x SLIVER px go to the parts on either side, nearest first
    dist = ndimage.distance_transform_edt(lab > 0)
    thick = ndimage.maximum(dist, lab, index=np.arange(lab.max() + 1))
    thin_ids = np.flatnonzero((np.asarray(thick) < SLIVER) & (np.arange(lab.max() + 1) > 0))
    thin = np.isin(lab, thin_ids)
    if thin.any() and (lab > 0).sum() > thin.sum():
        keep = np.where(thin, 0, lab)
        idx = ndimage.distance_transform_edt(keep == 0, return_distances=False, return_indices=True)
        lab = np.where(thin, keep[idx[0], idx[1]], lab)
    # parts under MIN_HA join the neighbour with the longest shared border, smallest first
    min_px = MIN_HA * 1e4 / (px * px)
    for _ in range(20):
        sizes = np.bincount(lab.ravel(), minlength=lab.max() + 1)
        small = [k for k in np.argsort(sizes) if k > 0 and 0 < sizes[k] < min_px]
        if not small:
            break
        sl = ndimage.find_objects(lab)
        changed = False
        for k in small:
            if sl[k - 1] is None:
                continue
            ys, xs = sl[k - 1]
            y0, x0 = max(ys.start - 1, 0), max(xs.start - 1, 0)
            sub = lab[y0:ys.stop + 1, x0:xs.stop + 1]
            m = sub == k
            if not m.any():
                continue
            ring = ndimage.binary_dilation(m, structure=CROSS) & ~m
            nb = sub[ring]
            nb = nb[(nb > 0) & (nb != k)]
            if len(nb):
                sub[m] = np.bincount(nb).argmax()
                changed = True
        if not changed:
            break
    return lab


SEEN_MIN, MIN_BORDER, PART_HA, MAX_CUTS = 0.5, 20, 50.0, 3


def shared_borders(lab):
    """(u, v) -> (rows, cols) of the pixels of u or v where they touch (4-neighbours), u < v, both > 0."""
    out = {}
    for a, b in ((lab[:, :-1], lab[:, 1:]), (lab[:-1, :], lab[1:, :])):
        m = (a != b) & (a > 0) & (b > 0)
        ys, xs = np.nonzero(m)
        for y, x, u, v in zip(ys.tolist(), xs.tolist(), a[m].tolist(), b[m].tolist()):
            k = (min(u, v), max(u, v))
            out.setdefault(k, ([], []))
            out[k][0].append(y)
            out[k][1].append(x)
    return out


def join_unseen(lab, count, holders, seen_min=SEEN_MIN):
    """Fields whose shared border is seen (count >= STRONG within 1 px) on under seen_min of its length join, never
    two groups with different known holders. Returns (label raster, [{u, v, rows, cols, share}])."""
    seen = ndimage.maximum_filter(count, size=3) >= STRONG
    parent, hold = {}, {u: set(h) for u, h in holders.items()}

    def find(a):
        parent.setdefault(a, a)
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    joins = []
    for (u, v), (ys, xs) in sorted(shared_borders(lab).items()):
        if len(ys) < MIN_BORDER:
            continue
        share = float(seen[np.array(ys), np.array(xs)].mean())
        if share >= seen_min:
            continue
        a, b = find(u), find(v)
        if a == b:
            continue
        ha, hb = hold.get(a, set()), hold.get(b, set())
        if ha and hb and not (ha & hb):
            continue
        parent[a] = b
        hold[b] = ha | hb
        joins.append(dict(u=u, v=v, rows=ys, cols=xs, share=round(share, 3)))
    look = np.arange(lab.max() + 1)
    for u in range(1, lab.max() + 1):
        look[u] = find(u)
    return look[lab], joins


def split_by_lines(lab, lines, part_ha=PART_HA, max_cuts=MAX_CUTS, px=PX):
    """Fields cut along lines that cross them edge to edge, when the two largest parts are both >= part_ha (crumbs and
    the line's pixels go to the nearer of the two); longest line first, at most max_cuts per field.
    Returns (label raster, [{u, new, line, ha}])."""
    out = lab.copy()
    nxt = int(out.max()) + 1
    min_px = part_ha * 1e4 / (px * px)
    cuts, splits = {}, []
    for s in sorted(lines, key=lambda s: -math.hypot(s[2] - s[0], s[3] - s[1])):
        cut = np.zeros(out.shape, np.uint8)
        cv2.line(cut, _pt(s[0], s[1]), _pt(s[2], s[3]), 1, 1)
        under = out[cut > 0]
        for u in np.unique(under[under > 0]).tolist():
            if cuts.get(u, 0) >= max_cuts:
                continue
            ys, xs = np.nonzero(out == u)
            y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
            sub, csub = out[y0:y1, x0:x1], cut[y0:y1, x0:x1]
            m = sub == u
            parts, k = ndimage.label(m & (csub == 0), structure=CROSS)
            if k < 2:
                continue
            sizes = np.bincount(parts.ravel())[1:]
            big = np.flatnonzero(sizes >= min_px) + 1
            if len(big) < 2:
                continue
            two = big[np.argsort(-sizes[big - 1])][:2]
            keep = np.where(np.isin(parts, two), parts, 0)
            idx = ndimage.distance_transform_edt(keep == 0, return_distances=False, return_indices=True)
            near = keep[idx[0], idx[1]]
            sub[m & (near == two[1])] = nxt
            cuts[u] = cuts.get(u, 0) + 1
            cuts[nxt] = cuts[u]
            splits.append(dict(u=u, new=nxt, line=tuple(round(float(v), 1) for v in s),
                               ha=(round(float((sub == u).sum()) * px * px / 1e4, 1),
                                   round(float((sub == nxt).sum()) * px * px / 1e4, 1))))
            nxt += 1
    return out, splits


def edit(lab, count, holders):
    """Today's fields edited with the line evidence: unseen borders removed, then splits along seen lines.
    Returns (label raster, joins, splits, lines)."""
    joined, joins = join_unseen(lab, count.astype(np.float32), holders)
    lines = lines_of(count, joined > 0)
    out, splits = split_by_lines(joined, lines)
    return out, joins, splits, lines


def lines_of(count, land):
    """The kept, grown and carried-on lines for a line-count raster and the land to divide."""
    img = (np.clip(count.astype(np.float32) / SCALE, 0, 1) * 255).astype(np.uint8)
    seg = cv2.createLineSegmentDetector(cv2.LSD_REFINE_STD).detect(cv2.GaussianBlur(img, (3, 3), 0))[0]
    seg = [] if seg is None else seg.reshape(-1, 4)
    c = count.astype(np.float32)
    seg = [centre(c, s) for s in seg if math.hypot(s[2] - s[0], s[3] - s[1]) >= MIN_SEG]
    merged = [centre(c, s) for s in merge(seg)]
    kept = [k for k in (keep_stretch(c, s) for s in merged) if k is not None]
    kept = merge([grow(c, k) for k in kept])
    return extend(kept, count.shape, land)


def divide(count, land):
    """(label raster of the new fields, lines)."""
    lines = lines_of(count, land)
    return partition(land, lines), lines


def count_path(district_dir, sq, year):
    return f'{district_dir}/{sq}/lines-{year}/count.npz'


def count_seasons(district_dir, sq, year):
    """The two seasons a square's line count for YEAR reads: the season before and the season itself, or, with no
    season before (Tayynsha and Esil 2024, Sandyktau 2025), the season itself and the one after; None with one season."""
    have = lambda y: os.path.isdir(f'{district_dir}/{sq}/season-{y}')
    for pair in ((year - 1, year), (year, year + 1)):
        if all(have(y) for y in pair):
            return pair
    return None


def save_count(district_dir, sq, year, reuse=None):
    """The square's line count over count_seasons, kept as <sq>/lines-YEAR/count.npz (uint8). A count of another year
    over the same two seasons is copied (same seasons, same count). reuse: an existing count raster (.npy) made by the
    same method, copied instead of recounted."""
    p = count_path(district_dir, sq, year)
    if os.path.isfile(p):
        return 'exists'
    pair = count_seasons(district_dir, sq, year)
    if pair is None:
        return 'no seasons'
    twin = next((y for y in pair if y != year and count_seasons(district_dir, sq, y) == pair
                 and os.path.isfile(count_path(district_dir, sq, y))), None)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    if twin is not None:
        shutil.copyfile(count_path(district_dir, sq, twin), p)
        return f'copied from {twin}'
    if reuse and os.path.isfile(reuse):
        count = np.load(reuse)
    else:
        count, _ = line_count(sq, pair)
    np.savez_compressed(p, count=np.clip(count, 0, 255).astype(np.uint8))
    return 'made'


def load_count(district_dir, sq, year):
    return np.load(count_path(district_dir, sq, year))['count']


def main():
    if sys.argv[1] == 'count':          # python lines_first.py count DISTRICT_DIR SQUARE YEAR [REUSE.npy]
        ddir, sq, year = sys.argv[2].rstrip('/'), sys.argv[3], int(sys.argv[4])
        print(sq, save_count(ddir, sq, year, sys.argv[5] if len(sys.argv) > 5 else None), flush=True)
        return
    district, sq, year, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    '../../experiments/square-fields-2026-11-18'))
    import rect_probe
    shown, _ = rect_probe.units(district, sq)
    cpath = f'{out}/lc-{sq}.npy'
    if os.path.isfile(cpath):
        count = np.load(cpath)
    else:
        count, seen = line_count(sq, (year - 1, year))
        np.save(cpath, count)
        np.save(f'{out}/lcn-{sq}.npy', seen)
    new, lines = divide(count, shown > 0)
    np.save(f'{out}/new-{sq}.npy', new)
    np.save(f'{out}/shown-{sq}.npy', shown)
    print(sq, 'lines', len(lines), 'fields', len(np.unique(new[new > 0])), 'shown', len(np.unique(shown[shown > 0])))


if __name__ == '__main__':
    main()
