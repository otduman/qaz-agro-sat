# Kokshetau: field maps and the first fresh harvest-rule test

10 November 2026, branch `landsat-photo-offset`. Steps 2 to 4 of the Kokshetau run.

## Field maps (step 2)

Unchanged settings (chosen on Kostanay 2025).

| season | field share | median field | field area in regions over 500 ha | regions over 1,000 ha |
|---|---|---|---|---|
| 2023 | 61% | 100 ha | 37% | 3 (1,562, 1,340, 1,142 ha) |
| 2024 | 76% | 60 ha | 21% | 2 (1,269, 1,221 ha) |
| 2025 | 70% | 92 ha | 12% | 0 |
| 2026 | 74% | 84 ha | 14% | 1 (1,054 ha) |

Kostanay after the field-lines change: 4-6%, none over 1,000 ha. So in Kokshetau, especially in 2023 and 2024, step 2
still joins neighbouring fields, as Kostanay did before the field-lines change. Fixing it means retuning, which this
run does not do; it is a decision for the owner.

Boundary stability (share of one season's boundary pixels within 20 m of another's; chance 9-11%): 2025 -> 2024 73%,
2024 -> 2025 72%, 2025 -> 2023 62%, 2023 -> 2025 73%. Kostanay: 57-71%.

The Kostanay boxes that FieldCheck reads by eye do not apply here. Instead, 20 fields per season were picked by a
fixed rule (`FieldStudy pick`: 16 crop fields of 30-400 ha and 4 fallow controls, seeded by the year).

## Harvest dates by eye (step 3)

For 2025 and 2026, each picked field's clear photos from 15 July, cropped around the field with its outline
(`FieldStudy strips`), read by eye before any rule result was seen: `experiment/kokshetau/photo-readings-2025.csv`
and `-2026.csv`. These are my readings, not ground truth.

| | 2025 | 2026 |
|---|---|---|
| cut seen, with a window | 14 | 8 |
| unclear | 2 | 5 |
| no harvest seen (controls and fallow) | 4 | 6 |
| part natural part field | 0 | 1 |

## The frozen rule v1 (step 4)

`Analysis.swirJump` unchanged: the biggest post-ripe rise in B11 of at least 3.5 points, medians of three dates per
side, on dates at least 85% clear. One input differs from the Kostanay experiment: a field's reading is its interior
median from step 3, not a 60-pixel box. "With Landsat" adds Landsat rows put on the Sentinel-2 scale by the fixed line
of the Landsat fit (fitted on Kokshetau 2025), on dates Sentinel-2 has no row for.

| | Sentinel-2 alone | with Landsat |
|---|---|---|
| 2025: cut dated within one photo (inside, or one photo early or late) | 10 of 14 | 9 of 14 |
| 2026: cut dated within one photo | 5 of 8 | 5 of 8 |
| **both seasons** | **15 of 22** | **14 of 22** |
| fired on a field with no harvest | 2 of 10 | 1 of 10 |
| typical length of the dated step, when inside the window | 15 days | 6 days |

Kostanay, blind rounds 2-4: 17 of 29 within one photo, no false fires on 10 uncut boxes.

## What this means

- **The frozen rule carries over to a new area:** 15 of 22 (68%) against 59% in Kostanay, with no retuning.
  Small numbers; the readings are mine.
- **Landsat makes the dates tighter, not more often right.** When the rule lands in the right window, the
  date it gives spans 6 days instead of 15, because Landsat adds photos in between. It finds the same cuts.
- **The misses look like the Kostanay ones:** gradual cuts (2026-F0166, F0257 brighten only 2.4 to 2.8 points),
  late tillage winning over the cut (2026-F0079, 2025-F0309), and a jump before the reading's window (2025-F0206).
- **Unclear fields fire often** (6 of 7). Either the rule sees cuts I could not, or it fires on ripening drift; only
  real dates can tell.
