"""Delineate Anything v2 (YOLO11 instance segmentation, AGPL-3.0) on one square (2026-11-12 ready-made model test).

  python models_da.py SQUARE DATE NAME [--sr S] [--conf C] [--link L] [--keep K] [--min HA]

DATE is a window name of models_lib.WINDOWS or YYYY-MM-DD. Writes maps/NAME/SQUARE/ and, the first time, the
stretched true-colour input inputs/SQUARE/da-DATE.png.

Input, as the authors' pipeline (github.com/Lavreniuk/Delineate-Anything, methods/main): bands red, green, blue;
each stretched linearly between its 1st and 99th percentile over the square (zeros left out) to 0-255; tiles of
512/S of our pixels, half-overlapping (tile_step 0.5), resized to 512 x 512 (cubic when enlarging; S = 2 is their
choice for 10 m imagery), passed to the model as BGR uint8 (their band order [3, 2, 1]); confidence >= C (theirs
0.15), full-resolution masks, then a 3 x 3 opening and closing of each mask, as their code does.

Joining the tiles is ours (their post-processing is a large multi-process program): masks are brought back to our
10 m grid; inside each tile, instances are laid down best score first and one left with under K of its pixels free
is dropped; two instances of neighbouring tiles are the same field when, inside the part both tiles see, their
overlap is at least L of their union; each such group is one field, made of the pixels that at least half of its
tiles put in it; groups are laid down best score first (again dropping one with under K free); then
models_lib.clean (regions under HA dropped, holes under 1 ha filled).
"""
import torch  # noqa: E402  (first: Windows DLL order)
import json
import os
import sys
import time

import cv2
import numpy as np
import torch.nn.functional as F
from ultralytics import YOLO

import models_lib as M

WEIGHTS = 'C:/Programming Projects/ml-models/DelineateAnythingv2.pt'
MODEL_PX = 512


def date_of(square, w):
    return w if w[:4].isdigit() else M.pick_date(square, *M.WINDOWS[w])[0]


def stretched(square, date):
    """BGR uint8 image of the square and its no-data mask."""
    path = M.EXP + f'inputs/{square}/da-{date}.png'
    chans = []
    nodata = None
    for band in ('blue', 'green', 'red'):
        v = M.dn(square, date, band)
        nodata = (v == 0) if nodata is None else (nodata & (v == 0))
        z = v[v > 0]
        lo, hi = np.percentile(z, (1, 99))
        chans.append(np.clip(255 * (v.astype(np.float32) - lo) / (hi - lo), 0, 255).astype(np.uint8))
    img = np.dstack(chans)
    img[nodata] = 0
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        cv2.imwrite(path, img)
    return img, nodata


def starts(n, tile, step):
    if tile >= n:
        return [0]
    s = list(range(0, n - tile + 1, step))
    if s[-1] + tile < n:
        s.append(n - tile)
    return s


class Instance:
    __slots__ = ('tile', 'y0', 'x0', 'mask', 'score')

    def __init__(self, tile, y0, x0, mask, score):
        self.tile, self.y0, self.x0, self.mask, self.score = tile, y0, x0, mask, score


def detect(model, img, nodata, sr, conf, batch=8):
    """All instances, per tile: list of (tile box, [Instance...]) with masks on our grid, cropped to their box."""
    h, w = nodata.shape
    tile = int(round(MODEL_PX / sr))
    tile = min(tile, h, w)
    step = tile // 2
    boxes = [(y0, x0) for y0 in starts(h, tile, step) for x0 in starts(w, tile, step)]
    boxes = [(y0, x0) for y0, x0 in boxes if not nodata[y0:y0 + tile, x0:x0 + tile].all()]
    interp = cv2.INTER_CUBIC if sr > 1 else (cv2.INTER_AREA if sr < 1 else cv2.INTER_LINEAR)
    tiles = []
    for i in range(0, len(boxes), batch):
        part = boxes[i:i + batch]
        ims = [cv2.resize(img[y0:y0 + tile, x0:x0 + tile], (MODEL_PX, MODEL_PX), interpolation=interp)
               for y0, x0 in part]
        res = model.predict(ims, conf=conf, quantize=16, verbose=False, retina_masks=True, imgsz=MODEL_PX,
                            max_det=300)
        for (y0, x0), r in zip(part, res):
            inst = []
            if r.masks is not None and r.masks.data.shape[0]:
                with torch.no_grad():
                    m = r.masks.data.float()[None]
                    m = -F.max_pool2d(-m, 3, 1, 1)
                    m = F.max_pool2d(m, 3, 1, 1)
                    m = F.max_pool2d(m, 3, 1, 1)
                    m = -F.max_pool2d(-m, 3, 1, 1)
                    m = F.interpolate(m, size=(tile, tile), mode='area' if sr >= 1 else 'bilinear')[0] >= 0.5
                    m = m.cpu().numpy()
                scores = r.boxes.conf.float().cpu().numpy()
                for k in range(m.shape[0]):
                    ys, xs = np.nonzero(m[k])
                    if ys.size < 10:
                        continue
                    a, b, c, d = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
                    inst.append(Instance(len(tiles), y0 + a, x0 + c, m[k, a:b, c:d].copy(), float(scores[k])))
            tiles.append(((y0, x0, tile), inst))
    return tiles


def lay_tile(box, inst, keep):
    """Tile-local labels: instances best score first, each only on free pixels; dropped under `keep` free."""
    y0, x0, t = box
    lab = np.zeros((t, t), np.int32)
    kept = []
    for i in sorted(range(len(inst)), key=lambda i: -inst[i].score):
        it = inst[i]
        sl = (slice(it.y0 - y0, it.y0 - y0 + it.mask.shape[0]), slice(it.x0 - x0, it.x0 - x0 + it.mask.shape[1]))
        free = it.mask & (lab[sl] == 0)
        if free.sum() < keep * it.mask.sum():
            continue
        kept.append(it)
        lab[sl][free] = len(kept)
    return lab, kept


def join(tiles, shape, link, keep):
    h, w = shape
    labs, kepts = [], []
    for box, inst in tiles:
        lab, kept = lay_tile(box, inst, keep)
        labs.append(lab)
        kepts.append(kept)
    offs = np.cumsum([0] + [len(k) for k in kepts])
    n = int(offs[-1])
    parent = np.arange(n)

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    boxes = [b for b, _ in tiles]
    for i in range(len(boxes)):
        yi, xi, ti = boxes[i]
        for j in range(i + 1, len(boxes)):
            yj, xj, tj = boxes[j]
            ya, yb = max(yi, yj), min(yi + ti, yj + tj)
            xa, xb = max(xi, xj), min(xi + ti, xj + tj)
            if yb - ya < 8 or xb - xa < 8:
                continue
            la = labs[i][ya - yi:yb - yi, xa - xi:xb - xi].ravel().astype(np.int64)
            lb = labs[j][ya - yj:yb - yj, xa - xj:xb - xj].ravel().astype(np.int64)
            na = np.bincount(la, minlength=len(kepts[i]) + 1)
            nb = np.bincount(lb, minlength=len(kepts[j]) + 1)
            both = (la > 0) & (lb > 0)
            if not both.any():
                continue
            key = la[both] * (len(kepts[j]) + 1) + lb[both]
            uk, cnt = np.unique(key, return_counts=True)
            for k, c in zip(uk, cnt):
                a, b = int(k // (len(kepts[j]) + 1)), int(k % (len(kepts[j]) + 1))
                if c >= 20 and c >= link * (na[a] + nb[b] - c):
                    ra, rb = find(offs[i] + a - 1), find(offs[j] + b - 1)
                    if ra != rb:
                        parent[ra] = rb
    groups = {}
    for t, kept in enumerate(kepts):
        for k, it in enumerate(kept):
            groups.setdefault(find(offs[t] + k), []).append((t, it))
    # each group: pixels at least half of its tiles put in it
    fields = []
    for members in groups.values():
        ya = min(it.y0 for _, it in members)
        yb = max(it.y0 + it.mask.shape[0] for _, it in members)
        xa = min(it.x0 for _, it in members)
        xb = max(it.x0 + it.mask.shape[1] for _, it in members)
        votes = np.zeros((yb - ya, xb - xa), np.int16)
        cover = np.zeros((yb - ya, xb - xa), np.int16)
        for t in {t for t, _ in members}:
            ty, tx, tt = boxes[t]
            cy0, cy1 = max(ty, ya) - ya, min(ty + tt, yb) - ya
            cx0, cx1 = max(tx, xa) - xa, min(tx + tt, xb) - xa
            cover[cy0:cy1, cx0:cx1] += 1
        for t, it in members:
            votes[it.y0 - ya:it.y0 - ya + it.mask.shape[0], it.x0 - xa:it.x0 - xa + it.mask.shape[1]] += it.mask
        m = (votes > 0) & (2 * votes >= cover)
        if m.sum() >= 10:
            fields.append((max(it.score for _, it in members), ya, xa, m))
    out = np.zeros((h, w), np.int32)
    k = 0
    for score, ya, xa, m in sorted(fields, key=lambda f: -f[0]):
        sl = (slice(ya, ya + m.shape[0]), slice(xa, xa + m.shape[1]))
        free = m & (out[sl] == 0)
        if free.sum() < keep * m.sum():
            continue
        k += 1
        out[sl][free] = k
    return out, {'instances': int(sum(len(i) for _, i in tiles)), 'kept_in_tiles': n, 'groups': len(groups),
                 'laid': k}


def run(square, wdate, name, sr=2.0, conf=0.15, link=0.5, keep=0.5, min_ha=5.0, model=None, polygons=True):
    t0 = time.time()
    date = date_of(square, wdate)
    img, nodata = stretched(square, date)
    model = model or YOLO(WEIGHTS)
    tiles = detect(model, img, nodata, sr, conf)
    lab, info = join(tiles, nodata.shape, link, keep)
    lab = M.clean(lab, min_ha=min_ha)
    meta = {'model': 'Delineate Anything v2', 'date': date, 'super_resolution': sr, 'tile_px': int(round(512 / sr)),
            'tile_step': 0.5, 'conf': conf, 'link_iou': link, 'keep_free': keep, 'min_ha': min_ha, **info,
            'regions': int(lab.max()), 'seconds': round(time.time() - t0, 1),
            'gpu_peak_mb': round(torch.cuda.max_memory_allocated() / 2 ** 20)}
    M.save_map(name, square, lab, meta, polygons=polygons)
    print(square, name, json.dumps(meta), flush=True)
    return model


if __name__ == '__main__':
    args = sys.argv[1:]
    opts = {}
    for key in ('--sr', '--conf', '--link', '--keep', '--min'):
        if key in args:
            k = args.index(key)
            opts[key[2:]] = float(args[k + 1])
            del args[k:k + 2]
    run(args[0], args[1], args[2], sr=opts.get('sr', 2.0), conf=opts.get('conf', 0.15),
        link=opts.get('link', 0.5), keep=opts.get('keep', 0.5), min_ha=opts.get('min', 5.0))
