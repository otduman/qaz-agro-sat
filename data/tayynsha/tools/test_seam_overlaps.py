"""Tests for seam_overlaps.py. Run: python data/tayynsha/tools/test_seam_overlaps.py"""
import os
import sys

from shapely.geometry import box as sbox

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import seam_overlaps as SO  # noqa: E402


def box(x0, y0, x1, y1):
    return [x0, y0, x1, y0, x1, y1, x0, y1, x0, y0]


def field(p, ha, sq='A', fid='F1'):
    return dict(p=p, ha=ha, c='grain', _k=(sq, fid))


CORES = {'A': sbox(-20, -10, 10.0, 10), 'B': sbox(10.0, -10, 40, 10)}   # the seam at x = 10 km


def test_a_copy_inside_a_field_of_the_neighbouring_square_adds_nothing():
    big = field(box(8, 0, 12, 2), 800, 'A')
    copy = field(box(9.9, 0, 12, 2), 420, 'B', 'F9')
    out, merged = SO.resolve([big, copy], CORES)
    assert len(out) == 1 and out[0]['ha'] == 800 and abs(SO.poly(out[0]['p']).area - 8.0) < 0.01
    assert merged == {('B', 'F9'): ('A', 'F1')}


def test_two_copies_cut_at_different_edges_become_the_whole_field():
    # Esil 2024: a 1,071 ha and a 1,292 ha copy sharing 692 ha are one field of about 1,670 ha, not 1,292
    a = field(box(8, 0, 12, 2), 800, 'A')
    b = field(box(10, 0, 13, 2), 600, 'B', 'F9')
    out, _ = SO.resolve([a, b], CORES)
    assert len(out) == 1 and out[0]['ha'] == 1000 and abs(SO.poly(out[0]['p']).area - 10.0) < 0.01


def test_a_sliver_across_squares_goes_to_the_field_whose_core_holds_it():
    a = field(box(7, 0, 10.2, 2), 640, 'A')      # reaches 200 m past the seam into B's core
    b = field(box(10, 0, 13, 2), 600, 'B')       # the overlap 10-10.2 lies in B's core
    out, _ = SO.resolve([a, b], CORES)
    na, nb = out
    assert nb == b
    assert abs(SO.poly(na['p']).area - 6.0) < 0.01 and na['ha'] == 600


def test_a_sliver_inside_one_square_is_cut_from_the_bulging_drawing_only():
    a = field(box(0, 0, 2.1, 2), 400, 'A', 'F1')   # drawn 420 ha for 400: bulges into its neighbour
    b = field(box(2, 0, 4, 2), 400, 'A', 'F2')
    out, _ = SO.resolve([a, b], CORES)
    assert abs(SO.poly(out[0]['p']).area - 4.0) < 0.01 and out[0]['ha'] == 400 and out[1] == b


def test_fields_that_only_touch_or_overlap_under_a_tenth_of_a_hectare_are_left_alone():
    a = field(box(0, 0, 2, 2), 400, 'A', 'F1')
    b = field(box(2, 0, 4, 2), 400, 'B', 'F2')
    c = field(box(3.9998, 0, 6, 2), 400, 'B', 'F3')
    assert SO.resolve([a, b, c], CORES) == ([a, b, c], {})


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
