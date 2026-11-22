"""Development (dev only): field-and-orbit anomalies, so the three orbits' geometry offsets drop out."""
import collections, statistics as st, itertools
import dev_rules as R

key, ser = R.load('dev-key.csv', 'dev-series.csv')
for code, s in ser.items():
    by_o = collections.defaultdict(list)
    for x in s:
        by_o[x['orbit']].append(x)
    for o, xs in by_o.items():
        for band in ('vv', 'vh', 'r'):
            m = st.mean(x[band] for x in xs)
            for x in xs:
                x[band + 'a'] = x[band] - m
# subtract the pass median of the anomalies (weather, moisture)
by_pass = collections.defaultdict(list)
for s in ser.values():
    for x in s:
        by_pass[(x['date'], x['orbit'])].append(x)
for xs in by_pass.values():
    for band in ('vva', 'vha', 'ra'):
        m = st.median(x[band] for x in xs)
        for x in xs:
            x[band + 'n'] = x[band] - m
for band, k, sign in itertools.product(('vha', 'vhan', 'ra', 'ran', 'vva', 'vvan'), (1, 2, 3), (-1, 1)):
    made, agr = R.score(key, ser, band=band, k=k, first='2026-07-25', last='2026-10-15', sign=sign)
    print(f'{band:5s} k={k} sign={sign:+d}: calls {made:.0%}, agree {agr:.0%}')
