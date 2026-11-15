import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import remap_clicks  # noqa: E402

SQUARE = [[[70.0, 53.0], [70.01, 53.0], [70.01, 53.01], [70.0, 53.01], [70.0, 53.0]]]
EAST = [[[70.01, 53.0], [70.02, 53.0], [70.02, 53.01], [70.01, 53.01], [70.01, 53.0]]]


def test_a_click_moves_to_the_new_field_that_holds_the_old_centre():
    new = {'2026-F0007': SQUARE, '2026-F0008': EAST}
    old = {'2026-F0100': (53.005, 70.005), '2026-F0101': (53.005, 70.015)}
    assert remap_clicks.remap(old, new) == {'2026-F0100': '2026-F0007', '2026-F0101': '2026-F0008'}


def test_a_centre_in_a_gap_goes_to_a_field_within_150_m_or_nowhere():
    new = {'2026-F0007': SQUARE}
    near = (53.005, 70.0115)   # ~100 m east of the square's edge
    far = (53.005, 70.05)
    assert remap_clicks.remap({'a': near, 'b': far}, new) == {'a': '2026-F0007'}


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
