"""Development (dev set only): candidate step rules and their agreement with the optical cut. python dev_rules.py"""
import csv, math, collections, datetime as dt, statistics as st, itertools
D = dt.date.fromisoformat
db = lambda x: 10 * math.log10(float(x))


def load(keyf, serf):
    key = {r['code']: r for r in csv.DictReader(open(keyf))}
    rows = [r for r in csv.DictReader(open(serf)) if float(r['vv']) > 0 and float(r['vh']) > 0]
    by_img = collections.defaultdict(list)
    for r in rows:
        by_img[(r['date'], r['orbit'])].append(r)
    med = {k: (st.median(db(x['vv']) for x in v), st.median(db(x['vh']) for x in v)) for k, v in by_img.items()}
    ser = collections.defaultdict(list)
    for r in rows:
        m = med[(r['date'], r['orbit'])]
        vv, vh = db(r['vv']), db(r['vh'])
        ser[r['code']].append(dict(date=r['date'], orbit=r['orbit'], vv=vv, vh=vh, vvn=vv - m[0], vhn=vh - m[1],
                                   rn=(vh - vv) - (m[1] - m[0]), r=vh - vv))
    for v in ser.values():
        v.sort(key=lambda x: x['date'])
    return key, ser


def step_call(s, band, k, first, last, sign=-1, min_step=0.0):
    """Split between passes i-1 and i maximising sign*(mean of k after - mean of k before); returns (from, to, size)."""
    best = None
    for i in range(k, len(s) - k + 1):
        if not (first <= s[i]['date'] <= last):
            continue
        before = st.mean(x[band] for x in s[i - k:i])
        after = st.mean(x[band] for x in s[i:i + k])
        size = sign * (after - before)
        if best is None or size > best[2]:
            best = (s[i - 1]['date'], s[i]['date'], size)
    if best is None or best[2] < min_step:
        return None
    return best


def agree(call, mid, slack=3):
    a, b = D(call[0]) - dt.timedelta(days=slack), D(call[1]) + dt.timedelta(days=slack)
    return a < D(mid) <= b


def score(key, ser, **kw):
    calls = ok = 0
    for code, k in key.items():
        c = step_call(ser.get(code, []), **kw)
        if c:
            calls += 1
            ok += agree(c, k['cut_middle'])
    return calls / len(key), (ok / calls if calls else 0)


if __name__ == '__main__':
    key, ser = load('dev-key.csv', 'dev-series.csv')
    for band, k, sign in itertools.product(('vhn', 'rn', 'vvn', 'vh', 'r'), (1, 2, 3), (-1, 1)):
        made, agr = score(key, ser, band=band, k=k, first='2026-07-25', last='2026-10-15', sign=sign)
        print(f'{band:4s} k={k} sign={sign:+d}: calls {made:.0%}, agree {agr:.0%}')
