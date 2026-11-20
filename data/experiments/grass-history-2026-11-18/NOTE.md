# Grass history v1: grass that was a crop field the season before (owner 2026-11-18: "ok proceed")

Why: the owner sees ordinary fields inside the grass layer. A first look (2026-11-18, Tayynsha only, before this note):
96 of 1,172 Tayynsha grass regions (16k ha) lie at least half under a field of our 2025 map (any field, crop or not);
another ~17% of the area is near-rectangular. The grass rule looks at one season only, so a field that rested, failed
or was sown to fodder in 2026 can pass as grass.

## Rule (written before running it on the districts)
For each district and season Y with a grass list (`<district>/grass-Y.csv`), every grass region is checked against the
other seasons X of the same square that have a field map and crop groups (2025 for 2026; also 2024 in the Esil squares
that have it). Same square, same 10 m grid (checked: the season stacks of both years have the same grid line).
- crop pixels of season X = pixels of a field-map region whose crop group (`crops-X-v2.csv`) is short season, long
  season or yellow flowers. Not: perennial grass (sown hay stays grass), unclear, no crop seen, fallow, other land.
- share = crop pixels of season X inside the region / region pixels; take the largest over the seasons X.
- share >= 0.5: the region was a crop field in X, so it is left out of the grass layer (shown as plain land, not as
  fallow or field: what it is in Y is not known).
Region masks: Tayynsha from the L2 labels of the grass experiment (`grass-tayynsha-2026-11-17/<sq>/labels-2026.npz`,
the ids in other-2026.geojson); Esil and Sandyktau from `<sq>/fields-Y/fields-Y.tif`.
Output: `<district>/grass-was-field-Y.csv` (square, region, area_ha, share, season). The site's grass_regions skips them.

## Blind check (written before the sample)
50 regions: 30 drawn at random from the ones the rule moves and 20 from the ones it keeps (seed 20261118, across the
three districts in proportion to their counts), mixed and coded H01-H50 so the labeller cannot tell which is which.
Sheet per region: two rows, 2025 and 2026, each six true-colour photos (May, June, July, early Aug, late Aug, Sept;
clearest date for the region) and one spring false-colour photo. Labels, saved and hashed before scoring:
- A "field in 2025": in the 2025 row most of the region shows field work: an even crop that ripens and is cut
  (sudden change, stubble or swath lines), or bare ploughed soil.
- B "grass both years": uneven green-brown cover in both rows, no cutting of a crop and no ploughing.
- U unsure.
Pass: among moved regions with a decided label, at least 80% are A, and no more than 10 of the 30 are U.
Reported beside it: the share of A among the kept regions (what the rule still misses), hectares moved per district.
If it fails: nothing goes to the site; not retuned on this sample.

## Run and result (2026-11-18)
`grass_history.py` (data/tayynsha/tools, tests test_grass_history.py) on the three districts, season 2026:
- Tayynsha: 91 of 1,172 grass regions moved, 14,637 of 394,263 ha (all by 2025);
- Sandyktau: 108 of 1,158, 8,174 of 141,148 ha (2025);
- Esil: 166 of 1,487, 28,142 of 290,828 ha (142 by 2025, 24 by 2024).
Total 365 regions, 50,953 ha (6.6% of the grass area).
Blind sample `sample.csv` (seed 20261118), sheets `sheets/h-01..10.png` (sheets2.py), labels saved before scoring:
`labels.csv` SHA-256 1d584ed742e7c27d50464ce7c7498fa8ef8b7637337be658b2ef7c8ad2187621.
- Moved (30): 23 A, 3 B, 4 U -> 23/26 = 88% (need 80%), U 4 (max 10). **PASS.** The 3 B: a dark wet/wooded patch
  (Tayynsha), a thin strip along a field edge (Tayynsha), a small triangle of steppe (Esil).
- Kept (20): 3 A, 15 B, 2 U -> about 15% of the kept grass regions still look like a field in 2025 (strips and
  parts of field blocks the 2025 map did not draw as crop); a next step (longer history) would be needed for those.
