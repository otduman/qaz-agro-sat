"""Neighbouring final regions of a map built from a probe: shared border length, median ridge, median line
strength on the border, NDVI and brightness curve gaps (3rd highest), areas and land labels."""
import numpy as np
import fields_sim as S, fields_lib as L

def final_pairs(square, p=None, lab=None, version=None):
    p = p or S.load(square)
    if lab is None:
        lab = L.labels(square, 2026, version)
    lands = L.land(square, 2026, version)
    # final id of each watershed region
    ws = p['ws']; m = (ws > 0) & (lab > 0)
    fid = np.zeros(p['n'] + 1, np.int64)
    fid[ws[m]] = lab[m]
    s = S.Sums(p)
    nf = lab.max()
    agg = S.Sums.__new__(S.Sums)
    for name in ('count', 'ndvi', 'bright'):
        a = np.zeros((nf + 1, getattr(s, name).shape[1]))
        np.add.at(a, fid, getattr(s, name))
        setattr(agg, name, a)
    agg.pixels = np.bincount(lab.ravel(), minlength=nf + 1).astype(float)
    q = dict(p); q['ws'] = lab; q['n'] = nf
    out = []
    for a, b, ridge, k, line in S.pairs(q):
        la, lb = lands.get(a, ('?', 0)), lands.get(b, ('?', 0))
        out.append(dict(a=a, b=b, ridge=ridge, k=k, line=line, gap=S.gap(agg, a, b), bgap=S.gap(agg, a, b, 'bright'),
                        la=la[0], lb=lb[0], aa=la[1], ab=lb[1]))
    return out
