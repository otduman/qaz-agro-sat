"""Builds the Field Atlas into site/dist: the page from site/src, per-district data from data/<district>, satellite
pictures, and the district texts (data/site/content, gitignored: they name companies) with their {tokens} filled
per language. site/dist holds company names, so it is
gitignored and goes straight to Vercel.

Run from the repository root:  python site/build/build.py [--no-images]
--no-images keeps the pictures already in site/dist/img (they take a minute to make)."""
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


def number(v, lang):
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


def tokens(d):
    """Numbers the content text may quote, from one district's data."""
    t = d['totals']
    ins = [f for f in d['fields'] if inside(sum(f['p'][0::2]) / (len(f['p']) // 2),
                                            sum(f['p'][1::2]) / (len(f['p']) // 2), d['outline'])]
    end = max(s for _, s in d['pace']['all']) if d['pace']['all'] else 0
    oil_in = sum(f['ha'] for f in ins if f['c'] == 'oilseed')
    grain_in = sum(f['ha'] for f in ins if f['c'] == 'grain')
    known = t['known_ha'] or 1
    top = d['accounts'][0] if d['accounts'] else {'name': '—', 'ha': 0}
    return dict(
        fields=t['fields'], ha=thousands(t['ha']), linked_ha=thousands(t['linked_ha']),
        private_ha=thousands(t['private_ha']), inside_fields=len(ins), inside_ha=thousands(sum(f['ha'] for f in ins)),
        linked_pct=round(100 * t['linked_ha'] / (t['ha'] or 1)), group_pct=round(100 * t['group_ha'] / known),
        # counted as registered companies: a row joining two registrations of one farm counts twice
        group_cos=sum(a.get('n', 1) for a in d['accounts'] if a['group']), companies=sum(a.get('n', 1) for a in d['accounts']),
        squares=len(d['squares']), pace_end=round(100 * end),
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
            values = tokens(d)
            for lang in cfg['languages']:
                content = json.load(open(f"{cfg['content_dir']}/{name}.{lang}.json", encoding='utf-8'))
                json.dump(fill_all(content, values, lang), open(f'{DIST}/content/{name}.{lang}.json', 'w', encoding='utf-8'),
                          ensure_ascii=False)
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
    for f in sorted(os.listdir(f'{DIST}/data')):
        print(f'  data/{f}: {os.path.getsize(f"{DIST}/data/{f}") / 1e6:.2f} MB')
    print(f'site/dist: {total / 1e6:.1f} MB in all')


if __name__ == '__main__':
    main()
