"""Tests for harvest_v2.py. Run: python data/tayynsha/tools/test_harvest_v2.py"""
import os
import sys
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import harvest_v2 as hv  # noqa: E402

DATES = [(date(2026, 7, 20) + timedelta(days=3 * i)).isoformat() for i in range(30)]   # 20 Jul .. 15 Oct, every 3 d
BASE = dict(b11=0.22, b12=0.18, ndti=0.10, brightness=0.07, ndvi_median=0.30, line_strength=0.25)


def rows(change=None, at='2026-08-25', ndvi=0.30, skip=(), sat='S2'):
    """A ripe field's S2 series; from date `at` on, `change` (feature -> amount) is added."""
    out = []
    for i, dt in enumerate(DATES):
        if dt in skip:
            continue
        v = dict(BASE, ndvi_median=ndvi)
        v['b11'] += 0.002 * (i % 2)                     # a little noise
        if change and dt >= at:
            for f, a in change.items():
                v[f] += a
        out.append(dict(field='F', date=dt, clear_share='1.0', snow_share='0.0', satellite=sat,
                        **{f: str(x) for f, x in v.items()}))
    return out


def test_a_swir_rise_is_dated_in_its_gap():
    r = hv.date_cut(rows({'b11': 0.06, 'b12': 0.04}), 2026, '2026-08-01')
    assert r['status'] == 'dated'
    assert r['cut_from'] < '2026-08-25' <= r['cut_to']


def test_a_darkening_counts_as_a_cut_too():
    r = hv.date_cut(rows({'b11': -0.05, 'brightness': -0.03, 'ndti': -0.05}), 2026, '2026-08-01')
    assert r['status'] == 'dated'
    assert r['cut_from'] < '2026-08-25' <= r['cut_to']


def test_the_first_change_wins_over_a_bigger_later_one():
    rs = rows({'b11': 0.06, 'b12': 0.04})
    for r in rs:                                       # ploughing in late September: a bigger change
        if r['date'] >= '2026-09-24':
            r['b11'] = str(float(r['b11']) - 0.12)
            r['brightness'] = str(float(r['brightness']) - 0.04)
    r = hv.date_cut(rs, 2026, '2026-08-01')
    assert r['cut_from'] < '2026-08-25' <= r['cut_to']


def test_a_still_green_field_is_not_cut():
    r = hv.date_cut(rows({'b11': 0.06, 'b12': 0.04}, ndvi=0.7), 2026, '2026-08-01')
    assert r['status'] == 'no cut found'


def test_a_flat_series_has_no_cut():
    assert hv.date_cut(rows(), 2026, '2026-08-01')['status'] == 'no cut found'


def test_a_cut_across_a_long_cloud_gap_gets_the_gap():
    skip = {d for d in DATES if '2026-08-15' < d < '2026-08-28'}
    r = hv.date_cut(rows({'b11': 0.06, 'b12': 0.04}, skip=skip), 2026, '2026-08-01')
    assert r['status'] == 'cut in a cloud gap'
    assert r['window_days'] > hv.WIDE


def test_landsat_rows_are_left_out():
    r = hv.date_cut(rows({'b11': 0.06, 'b12': 0.04}, sat='LC09'), 2026, '2026-08-01')
    assert r['status'] == 'no cut found' and r['readings'] == 0


def test_a_change_the_whole_square_makes_at_once_is_not_a_cut():
    by = {f'F{i}': [dict(r, field=f'F{i}') for r in rows({'b11': 0.06, 'b12': 0.04})] for i in range(12)}
    flat = hv.detrend(by)
    assert all(hv.date_cut(s, 2026, '2026-08-01')['status'] == 'no cut found' for s in flat.values())


def test_one_field_cut_among_still_ones_is_found_after_detrend():
    by = {f'F{i}': [dict(r, field=f'F{i}') for r in rows()] for i in range(12)}
    by['F0'] = rows({'b11': 0.06, 'b12': 0.04})
    r = hv.date_cut(hv.detrend(by)['F0'], 2026, '2026-08-01')
    assert r['status'] == 'dated' and r['cut_from'] < '2026-08-25' <= r['cut_to']


def test_rewrite_keeps_other_statuses_and_columns():
    old = [dict(field='F', land='field', area_ha='50', status='no cut found', cut_from='', cut_to='', cut_middle='',
                window_days='', rise='', readings='20'),
           dict(field='G', land='field', area_ha='5', status='no crop seen', cut_from='', cut_to='', cut_middle='',
                window_days='', rise='', readings='3')]
    new = hv.rewrite(old, {'F': rows({'b11': 0.06, 'b12': 0.04}), 'G': rows({'b11': 0.06})}, {'F': '2026-08-01'}, 2026)
    assert new[0]['status'] == 'dated' and new[0]['cut_middle']
    assert new[1] == old[1]
    assert list(new[0]) == list(old[0])


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(len(tests), 'passed')
