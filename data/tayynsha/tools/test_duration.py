"""Tests for duration.py. Run: python data/tayynsha/tools/test_duration.py"""
import random
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import duration  # noqa: E402


def test_span_counts_days_from_10_to_90_percent_of_area():
    fields = [(day, 100.0) for day in range(0, 100, 10)]  # ten equal fields, days 0..90
    assert duration.span(fields) == 80, duration.span(fields)


def test_span_weights_by_area():
    fields = [(0, 1000.0), (30, 10.0), (60, 10.0)]  # one big field holds 98% of the area
    assert duration.span(fields) == 0, duration.span(fields)


def test_a_group_spread_to_the_extremes_is_rarely_matched_by_chance():
    pool = [(day, 100.0) for day in range(0, 60, 2)]  # 30 neighbouring fields, days 0..58
    group = [(0, 100.0), (2, 100.0), (56, 100.0), (58, 100.0), (30, 100.0)]
    share = duration.chance(group, pool, draws=2000, rng=random.Random(1))
    assert share < 0.1, share


def test_a_group_cut_on_one_day_is_always_matched():
    pool = [(day, 100.0) for day in range(0, 60, 2)]
    group = [(20, 100.0)] * 5
    assert duration.chance(group, pool, draws=500, rng=random.Random(1)) == 1.0


def test_chance_needs_enough_neighbours():
    pool = [(day, 100.0) for day in range(3)]
    group = [(0, 100.0)] * 5
    assert duration.chance(group, pool, draws=10, rng=random.Random(1)) is None


def test_correlation():
    assert abs(duration.correlation([(1, 2), (2, 4), (3, 6)]) - 1.0) < 1e-9
    assert duration.correlation([(1, 2)]) is None


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
