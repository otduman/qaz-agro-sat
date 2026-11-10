"""Screen positions of a square's largest fields for the current map view. Usage:
python screen.py SQUARE YEAR E0 N0 SCALE [SHARE]   (E0, N0 = map coordinates at screen point (700, 300))"""
import csv, sys
sq, year, e0, n0, s = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])
share = float(sys.argv[6]) if len(sys.argv) > 6 else 0.8
rows = list(csv.DictReader(open(f'{sq}/owners-{year}.csv', encoding='utf-8-sig')))
done = set()
try:
    done = {r['field'] for r in csv.DictReader(open(f'{sq}/plot-clicks-{year}.csv', encoding='utf-8'))}
except FileNotFoundError:
    pass
ok, out = [], []
prev = 0.0
for r in rows:
    if r['land'] != 'field':
        continue
    if prev >= share:
        break
    prev = float(r['field_area_share_so_far'])
    if r['field'] in done:
        continue
    x = round(700 + (float(r['map_east']) - e0) / s)
    y = round(300 + (n0 - float(r['map_north'])) / s)
    bad = not (300 <= x <= 1430 and 115 <= y <= 645) or (300 <= x <= 730 and y <= 245) or (x >= 1360 and y >= 560)
    (out if bad else ok).append(f"{r['field'][5:]}:{x},{y}")
print('here', len(ok), ' '.join(ok))
print('elsewhere', len(out), ' '.join(out))
