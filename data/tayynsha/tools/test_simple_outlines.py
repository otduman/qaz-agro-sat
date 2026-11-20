"""Tests for simple_outlines.py. Run: python data/tayynsha/tools/test_simple_outlines.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import simple_outlines as SO  # noqa: E402


def test_a_field_with_a_grove_bay_is_drawn_as_its_rectangle():
    lab = np.zeros((120, 120), np.int64)
    lab[10:110, 10:110] = 1            # 100 ha
    lab[10:30, 40:70] = 0              # a 6 ha grove bitten out of the top side
    out = SO.simple(lab)
    assert 1 in out and SO.full(out[1], lab.shape)[10:110, 10:110].all()


def test_a_big_bay_is_cut_back_out_and_neighbours_keep_their_land():
    lab = np.zeros((200, 200), np.int64)
    lab[10:190, 10:190] = 1            # 324 ha
    lab[10:50, 60:100] = 0             # a 16 ha forest bay: over 10 ha, stays outside
    lab[10:14, 120:160] = 0
    lab[180:190, 150:190] = 2          # another field in a corner
    out = SO.simple(lab)
    m = SO.full(out[1], lab.shape)
    assert not m[10:50, 60:100].any() and m[10:14, 120:160].all() and not m[lab == 2].any()


def test_an_l_shape_and_a_whole_rectangle_are_left_alone():
    lab = np.zeros((100, 100), np.int64)
    lab[10:90, 10:40] = 1
    lab[60:90, 40:90] = 1              # L: fills its rectangle about 0.6
    lab[5:9, 5:95] = 2                 # a clean strip: nothing to add
    out = SO.simple(lab)
    assert 1 not in out and 2 not in out


def test_too_much_added_land_in_all_keeps_the_outline():
    lab = np.zeros((120, 120), np.int64)
    lab[10:110, 10:110] = 1
    for x0, x1 in ((12, 42), (50, 80), (85, 105)):   # three bays of 6, 6 and 4 ha: each small, 16% in all
        lab[10:30, x0:x1] = 0
    out = SO.simple(lab)
    assert 1 not in out


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
