"""Tests for fill_gaps.py. Run: python data/tayynsha/tools/test_fill_gaps.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fill_gaps as fg  # noqa: E402

LAND = {1: 'field', 2: 'field', 3: 'other'}


def test_a_small_hole_inside_a_field_joins_it():
    lab = np.ones((100, 100), np.int64)
    lab[40:55, 40:55] = 0                       # 2.25 ha blob
    out = fg.fill(lab, LAND)
    assert (out[40:55, 40:55] == 1).all()


def test_a_small_gap_between_two_fields_goes_to_the_one_it_touches_most():
    lab = np.zeros((100, 100), np.int64)
    lab[:, :50] = 1
    lab[:, 60:] = 2
    lab[:60, 50:60] = 1                          # field 1 wraps round the gap on three sides
    lab[90:, 50:60] = 2                          # and field 2 closes it at the bottom, away from the map edge
    out = fg.fill(lab, LAND)
    assert (out[60:90, 50:60] == 1).all()


def test_a_thin_strip_between_fields_is_left():
    lab = np.zeros((100, 100), np.int64)
    lab[:, :48] = 1
    lab[:, 50:] = 2
    lab[:5, 48:50] = 1
    lab[95:, 48:50] = 2                          # a 20 m road strip between two fields, closed at both ends
    out = fg.fill(lab, LAND)
    assert (out[5:95, 48:50] == 0).all()


def test_a_grove_that_looks_different_from_the_field_is_left():
    lab = np.ones((100, 100), np.int64)
    lab[40:55, 40:55] = 0
    ndvi = np.full(lab.shape, 0.25)             # the field is cut and yellow in late summer
    ndvi[40:55, 40:55] = 0.6                    # the gap is a green birch grove
    bright = np.full(lab.shape, 0.30)
    bright[40:55, 40:55] = 0.15
    out = fg.fill(lab, LAND, photo=(ndvi, bright))
    assert (out[40:55, 40:55] == 0).all()


def test_a_blob_of_the_same_crop_is_filled():
    lab = np.ones((100, 100), np.int64)
    lab[40:55, 40:55] = 0
    ndvi = np.full(lab.shape, 0.25)
    ndvi[40:55, 40:55] = 0.27
    bright = np.full(lab.shape, 0.30)
    out = fg.fill(lab, LAND, photo=(ndvi, bright))
    assert (out[40:55, 40:55] == 1).all()


def test_water_is_never_filled():
    lab = np.ones((100, 100), np.int64)
    lab[40:55, 40:55] = 0
    water = np.zeros(lab.shape, bool)
    water[40:55, 40:55] = True
    out = fg.fill(lab, LAND, water=water)
    assert (out[40:55, 40:55] == 0).all()


def test_a_large_gap_is_left():
    lab = np.ones((200, 200), np.int64)
    lab[50:150, 50:150] = 0                     # 100 ha
    assert np.array_equal(fg.fill(lab, LAND), lab)


def test_a_gap_mostly_against_land_that_is_not_a_field_is_left():
    lab = np.full((100, 100), 3, np.int64)
    lab[:, :30] = 1
    lab[40:55, 25:40] = 0                       # touches field 1 on one side, other land on three
    assert np.array_equal(fg.fill(lab, LAND), lab)


def test_the_open_land_at_the_map_edge_is_left():
    lab = np.ones((100, 100), np.int64)
    lab[:10, :10] = 0                           # a gap cut by the square's edge: we cannot see all of it
    assert np.array_equal(fg.fill(lab, LAND), lab)


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(f'{len(tests)} tests passed')
