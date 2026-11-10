"""Lead list: harvest pace per holder against neighbouring fields, season by season.

Inputs
  - a district folder after `district join` for each season (district-harvest-<year>.csv);
  - a holders table: square,field,holder,holder_type, with field ids of the reference season (the season the plot
    cards were read for). Other seasons' fields take the holder of the reference field covering at least 60% of them
    (the squares' grids are the same every season).

For each holder and season: field area, dated area, the date by which half its dated area was cut (each field at
its window's middle), and the same date for neighbouring fields (dated fields of other holders or none, whose centre
lies within NEIGHBOUR_KM of the holder's area-weighted centre). Days behind = holder minus neighbours.
Confidence: high with 3+ dated fields covering half the holder's area or more, medium with 1+ covering a third or
more, low otherwise. A season counts toward the lead only at medium or high confidence.

Run from the repository root:
python data/tayynsha/tools/lead_list.py data/tayynsha 2026 holders.csv 2026 [2025 ...]
Writes <district>/lead-list.csv and <district>/lead-list.md. Names stay in data/ (not in git, not published).
"""
import csv
import datetime as dt
import math
import sys
from collections import defaultdict

import numpy as np
import tifffile

NEIGHBOUR_KM = 20
SHARE = 0.6
DATED = ('dated',)


def halfway(items):
    """items: (date, ha). The date by which half the area was cut."""
    items = sorted(items)
    total = sum(a for _, a in items)
    run = 0.0
    for d, a in items:
        run += a
        if run >= total / 2 - 1e-9:
            return d
    return None


def km(lat1, lon1, lat2, lon2):
    return math.hypot((lat1 - lat2) * 111.2, (lon1 - lon2) * 111.2 * math.cos(math.radians((lat1 + lat2) / 2)))


def owners(district, ref_year, holders, year):
    """(square, field id of `year`) -> holder: the reference season's holders, carried to other seasons by pixel
    overlap (a field takes the holder covering at least SHARE of it)."""
    squares = {sq for sq, _ in holders}
    # Map this season's fields to reference-season holders, square by square, by pixel overlap.
    owner = {}
    for sq in squares:
        if year == ref_year:
            for (s, f), h in holders.items():
                if s == sq:
                    owner[(sq, f)] = h
            continue
        ref = tifffile.imread(f'{district}/{sq}/fields-{ref_year}/fields-{ref_year}.tif').astype(np.int64)
        cur = tifffile.imread(f'{district}/{sq}/fields-{year}/fields-{year}.tif').astype(np.int64)
        hid = {}
        for (s, f), h in holders.items():
            if s == sq:
                hid[int(f.split('-F')[1])] = h
        names = sorted(set(hid.values()))
        lut = np.zeros(int(ref.max()) + 1, dtype=np.int64)
        for region, h in hid.items():
            if region < len(lut):
                lut[region] = names.index(h) + 1
        href = lut[ref]
        pair = np.zeros((int(cur.max()) + 1, len(names) + 1))
        np.add.at(pair, (cur.ravel(), href.ravel()), 1)
        tot = pair.sum(axis=1)
        for k in range(1, pair.shape[0]):
            best = int(pair[k, 1:].argmax()) + 1 if len(names) else 0
            if best and pair[k, best] >= SHARE * tot[k]:
                owner[(sq, f'{year}-F{k:04d}')] = names[best - 1]
    return owner


def main():
    district, ref_year, holders_csv = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    years = [int(y) for y in sys.argv[4:]]
    holders = {}
    types = {}
    for r in csv.DictReader(open(holders_csv, encoding='utf-8-sig')):
        holders[(r['square'], r['field'])] = r['holder']
        types[r['holder']] = r['holder_type']
    squares = {sq for sq, _ in holders}

    rows = []
    for year in years:
        table = list(csv.DictReader(open(f'{district}/district-harvest-{year}.csv', encoding='utf-8')))
        owner = owners(district, ref_year, holders, year)
        fields = []
        for r in table:
            if r['land'] != 'field':
                continue
            middle = dt.date.fromisoformat(r['cut_middle']) if r['status'] in DATED else None
            fields.append((owner.get((r['square'], r['field'])), float(r['area_ha']), middle,
                           float(r['centroid_lat']), float(r['centroid_lon'])))
        by = defaultdict(list)
        for f in fields:
            if f[0]:
                by[f[0]].append(f)
        for h, fs in by.items():
            area = sum(f[1] for f in fs)
            dated = [(f[2], f[1]) for f in fs if f[2]]
            lat = sum(f[3] * f[1] for f in fs) / area
            lon = sum(f[4] * f[1] for f in fs) / area
            near = [(f[2], f[1]) for f in fields if f[0] != h and f[2] and km(lat, lon, f[3], f[4]) <= NEIGHBOUR_KM]
            dated_area = sum(a for _, a in dated)
            share = dated_area / area if area else 0
            conf = 'high' if len(dated) >= 3 and share >= 0.5 else 'medium' if dated and share >= 1 / 3 else 'low'
            mine = halfway(dated) if dated else None
            theirs = halfway(near) if near else None
            behind = (mine - theirs).days if mine and theirs else None
            rows.append(dict(holder=h, type=types.get(h, ''), year=year, fields=len(fs), area_ha=round(area),
                             dated_fields=len(dated), dated_share=round(share, 2), halfway=mine or '',
                             neighbours_halfway=theirs or '', neighbour_fields=len(near),
                             days_behind='' if behind is None else behind, confidence=conf))

    lead = defaultdict(list)
    for r in rows:
        if r['confidence'] != 'low' and r['days_behind'] != '':
            lead[r['holder']].append(r['days_behind'])
    for r in rows:
        seasons = lead.get(r['holder'], [])
        r['seasons_counted'] = len(seasons)
        r['mean_days_behind'] = round(sum(seasons) / len(seasons), 1) if seasons else ''
    rows.sort(key=lambda r: (-(r['mean_days_behind'] if r['mean_days_behind'] != '' else -999), r['holder'],
                             r['year']))
    cols = list(rows[0].keys()) if rows else []
    with open(f'{district}/lead-list.csv', 'w', encoding='utf-8', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    with open(f'{district}/lead-list.md', 'w', encoding='utf-8') as fh:
        fh.write('| holder | type | season | area ha | dated | halfway | neighbours | days behind | confidence |'
                 ' mean behind |\n|---|---|---|---|---|---|---|---|---|---|\n')
        for r in rows:
            fh.write(f"| {r['holder']} | {r['type']} | {r['year']} | {r['area_ha']} | {r['dated_fields']}/{r['fields']}"
                     f" | {r['halfway']} | {r['neighbours_halfway']} | {r['days_behind']} | {r['confidence']} |"
                     f" {r['mean_days_behind']} |\n")
    print(f'{len(rows)} holder-seasons, {len(lead)} holders with a counted season; wrote lead-list.csv and .md')


if __name__ == '__main__':
    main()
