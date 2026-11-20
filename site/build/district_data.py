"""One district's data for the Field Atlas: fields on the page grid with crop group, harvest window and holder,
survey squares, district outline, companies with machinery need, harvest pace (all, grain group, other holders),
crop and field-size summaries. Generalised from data/tayynsha/tools/brief_data.py.

Names: companies and cooperatives are named; peasant farms, partnerships and private holders only by kind. The
result holds company names, so it is written only into the gitignored site/dist."""
import csv
import datetime as dt
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', '..', 'data', 'tayynsha', 'tools'))
import geo  # noqa: E402

TOLERANCE_KM = 0.02
NAMED = {'company', 'cooperative'}
KIND = {'farm': 'farm', 'peasant farm': 'farm', 'holder hidden': 'private', 'shared lease': 'lease',
        'state college': 'college'}
GROUP = {'short season': 'grain', 'long season': 'oilseed', 'yellow flowers': 'oilseed', 'perennial grass': 'other',
         'unclear': 'none', 'no crop seen': 'none'}
GRAIN, OILSEED = ('short season',), ('long season', 'yellow flowers')
PACE_DAYS = 100


def name(holder, kind):
    """Companies and cooperatives by name, in «quotes»; everyone else None (the page shows their kind)."""
    if kind not in NAMED:
        return None
    form, _, rest = holder.partition(' ')
    n = rest.strip().strip('"').replace('""', '"')
    if n.count('"') % 2:  # the cadastre drops the closing quote of a name inside the name
        n += '"'
    return f'{form} «{n}»'


def pace(rows, year):
    """rows: (cut_middle or '', ha, is_group) for grain fields. Cumulative cut share by day from 25 July for all
    fields, the grain group's and the other holders', each of its own total."""
    days = [(dt.date(int(year), 7, 25) + dt.timedelta(days=i)).isoformat() for i in range(PACE_DAYS)]

    def series(sel):
        total = sum(ha for _, ha, _ in sel)
        return [[d, round(sum(ha for m, ha, _ in sel if m and m <= d) / total, 4) if total else 0] for d in days]

    return dict(all=series(rows), group=series([r for r in rows if r[2]]), other=series([r for r in rows if not r[2]]))


def public_figures(district_dir):
    """holder -> {whole_farm, source, url} from <district>/company-public.csv, or {} when there is none."""
    p = os.path.join(district_dir, 'company-public.csv')
    if not os.path.isfile(p):
        return {}
    return {r['holder']: r for r in csv.DictReader(open(p, encoding='utf-8'))}


def registry_figures(district_dir):
    """holder -> {tax: mln tenge paid in 2025, rev: bn tenge estimated revenue 2025, staff: employee band, reg_url}
    from <district>/company-registry.csv (Statsnet figures; revenue there is an estimate from taxes), or {}."""
    p = os.path.join(district_dir, 'company-registry.csv')
    if not os.path.isfile(p):
        return {}
    num = lambda v, div: round(float(v) / div, 1) if v else None
    return {r['holder']: dict(tax=num(r['tax_2025'], 1e6), rev=num(r['revenue_2025'], 1e9), staff=r['staff'] or None,
                              reg_url=r['url'] or None)
            for r in csv.DictReader(open(p, encoding='utf-8'))}


def plot_outlines(paths, proj):
    """Company plot outlines from GeoJSON files ({holder} in properties) as [{p: flat ring on the page grid, name}]."""
    out = []
    for path in paths or []:
        for f in json.load(open(path, encoding='utf-8'))['features']:
            ring = f['geometry']['coordinates'][0]
            out.append(dict(p=[round(v, 2) for lon, lat in ring for v in proj(lon, lat)],
                            name=name(f['properties'].get('holder', ''), 'company')))
    return out


DARK_SWIR = 0.12
ISLAND_SHARE = 0.8


SMALL_SHARE, SMALL_PX, SMALL_RATIO = 0.6, 2000, 5   # 2,000 px of 10 m = 20 ha


def island_parents(raster, share=ISLAND_SHARE, small_share=SMALL_SHARE, small_px=SMALL_PX, small_ratio=SMALL_RATIO):
    """Regions lying inside another: {region: parent} where at least `share` of the region's border (its 4-neighbour
    contacts with other labels, 0 = no region included) touches one bigger region; a small region (under small_px
    pixels) also counts when `small_share` of its border touches a region at least small_ratio times its size."""
    import numpy as np
    pairs = []
    for a, b in ((raster[:, :-1], raster[:, 1:]), (raster[:-1, :], raster[1:, :])):
        m = a != b
        pairs.append(np.stack([a[m], b[m]], 1))
        pairs.append(np.stack([b[m], a[m]], 1))
    p = np.concatenate(pairs)
    p = p[p[:, 0] > 0]
    if not len(p):
        return {}
    keys, counts = np.unique(p, axis=0, return_counts=True)
    size = np.bincount(raster.ravel())
    total, best = {}, {}
    for (a, b), c in zip(keys.tolist(), counts.tolist()):
        total[a] = total.get(a, 0) + c
        if b > 0 and c > best.get(a, (0, 0))[1]:
            best[a] = (b, c)
    out = {}
    for a, (b, c) in best.items():
        if (c >= share * total[a] and size[b] > size[a]) or                 (size[a] < small_px and c >= small_share * total[a] and size[b] >= small_ratio * size[a]):
            out[a] = b
    return out


def inherit_from_parents(owner, parents, keys, year):
    """owner with every unlinked key that is an island inside a linked region given that region's holder.
    parents: square -> {region: parent region} (raster numbers); keys: (square, field id) of the table."""
    out = dict(owner)
    for sq, fid in keys:
        if (sq, fid) in out:
            continue
        parent = parents.get(sq, {}).get(int(fid.split('-F')[1]))
        h = owner.get((sq, f'{year}-F{parent:04d}')) if parent else None
        if h:
            out[(sq, fid)] = h
    return out


MIN_FIELD_HA = 50   # owner 2026-11-15: "be stricter towards what counts as a field, cut the number a bit"


def idle_fallow(district_dir, year):
    """(square, region) of fallow that was fallow the other season too (data/tayynsha/tools/fallow_both_years.py):
    idle land, not shown as fallow; and fallow green or damp in spring, which is steppe (fallow_steppe.py, Esil)."""
    out = set()
    for name in ('fallow-both', 'fallow-steppe'):
        p = f'{district_dir}/{name}-{year}.csv'
        if os.path.isfile(p):
            out |= {(r['square'], r['region']) for r in csv.DictReader(open(p, encoding='utf-8'))}
    return out


def strict_fields(fields, fallow, min_ha=MIN_FIELD_HA, keep_small=frozenset()):
    """(fields, fallow) after the stricter rule: a field where no crop was seen all season is shown as fallow; a field
    under min_ha that no neighbour absorbed is left off (under 1% of the area), unless keep_small lists it (a real
    small field: its outline is a seen line, evidence_joins.py, 2026-11-19)."""
    kept, fal = [], list(fallow)
    for f in fields:
        if f.get('c') == 'none':
            fal.append(dict(p=f['p'], ha=f['ha']))
        elif f['ha'] >= min_ha or f.get('_k') in keep_small:
            kept.append(f)
    return kept, fal


def small_fields(district_dir, year):
    """(square, field id) of real small fields kept under the 50 ha mark (small-fields-YEAR.csv), or an empty set."""
    p = f'{district_dir}/small-fields-{year}.csv'
    if not os.path.isfile(p):
        return frozenset()
    return frozenset((r['square'], r['keep']) for r in csv.DictReader(open(p, encoding='utf-8')))


def fold_islands(keyed, parents, year, alias=None):
    """keyed: [((square, field id), field dict)]. An island whose surrounding region is a field in the list is not
    shown on its own: its hectares join that field (the field's outline already covers it). alias: a joined piece's
    key -> the key of the field it was joined into. Returns the field dicts."""
    index = {k: f for k, f in keyed}
    alias = alias or {}
    folded = set()
    for (sq, fid), f in keyed:
        p = parents.get(sq, {}).get(int(fid.split('-F')[1]))
        host = index.get(alias.get((sq, f'{year}-F{p:04d}'), (sq, f'{year}-F{p:04d}'))) if p else None
        if host is not None and host is not f:
            host['ha'] += f['ha']
            folded.add((sq, fid))
    return [f for k, f in keyed if k not in folded]


def read_joins(district_dir, year, proj):
    """Groups of field pieces joined into one field (data/tayynsha/tools/field_joins.py): [{square, members, keep,
    p}] with the group's outline projected like the fields'."""
    path = f'{district_dir}/joins-{year}.geojson'
    if not os.path.isfile(path):
        return []
    out = []
    for f in json.load(open(path, encoding='utf-8'))['features']:
        q = f['properties']
        pts = geo.simplify([proj(lon, lat) for lon, lat in f['geometry']['coordinates'][0]], TOLERANCE_KM)
        out.append(dict(square=q['square'], members=q['members'], keep=q['keep'], p=[round(v, 2) for pt in pts for v in pt]))
    return out


def read_splits(district_dir, year, proj):
    """(square, keep member) -> [{p, share}]: fields cut along a line that shows on the photos (field_splits.py,
    data/experiments/square-fields-2026-11-18/NOTE.md part S). {} when there is no file."""
    out = defaultdict(list)
    # splits along inner corners, then (lines first, 2026-11-19) along lines seen on many photos, whose pieces carry
    # their own crop group and harvest dates (data/experiments/lines-first-2026-11-18/NOTE.md)
    earlier = set()
    for name in (f'splits-{year}.geojson', f'line-splits-{year}.geojson'):
        path = f'{district_dir}/{name}'
        if not os.path.isfile(path):
            continue
        if name.startswith('line'):
            earlier = set(out)                  # a field split along inner corners keeps that split
        for f in json.load(open(path, encoding='utf-8'))['features']:
            q = f['properties']
            if (q['square'], q['keep']) in earlier:
                continue
            pts = geo.simplify([proj(lon, lat) for lon, lat in f['geometry']['coordinates'][0]], TOLERANCE_KM)
            piece = dict(p=[round(v, 2) for pt in pts for v in pt], share=q['share'], piece=q.get('piece', 0))
            piece.update({k: q[k] for k in ('c', 's', 'f', 't', 'm') if k in q})
            out[(q['square'], q['keep'])].append(piece)
    return dict(out)


def outside_fields(patches, fields):
    """Patches (grass, fallow) whose centre does not lie inside a drawn field: an island belongs to the field around
    it (owner 2026-11-13; data/experiments/square-fields-2026-11-18 part J v3)."""
    boxes = []
    for f in fields:
        xs, ys = f['p'][0::2], f['p'][1::2]
        boxes.append((min(xs), min(ys), max(xs), max(ys), f['p']))
    out = []
    for g in patches:
        n = len(g['p']) // 2
        x, y = sum(g['p'][0::2]) / n, sum(g['p'][1::2]) / n
        if not any(x0 <= x <= x1 and y0 <= y <= y1 and _inside(x, y, p) for x0, y0, x1, y1, p in boxes):
            out.append(g)
    return out


def split_fields(fields, splits):
    """Fields with a split drawn as its pieces: each piece the field's facts with its own outline and its share of
    the hectares."""
    out = []
    for f in fields:
        pieces = splits.get(f.get('_k'))
        if not pieces:
            out.append(f)
            continue
        out += [dict(f, p=q['p'], ha=round(f['ha'] * q['share']), _piece=q.get('piece', 0),
                     **{k: q[k] for k in ('c', 's', 'f', 't', 'm') if k in q})
                for q in pieces]
    return out


def read_outlines(district_dir, year, proj):
    """(square, keep, piece) -> simplified outline (simple_outlines.py, 2026-11-19), or {} when there is no file."""
    path = f'{district_dir}/outlines-{year}.geojson'
    if not os.path.isfile(path):
        return {}
    out = {}
    for f in json.load(open(path, encoding='utf-8'))['features']:
        q = f['properties']
        pts = geo.simplify([proj(lon, lat) for lon, lat in f['geometry']['coordinates'][0]], TOLERANCE_KM)
        out[(q['square'], q['keep'], q['piece'])] = [round(v, 2) for pt in pts for v in pt]
    return out


def draw_simple(fields, outlines):
    """Fields drawn with their simplified outline where there is one; nothing else changes."""
    out = []
    for f in fields:
        k = f.get('_k')
        p = outlines.get((k[0], k[1], f.get('_piece', 0))) if k else None
        out.append(dict(f, p=p) if p else f)
    return out


def share_group_holders(owner, joins):
    """owner with every piece of a joined field given the field's holder, when its pieces name exactly one."""
    out = dict(owner)
    for j in joins:
        keys = [(j['square'], m) for m in j['members']]
        hs = {owner[k] for k in keys if k in owner}
        if len(hs) == 1:
            h = hs.pop()
            out.update({k: h for k in keys})
    return out


def join_fields(keyed, joins):
    """Joined pieces shown as one field: the group's outline, the pieces' hectares summed, the crop with the most
    hectares, harvest and holder of the largest piece (holder of any piece if it has none). Returns (keyed, alias)
    with alias: a joined-away piece's key -> the key it was joined into."""
    index = dict(keyed)
    alias = {}
    for j in joins:
        keys = [(j['square'], m) for m in j['members'] if (j['square'], m) in index]
        if len(keys) == 1 and len(j['members']) == 1:
            # squares first 2026-11-18: one field drawn with its straightened outline (square_fields.py)
            index[keys[0]] = dict(index[keys[0]], p=j['p'])
            continue
        if len(keys) < 2:
            continue
        keep = (j['square'], j['keep'])
        if keep not in keys:
            keep = max(keys, key=lambda k: index[k]['ha'])
        f = dict(index[keep])
        crop = defaultdict(float)
        for k in keys:
            crop[index[k]['c']] += index[k]['ha']
        f.update(ha=sum(index[k]['ha'] for k in keys), c=max(crop, key=crop.get), p=j['p'])
        if not f.get('h'):
            for k in keys:
                if index[k].get('h'):
                    f.update(h=index[k]['h'], k=index[k]['k'])
                    break
        index[keep] = f
        alias.update({k: keep for k in keys if k != keep})
    return [(k, index[k]) for k, _ in keyed if k not in alias], alias


def island_parents_of(district_dir, year, squares):
    """square -> island_parents of its region raster, for the given squares."""
    import tifffile
    out = {}
    for sq in squares:
        p = f'{district_dir}/{sq}/fields-{year}/fields-{year}.tif'
        if os.path.isfile(p):
            out[sq] = island_parents(tifffile.imread(p).astype('int64'))
    return out


def dark_regions(district_dir, year, squares):
    """(square, region id) of regions dark in short-wave infrared all summer: median B11 (SWIR 1.6) under 0.12 over
    their clear dates (90% clear) from June to August, at least 4 of them. That is open or shallow water and wet marsh;
    bare soil, crops and stubble here stay above 0.17. Such a region is drawn neither as a field nor as fallow."""
    out = set()
    for sq in squares:
        p = f'{district_dir}/{sq}/series-{year}/series-{year}.csv'
        if not os.path.isfile(p):
            continue
        b11 = defaultdict(list)
        for r in csv.DictReader(open(p, encoding='utf-8')):
            if r['b11'] and float(r['clear_share'] or 0) >= 0.9 and '06-01' <= r['date'][5:] <= '08-31':
                b11[r['field']].append(float(r['b11']))
        out |= {(sq, f) for f, v in b11.items() if len(v) >= 4 and sorted(v)[len(v) // 2] < DARK_SWIR}
    return out


def duplicates_of(district_dir, year):
    """(copies to skip, {copy skipped: copy kept}) from duplicates-<year>.csv (data/tayynsha/tools/
    square_duplicates.py): a field cut by two overlapping squares is counted by both; the shorter copy goes."""
    path = f'{district_dir}/duplicates-{year}.csv'
    if not os.path.isfile(path):
        return set(), {}
    kept = {(r['square'], r['region']): (r['kept_square'], r['kept_region'])
            for r in csv.DictReader(open(path, encoding='utf-8'))}
    return set(kept), kept


def move_holders(owner, kept):
    """owner with the holder of a skipped copy given to the copy kept, when that one has none."""
    out = dict(owner)
    for k, v in kept.items():
        if k in owner and v not in owner:
            out[v] = owner[k]
    return out


def land_regions(district_dir, year, proj, land, skip=frozenset(), only=None):
    """Regions of one land label (e.g. 'fallow') as [{p, ha}], each taken once: from the square whose core (its box
    less the 0.5 km overlap on every side) holds the region's centre. only: the squares in the district table (a square
    still being processed is left out)."""
    out = []
    for r in csv.DictReader(open(f'{district_dir}/squares.csv', encoding='utf-8')):
        sq = r['square']
        if only is not None and sq not in only:
            continue
        table = f'{district_dir}/{sq}/series-{year}/fields-{year}.csv'
        shapes = f'{district_dir}/{sq}/fields-{year}/fields-{year}.geojson'
        if not (os.path.isfile(table) and os.path.isfile(shapes)):
            continue
        e0, n0, e1, n1 = (float(r[k]) for k in ('min_e', 'min_n', 'max_e', 'max_n'))
        keep = {}
        for x in csv.DictReader(open(table, encoding='utf-8')):
            if x['land'] != land or (sq, x['id']) in skip:
                continue
            e, n = geo.utm42(float(x['centroid_lon']), float(x['centroid_lat']))
            if e0 + 500 <= e < e1 - 500 and n0 + 500 <= n < n1 - 500:
                keep[x['id']] = round(float(x['area_ha']))
        for f in json.load(open(shapes, encoding='utf-8'))['features']:
            fid = f['properties']['id']
            if fid in keep:
                g = f['geometry']
                ring = g['coordinates'][0] if g['type'] == 'Polygon' else max((q[0] for q in g['coordinates']), key=len)
                pts = geo.simplify([proj(lon, lat) for lon, lat in ring], TOLERANCE_KM)
                out.append(dict(p=[round(v, 2) for q in pts for v in q], ha=keep[fid]))
    return out


def _inside(x, y, p):
    """Point (x, y) inside the flat ring p (ray casting)."""
    n, hit = len(p) // 2, False
    for i in range(n):
        x1, y1, x2, y2 = p[2 * i], p[2 * i + 1], p[2 * ((i + 1) % n)], p[2 * ((i + 1) % n) + 1]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def fold_inside(fields):
    """A field whose outline lies wholly inside a bigger field's drawn outline (a joined field's outline can cover a gap
    between its pieces) is not drawn on its own: its hectares join the bigger field. Returns (fields, alias: folded
    field's _k -> the _k of the field it joined)."""
    def box(f):
        xs, ys = f['p'][0::2], f['p'][1::2]
        return min(xs), min(ys), max(xs), max(ys)
    order = sorted(range(len(fields)), key=lambda i: -fields[i]['ha'])
    boxes = [box(f) for f in fields]
    gone, alias = set(), {}
    for a in order:
        if a in gone:
            continue
        ax0, ay0, ax1, ay1 = boxes[a]
        for b in order:
            if b == a or b in gone or fields[b]['ha'] >= fields[a]['ha']:
                continue
            bx0, by0, bx1, by1 = boxes[b]
            if bx0 < ax0 or by0 < ay0 or bx1 > ax1 or by1 > ay1:
                continue
            pb = fields[b]['p']
            if all(_inside(pb[2 * i], pb[2 * i + 1], fields[a]['p']) for i in range(len(pb) // 2)):
                fields[a]['ha'] += fields[b]['ha']
                gone.add(b)
                alias[fields[b].get('_k')] = fields[a].get('_k')
    return [f for i, f in enumerate(fields) if i not in gone], alias


def fallow_crops(district_dir, year):
    """(square, region id) -> field facts for map-fallow regions that are thin dry-land crops (straw stubble after a
    green summer; Esil 2026-11-17, blind check in data/experiments/esil-fallow-crop-2026-11-17/NOTE.md). {} if none."""
    p = f'{district_dir}/fallow-crop-{year}.csv'
    if not os.path.isfile(p):
        return {}
    return {(r['square'], f'{year}-F{int(r["region"]):04d}'): dict(
        ha=round(float(r['area_ha'])), c='grain', s=r.get('status') or 'no cut found', f=r.get('cut_from', ''),
        t=r.get('cut_to', ''), m=r.get('cut_middle', '')) for r in csv.DictReader(open(p, encoding='utf-8'))}


def grass_regions(district_dir, year, proj, only=None):
    """Regions listed in <district>/grass-<year>.csv (square, region number) as [{p, ha}]: "grass: hay and pasture",
    owner 2026-11-16, rule and blind check in data/experiments/hay-2026-11-16/NOTE.md. [] when there is no list."""
    p = f'{district_dir}/grass-{year}.csv'
    if not os.path.isfile(p):
        return []
    # 2026-11-18: grass that was a crop field another season leaves the layer (grass_history.py,
    # data/experiments/grass-history-2026-11-18/NOTE.md)
    was = f'{district_dir}/grass-was-field-{year}.csv'
    gone = {(r['square'], r['region']) for r in csv.DictReader(open(was, encoding='utf-8'))} if os.path.isfile(was) else set()
    # 2026-11-19: grass by use (pasture near villages and farms / old field / steppe), when listed
    # (data/experiments/grass-use-2026-11-19/NOTE.md)
    up = f'{district_dir}/grass-use-{year}.csv'
    use = {(r['square'], f'{year}-F{int(r["region"]):04d}'): r['cls']
           for r in csv.DictReader(open(up, encoding='utf-8'))} if os.path.isfile(up) else {}
    want = {}
    for r in csv.DictReader(open(p, encoding='utf-8')):
        if (r['square'], r['region']) in gone:
            continue
        if only is None or r['square'] in only:
            want.setdefault(r['square'], {})[f'{year}-F{int(r["region"]):04d}'] = round(float(r['area_ha']))
    out = []
    for sq, ids in sorted(want.items()):
        # Tayynsha 2026-11-17: its maps outline only fields, so land outside fields has its own outline file
        # (data/experiments/grass-tayynsha-2026-11-17/NOTE.md)
        shapes = f'{district_dir}/{sq}/other-{year}.geojson'
        if not os.path.isfile(shapes):
            shapes = f'{district_dir}/{sq}/fields-{year}/fields-{year}.geojson'
        if not os.path.isfile(shapes):
            continue
        for f in json.load(open(shapes, encoding='utf-8'))['features']:
            fid = f['properties']['id']
            if fid in ids:
                g = f['geometry']
                ring = g['coordinates'][0] if g['type'] == 'Polygon' else max((q[0] for q in g['coordinates']), key=len)
                pts = geo.simplify([proj(lon, lat) for lon, lat in ring], TOLERANCE_KM)
                rec = dict(p=[round(v, 2) for q in pts for v in q], ha=ids[fid])
                if use:
                    rec['u'] = use.get((sq, fid), 'steppe')
                out.append(rec)
    return out


def read_crops(district_dir, year, name='crops-{year}.csv'):
    """(square, field) -> crop group, from each square's crop file `name` ({year} filled in)."""
    crops = {}
    for sq in os.listdir(district_dir):
        p = f'{district_dir}/{sq}/' + name.format(year=year)
        if os.path.isfile(p):
            for c in csv.DictReader(open(p, encoding='utf-8')):
                crops[(sq, c['field'])] = c['group']
    return crops


def pairs_of(district_dir):
    """holder -> the holder it is shown with, from <district>/company-pairs.csv (holder,same_farm_as), or {}.
    Two registrations of one farm: an older company and a newer one with the same name on the same land."""
    p = os.path.join(district_dir, 'company-pairs.csv')
    if not os.path.isfile(p):
        return {}
    return {r['holder']: r['same_farm_as'] for r in csv.DictReader(open(p, encoding='utf-8'))}


def join_pairs(rows, pairs):
    """rows (holder, type, crop, ha) with each paired holder replaced by its partner, and per partner the names of the
    other registrations it took over: partner -> ['ТОО «Paired»']. The farm keeps one name, the partner's."""
    merged, joined = [], defaultdict(list)
    for h, t, c, ha in rows:
        to = pairs.get(h)
        if to:
            if h not in joined[to]:
                joined[to].append(h)
            h = to
        merged.append((h, t, c, ha))
    return merged, {to: [name(h, 'company') for h in olds] for to, olds in joined.items()}


def total(values):
    """Sum of the values that are known, or None when none is."""
    known = [v for v in values if v is not None]
    return round(sum(known), 1) if known else None


def group_accounts(accounts, group_name):
    """The group's member rows folded into one row named group_name, placed by size among the others. The group row
    sums area and combines and lists its members (largest first); a member whose name holds the group's own name gives
    the group row its whole-farm figure."""
    members = [a for a in accounts if a['group']]
    others = [a for a in accounts if not a['group']]
    if not members:
        return accounts
    members = sorted(members, key=lambda a: -a['ha'])
    key = group_name.lower()
    own = next((m for m in members if key in m['name'].lower()), None)
    g = dict(name=group_name, group=True, group_row=True, members_n=len(members), also=[],
             ha=sum(m['ha'] for m in members), grain=sum(m['grain'] for m in members), oil=sum(m['oil'] for m in members),
             avg=round(sum(m['avg'] for m in members), 1) if all(m['avg'] is not None for m in members) else None,
             tax=total(m.get('tax') for m in members), rev=total(m.get('rev') for m in members), staff=None,
             whole=own['whole'] if own else None, src=own['src'] if own else None, url=own['url'] if own else None)
    if own:
        own = dict(own, whole=None, src=None, url=None)
        members = [own if m['name'] == own['name'] else m for m in members]
    g['members'] = members
    return sorted([g] + others, key=lambda a: -a['ha'])


def group_with_pairs(in_group, pairs):
    """The group's holders plus every holder paired with one of them."""
    return set(in_group) | {h for h, to in pairs.items() if to in in_group}


def _rings(boundary_geojson):
    g = json.load(open(boundary_geojson, encoding='utf-8'))
    geom = g['features'][0]['geometry'] if 'features' in g else g.get('geometry', g)
    if geom['type'] == 'Polygon':
        return [geom['coordinates'][0]]
    return [p[0] for p in geom['coordinates']]


def _ring_points(ring):
    pts = [(lon, lat) for lon, lat in ring]
    return pts if len(pts) < 400 else pts[::max(1, len(pts) // 400)]


def build(district_dir, year, holders_csv, groups_csv, boundary_geojson, town_lonlat, ha_per_combine,
          crops_name='crops-{year}.csv', plots=None, holders_read=True, ref_year=None):
    import holder_pace
    import market

    holders, types = {}, {}
    if holders_csv and os.path.isfile(holders_csv):
        for r in csv.DictReader(open(holders_csv, encoding='utf-8-sig')):
            holders[(r['square'], r['field'])] = r['holder']
            types[r['holder']] = r['holder_type']
    pairs = pairs_of(district_dir)
    in_group = group_with_pairs({r['holder'] for r in csv.DictReader(open(groups_csv, encoding='utf-8'))}, pairs)
    dark = dark_regions(district_dir, year,
                        [r['square'] for r in csv.DictReader(open(f'{district_dir}/squares.csv', encoding='utf-8'))])
    dark = set(dark) | idle_fallow(district_dir, year)
    twice, kept = duplicates_of(district_dir, year)
    land_table = [r for r in csv.DictReader(open(f'{district_dir}/district-harvest-{year}.csv', encoding='utf-8'))
                  if r['land'] == 'field' and (r['square'], r['field']) not in dark]
    # map-fallow regions that are thin dry-land crops join the fields (Esil, fallow_crops)
    made_crop = fallow_crops(district_dir, year)
    if made_crop:
        cent = {}
        for sq in {k[0] for k in made_crop}:
            t = f'{district_dir}/{sq}/series-{year}/fields-{year}.csv'
            if os.path.isfile(t):
                for x in csv.DictReader(open(t, encoding='utf-8')):
                    cent[(sq, x['id'])] = (x['centroid_lat'], x['centroid_lon'])
        for k, v in made_crop.items():
            if k in cent and k not in dark:
                land_table.append(dict(square=k[0], field=k[1], land='field', area_ha=str(v['ha']), status=v['s'],
                                       cut_from=v['f'], cut_to=v['t'], cut_middle=v['m'], window_days='', rise='',
                                       readings='', centroid_lat=cent[k][0], centroid_lon=cent[k][1], touches_edge=''))
    # holders were read on the field map of ref_year; another season's fields take them by overlap
    owner = holder_pace.district_owners(district_dir, int(ref_year or year), holders, int(year), land_table) if holders else {}
    dark |= twice   # neither water nor a second copy of a field is drawn
    table = [r for r in land_table if (r['square'], r['field']) not in twice]
    keys = [(r['square'], r['field']) for r in table]
    parents = island_parents_of(district_dir, year, {k[0] for k in keys})
    if owner:
        # a small region lying inside a linked field belongs to the same land user
        owner = inherit_from_parents(owner, parents, keys, year)
    crops = read_crops(district_dir, year, crops_name)
    crops.update({k: 'short season' for k in made_crop})

    rings = _rings(boundary_geojson)
    lats = [p[1] for ring in rings for p in ring]
    lons = [p[0] for ring in rings for p in ring]
    lat0, lon0 = (min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2
    proj = geo.projector(lat0, lon0)
    # pieces of one field joined back together share its holder
    joins = read_joins(district_dir, year, proj)
    owner = move_holders(owner, kept)
    owner = share_group_holders(owner, joins)

    want = defaultdict(set)
    for r in table:
        want[r['square']].add(r['field'])
    shapes = {}
    for sq, ids in want.items():
        g = json.load(open(f'{district_dir}/{sq}/fields-{year}/fields-{year}.geojson', encoding='utf-8'))
        for f in g['features']:
            fid = f['properties']['id']
            if fid in ids:
                ring = f['geometry']['coordinates'][0] if f['geometry']['type'] == 'Polygon' \
                    else max((p[0] for p in f['geometry']['coordinates']), key=len)
                pts = geo.simplify([proj(lon, lat) for lon, lat in ring], TOLERANCE_KM)
                shapes[(sq, fid)] = [round(v, 2) for p in pts for v in p]

    def kind_of(h):
        t = types.get(h, '')
        if h in in_group:
            return 'group'
        return 'company' if t in NAMED else KIND.get(t, 'farm')

    read = {sq for sq, _ in holders} if holders_read else set()  # False: holders known only for some plots
    keyed = []
    for r in table:
        k = (r['square'], r['field'])
        if k not in shapes:
            continue
        h = owner.get(k)
        keyed.append((k, dict(p=shapes[k], ha=round(float(r['area_ha'])), c=GROUP.get(crops.get(k, 'unclear'), 'none'),
                           s=r['status'], f=r['cut_from'], t=r['cut_to'], m=r['cut_middle'],
                           h=name(h, types.get(h, '')) if h else None, k=kind_of(h) if h else None,
                           r=1 if r['square'] in read else 0)))
    # islands (a region almost wholly inside a field) are shown as part of that field
    for k, f in keyed:
        f['_k'] = k                           # each shown field remembers the piece it was built from
    pieces = [k for k, _ in keyed]
    keyed, alias = join_fields(keyed, joins)
    fields = fold_islands(keyed, parents, year, alias)
    fields, inner = fold_inside(fields)
    if inner:   # pieces of a folded field now belong to the field it joined
        alias = {**{k: inner.get(v, v) for k, v in alias.items()}, **inner}
    shown, fallow = strict_fields(fields, land_regions(district_dir, year, proj, 'fallow', dark | set(made_crop), only=set(want)),
                                  keep_small=small_fields(district_dir, year))
    shown = split_fields(shown, read_splits(district_dir, year, proj))
    shown = draw_simple(shown, read_outlines(district_dir, year, proj))
    left_off = {f['_k'] for f in fields} - {f['_k'] for f in shown}
    # pieces of a field the stricter rule left off (or showed as fallow) leave the company and pace figures too
    dropped = {k for k in pieces if alias.get(k, k) in left_off}
    # q, i: the square and region the field was built from (its greenness curve is read by these)
    fields = [{**{a: b for a, b in f.items() if a not in ('_k', '_piece')}, 'q': f['_k'][0],
               'i': int(f['_k'][1].split('-F')[1])} for f in shown]

    squares = []
    for r in csv.DictReader(open(f'{district_dir}/squares.csv', encoding='utf-8')):
        if r['square'] in want:
            box = [float(r[x]) for x in ('min_e', 'min_n', 'max_e', 'max_n')]
            squares.append(dict(id=r['square'], read=r['square'] in read, box=box, p=geo.utm_box(proj, *box),
                                epsg=int(r.get('epsg') or 32642)))
    outline = [[v for lon, lat in _ring_points(ring) for v in proj(lon, lat)] for ring in rings]

    rows = []
    for r in table:
        if (r['square'], r['field']) in dropped:
            continue
        h = owner.get((r['square'], r['field']))
        if h:
            rows.append((h, types.get(h, ''), crops.get((r['square'], r['field']), 'unclear'), float(r['area_ha'])))
    public = public_figures(district_dir)
    registry = registry_figures(district_dir)
    present = {r[0] for r in rows}
    rows, also = join_pairs(rows, pairs)
    accounts = []
    for a in market.summarise(rows):
        if a['type'] not in NAMED:
            continue
        combine_ha = a['grain_ha'] + a['oilseed_ha']
        pub = public.get(a['holder']) or next((public[h] for h, to in pairs.items() if to == a['holder'] and h in public), {})
        reg = registry.get(a['holder']) or next((registry[h] for h, to in pairs.items() if to == a['holder'] and h in registry), {})
        accounts.append(dict(name=name(a['holder'], a['type']), also=also.get(a['holder'], []),
                             group=a['holder'] in in_group, ha=a['field_ha'],
                             grain=a['grain_ha'], oil=a['oilseed_ha'],
                             avg=round(combine_ha / ha_per_combine, 1) if ha_per_combine else None,
                             tax=reg.get('tax'), rev=reg.get('rev'), staff=reg.get('staff'), reg_url=reg.get('reg_url'),
                             whole=pub.get('whole_farm') or None, src=pub.get('source') or None,
                             url=pub.get('url') or None))

    group_names = sorted({r['group'] for r in csv.DictReader(open(groups_csv, encoding='utf-8')) if r.get('group')})
    if len(group_names) == 1:
        accounts = group_accounts(accounts, group_names[0])

    grain_rows = [(r['cut_middle'], float(r['area_ha']), owner.get((r['square'], r['field'])) in in_group)
                  for r in table if crops.get((r['square'], r['field'])) in GRAIN and (r['square'], r['field']) not in dropped]
    crop_ha, sizes = defaultdict(float), {}
    for f in fields:
        crop_ha[f['c']] += f['ha']
        k = {'group': 'group', 'company': 'company', None: 'unread'}.get(f['k'], 'farm')
        s = sizes.setdefault(k, dict(fields=0, ha=0))
        s['fields'] += 1
        s['ha'] += f['ha']

    return dict(
        year=str(year), fields=fields, fallow=outside_fields(fallow, fields),
        grass=outside_fields(grass_regions(district_dir, year, proj, only=set(want)), fields), squares=squares, outline=outline, plots=plot_outlines(plots, proj), town=list(proj(*town_lonlat)), origin=[lat0, lon0],
        accounts=accounts, pace=pace(grain_rows, year),
        crop_ha={c: round(crop_ha.get(c, 0)) for c in ('grain', 'oilseed', 'other', 'none')}, sizes=sizes,
        totals=dict(fields=len(fields), ha=round(sum(x['ha'] for x in fields)),
                    linked_ha=round(sum(x['ha'] for x in fields if x['h'] or x['k'])),
                    known_ha=round(sum(x['ha'] for x in fields if x['k'] and x['k'] != 'private')),
                    group_ha=round(sum(x['ha'] for x in fields if x['k'] == 'group')),
                    grain_ha=round(sum(ha for _, ha, _ in grain_rows)),
                    private_ha=round(sum(x['ha'] for x in fields if x['k'] in ('farm', 'private', 'lease', 'college')))))
