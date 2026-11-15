"""Tests for market.py. Run: python data/tayynsha/tools/test_market.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import market  # noqa: E402


def test_hectares_per_combine_is_the_district_load():
    # Tayynsha 2026: about 400,000 ha grain and pulses plus 130,000 ha oilseeds, 756 combines at work.
    assert market.HA_PER_COMBINE == round(530_000 / 756)


def test_summary_per_holder():
    rows = [('A', 'company', 'short season', 700.0), ('A', 'company', 'long season', 701.0),
            ('A', 'company', 'perennial grass', 50.0), ('A', 'company', 'no crop seen', 20.0),
            ('B', 'farm', 'yellow flowers', 350.5)]
    out = {r['holder']: r for r in market.summarise(rows)}
    a = out['A']
    assert a['field_ha'] == 1471 and a['grain_ha'] == 700 and a['oilseed_ha'] == 701 and a['other_ha'] == 70, a
    assert abs(a['combines'] - 2.0) < 0.01, a  # (700 + 701) / 701
    assert abs(a['buys_per_year_low'] - 2.0 * 0.065) < 0.01 and abs(a['buys_per_year_high'] - 2.0 * 0.08) < 0.01, a
    assert out['B']['oilseed_ha'] == 350 and abs(out['B']['combines'] - 0.5) < 0.01, out['B']


def test_largest_first():
    rows = [('small', 'farm', 'short season', 100.0), ('big', 'company', 'short season', 5000.0)]
    assert [r['holder'] for r in market.summarise(rows)] == ['big', 'small']


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
