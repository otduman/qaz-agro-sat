"""Harvest dates, rule v2 (owner's harvest key, 2026-11-15; frozen in data/experiments/harvest-v2-2026-11-15).

v1 (the jar's `harvest`) looks for the biggest short-wave infrared rise after the field is ripe (NDVI under 0.4). It
found no cut on 40% of the field area, though the owner saw 13 of 14 such fields cut: cuts that darken the field,
a ripe gate that comes too late, and hazy single dates.

v2, per field, on Sentinel-2 dates only (Landsat reads SWIR about 2 points higher, which fakes steps) with clear share
at least CLEAR, from the field's ripening date (crop-group table) less EARLY days to 31 October:
  - every value is taken relative to that date's median over the square's fields (detrend), so a hazy date or a
    change the whole square makes at once is not a cut;
  - at every gap between two dates, each feature (SWIR1, SWIR2, tillage index, visible brightness, NDVI and line
    strength, the last two at half weight) steps by the median of the K dates after less the K dates before, in
    units of its day-to-day noise; the score is the root sum of squares, rise or drop alike;
  - the step across the gap itself must carry at least ABRUPT of it (ripening and swathing are gradual), and the
    field must already be ripe before it (NDVI at most RIPE);
  - the cut is the FIRST gap scoring at least SCORE (cuts come before autumn ploughing, which is often bigger);
    a gap wider than WIDE days is 'cut in a cloud gap'.
The field median moves once most of the field has changed, so a field cut in parts gets the date most of it was cut.
Check: Tayynsha 2026 grain area dated 64% -> 84%, cut by 7 September 58% -> 68% (official 72%).

python harvest_v2.py SQUARE_DIR YEAR   rewrites SQUARE_DIR/harvest-YEAR/harvest-YEAR.csv (the jar's own output kept as
                                       harvest-YEAR-v1.csv beside it; re-runs start from that copy)
"""
import csv
import math
import os
import shutil
import statistics as st
import sys
from datetime import date

CLEAR = 0.85
K = 3
EARLY = 10
SCORE = 4.0
ABRUPT = 0.5
WIDE = 8
RIPE = 0.45
END = '10-31'
FEATS = ('b11', 'b12', 'ndti', 'brightness', 'ndvi_median', 'line_strength')
NOISE = {'b11': 0.014, 'b12': 0.014, 'ndti': 0.024, 'brightness': 0.009, 'ndvi_median': 0.018, 'line_strength': 0.046}
WEIGHT = {'b11': 1.0, 'b12': 1.0, 'ndti': 1.0, 'brightness': 1.0, 'ndvi_median': 0.5, 'line_strength': 0.5}
REDATE = ('dated', 'no cut found', 'cut in a cloud gap')


def _ord(s):
    return date.fromisoformat(s).toordinal()


def _usable(r):
    return r['satellite'] == 'S2' and float(r['clear_share'] or 0) >= CLEAR and float(r['snow_share'] or 0) <= 0.02


def _obs(rows, start, end):
    out = {}
    for r in rows:
        if not _usable(r) or not (start <= r['date'] <= end) or r['date'] in out:
            continue
        try:
            out[r['date']] = dict({f: float(r[f]) for f in FEATS}, raw=float(r.get('ndvi_raw', r['ndvi_median'])))
        except ValueError:
            continue
    return sorted(out.items())


def detrend(by_field):
    """field -> S2 rows with each feature less that date's median over the square's clear fields (dates with at
    least 10 clear fields only); the original NDVI is kept as ndvi_raw for the ripe gate."""
    per = {}
    for rows in by_field.values():
        for r in rows:
            if _usable(r):
                try:
                    per.setdefault(r['date'], []).append({f: float(r[f]) for f in FEATS})
                except ValueError:
                    pass
    med = {dt: {f: st.median(v[f] for v in vs) for f in FEATS} for dt, vs in per.items() if len(vs) >= 10}
    out = {}
    for fid, rows in by_field.items():
        new = []
        for r in rows:
            if r['satellite'] != 'S2' or r['date'] not in med:
                continue
            try:
                new.append(dict(r, ndvi_raw=r['ndvi_median'], **{f: str(float(r[f]) - med[r['date']][f]) for f in FEATS}))
            except ValueError:
                pass
        out[fid] = new
    return out


def date_cut(rows, year, ripening):
    """One field's rows (series csv, detrended or not) -> dict(status, cut_from, cut_to, cut_middle, window_days,
    score, readings)."""
    start = f'{year}-07-15'
    if ripening:
        start = max(start, date.fromordinal(_ord(ripening) - EARLY).isoformat())
    o = _obs(rows, start, f'{year}-{END}')
    best = 0.0
    for i in range(1, len(o)):
        before, after = o[max(0, i - K):i], o[i:i + K]
        if len(before) < 2 or len(after) < 2 or st.median(v['raw'] for _, v in before) > RIPE:
            continue
        sq = num = den = 0.0
        for f in FEATS:
            step = st.median(v[f] for _, v in after) - st.median(v[f] for _, v in before)
            jump = o[i][1][f] - o[i - 1][1][f]
            z = step / NOISE[f]
            sq += WEIGHT[f] * z * z
            if abs(step) > NOISE[f]:
                num += WEIGHT[f] * abs(jump / NOISE[f]) * (jump * step > 0)
                den += WEIGHT[f] * abs(z)
        if not den or num / den < ABRUPT:
            continue
        score = math.sqrt(sq)
        if score >= SCORE:
            a, b = o[i - 1][0], o[i][0]
            gap = _ord(b) - _ord(a)
            return dict(status='dated' if gap <= WIDE else 'cut in a cloud gap', cut_from=a, cut_to=b,
                        cut_middle=date.fromordinal((_ord(a) + _ord(b) + 1) // 2).isoformat(), window_days=gap,
                        score=round(score, 2), readings=len(o))
        best = max(best, score)
    return dict(status='no cut found', cut_from='', cut_to='', cut_middle='', window_days='', score=round(best, 2),
                readings=len(o))


def rewrite(old_rows, series_by_field, ripening, year):
    """The jar's harvest rows with v2 dates for fields it dated, found no cut in or put in a cloud gap; other rows
    (no crop seen, unclear, non-field land) as they were. Same columns."""
    out = []
    for r in old_rows:
        if r['land'] != 'field' or r['status'] not in REDATE or r['field'] not in series_by_field:
            out.append(r)
            continue
        x = date_cut(series_by_field[r['field']], year, ripening.get(r['field']))
        out.append(dict(r, status=x['status'], cut_from=x['cut_from'], cut_to=x['cut_to'], cut_middle=x['cut_middle'],
                        window_days=str(x['window_days']), rise=str(x['score']), readings=str(x['readings'])))
    return out


def main():
    sq, year = sys.argv[1].rstrip('/'), int(sys.argv[2])
    hdir = f'{sq}/harvest-{year}'
    cur, v1 = f'{hdir}/harvest-{year}.csv', f'{hdir}/harvest-{year}-v1.csv'
    if not os.path.isfile(v1):
        shutil.copy(cur, v1)
    old = list(csv.DictReader(open(v1, encoding='utf-8')))
    ser = {}
    for r in csv.DictReader(open(f'{sq}/series-{year}/series-{year}.csv', encoding='utf-8')):
        ser.setdefault(r['field'], []).append(r)
    crops = f'{sq}/crops-{year}-v2.csv'
    ripening = {r['field']: r['ripening'] for r in csv.DictReader(open(crops, encoding='utf-8'))} if os.path.isfile(crops) else {}
    new = rewrite(old, detrend(ser), ripening, year)
    with open(cur, 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=list(old[0]))
        w.writeheader()
        w.writerows(new)
    n = {s: sum(1 for r in new if r['land'] == 'field' and r['status'] == s) for s in REDATE}
    print(f'{os.path.basename(sq)} {year}: ' + ', '.join(f'{v} {k}' for k, v in n.items()))


if __name__ == '__main__':
    main()
