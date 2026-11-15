"""District crop-group split per season, from the joined harvest table (each field once) and each square's
crops-<year>.csv, with the tests set in docs/superpowers/notes/2026-11-10-crop-groups.md.

Run from the repository root:
python data/tayynsha/tools/crop_summary.py data/tayynsha 2026 [2025 ...]
Prints the split and writes <district>/crop-summary-<year>.txt. Throwaway experiment code.
"""
import csv
import datetime as dt
import os
import sys
from collections import defaultdict

GROUPS = ['short season', 'long season', 'yellow flowers', 'perennial grass', 'unclear', 'no crop seen', 'missing']
CLASSED = ('short season', 'long season', 'yellow flowers', 'perennial grass')


def main():
    district = sys.argv[1]
    for year in sys.argv[2:]:
        crops = {}
        for sq in os.listdir(district):
            path = f'{district}/{sq}/crops-{year}.csv'
            if os.path.isfile(path):
                for r in csv.DictReader(open(path, encoding='utf-8')):
                    crops[(sq, r['field'])] = r['group']
        table = [r for r in csv.DictReader(open(f'{district}/district-harvest-{year}.csv', encoding='utf-8'))
                 if r['land'] == 'field']
        area = defaultdict(float)
        cut = defaultdict(list)
        for r in table:
            g = crops.get((r['square'], r['field']), 'missing')
            area[g] += float(r['area_ha'])
            if r['status'] == 'dated':
                cut[g].append((dt.date.fromisoformat(r['cut_middle']), float(r['area_ha'])))
        total = sum(area.values())
        classed = sum(area[g] for g in CLASSED)
        lines = [f'Tayynsha {year}: {len(table)} fields, {total:,.0f} ha', '',
                 'group            ha        share of all   share of classed   dated ha   dated cut by 7 Sep']
        for g in GROUPS:
            c = cut.get(g, [])
            ca = sum(a for _, a in c)
            by7 = sum(a for day, a in c if day <= dt.date(int(year), 9, 7))
            lines.append(f'{g:<16} {area[g]:>9,.0f}   {area[g] / total:>6.1%}        '
                         f'{(area[g] / classed if g in CLASSED else 0):>6.1%}           {ca:>8,.0f}   '
                         f'{(by7 / ca if ca else 0):>6.0%}')
        oil = (area['long season'] + area['yellow flowers']) / classed
        lines += ['', f'oilseeds (long season + yellow flowers) of classed area: {oil:.1%}',
                  f'grain (short season) of classed area: {area["short season"] / classed:.1%}',
                  f'perennial grass of classed area: {area["perennial grass"] / classed:.1%}']
        if year == '2026':
            lines.append(f'2026 volume test (oilseeds within 16-30%, official 23%): '
                         f'{"PASS" if 0.16 <= oil <= 0.30 else "FAIL"}')
        text = '\n'.join(lines)
        print(text + '\n')
        open(f'{district}/crop-summary-{year}.txt', 'w', encoding='utf-8').write(text + '\n')


if __name__ == '__main__':
    main()
