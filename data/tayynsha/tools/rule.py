"""The harvest command's dating, re-done in Python for diagnostics only (core.harvest: CutRule, LateScreen,
HarvestDater). Checked against the Java output before use."""
import csv
import datetime as dt
from collections import defaultdict

MIN_CLEAR, RIPE, JUMP, SIDE, MIN_AFTER = 0.85, 0.4, 3.5, 3, 2
SNOWY, FLASH, COVER, WET_RUN, MAX_WINDOW, MIN_READ = 0.25, 0.02, 0.2, 2, 10, 5


def med(v):
    s = sorted(v)
    m = len(s) // 2
    return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2


def load(series_folder, year, lands=('field', 'fallow')):
    info = {r['id']: r for r in csv.DictReader(open(f'{series_folder}/fields-{year}.csv', encoding='utf-8'))}
    keep = {k for k, r in info.items() if r['in_series'] == 'yes' and r['land'] in lands}
    rows = defaultdict(list)
    for r in csv.DictReader(open(f'{series_folder}/series-{year}.csv', encoding='utf-8')):
        if r['field'] not in keep or not r['ndvi_median'] or not r['b11']:
            continue
        rows[r['field']].append((dt.date.fromisoformat(r['date']), r.get('satellite') or 'S2', float(r['clear_share']),
                                 float(r['ndvi_median']), float(r['b11']), float(r['blue']) if r['blue'] else float('nan'),
                                 float(r['snow_share'] or 0)))
    for k in keep:
        rows.setdefault(k, [])
    return info, rows


def usable(readings, landsat=True):
    by = {}
    for r in readings:
        d, sat, clear, ndvi, b11 = r[:5]
        if clear < MIN_CLEAR:
            continue
        if sat != 'S2' and (not landsat or d in by):
            continue
        if sat == 'S2' or d not in by:
            by[d] = r
    return [by[d] for d in sorted(by)]


def screen(us):
    bydate = defaultdict(list)
    for rs in us.values():
        for r in rs:
            bydate[r[0]].append(r)
    n = len(us)
    judged, blue, b11, dropped = [], [], [], {}
    for d in sorted(bydate):
        rs = bydate[d]
        if len(rs) < COVER * n:
            continue
        snowy = sum(1 for r in rs if r[6] > 0)
        if snowy >= SNOWY * len(rs):
            dropped[d] = 'snow'
            continue
        judged.append(d)
        blue.append(med([r[5] for r in rs]))
        b11.append(med([r[4] for r in rs]) * 100)
    for i in range(1, len(judged) - 1):
        a, b = blue[i] - blue[i - 1], blue[i] - blue[i + 1]
        if (a >= FLASH and b >= FLASH) or (a <= -FLASH and b <= -FLASH):
            dropped[judged[i]] = 'flash'
    for length in range(1, WET_RUN + 1):
        for i in range(1, len(judged) - length):
            before, after = b11[i - 1], b11[i + length]
            if all(before - b11[k] >= JUMP and after - b11[k] >= JUMP for k in range(i, i + length)):
                for k in range(i, i + length):
                    dropped.setdefault(judged[k], 'wet')
    return dropped


def biggest(rs):
    start = next((i for i, r in enumerate(rs) if r[0].month >= 8 and r[3] < RIPE), None)
    if start is None:
        return None
    ripe = rs[start:]
    v = [r[4] * 100 for r in ripe]
    best = None
    for split in range(1, len(v) - MIN_AFTER + 1):
        rise = med(v[split:split + SIDE]) - med(v[max(0, split - SIDE):split])
        if best is None or rise > best[2]:
            best = (ripe[split - 1][0], ripe[split][0], rise)
    return best


def date_all(rows, landsat=True, use_screen=True):
    us = {k: usable(v, landsat) for k, v in rows.items()}
    dropped = screen(us) if use_screen else {}
    out = {}
    for k, rs in us.items():
        kept = [r for r in rs if r[0] not in dropped and r[6] <= 0]
        count = sum(1 for r in kept if r[0].month >= 8)
        step = biggest(kept)
        if count < MIN_READ or step is None:
            out[k] = ('unclear', None, None, step[2] if step else None)
        elif step[2] < JUMP:
            out[k] = ('no cut found', None, None, step[2])
        else:
            st = 'dated' if (step[1] - step[0]).days <= MAX_WINDOW else 'cut in a cloud gap'
            out[k] = (st, step[0], step[1], step[2])
    return out, dropped, us
