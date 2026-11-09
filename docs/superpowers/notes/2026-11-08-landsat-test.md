# Landsat test: results

8 November 2026, plan `docs/superpowers/plans/2026-11-08-landsat-test.md`. Free data from Microsoft Planetary
Computer (collection `landsat-c2-l2`, anonymous access tokens, no account). Each season took about 3 minutes and
stayed under 1 GB of memory. Run:

    java -Xmx1g -cp experiment\target\harvest-signal.jar kz.kazagrosat.experiment.LandsatCheck data

Each step 3 field was read on every Landsat 8 and 9 date exactly as step 3 reads Sentinel-2: 20 m inside its edge,
values only when at least 60% of that is clear (by Landsat's own cloud flags plus our snow test), medians. The
readings are in `data/landsat-<year>/landsat-series-<year>.csv`.

## What was found

| season | Landsat scenes (Landsat 7 left out) | satellite-days | at least 1% clear over the area |
|---|---|---|---|
| 2025 | 65 (0) | 42 | 23 |
| 2024 | 67 (0) | 45 | 30 |
| 2023 | 96 (28) | 45 | 31 |

The area lies where two Landsat paths overlap (161 and 162), so Landsat 8 and 9 together pass about every 4 days.

## Does it fill the harvest-window gaps?

Harvest window 1 August to 30 September. For each field, the longest stretch without a clear date, and the
number of fields never more than 15 days without one (medians across fields):

| season | clear dates per field, Sentinel-2 / Landsat | longest gap, Sentinel-2 alone | with Landsat | fields never over 15 days without a clear date, alone | with Landsat |
|---|---|---|---|---|---|
| 2025 | 5 / 3 | 28 days | 16 days | 0 of 326 | 154 of 326 |
| 2024 | 9 / 7 | 25 days | 15 days | 28 of 293 | 168 of 293 |
| 2023 | 10 / 6 | 15 days | 15 days | 222 of 248 | 248 of 248 |

In the two cloudy seasons Landsat cuts the typical longest gap by about 10 days. In 2023, already the clearest
season, it brings every field within 15 days.

## Do the two satellites read a field the same way?

Field medians on dates at most one day apart, Landsat minus Sentinel-2:

| band | 2025 | 2024 | 2023 |
|---|---|---|---|
| NDVI | +0.039 (2,275 pairs) | +0.029 (2,887) | +0.034 (3,733) |
| SWIR 1.6 µm (B11) | -0.025 | -0.015 | -0.018 |
| SWIR 2.2 µm (B12) | -0.021 | -0.018 | -0.020 |
| near-infrared | -0.004 | +0.002 | +0.003 |
| blue | -0.013 | -0.006 | -0.007 |

The typical absolute difference is about the same size as the bias, so the differences are mostly a steady
offset, not scatter. Landsat reads greenness about 0.03 to 0.04 higher and shortwave infrared about 0.02 lower,
in all three seasons. That is the known difference between the two sensors' bands, which NASA's Harmonized
Landsat Sentinel-2 product removes with a fixed line per band. Fitted lines here (Landsat = slope × Sentinel-2
+ intercept) have NDVI slopes 0.85 to 0.92 and SWIR slopes 0.71 to 0.91.

The cut signal the experiment relies on is a jump in shortwave infrared. Mixing the two satellites without
adjusting would put a steady 0.02 step into the curve every time it switches between them, which a cut
detector could take for part of a cut.

## What this means

- Landsat is worth having: it is free, no account is needed, and it fills about 10 days of the worst gap in
  cloudy seasons.
- It needs a per-band adjustment before mixing. Either fit our own lines on the pairs above (thousands of them
  per season, steady across seasons), or use NASA's published HLS coefficients. That's a decision for the owner,
  and the work would be a product step with its own plan: Landsat in the season stack and the series.
- At 30 m, Landsat can't show combine lines, so texture would stay Sentinel-2 only.

Nothing here was tuned; the 60% clear share, 20 m interior and the snow test are step 3's.
