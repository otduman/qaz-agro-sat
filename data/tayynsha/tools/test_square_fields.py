"""Tests for square_fields.py. Run: python data/tayynsha/tools/test_square_fields.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import square_fields as SF  # noqa: E402

AGREE = dict(holders=set(), crop='grain', cut=None)


def test_a_rectangle_with_bays_is_drawn_as_its_rectangle():
    lab = np.zeros((80, 80), np.int64)
    lab[10:70, 10:70] = 1
    lab[10:18, 30:45] = 0              # a bay of scrub on the top side
    lab[50:60, 10:16] = 0              # and one on the left
    out = SF.straighten(lab, {1: AGREE})
    assert out[1]['kind'] == 'rectangle'
    assert SF.full(out[1], lab.shape)[10:70, 10:70].all()


def test_neighbours_keep_their_land():
    lab = np.zeros((80, 80), np.int64)
    lab[10:70, 10:70] = 1
    lab[10:16, 30:40] = 2              # another field cuts in (2% of the rectangle)
    lab[10:16, 40:46] = 0
    info = {1: AGREE, 2: dict(AGREE, crop='oilseed')}
    out = SF.straighten(lab, info)
    assert out[1]['kind'] == 'rectangle' and not SF.full(out[1], lab.shape)[lab == 2].any()


def test_a_trapezoid_is_drawn_as_its_hull_not_its_rectangle():
    lab = np.zeros((100, 100), np.int64)
    for y in range(10, 90):            # right side slants (a road)
        lab[y, 10:40 + (y - 10) // 2] = 1
    lab[40:50, 10:14] = 0              # a small bay
    out = SF.straighten(lab, {1: AGREE})
    assert out[1]['kind'] == 'hull'
    assert SF.full(out[1], lab.shape)[40:50, 10:14].all()


def test_an_l_shape_is_left_alone():
    lab = np.zeros((80, 80), np.int64)
    lab[10:70, 10:30] = 1
    lab[50:70, 30:70] = 1
    assert 1 not in SF.straighten(lab, {1: AGREE})


def test_too_much_added_land_keeps_the_old_outline():
    lab = np.zeros((80, 80), np.int64)
    lab[10:70, 10:70] = 1
    lab[10:40, 10:24] = 0              # 12% of the rectangle missing: still fills 0.88
    out = SF.straighten(lab, {1: AGREE}, max_added=0.10)
    assert 1 not in out


def test_a_piece_inside_the_new_outline_joins_when_they_agree():
    lab = np.zeros((80, 80), np.int64)
    lab[10:70, 10:70] = 1
    lab[10:20, 30:38] = 2              # a small piece in a bay of field 1
    out = SF.straighten(lab, {1: AGREE, 2: AGREE})
    assert out[1]['joined'] == [2] and SF.full(out[1], lab.shape)[lab == 2].all()
    out = SF.straighten(lab, {1: AGREE, 2: dict(AGREE, crop='oilseed')})
    assert 1 not in out                # nothing to add or join: the outline stays


def test_v3_keeps_holes_and_ragged_edges_and_cuts_out_a_deep_bay_of_other_cover():
    m = np.zeros((100, 100), bool)
    m[10:90, 10:90] = True
    m[40:50, 40:50] = False            # a pond inside: enclosed
    m[10:12, 20:60] = False            # a ragged top edge, 20 m deep
    m[60:90, 10:30] = False            # a deep forest bay in the bottom-left corner (300 m)
    new = np.zeros_like(m)
    new[10:90, 10:90] = True           # the rectangle
    patches = SF.added_patches(new, m, np.zeros(m.shape, np.int64))
    kinds = sorted(p['why'] for p in patches)
    assert kinds == ['deep', 'enclosed', 'shallow']
    out = SF.carve(new, patches, same_cover=lambda p: False)
    assert out[45, 45] and out[10, 30] and not out[75, 20]
    out = SF.carve(new, patches, same_cover=lambda p: True)      # the bay grows the field's own crop
    assert out[75, 20]


def test_curves_agree_by_the_third_largest_gap():
    a = {f'2026-06-{d:02d}': 0.5 for d in range(1, 9)}
    b = dict(a)
    assert SF.curves_agree(a, b)
    b.update({'2026-06-01': 0.9, '2026-06-02': 0.9})            # two bad dates are forgiven
    assert SF.curves_agree(a, b)
    b['2026-06-03'] = 0.9
    assert not SF.curves_agree(a, b)
    assert not SF.curves_agree(a, {'2026-06-01': 0.5})           # too few common dates


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
