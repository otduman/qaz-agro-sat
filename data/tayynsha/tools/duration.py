"""Harvest duration per holder: does a holder take longer to cut its land than its neighbours would?

For each holder and season with at least MIN_DATED dated fields: the span in days between the dates by which 10% and
90% of its dated field area was cut (each field at its cut window's middle). Against chance: DRAWS random groups of
the same number of fields, drawn from the dated fields of other holders (or none) within NEIGHBOUR_KM of the holder's
area-weighted centre, give the span such a group shows by chance; `chance` is the share of them as long or longer.
A small share means a harvest longer than its neighbours explain. With many holders, about a tenth fall under 0.1 by
chance alone, so only what repeats across seasons counts.

Fields with no cut found are left out, so a holder whose slow cuts the rule misses looks faster than it is.

Run from the repository root:
python data/tayynsha/tools/duration.py data/tayynsha 2026 data/tayynsha/holders.csv 2026 2025
Writes <district>/duration.csv and prints a summary. Names stay in data/ (not in git, not published).
"""
import csv
import datetime as dt
import math
import os
import random
import statistics as st
import sys
from collections import defaultdict

MIN_DATED = 5
DRAWS = 1000
NEIGHBOUR_KM = 20


def _date_at(fields, share):
    items = sorted(fields)
    total = sum(a for _, a in items)
    run = 0.0
    for day, area in items:
        run += area
        if run >= share * total - 1e-9:
            return day
    return items[-1][0]


def span(fields, lo=0.1, hi=0.9):
    """fields: (day, hectares). Days between the dates by which `lo` and `hi` of the area was cut."""
    return _date_at(fields, hi) - _date_at(fields, lo)


def compare(group, pool, draws, rng):
    """(share of random same-size groups from `pool` with a span as long or longer, their median span);
    (None, None) when the pool has fewer than twice the group's fields."""
    n = len(group)
    if len(pool) < 2 * n:
        return None, None
    actual = span(group)
    spans = [span(rng.sample(pool, n)) for _ in range(draws)]
    return sum(s >= actual for s in spans) / draws, st.median(spans)


def chance(group, pool, draws, rng):
    return compare(group, pool, draws, rng)[0]


def correlation(pairs):
    if len(pairs) < 3:
        return None
    x = [a for a, _ in pairs]
    y = [b for _, b in pairs]
    mx, my = st.mean(x), st.mean(y)
    sx = sum((a - mx) ** 2 for a in x)
    sy = sum((b - my) ** 2 for b in y)
    if sx == 0 or sy == 0:
        return None
    return sum((a - mx) * (b - my) for a, b in zip(x, y)) / math.sqrt(sx * sy)


def km(lat1, lon1, lat2, lon2):
    return math.hypot((lat1 - lat2) * 111.2, (lon1 - lon2) * 111.2 * math.cos(math.radians((lat1 + lat2) / 2)))


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import lead_list
    district, ref_year, holders_csv = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    years = [int(y) for y in sys.argv[4:]]
    holders, types = {}, {}
    for r in csv.DictReader(open(holders_csv, encoding='utf-8-sig')):
        holders[(r['square'], r['field'])] = r['holder']
        types[r['holder']] = r['holder_type']
    rng = random.Random(2026)
    rows = []
    for year in years:
        owner = lead_list.owners(district, ref_year, holders, year)
        table = list(csv.DictReader(open(f'{district}/district-harvest-{year}.csv', encoding='utf-8')))
        fields = []
        for r in table:
            if r['land'] != 'field':
                continue
            fields.append(dict(holder=owner.get((r['square'], r['field'])), ha=float(r['area_ha']),
                               day=dt.date.fromisoformat(r['cut_middle']).toordinal() if r['status'] == 'dated' else None,
                               lat=float(r['centroid_lat']), lon=float(r['centroid_lon'])))
        by = defaultdict(list)
        for f in fields:
            if f['holder']:
                by[f['holder']].append(f)
        for h, fs in by.items():
            dated = [f for f in fs if f['day'] is not None]
            area = sum(f['ha'] for f in fs)
            row = dict(holder=h, type=types.get(h, ''), year=year, fields=len(fs), area_ha=round(area),
                       dated_fields=len(dated), dated_ha=round(sum(f['ha'] for f in dated)),
                       span_days='', neighbour_span_days='', chance='')
            if len(dated) >= MIN_DATED:
                lat = sum(f['lat'] * f['ha'] for f in fs) / area
                lon = sum(f['lon'] * f['ha'] for f in fs) / area
                pool = [(f['day'], f['ha']) for f in fields if f['day'] is not None and f['holder'] != h
                        and km(lat, lon, f['lat'], f['lon']) <= NEIGHBOUR_KM]
                group = [(f['day'], f['ha']) for f in dated]
                share, median = compare(group, pool, DRAWS, rng)
                row['span_days'] = span(group)
                if share is not None:
                    row['neighbour_span_days'] = round(median)
                    row['chance'] = round(share, 3)
            rows.append(row)

    with open(f'{district}/duration.csv', 'w', encoding='utf-8', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    tested = [r for r in rows if r['chance'] != '']
    for year in years:
        t = [r for r in tested if r['year'] == year]
        low = [r for r in t if r['chance'] < 0.1]
        print(f'{year}: {len(t)} holders tested, {len(low)} longer than chance at 0.1 (about {0.1 * len(t):.1f} expected '
              f'by chance alone)')
    if len(years) >= 2:
        a, b = years[0], years[1]
        by = defaultdict(dict)
        for r in tested:
            by[r['holder']][r['year']] = r
        both = {h: d for h, d in by.items() if a in d and b in d}
        rel = [(d[a]['span_days'] - d[a]['neighbour_span_days'], d[b]['span_days'] - d[b]['neighbour_span_days'])
               for d in both.values()]
        c = correlation(rel)
        print(f'tested in both {a} and {b}: {len(both)}; correlation of (span - neighbours) between years: '
              f'{"n/a" if c is None else round(c, 2)}')
        slow = [h for h, d in both.items() if d[a]['chance'] < 0.2 and d[b]['chance'] < 0.2]
        print(f'longer than neighbours in both (chance < 0.2 each year; about {0.04 * len(both):.1f} expected by '
              f'chance): {len(slow)}')
        for h in slow:
            d = both[h]
            print(f'  {h}: {d[a]["area_ha"]} ha; {a}: {d[a]["span_days"]} vs {d[a]["neighbour_span_days"]} days '
                  f'(chance {d[a]["chance"]}); {b}: {d[b]["span_days"]} vs {d[b]["neighbour_span_days"]} '
                  f'(chance {d[b]["chance"]})')


if __name__ == '__main__':
    main()
