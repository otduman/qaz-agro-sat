"""Builds the Field Atlas into site/dist: the page from site/src, per-district data from data/<district>, satellite
pictures, and the district texts (data/site/content, gitignored: they name companies) with their {tokens} filled
per language. site/dist holds company names, so it is
gitignored and goes straight to Vercel.

Run from the repository root:  python site/build/build.py [--no-images]
--no-images keeps the pictures already in site/dist/img (they take a minute to make)."""
import csv
import datetime
import json
import math
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import district_data  # noqa: E402
import imagery  # noqa: E402

SRC, DIST = 'site/src', 'site/dist'
THOUSANDS = {'ru': ' ', 'en': ','}
DECIMAL = {'ru': ',', 'en': '.'}
VERCEL = {'cleanUrls': True, 'headers': [
    {'source': '/img/(.*)', 'headers': [{'key': 'Cache-Control', 'value': 'public, max-age=604800'}]},
    {'source': '/data/(.*)', 'headers': [{'key': 'Cache-Control', 'value': 'public, max-age=0, must-revalidate'}]}]}


MONTHS = {'ru': ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'],
          'en': ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']}


def number(v, lang):
    if isinstance(v, datetime.date):
        m = MONTHS[lang][v.month - 1]
        return f'{v.day} {m}' if lang == 'ru' else f'{m} {v.day}'
    if isinstance(v, int):
        return f'{v:,}'.replace(',', THOUSANDS[lang])
    if isinstance(v, float):
        return f'{v:.1f}'.replace('.', DECIMAL[lang])
    return str(v)


def fill(text, values, lang):
    """Replaces {token} with values[token], numbers formatted for the language; an unknown token raises KeyError."""
    return re.sub(r'\{(\w+)\}', lambda m: number(values[m.group(1)], lang), text)


def fill_all(obj, values, lang):
    if isinstance(obj, str):
        return fill(obj, values, lang)
    if isinstance(obj, list):
        return [fill_all(v, values, lang) for v in obj]
    if isinstance(obj, dict):
        return {k: fill_all(v, values, lang) for k, v in obj.items()}
    return obj


def inside(x, y, rings):
    """Point in any of the flat rings [x, y, x, y, ...] (ray casting)."""
    hit = False
    for r in rings:
        n = len(r) // 2
        for i in range(n):
            x1, y1, x2, y2 = r[2 * i], r[2 * i + 1], r[2 * ((i + 1) % n)], r[2 * ((i + 1) % n) + 1]
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
    return hit


def thousands(v):
    return int(round(v, -3))


def stem(did, year, main_year):
    """File stem for a district-season: the main season keeps the bare id, others get -<year>."""
    return did if str(year) == str(main_year) else f'{did}-{year}'


def fields_inside(d):
    return [f for f in d['fields'] if inside(sum(f['p'][0::2]) / (len(f['p']) // 2),
                                             sum(f['p'][1::2]) / (len(f['p']) // 2), d['outline'])]


def crops_inside(d):
    """Hectares per crop group over fields whose centre is inside the district outline."""
    out = dict(grain=0, oilseed=0, other=0, none=0)
    for f in fields_inside(d):
        out[f['c']] = out.get(f['c'], 0) + f['ha']
    return out


COVER = 0.5   # another season's shape must cover at least half of a field to give it that season's class


def _poly(p):
    from shapely.geometry import Polygon
    g = Polygon(list(zip(p[0::2], p[1::2])))
    return g if g.is_valid else g.buffer(0)


def add_history(seasons):
    """seasons: year -> {'fields', 'fallow', 'grass', optional 'crop_split'}. Each field gets f['y'] = {other year:
    class there or None}: the class of the other season's field, fallow or grass shape that covers most of it, when
    that covers at least COVER of the field; 'crops' for cereals and oilseeds of a season whose split is unreliable."""
    from shapely.strtree import STRtree
    index = {}
    for y, d in seasons.items():
        shapes, labels = [], []
        for f in d['fields']:
            c = f['c']
            if d.get('crop_split') is False and c in ('grain', 'oilseed'):
                c = 'crops'
            shapes.append(_poly(f['p'])); labels.append(c)
        for kind in ('fallow', 'grass'):
            for q in d.get(kind) or []:
                shapes.append(_poly(q['p'])); labels.append(kind)
        index[y] = (STRtree(shapes), shapes, labels)
    for y, d in seasons.items():
        for f in d['fields']:
            g = _poly(f['p'])
            hist = {}
            for o, (tree, shapes, labels) in index.items():
                if o == y:
                    continue
                best, cls = 0.0, None
                for i in tree.query(g):
                    a = g.intersection(shapes[i]).area
                    if a > best:
                        best, cls = a, labels[i]
                hist[o] = cls if g.area and best / g.area >= COVER else None
            f['y'] = hist


CURVE_CLEAR = 0.5   # a date counts for a field's greenness curve when the field is at least half clear


def curves(district_dir, year, ids_by_square):
    """square -> {'d': ['MM-DD', ...], 'f': {region id: [NDVI x 100 or None per date]}} for the shown fields, from
    series-YEAR.csv: Sentinel-2 dates with the field at least CURVE_CLEAR clear (Landsat reads differently)."""
    import csv as _csv
    out = {}
    for sq, ids in sorted(ids_by_square.items()):
        path = f'{district_dir}/{sq}/series-{year}/series-{year}.csv'
        if not os.path.isfile(path):
            continue
        vals = {}
        for r in _csv.DictReader(open(path, encoding='utf-8')):
            try:
                fid = int(r['field'].split('-F')[1])
                if fid not in ids or r['satellite'] != 'S2' or float(r['clear_share'] or 0) < CURVE_CLEAR:
                    continue
                vals.setdefault(fid, {})[r['date'][5:]] = round(float(r['ndvi_median']) * 100)
            except (ValueError, IndexError):
                continue
        dates = sorted({day for v in vals.values() for day in v})
        out[sq] = {'d': dates, 'f': {str(f): [v.get(day) for day in dates] for f, v in sorted(vals.items())}}
    return out


def rain_days(district_dir, year):
    """[[date, mm]] of daily district rain for the season (rain.py: ERA5-Land, 15 Jul - 31 Oct), or [] without a file."""
    p = os.path.join(district_dir, f'rain-{year}.csv')
    if not os.path.isfile(p):
        return []
    return [[r['date'], float(r['mm'])] for r in csv.DictReader(open(p, encoding='utf-8'))]


def pace_inside(d):
    """Harvest pace over grain fields whose centre is inside the district outline (proposed 2026-11-20 with the edge squares: the survey squares
    reach far past the border, and the official pace is the district's)."""
    rows = [(f.get('m') or '', f['ha'], f.get('k') == 'group') for f in fields_inside(d) if f['c'] == 'grain']
    return district_data.pace(rows, d['year'])


def registrations(a):
    """Registered companies behind one table row; a folded group row counts its members."""
    if a.get('group_row'):
        return sum(m.get('n', 1) for m in a.get('members', []))
    return a.get('n', 1)


AVERAGE_HA = 700           # ha a season per combine today: Tayynsha 2026 (756 combines) and Sandyktau 2025 (560)
BIG_FARM_HA = 5000


def land_inside(d):
    """Hectares inside the district outline by kind of land: grain, oilseed, fallow, grass (centre of each shape)."""
    def ins(q):
        n = len(q['p']) // 2
        return inside(sum(q['p'][0::2]) / n, sum(q['p'][1::2]) / n, d['outline'])
    out = dict(grain=0, oilseed=0, fallow=0, grass=0)
    for f in d['fields']:
        if f['c'] in ('grain', 'oilseed') and ins(f):
            out[f['c']] += f['ha']
    for k in ('fallow', 'grass'):
        out[k] = sum(q['ha'] for q in d.get(k, []) if ins(q))
    return {k: round(v) for k, v in out.items()}


def tokens(d, combines=None):
    """Numbers the content text may quote, from one district's data; combines = the official count, if published."""
    t = d['totals']
    ins = [f for f in d['fields'] if inside(sum(f['p'][0::2]) / (len(f['p']) // 2),
                                            sum(f['p'][1::2]) / (len(f['p']) // 2), d['outline'])]
    end = max(s for _, s in d['pace']['all']) if d['pace']['all'] else 0
    half = next((day for day, s in d['pace']['all'] if s >= 0.5), None)
    oil_in = sum(f['ha'] for f in ins if f['c'] == 'oilseed')
    grain_in = sum(f['ha'] for f in ins if f['c'] == 'grain')
    known = t['known_ha'] or 1
    top = d['accounts'][0] if d['accounts'] else {'name': '—', 'ha': 0}
    crop_inside = round(grain_in + oil_in)
    fleet = crop_inside / AVERAGE_HA
    fleet = combines['n'] if combines else '≈' + str(int(round(fleet, -1)) if fleet >= 100 else int(round(fleet)))
    big = [a for a in d['accounts'] if a['ha'] >= BIG_FARM_HA]
    return dict(
        crop_inside=thousands(crop_inside) if crop_inside >= 10000 else crop_inside, fleet=fleet,
        # ha per combine this season, from our area and the official count
        load=int(round(crop_inside / combines['n'], -1)) if combines else '—',
        big_n=len(big), big_ha=thousands(sum(a['ha'] for a in big)) if big else 0,
        fields=t['fields'], ha=thousands(t['ha']), linked_ha=thousands(t['linked_ha']),
        private_ha=thousands(t['private_ha']), inside_fields=len(ins), inside_ha=thousands(sum(f['ha'] for f in ins)),
        linked_pct=round(100 * t['linked_ha'] / (t['ha'] or 1)), group_pct=round(100 * t['group_ha'] / known),
        # counted as registered companies: a row joining two registrations of one farm counts twice
        group_cos=sum(registrations(a) for a in d['accounts'] if a['group']), companies=sum(registrations(a) for a in d['accounts']),
        squares=len(d['squares']), pace_end=round(100 * end),
        # the day the satellite had seen half the cereal area cut
        half_date=datetime.date.fromisoformat(half) if half else '—',
        # inside the district only, so it compares with the district's official crop split
        oil_pct=round(100 * oil_in / ((grain_in + oil_in) or 1), 1),
        top_name=top['name'], top_pct=round(100 * top['ha'] / known))


def clean_dist(keep_images):
    if not os.path.isdir(DIST):
        return
    for name in os.listdir(DIST):
        p = os.path.join(DIST, name)
        if keep_images and name == 'img':
            continue
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)


def main():
    no_images = '--no-images' in sys.argv
    cfg = json.load(open('site/config.json', encoding='utf-8'))
    clean_dist(no_images)
    shutil.copytree(SRC, DIST, dirs_exist_ok=True)
    os.makedirs(f'{DIST}/data', exist_ok=True)
    os.makedirs(f'{DIST}/content', exist_ok=True)
    for dist in cfg['districts']:
        did = dist['id']
        main = cfg['year']
        imagery_main = None
        built = {}
        for year in [main] + [y for y in dist.get('years', [main]) if y != main]:
            name = stem(did, year, main)
            print(f'{name}: fields and holders ...', flush=True)
            d = district_data.build(dist['data_dir'], year, f"{dist['data_dir']}/holders.csv", cfg['groups_csv'],
                                    dist['boundary'], tuple(dist['town']), dist['ha_per_combine'],
                                    dist.get('crops_name', 'crops-{year}.csv'), dist.get('plots'),
                                    dist.get('holders_read', True), ref_year=main)
            if imagery_main is not None:
                d['imagery'] = imagery_main  # one picture set per district, from the main season
            else:
                meta = f'{DIST}/img/{did}/imagery.json'
                if no_images and os.path.isfile(meta):
                    d['imagery'] = json.load(open(meta, encoding='utf-8'))
                else:
                    print(f'{did}: satellite pictures ...', flush=True)
                    d['imagery'] = imagery.district_pictures(dist['data_dir'], d['squares'], d['origin'],
                                                             f'{DIST}/img/{did}', f'img/{did}/')
                    json.dump(d['imagery'], open(meta, 'w', encoding='utf-8'))
                imagery_main = d['imagery']
            d['crop_ha_inside'] = crops_inside(d)
            d['pace'] = pace_inside(d)
            d['rain'] = rain_days(dist['data_dir'], year)
            d['land_inside'] = land_inside(d)
            values = tokens(d, dist.get('combines'))
            for lang in cfg['languages']:
                content = json.load(open(f"{cfg['content_dir']}/{name}.{lang}.json", encoding='utf-8'))
                json.dump(fill_all(content, values, lang), open(f'{DIST}/content/{name}.{lang}.json', 'w', encoding='utf-8'),
                          ensure_ascii=False)
            split = json.load(open(f"{cfg['content_dir']}/{name}.{cfg['languages'][0]}.json", encoding='utf-8')).get('crop_split')
            built[year] = (name, d, split)
            ids = {}
            for f in d['fields']:
                ids.setdefault(f['q'], set()).add(f['i'])
            os.makedirs(f'{DIST}/data/curves/{name}', exist_ok=True)
            for sq, c in curves(dist['data_dir'], year, ids).items():
                json.dump(c, open(f'{DIST}/data/curves/{name}/{sq}.json', 'w', encoding='utf-8'), separators=(',', ':'))
        # each field's class in the district's other seasons (owner 2026-11-20: field history)
        add_history({y: {'fields': d['fields'], 'fallow': d.get('fallow'), 'grass': d.get('grass'), 'crop_split': split}
                     for y, (_, d, split) in built.items()})
        for y, (name, d, _) in built.items():
            json.dump(d, open(f'{DIST}/data/{name}.json', 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    public = {k: v for k, v in cfg.items() if k in ('title', 'subtitle', 'author', 'languages', 'default_language')}
    public['districts'] = [x['id'] for x in cfg['districts']]
    public['year'] = cfg['year']
    public['years'] = {x['id']: x.get('years', [cfg['year']]) for x in cfg['districts']}
    json.dump(public, open(f'{DIST}/config.json', 'w', encoding='utf-8'), ensure_ascii=False)
    json.dump(VERCEL, open(f'{DIST}/vercel.json', 'w', encoding='utf-8'), indent=1)
    total = 0
    for root, _, files in os.walk(DIST):
        for f in files:
            total += os.path.getsize(os.path.join(root, f))
    for f in sorted(n for n in os.listdir(f'{DIST}/data') if n.endswith('.json')):
        print(f'  data/{f}: {os.path.getsize(f"{DIST}/data/{f}") / 1e6:.2f} MB')
    print(f'site/dist: {total / 1e6:.1f} MB in all')


if __name__ == '__main__':
    main()
