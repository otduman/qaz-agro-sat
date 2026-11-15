"""Tests for land_rule.py. Run: python data/tayynsha/tools/test_land_rule.py"""
import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import land_rule as lr  # noqa: E402


def d(m, day):
    return dt.date(2026, m, day)


def pts(*ndvi, ndmi=-0.25, water=0.0, swir=(0.30, 0.22), late_swir=None):
    """Points (date, ndvi, ndmi, water share, swir16, swir22) from ((month, day), ndvi) pairs."""
    out = []
    for (m, day), v in ndvi:
        s = late_swir if late_swir and (8, 1) <= (m, day) <= (9, 15) else swir
        out.append(lr.Point(d(m, day), v, ndmi, water, s[0], s[1]))
    return out


# spring wheat: bare in May, green in July, ripe and cut in August
WHEAT = (((5, 10), 0.18), ((5, 25), 0.20), ((6, 10), 0.35), ((6, 25), 0.65), ((7, 10), 0.80), ((7, 25), 0.60),
         ((8, 10), 0.30), ((8, 20), 0.22), ((9, 1), 0.20), ((9, 10), 0.20))


def test_a_spring_crop_is_a_field():
    assert lr.label(pts(*WHEAT), straight=0.9) == ('field', 'crop curve')


def test_an_early_crop_peaking_in_early_june_is_a_field_now():
    early = (((5, 10), 0.18), ((5, 25), 0.30), ((6, 5), 0.62), ((6, 12), 0.70), ((6, 25), 0.55), ((7, 10), 0.35),
             ((7, 25), 0.22), ((8, 10), 0.20), ((8, 20), 0.20))
    assert lr.label(pts(*early), straight=0.9)[0] == 'field'


def test_a_peak_in_may_is_not_a_crop():
    weedy = (((5, 10), 0.25), ((5, 25), 0.55), ((6, 5), 0.30), ((6, 25), 0.25), ((7, 10), 0.24), ((8, 10), 0.22),
             ((8, 20), 0.22), ((9, 1), 0.2))
    assert lr.label(pts(*weedy), straight=0.9)[0] != 'field'


def test_a_thin_crop_peaking_at_045_is_a_field_now_but_not_at_035():
    thin = tuple(((m, day), v * 0.45 / 0.8 if v > 0.3 else v) for (m, day), v in WHEAT)
    assert lr.label(pts(*thin), straight=0.9)[0] == 'field'
    thinner = tuple(((m, day), min(v, 0.35)) for (m, day), v in WHEAT)
    assert lr.label(pts(*thinner), straight=0.9)[0] != 'field'


def test_a_crop_curve_on_wet_ground_with_a_crooked_outline_is_other():
    assert lr.label(pts(*WHEAT, ndmi=0.0), straight=0.1) == ('other', 'wet or green in spring, crooked outline')


def test_a_crop_curve_on_wet_ground_with_straight_sides_stays_a_field():
    assert lr.label(pts(*WHEAT, ndmi=0.0), straight=0.6)[0] == 'field'


def test_a_crooked_field_that_was_dry_and_bare_in_spring_stays_a_field():
    assert lr.label(pts(*WHEAT, ndmi=-0.25), straight=0.1)[0] == 'field'


def test_open_water_on_a_spring_date_counts_as_wet():
    p = pts(*WHEAT)
    p[1] = p[1]._replace(water=0.4)
    assert lr.label(p, straight=0.1)[0] == 'other'


def test_wetness_after_mid_june_does_not_count():
    p = pts(*WHEAT)
    p = [x._replace(ndmi=0.1, water=0.5) if x.date > d(6, 15) else x for x in p]
    assert lr.label(p, straight=0.1)[0] == 'field'


def test_a_crop_curve_whose_ground_is_worked_bare_in_late_summer_is_fallow():
    # weeds to 0.7 in late June, then ploughed: bare soil has a low tillage index (SWIR 1.6 close to SWIR 2.2)
    assert lr.label(pts(*WHEAT, late_swir=(0.25, 0.24)), straight=0.9) == ('fallow', 'crop curve, then worked bare')


def test_stubble_after_harvest_keeps_a_field():
    assert lr.label(pts(*WHEAT, late_swir=(0.32, 0.24)), straight=0.9)[0] == 'field'


def test_worked_bare_needs_three_bare_dates_in_the_window():
    short = tuple(x for x in WHEAT if x[0] not in ((8, 20), (9, 1)))
    assert lr.label(pts(*short, late_swir=(0.25, 0.24)), straight=0.9)[0] == 'field'


def test_bare_most_of_the_season_is_fallow_unless_wet_and_crooked():
    bare = tuple(((m, day), 0.2) for (m, day), _ in WHEAT)
    assert lr.label(pts(*bare), straight=0.9) == ('fallow', 'bare most of the season')
    assert lr.label(pts(*bare, ndmi=0.0), straight=0.1)[0] == 'other'


def test_water_and_too_few_dates_are_other():
    water = tuple(((m, day), -0.3) for (m, day), _ in WHEAT)
    assert lr.label(pts(*water), straight=0.0) == ('other', 'water')
    assert lr.label(pts(*WHEAT[:4]), straight=0.9) == ('other', 'too few clear dates')


def test_green_in_spring_is_other():
    grass = tuple(((m, day), max(v, 0.4)) for (m, day), v in WHEAT)
    assert lr.label(pts(*grass), straight=0.9)[0] == 'other'


def test_curve_gap_is_the_third_largest_gap_on_common_dates():
    a = {d(6, 1): 0.1, d(6, 2): 0.2, d(6, 3): 0.3, d(6, 4): 0.4, d(6, 5): 0.5, d(6, 6): 0.6}
    b = {d(6, 1): 0.1, d(6, 2): 0.3, d(6, 3): 0.6, d(6, 4): 0.8, d(6, 5): 0.55, d(6, 7): 0.9}
    assert abs(lr.curve_gap(a, b) - 0.1) < 1e-9  # gaps 0, 0.1, 0.3, 0.4, 0.05 -> third largest 0.1
    assert lr.curve_gap(a, {d(6, 1): 0.1}) is None  # fewer than five common dates


def test_an_island_joins_the_field_around_it_when_their_curves_agree():
    assert lr.joins(enclosure=0.9, parent_label='field', parent_ha=300, own_ha=8, gap=0.15)
    assert not lr.joins(enclosure=0.9, parent_label='field', parent_ha=300, own_ha=8, gap=0.3)  # a tree clump
    assert not lr.joins(enclosure=0.6, parent_label='field', parent_ha=300, own_ha=8, gap=0.1)  # not enclosed
    assert not lr.joins(enclosure=0.9, parent_label='other', parent_ha=300, own_ha=8, gap=0.1)  # inside steppe
    assert not lr.joins(enclosure=0.9, parent_label='field', parent_ha=8, own_ha=30, gap=0.1)  # larger than parent
    assert not lr.joins(enclosure=0.9, parent_label='field', parent_ha=300, own_ha=8, gap=None)


def test_a_small_crooked_field_on_its_own_outside_the_fields_is_not_a_field():
    assert lr.lonely('field', area_ha=12, straight=0.2, neighbour='other')
    assert not lr.lonely('field', area_ha=12, straight=0.2, neighbour='field')    # a piece among fields
    assert not lr.lonely('field', area_ha=12, straight=0.2, neighbour='fallow')
    assert not lr.lonely('field', area_ha=12, straight=0.8, neighbour='other')    # a small straight plot
    assert not lr.lonely('field', area_ha=45, straight=0.2, neighbour='other')    # big enough
    assert not lr.lonely('fallow', area_ha=12, straight=0.2, neighbour='other')


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
