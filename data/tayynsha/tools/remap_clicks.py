"""Move the cadastral map clicks of one square-season from the old field map to the new one.

A click was made at an old field's centre. It now belongs to the new field whose outline holds that centre, or, when
the centre falls in a gap, to the new field whose edge is within 150 m; otherwise it is dropped.

Usage: remap_clicks.py <square dir> <year>
Reads  series-<year>-f1/fields-<year>.csv (old centres), fields-<year>/fields-<year>.geojson (new outlines),
       plot-clicks-<year>-f1.csv (made from plot-clicks-<year>.csv on the first run)
Writes plot-clicks-<year>.csv with the new field ids.
"""
import csv
import json
import math
import os
import shutil
import sys

NEAR_KM = 0.150


def _inside(lat, lon, ring):
    hit = False
    for i in range(len(ring)):
        x1, y1 = ring[i - 1]
        x2, y2 = ring[i]
        if (y1 > lat) != (y2 > lat) and lon < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def _km_to_ring(lat, lon, ring):
    kx = 111.32 * math.cos(math.radians(lat))
    best = float('inf')
    for i in range(len(ring)):
        (x1, y1), (x2, y2) = ring[i - 1], ring[i]
        ax, ay = (x1 - lon) * kx, (y1 - lat) * 110.57
        bx, by = (x2 - lon) * kx, (y2 - lat) * 110.57
        dx, dy = bx - ax, by - ay
        t = 0 if dx == dy == 0 else max(0, min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
        best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    return best


def remap(old_centres, new_polygons):
    """old_centres: old field id -> (lat, lon); new_polygons: new field id -> list of rings of [lon, lat].
    Returns old id -> new id for every old field that lands in or near a new field."""
    out = {}
    for old, (lat, lon) in old_centres.items():
        hit = next((new for new, rings in new_polygons.items() if _inside(lat, lon, rings[0])), None)
        if hit is None:
            d, near = min(((_km_to_ring(lat, lon, rings[0]), new) for new, rings in new_polygons.items()),
                          default=(None, None))
            hit = near if d is not None and d <= NEAR_KM else None
        if hit:
            out[old] = hit
    return out


def _polygons(geojson):
    out = {}
    for f in json.load(open(geojson, encoding='utf-8'))['features']:
        g = f['geometry']
        out[f['properties']['id']] = g['coordinates'] if g['type'] == 'Polygon' \
            else max(g['coordinates'], key=lambda p: len(p[0]))
    return out


def main():
    sq, year = sys.argv[1].rstrip('/'), sys.argv[2]
    clicks = f'{sq}/plot-clicks-{year}.csv'
    backup = f'{sq}/plot-clicks-{year}-f1.csv'
    if not os.path.isfile(backup):
        shutil.copy2(clicks, backup)
    rows = list(csv.DictReader(open(backup, encoding='utf-8')))
    old = {r['id']: (float(r['centroid_lat']), float(r['centroid_lon']))
           for r in csv.DictReader(open(f'{sq}/series-{year}-f1/fields-{year}.csv', encoding='utf-8'))
           if r['id'] in {x['field'] for x in rows}}
    moved = remap(old, _polygons(f'{sq}/fields-{year}/fields-{year}.geojson'))
    kept, seen = [], set()
    for r in rows:
        new = moved.get(r['field'])
        if new and (new, r['plot_clicked']) not in seen:
            seen.add((new, r['plot_clicked']))
            kept.append({**r, 'field': new})
    with open(clicks, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator='\n')
        w.writeheader()
        w.writerows(kept)
    print(f'{sq}: {len(rows)} clicks, {len(kept)} kept on the new fields')


if __name__ == '__main__':
    main()
