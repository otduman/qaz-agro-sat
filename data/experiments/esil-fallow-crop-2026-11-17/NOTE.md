# Esil "fallow" that is a thin crop (owner 2026-11-17: "do everything else")

Of 48 regions labelled by eye that the site still shows as fallow in Esil 2026, 26 are thin dry-land crops, 16
fallow, 6 steppe. Seen labels (not a test): the tillage index on bare late-summer dates separates harvested crops
(straw stubble, NDTI 0.06-0.11) from worked fallow (bare soil, NDTI 0.02-0.05; 5 of 16 higher).

## Rule (written before the fresh sample)
A region the site shows as fallow (map fallow, not hidden as idle) becomes a crop field when
- median NDTI over its clear dates with NDVI < 0.3 from 1 Aug to 30 Sep is at least 0.065 (at least 3 such dates), and
- its peak NDVI 1 Jun - 31 Jul is at least 0.33.
It is then shown as a cereal field (crop group grain, no harvest date unless one is found later).

## Blind check
60 regions the site shows as fallow in Esil 2026, centre in the square core, 20 ha or more, none labelled before,
drawn at random (seed 20261117), labelled by eye on the season sheets (criteria file), hash recorded before scoring.
Pass: C1 of regions the rule makes crop, at least 75% are crop by eye; C2 of eye-crop regions in the sample, at
least 60% are made crop; C3 of eye-fallow regions, at most 25% are made crop.

Labels saved: `labels.csv` (60), SHA-256 f3daea3022cf95dc7cdcb01bb72632fb41f682cf2b0143f0e6aab0ad797ee815.
Result: 37 crop, 17 fallow, 6 steppe by eye. **C1 28/36 = 78% (>= 75%) PASS; C2 28/37 = 76% (>= 60%) PASS;
C3 4/17 = 24% (<= 25%) PASS** (C3 at the limit). Applied to Esil 2026 as `data/esil-akmola/fallow-crop-2026.csv`.

Applied (core regions only, so a region in two overlapping squares is counted once): 322 regions, 76,900 ha, dated by
harvest rule v2 (119 dated, 82 cut in a cloud gap, 121 no cut found). Esil 2026 on the site: fallow 142k -> 66k ha
(20% -> 9% of farmland), fields 1,527 -> 1,748. Also: a field drawn wholly inside a bigger field's outline now joins
it (fold_inside), which removed the last Esil 2025 overlap.
