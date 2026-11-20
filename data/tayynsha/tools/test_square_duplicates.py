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


def test_a_square_on_another_grid_is_resampled_onto_this_one():
    # Esil (2026-11-16): neighbouring squares on tiles of different UTM zones (or grids not 10 m apart) were never
    # compared, so fields where they overlap were drawn twice.
    import geo
    xa, ya = 300000.0, 5800000.0                     # A: zone 42, top-left corner, 10 m pixels, 40 x 40
    lon, lat = geo.from_utm42(xa, ya)
    xb, yb = geo.utm42(lon, lat, 63.0)               # the same corner in zone 41
    b = np.zeros((60, 60), np.int64)
    b[0:20, 0:20] = 7                                # B covers the north-west of A
    on_a = sd.onto(b, xb - 100, yb + 100, 32641, (40, 40), xa, ya, 32642)
    assert on_a.shape == (40, 40)
    assert on_a[3, 3] == 7                           # 30 m into A is 130 m into B: inside the block
    assert on_a[35, 35] == 0                         # far from the block (B's label 0 there)
    assert sd.onto(b, xb + 5000, yb, 32641, (40, 40), xa, ya, 32642)[5, 5] == 0   # B wholly east of A: nothing


def test_squares_whose_boxes_almost_touch_are_still_compared():
    # Esil 2026-11-16: E280N5720 and E300N5740 boxes are 840 m apart, but their field maps overlap
    assert sd.near([280500, 5718500, 294300, 5739500], [295140, 5738500, 321500, 5759500])
    assert not sd.near([0, 0, 1000, 1000], [5000, 0, 6000, 1000])


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
