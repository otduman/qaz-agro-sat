"""Tests for rain.py. Run: python data/tayynsha/tools/test_rain.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rain  # noqa: E402


def test_metres_become_millimetres_sorted_by_date_and_missing_days_left_out():
    feats = [{'date': '2026-08-02', 'total_precipitation_sum': 0.0043},
             {'date': '2026-08-01', 'total_precipitation_sum': 0.0},
             {'date': '2026-08-03'}]
    assert rain.rows_from(feats, 2026) == [('2026-08-01', 0.0), ('2026-08-02', 4.3)]


if __name__ == '__main__':
    n = 0
    for name, fn in sorted(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)
            n += 1
    print(n, 'passed')
