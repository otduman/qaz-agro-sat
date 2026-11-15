"""Tests for relative.py. Run: python data/tayynsha/tools/test_relative.py"""
import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relative  # noqa: E402

DATES = [dt.date(2026, 8, d) for d in (5, 9, 14, 19, 24, 29)] + [dt.date(2026, 9, d) for d in (3, 8, 13, 18)]
RAIN = dt.date(2026, 9, 1)


def reading(day, ndvi, b11, clear=1.0):
    return (day, 'S2', clear, ndvi, b11, 0.05, 0.0)


def standing(n):
    """n ripe fields never cut: B11 0.25, darkened to 0.20 by rain from 1 September; green in July."""
    rows = {}
    for i in range(n):
        rs = [reading(dt.date(2026, 7, 10), 0.8, 0.18)]
        rs += [reading(d, 0.3, 0.25 if d < RAIN else 0.20) for d in DATES]
        rows[f'S{i:02d}'] = rs
    return rows


def test_reference_is_the_median_of_ripe_cropped_fields_per_date():
    rows = standing(25)
    ref = relative.reference(rows, set(rows))
    assert abs(ref[DATES[0]] - 0.25) < 1e-9 and abs(ref[DATES[-1]] - 0.20) < 1e-9, ref
    assert dt.date(2026, 7, 10) not in ref  # green fields are not ripe


def test_a_date_with_too_few_ripe_fields_has_no_reference():
    rows = standing(19)
    assert relative.reference(rows, set(rows)) == {}


def test_fields_without_a_crop_do_not_count_in_the_reference():
    rows = standing(25)
    assert relative.reference(rows, set(list(rows)[:10])) == {}


def test_crop_gate():
    rows = standing(2)
    rows['bare'] = [reading(d, 0.2, 0.3) for d in DATES]
    assert relative.cropped(rows) == {'S00', 'S01'}


def test_a_cut_hidden_by_rain_is_found_against_the_same_days_fields():
    rows = standing(25)
    # Cut on 2 September, in the rain: +5 points against standing crop, while rain takes 5 points off everything.
    rows['T'] = [reading(dt.date(2026, 7, 10), 0.8, 0.18)] + [reading(d, 0.3, 0.25) for d in DATES]
    out = relative.date_fields(rows, set(rows))
    v1 = out['T']
    assert v1[0] == 'dated' and v1[4] == 'v2', v1
    # On a perfectly clean step, three splits tie under v1's medians of three and the earliest wins, so the
    # window comes out one photo early (29 August to 3 September is the true one). Same as v1.
    assert v1[1] == dt.date(2026, 8, 24) and v1[2] == dt.date(2026, 8, 29), v1


def test_standing_fields_stay_uncut():
    rows = standing(25)
    out = relative.date_fields(rows, set(rows))
    assert all(v[0] == 'no cut found' for v in out.values()), out


def test_a_cut_v1_dates_keeps_its_v1_date():
    rows = standing(25)
    rows['C'] = [reading(dt.date(2026, 7, 10), 0.8, 0.18)] + [
        reading(d, 0.3, 0.25 if d < dt.date(2026, 8, 20) else 0.32) for d in DATES]
    out = relative.date_fields(rows, set(rows))
    assert out['C'][0] == 'dated' and out['C'][4] == 'v1', out['C']
    assert out['C'][1] == dt.date(2026, 8, 14), out['C']  # one photo early on a clean step, as above


def test_no_crop_seen_is_not_dated():
    rows = standing(25)
    rows['B'] = [reading(d, 0.2, 0.25 if d < dt.date(2026, 8, 20) else 0.35) for d in DATES]
    out = relative.date_fields(rows, set(rows) - {'B'})
    assert out['B'][0] == 'no crop seen', out['B']


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
