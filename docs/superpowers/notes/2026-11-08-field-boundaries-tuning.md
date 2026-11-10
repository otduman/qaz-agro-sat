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

One consequence of round 1: with markerShrink at 2 instead of 3, a gap in a boundary line keeps two
fields apart only up to about 40 m (twice the shrink), not the 60 m the plan's decision 2 assumed. A
gap wider than that, such as a farm track crossing, can join two fields' seeds into one. The step 2
review pointed this out (its finding M4); the gap tests use a 4-pixel gap, exactly the new limit.

## Results on all three seasons (frozen values)

Each run used `java -Xmx1g`, so it stayed under 1 GB of memory.

| season | dates used (cloudy skipped) | run time | regions | field | fallow | other | left out |
|---|---|---|---|---|---|---|---|
| 2025, tuned on | 50 (2) | 72 s | 685 | 316, 24,047 ha | 11, 663 ha | 358, 15,382 ha | 348.5 ha, all regions under 5 ha |
| 2024 | 46 (4) | 75 s | 518 | 280, 25,748 ha | 12, 1,401 ha | 226, 12,882 ha | 410.7 ha, all regions under 5 ha |
| 2023 | 47 (2) | 78 s | 433 | 214, 26,001 ha | 36, 2,644 ha | 183, 11,457 ha | 338.9 ha, all regions under 5 ha |

No pixel in any season had too few clear dates. Regions labelled other for having no clear spring
date: 7 in 2025, 3 in 2024, none in 2023.

Box check, against the experiment's own photo readings (a consistency check, not ground truth). The
pooled agreement flatters: most boxes are one field, so a map with no boundaries at all agrees with
nearly all of them. The split by what the reading expects is the honest figure.

| season | two-field boxes crossed | one-field boxes not crossed | pooled | a map with no boundaries |
|---|---|---|---|---|
| 2025, seen while tuning | 3 of 4 | 24 of 30 | 27 of 34 | 30 of 34 |
| 2024 | 6 of 7 | 21 of 27 | 27 of 34 | 27 of 34 |
| 2023 | 7 of 7 | 26 of 27 | 33 of 34 | 27 of 34 |

Boxes that disagree:
- 2025: F02, F07, F08, F09, B18 crossed though read as one field; B13 not crossed though read as two;
  D02 a lake in pieces.
- 2024: F02, F07, F09, B02, B10, D01 crossed though read as one field; B19 not crossed though read as two.
- 2023: F07 crossed (largest region 85%) though read as one field.

(An earlier version of this table quoted only the pooled figure; the independent review pointed out
that it needs the split and the no-boundary baseline beside it.)

Stability, the share of one season's boundary pixels within 20 m of the other season's, with the
share a random line would get:

| from -> to | share | chance |
|---|---|---|
| 2025 -> 2024 | 60.4% | 13.9% |
| 2024 -> 2025 | 70.2% | 16.2% |
| 2025 -> 2023 | 51.7% | 11.9% |
| 2023 -> 2025 | 69.3% | 16.2% |

## After the freeze: two method fixes (8 November 2026, step 3)

Two changes to the method itself were made at the start of step 3. Neither is a retune: every frozen
value above stays as it is, and the reruns below were accepted whatever they showed.

1. **Every marker starts flooding at once.** The flood used to start each marker at its own edge
   level, so a calm but busy field middle started late. A quieter neighbour could then flood through a
   gap in the ridge and take ground next to the busy field. The independent review of step 2 found this
   (its finding M1). Test: `WatershedTest.aHigherMarkerKeepsTheBandBesideIt`.
2. **Snow the cloud mask misses is not clear.** A pixel is snow when its snow index
   (green - SWIR16) / (green + SWIR16) is at least 0.4 and its near-infrared at least 0.11, the standard
   MODIS and Sentinel-2 rule. Tests: `SnowTestTest`, `StackImagesTest.aSnowyPixelIsNotClear`.

**What the snow test catches: almost nothing.** Over all 143 stored dates it removes at most 0.55% of
the pixels the cloud mask calls clear (on 2023-08-12; I have not looked at what they are), and next to none on the
late-October dates. The expectation was the opposite, so I looked at why:
- The cloud mask already flags full snow cover as its own snow class; for example 91,000 of its 20 m
  pixels on 26 October 2023.
- What does slip past is bright ground with a *low* snow index. On 27 October 2024 the pixels the mask
  calls clear have a median green reflectance of 0.155, against about 0.065 two days earlier, yet their
  snow index is below 0 for 90% of them. On 26 October 2023 the median snow index is 0.00 and 90%
  are under 0.23. Step 1 saw snow on both dates by eye. A pixel that is partly snow and partly dark
  stubble has a low snow index, and so does thin cloud or haze, so the index can't tell these apart.

So the snow rule stays (it is standard and costs nothing), but the late-October problem is haze and
partial snow, which no threshold here was tuned for. Step 3 records each field's blue reflectance and
snow share per date so that step 4 can deal with them on evidence. (Snow share counts the mask's own snow
and the snow test only where the mask sees no cloud, since ice cloud passes the snow test too. Turbid
floodwater can pass it as well: some small fields in May 2024, a flood spring, are flagged. See the step 3
results notes.)

**Reruns with both fixes**, each under `java -Xmx1g`:

| season | run time | regions | field | fallow | other | left out |
|---|---|---|---|---|---|---|
| 2025 | 46 s | 691 (was 685) | 315, 23,959 ha | 11, 661 ha | 365, 15,461 ha | 360.1 ha |
| 2024 | 43 s | 520 (was 518) | 280, 25,736 ha | 13, 1,405 ha | 227, 12,896 ha | 403.1 ha |
| 2023 | 45 s | 426 (was 433) | 212, 25,990 ha | 36, 2,645 ha | 178, 11,451 ha | 355.0 ha |

(The runs are faster than the first ones. I did not look into why; the fixes themselves add a little work.)

Boundaries barely moved. The share of the old boundary pixels within 20 m of the new ones is
98.7% for 2025, 99.5% for 2024 and 98.5% for 2023.

The box check is unchanged in every season: 3 of 4 and 24 of 30 for 2025, 6 of 7 and 21 of 27 for
2024, 7 of 7 and 26 of 27 for 2023. Stability between seasons moved by at most 0.4 points:

| from -> to | share | chance |
|---|---|---|
| 2025 -> 2024 | 60.2% | 13.8% |
| 2024 -> 2025 | 70.4% | 16.3% |
| 2025 -> 2023 | 51.3% | 11.8% |
| 2023 -> 2025 | 69.7% | 16.3% |

## Faint straight borders (9 November 2026)

Plan: `docs/superpowers/plans/2026-11-09-field-lines.md`.

**The problem.** Step 3's box check found every box off by more than 0.05 inside an oversized region (2023-F0017,
3,633 ha; 2024-F0056, 1,229 ha). Joining is not the cause: F0017 came out of the watershed as 2 regions, F0056 as
1. Inside F0017, 95% of pixels have a season edge under the calm level of 0.15, and on no date do more than 0.4%
of its pixels reach 0.30. On the season-edge picture the borders between its fields are plainly there, as thin
straight lines of about 0.10 to 0.13 against 0.05 to 0.09 inside the fields. They were too faint to stop the
calm area, so one marker spread over several fields.

**The change.** Field borders here are long and straight; noise and wet hollows are not. So the season edge is
now also read along lines (`LineStrength`): for each pixel and each of 12 directions, the median season edge
along a 71-pixel (710 m) segment centred on it, minus the mean season edge in the 71 x 71 square around it. A
pixel whose best direction scores at least 0.025 is raised to the calm level, so markers stop at it and floods
meet on it. Joining is unchanged. In a first trial with 31-pixel segments, the line strength across one 2025 border was about 0.05 on the border
and under 0.015 inside the fields.

**Tuning on 2025.** Tried with scratch code before writing the product code. Rows marked * also checked
brightness curves when joining; that made no difference at a 35-pixel radius and was dropped.

| radius | lineEdge | regions | field area in regions over 500 ha | two-field boxes crossed | one-field boxes not crossed |
|---|---|---|---|---|---|
| (before) | | 691 | 5,545 ha | 3 of 4 | 24 of 30 |
| 15 | 0.02 | 1,008 | 564 ha | 4 of 4 | 17 of 30 |
| 15 | 0.03 | 804 | 3,130 ha | 4 of 4 | 23 of 30 |
| 15 | 0.04 | 760 | 2,914 ha | 3 of 4 | 24 of 30 |
| 25 | 0.025* | 872 | 1,084 ha | 4 of 4 | 23 of 30 |
| 35 | 0.02* | 880 | 571 ha | 4 of 4 | 21 of 30 |
| 35 | 0.025 | 762 | 1,082 ha | 4 of 4 | 23 of 30 |
| 35 | 0.03* | 806 | 1,421 ha | 4 of 4 | 24 of 30 |

Chosen: radius 35, lineEdge 0.025. By eye on the two largest 2025 regions, radius 35 split the joined fields as
well as radius 25, with fewer ragged pieces. (I looked at 2023-F0017 to diagnose the problem before any value was
chosen, but no value was chosen on it.)

**All three seasons**, each under `java -Xmx1g`, 2024 and 2023 unchanged:

| season | run time | regions | field | fallow | other | left out |
|---|---|---|---|---|---|---|
| 2025, tuned on | 47 s | 762 (was 691) | 369, 24,000 ha | 11, 697 ha | 382, 15,454 ha | 289.2 ha |
| 2024 | 48 s | 659 (was 520) | 377, 25,836 ha | 18, 1,417 ha | 264, 12,957 ha | 230.7 ha |
| 2023 | 48 s | 569 (was 426) | 301, 26,292 ha | 45, 2,387 ha | 223, 11,503 ha | 258.9 ha |

Oversized regions, before -> after:

| season | field area in regions over 500 ha | regions over 1,000 ha | largest field region |
|---|---|---|---|
| 2025 | 5,545 ha (23%) -> 1,082 ha (5%) | 2 -> 0 | 1,976 ha -> 575 ha |
| 2024 | 7,484 ha (29%) -> 1,162 ha (4%) | 3 -> 0 | 1,229 ha -> 659 ha |
| 2023 | 11,838 ha (46%) -> 1,695 ha (6%) | 3 -> 0 | 3,633 ha -> 650 ha |

On the photo, the old 3,633 ha region of 2023 now comes out as the patchwork of rectangular fields it is.

**Box check** (consistency, not ground truth):

| season | two-field boxes crossed | one-field boxes not crossed | pooled |
|---|---|---|---|
| 2025, tuned on | 3 of 4 -> 4 of 4 | 24 of 30 -> 23 of 30 | 27 -> 27 of 34 |
| 2024 | 6 of 7 -> 7 of 7 | 21 of 27 -> 19 of 27 | 27 -> 26 of 34 |
| 2023 | 7 of 7 -> 7 of 7 | 26 of 27 -> 24 of 27 | 33 -> 31 of 34 |

Every two-field box is now crossed. Fixed: 2025 B13, 2024 B02 and B19. Newly crossed though read as one field,
each looked at on the post-harvest photo:
- 2024 B14: the new line runs along a visible change of colour at the box's top edge; probably a real border.
- 2024 F04: split along a dark track across the field; could be a border, can't tell.
- 2023 F02: split along a pale strip with dashes running through the field; unclear.
- 2024 B11: split by a line with no visible border on that photo.
- 2023 B08: cut into strips along a pale straight stripe. I first called this wrong; see below.
- 2025 D01: a long thin piece cut off the meadow along a faint line. Wrong, but the meadow is "other", so it
  never reaches the series.

**Slivers, checked afterwards.** The pieces at 2023 B08 are not slivers of one field: they are 18 to 66 ha, and in
July their greenness differs by up to 0.14 (0.83, 0.77 and 0.69 on 13 July 2023), so they grew differently. They
are most likely separate strips. Joining step 2 had already refused to join them, because their curves differ. Nor
did the change make thin regions more common. Field and fallow regions at most about 160 m wide (half-width of at
most 8 pixels), before -> after: 2025 54 (458 ha) -> 47 (466 ha); 2024 43 (433 ha) -> 55 (652 ha); 2023 28 (269 ha)
-> 43 (462 ha). That is under 3% of the field area in each season, and the 2025 photos show narrow strips farmed
side by side in several places. A rule joining thin regions to a neighbour would join strips that grow differently, so I did not
add one.

**Stability** rose: 2025 -> 2024 60.2% -> 64.1% (chance 15.6%), 2024 -> 2025 70.4% -> 70.8% (17.4%),
2025 -> 2023 51.3% -> 57.4% (14.2%), 2023 -> 2025 69.7% -> 69.3% (17.4%).

**Step 3 box gaps** (rerun of `series` and `SeriesCheck`): 29 boxes lie wholly inside one field's interior
(was 40; the new borders cross some boxes). All 29 have a median gap under 0.05 (was 36 of 40). The median of
the median gaps is 0.007 (was 0.016), the largest 0.024 (was 0.067).

Outputs from before this change are kept in `data/fields-<year>-before-lines` and
`data/series-<year>-before-lines`.
