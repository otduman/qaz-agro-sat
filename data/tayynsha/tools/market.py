"""Combine need per land holder, Tayynsha: each holder's field area by crop group, the combines that area keeps
busy at the district's own load, and the yearly fleet renewal at the national rate.

Load: about 530,000 ha harvested by combine in 2026 (grain and pulses about 400,000, oilseeds 130,000; MTRK,
25 May 2026) with 756 combines at work (qz-media.kz, 5 September 2026): about 701 ha per combine.
Renewal: 6.5% of the machine fleet replaced in 2025, 8% expected for 2026 (Ministry of Agriculture).

A holder's area is only what lies in the squares whose plot cards were read, so it is a floor for holders that farm
beyond them. Run from the repository root:
python data/tayynsha/tools/market.py data/tayynsha 2026 data/tayynsha/holders.csv
Writes <district>/market-<year>.csv (names: stays in data/, not in git, not published). Throwaway experiment code.
"""
import csv
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

DISTRICT_COMBINE_HA = 530_000
DISTRICT_COMBINES = 756
HA_PER_COMBINE = round(DISTRICT_COMBINE_HA / DISTRICT_COMBINES)
RENEWAL_LOW, RENEWAL_HIGH = 0.065, 0.08
GRAIN = ('short season',)
OILSEED = ('long season', 'yellow flowers')


def summarise(rows):
    """rows: (holder, type, crop group, ha). One dict per holder, largest field area first."""
    by = defaultdict(lambda: defaultdict(float))
    types = {}
    for holder, kind, group, ha in rows:
        types[holder] = kind
        key = 'grain' if group in GRAIN else 'oilseed' if group in OILSEED else 'other'
        by[holder][key] += ha
    out = []
    for h, d in by.items():
        combine_ha = d['grain'] + d['oilseed']
        combines = combine_ha / HA_PER_COMBINE
        out.append(dict(holder=h, type=types[h], field_ha=round(d['grain'] + d['oilseed'] + d['other']),
                        grain_ha=round(d['grain']), oilseed_ha=round(d['oilseed']), other_ha=round(d['other']),
                        combines=round(combines, 2), buys_per_year_low=round(combines * RENEWAL_LOW, 2),
                        buys_per_year_high=round(combines * RENEWAL_HIGH, 2)))
    out.sort(key=lambda r: -r['field_ha'])
    return out


def main():
    import holder_pace
    district, year, holders_csv = sys.argv[1], sys.argv[2], sys.argv[3]
    holders, types = {}, {}
    for r in csv.DictReader(open(holders_csv, encoding='utf-8-sig')):
        holders[(r['square'], r['field'])] = r['holder']
        types[r['holder']] = r['holder_type']
    table = [r for r in csv.DictReader(open(f'{district}/district-harvest-{year}.csv', encoding='utf-8'))
             if r['land'] == 'field']
    owner = holder_pace.district_owners(district, 2026, holders, int(year), table)
    groups = {}
    for sq in os.listdir(district):
        p = f'{district}/{sq}/crops-{year}.csv'
        if os.path.isfile(p):
            for c in csv.DictReader(open(p, encoding='utf-8')):
                groups[(sq, c['field'])] = c['group']
    rows = []
    for r in table:
        h = owner.get((r['square'], r['field']))
        if h:
            rows.append((h, types.get(h, ''), groups.get((r['square'], r['field']), 'unclear'), float(r['area_ha'])))
    out = summarise(rows)
    with open(f'{district}/market-{year}.csv', 'w', encoding='utf-8', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)
    total = sum(r['field_ha'] for r in out)
    comb = sum(r['combines'] for r in out)
    print(f'{len(out)} holders, {total:,} ha, {comb:.0f} combines at {HA_PER_COMBINE} ha each, '
          f'{comb * RENEWAL_LOW:.1f}-{comb * RENEWAL_HIGH:.1f} bought a year')
    by_type = defaultdict(lambda: [0, 0, 0.0])
    for r in out:
        t = by_type[r['type']]
        t[0] += 1
        t[1] += r['field_ha']
        t[2] += r['combines']
    for k, (n, ha, c) in sorted(by_type.items(), key=lambda kv: -kv[1][1]):
        print(f'  {k:<14} {n:>3} holders {ha:>8,} ha {c:6.1f} combines')
    print(f'District: {DISTRICT_COMBINES} combines, {DISTRICT_COMBINES * RENEWAL_LOW:.0f}-'
          f'{DISTRICT_COMBINES * RENEWAL_HIGH:.0f} bought a year at {RENEWAL_LOW:.1%}-{RENEWAL_HIGH:.0%} renewal')


if __name__ == '__main__':
    main()
