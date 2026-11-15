"""Evidence for land labels (2026-11-13 land-label check): the outline shape of each region, how far it is enclosed
by one neighbour, and per-date means over each region's clear pixels (NDVI, water indices, SWIR).

Shape, in plain words:
  compactness     4 pi area / outline length squared: 1 for a circle, 0.785 for a square, small for a long or
                  ragged shape (holes add to the outline);
  straight share  the outer outline redrawn with as few straight strokes as possible, keeping within SIMPLIFY_M of
                  it; the share of its length in strokes at least STRAIGHT_M long. Fields here are bounded by long
                  straight lines; marsh, hollows and steppe patches are not;
  rectangle fill  area over the smallest (turned) rectangle around the outline: 1 for a rectangle, 0.785 for a
                  circle, low for an L, a crescent or a ragged blob;
  solidity        area over the convex hull's area;
  enclosure       the largest share of the region's border pixels that touch one single other region (map edge
                  and unlabelled pixels count as no region).
"""
import math

import numpy as np

SIMPLIFY_M = 10.0
STRAIGHT_M = 200.0


def ring_area(ring):
    """Signed area (shoelace) of a closed ring of (x, y) points in metres."""
    a = np.asarray(ring, float)
    x, y = a[:, 0], a[:, 1]
    return 0.5 * float(np.sum(x[:-1] * y[1:] - x[1:] * y[:-1]))


def ring_length(ring):
    a = np.asarray(ring, float)
    return float(np.sum(np.hypot(np.diff(a[:, 0]), np.diff(a[:, 1]))))


def shape_measures(rings):
    """rings: outer ring first, then holes, each closed, in metres. Area, outline length and compactness."""
    area = abs(ring_area(rings[0])) - sum(abs(ring_area(h)) for h in rings[1:])
    perim = sum(ring_length(r) for r in rings)
    return dict(area_ha=area / 1e4, perimeter_m=perim, compactness=4 * math.pi * area / perim ** 2 if perim else 0.0)


def _dp(points, tol):
    """Douglas-Peucker on an open polyline: indices of the kept points."""
    keep = [0, len(points) - 1]
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        p, q = points[i], points[j]
        seg = points[i + 1:j]
        d = q - p
        n = math.hypot(d[0], d[1])
        if n == 0:
            dist = np.hypot(seg[:, 0] - p[0], seg[:, 1] - p[1])
        else:
            dist = np.abs(d[0] * (seg[:, 1] - p[1]) - d[1] * (seg[:, 0] - p[0])) / n
        k = int(np.argmax(dist))
        if dist[k] > tol:
            m = i + 1 + k
            keep.append(m)
            stack += [(i, m), (m, j)]
    return sorted(set(keep))


def simplify(ring, tol=SIMPLIFY_M):
    """A closed ring redrawn with few straight strokes, within tol of it. Split at the two points farthest apart so
    that the start point does not matter much."""
    a = np.asarray(ring, float)
    if len(a) < 4:
        return a
    a = a[:-1] if np.allclose(a[0], a[-1]) else a
    far = int(np.argmax(np.hypot(a[:, 0] - a[0, 0], a[:, 1] - a[0, 1])))
    start = int(np.argmax(np.hypot(a[:, 0] - a[far, 0], a[:, 1] - a[far, 1])))
    a = np.roll(a, -start, axis=0)
    a = np.vstack([a, a[:1]])
    return a[_dp(a, tol)]


def straight_share(ring, tol=SIMPLIFY_M, min_len=STRAIGHT_M):
    s = simplify(ring, tol)
    seg = np.hypot(np.diff(s[:, 0]), np.diff(s[:, 1]))
    total = seg.sum()
    return float(seg[seg >= min_len].sum() / total) if total else 0.0


def hull(points):
    """Convex hull (monotone chain) of (x, y) points, as an array without repeating the first point."""
    pts = sorted(set(map(tuple, np.asarray(points, float).tolist())))
    if len(pts) < 3:
        return np.asarray(pts)

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return np.asarray(lower[:-1] + upper[:-1])


def solidity(ring):
    h = hull(ring)
    ha = abs(ring_area(np.vstack([h, h[:1]])))
    return abs(ring_area(ring)) / ha if ha else 0.0


def rectangle_fill(ring):
    """Area over the smallest turned rectangle holding the ring (one side of that rectangle lies on a hull side)."""
    h = hull(ring)
    if len(h) < 3:
        return 0.0
    edges = np.diff(np.vstack([h, h[:1]]), axis=0)
    best = math.inf
    for ex, ey in edges:
        n = math.hypot(ex, ey)
        if n == 0:
            continue
        ux, uy = ex / n, ey / n
        u = h[:, 0] * ux + h[:, 1] * uy
        v = -h[:, 0] * uy + h[:, 1] * ux
        best = min(best, (u.max() - u.min()) * (v.max() - v.min()))
    return abs(ring_area(ring)) / best if best > 0 else 0.0


def enclosure(labels, regions):
    """Per region (index 0..regions-1): the largest share of its border pixels that touch one other region, and that
    region (0 when none). A border pixel has a 4-neighbour outside the region or lies on the map edge."""
    lab = np.asarray(labels).astype(np.int64)
    pad = np.pad(lab, 1, constant_values=0)
    centre = pad[1:-1, 1:-1]
    edge_map = np.zeros(lab.shape, bool)
    edge_map[0, :] = edge_map[-1, :] = edge_map[:, 0] = edge_map[:, -1] = True
    border = edge_map.copy()
    pairs = []
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nb = pad[1 + dy:pad.shape[0] - 1 + dy, 1 + dx:pad.shape[1] - 1 + dx]
        diff = nb != centre
        border |= diff
        m = diff & (centre > 0) & (nb > 0)
        pairs.append(np.stack([centre[m], nb[m], np.flatnonzero(m.ravel())], axis=1))
    border &= centre > 0
    n_border = np.bincount(centre[border], minlength=regions)
    share = np.zeros(regions)
    neighbour = np.zeros(regions, np.int64)
    if pairs:
        p = np.unique(np.concatenate(pairs), axis=0)  # one count per (region, neighbour, pixel)
        rn = np.unique(p[:, :2], axis=0, return_counts=True)
        for (r, k), c in zip(rn[0], rn[1]):
            s = c / n_border[r] if n_border[r] else 0.0
            if s > share[r]:
                share[r], neighbour[r] = s, k
    return share, neighbour


def region_means(labels, clear, values, regions, min_share=0.6):
    """Per value array, the mean over each region's clear pixels (NaN where the region is under min_share clear),
    and each region's clear share."""
    lab = np.asarray(labels).ravel()
    ok = np.asarray(clear).ravel()
    total = np.bincount(lab, minlength=regions).astype(float)
    n = np.bincount(lab[ok], minlength=regions).astype(float)
    with np.errstate(invalid='ignore', divide='ignore'):
        share = np.where(total > 0, n / total, 0.0)
        out = []
        for v in values:
            v = np.asarray(v).ravel()
            good = ok & np.isfinite(v)
            s = np.bincount(lab[good], weights=v[good], minlength=regions)
            c = np.bincount(lab[good], minlength=regions)
            m = np.where(c > 0, s / np.maximum(c, 1), np.nan)
            m[share < min_share] = np.nan
            out.append(m)
    return out, share


def region_share(labels, clear, test, regions):
    """Share of each region's clear pixels that meet test."""
    lab = np.asarray(labels).ravel()
    ok = np.asarray(clear).ravel()
    t = np.asarray(test).ravel() & ok
    n = np.bincount(lab[ok], minlength=regions).astype(float)
    k = np.bincount(lab[t], minlength=regions).astype(float)
    with np.errstate(invalid='ignore', divide='ignore'):
        return np.where(n > 0, k / np.maximum(n, 1), np.nan)


def normalised_difference(a, b):
    """(a - b) / (a + b) with light below 0 counted as 0 and no value where there is no light, as the field method
    computes NDVI (core/index/Ndvi.java)."""
    a = np.maximum(np.asarray(a, np.float32), 0)
    b = np.maximum(np.asarray(b, np.float32), 0)
    s = a + b
    with np.errstate(invalid='ignore', divide='ignore'):
        return np.where(s > 0, (a - b) / s, np.nan)


def snow(green, nir, swir16):
    """The field method's snow test (core/mask/SnowTest.java): snow index at least 0.4 and near-infrared at least
    0.11."""
    s = green + swir16
    with np.errstate(invalid='ignore', divide='ignore'):
        return (s > 0) & ((green - swir16) / s >= 0.4) & (nir >= 0.11)


def upsample(a, shape):
    """A 20 m band on the 10 m grid: each pixel becomes a 2 x 2 block, cut to shape."""
    return np.repeat(np.repeat(a, 2, axis=0), 2, axis=1)[:shape[0], :shape[1]]
