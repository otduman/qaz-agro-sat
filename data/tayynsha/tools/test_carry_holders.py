"""Tests for carry_holders.py. Run: python data/tayynsha/tools/test_carry_holders.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import carry_holders as ch  # noqa: E402


def test_a_new_field_mostly_on_old_land_of_one_holder_takes_that_holder():
    old = np.zeros((4, 10), np.int64)
    old[:, :6], old[:, 6:] = 1, 2                    # old field 1 (holder A), old field 2 (no holder)
    new = np.zeros_like(old)
    new[:, :5], new[:, 5:] = 7, 8                    # new 7 lies on old 1; new 8 is 1/5 on old 1, 4/5 on old 2
    out = ch.carried(new, old, {1: ('A', 'company')}, known=set())
    assert out == {7: ('A', 'company')}


def test_a_field_with_its_own_click_keeps_it():
    old = np.ones((3, 3), np.int64)
    new = np.full_like(old, 5)
    assert ch.carried(new, old, {1: ('A', 'company')}, known={5}) == {}


def test_land_split_between_two_old_holders_is_left_alone():
    old = np.zeros((2, 10), np.int64)
    old[:, :5], old[:, 5:] = 1, 2
    new = np.full_like(old, 9)
    assert ch.carried(new, old, {1: ('A', 'company'), 2: ('B', 'company')}, known=set()) == {}


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
