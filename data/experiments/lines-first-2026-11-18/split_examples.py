"""Before/after pictures of line splits for the owner: N random split fields per district (seed 20261122), each a
late-summer photo with today's field (left, magenta) and its pieces (right, one colour each, with the piece's crop and
harvest date). python split_examples.py N DISTRICT [DISTRICT ...]   writes examples/sp-<district>-NN.png"""
import json
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/experiments/land-labels-2026-11-13/scripts')
import chips  # noqa: E402
import fields_lib as L  # noqa: E402
import line_splits as LS  # noqa: E402

D = 'C:/Programming Projects/qaz-agro-sat/data/'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
COLS = [(255, 0, 255), (0, 230, 255), (255, 140, 0), (120, 255, 60)]
WORD = {'grain': 'grain', 'oilseed': 'oilseed', 'other': 'grass', 'none': 'no crop'}


def edge(m):
    return ndimage.binary_dilation(m & ~ndimage.binary_erosion(m))


def main():
    n = int(sys.argv[1])
    rng = random.Random(20261122)
    os.makedirs(HERE + 'examples', exist_ok=True)
    for d in sys.argv[2:]:
        feats = json.load(open(f'{D}{d}/line-splits-2026.geojson'))['features']
        groups = {}
        for f in feats:
            q = f['properties']
            groups.setdefault((q['square'], q['keep']), []).append(q)
        pick = rng.sample(sorted(groups), min(n, len(groups)))
        tiles = []
        for sq, keep in sorted(pick):
            units, members, kp = LS.units_of(D + d, sq, 2026)
            u = [u for u, k in kp.items() if k == keep][0]
            old = units == u
            z = np.load(f'{D}{d}/line-splits-2026/{sq}.npz')
            ps = sorted(groups[(sq, keep)], key=lambda q: q['piece'])
            ys, xs = np.nonzero(old)
            side = int(max(np.ptp(ys), np.ptp(xs)) * 1.3) + 30
            cy, cx = (ys.min() + ys.max()) // 2, (xs.min() + xs.max()) // 2
            y0, x0 = max(0, cy - side // 2), max(0, cx - side // 2)
            win = (y0, min(units.shape[0], y0 + side), x0, min(units.shape[1], x0 + side))
            ds = [x for x in L.dates(sq, 2026, 0.9) if '2026-07-20' <= x[0] <= '2026-08-25']
            dd = max(ds, key=lambda x: x[1]) if ds else max(L.dates(sq, 2026, 0.5), key=lambda x: x[1])
            rgb = np.dstack([chips.stretch(L.reflectance(sq, 2026, dd[0], b, dd[2], dd[3], win), 0.2) for b in ('red', 'green', 'blue')])
            sl = (slice(win[0], win[1]), slice(win[2], win[3]))
            left = rgb.copy()
            left[edge(old[sl])] = (255, 0, 255)
            right = rgb.copy()
            for i, q in enumerate(ps):
                right[edge((z['pieces'] == q['piece'])[sl])] = COLS[i % len(COLS)]
            pair = [Image.fromarray(im).resize((300, 300), Image.NEAREST) for im in (left, right)]
            t = Image.new('RGB', (606, 322 + 16 * len(ps)), (20, 20, 20))
            t.paste(pair[0], (0, 22))
            t.paste(pair[1], (306, 22))
            dr = ImageDraw.Draw(t)
            dr.text((4, 4), f'today: one field  ({dd[0]})', fill=(230, 230, 230))
            dr.text((310, 4), f'split into {len(ps)} pieces', fill=(230, 230, 230))
            for i, q in enumerate(ps):
                ha = round(q['share'] * sum(1 for _ in [0]) * 0 + q['share'] * old.sum() / 100)
                cut = q['m'][5:] if q['m'] else 'no date'
                dr.text((310, 326 + 16 * i), f'piece {i + 1}: {ha} ha, {WORD.get(q["c"], q["c"])}, cut {cut}',
                        fill=COLS[i % len(COLS)])
            tiles.append(t)
            print(d, sq, keep, len(ps), flush=True)
        for k in range(0, len(tiles), 6):
            part = tiles[k:k + 6]
            h = max(t.height for t in part)
            sh = Image.new('RGB', (2 * 612, ((len(part) + 1) // 2) * (h + 6)), (0, 0, 0))
            for i, t in enumerate(part):
                sh.paste(t, ((i % 2) * 612, (i // 2) * (h + 6)))
            sh.save(HERE + f'examples/sp-{d}-{k // 6 + 1:02d}.png')


if __name__ == '__main__':
    main()
