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
    page.click('[data-d="sandyktau"]')
    page.wait_for_selector('#load-error:not([hidden])', timeout=10000)
    settled(page)
    assert page.inner_text('#title') == 'Тайынша'
    assert page.get_attribute('[data-d="tayynsha"]', 'aria-pressed') == 'true'
    page.click('[data-d="sandyktau"]')
    page.wait_for_function("document.getElementById('title').textContent === 'Сандыктау'", timeout=20000)
    assert page.is_hidden('#load-error')
    assert tries['n'] == 2


def test_a_quick_second_choice_wins_over_a_slow_first(page, base):
    held = []
    page.goto(base + '#ru/sandyktau')
    settled(page)
    page.route('**/data/tayynsha.json', lambda route: held.append(route))
    page.click('[data-d="tayynsha"]')
    page.click('[data-d="sandyktau"]')
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
    page.click('[data-d="esil"]')
    settled(page)
    esil = (cfg.get('years') or {}).get('esil', [])
    if other in esil:   # the other district has this season too: it stays
        assert page.evaluate('location.hash') == f'#ru/esil/{other}'
        assert page.get_attribute(f'[data-y="{other}"]', 'aria-pressed') == 'true'
    else:               # it has not: back to its main season
        assert page.evaluate('location.hash') == '#ru/esil'
        assert page.is_hidden('#year-switch') or len(esil) > 1


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
    assert bar and bar['y'] >= box['y'] + box['height'] - 2, 'the season slider is under the map'
    labels = [b.get_attribute('data-y') for b in page.locator('#year-switch [data-y]').all()]
    assert labels == years, labels
    slider = page.locator('#year-range')
    assert slider.get_attribute('max') == str(len(years) - 1)
    assert slider.input_value() == str(years.index(cfg['year']))
    slider.focus()
    page.keyboard.press('Home')
    page.wait_for_function(f"location.hash === '#en/esil/{years[0]}'", timeout=20000)
    settled(page)
    assert years[0] in page.inner_text('#eyebrow')
    assert page.get_attribute(f'[data-y="{years[0]}"]', 'aria-pressed') == 'true'


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
