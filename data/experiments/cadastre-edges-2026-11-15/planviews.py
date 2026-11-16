"""View centres (GK zone 12, the screen point (900, 380)) covering the processed squares of DIST that have no plot
lines yet, inside the district, at SX x SY m per screen pixel; map box 1221 x 545 px with 10% overlap.
ml-env python: python planviews.py DIST SX SY OUT.csv"""
import csv, json, os, sys
from pyproj import Transformer
from shapely.geometry import box, Polygon, shape
from shapely.ops import unary_union, transform
D = 'C:/Programming Projects/qaz-agro-sat/'
dist, SX, SY, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
t = Transformer.from_crs(4326, 10946, always_xy=True)
have = {f[:-4] for f in os.listdir(f'lines/{dist}')} if os.path.isdir(f'lines/{dist}') else set()
rows = [r for r in csv.DictReader(open(f'{D}data/{dist}/squares.csv')) if os.path.isdir(f"{D}data/{dist}/{r['square']}/fields-2026") and r['square'] not in have]
g = json.load(open(f'{D}docs/districts/{dist}.geojson', encoding='utf-8'))
distg = transform(lambda x, y, z=None: t.transform(x, y), unary_union([shape(f['geometry']) for f in g['features']]))
need = unary_union([Polygon([t.transform(x, y) for x, y in ((float(r['west']), float(r['south'])), (float(r['east']), float(r['south'])), (float(r['east']), float(r['north'])), (float(r['west']), float(r['north'])))]) for r in rows]).intersection(distg)
# map box on screen: x 292..1513, y 107..652; the centre point (900,380) sits off-centre
L, R, T, B = (900 - 292) * SX, (1513 - 900) * SX, (380 - 107) * SY, (652 - 380) * SY
stepx, stepy = (L + R) * 0.9, (T + B) * 0.9
minx, miny, maxx, maxy = need.bounds
views = []
y = maxy - T * 0.9
while y + B > miny:
    x = minx + L * 0.9
    while x - L < maxx:
        if box(x - L, y - B, x + R, y + T).intersects(need):
            views.append((x, y))
        x += stepx
    y -= stepy
with open(out, 'w', newline='') as f:
    w = csv.writer(f); w.writerow(['view', 'E', 'N'])
    for i, (x, y) in enumerate(views, 1):
        w.writerow([f'f{i:02d}', round(x, 1), round(y, 1)])
print(len(rows), 'squares,', len(views), 'views ->', out)
