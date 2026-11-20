"""Tests for line_splits.py. Run: python data/tayynsha/tools/test_line_splits.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import line_splits as LS  # noqa: E402


def test_each_split_field_gives_one_record_per_piece_with_shares_that_add_up():
    units = np.zeros((10, 30), np.int64)
    units[:, :20] = 1                  # field 1: split in two below
    units[:, 20:] = 2                  # field 2: left whole
    out = units.copy()
    out[:, 12:20] = 3
    recs = LS.piece_records(units, out, {1: ['2026-F0005', '2026-F0007'], 2: ['2026-F0009']},
                            {1: '2026-F0007', 2: '2026-F0009'})
    assert [(r['unit'], r['label']) for r in recs] == [(1, 1), (1, 3)]
    assert all(r['keep'] == '2026-F0007' and r['members'] == ['2026-F0005', '2026-F0007'] for r in recs)
    assert abs(sum(r['share'] for r in recs) - 1) < 1e-9 and recs[0]['share'] == 0.6


def test_fields_split_before_are_left_alone():
    units = np.array([[1, 1, 2, 2]])
    keep = {1: '2026-F0001', 2: '2026-F0002'}
    lab = LS.without(units, keep, {'2026-F0002'})
    assert lab.tolist() == [[1, 1, 0, 0]]


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
