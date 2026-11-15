"""Tests for drop_no_crop.py. Run: python data/tayynsha/tools/test_drop_no_crop.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drop_no_crop as dn  # noqa: E402


def test_only_new_fields_where_no_crop_is_seen_go_back_to_their_old_label():
    table = [dict(id='F1', land='other', land_v2='field'), dict(id='F2', land='fallow', land_v2='field'),
             dict(id='F3', land='other', land_v2='field'), dict(id='F4', land='field', land_v2='field'),
             dict(id='F5', land='other', land_v2='other')]
    groups = {'F1': 'no crop seen', 'F2': 'no crop seen', 'F3': 'short season', 'F4': 'no crop seen'}
    assert dn.reverts(table, groups) == {'F1': 'other', 'F2': 'fallow'}


def test_rows_take_the_old_label_and_dropped_fields_leave_field_tables():
    back = {'F1': 'other', 'F2': 'fallow'}
    rows = [dict(id='F1', land='field', x='1'), dict(id='F2', land='field', x='2'), dict(id='F3', land='field', x='3')]
    assert dn.relabel_rows(rows, 'id', back) == [dict(id='F1', land='other', x='1'), dict(id='F2', land='fallow', x='2'),
                                                 dict(id='F3', land='field', x='3')]
    assert dn.drop_rows(rows, 'id', back) == [dict(id='F3', land='field', x='3')]


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
