"""The four tests frozen in docs/superpowers/notes/2026-11-10-relative-cut.md, on the harvest-<year>-v2.csv tables
from relative_run.py. Run from the repository root: python data/tayynsha/tools/relative_tests.py
Throwaway experiment code."""
import csv
import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from holder_pace import km  # noqa: E402

CUT = ('dated', 'cut in a cloud gap')
T = 'data/tayynsha'
K = 'data/kokshetau'


def table(path, key='field'):
    return {r[key]: r for r in csv.DictReader(open(path, encoding='utf-8'))}


def v1_only(r):
    """The row as v1 alone would have it: a v2 date means v1 had no cut."""
    return dict(r, status='v1 no cut', cut_from='', cut_to='', cut_middle='') if r['rule'] == 'v2' else r


def score(reading, r):
    fired = r['status'] in CUT
    if reading['reading'] != 'cut seen':
        return 'fired' if fired else 'quiet'
    if not fired:
        return 'missed'
    a, b = r['cut_from'], r['cut_to']
    if not (b < reading['from'] or a > reading['to']):
        return 'inside'
    if a == reading['to']:
        return 'one gap late'
    return 'one gap early' if b == reading['from'] else 'elsewhere'


def test1():
    tally = {}
    for year in ('2025', '2026'):
        rows = table(f'{K}/harvest-{year}-v2.csv')
        for rd in csv.DictReader(open(f'experiment/kokshetau/photo-readings-{year}.csv', encoding='utf-8')):
            if rd['reading'] not in ('cut seen', 'no harvest seen') or rd['field'] not in rows:
                continue
            r = rows[rd['field']]
            for name, row in (('v1', v1_only(r)), ('v1+v2', r)):
                s = score(rd, row)
                tally.setdefault(name, {}).setdefault(s, 0)
                tally[name][s] += 1
    print('Test 1, Kokshetau hand reads 2025-2026:')
    res = {}
    for name, t in tally.items():
        good = sum(t.get(k, 0) for k in ('inside', 'one gap late', 'one gap early'))
        res[name] = (good, t.get('fired', 0))
        print(f'  {name}: within one photo {good} of 22, fired on no-harvest {t.get("fired", 0)} of 10; {t}')
    ok = res['v1+v2'][0] >= res['v1'][0] and res['v1+v2'][1] <= res['v1'][1] + 1
    print(f'  {"PASS" if ok else "FAIL"}')
    return ok


def test2():
    print('Test 2, same field in two squares:')
    ok = True
    for year in ('2026', '2025'):
        fields = []
        for sq in sorted(os.listdir(T)):
            p = f'{T}/{sq}/harvest-{year}-v2.csv'
            if not os.path.isfile(p):
                continue
            info = table(f'{T}/{sq}/series-{year}/fields-{year}.csv', 'id')
            for f, r in table(p).items():
                i = info[f]
                fields.append((sq, float(i['centroid_lat']), float(i['centroid_lon']), float(r['area_ha']), r))
        pairs = {'v1': [0, 0], 'v2 involved': [0, 0]}
        for i in range(len(fields)):
            a = fields[i]
            for j in range(i + 1, len(fields)):
                b = fields[j]
                if a[0] == b[0] or abs(a[1] - b[1]) > 0.002 or km(a[1], a[2], b[1], b[2]) > 0.150 \
                        or abs(a[3] - b[3]) > 0.25 * max(a[3], b[3]):
                    continue
                ra, rb = a[4], b[4]
                if ra['status'] not in CUT or rb['status'] not in CUT:
                    continue
                key = 'v2 involved' if 'v2' in (ra['rule'], rb['rule']) else 'v1'
                pairs[key][0] += 1
                if not (ra['cut_to'] < rb['cut_from'] or rb['cut_to'] < ra['cut_from']):
                    pairs[key][1] += 1
        allp = pairs['v1'][0] + pairs['v2 involved'][0]
        agree = pairs['v1'][1] + pairs['v2 involved'][1]
        print(f'  {year}: v1 pairs {pairs["v1"][1]} of {pairs["v1"][0]} agree; pairs with v2 {pairs["v2 involved"][1]} '
              f'of {pairs["v2 involved"][0]}; all {agree} of {allp} ({agree / allp:.0%})')
        ok &= agree >= 0.8 * allp
    print(f'  {"PASS" if ok else "FAIL"}')
    return ok


def test3():
    n = {'v1': 0, 'v1+v2': 0}
    total = 0
    for base in [f'{T}/{sq}' for sq in os.listdir(T) if sq.startswith('E')] + [K]:
        for year in ('2023', '2024', '2025', '2026'):
            p = f'{base}/harvest-{year}-v2.csv'
            if not os.path.isfile(p):
                continue
            for r in table(p).values():
                if r['land'] != 'fallow' or r['status'] == 'no crop seen':
                    continue
                total += 1
                n['v1+v2'] += r['status'] in CUT
                n['v1'] += v1_only(r)['status'] in CUT
    print(f'Test 3, fallow-class regions that pass the crop gate ({total}): cut by v1 {n["v1"]} '
          f'({n["v1"] / total:.1%}), by v1+v2 {n["v1+v2"]} ({n["v1+v2"] / total:.1%})')
    ok = n['v1+v2'] / total - n['v1'] / total <= 0.05
    print(f'  {"PASS" if ok else "FAIL"}')
    return ok


def test4():
    year = '2026'
    kept = {(r['square'], r['field']) for r in csv.DictReader(open(f'{T}/district-harvest-{year}.csv', encoding='utf-8'))
            if r['land'] == 'field'}
    total = {'v1': 0.0, 'v1+v2': 0.0}
    area = 0.0
    for sq in os.listdir(T):
        p = f'{T}/{sq}/crops-{year}.csv'
        if not os.path.isfile(p):
            continue
        rows = table(f'{T}/{sq}/harvest-{year}-v2.csv')
        for c in csv.DictReader(open(p, encoding='utf-8')):
            if c['group'] != 'short season' or (sq, c['field']) not in kept:
                continue
            r = rows[c['field']]
            ha = float(r['area_ha'])
            area += ha
            for name, row in (('v1', v1_only(r)), ('v1+v2', r)):
                if row['status'] in CUT and row['cut_middle'] <= f'{year}-09-05':
                    total[name] += ha
    a, b = total['v1'] / area, total['v1+v2'] / area
    print(f'Test 4, short-season area cut by 5 Sep 2026 ({area:,.0f} ha): v1 {a:.1%}, v1+v2 {b:.1%}, official 72%')
    ok = abs(b - 0.72) < abs(a - 0.72) and b <= 0.82
    print(f'  {"PASS" if ok else "FAIL"}')
    return ok


def coverage():
    print('Reported: area dated or cut in a gap, v1 -> v1+v2 (all field land in the joined table)')
    for year in ('2026', '2025'):
        kept = {(r['square'], r['field']) for r in
                csv.DictReader(open(f'{T}/district-harvest-{year}.csv', encoding='utf-8')) if r['land'] == 'field'}
        groups = {}
        for sq in os.listdir(T):
            p = f'{T}/{sq}/crops-{year}.csv'
            if os.path.isfile(p):
                for c in csv.DictReader(open(p, encoding='utf-8')):
                    groups[(sq, c['field'])] = c['group']
        by = {}
        cache = {}
        for sq, f in kept:
            if sq not in cache:
                cache[sq] = table(f'{T}/{sq}/harvest-{year}-v2.csv')
            r = cache[sq][f]
            g = groups.get((sq, f), '?')
            d = by.setdefault(g, [0.0, 0.0, 0.0])
            ha = float(r['area_ha'])
            d[0] += ha
            d[1] += ha if v1_only(r)['status'] in CUT else 0
            d[2] += ha if r['status'] in CUT else 0
        for g, (ha, a, b) in sorted(by.items(), key=lambda kv: -kv[1][0]):
            print(f'  {year} {g:<16} {ha:>9,.0f} ha: {a / ha:.0%} -> {b / ha:.0%}')


if __name__ == '__main__':
    results = [test1(), test2(), test3(), test4()]
    coverage()
    print('all pass' if all(results) else 'not all pass')
