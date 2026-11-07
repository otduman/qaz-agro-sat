# Field boundaries: tuning log (2025 only)

Tuning discipline from the spec: parameters are tuned on the 2025 season only; 2024 and 2023 then run
unchanged and are reported as they come out. Each round below changed `FieldParameters.DEFAULTS` only.

## Round 0: starting values

- Result: 25 watershed regions for about 40,000 ha; 22 regions in the end (14 field, 0 fallow, 8 other).
  Fields were flooded together.
- Why: inside fields the season edge is 0.05 to 0.09 (median per box) with scattered pixels up to
  0.15 to 0.2; boundaries are 0.3 and more. With calm meaning under 0.05, only 0.1% of the area
  survived the 3-pixel shrink as markers, and 30 of 35 boxes had no marker at all.
- Measured offline on the 2025 edge image (marker share of area, boxes without a marker):
  calm < 0.10 and shrink 3: 12%, 4 boxes; calm < 0.15 and shrink 2: 53%, 0 boxes.

## Round 1: markerEdge 0.05 -> 0.15, markerShrink 3 -> 2, ridgeWeak 0.08 -> 0.2

- Why ridgeWeak moved too: the noise ridges between marker fragments of one field now sit around
  0.15, so a merge limit under the calm level would never merge them. Real boundaries are 0.3 and up.
- Result: 888 watershed regions, 103 merges, 685 regions (299 field 23,115 ha; 12 fallow 680 ha;
  374 other 16,298 ha). Outlines follow the field edges in the photos.
- Box check (2025, seen while tuning): 27 of 34 agree. The 7 disagreements, looked at by eye:
  F02, F09, B18 straddle a real strip boundary; F07 is split along a wet hollow; F08 along a tone
  change; B13 is not split though read as two fields; D02 is a lake broken into many "other" pieces.
- Land labels: of the 16,298 ha "other", 11,395 ha are green already in spring (lowest spring NDVI
  0.35 to 0.7, against 0.10 to 0.25 for fields): pasture, meadow, groves, but also a striped field
  peaking in early June (a winter crop or hay). 3,767 ha are bare in spring but fail the crop curve:
  peak before 20 June (1,728 ha; by eye some weedy fallow, some steppe), peak under 0.5 (533 ha),
  no drop of 0.25 by 30 September (1,507 ha; by eye at least one clearly harvested field).
- Unit-test fixture `aFaintStripAcrossAFieldIsMergedBack` now takes its strip depth halfway between
  markerEdge and ridgeWeak, instead of the hard-coded 0.06 that sat between the old values.

## Round 2: dropBy 0.25 -> 0.2

- Why: 16 regions (856 ha) labelled other dropped between 0.20 and 0.25 after their peak; the largest,
  by eye, is a harvested field with combine lines in September.
- Result: 685 regions (316 field 24,047 ha; 11 fallow 663 ha; 358 other 15,382 ha); 348.5 ha left
  out as regions under 5 ha; no pixel without clear data. Box check unchanged, 27 of 34.

## Frozen

Stopped after round 2. The rest of "other" is mostly land green already in spring, which the rule
means to leave out; the doubtful cases there (weedy fallow against steppe, winter crops or hay
against pasture) can't be told apart by the NDVI curve alone, so more rounds would fit the
values to my eye rather than to the ground. Final values, used unchanged on 2024 and 2023:

| name | value | name | value |
|---|---|---|---|
| brightnessWeight | 5.0 | minLabelDates | 5 |
| edgeRank | 3 | springEnd | 06-15 |
| markerEdge | 0.15 | springBare | 0.3 |
| markerShrink | 2 | peakGreen | 0.5 |
| minMarkerPixels | 50 | peakFrom | 06-20 |
| ridgeWeak | 0.2 | dropBy | 0.2 |
| curveTolerance | 0.05 | dropUntil | 09-30 |
| minCommonDates | 5 | fallowBare | 0.3 |
| minClearShare | 0.6 | fallowShare | 0.7 |
| minRegionHectares | 5.0 | waterMedian | 0.05 |
| maxHoleHectares | 1.0 | uniformitySpread | 0.25 |
| simplifyMetres | 10.0 | | |

## Results on all three seasons (frozen values)

Each run used `java -Xmx1g`, so it stayed under 1 GB of memory.

| season | dates used (cloudy skipped) | run time | regions | field | fallow | other | left out |
|---|---|---|---|---|---|---|---|
| 2025, tuned on | 50 (2) | 72 s | 685 | 316, 24,047 ha | 11, 663 ha | 358, 15,382 ha | 348.5 ha, all regions under 5 ha |
| 2024 | 46 (4) | 75 s | 518 | 280, 25,748 ha | 12, 1,401 ha | 226, 12,882 ha | 410.7 ha, all regions under 5 ha |
| 2023 | 47 (2) | 78 s | 433 | 214, 26,001 ha | 36, 2,644 ha | 183, 11,457 ha | 338.9 ha, all regions under 5 ha |

No pixel in any season had too few clear dates. Regions labelled other for having no clear spring
date: 7 in 2025, 3 in 2024, none in 2023.

Box check, against the experiment's own photo readings (a consistency check, not ground truth):

| season | agree | boxes that disagree |
|---|---|---|
| 2025, seen while tuning | 27 of 34 | F02, F07, F08, F09, B18 crossed though read as one field; B13 not crossed though read as two; D02 a lake in pieces |
| 2024 | 27 of 34 | F02, F07, F09, B02, B10, D01 crossed though read as one field; B19 not crossed though read as two |
| 2023 | 33 of 34 | F07 crossed (largest region 85%) though read as one field |

Stability, the share of one season's boundary pixels within 20 m of the other season's, with the
share a random line would get:

| from -> to | share | chance |
|---|---|---|
| 2025 -> 2024 | 60.4% | 13.9% |
| 2024 -> 2025 | 70.2% | 16.2% |
| 2025 -> 2023 | 51.7% | 11.9% |
| 2023 -> 2025 | 69.3% | 16.2% |
