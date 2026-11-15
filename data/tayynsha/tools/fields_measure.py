"""Measures of a field map version against independent evidence, per square and pooled (2026-11-12 field-detection
check). VERSION is 'current' (the square's own fields-<year>/) or an experiment folder name such as fields-2026-v2.

  sizes     field and fallow regions by size; thin ones (half-width at most 3 pixels, so under about 70 m wide);
  cadastre  for each map click (field -> plot) the region now at the click point; 'over' when its area is more than
            1.1 x the plot's area + 5 ha, so the region must spill over the plot's edge (a likely merge);
  years     (squares with two seasons, 2025 and 2026 both of VERSION) share of field and fallow outline within 20 m of
            the other season's outline, both ways;
  overlap   (pairs of neighbouring squares both in the run) inside the strip both squares see, the share of
            field area whose region in one square lies mostly (>= 80%) in one region of the other, both ways.

Reads only plot numbers and areas from the plot cards, and only field id and point from the owner sheets.

python fields_measure.py VERSION SET [--out FILE]      SET: tuning, held-out, sandyktau or a comma list of squares
"""
import csv
import itertools
import sys

import numpy as np
from scipy import ndimage

import fields_lib as L

HA = 0.01  # hectares per 10 m pixel


def version_arg(v):
    return None if v == 'current' else v


def map_of(square, year, version):
    lab = L.labels(square, year, version)
    lands = L.land(square, year, version)
    n = lab.max()
    kind = np.zeros(n + 1, np.int8)  # 1 field/fallow, 2 other
    for r, (land, _) in lands.items():
        kind[r] = 1 if land in ('field', 'fallow') else 2
    return lab, kind


def sizes(lab, kind):
    n = lab.max()
    area = np.bincount(lab.ravel(), minlength=n + 1) * HA
    fieldish = (kind == 1)
    fieldish[0] = False
    # half-width: the largest distance to the region's edge
    dist = ndimage.distance_transform_edt(~L.boundaries(lab))
    half = ndimage.maximum(dist, lab, index=np.arange(n + 1))
    half = np.asarray(half)
    out = {'fields': int(fieldish.sum()), 'field_ha': float(area[fieldish].sum())}
    for name, lo, hi in (('under10', 0, 10), ('10to20', 10, 20), ('20to50', 20, 50), ('over500', 500, 1e9),
                         ('over1000', 1000, 1e9)):
        m = fieldish & (area >= lo) & (area < hi)
        out[name] = int(m.sum())
        out[name + '_ha'] = float(area[m].sum())
    thin = fieldish & (half <= 3.5)
    out['thin'] = int(thin.sum())
    out['thin_ha'] = float(area[thin].sum())
    return out


def clicks(square, year):
    """(lat, lon, plot area) per clicked field of the square's current map."""
    sd = L.square_dir(square)
    try:
        cards = {r['plot']: float(r['area_ha']) for r in csv.DictReader(open(sd + f'plot-cards-{year}.csv',
                                                                                encoding='utf-8'))
                 if r['area_ha'].strip()}
        clicked = {r['field']: r['plot_clicked'] for r in csv.DictReader(open(sd + f'plot-clicks-{year}.csv',
                                                                                  encoding='utf-8'))}
        points = {}
        for r in csv.DictReader(open(sd + f'owners-{year}.csv', encoding='utf-8-sig')):
            points[r['field']] = (float(r['lat']), float(r['lon']))
    except FileNotFoundError:
        return []
    out = []
    for field, plot in clicked.items():
        if field in points and plot in cards:
            out.append((points[field][0], points[field][1], cards[plot], plot))
    return out


def cadastre(square, year, lab, kind):
    n = lab.max()
    area = np.bincount(lab.ravel(), minlength=n + 1) * HA
    rows = []
    for lat, lon, plot_ha, plot in clicks(square, year):
        y, x = L.pixel_of(square, year, lon, lat)
        if not (0 <= y < lab.shape[0] and 0 <= x < lab.shape[1]):
            continue
        r = lab[y, x]
        a = area[r] if r else 0.0
        rows.append((plot, plot_ha, r, a, kind[r] == 1 if r else False))
    # a region clicked twice (two old fields, now one region) is counted once
    seen = {}
    for plot, plot_ha, r, a, fieldish in rows:
        if r and fieldish:
            seen.setdefault(r, (plot, plot_ha, a))
    over = [(r, v) for r, v in seen.items() if v[2] > 1.1 * v[1] + 5]
    # several plots in one region: clicks on different plots landing in the same region
    plots_in = {}
    for plot, plot_ha, r, a, fieldish in rows:
        if r and fieldish:
            plots_in.setdefault(r, set()).add(plot)
    multi = [r for r, p in plots_in.items() if len(p) > 1]
    return {'clicked_regions': len(seen), 'clicked_ha': sum(v[2] for v in seen.values()),
            'over_plot': len(over), 'over_plot_ha': sum(v[2] for _, v in over),
            'excess_ha': sum(v[2] - v[1] for _, v in over),
            'multi_plot': len(multi), 'multi_plot_ha': sum(seen[r][2] for r in multi)}


def outline(lab, kind):
    """Outline pixels of field and fallow regions (both sides of each crack touching one)."""
    fieldish = kind[lab] == 1
    b = np.zeros(lab.shape, bool)
    d = (lab[:, 1:] != lab[:, :-1]) & (fieldish[:, 1:] | fieldish[:, :-1])
    b[:, 1:] |= d
    b[:, :-1] |= d
    d = (lab[1:, :] != lab[:-1, :]) & (fieldish[1:, :] | fieldish[:-1, :])
    b[1:, :] |= d
    b[:-1, :] |= d
    return b


def years(square, version, maps):
    lab26, kind26 = maps
    try:
        lab25, kind25 = map_of(square, 2025, version)
    except FileNotFoundError:
        return None
    if lab25.shape != lab26.shape:
        return None
    b26, b25 = outline(lab26, kind26), outline(lab25, kind25)
    d25 = ndimage.distance_transform_edt(~b25)
    d26 = ndimage.distance_transform_edt(~b26)
    return {'b26': int(b26.sum()), 'b26_near': int((d25[b26] <= 2).sum()),
            'b25': int(b25.sum()), 'b25_near': int((d26[b25] <= 2).sum())}


def purity(la, ka, lb, kb):
    """Share of A's field area whose region lies >= 80% inside one region of B (within the window)."""
    m = (ka[la] == 1) & (lb > 0)
    a = la[m].astype(np.int64)
    b = lb[m].astype(np.int64)
    if a.size == 0:
        return 0, 0
    key = a * (lb.max() + 1) + b
    uk, cnt = np.unique(key, return_counts=True)
    ra = uk // (lb.max() + 1)
    tot = np.bincount(a, minlength=la.max() + 1)
    best = np.zeros(la.max() + 1, np.int64)
    np.maximum.at(best, ra, cnt)
    regions = np.nonzero(tot)[0]
    good = regions[best[regions] >= 0.8 * tot[regions]]
    return int(tot[good].sum()), int(tot[regions].sum())


def overlap(sa, sb, year, maps):
    ea, na, size, wa, ha, _ = L.grid(sa, year)
    eb, nb, _, wb, hb, _ = L.grid(sb, year)
    e0, e1 = max(ea, eb), min(ea + wa * size, eb + wb * size)
    n1, n0 = min(na, nb), max(na - ha * size, nb - hb * size)
    if e1 - e0 < 500 or n1 - n0 < 500:
        return None
    def window(e, n, lab):
        x0, y0 = int(round((e0 - e) / size)), int(round((n - n1) / size))
        x1, y1 = int(round((e1 - e) / size)), int(round((n - n0) / size))
        return lab[y0:y1, x0:x1]
    la, ka = maps[sa]
    lb, kb = maps[sb]
    wa_, wb_ = window(ea, na, la), window(eb, nb, lb)
    if wa_.shape != wb_.shape:
        return None
    ga, ta = purity(wa_, ka, wb_, kb)
    gb, tb = purity(wb_, kb, wa_, ka)
    return {'good': ga + gb, 'total': ta + tb}


def squares_of(name):
    return {'tuning': L.TUNING, 'held-out': L.HELD_OUT_TAYYNSHA, 'sandyktau': L.SANDYKTAU}.get(
        name, name.split(','))


def main():
    args = sys.argv[1:]
    out = None
    if '--out' in args:
        k = args.index('--out')
        out = args[k + 1]
        del args[k:k + 2]
    version, which = args[0], args[1]
    v = version_arg(version)
    squares = squares_of(which)
    year = 2026
    maps = {}
    lines = []
    tot = {}
    for sq in squares:
        maps[sq] = map_of(sq, year, v)
        s = sizes(*maps[sq])
        c = cadastre(sq, year, *maps[sq])
        y = years(sq, v, maps[sq]) if sq in L.TWO_SEASONS else None
        row = {'square': sq, **s, **c}
        if y:
            row.update(y)
        for k, val in row.items():
            if k != 'square':
                tot[k] = tot.get(k, 0) + val
        lines.append(row)
        print(sq, {k: (round(val, 1) if isinstance(val, float) else val) for k, val in row.items() if k != 'square'},
              flush=True)
    ov_good = ov_total = pairs = 0
    for sa, sb in itertools.combinations(squares, 2):
        r = overlap(sa, sb, year, maps)
        if r:
            pairs += 1
            ov_good += r['good']
            ov_total += r['total']
    summary = [f'version {version}, set {which}, {len(squares)} squares, season {year}',
               f"field+fallow regions: {tot['fields']}, {tot['field_ha']:.0f} ha",
               f"  under 10 ha: {tot['under10']} ({tot['under10_ha']:.0f} ha); 10-20 ha: {tot['10to20']} "
               f"({tot['10to20_ha']:.0f} ha); 20-50 ha: {tot['20to50']} ({tot['20to50_ha']:.0f} ha)",
               f"  over 500 ha: {tot['over500']} ({tot['over500_ha']:.0f} ha); over 1000 ha: {tot['over1000']} "
               f"({tot['over1000_ha']:.0f} ha); thin (under ~70 m wide): {tot['thin']} ({tot['thin_ha']:.0f} ha)",
               f"cadastre: {tot['clicked_regions']} clicked regions ({tot['clicked_ha']:.0f} ha); larger than their "
               f"plot: {tot['over_plot']} ({tot['over_plot_ha']:.0f} ha, {tot['excess_ha']:.0f} ha beyond the plot); "
               f"holding clicks on 2+ plots: {tot['multi_plot']} ({tot['multi_plot_ha']:.0f} ha)"]
    if 'b26' in tot:
        summary.append(f"years (2026 vs 2025, {sum(1 for r in lines if 'b26' in r)} squares): 2026 outline within "
                       f"20 m of 2025: {100 * tot['b26_near'] / tot['b26']:.1f}%; 2025 within 20 m of 2026: "
                       f"{100 * tot['b25_near'] / tot['b25']:.1f}%")
    if ov_total:
        summary.append(f'overlap ({pairs} neighbouring pairs): field area matched one-to-one (>= 80%) in the other '
                       f'square: {100 * ov_good / ov_total:.1f}% of {ov_total * HA:.0f} ha')
    text = '\n'.join(summary)
    print(text)
    if out:
        with open(out, 'w', encoding='utf-8') as f:
            f.write(text + '\n\n')
            keys = sorted({k for r in lines for k in r if k != 'square'})
            f.write('square,' + ','.join(keys) + '\n')
            for r in lines:
                f.write(r['square'] + ',' + ','.join(
                    (f'{r[k]:.2f}' if isinstance(r.get(k), float) else str(r.get(k, ''))) for k in keys) + '\n')


if __name__ == '__main__':
    main()
