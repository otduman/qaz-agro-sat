# Satellite embeddings for crop vs no crop (owner 2026-11-20, item 1; prepared, waiting for an Earth Engine account)

Why: rules built on clear optical photos fail in cloudy or late seasons (Esil 2024: crop seen v3 finds about half of
the missed crops; Tayynsha 2024: cereals and oilseeds cannot be told apart). Google's annual satellite embeddings
(GOOGLE/SATELLITE_EMBEDDING/V1/ANNUAL, AlphaEarth Foundations: 64 numbers per 10 m pixel per year, built from optical,
radar and other sources) summarise the whole season, clouds included.

## Needs from the owner
An Earth Engine account (free for non-commercial use) on the owner's Google account, and one `earthengine authenticate`
in a terminal on this laptop (it opens the owner's browser; the owner signs in, not Claude). Installed already:
earthengine-api 1.7.53, scikit-learn 1.9.1.

## Plan (written before any embedding is read)
1. Coverage: check which years the collection holds (catalog said 2017-2024). If 2024 only, the test is Esil 2024 and
   Tayynsha 2024, the seasons where the rules fail.
2. Features: per field, the mean of the 64 embedding bands over the field (shrunk 20 m), from extract.py.
3. Training (development) labels: the 120 fields labelled by eye 2026-11-20 (n-, m-, k- samples in
   ../esil-nocrop-2024-11-20; crop 64, no crop 30, unsure left out; the n- labels lean to "no crop", see that NOTE)
   plus the owner's 150 marked boxes (../land-key-2026-11-15, 2026 only, if 2026 embeddings exist).
4. Model: logistic regression on the 64 numbers (no tuning beyond a fixed C = 1.0), crop vs no crop.
5. Fresh blind check: 40 "no crop seen" fields of Esil 2024 not among the 120, seed 20261125, sheets as sheets2.py
   (every date >= 40% clear), labelled and hashed before the model's calls are read.
   Pass: the model agrees with the eye on at least 85% of decided fields AND finds at least 70% of the eye's crops
   (v3 found about half). Compared beside it: v3 on the same 40.
6. If it passes: a second test on crop groups (cereal vs oilseed) for Tayynsha 2024 against the official ~21% oilseed
   share, written down before it is run.

## Fresh blind labels made in advance (2026-11-20 ~12:40)
sheets.py drew the 40 fields (seed 20261125; Esil 2024 jar "no crop seen", not the 120 dev fields), sheets/g-NN.png,
key g-key.csv (square, field only: no rule or model call exists for them yet). Labelled by eye before any embedding
was read: g-labels.csv, SHA-256 6915509b300f26d2ba3957993b361b1c8cbbe192d38362be498af900a96d37d5. 20 crop, 5 no crop, 15 unsure (many large fields' thin outlines are hard to read
on the sheets). The test scores the model (and v3, beside it) against these once the embeddings are extracted.

## Result (2026-11-20, Earth Engine project qazagrosat-ee): FAIL, not adopted
- Collection holds 2017-2025 (no 2026): the 13 dev fields from 2026 left out. Outlines are lon/lat (the prepared
  extract.py assumed UTM; fixed before the first run). Placement check: ee area of the 20 m-shrunk outline / our area
  median 0.94. emb.csv: 147 fields (94 dev 2024, 13 dev 2025, 40 test).
- Dev: 84 decided fields (57 crop, 27 no crop). score.py, registered model (LogisticRegression C=1.0, raw bands).
- Test (40 blind, labels hash checked): model agrees 20/25 decided (80% < 85%), finds 20/20 eye crops; calls all 40
  "crop" (p_crop 0.60-0.71). v3 on the same 40: 16/25 (64%), 11/20 crops (55%). FAIL by the written rule.
- Why (dev only, after the test): the bands are tiny (sd ~0.023), so C=1.0 shrinks the model to the class share; dev
  5-fold = 68% = "always crop". With bands standardised dev 5-fold is 75% (C 1.0 or 0.1) against 68% for "always
  crop": a small real signal, far from the 85% bar. The 40 test fields are now seen; any retry needs a new written
  rule and a fresh blind set.
