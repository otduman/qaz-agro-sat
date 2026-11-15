"""Tests for blank_clouds.py. Run: python data/tayynsha/tools/test_blank_clouds.py"""
import os
import sys
import tempfile

import numpy as np
import tifffile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import blank_clouds as bc  # noqa: E402

GEO = [(33550, 'd', 3, (10.0, 10.0, 0.0), False), (33922, 'd', 6, (0.0, 0.0, 0.0, 480340.0, 5959660.0, 0.0), False),
       (34735, 'H', 16, (1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 32642), False),
       (42113, 's', 0, '0', False)]


def make_date(folder):
    os.makedirs(folder)
    scl = np.full((6, 6), 4, np.uint8)
    scl[0, 0] = 9          # cloud
    scl[1, 1] = 3          # cloud shadow
    scl[2, 2] = 11         # snow: kept (snow share is read from it)
    scl[3, 3] = 10         # cirrus
    tifffile.imwrite(f'{folder}/scl.tif', scl, tile=(256, 256), compression='adobe_deflate', predictor=True, extratags=GEO)
    for b in ('red', 'nir'):
        tifffile.imwrite(f'{folder}/{b}.tif', np.full((12, 12), 1500, np.uint16), tile=(256, 256),
                         compression='adobe_deflate', predictor=True, extratags=GEO)
    tifffile.imwrite(f'{folder}/swir16.tif', np.full((6, 6), 2500, np.uint16), tile=(256, 256),
                     compression='adobe_deflate', predictor=True, extratags=GEO)


def test_cloud_and_shadow_pixels_become_nodata_and_the_rest_stays():
    with tempfile.TemporaryDirectory() as t:
        make_date(t + '/d')
        bc.blank_date(t + '/d')
        red, sw = tifffile.imread(t + '/d/red.tif'), tifffile.imread(t + '/d/swir16.tif')
        assert (red[0:2, 0:2] == 0).all() and (red[2:4, 2:4] == 0).all() and (red[6:8, 6:8] == 0).all()
        assert (red[4:6, 4:6] == 1500).all()           # snow kept
        assert sw[0, 0] == 0 and sw[1, 1] == 0 and sw[3, 3] == 0 and sw[2, 2] == 2500 and sw[5, 5] == 2500
        assert (red == 0).sum() == 12 and (sw == 0).sum() == 3


def test_scl_and_georeference_and_tiling_are_kept():
    with tempfile.TemporaryDirectory() as t:
        make_date(t + '/d')
        before = tifffile.imread(t + '/d/scl.tif')
        bc.blank_date(t + '/d')
        assert (tifffile.imread(t + '/d/scl.tif') == before).all()
        with tifffile.TiffFile(t + '/d/red.tif') as f:
            p = f.pages[0]
            assert p.is_tiled and p.compression.name == 'ADOBE_DEFLATE' and p.predictor == 2
            assert tuple(p.tags[33922].value) == GEO[1][3] and tuple(p.tags[34735].value) == GEO[2][3]
            assert p.tags[42113].value == '0'


def test_running_twice_changes_nothing_more():
    with tempfile.TemporaryDirectory() as t:
        make_date(t + '/d')
        assert bc.blank_date(t + '/d') > 0
        assert bc.blank_date(t + '/d') == 0


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for f in tests:
        f()
        print('ok', f.__name__)
    print(len(tests), 'passed')
