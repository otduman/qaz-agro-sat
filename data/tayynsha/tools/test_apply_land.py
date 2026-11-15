"""Tests for apply_land.py. Run: python data/tayynsha/tools/test_apply_land.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apply_land as al  # noqa: E402


def gj(*pairs):
    return {'type': 'FeatureCollection', 'features': [
        {'type': 'Feature', 'properties': {'id': i, 'area_ha': 10.0, 'land': land, 'uniformity': 0.8},
         'geometry': None} for i, land in pairs]}


def row(i, land, v2):
    return {'id': i, 'land': land, 'land_v2': v2}


def test_each_region_takes_its_rule_v2_label_and_keeps_its_other_properties():
    g = gj(('F1', 'other'), ('F2', 'field'), ('F3', 'fallow'))
    out, changed = al.relabel(g, [row('F1', 'other', 'field'), row('F2', 'field', 'field'),
                                  row('F3', 'fallow', 'other')])
    assert [f['properties']['land'] for f in out['features']] == ['field', 'field', 'other']
    assert out['features'][0]['properties']['uniformity'] == 0.8
    assert changed == 2


def test_a_table_made_on_another_field_map_is_refused():
    g = gj(('F1', 'other'), ('F2', 'field'))
    for rows in ([row('F1', 'other', 'field')],                                    # a region missing
                 [row('F1', 'field', 'field'), row('F2', 'field', 'field')]):    # a label that does not match
        try:
            al.relabel(g, rows)
        except ValueError:
            continue
        raise AssertionError('accepted a table that does not fit this field map')


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
