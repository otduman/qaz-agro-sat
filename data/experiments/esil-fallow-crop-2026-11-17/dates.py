"""Harvest dates (rule v2, as harvest_v2.py) for the fallow regions made crop: adds status/cut columns to
DISTRICT_DIR/fallow-crop-2026.csv. Ripening = the square's median ripening of its grain fields. python dates.py DIR"""
import csv, os, sys, statistics as st
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import harvest_v2 as H
ddir = sys.argv[1].rstrip('/')
p = f'{ddir}/fallow-crop-2026.csv'
rows = list(csv.DictReader(open(p, encoding='utf-8')))
by_sq = {}
for r in rows: by_sq.setdefault(r['square'], []).append(r)
for sq, items in by_sq.items():
    d = f'{ddir}/{sq}'
    ser = {}
    for r in csv.DictReader(open(f'{d}/series-2026/series-2026.csv', encoding='utf-8')):
        ser.setdefault(r['field'], []).append(r)
    rip = [r['ripening'] for r in csv.DictReader(open(f'{d}/crops-2026-v2.csv', encoding='utf-8')) if r.get('ripening')]
    ripening = sorted(rip)[len(rip) // 2] if rip else None
    det = H.detrend(ser)
    for r in items:
        fid = f"2026-F{int(r['region']):04d}"
        x = H.date_cut(det.get(fid, []), 2026, ripening) if fid in det else dict(status='no cut found', cut_from='', cut_to='', cut_middle='')
        r.update(status=x['status'], cut_from=x['cut_from'], cut_to=x['cut_to'], cut_middle=x['cut_middle'])
with open(p, 'w', newline='', encoding='utf-8') as fh:
    w = csv.DictWriter(fh, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
import collections
print(collections.Counter(r['status'] for r in rows))
