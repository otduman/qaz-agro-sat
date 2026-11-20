"""Own crop group and harvest date for pieces of fields (lines first, data/experiments/lines-first-2026-11-18): the
pieces are read like any field, by the same steps the square's fields went through (add-squares.sh): the jar's
series and harvest on a label file holding only the pieces, colour_series' means, then crop groups v1 and v2 and
harvest rule v2 run on the square's own fields and the pieces together (so medians and the detrend see the same
square). Piece n is '<year>-P<nnnn>' there.

Working files: <square>/pieces-<year>/ (rewritten on every run).
"""
import csv
import json
import os
import shutil
import subprocess
import sys

import numpy as np
import tifffile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
JAR = os.path.normpath(os.path.join(HERE, '../../kazagrosat-fields-v2.jar'))
GEO_TAGS = (33550, 33922, 34735, 34736, 34737, 42113)
TYPES = {33550: 12, 33922: 12, 34735: 3, 34736: 12, 34737: 2, 42113: 2}


def write_like(src, data, dst):
    """data as a tiled uint16 GeoTIFF with the grid, tiling, compression and geo tags of src."""
    t = tifffile.TiffFile(src)
    p = t.pages[0]
    extra = []
    for code in GEO_TAGS:
        if code in p.tags:
            v = p.tags[code].value
            if TYPES[code] == 2:
                v = str(v)
                extra.append((code, 2, len(v) + 1, v, True))
            else:
                v = tuple(v)
                extra.append((code, TYPES[code], len(v), v, True))
    kw = {}
    if p.is_tiled:
        kw['tile'] = (p.tilelength, p.tilewidth)
    comp = {8: 'zlib', 32946: 'zlib', 5: 'lzw'}.get(int(p.compression), None)
    if comp:
        kw['compression'] = comp
        if int(getattr(p, 'predictor', 1)) == 2:
            kw['predictor'] = True
    t.close()
    tifffile.imwrite(dst, data.astype(np.uint16), extratags=extra, **kw)


def piece_id(year, n):
    return f'{year}-P{n:04d}'


def piece_number(fid):
    return int(fid.split('-P')[1]) if '-P' in fid else None


def combine(square_rows, piece_rows, key, year):
    """The square's rows, then the pieces' rows with their ids '<year>-F<n>' renamed '<year>-P<n>'."""
    out = [dict(r) for r in square_rows]
    for r in piece_rows:
        r = dict(r)
        r[key] = piece_id(year, int(r[key].split('-F')[1]))
        out.append(r)
    return out


def _rows(path):
    return list(csv.DictReader(open(path, encoding='utf-8')))


def _write(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def colour_rows(sq_dir, year, labels):
    """colour_series' rows for the regions of `labels` (every region listed as a field)."""
    import colour_series as CS
    regions = int(labels.max()) + 1
    inner = CS.interior(labels)
    stack = f'{sq_dir}/season-{year}'
    out = []
    for d in csv.DictReader(open(f'{stack}/index.csv', encoding='utf-8')):
        if d['status'] != 'stored':
            continue
        folder = f'{stack}/{d["date"]}'
        scale, offset, nodata = float(d['scale']), float(d['offset']), int(d['nodata'])
        bands = [CS.reflectance(tifffile.imread(f'{folder}/{b}.tif'), scale, offset, nodata) for b in CS.BANDS]
        scl = tifffile.imread(f'{folder}/scl.tif')
        up = np.repeat(np.repeat(scl, 2, axis=0), 2, axis=1)[:labels.shape[0], :labels.shape[1]]
        means, share = CS.field_means(labels, inner, np.isin(up, CS.USABLE), bands, regions)
        for k in range(1, regions):
            if share[k] > 0:
                out.append(dict(field=f'{year}-F{k:04d}', date=d['date'], clear_share=f'{share[k]:.4f}',
                                **{b: f'{m[k]:.4f}' for b, m in zip(CS.BANDS, means)}))
    return out


def run(sq_dir, year, pieces, log=print):
    """{piece number: {group, ripening, status, cut_from, cut_to, cut_middle}} for the pieces (labels 1..n of a raster
    on the square's grid)."""
    n = int(pieces.max())
    if n == 0:
        return {}
    work = f'{sq_dir}/pieces-{year}'
    shutil.rmtree(work, ignore_errors=True)
    fdir = f'{work}/fields-{year}'
    os.makedirs(fdir)
    write_like(f'{sq_dir}/fields-{year}/fields-{year}.tif', pieces, f'{fdir}/fields-{year}.tif')
    sizes = np.bincount(pieces.ravel(), minlength=n + 1)
    feats = [dict(type='Feature', geometry=None,
                  properties=dict(id=f'{year}-F{k:04d}', area_ha=round(float(sizes[k]) / 100, 2), land='field',
                                  uniformity=0.5)) for k in range(1, n + 1)]
    json.dump(dict(type='FeatureCollection', features=feats), open(f'{fdir}/fields-{year}.geojson', 'w'))
    java = ['java', '-Xmx2g', '-jar', JAR]
    land = ['--landsat', f'{sq_dir}/landsat-{year}'] if os.path.isdir(f'{sq_dir}/landsat-{year}') else []
    for cmd in (java + ['series', '--stack', f'{sq_dir}/season-{year}', '--fields', fdir] + land
                + ['--out', f'{work}/series-{year}'],
                java + ['harvest', '--series', f'{work}/series-{year}', '--fields', fdir, '--out', f'{work}/harvest-{year}']):
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f'{cmd[4]} failed: {r.stderr[-800:]}')
    combo = f'{work}/combo'
    v1 = f'{sq_dir}/harvest-{year}/harvest-{year}-v1.csv'
    if not os.path.isfile(v1):
        v1 = f'{sq_dir}/harvest-{year}/harvest-{year}.csv'
    _write(f'{combo}/series-{year}/series-{year}.csv',
           combine(_rows(f'{sq_dir}/series-{year}/series-{year}.csv'), _rows(f'{work}/series-{year}/series-{year}.csv'),
                   'field', year))
    _write(f'{combo}/series-{year}/fields-{year}.csv',
           combine(_rows(f'{sq_dir}/series-{year}/fields-{year}.csv'), _rows(f'{work}/series-{year}/fields-{year}.csv'),
                   'id', year))
    _write(f'{combo}/harvest-{year}/harvest-{year}.csv',
           combine(_rows(v1), _rows(f'{work}/harvest-{year}/harvest-{year}.csv'), 'field', year))
    _write(f'{combo}/colour-{year}.csv',
           combine(_rows(f'{sq_dir}/colour-{year}.csv'), colour_rows(sq_dir, year, pieces), 'field', year))
    py = [sys.executable, os.path.join(HERE, 'crop_groups.py'), combo, str(year)]
    for cmd in (py, py + ['v2', f'{combo}/crops-{year}-v2.csv'],
                [sys.executable, os.path.join(HERE, 'harvest_v2.py'), combo, str(year)]):
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f'{os.path.basename(cmd[1])} failed: {r.stderr[-800:]}')
    out = {}
    for r in _rows(f'{combo}/crops-{year}-v2.csv'):
        k = piece_number(r['field'])
        if k:
            out.setdefault(k, {}).update(group=r['group'], ripening=r['ripening'])
    for r in _rows(f'{combo}/harvest-{year}/harvest-{year}.csv'):
        k = piece_number(r['field'])
        if k:
            out.setdefault(k, {}).update(status=r['status'], cut_from=r['cut_from'], cut_to=r['cut_to'],
                                         cut_middle=r['cut_middle'])
    return out
