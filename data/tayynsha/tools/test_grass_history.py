"""Tests for grass_history.py. Run: python data/tayynsha/tools/test_grass_history.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grass_history as gh  # noqa: E402


def test_crop_pixels_are_fields_with_a_crop_group_only():
    other = np.array([[1, 2, 3, 4, 5, 0]])
    groups = {'2025-F0001': 'short season', '2025-F0002': 'long season', '2025-F0003': 'yellow flowers',
              '2025-F0004': 'perennial grass', '2025-F0005': 'unclear'}
    assert gh.crop_pixels(other, groups, 2025).tolist() == [[True, True, True, False, False, False]]


def test_share_is_the_part_of_each_region_under_crop_pixels():
    lab = np.zeros((10, 10), np.int64)
    lab[:, :5] = 7                     # region 7
    lab[:, 5:] = 9                     # region 9
    crop = np.zeros((10, 10), bool)
    crop[:, :5] = True                 # all of region 7
    crop[:5, 5:] = True                # half of region 9
    s = gh.shares(lab, {7, 9}, crop)
    assert s == {7: 1.0, 9: 0.5}


def test_moved_when_half_or_more_was_crop_in_any_other_season():
    lab = np.zeros((4, 4), np.int64)
    lab[:, :2] = 1
    lab[:, 2:] = 2
    c2025 = np.zeros((4, 4), bool)
    c2025[:2, :2] = True               # region 1: 50% in 2025
    c2024 = np.zeros((4, 4), bool)
    c2024[:1, 2:] = True               # region 2: 25% in 2024
    out = gh.moved(lab, {1, 2}, {2025: c2025, 2024: c2024})
    assert out == {1: (0.5, 2025)}


def test_rasters_of_different_size_are_compared_on_their_common_part():
    lab = np.ones((5, 6), np.int64)
    crop = np.ones((6, 5), bool)
    assert gh.shares(lab, {1}, crop) == {1: 25 / 30}


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
