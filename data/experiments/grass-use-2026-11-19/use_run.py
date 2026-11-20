"""Grass by use as adopted (NOTE.md): every square of a district with grass pieces -> <district>/grass-use-2026.csv
(square, region, cls = oldfield | steppe), rows of all classes kept in grass-use-detail-<district>.csv here.
python use_run.py DISTRICT_DIR"""
import csv, os, sys
import classes as C
d = sys.argv[1].rstrip('/')
name = os.path.basename(d)
done = {}
detail = os.path.join(os.path.dirname(os.path.abspath(__file__)), f'grass-use-detail-{name}.csv')
if os.path.isfile(detail):
    for r in csv.DictReader(open(detail)):
        done.setdefault(r['square'], []).append(r)
squares = sorted({r['square'] for r in csv.DictReader(open(f'{d}/grass-2026.csv'))})
for sq in squares:
    if sq in done:
        continue
    try:
        rows, _, _ = C.classify(sq)
    except Exception as e:      # a square without a season stack or places date
        print(sq, 'skipped:', e, flush=True)
        rows = []
    done[sq] = rows or [dict(square=sq, region='', ha='', near='', fill='', cls='')]
    with open(detail, 'w', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'region', 'ha', 'near', 'fill', 'cls'])
        w.writeheader()
        for s in sorted(done):
            w.writerows(done[s])
    print(sq, len(rows), flush=True)
with open(f'{d}/grass-use-2026.csv', 'w', newline='') as fh:
    w = csv.writer(fh)
    w.writerow(['square', 'region', 'cls'])
    for s in sorted(done):
        for r in done[s]:
            if r['region']:
                w.writerow([s, r['region'], 'oldfield' if r['cls'] == 'oldfield' else 'steppe'])
print('done', d)
