"""The grass rule of NOTE.md on one district (2026): reads the land-label experiment's curves/shape per square and the
district's idle-fallow lists. python grass_rule.py DISTRICT_DIR EXP_DISTRICT -> grass-<dist>.csv, other-<dist>.csv"""
import csv, datetime as dt, os, statistics as st, sys
ddir, ed = sys.argv[1].rstrip('/'), sys.argv[2]
EXP = 'C:/Programming Projects/qaz-agro-sat/data/experiments/land-labels-2026-11-13/' + ed
idle = set()
for n in ('fallow-both-2026.csv', 'fallow-steppe-2026.csv'):
    p = f'{ddir}/{n}'
    if os.path.isfile(p):
        idle |= {(r['square'], int(r['region'].split('-F')[1])) for r in csv.DictReader(open(p, encoding='utf-8'))}
grass, other = [], []
for sq in sorted(os.listdir(EXP)):
    c, s = f'{EXP}/{sq}/curves-2026.csv', f'{EXP}/{sq}/shape-2026.csv'
    if not (os.path.isfile(c) and os.path.isfile(s)): continue
    shape = {int(r['region']): r for r in csv.DictReader(open(s, encoding='utf-8'))}
    cur = {}
    for r in csv.DictReader(open(c, encoding='utf-8')):
        if r['ndvi']: cur.setdefault(int(r['region']), []).append(r)
    for reg, sh in shape.items():
        land = sh['land']
        if not (land == 'other' or (land == 'fallow' and (sq, reg) in idle)): continue
        if sh['core'] != 'yes' or float(sh['area_ha']) < 20: continue
        rows = cur.get(reg, [])
        season = [float(r['ndvi']) for r in rows if '2026-05-01' <= r['date'] <= '2026-09-30']
        spring = [float(r['ndvi']) for r in rows if '2026-05-15' <= r['date'] <= '2026-06-15']
        ndmi = [float(r['ndmi']) for r in rows if r['date'] <= '2026-06-15' and r['ndmi']]
        water = [float(r['water_share']) for r in rows if r['water_share']]
        row = dict(square=sq, region=reg, land=land, area_ha=sh['area_ha'])
        ok = (season and st.median(season) >= 0.15 and (not water or st.median(water) < 0.3)
              and ((spring and st.median(spring) >= 0.25) or (ndmi and st.median(ndmi) >= -0.15)))
        (grass if ok else other).append(row)
d = os.path.basename(ddir)
for name, rows in (('grass', grass), ('other', other)):
    with open(f'{name}-{d}.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'region', 'land', 'area_ha']); w.writeheader(); w.writerows(rows)
print(d, 'grass', len(grass), round(sum(float(r['area_ha']) for r in grass)), 'ha; left out', len(other), round(sum(float(r['area_ha']) for r in other)), 'ha')
