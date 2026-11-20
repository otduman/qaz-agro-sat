"""Charts for the Russian methodology PDF (2026-11-16). ml-env python: python charts.py"""
import csv, json, datetime as dt, statistics as st, collections
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
R = 'C:/Programming Projects/qaz-agro-sat/'
INK, MUTED, RULE = '#1b1f1b', '#586057', '#d6dad0'
GRAIN, OIL, FALLOW, GRASS, ACC = '#c98a00', '#2a78d6', '#9c6b3c', '#6f9a4a', '#2f5d50'
plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 9, 'axes.edgecolor': RULE, 'axes.labelcolor': MUTED,
                     'xtick.color': MUTED, 'ytick.color': MUTED, 'axes.spines.top': False, 'axes.spines.right': False,
                     'axes.grid': True, 'grid.color': '#eceee8', 'grid.linewidth': 0.8, 'svg.fonttype': 'none', 'axes.axisbelow': True})
MON = mdates.DateFormatter('%d.%m')

def save(fig, name):
    fig.savefig(name, bbox_inches='tight'); plt.close(fig)

# 1. season curves by land type (Esil, labelled by eye)
L = R + 'data/experiments/land-labels-2026-11-13/'
key = {r['code']: r for r in csv.DictReader(open(L + 'key-esil.csv', encoding='utf-8'))}
lab = {r['code']: r['label'] for r in csv.DictReader(open(L + 'labels-esil.csv', encoding='utf-8'))}
cls = {}
for c, k in key.items():
    l = lab.get(c)
    if l == 'crop' and k['land'] == 'field':
        cls[(k['square'], k['region'])] = 'oil' if k['group'] in ('long season', 'yellow flowers') else 'grain'
    elif l == 'fallow': cls[(k['square'], k['region'])] = 'fallow'
    elif l == 'pasture': cls[(k['square'], k['region'])] = 'grass'
vals = collections.defaultdict(lambda: collections.defaultdict(list))
for sq in {s for s, _ in cls}:
    for r in csv.DictReader(open(f'{L}esil-akmola/{sq}/curves-2026.csv', encoding='utf-8')):
        c = cls.get((sq, r['region']))
        if c and r['ndvi']:
            d = dt.date.fromisoformat(r['date']); half = dt.date(2026, d.month, 1 if d.day <= 15 else 16)
            vals[c][half].append(float(r['ndvi']))
fig, ax = plt.subplots(figsize=(7, 3.2))
for c, col, name in (('grain', GRAIN, 'зерновые'), ('oil', OIL, 'масличные'), ('fallow', FALLOW, 'пар'), ('grass', GRASS, 'трава: пастбища, луга')):
    xs = sorted(k for k in vals[c] if len(vals[c][k]) >= 3)
    ax.plot(xs, [st.median(vals[c][x]) for x in xs], color=col, lw=2, marker='o', ms=3.5, label=name)
ax.set_ylabel('NDVI (зелень), медиана'); ax.xaxis.set_major_formatter(MON); ax.set_ylim(0, 0.8)
ax.legend(frameon=False, ncol=4, loc='upper center', bbox_to_anchor=(0.5, 1.15))
save(fig, 'c1-curves.svg')

# 2. one field's harvest: SWIR jump
rows = [r for r in csv.DictReader(open(R + 'data/esil-akmola/E320N5760/series-2026/series-2026.csv', encoding='utf-8'))
        if r['field'] == '2026-F0456' and r['ndvi_median'] and r['b11'] and r['satellite'] == 'S2' and float(r['clear_share']) >= 0.85]
x = [dt.date.fromisoformat(r['date']) for r in rows]
fig, ax = plt.subplots(figsize=(7, 3))
ax.plot(x, [float(r['ndvi_median']) for r in rows], color=GRAIN, lw=2, marker='o', ms=3, label='NDVI (зелень)')
ax.plot(x, [float(r['b11']) for r in rows], color=ACC, lw=2, marker='o', ms=3, label='SWIR 1,6 мкм (сухая солома, почва)')
ax.axvspan(dt.date(2026, 8, 12), dt.date(2026, 8, 14), color=FALLOW, alpha=0.25, lw=0)
ax.annotate('уборка: между\n12 и 14 августа', xy=(dt.date(2026, 8, 13), 0.62), xytext=(dt.date(2026, 9, 2), 0.66),
            color=INK, fontsize=8.5, arrowprops=dict(arrowstyle='->', color=MUTED))
ax.xaxis.set_major_formatter(MON); ax.set_ylim(0, 0.75); ax.set_ylabel('значение')
ax.legend(frameon=False, ncol=2, loc='upper center', bbox_to_anchor=(0.5, 1.16))
save(fig, 'c2-harvest.svg')

# 3. harvest pace, Tayynsha 2026
d = json.load(open(R + 'site/dist/data/tayynsha.json', encoding='utf-8'))
p = d['pace']['all']
fig, ax = plt.subplots(figsize=(7, 3))
ax.plot([dt.date.fromisoformat(a) for a, _ in p], [100 * b for _, b in p], color=GRAIN, lw=2.2, label='по снимкам (наш расчёт)')
ax.scatter([dt.date(2026, 9, 7)], [72], color=INK, zorder=5, s=28, label='официально на 7 сентября: 72%')
ax.annotate('68% по снимкам', xy=(dt.date(2026, 9, 7), 67.9), xytext=(dt.date(2026, 9, 20), 50), fontsize=8.5, color=INK,
            arrowprops=dict(arrowstyle='->', color=MUTED))
ax.set_ylabel('% площади зерновых убрано'); ax.set_ylim(0, 100); ax.xaxis.set_major_formatter(MON)
ax.legend(frameon=False, loc='upper left')
save(fig, 'c3-pace.svg')

# 4. crop split vs official, Tayynsha 2026 (inside the district)
ci = d['crop_ha_inside']
fig, ax = plt.subplots(figsize=(7, 2.2))
ours = [ci['grain'] / 1000, ci['oilseed'] / 1000, 0]; off = [400, 130, 40]
names = ['по снимкам', 'официально']
for i, (row, nm) in enumerate(((ours, names[0]), (off, names[1]))):
    left = 0
    for v, col, lab_ in zip(row, (GRAIN, OIL, GRASS), ('зерновые', 'масличные', 'кормовые')):
        if v:
            ax.barh(i, v, left=left, color=col, height=0.55, edgecolor='white', linewidth=2, label=lab_ if i == 1 else None)
            ax.text(left + v / 2, i, f'{v:,.0f}'.replace(',', ' '), ha='center', va='center', color='white', fontsize=8.5, fontweight='bold')
        left += v
ax.set_yticks([0, 1]); ax.set_yticklabels(names); ax.set_xlabel('тыс. га'); ax.invert_yaxis(); ax.grid(axis='y', visible=False)
ax.legend(frameon=False, ncol=3, loc='upper center', bbox_to_anchor=(0.5, 1.32))
save(fig, 'c4-crops.svg')
print('ok', {c: len(v) for c, v in vals.items()}, len(rows))
