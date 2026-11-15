"""Tests for holder_pace.py. Run: python data/tayynsha/tools/test_holder_pace.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import holder_pace  # noqa: E402


def row(square, field, lat, lon, ha):
    return dict(square=square, field=field, centroid_lat=str(lat), centroid_lon=str(lon), area_ha=str(ha))


def test_a_field_kept_under_its_own_square_keeps_its_holder():
    owner = {('A', '2026-F0001'): 'farm'}
    info = {('A', '2026-F0001'): (53.0, 70.0, 100.0)}
    rows = [row('A', '2026-F0001', 53.0, 70.0, 100.0)]
    assert holder_pace.attach(owner, info, rows) == {('A', '2026-F0001'): 'farm'}


def test_a_field_kept_under_the_neighbouring_square_takes_the_holder_read_in_the_other():
    # The plot card was read in square A, but the field's centre lies in square B's core, so the district table keeps
    # B's copy of it under B's own field id.
    owner = {('A', '2026-F0007'): 'farm'}
    info = {('A', '2026-F0007'): (53.0, 70.0, 100.0)}
    rows = [row('B', '2026-F0031', 53.0005, 70.0005, 110.0)]  # about 65 m away, 10% larger
    assert holder_pace.attach(owner, info, rows) == {('B', '2026-F0031'): 'farm'}


def test_a_different_field_nearby_does_not_take_the_holder():
    owner = {('A', '2026-F0007'): 'farm'}
    info = {('A', '2026-F0007'): (53.0, 70.0, 100.0)}
    rows = [row('B', '2026-F0031', 53.0005, 70.0005, 200.0),  # close, but twice the size
            row('B', '2026-F0032', 53.003, 70.0, 100.0)]  # same size, but about 330 m away
    assert holder_pace.attach(owner, info, rows) == {}


def test_the_same_id_in_another_square_is_not_the_same_field():
    owner = {('A', '2026-F0007'): 'farm'}
    info = {('A', '2026-F0007'): (53.0, 70.0, 100.0)}
    rows = [row('B', '2026-F0007', 53.2, 70.0, 100.0)]
    assert holder_pace.attach(owner, info, rows) == {}


def test_a_field_kept_in_the_table_does_not_lend_its_holder_to_a_neighbour():
    # A/F0001 is a kept row of its own; B/F0009 is the neighbouring field whose copy read in A is A/F0002.
    owner = {('A', '2026-F0001'): 'first', ('A', '2026-F0002'): 'second'}
    info = {('A', '2026-F0001'): (53.0, 70.0, 10.0), ('A', '2026-F0002'): (53.001, 70.0, 10.0)}
    rows = [row('A', '2026-F0001', 53.0, 70.0, 10.0), row('B', '2026-F0009', 53.001, 70.0, 10.0)]
    assert holder_pace.attach(owner, info, rows) == {('A', '2026-F0001'): 'first', ('B', '2026-F0009'): 'second'}


def test_the_nearest_copy_wins_over_an_earlier_one_further_away():
    owner = {('A', '2026-F0001'): 'far', ('A', '2026-F0002'): 'near'}
    info = {('A', '2026-F0001'): (53.0, 70.0, 10.0), ('A', '2026-F0002'): (53.0011, 70.0, 10.0)}
    rows = [row('B', '2026-F0009', 53.001, 70.0, 10.0)]
    assert holder_pace.attach(owner, info, rows) == {('B', '2026-F0009'): 'near'}


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
