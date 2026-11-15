"""Tests for hybrid_fields.py. Run: python data/tayynsha/tools/test_hybrid_fields.py"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hybrid_fields as hf  # noqa: E402

F, W, O = hf.FIELD, hf.FALLOW, hf.OTHER


def test_a_model_field_on_our_cropland_replaces_our_pieces():
    ours = np.zeros((10, 20), np.int32)
    ours[:, :8], ours[:, 8:14], ours[:, 14:] = 1, 2, 3          # two field pieces and a pasture
    land = {1: F, 2: F, 3: O}
    da = np.zeros_like(ours)
    da[:, :14] = 7                                              # the model sees one field over both pieces
    lab, lands = hf.hybrid(ours, land, da, min_px=10)
    assert lab.max() == 1 and lands[1] == F
    assert (lab[:, :14] == 1).all() and (lab[:, 14:] == 0).all()


def test_a_model_field_mostly_on_pasture_is_left_out():
    ours = np.zeros((10, 20), np.int32)
    ours[:, :5], ours[:, 5:] = 1, 2
    land = {1: F, 2: O}
    da = np.full_like(ours, 4)                                  # 25% field, 75% pasture
    lab, lands = hf.hybrid(ours, land, da, min_px=10)
    assert (lab[:, :5] > 0).all() and (lab[:, 5:] == 0).all()   # our field kept, the model's outline not
    assert lands[lab[0, 0]] == F


def test_our_field_the_model_missed_is_kept_whole_and_small_leftovers_go():
    ours = np.zeros((10, 30), np.int32)
    ours[:, :10], ours[:, 10:20], ours[:, 20:] = 1, 2, 3
    land = {1: F, 2: W, 3: F}
    da = np.zeros_like(ours)
    da[:, :19] = 5                                              # covers piece 1 and 90% of piece 2
    lab, lands = hf.hybrid(ours, land, da, min_px=10)
    assert len(lands) == 2                                      # the model's field and our piece 3
    assert lands[lab[0, 0]] == F and lands[lab[0, 25]] == F
    assert lab[0, 19] == 0                                      # piece 2's 10% edge left over is dropped


def test_the_model_field_takes_field_or_fallow_by_area():
    ours = np.zeros((10, 10), np.int32)
    ours[:, :4], ours[:, 4:] = 1, 2
    da = np.full_like(ours, 3)
    lab, lands = hf.hybrid(ours, {1: F, 2: W}, da, min_px=10)
    assert lands[1] == W


if __name__ == '__main__':
    tests = [v for k, v in sorted(globals().items()) if k.startswith('test_')]
    for t in tests:
        t()
    print(f'{len(tests)} tests passed')
