"""Tests for land_lib.py. Run: python data/tayynsha/tools/test_land_lib.py"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import land_lib as ll  # noqa: E402


def square(side, x0=0.0, y0=0.0):
    return [(x0, y0), (x0 + side, y0), (x0 + side, y0 + side), (x0, y0 + side), (x0, y0)]


def circle(radius, n=720):
    return [(radius * math.cos(2 * math.pi * k / n), radius * math.sin(2 * math.pi * k / n)) for k in range(n + 1)]


def test_area_and_perimeter_of_a_square():
    ring = square(1000)
    assert abs(abs(ll.ring_area(ring)) - 1e6) < 1e-6
    assert abs(ll.ring_length(ring) - 4000) < 1e-6


def test_compactness_is_one_for_a_circle_and_pi_over_four_for_a_square():
    assert abs(ll.shape_measures([circle(500)])['compactness'] - 1.0) < 1e-3
    assert abs(ll.shape_measures([square(800)])['compactness'] - math.pi / 4) < 1e-9


def test_a_hole_takes_area_and_adds_outline():
    m = ll.shape_measures([square(1000), square(100, 450, 450)])
    assert abs(m['area_ha'] - 99.0) < 1e-6
    assert abs(m['perimeter_m'] - 4400) < 1e-6


def test_a_rectangle_is_all_straight_and_a_circle_is_not():
    assert ll.straight_share(square(600)) > 0.99
    assert ll.straight_share(circle(400)) < 0.05


def test_a_staircase_along_a_diagonal_still_counts_as_straight():
    # a diagonal field edge drawn on a 10 m pixel grid: 10 m steps, kept within 10 m of a line
    steps = [(0.0, 0.0)]
    for k in range(60):
        x, y = steps[-1]
        steps += [(x + 10, y), (x + 10, y + 10)]
    ring = steps + [(0.0, 600.0), (0.0, 0.0)]
    assert ll.straight_share(ring) > 0.95


def test_a_wobbly_edge_is_not_straight():
    # a field-sized blob whose edge wanders 40 m every 100 m
    n = 400
    ring = [((500 + 40 * math.sin(2 * math.pi * 30 * k / n)) * math.cos(2 * math.pi * k / n),
             (500 + 40 * math.sin(2 * math.pi * 30 * k / n)) * math.sin(2 * math.pi * k / n)) for k in range(n + 1)]
    assert ll.straight_share(ring) < 0.1


def test_solidity_of_an_l_shape_is_three_quarters():
    ell = [(0, 0), (200, 0), (200, 100), (100, 100), (100, 200), (0, 200), (0, 0)]
    assert abs(ll.solidity(ell) - 0.75 / (1 - 0.125)) < 1e-9  # hull cuts the inner corner's triangle


def test_rectangle_fill_is_one_for_a_tilted_rectangle_and_pi_over_four_for_a_circle():
    a = math.radians(27)
    rect = [(0, 0), (800, 0), (800, 300), (0, 300), (0, 0)]
    tilted = [(x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)) for x, y in rect]
    assert abs(ll.rectangle_fill(tilted) - 1.0) < 1e-9
    assert abs(ll.rectangle_fill(circle(300)) - math.pi / 4) < 1e-3


def test_enclosure_is_the_largest_share_of_border_pixels_touching_one_neighbour():
    labels = np.array([[1, 1, 1, 1, 3],
                       [1, 2, 2, 1, 3],
                       [1, 2, 2, 1, 3],
                       [1, 1, 1, 1, 3]])
    share, neighbour = ll.enclosure(labels, regions=4)
    assert neighbour[2] == 1 and abs(share[2] - 1.0) < 1e-9
    # region 1's border pixels: 4 touch region 3 (column 3), 8 touch region 2; 12 border pixels in all
    assert neighbour[1] == 2


def test_enclosure_counts_the_map_edge_and_unlabelled_pixels_as_no_neighbour():
    labels = np.array([[2, 2, 1],
                       [2, 2, 1],
                       [0, 0, 1]])
    share, neighbour = ll.enclosure(labels, regions=3)
    # region 2 sits in the map corner: its 4 pixels all lie on the map edge or beside 0, and 3 touch region 1 or 0
    assert neighbour[2] == 1 and share[2] < 0.75


def test_region_means_use_only_clear_pixels_and_need_the_clear_share():
    labels = np.array([[1, 1, 2, 2],
                       [1, 1, 2, 2]])
    value = np.array([[0.2, 0.4, 0.9, 0.9],
                      [0.6, 9.0, 0.1, 0.1]])
    clear = np.array([[True, True, False, False],
                      [True, False, False, True]])
    means, share = ll.region_means(labels, clear, [value], regions=3, min_share=0.6)
    assert abs(means[0][1] - 0.4) < 1e-9, means
    assert np.isnan(means[0][2]), means  # region 2 is only 25% clear
    assert abs(share[1] - 0.75) < 1e-9 and abs(share[2] - 0.25) < 1e-9


def test_region_share_counts_clear_pixels_meeting_a_test():
    labels = np.array([[1, 1, 1, 1]])
    clear = np.array([[True, True, True, False]])
    wet = np.array([[True, False, True, True]])
    s = ll.region_share(labels, clear, wet, regions=2)
    assert abs(s[1] - 2 / 3) < 1e-9


def test_normalised_difference_clamps_negative_light_and_has_no_value_without_light():
    a = np.array([0.4, -0.01, 0.0, 0.3])
    b = np.array([0.1, 0.2, 0.0, -0.05])
    out = ll.normalised_difference(a, b)
    assert abs(out[0] - 0.6) < 1e-6
    assert abs(out[1] - (-1.0)) < 1e-6  # a below 0 counts as 0, as in the field method's NDVI
    assert np.isnan(out[2])
    assert abs(out[3] - 1.0) < 1e-6


def test_snow_needs_a_high_snow_index_and_bright_near_infrared():
    green = np.array([0.6, 0.08, 0.69])
    nir = np.array([0.55, 0.03, 0.5])
    swir16 = np.array([0.1, 0.01, 0.3])
    assert ll.snow(green, nir, swir16).tolist() == [True, False, False]


def test_upsample_doubles_each_pixel():
    a = np.array([[1, 2], [3, 4]])
    assert ll.upsample(a, (4, 3)).tolist() == [[1, 1, 2], [1, 1, 2], [3, 3, 4], [3, 3, 4]]


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
