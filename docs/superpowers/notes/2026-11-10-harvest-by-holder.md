# Harvest pace by land user: first look

10 November 2026, branch `harvest-by-holder`. Results page: https://claude.ai/artifact/F1VMc3K459nUk52Zfc1EAU
(holders lettered; names stay in `data/kokshetau/`, outside git).

## Method

- Holders: the cadastral lookup of the 90 demo-band fields of 2026 (`2026-11-10-cadastre.md`).
- Cut dates: `FieldStudy date <data> <year> <out.csv>` runs the frozen rule v1 (`Analysis.swirJump`, unchanged) on
  every field of `series-landsat-<year>`: Sentinel-2 rows, plus Landsat rows (already on the Sentinel-2 scale) on
  dates Sentinel-2 has no row for; dates at least 85% clear. Status `dated`, `no cut found` or `never ripe`.
- Link: each season's field takes the 2026 holder covering at least 60% of its pixels (`fields-<year>.tif` against
  `fields-2026.tif`); land `field` only. A field counts as cut on the middle day of its dated step.
- Pace: dates by which 10%, 50% and 90% of a holder's dated field area was cut; only with at least 8 dated fields.
  Script: `data/kokshetau/holders/by_holder.py`, output `holders/by-holder.csv`.

## Result

| | 2024 halfway | 2025 halfway | 2026 halfway |
|---|---|---|---|
| whole square | 17 Sep | 21 Sep | 4 Sep |
| holder A (company, ~6,000 ha, 23-31 fields) | 5 Oct (10->90%: 18 days) | 21 Sep | 7 Sep |
| holder B (company, ~2,400 ha, 16-22 fields) | 19 Sep | 23 Sep | 2 Sep |

- Holder A was about two weeks late in 2024 (30 Sep without one 1,222 ha joined region), on pace in 2025 and 2026.
- Holders C-F have 1-6 fields each: no pace. 2023 left out (joined fields, dates bunch on 15 and 22 Sep).
- Dated per season: 81 of 159 fields (2023), 194/278, 175/243, 194/256.

## Limits

Rule v1 is about 2/3 right within one photo on hand readings; a third of fields have no dated cut; October rises
may be tillage (three of holder A's 2024 fields dated 16-30 Oct); holders are 2026's; no ground truth.

## Photo check of holder A, 2024

Strips from `FieldStudy strips` for holder A's 12 dated fields and its largest undated one; 9 read by eye
(`data/kokshetau/holders/readings-a-2024.csv`). The rule's dates were known before reading, so this is not blind.

- The late harvest is real: on the 1,222 ha joined region strips were cut one by one from 16 Sep and the last turned
  pale between 3 and 16 Oct; swath lines on two other large fields appear 26 Sep to 1 Oct. One 614 ha field was cut
  from 18 Sep, as dated.
- Three October dates were false (fields of 25, 58 and 69 ha; the last bare all season): a very bright 16 Oct photo
  (and a hazy 13 Oct one) faked the rise. A fourth (395 ha) is unclear for the same reason.
- Without the three false fields the halfway date is 30 Sep, about two weeks after the square's 17 Sep.
- Lesson for the dating step: late-season photos need a screen (brightness or snow/frost check) before dates are used.
