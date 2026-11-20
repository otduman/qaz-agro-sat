"""Browser checks of the built page (site/dist) in headless Edge or Chromium via Playwright.
Run: python site/build/test_page.py   (skipped when Playwright, a browser or site/dist is missing)."""
import functools
import http.server
import os
import socketserver
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(HERE, '..', 'dist')


def serve():
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass
    handler = functools.partial(Quiet, directory=DIST)
    httpd = socketserver.ThreadingTCPServer(('127.0.0.1', 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f'http://127.0.0.1:{httpd.server_address[1]}/'


def browser(p):
    for kw in ({'channel': 'msedge'}, {}):
        try:
            return p.chromium.launch(headless=True, **kw)
        except Exception:
            continue
    return None


def pick(page, d):
    # the district list on the map is a drop-down since 2026-11-17
    page.click('#mpick summary')
    page.click(f'#district-switch [data-d="{d}"]')


def settled(page):
    page.wait_for_function("document.getElementById('page').getAttribute('aria-busy') === 'false'", timeout=20000)


def test_a_failed_load_shows_a_message_and_can_be_retried(page, base):
    tries = {'n': 0}

    def flaky(route):
        tries['n'] += 1
        if tries['n'] == 1:
            route.abort()
        else:
            route.continue_()
    page.route('**/data/sandyktau.json', flaky)
    page.goto(base + '#ru/tayynsha')
    settled(page)
    pick(page, 'sandyktau')
    page.wait_for_selector('#load-error:not([hidden])', timeout=10000)
    settled(page)
    assert page.inner_text('#title') == 'Тайынша'
    assert page.get_attribute('[data-d="tayynsha"]', 'aria-pressed') == 'true'
    pick(page, 'sandyktau')
    page.wait_for_function("document.getElementById('title').textContent === 'Сандыктау'", timeout=20000)
    assert page.is_hidden('#load-error')
    assert tries['n'] == 2


def test_a_quick_second_choice_wins_over_a_slow_first(page, base):
    held = []
    page.goto(base + '#ru/sandyktau')
    settled(page)
    page.route('**/data/tayynsha.json', lambda route: held.append(route))
    pick(page, 'tayynsha')
    pick(page, 'sandyktau')
    page.wait_for_timeout(500)
    for r in held:
        r.continue_()
    page.wait_for_timeout(2500)
    assert held, 'the Tayynsha load was not held'
    assert page.inner_text('#title') == 'Сандыктау'
    assert page.get_attribute('[data-d="sandyktau"]', 'aria-pressed') == 'true'


def test_the_season_switch_loads_the_other_season_and_keeps_it_in_the_link(page, base):
    cfg = __import__('json').load(open(os.path.join(DIST, 'config.json'), encoding='utf-8'))
    years = (cfg.get('years') or {}).get('tayynsha', [])
    if len(years) < 2:
        print('  skipped: one season only')
        return
    other = [y for y in years if y != cfg['year']][0]
    page.goto(base + '#ru/tayynsha')
    settled(page)
    page.click(f'[data-y="{other}"]')
    page.wait_for_function(f"location.hash === '#ru/tayynsha/{other}'", timeout=20000)
    settled(page)
    assert page.get_attribute(f'[data-y="{other}"]', 'aria-pressed') == 'true'
    assert other in page.inner_text('#eyebrow')
    pick(page, 'esil')
    settled(page)
    esil = (cfg.get('years') or {}).get('esil', [])
    if other in esil:   # the other district has this season too: it stays
        assert page.evaluate('location.hash') == f'#ru/esil/{other}'
        assert page.get_attribute(f'[data-y="{other}"]', 'aria-pressed') == 'true'
    else:               # it has not: back to its main season
        assert page.evaluate('location.hash') == '#ru/esil'
        assert page.is_hidden('#year-switch') or len(esil) > 1


def test_fields_have_a_dark_edge_under_their_coloured_outline_on_the_photo(page, base):
    # owner 2026-11-16: on the brighter photos the field outlines were hard to see
    page.goto(base + '#en/esil')
    settled(page)
    n_fields = page.locator('#map path.f').count()
    edges = page.locator('#map path.edge')
    assert n_fields > 100 and edges.count() == n_fields, (n_fields, edges.count())
    first_edge = page.evaluate("[...document.querySelectorAll('#map path')].findIndex(e => e.classList.contains('edge'))")
    first_field = page.evaluate("[...document.querySelectorAll('#map path')].findIndex(e => e.classList.contains('f'))")
    assert first_edge < first_field, 'edges are drawn under the fields'
    assert page.evaluate("getComputedStyle(document.querySelector('#map path.edge')).pointerEvents") == 'none'


def test_grass_pieces_blend_into_one_see_through_area(page, base):
    # owner 2026-11-19: grass looked like many pieces; pieces are painted in one see-through group with no outline of
    # their own, so touching pieces read as one area
    page.goto(base + '#en/esil')
    settled(page)
    r = page.evaluate("""() => { const p = document.querySelector('#map path.grasspatch'); const g = p.parentNode;
      const s = getComputedStyle(p); return [g.tagName, +getComputedStyle(g).opacity, +s.fillOpacity, s.fill === s.stroke]; }""")
    assert r[0] == 'g' and r[1] < 1 and r[2] == 1 and r[3], r


def test_grass_shows_old_fields_apart_from_steppe_meadows_and_pastures(page, base):
    # grass by use 2026-11-19 (data/experiments/grass-use-2026-11-19/NOTE.md): old fields under grass passed blind
    # twice; pasture could not be confirmed by eye, so it stays together with steppe and meadows
    page.goto(base + '#ru/tayynsha')
    settled(page)
    key = page.text_content('#key')
    assert 'Старые поля' in key and 'Степь и пастбища' in key and len(key) < 400, key
    assert page.locator('#map g.grassuse.u-oldfield path').count() > 10
    assert page.locator('#map g.grassuse.u-steppe path').count() > 100


def test_esil_shows_a_grass_layer_with_its_key(page, base):
    page.goto(base + '#en/esil')
    settled(page)
    assert page.locator('#map path.grasspatch').count() > 100
    assert 'pasture' in page.inner_text('#key').lower()


def test_the_district_picker_in_the_headline_switches_district(page, base):
    page.goto(base + '#en/tayynsha')
    settled(page)
    page.click('#dpick summary')
    page.click('[data-dm="esil"]')
    page.wait_for_function("location.hash === '#en/esil'", timeout=20000)
    settled(page)
    assert page.inner_text('#title') == 'Esil'
    assert not page.evaluate("document.getElementById('dpick').open")
    assert page.get_attribute('#district-switch [data-d="esil"]', 'aria-pressed') == 'true'


def test_the_season_slider_sits_under_the_map_oldest_first_and_moves_the_season(page, base):
    # owner 2026-11-16: seasons as a slider at the bottom of the map
    cfg = __import__('json').load(open(os.path.join(DIST, 'config.json'), encoding='utf-8'))
    years = sorted((cfg.get('years') or {}).get('esil', []))
    if len(years) < 2:
        print('  skipped: one season only')
        return
    page.goto(base + '#en/esil')
    settled(page)
    box = page.locator('.mapbox').bounding_box()
    bar = page.locator('#year-switch').bounding_box()
    # 2026-11-17 redesign: the slider sits over the bottom of the map
    assert bar and bar['y'] > box['y'] + box['height'] / 2 and bar['y'] + bar['height'] <= box['y'] + box['height'] + 1,         'the season slider sits at the bottom of the map'
    labels = [b.get_attribute('data-y') for b in page.locator('#year-switch [data-y]').all()]
    assert labels == years, labels
    page.click(f'#year-switch [data-y="{years[0]}"]')
    page.wait_for_function(f"location.hash === '#en/esil/{years[0]}'", timeout=20000)
    settled(page)
    assert years[0] in page.inner_text('#eyebrow')
    assert page.get_attribute(f'[data-y="{years[0]}"]', 'aria-pressed') == 'true'


def test_the_headline_lines_up_with_the_map_edges(page, base):
    # owner 2026-11-17: the part above the map was narrower than the full-width map
    page.set_viewport_size({'width': 1920, 'height': 1000})
    page.goto(base + '#ru/tayynsha')
    settled(page)
    title = page.locator('#title').bounding_box()
    left = page.locator('#mpick').bounding_box()
    stats = page.locator('#stats').bounding_box()
    zoom = page.locator('.zoom').bounding_box()
    assert abs(title['x'] - left['x']) <= 2, (title, left)
    assert abs((stats['x'] + stats['width']) - (zoom['x'] + zoom['width'])) <= 2, (stats, zoom)
    page.set_viewport_size({'width': 1280, 'height': 800})


def test_the_district_chevron_sits_beside_the_district_name(page, base):
    page.goto(base + '#ru/tayynsha')
    settled(page)
    title = page.locator('#title').bounding_box()
    chev = page.locator('#dpick summary svg').bounding_box()
    mid = chev['y'] + chev['height'] / 2
    assert title['y'] < mid < title['y'] + title['height'], (title, chev)
    assert 0 < chev['x'] - (title['x'] + title['width']) < 40, (title, chev)


def test_the_group_row_name_and_its_count_line_up_past_the_triangle(page, base):
    # owner 2026-11-17: the triangle by the group name looked off, and the count sat under the triangle
    page.goto(base + '#ru/tayynsha')
    settled(page)
    name = page.locator('#acc tr.grouprow button.grp .gname').bounding_box()
    chip = page.locator('#acc tr.grouprow .chip').bounding_box()
    tri = page.locator('#acc tr.grouprow button.grp .tw').bounding_box()
    assert abs(chip['x'] - name['x']) <= 2, (chip, name)
    assert tri['x'] + tri['width'] <= name['x'], (tri, name)
    line = 1.5 * 15  # the first line of the name
    assert name['y'] <= tri['y'] and tri['y'] + tri['height'] <= name['y'] + line, (tri, name)



def test_the_district_chevron_is_centred_on_the_letters_not_the_line(page, base):
    # owner 2026-11-17: the round button looked a little high beside the district name
    page.goto(base + '#ru/tayynsha')
    settled(page)
    got = page.evaluate("""() => {
      const t = document.getElementById('title'), c = document.querySelector('#dpick .chev').getBoundingClientRect();
      const m = document.createElement('span'); m.style.cssText = 'display:inline-block;width:0;height:0';
      t.appendChild(m); const base = m.getBoundingClientRect().top; m.remove();
      return [(c.top + c.bottom) / 2, base - 0.3 * parseFloat(getComputedStyle(t).fontSize)];
    }""")
    assert abs(got[0] - got[1]) <= 2, got


def test_each_headline_figure_and_its_caption_share_a_centre_line(page, base):
    page.goto(base + '#ru/sandyktau')
    settled(page)
    for s in page.locator('.stat').all():
        n, l = s.locator('.n').bounding_box(), s.locator('.l').bounding_box()
        assert abs((n['y'] + n['height'] / 2) - (l['y'] + l['height'] / 2)) <= 2, (n, l)
    xs = {round(s.locator('.l').bounding_box()['x']) for s in page.locator('.stat').all()}
    assert len(xs) == 1, xs   # the captions start on one line down the list


def test_the_top_bar_parts_sit_on_one_line(page, base):
    # owner 2026-11-17: the «Атлас полей» tag and the «Как это сделано» link looked off
    page.goto(base + '#ru/tayynsha')
    settled(page)
    name, sub = page.locator('#brand .name').bounding_box(), page.locator('#brand .sub').bounding_box()
    assert abs((name['y'] + name['height'] / 2) - (sub['y'] + sub['height'] / 2)) <= 2, (name, sub)
    how, lang = page.locator('.toplinks a.how').bounding_box(), page.locator('#lang-switch').bounding_box()
    assert abs(how['height'] - lang['height']) <= 1 and abs(how['y'] - lang['y']) <= 1, (how, lang)


def test_esil_has_no_plot_line_and_the_key_heading_has_room(page, base):
    # owner 2026-11-17: drop the dashed Заречный plot; the key heading sat too close to its first row
    page.goto(base + '#ru/esil')
    settled(page)
    assert page.locator('#map path.plotline').count() == 0
    h, ul = page.locator('#key h3').bounding_box(), page.locator('#key ul.key').bounding_box()
    assert ul['y'] - (h['y'] + h['height']) >= 8, (h, ul)


def test_the_company_table_shows_registry_columns_and_no_modern_fleet(page, base):
    # owner 2026-11-17: taxes and estimated revenue in their own columns; the modern-fleet estimate is gone
    page.goto(base + '#ru/esil')
    settled(page)
    head = page.text_content('#acc thead')
    assert 'Современный' not in head and 'Налоги' in head and 'Выручка' in head, head
    row = page.locator('#acc tbody tr', has_text='Заречный').first.inner_text()
    assert '354' in row, row
    assert 'налоги за 2025' not in page.inner_text('#acc tbody')   # no repeated text in the open-data column


def test_tayynsha_shows_grass_with_what_it_holds_and_a_land_chart(page, base):
    # owner 2026-11-17: say that grass is hay and pasture together; the charts below show it too
    page.goto(base + '#ru/tayynsha')
    settled(page)
    assert page.locator('#map path.grasspatch').count() > 100
    key = page.text_content('#key')
    assert 'пастбищ' in key, key
    # owner 2026-11-19: short captions on the map; the details sit under the land chart
    note = page.evaluate("document.getElementById('ch-land').previousElementSibling.textContent")
    assert 'сенокос' in note and 'пастбищ' in note, note
    labels = page.text_content('#ch-land')
    assert all(w in labels for w in ('Зерновые', 'Масличные', 'Пар', 'Трава')), labels
    assert 'Кормовые' in page.text_content('#lg-crops')   # sown fodder, not the hay-and-pasture layer

def test_a_season_whose_crop_split_is_unreliable_shows_one_crops_bar(page, base):
    # owner 2026-11-20: Tayynsha 2024 goes up with the crop split marked unreliable (oilseeds and grain ripened together)
    page.goto(base + '#ru/tayynsha')
    settled(page)
    page.click('#year-switch [data-y="2024"]')
    settled(page)
    labels = page.text_content('#ch-land')
    assert 'Посевы' in labels and 'Масличные' not in labels and 'Зерновые' not in labels, labels


def test_a_field_card_shows_the_fields_history_and_its_greenness_curve(page, base):
    # owner 2026-11-20 (items 4, 5): crop history over the seasons and the field's greenness through the season
    page.goto(base + '#ru/tayynsha')
    settled(page)
    i = page.evaluate("FieldMap.data.fields.findIndex(f => f.y && f.y['2025'] === 'grain')")
    assert i >= 0
    page.evaluate(f"FieldMap.select({i})")
    page.wait_for_selector('#panel svg.curve path.line')
    text = page.text_content('#panel')
    assert 'История' in text and '2025' in text and '2024' in text, text
    assert page.locator('#panel .hist').count() == 3


def test_company_search_filters_the_table_and_a_company_shows_its_fields(page, base):
    # owner 2026-11-20 (item 6): find a company, see its card and its fields on the map
    page.goto(base + '#ru/tayynsha')
    settled(page)
    before = page.locator('#acc tbody tr').count()
    name = page.evaluate("FieldMap.data.accounts.find(a => !a.group_row && a.ha >= 150).name")
    page.fill('#acc-search', name[3:9])
    after = page.locator('#acc tbody tr').count()
    assert 0 < after < before, (before, after)
    page.locator('#acc tbody button.cname').first.click()
    page.wait_for_selector('#panel .company')
    assert page.locator('#map path.hl').count() > 0
    page.fill('#acc-search', 'zzzz-no-such-company')
    assert 'Ничего не найдено' in page.text_content('#acc tbody')


if __name__ == '__main__':
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print('skipped: Playwright is not installed')
        sys.exit(0)
    if not os.path.isfile(os.path.join(DIST, 'index.html')):
        print('skipped: no site/dist (run build.py first)')
        sys.exit(0)
    httpd, base = serve()
    tests = [(k, v) for k, v in sorted(globals().items()) if k.startswith('test_')]
    with sync_playwright() as p:
        b = browser(p)
        if b is None:
            print('skipped: no browser for Playwright')
            sys.exit(0)
        for name, t in tests:
            ctx = b.new_context()
            page = ctx.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            t(page, base)
            assert not errors, (name, errors)
            ctx.close()
            print('ok', name)
        b.close()
    httpd.shutdown()
    print('all tests passed')
