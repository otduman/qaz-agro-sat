"""Development look (dev set only): radar values around the optical cut. python explore.py"""
import csv, math, collections, datetime as dt, statistics as st
key = {r['code']: r for r in csv.DictReader(open('dev-key.csv'))}
rows = [r for r in csv.DictReader(open('dev-series.csv')) if float(r['vv']) > 0 and float(r['vh']) > 0]
db = lambda x: 10 * math.log10(float(x))
by_img = collections.defaultdict(list)
for r in rows:
    by_img[(r['date'], r['orbit'])].append(r)
med = {k: (st.median(db(x['vv']) for x in v), st.median(db(x['vh']) for x in v)) for k, v in by_img.items()}
print('passes', len(med), sorted(k[0][5:] + k[1] for k in med))
D = lambda s: dt.date.fromisoformat(s)
bins = collections.defaultdict(lambda: [[] for _ in range(6)])
for r in rows:
    k = key[r['code']]
    t = (D(r['date']) - D(k['cut_middle'])).days
    b = math.floor(t / 6)
    if -6 <= b <= 5:
        m = med[(r['date'], r['orbit'])]
        vv, vh = db(r['vv']), db(r['vh'])
        x = bins[b]
        for i, v in enumerate((vv - m[0], vh - m[1], vh - vv - (m[1] - m[0]), vv, vh, vh - vv)):
            x[i].append(v)
print('days since cut   n   VVn    VHn  RATIOn |    VV     VH  RATIO')
for b in sorted(bins):
    x = bins[b]
    print(f'{b*6:+4d}..{b*6+5:+3d} {len(x[0]):4d} ' + ' '.join(f'{st.mean(v):+6.2f}' for v in x[:3]) + ' | ' + ' '.join(f'{st.mean(v):6.2f}' for v in x[3:]))
