"""Piece joins J v1 (data/experiments/join-pieces-2026-11-21/NOTE.md, blind check on Google photos 16/18 joins right,
6/6 decoys real borders, 2026-11-22): two fields as the site shows them are one field when

- J1 the border between them is not a seen line: under SEEN_MAX of it (sampled every 10 m, 3 x 3 max) has a line
  count >= STRONG;
- J2 the same crop group;
- J3 not two different known holders;
- J4 not both dated with cut medians more than CUT_DAYS apart.

Neighbours: outlines within GAP km of each other along at least SHARED km. Chains join. The joined field is drawn
as the pieces' union (gaps up to GAP closed), holds their hectares, and keeps the largest piece's facts (crop,
harvest, the square and region its curve is read by) and the holder any piece knows.

Used by site/build/district_data.py on the fields it has drawn (after splits and simple outlines).
"""
import datetime as dt
import os
import sys

import numpy as np
from shapely.geometry import Polygon
from shapely.ops import unary_union
from shapely.strtree import STRtree

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../site/build'))
import geo  # noqa: E402

GAP, SHARED = 0.03, 0.15      # km
SEEN_MAX, STRONG, STEP = 0.5, 10, 0.01
CUT_DAYS = 5
SIMPLIFY = 0.02               # km


def poly(p):
    g = Polygon(list(zip(p[0::2], p[1::2])))
    return g if g.is_valid else g.buffer(0)


def seen_share(line, square, count_at):
    """Share of the samples along `line` (every STEP km) where count_at(square, x, y) >= STRONG; samples without a
    count (None) are skipped; None when no sample has a count."""
    vals = []
    for part in getattr(line, 'geoms', None) or [line]:
        if not hasattr(part, 'interpolate') or part.length == 0:
            continue
        n = max(1, int(round(part.length / STEP)))
        for i in range(n + 1):
            pt = part.interpolate(i / n, normalized=True)
            v = count_at(square, pt.x, pt.y)
            if v is not None:
                vals.append(v >= STRONG)
    return float(np.mean(vals)) if vals else None


def _day(m):
    try:
        return dt.date.fromisoformat(m)
    except (TypeError, ValueError):
        return None


def same_field(a, b, share):
    """J1-J4 for two shown fields with `share` of their border seen."""
    if share is None or share >= SEEN_MAX:
        return False
    if a['c'] != b['c']:
        return False
    if a.get('_o') and b.get('_o') and a['_o'] != b['_o']:
        return False
    da, db = _day(a.get('m')), _day(b.get('m'))
    return not (da and db and abs((da - db).days) > CUT_DAYS)


def join(fields, count_at):
    """(fields with J v1 joins applied, {joined-away region key: region key it now belongs to}).
    count_at(square, x_km, y_km) -> line count (3 x 3 max) or None."""
    G = [poly(f['p']) for f in fields]
    tree = STRtree(G)
    parent = list(range(len(fields)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for a, ga in enumerate(G):
        for b in sorted(int(x) for x in tree.query(ga.buffer(GAP))):
            if b <= a:
                continue
            line = ga.boundary.intersection(G[b].buffer(GAP))
            if line.length < SHARED:
                continue
            share = seen_share(line, fields[a]['_k'][0], count_at)
            if share is None:
                share = seen_share(line, fields[b]['_k'][0], count_at)
            if same_field(fields[a], fields[b], share):
                parent[find(a)] = find(b)
    groups = {}
    for i in range(len(fields)):
        groups.setdefault(find(i), []).append(i)
    out, merged = [], {}
    for members in sorted(groups.values()):
        if len(members) == 1:
            out.append(fields[members[0]])
            continue
        members.sort(key=lambda i: -fields[i]['ha'])
        f = dict(fields[members[0]])
        f['ha'] = sum(fields[i]['ha'] for i in members)
        if not f.get('_o'):
            for i in members:
                if fields[i].get('_o'):
                    f.update(h=fields[i]['h'], k=fields[i]['k'], _o=fields[i]['_o'])
                    break
        f['r'] = max(fields[i].get('r', 0) for i in members)
        u = unary_union([G[i].buffer(GAP / 2, join_style='mitre') for i in members]).buffer(-GAP / 2, join_style='mitre')
        if u.geom_type != 'Polygon':
            u = max(u.geoms, key=lambda g: g.area)
        pts = geo.simplify(list(u.exterior.coords), SIMPLIFY)
        f['p'] = [round(v, 2) for pt in pts for v in pt]
        out.append(f)
        merged.update({fields[i]['_k']: f['_k'] for i in members[1:] if fields[i]['_k'] != f['_k']})
    return out, merged


def count_reader(district_dir, year, proj_inverse):
    """count_at(square, x_km, y_km) for a district: the square's line count for YEAR (lines-YEAR/count.npz, the
    season's 10 m grid), 3 x 3 max; None off the grid or without a count. proj_inverse: page km -> (lon, lat)."""
    cache = {}

    def load(sq):
        if sq not in cache:
            cache[sq] = None
            p = f'{district_dir}/{sq}/lines-{year}/count.npz'
            props = f'{district_dir}/{sq}/season-{year}/stack.properties'
            if os.path.isfile(p) and os.path.isfile(props):
                kv = dict(line.strip().split('=', 1) for line in open(props, encoding='utf-8') if '=' in line)
                epsg, e0, n0, size, _, _ = kv['grid'].split(',')
                cache[sq] = (np.load(p)['count'], float(e0), float(n0), float(size), (int(epsg) - 32600) * 6 - 183)
        return cache[sq]

    def count_at(sq, x, y):
        g = load(sq)
        if g is None:
            return None
        c, e0, n0, size, lon0 = g
        e, n = geo.utm42(*proj_inverse(x, y), lon0=lon0)
        r, q = int((n0 - n) / size), int((e - e0) / size)
        if not (1 <= r < c.shape[0] - 1 and 1 <= q < c.shape[1] - 1):
            return None
        return int(c[r - 1:r + 2, q - 1:q + 2].max())

    return count_at
