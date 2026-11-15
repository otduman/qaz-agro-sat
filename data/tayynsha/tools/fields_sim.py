"""Offline re-run of the field method's joining step on the probe's in-between results (FieldProbe), so merge rules
can be tried without rereading the season. sim(square, rule) returns the final label map after joining, dropping
regions under 5 ha and filling holes under 1 ha, like FieldFinder; rule 'current' is RegionMerger as it is.
"""
import numpy as np
from scipy import ndimage

import fields_lib as L

PROBE = L.EXPERIMENT + 'probe/'
RIDGE_WEAK, CURVE_TOL, MIN_COMMON, RANK, MIN_CLEAR = 0.2, 0.05, 5, 3, 0.6


def load(square, year=2026):
    d = PROBE + ('' if year == 2026 else f'{year}/') + square + '/'
    meta = dict(l.strip().split('=', 1) for l in open(d + 'meta.txt', encoding='utf-8'))
    w, h, n = int(meta['width']), int(meta['height']), int(meta['regions'])
    dates = meta['dates'].split(',')
    D = len(dates)
    f = lambda name, t: np.fromfile(d + name, dtype=t)
    return {
        'w': w, 'h': h, 'n': n, 'dates': dates,
        'raw': f('raw-edges.f32', '<f4').reshape(h, w),
        'lines': f('lines.f32', '<f4').reshape(h, w),
        'edges': f('edges.f32', '<f4').reshape(h, w),
        'ws': f('watershed.i32', '<i4').reshape(h, w),
        'count': f('count.i32', '<i4').reshape(n + 1, D).astype(np.float64),
        'ndvi': f('ndvi.f64', '<f8').reshape(n + 1, D),
        'bright': f('bright.f64', '<f8').reshape(n + 1, D),
    }


def pairs(p):
    """Per neighbouring watershed pair: a, b, median ridge, crack count, median line strength on the crack."""
    ws, e, ln = p['ws'], p['edges'], p['lines']
    out = []
    for sa, sb, ea, eb, la, lb in (
            (ws[:, :-1], ws[:, 1:], e[:, :-1], e[:, 1:], ln[:, :-1], ln[:, 1:]),
            (ws[:-1, :], ws[1:, :], e[:-1, :], e[1:, :], ln[:-1, :], ln[1:, :])):
        m = (sa != sb) & (sa > 0) & (sb > 0) & ~np.isnan(ea) & ~np.isnan(eb)
        a, b = np.minimum(sa[m], sb[m]), np.maximum(sa[m], sb[m])
        out.append((a, b, np.maximum(ea[m], eb[m]), np.maximum(la[m], lb[m])))
    a = np.concatenate([o[0] for o in out]).astype(np.int64)
    b = np.concatenate([o[1] for o in out]).astype(np.int64)
    r = np.concatenate([o[2] for o in out])
    l = np.concatenate([o[3] for o in out])
    key = a * (p['n'] + 1) + b
    order = np.argsort(key, kind='stable')
    key, r, l = key[order], r[order], l[order]
    starts = np.r_[0, np.nonzero(np.diff(key))[0] + 1]
    ends = np.r_[starts[1:], key.size]
    res = []
    for s, t in zip(starts, ends):
        rr = np.sort(r[s:t])
        k = t - s
        med = rr[k // 2] if k % 2 else (rr[k // 2 - 1] + float(rr[k // 2])) / 2
        res.append((int(key[s] // (p['n'] + 1)), int(key[s] % (p['n'] + 1)), float(med), k,
                    float(np.median(l[s:t]))))
    return res


class Sums:
    def __init__(self, p):
        self.count = p['count'].copy()
        self.ndvi = p['ndvi'].copy()
        self.bright = p['bright'].copy()
        self.pixels = np.bincount(p['ws'].ravel(), minlength=p['n'] + 1).astype(np.float64)

    def mean(self, r, what='ndvi'):
        c = self.count[r]
        ok = (c > 0) & (c >= MIN_CLEAR * self.pixels[r])
        v = np.full(c.shape, np.nan)
        v[ok] = getattr(self, what)[r][ok] / c[ok]
        return v

    def absorb(self, into, other):
        for a in (self.count, self.ndvi, self.bright):
            a[into] += a[other]
        self.pixels[into] += self.pixels[other]


def gap(s, a, b, what='ndvi'):
    """rank-th highest gap between two curves, or inf with too few common dates."""
    ma, mb = s.mean(a, what), s.mean(b, what)
    g = np.abs(ma - mb)
    g = g[~np.isnan(g)]
    if g.size < MIN_COMMON or g.size < RANK:
        return np.inf
    return float(np.sort(g)[-RANK])


def find(parent, r):
    while parent[r] != r:
        parent[r] = parent[parent[r]]
        r = parent[r]
    return r


def merge(p, rule='current', record=None, **kw):
    """Returns new id per watershed label (index 0..n). record, a list, gets (a, b, ridge, line, gap) per join."""
    prs = pairs(p)
    prs.sort(key=lambda t: (t[2], t[0], t[1]))
    n = p['n']
    parent = list(range(n + 1))
    s = Sums(p)
    for a, b, ridge, k, line in prs:
        if not rule_allows(rule, ridge, k, line, kw, pass_='first'):
            continue
        ra, rb = find(parent, a), find(parent, b)
        if ra == rb or gap(s, ra, rb) > kw.get('tol', CURVE_TOL):
            continue
        if not extra_ok(rule, s, ra, rb, kw):
            continue
        if record is not None:
            record.append((a, b, ridge, line, gap(s, ra, rb)))
        root, other = min(ra, rb), max(ra, rb)
        parent[other] = root
        s.absorb(root, other)
    return relabel(parent, n)


def rule_allows(rule, ridge, k, line, kw, pass_):
    if rule == 'current':
        return ridge <= RIDGE_WEAK
    if rule == 'straight-holds':  # a border on a straight line never joins
        return ridge <= RIDGE_WEAK and line < kw.get('line_stop', 0.025)
    if rule == 'curvy-joins':  # a border off straight lines joins whatever its ridge, when curves match
        return ridge <= RIDGE_WEAK or line < kw.get('line_join', 0.01)
    if rule == 'both':
        if line >= kw.get('line_stop', 0.025):
            return False
        return ridge <= RIDGE_WEAK or line < kw.get('line_join', 0.01)
    raise ValueError(rule)


def extra_ok(rule, s, a, b, kw):
    bt = kw.get('bright_tol')
    if bt is None:
        return True
    return gap(s, a, b, 'bright') <= bt


def relabel(parent, n):
    new = np.zeros(n + 1, np.int64)
    ids = {}
    for old in range(1, n + 1):
        r = find(parent, old)
        if r not in ids:
            ids[r] = len(ids) + 1
        new[old] = ids[r]
    return new


def finish(p, new_id, min_ha=5.0, hole_ha=1.0):
    """Drop small regions and fill small holes, as Cleanup does; returns the label map."""
    lab = new_id[p['ws']]
    cnt = np.bincount(lab.ravel())
    small = cnt < round(min_ha * 100)
    small[0] = False
    lab = np.where(small[lab], 0, lab)
    zero = lab == 0
    comp, k = ndimage.label(zero)  # 4-connected
    if k:
        sizes = np.bincount(comp.ravel())
        h, w = lab.shape
        border = np.zeros(k + 1, bool)
        border[np.unique(np.r_[comp[0], comp[-1], comp[:, 0], comp[:, -1]])] = True
        # neighbouring labels of each zero component
        nb = {}
        for sa, sb in ((comp[:, :-1], lab[:, 1:]), (comp[:, 1:], lab[:, :-1]), (comp[:-1, :], lab[1:, :]),
                       (comp[1:, :], lab[:-1, :])):
            m = (sa > 0) & (sb > 0)
            for c, l in set(zip(sa[m].tolist(), sb[m].tolist())):
                nb.setdefault(c, set()).add(l)
        fill = np.zeros(k + 1, np.int64)
        for c, labels in nb.items():
            if len(labels) == 1 and not border[c] and sizes[c] < round(hole_ha * 100):
                fill[c] = next(iter(labels))
        lab = np.where(zero & (fill[comp] > 0), fill[comp], lab)
    return lab


def land_label(dates, values):
    """LandLabel.of with the default parameters."""
    present = values[~np.isnan(values)]
    if present.size < 5:
        return 'other'
    if np.median(present) < 0.05:
        return 'other'
    md = [d[5:] for d in dates]
    spring = [v for d, v in zip(md, values) if d <= '06-15' and not np.isnan(v)]
    peak = int(np.nanargmax(values))
    green_drop = False
    if values[peak] >= 0.5 and md[peak] >= '06-20':
        for d in range(peak + 1, len(values)):
            if md[d] > '09-30':
                break
            if not np.isnan(values[d]) and values[d] <= values[peak] - 0.2:
                green_drop = True
                break
    if spring and min(spring) < 0.3 and green_drop:
        return 'field'
    if (present < 0.3).sum() >= 0.7 * present.size:
        return 'fallow'
    return 'other'


def save(square, p, lab, version, year=2026):
    """Writes lab as an experiment version: fields-2026.tif and a GeoJSON holding only the properties."""
    import json, os, tifffile
    out = L.fields_dir(square, year, version)
    os.makedirs(out, exist_ok=True)
    ws = p['ws']
    m = (ws > 0) & (lab > 0)
    nf = int(lab.max())
    fid = np.zeros(p['n'] + 1, np.int64)
    fid[ws[m]] = lab[m]
    cnt = np.zeros((nf + 1, len(p['dates'])))
    sm = np.zeros_like(cnt)
    np.add.at(cnt, fid, p['count'])
    np.add.at(sm, fid, p['ndvi'])
    pix = np.bincount(lab.ravel(), minlength=nf + 1)
    feats = []
    for r in range(1, nf + 1):
        if pix[r] == 0:
            continue
        ok = (cnt[r] > 0) & (cnt[r] >= MIN_CLEAR * pix[r])
        v = np.full(cnt.shape[1], np.nan)
        v[ok] = sm[r][ok] / cnt[r][ok]
        feats.append({'type': 'Feature', 'geometry': None, 'properties': {
            'id': f'{year}-F{r:04d}', 'area_ha': round(pix[r] * 0.01, 2), 'land': land_label(p['dates'], v)}})
    tifffile.imwrite(out + f'fields-{year}.tif', lab.astype(np.uint16), compression='zlib')
    json.dump({'type': 'FeatureCollection', 'features': feats}, open(out + f'fields-{year}.geojson', 'w'))
    return out


def join_pieces(p, new_id, G=0.10, Lmax=0.05, min_crack=1, record=None, what='ndvi', bright_tol=None,
                max_small_ha=None, max_ratio=None):
    """Second joining pass on the regions after the first: neighbours whose curves are within G (3rd highest
    gap) and whose shared border's median line strength is under Lmax are joined, closest curves first; each
    region joins at most once per round; rounds repeat until none joins. Returns the new id per watershed label."""
    ids = new_id.copy()
    s = Sums(p)
    while True:
        n = int(ids.max())
        lab = ids[p['ws']]
        agg = Sums.__new__(Sums)
        for name in ('count', 'ndvi', 'bright'):
            a = np.zeros((n + 1, getattr(s, name).shape[1]))
            np.add.at(a, ids, getattr(s, name))
            setattr(agg, name, a)
        agg.pixels = np.bincount(lab.ravel(), minlength=n + 1).astype(float)
        q = dict(p); q['ws'] = lab; q['n'] = n
        cands = []
        for a, b, ridge, k, line in pairs(q):
            if k < min_crack or line >= Lmax:
                continue
            g = gap(agg, a, b, what)
            if g > G:
                continue
            if bright_tol is not None and gap(agg, a, b, 'bright') > bright_tol:
                continue
            small, large = sorted((agg.pixels[a] / 100, agg.pixels[b] / 100))
            if max_small_ha is not None and small > max_small_ha:
                continue
            if max_ratio is not None and small > max_ratio * large:
                continue
            cands.append((g, a, b, ridge, line, k))
        cands.sort()
        used = set()
        target = np.arange(n + 1)
        joins = 0
        for g, a, b, ridge, line, k in cands:
            if a in used or b in used:
                continue
            used.update((a, b))
            target[max(a, b)] = min(a, b)
            joins += 1
            if record is not None:
                record.append((a, b, ridge, line, g, k, agg.pixels[a] / 100, agg.pixels[b] / 100,
                               np.nonzero(ids == a)[0], np.nonzero(ids == b)[0]))
        if joins == 0:
            return ids
        # renumber
        t = target[ids]
        _, ids = np.unique(t, return_inverse=True)
        ids = ids.astype(np.int64)
        if ids[0] != 0:
            ids = ids - ids[0]
