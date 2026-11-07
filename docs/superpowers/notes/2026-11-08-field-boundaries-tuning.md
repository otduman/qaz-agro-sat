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
