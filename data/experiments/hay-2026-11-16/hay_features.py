"""Hay and sown-grass signal per region (2026-11-16, owner: a hay and grass group). Throwaway exploration.
Reads the land-label experiment's curves-YEAR.csv and shape-YEAR.csv (land_features.py) per square and writes one row
per region: land, area, straight share, May greenness, and the mowing cuts found (an NDVI drop of at least DROP
between clear dates at most GAP days apart, 1 June - 31 July, followed within REGROW days by a rise of at least RISE
over the low: grass grows back after mowing, a ripening or harvested grain field does not).
python hay_features.py DISTRICT [YEAR] -> features-DISTRICT-YEAR.csv"""
import csv, datetime as dt, os, statistics as st, sys
EXP = 'C:/Programming Projects/qaz-agro-sat/data/experiments/land-labels-2026-11-13/'
DROP, GAP, RISE, REGROW = 0.12, 12, 0.06, 30


def cuts(pts, year):
    """pts: [(date, ndvi)] sorted. [(date of the low, drop size)]"""
    out = []
    d0, d1 = dt.date(year, 6, 1), dt.date(year, 7, 31)
    for i in range(1, len(pts)):
        (da, va), (db, vb) = pts[i - 1], pts[i]
        if not (d0 <= db <= d1) or (db - da).days > GAP or va - vb < DROP:
            continue
        later = [v for d, v in pts[i + 1:] if (d - db).days <= REGROW]
        if later and max(later) - vb >= RISE:
            out.append((db, round(va - vb, 3)))
    return out


def main():
    dist, year = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 2026
    rows = []
    for sq in sorted(os.listdir(EXP + dist)):
        c, s = f'{EXP}{dist}/{sq}/curves-{year}.csv', f'{EXP}{dist}/{sq}/shape-{year}.csv'
        if not (os.path.isfile(c) and os.path.isfile(s)):
            continue
        shape = {r['region']: r for r in csv.DictReader(open(s, encoding='utf-8'))}
        curves = {}
        for r in csv.DictReader(open(c, encoding='utf-8')):
            if r['ndvi']:
                curves.setdefault(r['region'], []).append((dt.date.fromisoformat(r['date']), float(r['ndvi'])))
        for reg, pts in curves.items():
            if reg not in shape:
                continue
            pts.sort()
            may = [v for d, v in pts if dt.date(year, 5, 15) <= d <= dt.date(year, 5, 31)]
            jun = [v for d, v in pts if dt.date(year, 6, 1) <= d <= dt.date(year, 6, 20)]
            k = cuts(pts, year)
            sh = shape[reg]
            rows.append(dict(square=sq, region=reg, land=sh['land'], area_ha=sh.get('area_ha') or sh.get('area'),
                             core=sh.get('core', ''), straight=sh.get('straight', ''),
                             may=round(st.median(may), 3) if may else '', june=round(st.median(jun), 3) if jun else '',
                             n_cuts=len(k), first_cut=k[0][0].isoformat() if k else '', cut_drop=max((x[1] for x in k), default='')))
    with open(f'features-{dist}-{year}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader(); w.writerows(rows)
    print(dist, year, len(rows), 'regions')


if __name__ == '__main__':
    main()
