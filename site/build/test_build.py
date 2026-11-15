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
    for p in glob.glob(f'{SRC}/**/*', recursive=True):
        if os.path.isfile(p):
            t = open(p, encoding='utf-8').read()
            assert not [n for n in NAMES if n in t], p


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


if __name__ == '__main__':
    for n, f in list(globals().items()):
        if n.startswith('test_'):
            f()
            print('ok', n)
    print('all tests passed')
