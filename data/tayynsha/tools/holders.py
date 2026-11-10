"""Builds data/tayynsha/holders.csv from every square's plot-clicks-<year>.csv and plot-cards-<year>.csv.
Companies, cooperatives and state bodies are named; plots whose holder the map hides are labelled by plot.
Run: python data/tayynsha/tools/holders.py 2026"""
import csv, glob, os, sys
from collections import defaultdict
year = sys.argv[1]
base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
named = {'company', 'state college', 'cooperative'}
rows, by = [], defaultdict(float)
for clicks in sorted(glob.glob(f'{base}/E*/plot-clicks-{year}.csv')):
    sq = os.path.basename(os.path.dirname(clicks))
    cards = {r['plot']: r for r in csv.DictReader(open(f'{base}/{sq}/plot-cards-{year}.csv', encoding='utf-8'))}
    area = {r['field']: float(r['area_ha']) for r in csv.DictReader(open(f'{base}/{sq}/owners-{year}.csv', encoding='utf-8-sig'))}
    total = sum(float(r['area_ha']) for r in csv.DictReader(open(f'{base}/{sq}/owners-{year}.csv', encoding='utf-8-sig')) if r['land'] == 'field')
    linked = missing = 0.0
    for c in csv.DictReader(open(clicks, encoding='utf-8')):
        p = c['plot_clicked']
        if p == 'none':
            continue
        if p not in cards:
            missing += area[c['field']]
            continue
        card = cards[p]
        h = card['land_user'] if card['user_type'] in named else f"{card['user_type']} plot {p} (holder hidden)"
        rows.append([sq, c['field'], h, card['user_type']])
        by[card['user_type']] += area[c['field']]
        linked += area[c['field']]
    print(f'{sq}: {linked:.0f} ha linked = {linked / total:.0%} of field area' + (f'; {missing:.0f} ha without a card' if missing else ''))
with open(f'{base}/holders.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f)
    w.writerow(['square', 'field', 'holder', 'holder_type'])
    w.writerows(rows)
for k, v in sorted(by.items(), key=lambda x: -x[1]):
    print(f'  {k}: {v:.0f} ha')
