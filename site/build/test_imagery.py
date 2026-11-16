"""Tests for imagery.py. Run from the repository root: python site/build/test_imagery.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geo  # noqa: E402
import imagery  # noqa: E402


def rows(*pairs):
    return [dict(date=d, clear_share=str(c), status='stored') for d, c in pairs]


def test_pick_date_prefers_clearest_july():
    r = rows(('2026-06-20', 0.99), ('2026-07-05', 0.80), ('2026-07-19', 0.97), ('2026-08-02', 1.0))
    assert imagery.pick_date(r) == '2026-07-19', imagery.pick_date(r)


def test_pick_date_falls_back_then_none():
    assert imagery.pick_date(rows(('2026-08-10', 0.9))) == '2026-08-10'
    assert imagery.pick_date(rows(('2026-10-01', 0.99))) is None


def test_pick_date_ignores_unstored():
    r = rows(('2026-07-10', 0.99), ('2026-07-12', 0.6))
    r[0]['status'] = 'skipped'
    assert imagery.pick_date(r) == '2026-07-12'


def test_pick_dates_uses_one_common_clear_day_where_it_can():
    idx = {'A': rows(('2026-07-05', 0.99), ('2026-07-19', 1.0)),
           'B': rows(('2026-07-05', 0.98), ('2026-07-19', 0.5)),
           'C': rows(('2026-07-05', 0.97), ('2026-07-22', 1.0)),
           'D': rows(('2026-07-12', 0.99), ('2026-07-30', 0.99))}
    got = imagery.pick_dates(idx)
    assert got['A'] == got['B'] == got['C'] == '2026-07-05', got   # the day most squares share clear
    assert got['D'] == '2026-07-12', got                            # its own clear day nearest to it


def test_true_colour_stretch():
    band = np.array([[1200, 3200]], dtype=np.uint16)
    rgb = imagery.true_colour(band, band, band)
    assert rgb.dtype == np.uint8 and rgb.shape == (1, 2, 3)
    assert rgb[0, 0, 0] == 0 and rgb[0, 1, 0] == 255, rgb


def test_resample_reads_a_zone_41_grid():
    lat0, lon0 = 52.3, 66.2
    e0, n0 = geo.utm42(66.2, 52.3, lon0=63.0)
    e0, n0 = round(e0, -1), round(n0, -1)
    src = np.zeros((10, 10, 3), dtype=np.uint8)
    src[5, 5] = 70
    lon, lat = geo.from_utm42(e0 + 55, n0 - 55, lon0=63.0)
    px, py = geo.projector(lat0, lon0)(lon, lat)
    out = imagery.resample(src, (e0, n0, 10.0), (lat0, lon0), (px - 0.055, py - 0.055, px + 0.045, py + 0.045), 0.01,
                           lon0=63.0)
    assert out[5, 5, 0] == 70 and out[5, 5, 3] == 255, out[5, 5]
    assert imagery.zone_lon0(32641) == 63.0 and imagery.zone_lon0(32642) == 69.0


def test_resample_places_pixels_and_masks_outside():
    lat0, lon0 = 53.9, 69.6
    e0, n0 = geo.utm42(69.6, 53.9)
    e0, n0 = round(e0, -1), round(n0, -1)
    src = np.zeros((10, 10, 3), dtype=np.uint8)
    src[5, 5] = 50  # pixel centre at (e0 + 55, n0 - 55)
    lon, lat = geo.from_utm42(e0 + 55, n0 - 55)
    px, py = geo.projector(lat0, lon0)(lon, lat)
    out = imagery.resample(src, (e0, n0, 10.0), (lat0, lon0), (px - 0.055, py - 0.055, px + 0.045, py + 0.045), 0.01)
    assert out.shape == (10, 10, 4), out.shape
    assert out[5, 5, 0] == 50 and out[5, 5, 3] == 255, out[5, 5]
    ex, ey = geo.projector(lat0, lon0)(*geo.from_utm42(e0 - 500, n0 - 50))
    west = imagery.resample(src, (e0, n0, 10.0), (lat0, lon0), (ex - 0.02, ey - 0.02, ex + 0.02, ey + 0.02), 0.01)
    assert (west[..., 3] == 0).all()


def test_balance_brings_a_dull_district_to_the_same_look():
    # owner 2026-11-16: Tayynsha's photo looked duller and darker than the other districts'
    rng = np.random.default_rng(1)
    dull = np.zeros((50, 50, 4), np.uint8)
    dull[..., :3] = rng.integers(60, 110, (50, 50, 3))
    dull[..., 3] = 255
    dull[:5, :5, 3] = 0                                   # no data: left alone
    lut = imagery.balance_lut([dull])
    out = imagery.apply_lut(dull, lut)
    v = out[..., :3][dull[..., 3] > 0]
    assert np.percentile(v, 1) <= 30 and np.percentile(v, 99) >= 200, (np.percentile(v, 1), np.percentile(v, 99))
    assert (out[:5, :5] == dull[:5, :5]).all()
    vivid = dull.copy(); vivid[..., :3] = rng.integers(10, 245, (50, 50, 3))
    lut2 = imagery.balance_lut([vivid])
    assert abs(int(lut2[0][128]) - 128) < 20             # a photo with full range is barely changed


if __name__ == '__main__':
    for n, f in list(globals().items()):
        if n.startswith('test_'):
            f()
            print('ok', n)
    print('all tests passed')
