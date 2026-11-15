"""Tests for trim_tails.py. Run: python data/tayynsha/tools/test_trim_tails.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trim_tails as tt  # noqa: E402

LAND = {1: 'field', 2: 'field', 3: 'other'}


def test_a_thin_tail_is_cut_off_a_field():
    lab = np.zeros((100, 100), np.int64)
    lab[20:80, 20:60] = 1
    lab[45:48, 60:95] = 1                       # a 30 m wide, 350 m long tail along a road
    out = tt.trim(lab, LAND)
    assert (out[45:48, 62:95] == 0).all()
    assert (out[20:80, 20:60] == 1).all()


def test_a_cut_tail_lying_along_a_neighbour_goes_to_it():
    lab = np.zeros((100, 100), np.int64)
    lab[20:80, 20:60] = 1
    lab[20:80, 63:90] = 2
    lab[20:80, 60:63] = 1                       # a 30 m strip of field 1 against field 2 ... still part of field 1's body
    lab[10:20, 60:63] = 1                       # and a thin finger reaching past it
    out = tt.trim(lab, LAND)
    assert (out[10:18, 60:63] != 1).all()


def test_a_regular_field_is_left_as_it_is():
    lab = np.zeros((60, 60), np.int64)
    lab[10:50, 10:50] = 1
    assert np.array_equal(tt.trim(lab, LAND), lab)


def test_a_thin_field_on_its_own_is_kept_whole():
    lab = np.zeros((60, 100), np.int64)
    lab[20:24, 5:95] = 1                        # a 40 m wide strip field: no body to cut a tail off
    assert np.array_equal(tt.trim(lab, LAND), lab)


def test_land_that_is_not_a_field_is_never_trimmed():
    lab = np.zeros((100, 100), np.int64)
    lab[20:80, 20:60] = 3
    lab[45:48, 60:95] = 3
    assert np.array_equal(tt.trim(lab, LAND), lab)


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(f'{len(tests)} tests passed')
