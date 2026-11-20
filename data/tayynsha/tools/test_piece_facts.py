"""Tests for piece_facts.py. Run: python data/tayynsha/tools/test_piece_facts.py"""
import os
import sys
import tempfile

import numpy as np
import tifffile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import piece_facts as PF  # noqa: E402

GEO = {33550: (10.0, 10.0, 0.0), 33922: (0.0, 0.0, 0.0, 500000.0, 6000000.0, 0.0),
       34735: (1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 32642)}


def source(path):
    data = np.arange(64 * 64, dtype=np.uint16).reshape(64, 64)
    tifffile.imwrite(path, data, tile=(32, 32), compression='zlib',
                     extratags=[(33550, 12, 3, GEO[33550], True), (33922, 12, 6, GEO[33922], True),
                                (34735, 3, len(GEO[34735]), GEO[34735], True)])


def test_pieces_are_written_on_the_source_grid_with_its_tags():
    d = tempfile.mkdtemp()
    src, dst = os.path.join(d, 'a.tif'), os.path.join(d, 'b.tif')
    source(src)
    pieces = np.zeros((64, 64), np.int64)
    pieces[:, :32] = 1
    pieces[:, 32:] = 2
    PF.write_like(src, pieces, dst)
    t = tifffile.TiffFile(dst)
    p = t.pages[0]
    assert p.dtype == np.uint16 and p.shape == (64, 64) and p.is_tiled
    assert (p.asarray() == pieces).all()
    for code in GEO:
        assert tuple(p.tags[code].value) == tuple(GEO[code])


def test_piece_rows_join_the_square_rows_under_their_own_ids():
    square = [{'field': '2026-F0001', 'date': '2026-05-01', 'v': '1'}]
    pieces = [{'field': '2026-F0001', 'date': '2026-05-01', 'v': '2'}, {'field': '2026-F0002', 'date': '2026-05-01', 'v': '3'}]
    out = PF.combine(square, pieces, 'field', 2026)
    assert [r['field'] for r in out] == ['2026-F0001', '2026-P0001', '2026-P0002']
    assert PF.piece_number('2026-P0002') == 2 and PF.piece_number('2026-F0002') is None


if __name__ == '__main__':
    for k, f in list(globals().items()):
        if k.startswith('test_'):
            f()
            print('ok', k)
