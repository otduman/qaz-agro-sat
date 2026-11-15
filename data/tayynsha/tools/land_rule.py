"""Land labels, rule v2 (land-label check, docs/superpowers/notes/2026-11-13-land-labels.md). A post-step on the field
map: it relabels each region (field, fallow, other) from its season curve, its spring wetness and the straightness of
its outline, and says which small enclosed regions should join the field around them. The field map itself (outlines,
region numbers) is not changed.

In plain words, a region is
  other    when it has under 5 clear dates, or its median NDVI is under 0.05 (water, salt);
  field    when it is bare in spring (lowest NDVI up to 15 June under 0.3), then green (peak NDVI at least 0.4, on or
           after 1 June), then drops (a later date up to 30 September at least 0.2 below the peak) -- the current
           rule with the peak allowed lower (0.4, was 0.5) and earlier (1 June, was 20 June);
  fallow   when it has that crop curve but its ground is worked bare in late summer: on its bare dates (NDVI under
           0.3) from 1 August to 15 September, at least 3 of them, the median tillage index (SWIR 1.6 - SWIR 2.2) /
           (SWIR 1.6 + SWIR 2.2) is under 0.04, which is bare soil, not straw stubble (weeds ploughed in, not a harvest);
  fallow   when at least 70% of its dates are under 0.3 NDVI (unchanged);
  other    otherwise.
Then a "not a field" guard: a region the steps above call field or fallow becomes other when it was wet or already
green in spring (median NDMI = (NIR - SWIR 1.6) / (NIR + SWIR 1.6) over its clear dates up to 15 June at least -0.10,
or open water (MNDWI > 0) on at least 25% of it on some spring date) AND its outline is crooked (under 30% of its
outline on straight sides at least 200 m long, see land_lib.straight_share). Marsh, reeds, lake shores and meadows
pass the crop curve because they green up and dry off; fields here are dry and bare in spring and have straight sides.

Lonely blobs (added 2026-11-13 on the owner's rule, after the held-out run): a "field" under 20 ha with under half
its outline on straight sides, whose main neighbour is neither field nor fallow, is other.

Islands: a region with at least 80% of its border on one field region larger than itself, whose NDVI curve is within
0.20 of that field's (third-largest gap over at least 5 common dates), joins it: it takes the field's label and its
id in join_to.

python land_rule.py SQUARE YEAR      reads the experiment's curves-<year>.csv and shape-<year>.csv, writes
                                     land-<year>-v2.csv beside them
"""
import csv
import datetime as dt
import os
import statistics as st
import sys
from collections import defaultdict
from typing import NamedTuple

MIN_DATES = 5
WATER_MEDIAN = 0.05
SPRING_END = (6, 15)
SPRING_BARE = 0.3
PEAK_GREEN = 0.4
PEAK_FROM = (6, 1)
DROP_BY = 0.2
DROP_UNTIL = (9, 30)
FALLOW_BARE = 0.3
FALLOW_SHARE = 0.7
WORKED_FROM = (8, 1)
WORKED_TO = (9, 15)
WORKED_NDTI = 0.04
WORKED_DATES = 3
WET_NDMI = -0.10
WET_WATER = 0.25
CROOKED = 0.30
ISLAND_ENCLOSURE = 0.8
ISLAND_GAP = 0.20
ISLAND_COMMON = 5
LONELY_HA = 20
LONELY_STRAIGHT = 0.5

GUARD = 'wet or green in spring, crooked outline'


class Point(NamedTuple):
    date: dt.date
    ndvi: float
    ndmi: float
    water: float
    swir16: float
    swir22: float


def _md(day):
    return day.month, day.day


def crop_curve(points):
    spring = [p.ndvi for p in points if _md(p.date) <= SPRING_END]
    if not spring or min(spring) >= SPRING_BARE:
        return False
    peak = max(range(len(points)), key=lambda k: points[k].ndvi)
    top = points[peak]
    if top.ndvi < PEAK_GREEN or _md(top.date) < PEAK_FROM:
        return False
    return any(p.ndvi <= top.ndvi - DROP_BY for p in points[peak + 1:] if _md(p.date) <= DROP_UNTIL)


def worked_bare(points):
    ndti = [(p.swir16 - p.swir22) / (p.swir16 + p.swir22) for p in points
            if WORKED_FROM <= _md(p.date) <= WORKED_TO and p.ndvi < FALLOW_BARE and p.swir16 + p.swir22 > 0]
    return len(ndti) >= WORKED_DATES and st.median(ndti) < WORKED_NDTI


def wet_in_spring(points):
    spring = [p for p in points if _md(p.date) <= SPRING_END]
    if not spring:
        return False
    return st.median(p.ndmi for p in spring) >= WET_NDMI or max(p.water for p in spring) >= WET_WATER


def label(points, straight):
    """points: the region's clear dates in date order. -> (label, reason)."""
    if len(points) < MIN_DATES:
        return 'other', 'too few clear dates'
    if st.median(p.ndvi for p in points) < WATER_MEDIAN:
        return 'other', 'water'
    guarded = wet_in_spring(points) and straight < CROOKED
    if crop_curve(points):
        if guarded:
            return 'other', GUARD
        if worked_bare(points):
            return 'fallow', 'crop curve, then worked bare'
        return 'field', 'crop curve'
    if sum(p.ndvi < FALLOW_BARE for p in points) >= FALLOW_SHARE * len(points):
        if guarded:
            return 'other', GUARD
        return 'fallow', 'bare most of the season'
    return 'other', 'no crop curve'


def curve_gap(a, b):
    """a, b: date -> NDVI. The third-largest gap over common dates, None under ISLAND_COMMON common dates."""
    gaps = sorted((abs(a[k] - b[k]) for k in a if k in b), reverse=True)
    return gaps[2] if len(gaps) >= ISLAND_COMMON else None


def joins(enclosure, parent_label, parent_ha, own_ha, gap):
    return (enclosure >= ISLAND_ENCLOSURE and parent_label == 'field' and parent_ha > own_ha and gap is not None
            and gap <= ISLAND_GAP)


def lonely(label, area_ha, straight, neighbour):
    """A small crooked "field" on its own: under 20 ha, under half its outline on straight sides, and the region it
    shares most border with is neither field nor fallow (steppe, woods, marsh). The owner's rule: fields here are big
    enough and straight-sided, and lie among other fields."""
    return (label == 'field' and area_ha < LONELY_HA and straight < LONELY_STRAIGHT
            and neighbour not in ('field', 'fallow'))


def _f(x):
    return float(x) if x not in ('', None) else None


def relabel(curves_csv, shape_csv, year):
    """Rows of land-<year>-v2.csv for one square."""
    points = defaultdict(list)
    for r in csv.DictReader(open(curves_csv, encoding='utf-8')):
        if r['ndvi'] == '':
            continue
        points[int(r['region'])].append(Point(dt.date.fromisoformat(r['date']), float(r['ndvi']), float(r['ndmi']),
                                              float(r['water_share']), float(r['swir16']), float(r['swir22'])))
    shape = {int(r['region']): r for r in csv.DictReader(open(shape_csv, encoding='utf-8'))}
    out = {}
    for reg, s in shape.items():
        p = sorted(points.get(reg, []))
        lab, why = label(p, float(s['straight']))
        out[reg] = dict(region=reg, id=f'{year}-F{reg:04d}', area_ha=s['area_ha'], core=s['core'], land=s['land'],
                        land_v2=lab, reason=why, join_to='')
    for reg, s in shape.items():
        parent = int(s['enclosed_by'])
        if parent not in out or parent == reg:
            continue
        gap = curve_gap({p.date: p.ndvi for p in points.get(reg, [])}, {p.date: p.ndvi for p in points.get(parent, [])})
        if joins(float(s['enclosure']), out[parent]['land_v2'], float(shape[parent]['area_ha']), float(s['area_ha']),
                 gap):
            out[reg]['join_to'] = out[parent]['id']
            out[reg]['land_v2'] = out[parent]['land_v2']
            out[reg]['reason'] += ', island joins ' + out[parent]['id']
    before = {k: v['land_v2'] for k, v in out.items()}
    for reg, s in shape.items():
        if lonely(before[reg], float(s['area_ha']), float(s['straight']), before.get(int(s['enclosed_by']))):
            out[reg]['land_v2'] = 'other'
            out[reg]['reason'] += ', small and crooked on its own'
    return [out[k] for k in sorted(out)]


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import fields_lib as L
    square, year = sys.argv[1], int(sys.argv[2])
    d = L.DATA + f'experiments/land-labels-2026-11-13/{L.district_of(square)}/{square}/'
    rows = relabel(d + f'curves-{year}.csv', d + f'shape-{year}.csv', year)
    with open(d + f'land-{year}-v2.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    moved = sum(r['land'] != r['land_v2'] for r in rows)
    print(f'{square} {year}: {len(rows)} regions, {moved} relabelled, '
          f'{sum(bool(r["join_to"]) for r in rows)} islands join their field')


if __name__ == '__main__':
    main()
