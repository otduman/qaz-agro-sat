"""Fields drawn twice where survey squares meet (data/experiments/seam-overlaps-2026-11-22/NOTE.md). On the fields as
the site shows them (page km):

- S1 copies: two fields from different squares overlapping by at least COPY of the smaller one's drawn area are one
  field: drawn as their union, the larger one's facts, its hectares plus the smaller one's share outside the overlap
  (big Esil fields are copied deep into the neighbouring square, so dropping the smaller copy would lose land);
- S2 slivers across squares (overlap >= MIN_HA): the overlap belongs to the field whose square's core holds the
  overlap's centre (the larger field when neither does); it is cut out of the other field's outline, whose hectares
  shrink by the share of its drawn area cut away;
- S3 slivers inside one square: drawing only; cut from the field drawn larger than its hectares.

Used by site/build/district_data.py before piece joins.
"""
import os
import sys

from shapely.geometry import Polygon
from shapely.strtree import STRtree

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../site/build'))
import geo  # noqa: E402

COPY, MIN_HA, SIMPLIFY = 0.5, 0.1, 0.005


def poly(p):
    g = Polygon(list(zip(p[0::2], p[1::2])))
    return g if g.is_valid else g.buffer(0)


def _flat(g):
    if g.geom_type != 'Polygon':
        g = max(g.geoms, key=lambda x: x.area)
    pts = geo.simplify(list(g.exterior.coords), SIMPLIFY)
    return [round(v, 2) for pt in pts for v in pt]


def resolve(fields, cores):
    """(fields with copies merged and overlaps cut, {merged-away region key: key it now belongs to}).
    cores: square -> its core polygon on the page grid."""
    G = [poly(f['p']) for f in fields]
    out = [dict(f) for f in fields]
    drop, cut, into = set(), {}, {}
    tree = STRtree(G)
    for a, ga in enumerate(G):
        for b in sorted(int(x) for x in tree.query(ga)):
            if b <= a or a in drop or b in drop:
                continue
            gi = ga.intersection(G[b])
            ov = gi.area * 100
            if ov < MIN_HA:
                continue
            fa, fb = fields[a], fields[b]
            small, large = (a, b) if G[a].area <= G[b].area else (b, a)
            across = fa['_k'][0] != fb['_k'][0]
            if across and gi.area >= COPY * G[small].area:
                drop.add(small)
                into[small] = large
                out[large]['ha'] = out[large]['ha'] + round(fields[small]['ha'] * max(0.0, 1 - gi.area / G[small].area))
                G[large] = G[large].union(G[small])
                out[large]['p'] = _flat(G[large])
                continue
            if across:
                c = gi.centroid
                keep = a if cores.get(fa['_k'][0]) is not None and cores[fa['_k'][0]].contains(c) else \
                    b if cores.get(fb['_k'][0]) is not None and cores[fb['_k'][0]].contains(c) else large
            else:
                ratio = lambda i: G[i].area * 100 / max(fields[i]['ha'], 1)  # noqa: E731
                keep = a if ratio(a) <= ratio(b) else b
            lose = b if keep == a else a
            cut.setdefault(lose, []).append((gi, across))
    for i, pieces in cut.items():
        if i in drop:
            continue
        g = G[i]
        for gi, across in pieces:
            g = g.difference(gi)
        out[i]['p'] = _flat(g)
        shrink = sum(gi.area for gi, across in pieces if across)
        if shrink:
            out[i]['ha'] = round(out[i]['ha'] * max(0.0, 1 - shrink / G[i].area))
    def home(i):
        while i in into:
            i = into[i]
        return i

    merged = {fields[i]['_k']: fields[home(i)]['_k'] for i in drop if fields[i]['_k'] != fields[home(i)]['_k']}
    return [f for i, f in enumerate(out) if i not in drop], merged
