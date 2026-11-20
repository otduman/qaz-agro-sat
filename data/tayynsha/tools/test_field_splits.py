"""Tests for field_splits.py. Run: python data/tayynsha/tools/test_field_splits.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import field_splits as FS  # noqa: E402


def l_shape():
    m = np.zeros((120, 120), bool)
    m[10:110, 10:50] = True            # a tall block 100 x 40 px (40 ha)
    m[70:110, 50:110] = True           # and a wide foot 40 x 60 px (24 ha)
    return m


def test_a_rectangle_has_no_inner_corner():
    m = np.zeros((80, 80), bool)
    m[10:70, 10:60] = True
    assert FS.inner_corners(m) == []


def test_an_l_shape_has_one_inner_corner_and_two_cuts():
    corners = FS.inner_corners(l_shape())
    assert len(corners) == 1
    (x, y), dirs = corners[0]
    assert abs(x - 50) <= 2 and abs(y - 70) <= 2 and len(dirs) == 2


def test_a_cut_leaves_two_rectangles():
    m = l_shape()
    best = None
    for (x, y), dirs in FS.inner_corners(m):
        for d in dirs:
            line = FS.carry_on(m, (x, y), d)
            r = FS.shape_test(m, line)
            if r and (best is None or r['gain'] > best['gain']):
                best = r
    assert best is not None and len(best['pieces']) == 2
    assert all(f >= 0.95 for f in best['fills']) and best['gain'] >= 0.10


def test_the_line_shows_only_where_the_two_sides_differ():
    m = np.zeros((60, 100), bool)
    m[5:55, 5:95] = True
    line = ((50.0, 5.0), (50.0, 54.0))             # a vertical cut at x = 50
    a, b = np.zeros_like(m), np.zeros_like(m)
    a[5:55, 5:50], b[5:55, 51:95] = True, True
    rng = np.random.default_rng(1)
    same = 0.5 + rng.normal(0, 0.01, m.shape)
    differ = same.copy()
    differ[:, 51:] += 0.10
    swir = np.full(m.shape, 0.2)
    pts = FS.sample_points(line, a, b)
    assert FS.shows(pts, differ, swir) and not FS.shows(pts, same, swir)
    cloudy = differ.copy()
    cloudy[:, :] = np.nan
    assert not FS.shows(pts, cloudy, swir)


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
