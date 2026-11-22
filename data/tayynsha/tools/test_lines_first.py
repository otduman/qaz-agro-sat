"""Tests for lines_first.py. Run: python data/tayynsha/tools/test_lines_first.py"""
import os
import shutil
import sys
import tempfile

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lines_first as LF  # noqa: E402


def ridge(shape, segs, value=40, width=3):
    """A line-count image with bright ridges along the given segments."""
    img = np.zeros(shape, np.float32)
    for x0, y0, x1, y1 in segs:
        cv2.line(img, (x0, y0), (x1, y1), value, width)
    return img


def test_pieces_of_one_line_join_but_parallel_lines_do_not():
    out = LF.merge([(10, 50, 60, 50), (65, 50.8, 120, 51), (10, 56, 120, 56)])
    assert len(out) == 2
    long = max(out, key=lambda s: abs(s[2] - s[0]))
    assert abs(min(long[0], long[2]) - 10) < 1 and abs(max(long[0], long[2]) - 120) < 1


def test_a_long_well_seen_line_is_kept_and_a_short_or_weak_one_is_not():
    img = ridge((100, 200), [(10, 50, 190, 50)])
    assert LF.keep_stretch(img, (10, 50, 190, 50)) is not None
    assert LF.keep_stretch(img, (10, 50, 30, 50)) is None                  # 200 m: too short
    weak = ridge((100, 200), [(10, 50, 190, 50)], value=LF.STRONG - 1)
    assert LF.keep_stretch(weak, (10, 50, 190, 50)) is None


def test_a_line_grows_through_a_junction_and_stops_where_its_border_ends():
    img = ridge((200, 200), [(100, 10, 100, 150), (10, 80, 190, 80)])   # a T: the cross line passes y = 80
    x0, y0, x1, y1 = LF.grow(img, (100, 100, 100, 130))
    assert min(y0, y1) <= 12 and max(y0, y1) >= 148 and max(y0, y1) <= 150 + LF.HOLE + 1


def test_a_line_is_carried_on_to_the_next_line_or_to_the_edge_of_the_land():
    land = np.zeros((100, 200), bool)
    land[10:90, 10:190] = True
    lines = [(100, 30, 100, 80), (10, 20, 190, 20)]                     # stops 10 px short of the top line
    out = LF.extend(lines, land.shape, land)
    assert min(out[0][1], out[0][3]) <= 21
    out = LF.extend([(100, 20, 100, 82)], land.shape, land)            # 8 px short of the land's edge
    assert max(out[0][1], out[0][3]) >= 89


def test_a_line_divides_the_land_without_leaks_and_thin_strips_and_crumbs_go_to_neighbours():
    land = np.zeros((120, 200), bool)
    land[10:110, 10:190] = True
    lab = LF.partition(land, [(100, 10, 100, 109)])
    assert lab[60, 50] != lab[60, 150] and lab[60, 50] > 0 and lab[60, 150] > 0
    assert (lab > 0).sum() == land.sum()                                # line pixels kept as land
    lab = LF.partition(land, [(100, 10, 100, 109), (103, 10, 103, 109)])  # both sides of a 30 m road
    assert len(np.unique(lab[lab > 0])) == 2
    lab = LF.partition(land, [(100, 10, 100, 109), (10, 104, 100, 104)])  # a 5 x 90 px strip: under 5 ha
    assert len(np.unique(lab[lab > 0])) == 2


def two_fields():
    lab = np.zeros((120, 240), np.int64)
    lab[10:110, 10:120] = 1            # 110 ha
    lab[10:110, 120:230] = 2           # 110 ha
    return lab


def test_a_border_nobody_sees_is_removed_but_a_seen_one_or_one_between_two_holders_stays():
    lab = two_fields()
    quiet = np.zeros(lab.shape, np.float32)
    out, joins = LF.join_unseen(lab, quiet, {})
    assert len(np.unique(out[out > 0])) == 1 and len(joins) == 1
    seen = ridge(lab.shape, [(120, 10, 120, 109)])
    out, joins = LF.join_unseen(lab, seen, {})
    assert len(np.unique(out[out > 0])) == 2 and joins == []
    out, joins = LF.join_unseen(lab, quiet, {1: {'A'}, 2: {'B'}})
    assert len(np.unique(out[out > 0])) == 2


def test_a_field_is_split_along_a_line_crossing_it_when_both_parts_reach_the_site_minimum():
    lab = np.zeros((120, 240), np.int64)
    lab[10:110, 10:230] = 1            # 220 ha
    out, splits = LF.split_by_lines(lab, [(120, 5, 120, 115)])
    assert len(np.unique(out[out > 0])) == 2 and len(splits) == 1
    assert (out > 0).sum() == (lab > 0).sum()
    out, splits = LF.split_by_lines(lab, [(40, 5, 40, 115)])           # leaves a 30 x 100 px (30 ha) part
    assert len(np.unique(out[out > 0])) == 1 and splits == []
    out, splits = LF.split_by_lines(lab, [(120, 5, 120, 70)])          # stops inside the field
    assert len(np.unique(out[out > 0])) == 1 and splits == []


def seasons_tree(tree):
    """A district folder with the given square -> season folders."""
    d = tempfile.mkdtemp()
    for sq, years in tree.items():
        for y in years:
            os.makedirs(f'{d}/{sq}/season-{y}')
    return d


def test_the_line_count_reads_the_season_before_or_else_the_season_after():
    d = seasons_tree({'A': (2024, 2025, 2026), 'B': (2025, 2026), 'C': (2025,)})
    try:
        assert LF.count_seasons(d, 'A', 2026) == (2025, 2026)
        assert LF.count_seasons(d, 'A', 2025) == (2024, 2025)
        assert LF.count_seasons(d, 'A', 2024) == (2024, 2025)
        assert LF.count_seasons(d, 'B', 2025) == (2025, 2026)
        assert LF.count_seasons(d, 'C', 2025) is None
        assert LF.save_count(d, 'C', 2025) == 'no seasons' and not os.path.exists(LF.count_path(d, 'C', 2025))
    finally:
        shutil.rmtree(d)


def test_a_count_over_the_same_two_seasons_is_copied_not_recounted():
    d = seasons_tree({'B': (2025, 2026)})
    try:
        c = np.arange(12, dtype=np.uint8).reshape(3, 4)
        os.makedirs(os.path.dirname(LF.count_path(d, 'B', 2026)))
        np.savez_compressed(LF.count_path(d, 'B', 2026), count=c)
        assert LF.save_count(d, 'B', 2025) == 'copied from 2026'
        assert (LF.load_count(d, 'B', 2025) == c).all()
        assert LF.save_count(d, 'B', 2025) == 'exists'
    finally:
        shutil.rmtree(d)


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
