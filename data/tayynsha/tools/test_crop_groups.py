"""Tests for crop_groups.py. Run: python data/tayynsha/tools/test_crop_groups.py"""
import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crop_groups as cg  # noqa: E402


def d(m, day):
    return dt.date(2026, m, day)


def series(*pairs):
    return [(d(m, day), v) for (m, day), v in pairs]


GRAIN = series(((5, 6), 0.2), ((6, 5), 0.3), ((6, 20), 0.6), ((7, 10), 0.8), ((7, 25), 0.6), ((7, 30), 0.35),
               ((8, 9), 0.25), ((8, 20), 0.2))


def test_ripening_is_the_first_date_after_the_peak_below_04_confirmed_by_the_next():
    assert cg.ripening(GRAIN) == d(7, 30)


def test_a_single_low_date_is_not_ripening():
    s = series(((7, 10), 0.8), ((7, 20), 0.3), ((7, 25), 0.7), ((8, 20), 0.35), ((8, 25), 0.2))
    assert cg.ripening(s) == d(8, 20)


def test_no_ripening_without_a_confirming_date():
    s = series(((7, 10), 0.8), ((8, 20), 0.6), ((9, 1), 0.3))
    assert cg.ripening(s) is None


def test_perennial_grass_is_green_early_and_never_dips():
    grass = series(((5, 6), 0.45), ((5, 20), 0.5), ((6, 5), 0.55), ((6, 14), 0.5), ((6, 20), 0.2), ((7, 10), 0.5))
    assert cg.perennial(grass)


def test_weedy_stubble_sprayed_out_before_sowing_is_not_perennial():
    stubble = series(((5, 6), 0.45), ((5, 24), 0.25), ((6, 5), 0.2), ((6, 28), 0.7))
    assert not cg.perennial(stubble)


def test_a_spring_crop_is_not_perennial():
    assert not cg.perennial(GRAIN)


def test_robust_threshold_is_median_plus_three_robust_sigmas():
    assert abs(cg.robust_threshold([1.0, 2.0, 3.0, 4.0, 5.0]) - (3.0 + 3 * 1.4826 * 1.0)) < 1e-9


def test_yellow_needs_two_dates_above_that_dates_threshold_with_green_canopy():
    thresholds = {d(7, 10): 0.3, d(7, 12): 0.3, d(7, 15): 0.3, d(6, 1): 0.3}
    one = [(d(7, 10), 0.6, 0.35), (d(7, 12), 0.6, 0.25)]
    two = [(d(7, 10), 0.6, 0.35), (d(7, 12), 0.6, 0.36)]
    bare = [(d(7, 10), 0.3, 0.35), (d(7, 12), 0.3, 0.36)]  # NDVI below 0.4: not a flowering canopy
    early = [(d(6, 1), 0.6, 0.35), (d(7, 12), 0.6, 0.36)]  # 1 June is outside the flowering window
    assert cg.yellow_dates(two, thresholds) == 2
    assert cg.yellow_dates(one, thresholds) == 1
    assert cg.yellow_dates(bare, thresholds) == 0
    assert cg.yellow_dates(early, thresholds) == 1


def test_weighted_median():
    assert cg.weighted_median([(d(7, 30), 100.0), (d(8, 5), 10.0), (d(9, 20), 10.0)]) == d(7, 30)


def test_groups():
    median = d(7, 30)
    assert cg.group(crop=False, perennial=False, yellow=0, ripe=None, median=median) == 'no crop seen'
    assert cg.group(crop=True, perennial=True, yellow=3, ripe=d(9, 20), median=median) == 'perennial grass'
    assert cg.group(crop=True, perennial=False, yellow=2, ripe=d(7, 30), median=median) == 'yellow flowers'
    assert cg.group(crop=True, perennial=False, yellow=1, ripe=d(8, 20), median=median) == 'long season'
    assert cg.group(crop=True, perennial=False, yellow=1, ripe=d(8, 19), median=median) == 'short season'
    assert cg.group(crop=True, perennial=False, yellow=0, ripe=None, median=median) == 'unclear'


# Rule v2 (docs/superpowers/notes/2026-11-12-oilseed-detection.md): rapeseed bloom on a single clear date.

def test_date_stats_are_median_and_robust_sigma_of_green_canopies_only():
    day = d(7, 10)
    rows = [(day, 0.7, 0.20 + 0.01 * i, 0.30 + 0.01 * i) for i in range(10)] + [(day, 0.2, 0.9, 0.9)]  # bare: left out
    stats = cg.date_stats({'a%d' % i: [r] for i, r in enumerate(rows)})
    (ym, ys), (nm, ns) = stats[day]
    assert abs(ym - 0.245) < 1e-9 and abs(nm - 0.345) < 1e-9
    assert abs(ys - 1.4826 * 0.025) < 1e-9 and abs(ns - 1.4826 * 0.025) < 1e-9


def test_date_stats_need_ten_green_fields():
    day = d(7, 10)
    stats = cg.date_stats({'a%d' % i: [(day, 0.7, 0.2, 0.3)] for i in range(9)})
    assert day not in stats


STATS = {d(7, 10): ((0.25, 0.04), (0.30, 0.05)), d(6, 20): ((0.25, 0.04), (0.30, 0.05))}


def test_bloom_is_yellow_and_dense_on_one_date_and_green_two_weeks_later():
    rape = [(d(7, 10), 0.75, 0.34, 0.41), (d(7, 25), 0.80, 0.30, 0.40)]  # NDYI z 2.25, NIR z 2.2; green after
    assert cg.bloom_dates(rape, STATS) == 1


def test_yellowing_grain_that_ripens_within_two_weeks_is_not_bloom():
    grain = [(d(7, 10), 0.75, 0.34, 0.41), (d(7, 18), 0.45, 0.30, 0.30), (d(7, 25), 0.30, 0.25, 0.25)]
    assert cg.bloom_dates(grain, STATS) == 0


def test_yellow_but_thin_canopy_is_not_bloom():
    thin = [(d(7, 10), 0.75, 0.34, 0.38), (d(7, 25), 0.80, 0.30, 0.40)]  # NIR z 1.6 < 2
    assert cg.bloom_dates(thin, STATS) == 0


def test_bloom_needs_a_green_canopy_on_the_date_and_the_july_window():
    sparse = [(d(7, 10), 0.45, 0.34, 0.41), (d(7, 25), 0.80, 0.30, 0.40)]  # NDVI 0.45 < 0.5
    early = [(d(6, 20), 0.75, 0.34, 0.41), (d(7, 25), 0.80, 0.30, 0.40)]  # 20 June: before the window
    assert cg.bloom_dates(sparse, STATS) == 0
    assert cg.bloom_dates(early, STATS) == 0


def test_v2_groups_a_single_bloom_date_as_yellow_flowers():
    median = d(7, 30)
    assert cg.group(crop=True, perennial=False, yellow=0, ripe=d(7, 30), median=median, bloom=1) == 'yellow flowers'
    assert cg.group(crop=True, perennial=True, yellow=0, ripe=d(7, 30), median=median, bloom=1) == 'perennial grass'
    assert cg.group(crop=False, perennial=False, yellow=0, ripe=None, median=median, bloom=1) == 'no crop seen'
    assert cg.group(crop=True, perennial=False, yellow=0, ripe=d(7, 30), median=median, bloom=0) == 'short season'


def test_yellow_dates_accept_points_that_carry_nir():
    thresholds = {d(7, 10): 0.3, d(7, 12): 0.3}
    two = [(d(7, 10), 0.6, 0.35, 0.4), (d(7, 12), 0.6, 0.36, 0.4)]
    assert cg.yellow_dates(two, thresholds) == 2


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
