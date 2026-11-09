# Landsat fit: results

10 November 2026, branch `landsat-fit` (`area-compare` with `hls-test` merged in). The owner chose to bring Landsat
in; this is the recommended option from the HLS test: ordinary Landsat (Collection 2, level 2, Planetary Computer,
no account) with our own adjustment.

## How

`LandsatCheck` (unchanged) read every field on Landsat 8 and 9 for Kokshetau (main area, `data/kokshetau`) 2023 to
2026 and for Kostanay (`data`) 2023 to 2025, on the current field maps. The Kostanay Landsat readings from the
Landsat test, made on the old field maps, are kept in `data/landsat-old-maps/`.

`LandsatFit` pairs the same field on Landsat and on our Sentinel-2 series at most a day apart and fits, per measure
(NDVI, B11, B12, NIR, blue), a line sentinel = slope x landsat + intercept by Theil-Sen (`BandFit`: median pairwise
slope, then median intercept, so a field cut between the two photos or a cloud edge doesn't move it). Fitted on
Kokshetau 2025 only, then applied unchanged to the six other seasons.

## Results

The line, fitted on Kokshetau 2025 (2,801 pairs):

| measure | slope | intercept |
|---|---|---|
| NDVI | 1.037 | -0.045 |
| B11 (SWIR 1.6) | 0.880 | +0.045 |
| B12 (SWIR 2.2) | 0.949 | +0.025 |
| NIR | 1.025 | -0.008 |
| blue | 0.801 | +0.016 |

Landsat minus Sentinel-2, median difference before -> after, and median absolute difference before -> after:

| season | NDVI | B11 | B12 | NDVI abs | B11 abs |
|---|---|---|---|---|---|
| Kokshetau 2025 (fitted) | +0.032 -> 0 | -0.015 -> 0 | -0.016 -> 0 | 0.033 -> 0.015 | 0.018 -> 0.014 |
| Kokshetau 2024 | +0.032 -> +0.003 | -0.033 -> -0.015 | -0.034 -> -0.018 | 0.034 -> 0.017 | 0.033 -> 0.017 |
| Kokshetau 2023 | +0.038 -> +0.008 | -0.013 -> +0.005 | -0.020 -> -0.004 | 0.038 -> 0.018 | 0.014 -> 0.011 |
| Kokshetau 2026 | +0.043 -> +0.016 | -0.023 -> -0.005 | -0.026 -> -0.009 | 0.044 -> 0.020 | 0.024 -> 0.012 |
| Kostanay 2025 | +0.039 -> +0.008 | -0.025 -> -0.007 | -0.021 -> -0.005 | 0.041 -> 0.021 | 0.026 -> 0.012 |
| Kostanay 2024 | +0.030 -> -0.003 | -0.015 -> +0.003 | -0.018 -> -0.002 | 0.032 -> 0.015 | 0.016 -> 0.009 |
| Kostanay 2023 | +0.034 -> +0.003 | -0.018 -> +0.003 | -0.020 -> -0.003 | 0.035 -> 0.021 | 0.019 -> 0.015 |

(1,995 to 5,150 pairs per season; full table in `data/kokshetau/landsat-fit.md`.)

Longest harvest-window gap (1 August to 30 September), typical field, Sentinel-2 alone -> with Landsat:

| | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|
| Kokshetau | 18 -> 18 | 20 -> 15 | 15 -> 13 | 10 -> 8 |
| Kostanay | 15 -> 15 | 25 -> 15 | 28 -> 16 | - |

## What this means

- **The fixed line works across seasons and areas.** Fitted on one Kokshetau season, it brings the average
  difference in every other season, and in Kostanay, which it never saw, from 0.013-0.043 to within about 0.01,
  except Kokshetau 2024 SWIR (-0.015, -0.018) and Kokshetau 2026 NDVI (+0.016). The typical difference per reading
  roughly halves.
- **What's left is mostly photo-to-photo.** Per Landsat date, the B11 difference (before the fit) ranges from about
  -0.06 to +0.025 (Kokshetau 2024 to 2026), wider than the spread between seasons. Each photo carries its own offset,
  most likely from haze and the two archives' different atmospheric corrections. Kokshetau 2024 is simply a season
  whose photos happened to sit low.
- **Why that matters for harvest dating.** The cut shows as a B11 rise of 0.05 to 0.08, and the frozen rule looks for
  a rise of at least 0.035. A Landsat photo whose own offset is 0.02 to 0.03 off could fake or hide part of a jump.
- **The fix to test next:** correct each Landsat photo by its own offset, measured on the fields that did not change
  between the Sentinel-2 photos either side of it, and judge it on held-out same-day pairs.
- **Landsat helps Kokshetau less than Kostanay**, because Kokshetau's harvest skies are already better: 2 to 5 days
  off the typical field's longest gap in three of four seasons.
