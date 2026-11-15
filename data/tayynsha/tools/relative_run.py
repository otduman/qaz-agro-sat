"""Run cut rule v1 + v2 (relative.py) on one square-season's series. Writes a harvest-like table with a `rule`
column saying which rule dated the field. Run from the repository root:
python data/tayynsha/tools/relative_run.py data/tayynsha/E560N5960/series-2026 2026 data/tayynsha/E560N5960/harvest-2026-v2.csv
Throwaway experiment code.
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relative  # noqa: E402
import rule  # noqa: E402


def run(series_folder, year, out_csv):
    info, rows = rule.load(series_folder, year)
    crop = relative.cropped(rows)
    out = relative.date_fields(rows, crop)
    with open(out_csv, 'w', encoding='utf-8', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['field', 'land', 'area_ha', 'status', 'cut_from', 'cut_to', 'cut_middle', 'window_days', 'rise',
                    'rule'])
        for k in sorted(out):
            status, a, b, rise, which = out[k]
            mid = a + (b - a) / 2 if a else None
            w.writerow([k, info[k]['land'], info[k]['area_ha'], status, a or '', b or '', mid or '',
                        (b - a).days if a else '', '' if rise is None else f'{rise * 100:.2f}', which])
    return out


if __name__ == '__main__':
    out = run(sys.argv[1], sys.argv[2], sys.argv[3])
    n = {}
    for v in out.values():
        n[(v[0], v[4])] = n.get((v[0], v[4]), 0) + 1
    print(sys.argv[3], dict(sorted(n.items())))
