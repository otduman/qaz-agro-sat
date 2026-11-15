"""Tuning sweep of the piece pass on the tuning squares' 2026 probes (sizes, cadastre, overlap)."""
import itertools, sys
import numpy as np
import fields_sim as S, fields_lib as L, fields_measure as M

settings = [('base', 0, 0, None, None)] + [(f'g{G}-l{Lm}-s{sm}-r{ra}', G, Lm, sm, ra) for G, Lm, sm, ra in (
    (0.10, 0.05, 60, 0.5), (0.08, 0.05, 60, 0.5), (0.12, 0.05, 60, 0.5), (0.10, 0.04, 60, 0.5), (0.10, 0.07, 60, 0.5),
    (0.10, 0.05, 40, 0.5), (0.10, 0.05, 100, 0.5), (0.10, 0.05, 60, 0.33), (0.10, 0.05, 60, 1.0))]
probes = {sq: S.load(sq) for sq in L.TUNING}
first = {sq: S.merge(p) for sq, p in probes.items()}
for name, G, Lm, sm, ra in settings:
    maps = {}
    tot = {}
    for sq, p in probes.items():
        ids = first[sq] if G == 0 else S.join_pieces(p, first[sq], G=G, Lmax=Lm, max_small_ha=sm, max_ratio=ra)
        lab = S.finish(p, ids)
        n = lab.max()
        # land per region, as in save()
        ws = p['ws']; m = (ws > 0) & (lab > 0)
        fid = np.zeros(p['n'] + 1, np.int64); fid[ws[m]] = lab[m]
        cnt = np.zeros((n + 1, len(p['dates']))); sm_ = np.zeros_like(cnt)
        np.add.at(cnt, fid, p['count']); np.add.at(sm_, fid, p['ndvi'])
        pix = np.bincount(lab.ravel(), minlength=n + 1)
        kind = np.zeros(n + 1, np.int8)
        for r in range(1, n + 1):
            if pix[r] == 0: continue
            ok = (cnt[r] > 0) & (cnt[r] >= 0.6 * pix[r]); v = np.full(cnt.shape[1], np.nan); v[ok] = sm_[r][ok] / cnt[r][ok]
            kind[r] = 1 if S.land_label(p['dates'], v) in ('field', 'fallow') else 2
        maps[sq] = (lab, kind)
        for k, val in {**M.sizes(lab, kind), **M.cadastre(sq, 2026, lab, kind)}.items():
            tot[k] = tot.get(k, 0) + val
    g = t = 0
    for a, b in itertools.combinations(L.TUNING, 2):
        r = M.overlap(a, b, 2026, maps)
        if r: g += r['good']; t += r['total']
    print(f"{name:24s} regions {tot['fields']:5d} area {tot['field_ha']:8.0f} under20 {tot['under10']+tot['10to20']:4d} "
          f"({tot['under10_ha']+tot['10to20_ha']:5.0f} ha) over500 {tot['over500']:3d} ({tot['over500_ha']:6.0f} ha) "
          f"overplot {tot['over_plot']:2d} ({tot['over_plot_ha']:5.0f} ha) overlap {100*g/t:.1f}%", flush=True)
