"""Owner 2026-11-19: are cadastral plot lines and holders the strongest join signs? For every item of the join check
(22 joins, 10 decoys; j-key.csv, labels j-labels.csv): share of the removed border with a cadastral plot line within
30 m (where the cadastral map was viewed), the holders of the two pieces, and the joined shape (rectangle fill)."""
import csv
import sys

import numpy as np
import tifffile
from scipy import ndimage

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import fields_lib as L  # noqa: E402
import lines_first as LF  # noqa: E402

D = 'C:/Programming Projects/qaz-agro-sat/data/'
CAD = D + 'experiments/cadastre-edges-2026-11-15/'
lab = {r['code']: r['label'] for r in csv.DictReader(open('j-labels.csv'))}
key = list(csv.DictReader(open('j-key.csv')))
hold = {}
for d in ('tayynsha', 'sandyktau', 'esil-akmola'):
    for r in csv.DictReader(open(f'{D}{d}/holders.csv', encoding='utf-8')):
        hold[(r['square'], r['field'])] = (r['holder'], r['holder_type'])
ev = {}
for d in ('tayynsha', 'sandyktau', 'esil-akmola'):
    for r in csv.DictReader(open(f'evidence-joins-{d}-v1.csv')):
        ev[(r['square'], r['a'], r['b'])] = r


def cad_path(kind, d, sq):
    return f'{CAD}{kind}/{sq}.tif' if d == 'tayynsha' else f'{CAD}{kind}/{d}/{sq}.tif'


rows = []
for k in key:
    sq, d = k['square'], k['district']
    reg = L.labels(sq, 2026)
    a = np.isin(reg, [int(m.split('-F')[1]) for m in k['a'].split()])
    b = np.isin(reg, [int(m.split('-F')[1]) for m in k['b'].split()])
    border = (a & ndimage.binary_dilation(b, structure=LF.CROSS)) | (b & ndimage.binary_dilation(a, structure=LF.CROSS))
    try:
        pl = tifffile.imread(cad_path('lines', d, sq)) > 0
        seen = tifffile.imread(cad_path('seen', d, sq)) > 0
        assert pl.shape == reg.shape, (pl.shape, reg.shape)
        near = ndimage.binary_dilation(pl, iterations=3)
        bs = border & seen
        on_line = near[bs].mean() if bs.sum() >= 10 else None
        seen_share = bs.sum() / max(border.sum(), 1)
    except FileNotFoundError:
        on_line, seen_share = None, 0
    ha_ = {(x, m) for x in (k['a'],) for m in x.split()}
    hs = lambda ids: {hold.get((sq, m), ('?', 'not looked up')) for m in ids.split()}
    A, B = hs(k['a']), hs(k['b'])
    na = {h for h, t in A if t in ('company', 'cooperative', 'farm', 'peasant farm', 'state college', 'shared lease')}
    nb = {h for h, t in B if t in ('company', 'cooperative', 'farm', 'peasant farm', 'state college', 'shared lease')}
    if na and nb:
        holder = 'same' if na & nb else 'different'
    else:
        holder = 'unknown'
    fill = ev.get((sq, k['a'], k['b']), {}).get('fill_join', '')
    rows.append([k['code'], k['kind'], lab[k['code']], d, sq, 'none' if on_line is None else f'{on_line:.0%}',
                 f'{seen_share:.0%}', holder, fill])
w = csv.writer(open('joins-cadastre.csv', 'w', newline=''))
w.writerow(['code', 'kind', 'my_label', 'district', 'square', 'border_on_plot_line', 'border_viewed', 'holders', 'fill_join'])
w.writerows(rows)
for r in sorted(rows, key=lambda r: (r[1], r[2])):
    print(' '.join(f'{x!s:>11}' for x in r))
