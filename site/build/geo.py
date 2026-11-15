"""Coordinates for the Field Atlas pages: a flat kilometre grid around each district's centre, and UTM zone 42N
(EPSG 32642, the grid of the Sentinel-2 stacks). utm42 and from_utm42 take numbers or numpy arrays."""
import math

import numpy as np

KM_PER_DEG_LAT = 110.57


def projector(lat0, lon0):
    """(lon, lat) -> (x, y) in km on the page grid; y grows southwards, as on screen."""
    kx = 111.32 * math.cos(math.radians(lat0))
    return lambda lon, lat: (round((lon - lon0) * kx, 3), round((lat0 - lat) * KM_PER_DEG_LAT, 3))


def unprojector(lat0, lon0):
    """The exact inverse of projector (without its rounding): (x, y) km -> (lon, lat). Works on arrays."""
    kx = 111.32 * math.cos(math.radians(lat0))
    return lambda x, y: (lon0 + x / kx, lat0 - y / KM_PER_DEG_LAT)


_A = 6378137.0
_F = 1 / 298.257223563
_N = _F / (2 - _F)
_AA = _A / (1 + _N) * (1 + _N ** 2 / 4 + _N ** 4 / 64)
_AL = [None, _N / 2 - 2 * _N ** 2 / 3 + 5 * _N ** 3 / 16, 13 * _N ** 2 / 48 - 3 * _N ** 3 / 5, 61 * _N ** 3 / 240]
_E = math.sqrt(_F * (2 - _F))


def utm42(lon, lat, lon0=69.0):
    """(lon, lat) -> UTM zone 42N (east, north) in metres (Krüger series)."""
    phi, lam = np.radians(lat), np.radians(np.asarray(lon) - lon0)
    t = np.sinh(np.arctanh(np.sin(phi)) - _E * np.arctanh(_E * np.sin(phi)))
    xi = np.arctan2(t, np.cos(lam))
    eta = np.arctanh(np.sin(lam) / np.sqrt(1 + t * t))
    x = xi + sum(_AL[j] * np.sin(2 * j * xi) * np.cosh(2 * j * eta) for j in range(1, 4))
    y = eta + sum(_AL[j] * np.cos(2 * j * xi) * np.sinh(2 * j * eta) for j in range(1, 4))
    e, n = 500000 + 0.9996 * _AA * y, 0.9996 * _AA * x
    return (float(e), float(n)) if np.ndim(e) == 0 else (e, n)


def from_utm42(east, north, lon0=69.0):
    """UTM zone 42N (or the zone with central meridian lon0) -> (lon, lat), by iterating the forward projection."""
    lon, lat = lon0, 54.0
    for _ in range(40):
        e, n = utm42(lon, lat, lon0)
        lon += (east - e) / (111320 * math.cos(math.radians(lat)))
        lat += (north - n) / 111200
    return lon, lat


def simplify(points, tol):
    """Douglas-Peucker on a closed ring of (x, y)."""
    if len(points) < 5:
        return points

    def dp(pts):
        a, b = pts[0], pts[-1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        norm = math.hypot(dx, dy) or 1e-12
        best, idx = -1.0, 0
        for i in range(1, len(pts) - 1):
            d = abs(dy * (pts[i][0] - a[0]) - dx * (pts[i][1] - a[1])) / norm
            if d > best:
                best, idx = d, i
        if best > tol:
            return dp(pts[:idx + 1])[:-1] + dp(pts[idx:])
        return [a, b]

    half = len(points) // 2
    out = dp(points[:half + 1])[:-1] + dp(points[half:])
    return out if len(out) >= 4 else points


def utm_box(proj, e0, n0, e1, n1, steps=8):
    """A UTM rectangle as a flat ring [x, y, x, y, ...] on the page grid, with points along each edge."""
    ring = []
    for i in range(steps):
        ring.append((e0 + (e1 - e0) * i / steps, n1))
    for i in range(steps):
        ring.append((e1, n1 - (n1 - n0) * i / steps))
    for i in range(steps):
        ring.append((e1 - (e1 - e0) * i / steps, n0))
    for i in range(steps):
        ring.append((e0, n0 + (n1 - n0) * i / steps))
    return [round(v, 2) for e, n in ring for v in proj(*from_utm42(e, n))]
