"""Tests for piece_joins.py. Run: python data/tayynsha/tools/test_piece_joins.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import piece_joins as PJ  # noqa: E402


def box(x0, y0, x1, y1):
    return [x0, y0, x1, y0, x1, y1, x0, y1, x0, y0]


def field(p, ha, c='grain', m='', o=None, k=('E1', 'E1-F1'), **more):
    return dict(p=p, ha=ha, c=c, s='dated' if m else 'no cut found', f=m, t=m, m=m, h=o, k='company' if o else None,
                r=1, _o=o, _k=k, **more)


NO_LINES = lambda sq, x, y: 0                                   # noqa: E731
LINE_AT_X1 = lambda sq, x, y: 20 if abs(x - 1.0) < 0.015 else 0  # noqa: E731


def test_two_halves_of_one_field_with_no_line_between_become_one_field():
    a = field(box(0, 0, 1, 2), 200, k=('E1', 'E1-F1'))
    b = field(box(1, 0, 2, 2), 200, k=('E1', 'E1-F2'))
    out, merged = PJ.join([a, b], NO_LINES)
    assert len(out) == 1 and out[0]['ha'] == 400
    assert abs(PJ.poly(out[0]['p']).area - 4.0) < 0.05
    assert merged == {('E1', 'E1-F2'): ('E1', 'E1-F1')} or merged == {('E1', 'E1-F1'): ('E1', 'E1-F2')}


def test_a_seen_line_between_them_keeps_two_fields():
    a = field(box(0, 0, 1, 2), 200)
    b = field(box(1, 0, 2, 2), 200, k=('E1', 'E1-F2'))
    out, merged = PJ.join([a, b], LINE_AT_X1)
    assert len(out) == 2 and not merged


def test_other_crop_other_holder_or_cut_dates_apart_keep_two_fields():
    pairs = [
        (field(box(0, 0, 1, 2), 200), field(box(1, 0, 2, 2), 200, c='oilseed', k=('E1', 'E1-F2'))),
        (field(box(0, 0, 1, 2), 200, o='TOO A'), field(box(1, 0, 2, 2), 200, o='TOO B', k=('E1', 'E1-F2'))),
        (field(box(0, 0, 1, 2), 200, m='2024-09-01'), field(box(1, 0, 2, 2), 200, m='2024-09-10', k=('E1', 'E1-F2'))),
    ]
    for a, b in pairs:
        out, _ = PJ.join([a, b], NO_LINES)
        assert len(out) == 2


def test_cut_dates_within_5_days_one_date_missing_or_one_holder_known_still_join():
    pairs = [
        (field(box(0, 0, 1, 2), 200, m='2024-09-01'), field(box(1, 0, 2, 2), 200, m='2024-09-06', k=('E1', 'E1-F2'))),
        (field(box(0, 0, 1, 2), 200, m='2024-09-01'), field(box(1, 0, 2, 2), 200, k=('E1', 'E1-F2'))),
        (field(box(0, 0, 1, 2), 200, o='TOO A'), field(box(1, 0, 2, 2), 200, k=('E1', 'E1-F2'))),
    ]
    for a, b in pairs:
        out, _ = PJ.join([a, b], NO_LINES)
        assert len(out) == 1


def test_fields_more_than_30_m_apart_or_touching_at_a_corner_stay_apart():
    out, _ = PJ.join([field(box(0, 0, 1, 2), 200), field(box(1.05, 0, 2, 2), 190, k=('E1', 'E1-F2'))], NO_LINES)
    assert len(out) == 2
    out, _ = PJ.join([field(box(0, 0, 1, 1), 100), field(box(1, 1, 2, 2), 100, k=('E1', 'E1-F2'))], NO_LINES)
    assert len(out) == 2


def test_a_small_gap_up_to_30_m_is_closed_in_the_joined_outline():
    out, _ = PJ.join([field(box(0, 0, 1, 2), 200), field(box(1.02, 0, 2, 2), 196, k=('E1', 'E1-F2'))], NO_LINES)
    assert len(out) == 1 and PJ.poly(out[0]['p']).geom_type == 'Polygon'


def test_a_chain_of_three_pieces_becomes_one_field():
    fs = [field(box(i, 0, i + 1, 2), 200, k=('E1', f'E1-F{i}')) for i in range(3)]
    out, merged = PJ.join(fs, NO_LINES)
    assert len(out) == 1 and out[0]['ha'] == 600 and len(merged) == 2


def test_the_joined_field_keeps_the_largest_pieces_facts_and_the_known_holder():
    a = field(box(0, 0, 1, 2), 100, m='2024-09-02', k=('E1', 'E1-F1'))
    b = field(box(1, 0, 3, 2), 300, m='2024-09-04', k=('E2', 'E2-F7'))
    a.update(h='TOO «A»', k='company', _o='TOO A')
    out, merged = PJ.join([a, b], NO_LINES)
    f = out[0]
    assert f['_k'] == ('E2', 'E2-F7') and f['m'] == '2024-09-04' and f['ha'] == 400
    assert f['h'] == 'TOO «A»' and f['k'] == 'company' and f['_o'] == 'TOO A'
    assert merged == {('E1', 'E1-F1'): ('E2', 'E2-F7')}


def test_split_pieces_of_one_region_join_back_by_their_piece_numbers():
    a = field(box(0, 0, 1, 2), 200, k=('E1', 'E1-F1'), _piece=1)
    b = field(box(1, 0, 2, 2), 200, k=('E1', 'E1-F1'), _piece=2)
    out, merged = PJ.join([a, b], NO_LINES)
    assert len(out) == 1 and out[0]['ha'] == 400 and not merged   # the region itself is still shown


def test_seen_share_reads_the_count_every_10_m_and_skips_places_without_a_count():
    from shapely.geometry import LineString
    line = LineString([(0, 0), (1, 0)])                    # 1 km: 101 samples
    half = lambda sq, x, y: 20 if x < 0.5 else 0           # noqa: E731
    assert abs(PJ.seen_share(line, 'E1', half) - 0.5) < 0.02
    none_east = lambda sq, x, y: None if x > 0.5 else 0    # noqa: E731
    assert PJ.seen_share(line, 'E1', none_east) == 0.0
    assert PJ.seen_share(line, 'E1', lambda sq, x, y: None) is None


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
