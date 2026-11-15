"""Carry land users from the previous field map to the new one (after hybrid_fields.py, 2026-11-13).

A cadastral click links one field; when a new outline cuts an old field in two, or a click fell outside every new
field, the land it was on loses its holder. A new field without a click of its own takes the holder of the old fields
it lies on when more than half of its pixels lie on old fields of that one holder.

python carry_holders.py DISTRICT_DIR YEAR OLD_SUFFIX     reads holders-OLD_SUFFIX.csv and <sq>/fields-YEAR-OLD_SUFFIX/,
                                                         adds the carried rows to holders.csv
"""
import csv
import os
import sys
from collections import defaultdict

import numpy as np
import tifffile

SHARE = 0.5


def carried(new, old, old_holder, known):
    """{new region: (holder, type)} for new regions not in known whose pixels lie more than SHARE on old regions of
    one holder. old_holder: {old region: (holder, type)}."""
    m = new.max() + 1
    size = np.bincount(new.ravel(), minlength=m)
    by = defaultdict(lambda: np.zeros(m))
    for o, h in old_holder.items():
        sel = old == o
        by[h] += np.bincount(new[sel], minlength=m)
    out = {}
    for h, counts in by.items():
        for n in np.nonzero(counts > SHARE * size)[0]:
            if n and n not in known and size[n]:
                out[int(n)] = h
    return out


def main():
    ddir, year, suffix = sys.argv[1].rstrip('/'), sys.argv[2], sys.argv[3]
    rows = list(csv.DictReader(open(f'{ddir}/holders.csv', encoding='utf-8-sig')))
    old_rows = list(csv.DictReader(open(f'{ddir}/holders-{suffix}.csv', encoding='utf-8-sig')))
    fields = defaultdict(set)
    for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8')):
        if r['land'] == 'field':
            fields[r['square']].add(int(r['field'].split('-F')[1]))
    have = defaultdict(set)
    for r in rows:
        have[r['square']].add(int(r['field'].split('-F')[1]))
    olds = defaultdict(dict)
    for r in old_rows:
        olds[r['square']][int(r['field'].split('-F')[1])] = (r['holder'], r['holder_type'])
    added, ha = [], 0.0
    for sq in sorted(olds):
        p_new = f'{ddir}/{sq}/fields-{year}/fields-{year}.tif'
        p_old = f'{ddir}/{sq}/fields-{year}-{suffix}/fields-{year}.tif'
        if not (os.path.isfile(p_new) and os.path.isfile(p_old)):
            continue
        new = tifffile.imread(p_new).astype(np.int64)
        new[~np.isin(new, list(fields[sq]))] = 0
        got = carried(new, tifffile.imread(p_old).astype(np.int64), olds[sq], have[sq])
        size = np.bincount(new.ravel())
        for n, (h, t) in sorted(got.items()):
            added.append({'square': sq, 'field': f'{year}-F{n:04d}', 'holder': h, 'holder_type': t})
            ha += size[n] * 0.01
    with open(f'{ddir}/holders.csv', 'w', encoding='utf-8', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'field', 'holder', 'holder_type'])
        w.writeheader()
        w.writerows(rows + added)
    print(f'{len(added)} fields, {ha:,.0f} ha took the holder of the old fields they lie on')


if __name__ == '__main__':
    main()
