"""Snap drawn field edges onto cadastral plot lines (NOTE.md rule), Tayynsha 2026, squares with plot lines.
Writes snap/<sq>-old.npy, snap/<sq>-new.npy (int32 labels), stats.csv (per square), patches.csv (changed patches
>= 0.5 ha), prints totals. python snap.py"""
import csv
import json
import os
import sys

import numpy as np
import tifffile
from PIL import Image, ImageDraw
from pyproj import Transformer
from scipy import ndimage
from skimage.morphology import skeletonize

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import fields_lib as L  # noqa: E402

D_ = 'C:/Programming Projects/qaz-agro-sat/data/'
LINES = D_ + 'experiments/cadastre-edges-2026-11-15/'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
Y, D, MIN_PATCH_CELLS = 2026, 3, 50


def raster(sq, feats):
    e0, n0, size, w, h, epsg = L.grid(sq, Y)
    t = Transformer.from_crs(4326, epsg, always_xy=True)
    img = Image.new('I', (w, h), 0)
    dr = ImageDraw.Draw(img)
    keys = []
    for f in feats:
        keys.append((f['properties']['keep'], f['properties']['piece']))
        pts = [((x - e0) / size, (n0 - y) / size) for x, y in (t.transform(lo, la) for lo, la in f['geometry']['coordinates'][0])]
        dr.polygon(pts, fill=len(keys))
    return np.array(img, np.int32), keys


def snap(lab, line):
    dist, (qi, qj) = ndimage.distance_transform_edt(~line, return_indices=True)
    band = (dist > 0) & (dist <= D)
    ii, jj = np.nonzero(band)
    vi, vj = ii - qi[band], jj - qj[band]
    n = np.hypot(vi, vj)
    si = np.clip(np.round(qi[band] + (D + 1.5) * vi / n).astype(int), 0, lab.shape[0] - 1)
    sj = np.clip(np.round(qj[band] + (D + 1.5) * vj / n).astype(int), 0, lab.shape[1] - 1)
    new = lab.copy()
    nl = lab[si, sj]
    ok = (lab[ii, jj] > 0) | (nl > 0)
    new[ii[ok], jj[ok]] = nl[ok]
    return new


def edges(lab):
    return L.boundaries(lab) & (lab > 0)


def measures(lab, line, seen):
    e = edges(lab) & seen
    near_line = ndimage.binary_dilation(line, iterations=1)
    near_edge = ndimage.binary_dilation(edges(lab), iterations=1)
    farm_line = line & ndimage.binary_dilation(lab > 0, iterations=2) & seen
    return e.sum(), (e & near_line).sum(), farm_line.sum(), (farm_line & near_edge).sum()


def main():
    g = json.load(open(D_ + f'tayynsha/outlines-{Y}.geojson', encoding='utf-8'))
    by_sq = {}
    for f in g['features']:
        by_sq.setdefault(f['properties']['square'], []).append(f)
    os.makedirs(HERE + 'snap', exist_ok=True)
    stats, patches = [], []
    for fn in sorted(os.listdir(LINES + 'lines')):
        if not fn.endswith('.tif'):
            continue
        sq = fn[:-4]
        if sq not in by_sq:
            continue
        lab, keys = raster(sq, by_sq[sq])
        seen = tifffile.imread(LINES + 'seen/' + fn) > 0
        line = skeletonize(tifffile.imread(LINES + 'lines/' + fn) > 0) & seen
        assert lab.shape == line.shape, sq
        new = snap(lab, line)
        np.save(HERE + f'snap/{sq}-old.npy', lab)
        np.save(HERE + f'snap/{sq}-new.npy', new)
        b, n = measures(lab, line, seen), measures(new, line, seen)
        ch = new != lab
        area_old = np.bincount(lab.ravel(), minlength=len(keys) + 1)[1:]
        area_new = np.bincount(new.ravel(), minlength=len(keys) + 1)[1:]
        big = int(((np.abs(area_new - area_old) > 0.05 * np.maximum(area_old, 1)) & (area_old > 0)).sum())
        stats.append(dict(square=sq, fields=len(keys), field_ha=round((lab > 0).sum() / 100),
                          edge_cells=b[0], edge_on_line_before=b[1], edge_on_line_after=n[1],
                          line_cells=b[2], line_on_edge_before=b[3], line_on_edge_after=n[3],
                          moved_ha=round(ch.sum() / 100, 1), fields_over_5pct=big))
        comp, k = ndimage.label(ch)
        for i, sl in enumerate(ndimage.find_objects(comp), 1):
            m = comp[sl] == i
            if m.sum() >= MIN_PATCH_CELLS:
                cy, cx = [int(np.mean(a)) + s.start for a, s in zip(np.nonzero(m), sl)]
                patches.append(dict(square=sq, patch=i, cells=int(m.sum()), row=cy, col=cx))
        print(sq, stats[-1]['moved_ha'], 'ha moved', flush=True)
    for name, rows in (('stats.csv', stats), ('patches.csv', patches)):
        with open(HERE + name, 'w', newline='') as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
    t = {k: sum(s[k] for s in stats) for k in stats[0] if k != 'square'}
    print(f"squares {len(stats)}, fields {t['fields']}, field land {t['field_ha']:,} ha")
    print(f"our edge on a plot line: {t['edge_on_line_before'] / t['edge_cells']:.0%} -> {t['edge_on_line_after'] / t['edge_cells']:.0%}")
    print(f"plot line on our edge:   {t['line_on_edge_before'] / t['line_cells']:.0%} -> {t['line_on_edge_after'] / t['line_cells']:.0%}")
    print(f"moved {t['moved_ha']:,.0f} ha ({t['moved_ha'] / t['field_ha']:.1%} of field land); fields changed > 5%: "
          f"{t['fields_over_5pct']}; patches >= 0.5 ha: {len(patches)}")


if __name__ == '__main__':
    main()
