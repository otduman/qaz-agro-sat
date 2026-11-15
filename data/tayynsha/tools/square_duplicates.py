"""Fields counted twice where survey squares overlap (2026-11-13).

Each square has its own field map, and squares overlap by 1 km. A region is counted by the square whose core (its
box less 0.5 km each side) holds its centre. A field that crosses the overlap is cut at each square's edge, so the two
copies have different centres and each can land in its own square's core: the field is then drawn twice, one copy
inside the other. Of two counted field or fallow regions of neighbouring squares that share at least half of the
smaller one's pixels, the smaller copy (the one cut shorter) is dropped.

python square_duplicates.py DISTRICT_DIR YEAR      writes DISTRICT_DIR/duplicates-YEAR.csv (the copy dropped, the copy kept)
"""
import csv
import os
import sys

import numpy as np
import tifffile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../site/build'))
import geo  # noqa: E402

SHARE = 0.5
PX = 10.0


def pair_duplicates(a, b, dy, dx, a_ids, b_ids, share=SHARE):
    """{('a' or 'b', region dropped, region kept)}. a, b: label rasters on one 10 m lattice; B's pixel (r, c) is A's
    (r + dy, c + dx). a_ids, b_ids: the regions each square counts."""
    if not a_ids or not b_ids:
        return set()
    r0, r1 = max(0, dy), min(a.shape[0], dy + b.shape[0])
    c0, c1 = max(0, dx), min(a.shape[1], dx + b.shape[1])
    if r0 >= r1 or c0 >= c1:
        return set()
    sa = a[r0:r1, c0:c1]
    sb = b[r0 - dy:r1 - dy, c0 - dx:c1 - dx]
    ok = np.isin(sa, list(a_ids)) & np.isin(sb, list(b_ids))
    if not ok.any():
        return set()
    pairs, counts = np.unique(np.stack([sa[ok], sb[ok]]), axis=1, return_counts=True)
    size_a = np.bincount(a.ravel())
    size_b = np.bincount(b.ravel())
    drop = set()
    for (ra, rb), n in zip(pairs.T, counts):
        na, nb = size_a[ra], size_b[rb]
        if n >= share * min(na, nb):
            drop.add(('a', int(ra), int(rb)) if na < nb else ('b', int(rb), int(ra)))
    return drop


def _grid(path):
    page = tifffile.TiffFile(path).pages[0]
    keys = list(page.tags[34735].value)
    epsg = {keys[i]: keys[i + 3] for i in range(4, len(keys) - 3, 4)}.get(3072, 32642)
    x0, y0 = page.tags[33922].value[3:5]
    return page.asarray().astype(np.int64), x0, y0, epsg


def main():
    ddir, year = sys.argv[1].rstrip('/'), sys.argv[2]
    squares = list(csv.DictReader(open(f'{ddir}/squares.csv', encoding='utf-8')))
    counted = {}
    fields = {}
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        if r['land'] == 'field':
            fields.setdefault(r['square'], set()).add(int(r['field'].split('-F')[1]))
    grids = {}
    for q in squares:
        sq = q['square']
        tif = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
        table = f'{ddir}/{sq}/series-{year}/fields-{year}.csv'
        if not (os.path.isfile(tif) and os.path.isfile(table)):
            continue
        grids[sq] = (_grid(tif), [float(q[k]) for k in ('min_e', 'min_n', 'max_e', 'max_n')])
        e0, n0, e1, n1 = grids[sq][1]
        ids = set(fields.get(sq, set()))
        for x in csv.DictReader(open(table, encoding='utf-8')):
            if x['land'] == 'fallow':
                e, n = geo.utm42(float(x['centroid_lon']), float(x['centroid_lat']))
                if e0 + 500 <= e < e1 - 500 and n0 + 500 <= n < n1 - 500:
                    ids.add(int(x['id'].split('-F')[1]))
        counted[sq] = ids
    out = set()
    names = sorted(grids)
    for i, a in enumerate(names):
        (la, xa, ya, pa), ba = grids[a]
        for b in names[i + 1:]:
            (lb, xb, yb, pb), bb = grids[b]
            if pa != pb or ba[2] < bb[0] or bb[2] < ba[0] or ba[3] < bb[1] or bb[3] < ba[1]:
                continue
            dx, dy = (xb - xa) / PX, (ya - yb) / PX
            if dx != int(dx) or dy != int(dy):
                continue
            for side, r, k in pair_duplicates(la, lb, int(dy), int(dx), counted[a], counted[b]):
                out.add((a, f'{year}-F{r:04d}', b, f'{year}-F{k:04d}') if side == 'a' else
                        (b, f'{year}-F{r:04d}', a, f'{year}-F{k:04d}'))
    with open(f'{ddir}/duplicates-{year}.csv', 'w', encoding='utf-8', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['square', 'region', 'kept_square', 'kept_region'])
        w.writerows(sorted(out))
    print(f'{ddir} {year}: {len(out)} copies of regions counted twice will be dropped')


if __name__ == '__main__':
    main()
