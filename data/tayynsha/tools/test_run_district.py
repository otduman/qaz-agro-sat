"""Tests for run_district.py (the plan, not the long runs). Run: python data/tayynsha/tools/test_run_district.py"""
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_district as rd  # noqa: E402


def tmp_district(squares=('E1N1', 'E2N1', 'E3N1')):
    d = tempfile.mkdtemp()
    with open(f'{d}/squares.csv', 'w') as fh:
        fh.write('square,min_e\n' + ''.join(f'{s},0\n' for s in squares))
    return d


def done_square(d, sq, y):
    os.makedirs(f'{d}/{sq}/harvest-{y}', exist_ok=True)
    for n in (f'harvest-{y}.csv', f'harvest-{y}-v1.csv'):
        open(f'{d}/{sq}/harvest-{y}/{n}', 'w').write('x')


def test_every_district_recipe_keeps_the_dependency_order():
    for name, r in rd.RECIPES.items():
        steps = [s.name for s in r.district_steps]
        for before, after in (('join_v1', 'duplicates'), ('duplicates', 'field_joins'), ('field_joins', 'absorb'),
                              ('absorb', 'join_v2'), ('join_v2', 'square_fields'), ('square_fields', 'field_splits'),
                              ('field_splits', 'line_counts'), ('line_counts', 'evidence_joins'),
                              ('evidence_joins', 'line_splits'), ('line_splits', 'outlines'),
                              ('fallow_both', 'grass'), ('grass', 'grass_history'), ('grass_history', 'grass_use')):
            assert steps.index(before) < steps.index(after), (name, before, after)
    assert 'plot_joins' in [s.name for s in rd.RECIPES['tayynsha'].district_steps]
    assert 'plot_joins' not in [s.name for s in rd.RECIPES['sandyktau'].district_steps]
    esil = [s.name for s in rd.RECIPES['esil-akmola'].district_steps]
    assert esil.index('fallow_steppe') < esil.index('grass') and 'fallow_crop' in esil


DRAWING = ('square_fields', 'field_splits', 'line_counts', 'evidence_joins', 'line_splits', 'outlines')


def test_drawing_steps_run_for_every_season_and_grass_steps_for_the_main_one():
    for r in rd.RECIPES.values():
        years = {s.name: s.years for s in r.district_steps}
        assert all(years[n] is None for n in DRAWING), r.name
        assert years['grass'] == ('2026',) and years['grass_use'] == ('2026',)
        assert years['join_v1'] is None   # every season


def test_a_season_finished_before_has_only_the_drawing_steps_left_to_run():
    d = tmp_district()
    try:
        r = rd.RECIPES['tayynsha']
        for n in ('join_v1', 'duplicates', 'field_joins', 'plot_joins', 'absorb', 'join_v2', 'fallow_both'):
            rd.mark_done(d, '2025', n)
        assert tuple(s.name for s in rd.plan(d, r, '2025')) == DRAWING
    finally:
        shutil.rmtree(d)


def test_line_counts_leave_the_choice_of_seasons_to_lines_first():
    r = rd.RECIPES['tayynsha']
    step = next(s for s in r.district_steps if s.name == 'line_counts')
    cmd = ' '.join(step.commands('data/tayynsha', '2024', r))
    assert 'lines_first.py count data/tayynsha $q 2024' in cmd and 'season-' not in cmd, cmd


def test_squares_to_run_skip_left_out_and_done_square_seasons():
    d = tmp_district()
    try:
        done_square(d, 'E1N1', '2026')
        r = rd.Recipe('t', 'tayynsha', ('2026', '2025'), left_out={'E3N1': ('2026', '2025')})
        assert rd.squares_to_run(d, r, '2026') == ['E2N1']
        assert rd.squares_to_run(d, r, '2025') == ['E1N1', 'E2N1']
    finally:
        shutil.rmtree(d)


def test_a_left_out_square_with_a_harvest_table_is_reported():
    d = tmp_district()
    try:
        done_square(d, 'E3N1', '2026')
        r = rd.Recipe('t', 'tayynsha', ('2026',), left_out={'E3N1': ('2026',)})
        assert rd.left_out_problems(d, r) == ['E3N1 2026: left out but has harvest-2026/harvest-2026.csv']
    finally:
        shutil.rmtree(d)


def test_done_steps_are_skipped_and_from_reruns_that_step_and_all_after_it():
    d = tmp_district()
    try:
        r = rd.RECIPES['sandyktau']
        names = [s.name for s in r.district_steps if s.years is None or '2026' in s.years]
        for n in names[:4]:
            rd.mark_done(d, '2026', n)
        assert [s.name for s in rd.plan(d, r, '2026')] == names[4:]
        assert [s.name for s in rd.plan(d, r, '2026', start='duplicates')] == names[1:]
    finally:
        shutil.rmtree(d)


def test_new_square_harvests_make_every_district_step_of_that_season_stale():
    d = tmp_district()
    try:
        r = rd.RECIPES['sandyktau']
        for s in r.district_steps:
            rd.mark_done(d, '2026', s.name)
        rd.invalidate(d, '2026')
        assert len(rd.plan(d, r, '2026')) == len([s for s in r.district_steps if s.years is None or '2026' in s.years])
        assert rd.plan(d, r, '2025') == [s for s in r.district_steps if s.years is None or '2025' in s.years]
    finally:
        shutil.rmtree(d)


def test_commands_name_the_district_and_season():
    r = rd.RECIPES['tayynsha']
    step = next(s for s in r.district_steps if s.name == 'field_joins')
    cmds = step.commands('data/tayynsha', '2025', r)
    assert any('field_joins.py data/tayynsha 2025 2026' in c for c in cmds), cmds
    step = next(s for s in r.district_steps if s.name == 'fallow_both')
    assert any('fallow_both_years.py data/tayynsha 2026 2025' in c for c in step.commands('data/tayynsha', '2026', r))


def test_adopt_marks_every_step_of_a_season_done():
    d = tmp_district()
    try:
        r = rd.RECIPES['esil-akmola']
        rd.adopt(d, r, '2025')
        assert rd.plan(d, r, '2025') == [] and rd.plan(d, r, '2026') != []
    finally:
        shutil.rmtree(d)


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
        print('ok', t.__name__)
    print(len(tests), 'passed')
