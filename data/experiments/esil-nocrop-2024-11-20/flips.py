"""Dry run of crop seen v2 (NOTE.md) over every district-season table: which 'no crop seen' fields it would make crop
fields. -> flips.csv (district, year, square, field, ha, flip), counts printed. Writes nothing else. python flips.py"""
import csv
import os
import sys

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import harvest_v2 as hv  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
DATA = 'C:/Programming Projects/qaz-agro-sat/data/'
SEASONS = [('tayynsha', 2026), ('tayynsha', 2025), ('sandyktau', 2026), ('sandyktau', 2025),
           ('esil-akmola', 2026), ('esil-akmola', 2025), ('esil-akmola', 2024)]


def main():
    out = []
    for d, y in SEASONS:
        rows = [r for r in csv.DictReader(open(f'{DATA}{d}/district-harvest-{y}.csv', encoding='utf-8'))
                if r['status'] == 'no crop seen']
        series = {}
        for sq in sorted({r['square'] for r in rows}):
            ser = {}
            for s in csv.DictReader(open(f'{DATA}{d}/{sq}/series-{y}/series-{y}.csv', encoding='utf-8')):
                ser.setdefault(s['field'], []).append(s)
            series[sq] = ser
        n = ha = fn = fha = 0
        for r in rows:
            flip = hv.crop_seen(series[r['square']].get(r['field'], []))
            out.append([d, y, r['square'], r['field'], r['area_ha'], int(flip)])
            n += 1
            ha += float(r['area_ha'])
            if flip:
                fn += 1
                fha += float(r['area_ha'])
        print(f'{d} {y}: {n} no-crop fields {ha:,.0f} ha -> {fn} flip ({fha:,.0f} ha)', flush=True)
    with open(HERE + 'flips.csv', 'w', newline='') as fh:
        csv.writer(fh).writerows([['district', 'year', 'square', 'field', 'ha', 'flip']] + out)


if __name__ == '__main__':
    main()
