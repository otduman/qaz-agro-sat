"""Tests for evidence_joins.py. Run: python data/tayynsha/tools/test_evidence_joins.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import evidence_joins as EJ  # noqa: E402


def field(ha=200, crop='grain', cut='2026-08-20', holders=(), fill=0.9):
    return dict(ha=ha, crop=crop, cut=cut, holders=set(holders), fill=fill, curve={f'2026-06-{d:02d}': 0.5 for d in range(1, 9)})


def test_two_look_alike_fields_across_an_unseen_border_join():
    assert EJ.same_field(field(), field(cut='2026-08-23'), seen=0.2, fill_join=0.9)


def test_each_piece_of_evidence_can_stop_a_join():
    a = field()
    assert not EJ.same_field(a, field(), seen=0.6, fill_join=0.9)                        # E1 a seen line
    assert not EJ.same_field(a, field(crop='oilseed'), seen=0.2, fill_join=0.9)          # E2 crop
    assert not EJ.same_field(a, field(cut='2026-09-01'), seen=0.2, fill_join=0.9)        # E3 cut 12 days apart
    assert not EJ.same_field(a, field(cut=''), seen=0.2, fill_join=0.9)                  # E3 dated against undated
    b = field()
    b['curve'] = {k: 0.8 for k in b['curve']}
    assert not EJ.same_field(a, b, seen=0.2, fill_join=0.9)                              # E4 curves
    assert not EJ.same_field(field(holders=['A']), field(holders=['B']), seen=0.2, fill_join=0.9)   # E5
    assert not EJ.same_field(a, field(), seen=0.2, fill_join=0.85)                       # E6 shape worse


def test_a_small_piece_may_join_with_a_slightly_worse_shape_and_an_unclear_crop():
    big, small = field(fill=0.9), field(ha=30, crop='none', fill=0.6)
    assert EJ.same_field(big, small, seen=0.2, fill_join=0.86)
    assert not EJ.same_field(big, field(ha=80, crop='none'), seen=0.2, fill_join=0.9)    # unclear crop, not small


def test_a_small_field_is_shown_when_its_outline_is_a_seen_line():
    m = np.zeros((40, 40), bool)
    m[10:30, 10:30] = True               # 4 ha at 10 m: too small even when seen
    seen = np.ones(m.shape, bool)
    assert not EJ.real_small_field(m, seen)
    m = np.zeros((80, 80), bool)
    m[10:60, 10:60] = True               # 25 ha
    assert EJ.real_small_field(m, np.ones(m.shape, bool))
    assert not EJ.real_small_field(m, np.zeros(m.shape, bool))
    m = np.zeros((120, 120), bool)
    for y in range(10, 110):           # a ragged strip (about 30 ha): seen all round but no simple shape
        m[y, 10 + (y % 9) * 6:40 + (y % 5) * 8] = True
    assert m.sum() >= 2000
    m = np.zeros((80, 80), bool)
    m[0:50, 10:60] = True                # 25 ha but cut by the edge of the square: its rest lies in the next square
    assert not EJ.real_small_field(m, np.ones(m.shape, bool))
    assert not EJ.real_small_field(m, np.ones(m.shape, bool))



def test_joins_are_off_after_the_blind_check():
    # 2026-11-19: evidence joins failed blind (10 of 17 decided were one field); only small fields are kept
    assert EJ.JOINS is False


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
