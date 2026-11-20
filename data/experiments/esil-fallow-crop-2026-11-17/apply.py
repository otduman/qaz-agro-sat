"""Writes DISTRICT_DIR/fallow-crop-2026.csv (square, region, area_ha): site fallow regions the stubble rule makes crop.
python apply.py DISTRICT_DIR EXP_DISTRICT"""
import csv, os, sys
import stubble as S
ddir, ed = sys.argv[1].rstrip('/'), sys.argv[2]
idle = set()
for n in ('fallow-both-2026.csv', 'fallow-steppe-2026.csv'):
    p = f'{ddir}/{n}'
    if os.path.isfile(p):
        idle |= {(r['square'], int(r['region'].split('-F')[1])) for r in csv.DictReader(open(p, encoding='utf-8'))}
out = []
for sq in sorted(os.listdir(S.EXP + ed)):
    sp = f'{S.EXP}{ed}/{sq}/shape-2026.csv'
    if not os.path.isfile(sp): continue
    cur = S.curves(ed, sq)
    for r in csv.DictReader(open(sp, encoding='utf-8')):
        reg = int(r['region'])
        if r['land'] != 'fallow' or r['core'] != 'yes' or (sq, reg) in idle: continue
        nd, n, pk = S.stubble(cur.get(reg, []))
        if nd is not None and n >= 3 and nd >= 0.065 and pk >= 0.33:
            out.append(dict(square=sq, region=reg, area_ha=r['area_ha']))
with open(f'{ddir}/fallow-crop-2026.csv', 'w', newline='', encoding='utf-8') as fh:
    w = csv.DictWriter(fh, fieldnames=['square', 'region', 'area_ha']); w.writeheader(); w.writerows(out)
print(len(out), 'regions', round(sum(float(r['area_ha']) for r in out)), 'ha')
