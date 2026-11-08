# Per-field series: results on three seasons

Step 3 of the Phase 2 groundwork, 8 November 2026. Plan: `docs/superpowers/plans/2026-11-08-field-series.md`.
Nothing was tuned: the 20 m interior shrink and the 60% clear share are the spec's values, and the
field maps are step 2's, rerun after its two method fixes (see the step 2 tuning notes, section "After
the freeze").

## Runs

Each run used `java -Xmx1g -jar cli\target\kazagrosat.jar series --stack data\season-<year>
--fields data\fields-<year> --out data\series-<year>`.

| season | run time | dates | fields in the series | other regions, listed only | rows | rows with blank values |
|---|---|---|---|---|---|---|
| 2025 | 44 s | 50 | 326 (315 field, 11 fallow) | 365 | 16,300 | 7,946 (49%) |
| 2024 | 45 s | 46 | 293 (280 field, 13 fallow) | 227 | 13,478 | 6,329 (47%) |
| 2023 | 51 s | 47 | 248 (212 field, 36 fallow) | 178 | 11,656 | 3,709 (32%) |

Rows equal fields times dates in every season. No field lost its whole interior to the 20 m shrink,
so every blank row is a field less than 60% clear on that date. 2023 was the clearest season, as the
stack's clear shares already said.

**Snow.** Rows with any snow in the field's interior, by the cloud mask's snow class or the snow test:
1,099 in 2025, 839 in 2024 and 612 in 2023 (most of them a few pixels). Rows with at least 5% snow
that still have values: 8 in 2025, all on 30 September, and 18 in 2023, all on 26 October; 9 in 2024.
Those rows carry their snow share, so step 4 can drop or keep them on evidence.

## Box check

For each experiment box lying wholly inside one field's interior, the field's median NDVI against the
box's NDVI from the experiment (`optical.csv`, dates at least 60% clear), on the dates both have.
Same pixels, different crop, so the gap should be small.

| season | boxes wholly inside one field's interior | median gap under 0.05 | median of the median gaps |
|---|---|---|---|
| 2025 | 11 of 35 | 11 | 0.011 |
| 2024 | 13 of 35 | 11 | 0.013 |
| 2023 | 16 of 35 | 14 | 0.018 |

Over all 40 box-seasons the median of the median gaps is 0.016 and the largest 0.067. Most boxes that
don't qualify straddle a field edge or the 20 m margin, as expected for 600 m to 1 km boxes.

The four boxes over 0.05, looked at on the photos:
- **2023 B03 (0.062) and B05 (0.067)** both lie in region 2023-F0017, which is 3,633 ha. On
  16 September 2023 it is plainly a patchwork of separate fields in different browns. In July they were
  all equally green, so the step 2 watershed found no edge between them and merged them. The field's
  median mixes several fields, so a single box differs from it. Four more boxes lie in the same region
  with gaps of 0.017 to 0.018; their fields happen to be close to the mix.
- **2024 B01 (0.051)** lies in 2024-F0056 (1,229 ha), which also takes in several rectangles that look
  like separate fields on 10 September 2024.
- **2024 B09 (0.065)** lies at the western edge of 2024-F0029 (389 ha). On 10 September 2024 the box's
  part looks browner than the rest of the region.

So the series reads what is inside each region correctly. Where it disagrees with a box, the
region is wider than one field: step 2 merged fields that were equally green all summer. That is a
limit of step 2 this check exposes, and it goes on the open list. Nothing was adjusted for these boxes.

## A first look at lines

This describes only; it decides nothing, and step 4 is where cut dating will be built and tested.

For boxes read as "cut seen" with a cut window, the field's median line strength on dates before the
window and after it:
- **2025:** B04 0.10 -> 0.29 (4 dates after); B15 0.07 -> 0.29 (2 dates after).
- **2024:** rises in 6 of 8. F06 0.09 -> 0.17, C01 0.21 -> 0.50, B04 0.07 -> 0.25,
  B11 0.12 -> 0.25, B15 0.15 -> 0.28, B16 0.15 -> 0.43. The two falls, B01 and B05, have one date
  after the window.
- **2023:** mixed. It rises for B11 (0.19 -> 0.53) and B17, and falls for F02, C01, B16 and D01.
  F02 and B17 already show strong lines before the cut (0.44 and 0.41), so in 2023 some crops show
  their sowing rows all season.

Lines appearing after a cut is visible in 2024 and 2025, as the photo readings suggested. A level
of line strength alone won't date a cut, because some fields have lines all season; its change will
need to be read alongside SWIR and NDVI.
