"""Tests for fallow_both_years.py. Run: python data/tayynsha/tools/test_fallow_both_years.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fallow_both_years as fb  # noqa: E402


def test_fallow_that_was_fallow_the_other_year_too_is_listed():
    a = np.zeros((10, 10), np.int64)
    a[:, :5] = 1                       # region 1: fallow this year
    a[:, 5:] = 2                       # region 2: fallow this year
    b = np.zeros((10, 10), np.int64)
    b[:, :5] = 7                       # other year: fallow under region 1
    b[:, 5:] = 8                       # other year: a crop field under region 2
    out = fb.both_years(a, {1: 'fallow', 2: 'fallow'}, b, {7: 'fallow', 8: 'field'})
    assert out == {1}


def test_a_field_is_never_listed_and_partial_overlap_needs_the_share():
    a = np.ones((10, 10), np.int64)
    b = np.zeros((10, 10), np.int64)
    b[:5] = 3                          # half of it fallow the other year
    assert fb.both_years(a, {1: 'fallow'}, b, {3: 'fallow'}) == set()          # 50% < SHARE
    assert fb.both_years(a, {1: 'field'}, np.full((10, 10), 3), {3: 'fallow'}) == set()


def test_rasters_of_different_size_are_compared_on_their_common_part():
    a = np.ones((10, 12), np.int64)
    b = np.full((12, 10), 4, np.int64)
    assert fb.both_years(a, {1: 'fallow'}, b, {4: 'fallow'}) == {1}


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(len(tests), 'passed')
