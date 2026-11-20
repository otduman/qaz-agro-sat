"""Straw stubble on a 'fallow' region (2026-11-17): median tillage index NDTI = (SWIR1.6 - SWIR2.2)/(SWIR1.6 + SWIR2.2)
over its bare dates (NDVI < 0.3, clear) 1 Aug - 30 Sep, and its peak NDVI 1 Jun - 31 Jul. Harvested crops leave
straw (NDTI ~0.1+); worked fallow is bare soil (NDTI ~0). Reads the land-label experiment curves.
stubble(curve_rows) -> (ndti_median or None, n_dates, peak)"""
import csv, statistics as st
EXP = 'C:/Programming Projects/qaz-agro-sat/data/experiments/land-labels-2026-11-13/'

def stubble(rows):
    v, peak = [], 0.0
    for r in rows:
        if not r['ndvi']: continue
        n = float(r['ndvi'])
        if '2026-06-01' <= r['date'] <= '2026-07-31': peak = max(peak, n)
        if '2026-08-01' <= r['date'] <= '2026-09-30' and n < 0.3 and r['swir16'] and r['swir22']:
            a, b = float(r['swir16']), float(r['swir22'])
            if a + b > 0: v.append((a - b) / (a + b))
    return (st.median(v) if v else None), len(v), peak

def curves(dist, sq):
    out = {}
    for r in csv.DictReader(open(f'{EXP}{dist}/{sq}/curves-2026.csv', encoding='utf-8')):
        out.setdefault(int(r['region']), []).append(r)
    return out
