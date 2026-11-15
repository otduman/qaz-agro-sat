"""True-colour chips of field regions with the outlines drawn on top, for checking field boundaries by eye.

Each chip shows one region on three dates of its season (mid-season, harvest time, after harvest). The region's
own outline is red; other field and fallow outlines yellow; outlines of regions labelled other cyan. A second
map (another version) can be drawn beside it, so before and after can be compared on the same dates.

python fields_chips.py SQUARE YEAR OUT.png REGION [REGION ...] [--version V] [--compare V2]
"""
import sys

import numpy as np
from PIL import Image, ImageDraw

import fields_lib as L

PANEL = 480
WINDOWS = (('07-01', '08-05'), ('08-10', '09-12'), ('09-15', '10-25'))


def season_dates(square, year):
    rows = L.dates(square, year)
    chosen = []
    for lo, hi in WINDOWS:
        inside = [r for r in rows if f'{year}-{lo}' <= r[0] <= f'{year}-{hi}']
        if inside:
            chosen.append(max(inside, key=lambda r: (round(r[1], 2), r[0])))
    return chosen


def stretch(r):
    v = np.clip(r / 0.22, 0, 1) ** 0.75
    return np.nan_to_num(v * 255).astype(np.uint8)


def window_of(lab, region, margin=0.35, minimum=160):
    ys, xs = np.nonzero(lab == region)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    side = max(y1 - y0, x1 - x0)
    side = max(minimum, int(side * (1 + 2 * margin)))
    cy, cx = (y0 + y1) // 2, (x0 + x1) // 2
    h, w = lab.shape
    y0 = int(np.clip(cy - side // 2, 0, max(0, h - side)))
    x0 = int(np.clip(cx - side // 2, 0, max(0, w - side)))
    return y0, min(h, y0 + side), x0, min(w, x0 + side)


def outlined(rgb, lab_win, region, kinds):
    """Upscales a window and draws the outlines on it. kinds: region -> 'field' or 'other'."""
    h, w = lab_win.shape
    scale = PANEL / max(h, w)
    size = (int(round(w * scale)), int(round(h * scale)))
    img = np.array(Image.fromarray(rgb).resize(size, Image.NEAREST))
    big = np.array(Image.fromarray(lab_win.astype(np.int32)).resize(size, Image.NEAREST))
    b = np.zeros(big.shape, bool)
    b[:, 1:] |= big[:, 1:] != big[:, :-1]
    b[1:, :] |= big[1:, :] != big[:-1, :]
    other = np.vectorize(lambda r: kinds.get(r, 'none') == 'other')(big) if kinds else np.zeros(big.shape, bool)
    img[b & other] = (0, 220, 255)
    img[b & ~other] = (255, 230, 0)
    own = big == region
    ob = np.zeros(big.shape, bool)
    ob[:, 1:] |= own[:, 1:] != own[:, :-1]
    ob[1:, :] |= own[1:, :] != own[:-1, :]
    ob = ob | np.roll(ob, 1, 0) | np.roll(ob, 1, 1)
    img[ob] = (255, 30, 30)
    return Image.fromarray(img)


def chip(square, year, region, version=None, compare=None, title=None):
    lab = L.labels(square, year, version)
    lands = L.land(square, year, version)
    kinds = {r: ('other' if v[0] == 'other' else 'field') for r, v in lands.items()}
    win = window_of(lab, region)
    y0, y1, x0, x1 = win
    rows = [(lab[y0:y1, x0:x1], region, kinds, version or 'current')]
    if compare is not None:
        cv = None if compare == 'current' else compare
        lab2 = L.labels(square, year, cv)
        lands2 = L.land(square, year, cv)
        kinds2 = {r: ('other' if v[0] == 'other' else 'field') for r, v in lands2.items()}
        lw = lab2[y0:y1, x0:x1]
        # the compared map's region that holds most of this region
        inside = lw[lab[y0:y1, x0:x1] == region]
        inside = inside[inside > 0]
        r2 = int(np.bincount(inside).argmax()) if inside.size else -1
        rows.append((lw, r2, kinds2, compare))
    ds = season_dates(square, year)
    panels = []
    for lab_win, reg, kd, name in rows:
        line = []
        for date, _, scale, offset in ds:
            rgb = np.dstack([stretch(L.reflectance(square, year, date, b, scale, offset, win))
                             for b in ('red', 'green', 'blue')])
            p = outlined(rgb, lab_win, reg, kd)
            ImageDraw.Draw(p).text((4, 4), f'{date[5:]} {name}', fill=(255, 255, 255))
            line.append(p)
        panels.append(line)
    pw = max(p.width for line in panels for p in line)
    ph = max(p.height for line in panels for p in line)
    head = 16
    sheet = Image.new('RGB', (pw * len(panels[0]) + 4 * (len(panels[0]) - 1), head + (ph + 4) * len(panels)),
                      (40, 40, 40))
    d = ImageDraw.Draw(sheet)
    area = lands.get(region, ('?', 0))
    km = (y1 - y0) * 10 / 1000
    d.text((4, 2), title or f'{square} {year} F{region:04d} {area[0]} {area[1]} ha  (chip {km:.1f} km)',
           fill=(255, 255, 255))
    for j, line in enumerate(panels):
        for i, p in enumerate(line):
            sheet.paste(p, (i * (pw + 4), head + j * (ph + 4)))
    return sheet


def stack_vertically(images, gap=6):
    w = max(i.width for i in images)
    h = sum(i.height for i in images) + gap * (len(images) - 1)
    out = Image.new('RGB', (w, h), (0, 0, 0))
    y = 0
    for i in images:
        out.paste(i, (0, y))
        y += i.height + gap
    return out


if __name__ == '__main__':
    args = sys.argv[1:]
    version = compare = None
    if '--version' in args:
        k = args.index('--version')
        version = args[k + 1]
        del args[k:k + 2]
    if '--compare' in args:
        k = args.index('--compare')
        compare = args[k + 1]
        del args[k:k + 2]
    square, year, out = args[0], int(args[1]), args[2]
    regions = [int(a) for a in args[3:]]
    stack_vertically([chip(square, year, r, version, compare) for r in regions]).save(out)
    print(out)


def pair_chip(square, year, lab, a_mask, b_mask, title):
    """Two areas of one label map: A outlined red, B magenta, other outlines yellow; three dates."""
    both = a_mask | b_mask
    ys, xs = np.nonzero(both)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    side = max(160, int(max(y1 - y0, x1 - x0) * 1.5))
    cy, cx = (y0 + y1) // 2, (x0 + x1) // 2
    h, w = lab.shape
    y0 = int(np.clip(cy - side // 2, 0, max(0, h - side)))
    x0 = int(np.clip(cx - side // 2, 0, max(0, w - side)))
    win = (y0, min(h, y0 + side), x0, min(w, x0 + side))
    lw = lab[win[0]:win[1], win[2]:win[3]]
    aw = a_mask[win[0]:win[1], win[2]:win[3]]
    bw = b_mask[win[0]:win[1], win[2]:win[3]]
    scale = PANEL / max(lw.shape)
    size = (int(round(lw.shape[1] * scale)), int(round(lw.shape[0] * scale)))
    up = lambda m: np.array(Image.fromarray(m.astype(np.int32)).resize(size, Image.NEAREST))
    big, ba, bb = up(lw), up(aw) > 0, up(bw) > 0
    def edge(m):
        e = np.zeros(m.shape, bool)
        e[:, 1:] |= m[:, 1:] != m[:, :-1]
        e[1:, :] |= m[1:, :] != m[:-1, :]
        return e
    panels = []
    for date, _, sc, off in season_dates(square, year):
        rgb = np.dstack([stretch(L.reflectance(square, year, date, b, sc, off, win)) for b in ('red', 'green', 'blue')])
        img = np.array(Image.fromarray(rgb).resize(size, Image.NEAREST))
        img[edge(big)] = (255, 230, 0)
        ea, eb = edge(ba), edge(bb)
        img[ea | np.roll(ea, 1, 0) | np.roll(ea, 1, 1)] = (255, 30, 30)
        img[eb | np.roll(eb, 1, 0) | np.roll(eb, 1, 1)] = (255, 0, 255)
        p = Image.fromarray(img)
        ImageDraw.Draw(p).text((4, 4), date[5:], fill=(255, 255, 255))
        panels.append(p)
    sheet = Image.new('RGB', (sum(p.width for p in panels) + 8, panels[0].height + 16), (40, 40, 40))
    ImageDraw.Draw(sheet).text((4, 2), title, fill=(255, 255, 255))
    x = 0
    for p in panels:
        sheet.paste(p, (x, 16))
        x += p.width + 4
    return sheet
