"""Tests for fallow_steppe.py. Run: python data/tayynsha/tools/test_fallow_steppe.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fallow_steppe as fs  # noqa: E402


def row(region, date, ndmi, ndvi='0.2'):
    return {'region': str(region), 'date': date, 'ndvi': ndvi, 'ndmi': str(ndmi)}


def test_fallow_damp_or_green_in_spring_is_steppe():
    rows = [row(1, '2026-05-10', -0.05), row(1, '2026-06-01', -0.12), row(1, '2026-07-01', -0.40),
            row(2, '2026-05-10', -0.25), row(2, '2026-06-01', -0.22)]
    assert fs.steppe(rows, {1, 2}, 2026) == {1}


def test_only_fallow_regions_and_only_clear_spring_dates_count():
    rows = [row(3, '2026-05-10', 0.0),                       # a field, not fallow: never listed
            row(4, '2026-05-10', 0.0, ndvi=''),              # not clear that date: ignored
            row(4, '2026-05-20', -0.30),
            row(4, '2026-06-20', 0.10)]                      # after 15 June: ignored
    assert fs.steppe(rows, {4}, 2026) == set()


def test_the_threshold_is_inclusive():
    assert fs.steppe([row(5, '2026-05-01', -0.15)], {5}, 2026) == {5}


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(len(tests), 'passed')
