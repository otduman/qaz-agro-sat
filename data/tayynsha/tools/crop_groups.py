"""Crop groups from season curves, per square and season. The rules are frozen in
docs/superpowers/notes/2026-11-10-crop-groups.md (rule v1, the default) and
docs/superpowers/notes/2026-11-12-oilseed-detection.md (rule v2: v1 plus a one-date rapeseed bloom test);
this file only carries them out.

Inputs per square: colour-<year>.csv (colour_series.py), series-<year>/fields-<year>.csv (field areas) and
harvest-<year>/harvest-<year>.csv (crop gate status). Writes <square>/crops-<year>.csv.

Run from the repository root:
python data/tayynsha/tools/crop_groups.py data/tayynsha/E560N5960 2026 [v1|v2 <out.csv>]
Rule v1 by default; "v2 <out.csv>" uses rule v2 and writes to <out.csv> (never over crops-<year>.csv).
Throwaway experiment code.
"""
import csv
import datetime as dt
import statistics as st
import sys
from collections import defaultdict

CLEAR = 0.9
RIPE_NDVI = 0.4
PERENNIAL_GREEN = 0.35
PERENNIAL_FLOOR = 0.3
YELLOW_SIGMAS = 3.0
YELLOW_NDVI = 0.4
YELLOW_DATES = 2
LONG_DAYS = 21
MIN_FIELDS_PER_DATE = 10  # a date's yellow threshold needs at least this many cropped fields clear on it
# v2 bloom test: one clear date, 25 June-31 July, yellow AND dense canopy, still green two weeks later
BLOOM_NDYI_Z = 2.0
BLOOM_NIR_Z = 2.0
BLOOM_NDVI = 0.5
BLOOM_STATS_NDVI = 0.4  # the date's comparison fields: cropped fields with at least this NDVI
BLOOM_AFTER_DAYS = 14
BLOOM_GREEN_AFTER = 0.6


def ripening(s):
    """s: sorted (date, ndvi). The first date after the NDVI peak with NDVI below 0.4 there and on the next date."""
    if not s:
        return None
    peak = max(range(len(s)), key=lambda i: s[i][1])
    for i in range(peak + 1, len(s) - 1):
        if s[i][1] < RIPE_NDVI and s[i + 1][1] < RIPE_NDVI:
            return s[i][0]
    return None


def perennial(s):
    if not s:
        return False
    year = s[0][0].year
    start = next((day for day, v in s if day < dt.date(year, 5, 10) and v >= PERENNIAL_GREEN), None)
    if start is None:
        return False
    return all(v >= PERENNIAL_FLOOR for day, v in s if start <= day <= dt.date(year, 6, 15))


def robust_threshold(values):
    m = st.median(values)
    mad = st.median(abs(v - m) for v in values)
    return m + YELLOW_SIGMAS * 1.4826 * mad


def yellow_dates(points, thresholds):
    """points: (date, ndvi, ndyi). Dates 25 June-31 August with a green canopy and NDYI above that date's threshold."""
    n = 0
    for day, ndvi, ndyi, *_ in points:
        if dt.date(day.year, 6, 25) <= day <= dt.date(day.year, 8, 31) and ndvi >= YELLOW_NDVI \
                and day in thresholds and ndyi >= thresholds[day]:
            n += 1
    return n


def _median_sigma(values):
    m = st.median(values)
    return m, 1.4826 * st.median(abs(v - m) for v in values)


def date_stats(points):
    """points: field -> [(date, ndvi, ndyi, nir)] of cropped fields. Per date with at least 10 fields of NDVI >= 0.4:
    ((NDYI median, NDYI robust sigma), (NIR median, NIR robust sigma)) over those fields."""
    by = defaultdict(list)
    for p in points.values():
        for day, ndvi, ndyi, nir in p:
            if ndvi >= BLOOM_STATS_NDVI:
                by[day].append((ndyi, nir))
    return {day: (_median_sigma([y for y, _ in v]), _median_sigma([n for _, n in v]))
            for day, v in by.items() if len(v) >= MIN_FIELDS_PER_DATE}


def bloom_dates(points, stats):
    """points: sorted (date, ndvi, ndyi, nir). Clear dates 25 June-31 July with NDVI >= 0.5, NDYI and NIR both at
    least 2 robust sigmas above that date's fields, and NDVI >= 0.6 on a date at least 14 days later (a bloom keeps
    green pods; grain that yellows while ripening does not)."""
    n = 0
    for day, ndvi, ndyi, nir in points:
        if not (dt.date(day.year, 6, 25) <= day <= dt.date(day.year, 7, 31)) or day not in stats or ndvi < BLOOM_NDVI:
            continue
        (ym, ys), (nm, ns) = stats[day]
        if ys <= 0 or ns <= 0 or (ndyi - ym) / ys < BLOOM_NDYI_Z or (nir - nm) / ns < BLOOM_NIR_Z:
            continue
        if any(v >= BLOOM_GREEN_AFTER for d2, v, _, _ in points if (d2 - day).days >= BLOOM_AFTER_DAYS):
            n += 1
    return n


def weighted_median(items):
    items = sorted(items)
    total = sum(a for _, a in items)
    run = 0.0
    for day, a in items:
        run += a
        if run >= total / 2 - 1e-9:
            return day
    return None


def group(crop, perennial, yellow, ripe, median, bloom=0):
    if not crop:
        return 'no crop seen'
    if perennial:
        return 'perennial grass'
    if yellow >= YELLOW_DATES or bloom >= 1:
        return 'yellow flowers'
    if ripe is None:
        return 'unclear'
    if median is not None and (ripe - median).days >= LONG_DAYS:
        return 'long season'
    return 'short season'


def main():
    square, year = sys.argv[1].rstrip('/'), sys.argv[2]
    v2 = len(sys.argv) > 3 and sys.argv[3] == 'v2'
    out = sys.argv[4] if len(sys.argv) > 4 else f'{square}/crops-{year}.csv'
    area = {r['id']: float(r['area_ha']) for r in csv.DictReader(open(f'{square}/series-{year}/fields-{year}.csv',
                                                                       encoding='utf-8')) if r['land'] == 'field'}
    status = {r['field']: r['status'] for r in csv.DictReader(open(f'{square}/harvest-{year}/harvest-{year}.csv',
                                                                    encoding='utf-8'))}
    points = defaultdict(list)
    for r in csv.DictReader(open(f'{square}/colour-{year}.csv', encoding='utf-8')):
        if r['field'] not in area or r['field'] not in status or float(r['clear_share']) < CLEAR:
            continue
        b, g, red, nir = (float(r[k]) for k in ('blue', 'green', 'red', 'nir'))
        if nir + red <= 0 or g + b <= 0:
            continue
        points[r['field']].append((dt.date.fromisoformat(r['date']), (nir - red) / (nir + red), (g - b) / (g + b), nir))
    for p in points.values():
        p.sort()
    crop = {f: status[f] != 'no crop seen' for f in points}

    by_date = defaultdict(list)
    for f, p in points.items():
        if crop[f]:
            for day, _, ndyi, _ in p:
                by_date[day].append(ndyi)
    thresholds = {day: robust_threshold(v) for day, v in by_date.items() if len(v) >= MIN_FIELDS_PER_DATE}

    stats = date_stats({f: p for f, p in points.items() if crop[f]}) if v2 else {}
    ripe = {f: ripening([(day, v) for day, v, *_ in p]) for f, p in points.items()}
    median = weighted_median([(ripe[f], area[f]) for f in points if crop[f] and ripe[f]])
    rows = []
    for f, p in points.items():
        per = perennial([(day, v) for day, v, *_ in p])
        y = yellow_dates(p, thresholds)
        bl = bloom_dates(p, stats) if v2 else 0
        row = dict(field=f, area_ha=area[f], group=group(crop[f], per, y, ripe[f], median, bl),
                   ripening=ripe[f] or '', days_after_median=(ripe[f] - median).days if ripe[f] and median else '',
                   yellow_dates=y, clear_dates=len(p))
        if v2:
            row['bloom_dates'] = bl
        rows.append(row)
    rows.sort(key=lambda r: r['field'])
    with open(out, 'w', encoding='utf-8', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    share = defaultdict(float)
    for r in rows:
        share[r['group']] += r['area_ha']
    tot = sum(share.values())
    print(f'{square} {year}: median ripening {median}; ' + ', '.join(f'{g} {share[g] / tot:.0%}' for g in sorted(share)))


if __name__ == '__main__':
    main()
