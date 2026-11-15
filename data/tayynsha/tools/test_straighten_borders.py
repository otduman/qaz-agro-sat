"""Tests for straighten_borders.py. Run: python data/tayynsha/tools/test_straighten_borders.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import straighten_borders as sb  # noqa: E402

FIELDS = {1: 'field', 2: 'field'}


def border_columns(lab, rows, left=1):
    """For each row, the first column that is no longer region `left` (scanning from the left edge)."""
    return np.array([int(np.argmax(lab[r] != left)) for r in rows])


def two_fields(edge):
    """200 x 160 map: region 1 left of edge[row], region 2 from edge[row] on."""
    lab = np.full((200, 160), 2, np.int64)
    for r, c in enumerate(edge):
        lab[r, :c] = 1
    return lab


def test_a_jagged_straight_border_between_two_fields_becomes_straight():
    rng = np.random.default_rng(1)
    edge = 80 + rng.integers(-2, 3, 200)              # a straight border drawn with +-2 pixel noise
    lab = two_fields(edge)
    out = sb.straighten(lab, FIELDS)
    cols = border_columns(out, range(20, 180))
    assert np.ptp(cols) <= 1, np.unique(cols)           # one straight line
    assert abs(int((out == 1).sum()) - int((lab == 1).sum())) < 0.01 * (lab == 1).sum()


def test_an_already_straight_border_is_left_as_it_is():
    lab = two_fields(np.full(200, 80))
    assert np.array_equal(sb.straighten(lab, FIELDS), lab)


def test_a_curved_border_is_left_alone():
    rows = np.arange(200)
    edge = (80 + 15 * (1 - ((rows - 100) / 100.0) ** 2)).astype(int)   # an arc bulging 15 pixels
    lab = two_fields(edge)
    assert np.array_equal(sb.straighten(lab, FIELDS), lab)


def test_a_zigzag_between_two_fields_is_cut_through_its_middle():
    rows = np.arange(200)
    edge = 80 + np.where((rows // 6) % 2 == 0, 5, -5)    # teeth 5 pixels each side, 60 m wide
    lab = two_fields(edge)
    out = sb.straighten(lab, FIELDS)
    cols = border_columns(out, range(30, 170))
    assert np.ptp(cols) <= 1 and abs(cols.mean() - 80) <= 1.5, np.unique(cols)


def test_a_zigzag_against_land_that_is_not_a_field_is_left_alone():
    rows = np.arange(200)
    lab = two_fields(80 + np.where((rows // 6) % 2 == 0, 5, -5))
    out = sb.straighten(lab, {1: 'field', 2: 'other'})
    assert np.array_equal(out, lab)                      # the coarse pass is for field against field only


def test_the_edge_of_a_small_patch_that_is_not_a_field_is_left_alone():
    rng = np.random.default_rng(2)
    lab = np.ones((200, 160), np.int64)
    edge = 120 + rng.integers(-2, 3, 60)
    for i, c in enumerate(edge):
        lab[70 + i, c:] = 3                               # a small marsh (under 50 ha) with a ragged straight side
    out = sb.straighten(lab, {1: 'field', 3: 'other'})
    assert np.array_equal(out, lab)


def test_a_third_region_inside_the_band_is_never_touched():
    rng = np.random.default_rng(3)
    lab = two_fields(80 + rng.integers(-2, 3, 200))
    lab[95:105, 76:85] = 3                                # a small region sitting on the border
    out = sb.straighten(lab, {1: 'field', 2: 'field', 3: 'other'})
    assert np.array_equal(out == 3, lab == 3)


def test_bits_cut_off_from_their_field_go_to_the_field_around_them():
    before = np.full((40, 40), 2, np.int64)
    before[:, :20] = 1
    after = before.copy()
    after[10:13, 25:28] = 1                               # a bit of field 1 left inside field 2
    out = sb.tidy(after)
    assert (out[10:13, 25:28] == 2).all() and int((out == 1).sum()) == 40 * 20


def test_a_large_detached_piece_is_kept():
    lab = np.full((60, 60), 2, np.int64)
    lab[:, :10] = 1
    lab[20:40, 30:50] = 1                                 # 400 pixels: over the 100-pixel limit, stays
    assert np.array_equal(sb.tidy(lab), lab)


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(f'{len(tests)} tests passed')
