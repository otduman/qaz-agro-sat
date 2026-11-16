"""True-colour Sentinel-2 pictures for the Field Atlas map, resampled onto each district's page grid.

Per survey square: the clearest July scene in the season stack (fallback: clearest 15 June - 31 August), red/green/blue
stretched to a natural look, resampled (block mean, then nearest) onto the page grid and clipped to the square's own
UTM box. Writes one overview picture per district (0.1 km per pixel) and one detail picture per square (0.02 km)."""
import csv
import os

import numpy as np
import tifffile
from PIL import Image

import geo

LOW, HIGH, GAMMA = 0.02, 0.22, 1 / 1.3
OVERVIEW_KM, DETAIL_KM = 0.05, 0.02   # overview 0.05 km since 2026-11-16 (was 0.1: blurry at district view)
QUALITY = 72


def pick_date(index_rows):
    """The stored scene with the highest clear share in July, else in 15 June - 31 August, else None."""
    stored = [r for r in index_rows if r['status'] == 'stored']
    for lo, hi in (('-07-01', '-07-31'), ('-06-15', '-08-31')):
        cand = [r for r in stored if lo <= r['date'][4:] <= hi]
        if cand:
            return max(cand, key=lambda r: float(r['clear_share']))['date']
    return None


CLEAR_DAY = 0.95


def pick_dates(indexes):
    """{square: index rows} -> {square: date}: neighbouring squares on one common clear summer day, so the background
    photo looks even (owner 2026-11-15). The common day is the July (else 15 June - 31 August) date clear
    (CLEAR_DAY or more) on the most squares; a square not clear that day takes its own clear day nearest to it, and a
    square with no clear day falls back to pick_date."""
    from datetime import date as _d
    count = {}
    for rows in indexes.values():
        for r in rows:
            if r['status'] == 'stored' and float(r['clear_share']) >= CLEAR_DAY:
                count[r['date']] = count.get(r['date'], 0) + 1
    common = None
    for lo, hi in (('-07-01', '-07-31'), ('-06-15', '-08-31')):
        cand = [d for d in count if lo <= d[4:] <= hi]
        if cand:
            common = max(cand, key=lambda d: (count[d], d[4:] == '-07-15'))
            break
    out = {}
    for sq, rows in indexes.items():
        clear = [r['date'] for r in rows if r['status'] == 'stored' and float(r['clear_share']) >= CLEAR_DAY
                 and '-06-15' <= r['date'][4:] <= '-08-31']
        if common and common in clear:
            out[sq] = common
        elif common and clear:
            c = _d.fromisoformat(common)
            out[sq] = min(clear, key=lambda d: abs((_d.fromisoformat(d) - c).days))
        else:
            out[sq] = pick_date(rows)
    return out


def true_colour(red, green, blue, scale=1e-4, offset=-0.1):
    """uint16 surface reflectance bands -> uint8 HxWx3: reflectance LOW..HIGH stretched with a gamma."""
    out = []
    for b in (red, green, blue):
        refl = b.astype(np.float32) * scale + offset
        v = np.clip((refl - LOW) / (HIGH - LOW), 0, 1) ** GAMMA
        out.append(np.round(v * 255).astype(np.uint8))
    return np.dstack(out)


def balance_lut(pictures, lo=1.0, hi=99.5, out_lo=10, out_hi=240):
    """256-value lookup table (the same for red, green and blue, so colours keep their hue) that stretches the
    district's pictures (HxWx4, alpha 0 = no data) from their lo..hi percentiles of all channels to out_lo..out_hi,
    so every district gets the same brightness and contrast whatever the haze of its day (owner 2026-11-16: Tayynsha
    looked duller and darker). Returns the table three times, one per channel."""
    v = np.concatenate([p[..., :3][p[..., 3] > 0].ravel() for p in pictures]) if pictures else np.arange(256)
    a, b = (np.percentile(v, [lo, hi]) if v.size else (0, 255))
    b = max(b, a + 1)
    x = np.arange(256, dtype=np.float32)
    lut = np.clip(out_lo + (x - a) * (out_hi - out_lo) / (b - a), 0, 255).round().astype(np.uint8)
    return [lut, lut, lut]


def apply_lut(rgba, luts):
    """rgba with each colour channel passed through its table; no-data pixels (alpha 0) unchanged."""
    out = rgba.copy()
    m = rgba[..., 3] > 0
    for c in range(3):
        out[..., c][m] = luts[c][rgba[..., c][m]]
    return out


def _pool(rgb, valid, f):
    """Block mean by an integer factor f (valid pixels only)."""
    if f <= 1:
        return rgb, valid
    h, w = rgb.shape[0] // f * f, rgb.shape[1] // f * f
    a = rgb[:h, :w].astype(np.float32).reshape(h // f, f, w // f, f, 3)
    m = valid[:h, :w].reshape(h // f, f, w // f, f).astype(np.float32)
    n = m.sum(axis=(1, 3))
    s = (a * m[..., None]).sum(axis=(1, 3))
    return (s / np.maximum(n, 1)[..., None]).round().astype(np.uint8), n > 0


def zone_lon0(epsg):
    """Central meridian of a northern UTM zone EPSG code (326xx)."""
    return float((int(epsg) - 32600) * 6 - 183)


def resample(rgb, grid, origin, box_km, km_per_px, valid=None, clip=None, lon0=69.0, clip_lon0=69.0):
    """rgb: HxWx3 uint8 on the UTM grid (e0, n0, pixel size m; e0, n0 = top-left corner). origin: (lat0, lon0) of
    the page grid. box_km: (x0, y0, x1, y1) on the page grid. Returns HxWx4 uint8; alpha 0 where there is no source
    pixel, the source is invalid, or the point lies outside clip=(e0, n0, e1, n1) in UTM metres."""
    e0, n0, size = grid
    x0, y0, x1, y1 = box_km
    w, h = max(1, int(round((x1 - x0) / km_per_px))), max(1, int(round((y1 - y0) / km_per_px)))
    xs = x0 + (np.arange(w) + 0.5) * km_per_px
    ys = y0 + (np.arange(h) + 0.5) * km_per_px
    gx, gy = np.meshgrid(xs, ys)
    lon, lat = geo.unprojector(*origin)(gx, gy)
    e, n = geo.utm42(lon, lat, lon0)
    col = np.floor((e - e0) / size).astype(np.int64)
    row = np.floor((n0 - n) / size).astype(np.int64)
    ok = (col >= 0) & (col < rgb.shape[1]) & (row >= 0) & (row < rgb.shape[0])
    if clip is not None:
        ce, cn = (e, n) if clip_lon0 == lon0 else geo.utm42(lon, lat, clip_lon0)
        ok &= (ce >= clip[0]) & (ce <= clip[2]) & (cn >= clip[1]) & (cn <= clip[3])
    out = np.zeros((h, w, 4), dtype=np.uint8)
    r, c = row[ok], col[ok]
    out[ok, :3] = rgb[r, c]
    good = valid[r, c] if valid is not None else True
    out[ok, 3] = np.where(good, 255, 0)
    return out


def _bbox_km(proj, box, lon0=69.0):
    pts = [proj(*geo.from_utm42(e, n, lon0)) for e in (box[0], box[2]) for n in (box[1], box[3])]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def _scene(district_dir, square, date=None):
    season = f'{district_dir}/{square}/season-2026'
    props = dict(l.strip().split('=', 1) for l in open(f'{season}/stack.properties', encoding='utf-8') if '=' in l)
    epsg, e0, n0, size, _, _ = props['grid'].split(',')
    rows = list(csv.DictReader(open(f'{season}/index.csv', encoding='utf-8')))
    date = date or pick_date(rows)
    if not date:
        return None
    meta = next(r for r in rows if r['date'] == date)
    bands = [tifffile.imread(f'{season}/{date}/{b}.tif') for b in ('red', 'green', 'blue')]
    valid = (bands[0] > 0) & (bands[1] > 0) & (bands[2] > 0)
    rgb = true_colour(*bands, scale=float(meta['scale']), offset=float(meta['offset']))
    return rgb, valid, (float(e0), float(n0), float(size)), date, zone_lon0(epsg)


def _save(rgba, path):
    Image.fromarray(rgba, 'RGBA').save(path, 'WEBP', quality=QUALITY, method=4)


def district_pictures(district_dir, squares, origin, out_dir, rel_prefix):
    """squares: [{id, box: [e0, n0, e1, n1]}]. Writes out_dir/overview.webp and out_dir/<square>.webp; returns
    {overview: {href, x, y, w, h}, tiles: [{id, href, x, y, w, h, date}]} with x, y, w, h in page km."""
    os.makedirs(out_dir, exist_ok=True)
    proj = geo.projector(*origin)
    boxes = {q['id']: _bbox_km(proj, q['box'], zone_lon0(q.get('epsg', 32642))) for q in squares}
    ox0 = min(b[0] for b in boxes.values())
    oy0 = min(b[1] for b in boxes.values())
    ox1 = max(b[2] for b in boxes.values())
    oy1 = max(b[3] for b in boxes.values())
    ow, oh = int(round((ox1 - ox0) / OVERVIEW_KM)), int(round((oy1 - oy0) / OVERVIEW_KM))
    canvas = np.zeros((oh, ow, 4), dtype=np.uint8)
    tiles = []
    details = []
    indexes = {}
    for q in squares:
        p = f"{district_dir}/{q['id']}/season-2026/index.csv"
        if os.path.isfile(p):
            indexes[q['id']] = list(csv.DictReader(open(p, encoding='utf-8')))
    dates = pick_dates(indexes)
    for q in squares:
        scene = _scene(district_dir, q['id'], dates.get(q['id']))
        if scene is None:
            continue
        rgb, valid, grid, date, glon0 = scene
        blon0 = zone_lon0(q.get('epsg', 32642))
        x0, y0, x1, y1 = boxes[q['id']]
        f = int(round(DETAIL_KM * 1000 / grid[2]))
        prgb, pvalid = _pool(rgb, valid, f)
        detail = resample(prgb, (grid[0], grid[1], grid[2] * f), origin, (x0, y0, x1, y1), DETAIL_KM, pvalid, q['box'], glon0, blon0)
        details.append((q['id'], detail))
        tiles.append(dict(id=q['id'], href=f"{rel_prefix}{q['id']}.webp", x=round(x0, 3), y=round(y0, 3),
                          w=round(detail.shape[1] * DETAIL_KM, 3), h=round(detail.shape[0] * DETAIL_KM, 3), date=date))
        # Overview: snap the square's box to the overview pixel grid and paste where it has data.
        cx0 = int(np.floor((x0 - ox0) / OVERVIEW_KM))
        cy0 = int(np.floor((y0 - oy0) / OVERVIEW_KM))
        cx1 = min(ow, int(np.ceil((x1 - ox0) / OVERVIEW_KM)))
        cy1 = min(oh, int(np.ceil((y1 - oy0) / OVERVIEW_KM)))
        f = int(round(OVERVIEW_KM * 1000 / grid[2]))
        orgb, ovalid = _pool(rgb, valid, f)
        part = resample(orgb, (grid[0], grid[1], grid[2] * f), origin,
                        (ox0 + cx0 * OVERVIEW_KM, oy0 + cy0 * OVERVIEW_KM, ox0 + cx1 * OVERVIEW_KM,
                         oy0 + cy1 * OVERVIEW_KM), OVERVIEW_KM, ovalid, q['box'], glon0, blon0)
        part = part[:cy1 - cy0, :cx1 - cx0]
        sub = canvas[cy0:cy0 + part.shape[0], cx0:cx0 + part.shape[1]]
        m = part[..., 3] > 0
        sub[m] = part[m]
    luts = balance_lut([canvas])          # one stretch per district, from its whole overview
    for sid, detail in details:
        _save(apply_lut(detail, luts), f"{out_dir}/{sid}.webp")
    _save(apply_lut(canvas, luts), f'{out_dir}/overview.webp')
    return dict(overview=dict(href=f'{rel_prefix}overview.webp', x=round(ox0, 3), y=round(oy0, 3),
                              w=round(ow * OVERVIEW_KM, 3), h=round(oh * OVERVIEW_KM, 3)), tiles=tiles)
