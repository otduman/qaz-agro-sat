"""Tests for the Field Atlas build and its source files. Run from the repository root: python site/build/test_build.py"""
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402

SRC = 'site/src'
CONTENT = json.load(open('site/config.json', encoding='utf-8'))['content_dir']
DIST = 'site/dist'
# Words and name fragments that must never reach the page are kept outside git (data/ is not committed):
# data/site/privacy-words.json {"forbidden": [...], "names": [...]}. Without that file these checks are skipped.
_PRIVACY = 'data/site/privacy-words.json'
_WORDS = json.load(open(_PRIVACY, encoding='utf-8')) if os.path.isfile(_PRIVACY) else {'forbidden': [], 'names': []}
FORBIDDEN = _WORDS['forbidden']
NAMES = _WORDS['names']


def keys(d, prefix=''):
    if isinstance(d, dict):
        return {k for key, v in d.items() for k in {prefix + key} | keys(v, prefix + key + '.')}
    if isinstance(d, list):
        return {k for i, v in enumerate(d) for k in keys(v, f'{prefix}{i}.')}
    return set()


def load(p):
    return json.load(open(p, encoding='utf-8'))


def test_translation_keys_match():
    ru, en = load(f'{SRC}/i18n/ru.json'), load(f'{SRC}/i18n/en.json')
    assert keys(ru) == keys(en), keys(ru) ^ keys(en)
    for p in glob.glob(f'{CONTENT}/*.ru.json'):
        other = p.replace('.ru.json', '.en.json')
        assert os.path.isfile(other), other
        a, b = keys(load(p)), keys(load(other))
        assert a == b, (p, a ^ b)


def test_every_district_has_content_in_every_language():
    cfg = load('site/config.json')
    for d in cfg['districts']:
        for lang in cfg['languages']:
            assert os.path.isfile(f"{CONTENT}/{d['id']}.{lang}.json"), (d['id'], lang)


def test_every_district_text_has_specific_section_headings():
    cfg = load('site/config.json')
    for d in cfg['districts']:
        for lang in cfg['languages']:
            c = load(f"{CONTENT}/{d['id']}.{lang}.json")
            for k in ('map_h', 'accounts_h', 'charts_h', 'pace_h'):
                assert c.get(k), (d['id'], lang, k)


def _words_in(folder):
    """Forbidden words: English as whole words (clientX is a browser API, not a word), Russian as word starts
    (Russian words inflect)."""
    import re
    pats = [(w, re.compile(r'(?<![a-z])' + re.escape(w) + r'(?![a-z])') if w.isascii() or ' ' in w
             else re.compile(r'(?<![а-яё])' + re.escape(w) + (r'(?![а-яё])' if w == 'лид' else ''))) for w in FORBIDDEN]
    for p in glob.glob(f'{folder}/**/*', recursive=True):
        if os.path.isfile(p) and p.endswith(('.json', '.html', '.js', '.css')):
            text = open(p, encoding='utf-8').read().lower()
            for w, pat in pats:
                if pat.search(text):
                    yield p, w


def test_src_names_no_company():
    # the page source and interface text go into git: no company or group names
    for p in glob.glob(f'{SRC}/**/*', recursive=True) + glob.glob(f'{SRC}/../build/method/*.html'):
        if os.path.isfile(p) and not p.endswith(('.pdf', '.png', '.jpg', '.webp')):
            t = open(p, encoding='utf-8').read()
            assert not [n for n in NAMES if n in t], p


def test_the_method_pdf_is_built_from_its_checked_source():
    # metodika.pdf is binary; its words are checked in its source page site/build/method/metod.html
    src = f'{SRC}/../build/method/metod.html'
    assert os.path.isfile(f'{SRC}/metodika.pdf') and os.path.isfile(src)
    assert not list(_words_in(f'{SRC}/../build/method'))


def test_no_forbidden_words():
    found = list(_words_in(SRC)) + list(_words_in(CONTENT))
    assert not found, found


def test_company_counts_count_both_registrations_of_a_joined_farm():
    d = dict(totals=dict(fields=0, ha=0, linked_ha=0, private_ha=0, known_ha=1, group_ha=0), fields=[], outline=[[]],
             pace=dict(all=[]), crop_ha=dict(grain=1, oilseed=0, other=0, none=0), squares=[],
             accounts=[dict(name='a', ha=10, group=True, n=2), dict(name='b', ha=5, group=True),
                       dict(name='c', ha=3, group=False)])
    t = build.tokens(d)
    assert (t['group_cos'], t['companies']) == (3, 4)


def test_oilseed_share_counts_fields_inside_the_district_only():
    sq = lambda x: [x, 0, x + 1, 0, x + 1, 1, x, 1]
    d = dict(totals=dict(fields=3, ha=300, linked_ha=0, private_ha=0, known_ha=1, group_ha=0), squares=[],
             outline=[[0, -5, 10, -5, 10, 5, 0, 5]], pace=dict(all=[]), accounts=[],
             crop_ha=dict(grain=100, oilseed=200, other=0, none=0),
             fields=[dict(p=sq(1), ha=100, c='grain'), dict(p=sq(3), ha=100, c='oilseed'), dict(p=sq(20), ha=100, c='oilseed')])
    assert build.tokens(d)['oil_pct'] == 50.0


def test_crop_areas_inside_the_district():
    sq = lambda x: [x, 0, x + 1, 0, x + 1, 1, x, 1]
    d = dict(outline=[[0, -5, 10, -5, 10, 5, 0, 5]],
             fields=[dict(p=sq(1), ha=100, c='grain'), dict(p=sq(3), ha=40, c='oilseed'), dict(p=sq(20), ha=100, c='oilseed')])
    assert build.crops_inside(d) == {'grain': 100, 'oilseed': 40, 'other': 0, 'none': 0}


def test_each_season_has_its_own_file_names_with_the_main_season_unsuffixed():
    assert build.stem('tayynsha', '2026', '2026') == 'tayynsha'
    assert build.stem('tayynsha', '2025', '2026') == 'tayynsha-2025'


def test_every_district_season_has_text_in_every_language():
    cfg = load('site/config.json')
    for d in cfg['districts']:
        for y in d.get('years', [cfg['year']]):
            for lang in cfg['languages']:
                assert os.path.isfile(f"{CONTENT}/{build.stem(d['id'], y, cfg['year'])}.{lang}.json"), (d['id'], y, lang)


def test_tokens_resolve():
    v = {'group_pct': 42, 'ha': 567000}
    assert build.fill('{group_pct}% of {ha} ha', v, 'en') == '42% of 567,000 ha'
    assert build.fill('{group_pct}% of {ha} ha', v, 'ru') == '42% of 567 000 ha'
    try:
        build.fill('{nope}', v, 'en')
    except KeyError:
        pass
    else:
        raise AssertionError('unknown token must raise')


def test_dist_privacy():
    p = f'{DIST}/data/tayynsha.json'
    if not os.path.isfile(p):
        print('  skipped: no site/dist (run build.py first)')
        return
    for p in glob.glob(f'{DIST}/data/*.json'):
        d = load(p)
        assert all(f['h'] is None for f in d['fields'] if f['k'] in ('farm', 'private', 'lease', 'college')), p
        assert not [a['name'] for a in d['accounts'] if a['name'].startswith(('ПТ', 'КХ', 'ИП'))], p
    found = list(_words_in(DIST))
    assert not found, found


def test_data_files_are_checked_again_on_every_visit():
    # a new deploy must show at once; the data is small and keeps its ETag, so a revisit costs one 304
    data = [h for h in build.VERCEL['headers'] if h['source'].startswith('/data/')][0]
    assert data['headers'][0]['value'] == 'public, max-age=0, must-revalidate'


def test_every_field_lies_within_the_survey_squares():
    # a field drawn far off the squares means a wrong map zone somewhere in the data (2026-11-13: Esil joins)
    for path in glob.glob(f'{DIST}/data/*.json'):
        d = load(path)
        sx = [v for q in d['squares'] for v in q['p'][0::2]]
        sy = [v for q in d['squares'] for v in q['p'][1::2]]
        x0, x1, y0, y1 = min(sx) - 2, max(sx) + 2, min(sy) - 2, max(sy) + 2
        off = [f for f in d['fields'] + d['fallow']
               if not all(x0 <= x <= x1 for x in f['p'][0::2]) or not all(y0 <= y <= y1 for y in f['p'][1::2])]
        assert not off, (path, len(off))


def test_half_date_is_the_day_the_satellite_saw_half_the_cereals_cut():
    d = {'totals': dict(fields=1, ha=10, linked_ha=0, private_ha=0, group_ha=0, known_ha=0), 'fields': [], 'outline': [],
         'accounts': [], 'squares': [], 'pace': {'all': [['2026-08-27', 0.49], ['2026-08-28', 0.5], ['2026-08-29', 0.6]]}}
    v = build.tokens(d)
    assert build.fill('{half_date}', v, 'ru') == '28 авг'
    assert build.fill('{half_date}', v, 'en') == 'Aug 28'


def test_headline_figures_come_from_our_own_data():
    # owner 2026-11-17: area to harvest inside the district, combines now -> a modern fleet, large farms
    for p in glob.glob(f'{CONTENT}/*.json'):
        n = [s['n'] for s in load(p)['stats']]
        assert n == ['{crop_inside}', '{fleet}', '{big_n}'], (p, n)   # owner 2026-11-17: no modern-fleet forecast


def test_a_folded_group_row_counts_its_member_companies():
    # 2026-11-17: the callout said "1 land user ... in the group" while the row chip said 10 companies
    member = lambda n: {'name': 'x', 'group': True, 'ha': 1, 'n': n}
    d = {'totals': dict(fields=1, ha=10, linked_ha=0, private_ha=0, group_ha=0, known_ha=0), 'fields': [], 'outline': [],
         'squares': [], 'pace': {'all': []},
         'accounts': [{'name': 'G', 'group': True, 'group_row': True, 'members_n': 3, 'ha': 3,
                       'members': [member(1), member(2), member(1)]},
                      {'name': 'other', 'group': False, 'ha': 1}]}
    v = build.tokens(d)
    assert v['group_cos'] == 4
    assert v['companies'] == 5



def _district(fields, accounts):
    return {'totals': dict(fields=1, ha=10, linked_ha=0, private_ha=0, group_ha=0, known_ha=1), 'squares': [],
            'pace': {'all': []}, 'outline': [[0, 0, 100, 0, 100, 100, 0, 100]], 'fields': fields, 'accounts': accounts}


def test_combine_figures_use_the_area_inside_the_district():
    # 2026-11-17: Sandyktau's headline counted all squares (595k ha) though the district has about 360k
    sq = [10, 10, 20, 10, 20, 20, 10, 20]
    out = [x + 200 for x in sq]
    fields = [{'c': 'grain', 'ha': 2000, 'p': sq}, {'c': 'oilseed', 'ha': 600, 'p': sq},
              {'c': 'grain', 'ha': 9000, 'p': out}, {'c': 'none', 'ha': 50, 'p': sq}]
    v = build.tokens(_district(fields, []), combines={'n': 4, 'year': 2025})
    assert v['crop_inside'] == 2600
    assert v['fleet'] == 4
    assert v['load'] == 650   # ha per combine: 2600 / 4
    est = build.tokens(_district(fields, []))
    assert est['fleet'] == '≈4'   # no official count: 2600 ha at about 700 ha a combine


def test_large_farms_are_holders_with_5000_ha_or_more():
    acc = [{'name': 'a', 'group': False, 'ha': 7000}, {'name': 'b', 'group': False, 'ha': 5000},
           {'name': 'c', 'group': False, 'ha': 4999}]
    v = build.tokens(_district([], acc))
    assert v['big_n'] == 2 and v['big_ha'] == 12000


def test_no_text_quotes_the_modern_farm_combine_load():
    # owner 2026-11-17: 1,600-2,000 ha per combine undercounted fleets 2-3 times; only district averages are shown
    for p in glob.glob(f'{CONTENT}/*.json'):
        text = json.dumps(load(p), ensure_ascii=False)
        assert 'современного хозяйства' not in text and "modern farm's" not in text, p


def test_land_inside_sums_crops_fallow_and_grass_whose_centre_is_in_the_district():
    sq = [10, 10, 20, 10, 20, 20, 10, 20]
    out = [x + 200 for x in sq]
    d = {'outline': [[0, 0, 100, 0, 100, 100, 0, 100]],
         'fields': [{'c': 'grain', 'ha': 2000, 'p': sq}, {'c': 'oilseed', 'ha': 600, 'p': sq}, {'c': 'grain', 'ha': 9, 'p': out}],
         'fallow': [{'ha': 300, 'p': sq}, {'ha': 7, 'p': out}], 'grass': [{'ha': 900, 'p': sq}, {'ha': 5, 'p': out}]}
    assert build.land_inside(d) == {'grain': 2000, 'oilseed': 600, 'fallow': 300, 'grass': 900}

def test_pace_counts_only_grain_fields_whose_centre_is_in_the_district():
    sq = [10, 10, 20, 10, 20, 20, 10, 20]
    out = [x + 200 for x in sq]
    d = {'year': '2026', 'outline': [[0, 0, 100, 0, 100, 100, 0, 100]],
         'fields': [{'c': 'grain', 'ha': 300, 'p': sq, 'm': '2026-08-20', 'k': 'group'},
                    {'c': 'grain', 'ha': 100, 'p': sq, 'm': '', 'k': 'farm'},
                    {'c': 'oilseed', 'ha': 500, 'p': sq, 'm': '2026-08-01', 'k': 'farm'},
                    {'c': 'grain', 'ha': 900, 'p': out, 'm': '', 'k': 'farm'}]}
    p = dict(build.pace_inside(d)['all'])
    assert p['2026-08-19'] == 0 and p['2026-08-20'] == 0.75 and p['2026-10-01'] == 0.75
    assert dict(build.pace_inside(d)['group'])['2026-08-20'] == 1.0


def test_field_history_takes_each_other_seasons_class_where_it_covers_most_of_the_field():
    sq = [0, 0, 10, 0, 10, 10, 0, 10]
    part = [0, 0, 4, 0, 4, 10, 0, 10]   # 40% of sq
    far = [50, 50, 60, 50, 60, 60, 50, 60]
    seasons = {
        '2026': {'fields': [{'c': 'grain', 'p': sq}, {'c': 'oilseed', 'p': far}], 'fallow': [], 'grass': []},
        '2025': {'fields': [{'c': 'oilseed', 'p': sq}], 'fallow': [{'p': far}], 'grass': []},
        '2024': {'fields': [{'c': 'grain', 'p': part}], 'fallow': [], 'grass': [], 'crop_split': False},
    }
    build.add_history(seasons)
    a, b = seasons['2026']['fields']
    assert a['y'] == {'2025': 'oilseed', '2024': None}   # 2024 covers only 40% of it
    assert b['y'] == {'2025': 'fallow', '2024': None}
    assert seasons['2024']['fields'][0]['y'] == {'2026': 'grain', '2025': 'oilseed'}
    assert seasons['2025']['fields'][0]['y'] == {'2026': 'grain', '2024': None}


def test_field_history_says_crops_where_a_seasons_crop_split_is_unreliable():
    sq = [0, 0, 10, 0, 10, 10, 0, 10]
    seasons = {'2026': {'fields': [{'c': 'grain', 'p': sq}], 'fallow': [], 'grass': []},
               '2024': {'fields': [{'c': 'oilseed', 'p': sq}], 'fallow': [], 'grass': [], 'crop_split': False}}
    build.add_history(seasons)
    assert seasons['2026']['fields'][0]['y'] == {'2024': 'crops'}


def test_curves_keep_the_shown_fields_s2_greenness_on_dates_at_least_half_clear():
    import tempfile
    d = tempfile.mkdtemp()
    os.makedirs(f'{d}/E1N1/series-2026')
    rows = ['field,date,clear_share,ndvi_median,satellite',
            '2026-F0007,2026-06-01,0.9,0.412,S2', '2026-F0007,2026-06-06,0.3,0.700,S2',
            '2026-F0007,2026-06-09,0.8,0.550,LC08', '2026-F0007,2026-06-11,0.6,0.633,S2',
            '2026-F0009,2026-06-11,1.0,0.200,S2', '2026-F0099,2026-06-11,1.0,0.900,S2']
    with open(f'{d}/E1N1/series-2026/series-2026.csv', 'w') as fh:
        fh.write('\n'.join(rows) + '\n')
    out = build.curves(d, '2026', {'E1N1': {7, 9}})
    assert out == {'E1N1': {'d': ['06-01', '06-11'], 'f': {'7': [41, 63], '9': [None, 20]}}}, out


if __name__ == '__main__':
    for n, f in list(globals().items()):
        if n.startswith('test_'):
            f()
            print('ok', n)
    print('all tests passed')
