"""Tests for colour_series.py. Run: python data/tayynsha/tools/test_colour_series.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import colour_series  # noqa: E402


def test_interior_drops_the_edge_pixels_of_each_field():
    labels = np.zeros((6, 6), dtype=np.int64)
    labels[1:5, 1:5] = 1  # a 4x4 field: its inner 2x2 is interior
    inner = colour_series.interior(labels)
    assert inner.sum() == 4, inner
    assert inner[2:4, 2:4].all()


def test_field_means_use_only_clear_interior_pixels():
    labels = np.zeros((6, 6), dtype=np.int64)
    labels[1:5, 1:5] = 1
    band = np.full((6, 6), 0.5)
    band[2, 2] = 0.9  # interior, clouded below
    band[1, 1] = 9.0  # edge: never counted
    clear = np.ones((6, 6), dtype=bool)
    clear[2, 2] = False
    means, share = colour_series.field_means(labels, colour_series.interior(labels), clear, [band], regions=2)
    assert abs(means[0][1] - 0.5) < 1e-9, means
    assert abs(share[1] - 0.75) < 1e-9, share


def test_reflectance_applies_the_scene_scale_and_offset_and_nodata():
    raw = np.array([[0, 2000]], dtype=np.uint16)
    out = colour_series.reflectance(raw, scale=1e-4, offset=-0.1, nodata=0)
    assert np.isnan(out[0, 0]) and abs(out[0, 1] - 0.1) < 1e-9, out


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
