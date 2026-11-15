"""Tests for square_duplicates.py. Run: python data/tayynsha/tools/test_square_duplicates.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import square_duplicates as sd  # noqa: E402


def test_the_smaller_copy_of_a_field_cut_by_two_squares_is_dropped():
    a = np.zeros((10, 10), np.int64)
    a[2:8, 5:10] = 1                       # A sees the field up to its east edge (cut there)
    b = np.zeros((10, 10), np.int64)
    b[2:8, 0:6] = 4                        # B, which starts 5 pixels east of A, sees the rest: (2:8, 5:11) in A's frame
    drop = sd.pair_duplicates(a, b, dy=0, dx=5, a_ids={1}, b_ids={4})
    assert drop == {('a', 1, 4)}              # A's copy is 6 x 5 pixels, B's 6 x 6: the smaller, A's, goes


def test_fields_that_only_touch_in_the_overlap_are_both_kept():
    a = np.zeros((10, 10), np.int64)
    a[:, 0:7] = 1
    b = np.zeros((10, 10), np.int64)
    b[:, 3:10] = 2                          # in A's frame from column 8: touches A's field only by 0 pixels
    assert sd.pair_duplicates(a, b, dy=0, dx=5, a_ids={1}, b_ids={2}) == set()


def test_only_counted_regions_are_compared():
    a = np.ones((6, 6), np.int64)
    b = np.ones((6, 6), np.int64)
    assert sd.pair_duplicates(a, b, dy=0, dx=0, a_ids=set(), b_ids={1}) == set()


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
