"""Score the embedding model against the advance blind labels (NOTE.md steps 4-5), v3 beside it.
Model: LogisticRegression(C=1.0) on the 64 field-mean bands, trained on the dev labels (crop / no crop; unsure out).
Pass: agrees with the eye on >= 85% of decided test fields AND finds >= 70% of the eye's crops.
python score.py   (needs emb.csv from extract.py) -> calls.csv, printed summary"""
import csv
import hashlib
import os
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import cross_val_score

sys.path.insert(0, 'C:/Programming Projects/qaz-agro-sat/data/tayynsha/tools')
import harvest_v2 as hv  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
DEV = 'C:/Programming Projects/qaz-agro-sat/data/experiments/esil-nocrop-2024-11-20/'
D = 'C:/Programming Projects/qaz-agro-sat/data/'
HASH = '6915509b300f26d2ba3957993b361b1c8cbbe192d38362be498af900a96d37d5'
BANDS = [f'A{i:02d}' for i in range(64)]


def labels():
    lab = {}
    for p in 'nmk':
        lab.update({r['code']: r['label'] for r in csv.DictReader(open(f'{DEV}{p}-labels.csv'))})
    return lab


def v3_calls(rows):
    out, cache = {}, {}
    for r in rows:
        sq, y = r['square'], r['year']
        if (sq, y) not in cache:
            ser = {}
            for s in csv.DictReader(open(f'{D}{r["district"]}/{sq}/series-{y}/series-{y}.csv', encoding='utf-8')):
                ser.setdefault(s['field'], []).append(s)
            cache[(sq, y)] = ser
        out[r['code']] = 'crop' if hv.crop_seen(cache[(sq, y)].get(r['field'], [])) else 'no crop'
    return out


def main():
    assert hashlib.sha256(open(HERE + 'g-labels.csv', 'rb').read()).hexdigest() == HASH, 'blind labels changed'
    rows = list(csv.DictReader(open(HERE + 'emb.csv')))
    lab = labels()
    dev = [r for r in rows if r['set'] == 'dev' and lab.get(r['code']) in ('crop', 'no crop')]
    X = np.array([[float(r[b]) for b in BANDS] for r in dev])
    y = np.array([lab[r['code']] == 'crop' for r in dev])
    m = LogisticRegression(C=1.0, max_iter=2000)
    cv = cross_val_score(m, X, y, cv=5)
    m.fit(X, y)
    print(f'dev: {len(dev)} fields ({y.sum()} crop, {(~y).sum()} no crop); 5-fold agreement {cv.mean():.0%}')

    test = [r for r in rows if r['set'] == 'test']
    p = m.predict_proba(np.array([[float(r[b]) for b in BANDS] for r in test]))[:, 1]
    model = {r['code']: ('crop' if q >= 0.5 else 'no crop') for r, q in zip(test, p)}
    v3 = v3_calls(test)
    eye = {r['code']: r['label'] for r in csv.DictReader(open(HERE + 'g-labels.csv'))}
    with open(HERE + 'calls.csv', 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['code', 'ha', 'eye', 'model', 'p_crop', 'v3'])
        for r, q in zip(test, p):
            w.writerow([r['code'], r['ha'], eye[r['code']], model[r['code']], round(q, 2), v3[r['code']]])
    decided = [c for c in eye if eye[c] in ('crop', 'no crop')]
    crops = [c for c in decided if eye[c] == 'crop']
    for name, calls in (('model', model), ('v3', v3)):
        agree = sum(calls[c] == eye[c] for c in decided)
        found = sum(calls[c] == 'crop' for c in crops)
        unsure = [calls[c] for c in eye if eye[c] == 'unsure']
        print(f'{name}: agrees {agree}/{len(decided)} ({agree / len(decided):.0%}); eye crops found {found}/{len(crops)} '
              f'({found / len(crops):.0%}); on the {len(unsure)} unsure: {unsure.count("crop")} crop')
    agree = sum(model[c] == eye[c] for c in decided) / len(decided)
    found = sum(model[c] == 'crop' for c in crops) / len(crops)
    print('PASS' if agree >= 0.85 and found >= 0.70 else 'FAIL', f'(agree {agree:.0%} >= 85%, crops {found:.0%} >= 70%)')


if __name__ == '__main__':
    main()
