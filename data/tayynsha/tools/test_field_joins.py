"""Tests for field_joins.py. Run: python data/tayynsha/tools/test_field_joins.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import field_joins as fj  # noqa: E402


def pair(**kw):
    p = dict(ha_a=200.0, ha_b=150.0, touch_share=0.4, fill_a=0.8, fill_b=0.8, fill_join=0.9, gap=0.1,
             border_straight=0.5, one_field_other_year=False, cut_days=None, holder_a='', holder_b='')
    p.update(kw)
    return p


def test_puzzle_pieces_join_when_together_more_regular_curves_agree_and_the_border_is_crooked():
    assert fj.decide(pair()) == 'puzzle'
    assert fj.decide(pair(fill_join=0.75)) is None                      # less regular together
    assert fj.decide(pair(gap=0.2)) is None                              # curves differ
    assert fj.decide(pair(gap=None)) is None                             # too few common dates
    assert fj.decide(pair(touch_share=0.1)) is None                      # barely touch


def test_a_straight_border_needs_another_year_or_the_same_cut_date():
    straight = dict(border_straight=0.9)
    assert fj.decide(pair(**straight)) is None
    assert fj.decide(pair(one_field_other_year=True, **straight)) == 'puzzle'
    assert fj.decide(pair(cut_days=2, **straight)) == 'puzzle'
    assert fj.decide(pair(cut_days=5, **straight)) is None


def test_a_small_piece_joins_a_field_five_times_its_size():
    small = dict(ha_a=300.0, ha_b=10.0, touch_share=0.35, fill_join=0.5, gap=None)
    assert fj.decide(pair(**small)) == 'small piece'
    assert fj.decide(pair(**dict(small, touch_share=0.2))) is None
    assert fj.decide(pair(**dict(small, ha_a=40.0))) is None             # neighbour not 5x bigger
    assert fj.decide(pair(**dict(small, border_straight=0.95))) is None  # a strip along a straight border


def test_never_across_two_known_holders():
    assert fj.decide(pair(holder_a='A', holder_b='B')) is None
    assert fj.decide(pair(holder_a='A', holder_b='A')) == 'puzzle'
    assert fj.decide(pair(holder_a='A')) == 'puzzle'


def test_groups_chain_joins():
    assert fj.groups([('a', 'b'), ('b', 'c'), ('d', 'e')]) == [['a', 'b', 'c'], ['d', 'e']]


def test_the_outline_of_joined_pieces_goes_round_both():
    lab = np.zeros((12, 20), np.int64)
    lab[2:10, 2:9] = 1
    lab[2:10, 9:17] = 2
    lab[0:2, :] = 3
    ring = fj.outline_px(lab, [1, 2])
    xs, ys = [p[0] for p in ring], [p[1] for p in ring]
    assert (min(xs), max(xs), min(ys), max(ys)) == (2, 16, 2, 9)


def test_pixel_centres_become_utm_metres():
    assert fj.to_utm([(0, 0), (3, 2)], 560160.0, 5979940.0) == [(560165.0, 5979935.0), (560195.0, 5979915.0)]


def test_the_projection_comes_from_the_raster_itself():
    # GeoKeyDirectory: header, then (key, location, count, value); 3072 = projected CRS
    keys = [1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 32641]
    assert fj.geokey_epsg(keys) == 32641
    assert fj.central_meridian(32641) == 63 and fj.central_meridian(32642) == 69


def test_neighbours_that_behave_as_one_field_join_across_a_straight_border():
    # v3, the owner's call: fewer, whole fields; neighbouring strips of one crop cut together may be joined too
    same = dict(border_straight=0.95, gap=0.08, cut_days=2, fill_join=0.85, fill_a=0.88, fill_b=0.9)
    assert fj.decide(pair(**same)) == 'same field'
    assert fj.decide(pair(**dict(same, gap=0.12))) is None                # curves only roughly alike
    assert fj.decide(pair(**dict(same, cut_days=6))) is None              # cut a week apart
    assert fj.decide(pair(**dict(same, cut_days=None, gap=0.09))) is None  # no cut date, curves only close
    assert fj.decide(pair(**dict(same, cut_days=None, gap=0.07))) == 'same field'   # no cut date, curves very close
    assert fj.decide(pair(**dict(same, fill_join=0.7))) is None           # an L or a T together, not one shape
    assert fj.decide(pair(**dict(same, holder_a='A', holder_b='B'))) is None


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
