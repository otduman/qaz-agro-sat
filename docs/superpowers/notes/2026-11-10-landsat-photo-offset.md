# Per-photo Landsat correction: results

10 November 2026, branch `landsat-photo-offset`. Step 1 of the Kokshetau run
(`docs/superpowers/plans/2026-11-10-kokshetau-run.md`).

## Verdict

**It missed its pass mark, and should not be used.** The pass mark, fixed beforehand, was a typical photo's leftover
B11 offset under 0.01. The per-photo correction gave 0.013 on the season it was designed on and made things worse in
five of the seven seasons. It is not patched further, because the cause is not in Landsat.

## What was tried

After the fixed line of the Landsat fit, each Landsat photo was shifted by its own offset: the median, over fields
whose Sentinel-2 value changed by at most 0.02 (NDVI 0.05) between the Sentinel-2 photos either side, of the Landsat
reading minus the value a straight line between those two photos gives (`PhotoOffset`). Judged by hiding the
Sentinel-2 photos within a day of the Landsat photo and comparing the corrected reading with the hidden one
(`LandsatPhotoFit`). Tolerances 0.01 to 0.03 and brackets of 12 or 20 days gave the same picture on Kokshetau 2025.

## Results (B11)

Typical photo's leftover offset (median over photos of the absolute per-photo median difference):

| season | photos | fixed line only | with photo offset | worst photo: line only | with photo offset |
|---|---|---|---|---|---|
| Kokshetau 2025 (designed on) | 18 | 0.017 | 0.013 | 0.044 | 0.042 |
| Kokshetau 2024 | 14 | 0.016 | 0.012 | 0.045 | 0.061 |
| Kokshetau 2023 | 25 | 0.010 | 0.018 | 0.046 | 0.071 |
| Kokshetau 2026 | 22 | 0.008 | 0.012 | 0.049 | 0.043 |
| Kostanay 2025 | 11 | 0.008 | 0.013 | 0.019 | 0.033 |
| Kostanay 2024 | 12 | 0.006 | 0.009 | 0.015 | 0.028 |
| Kostanay 2023 | 17 | 0.015 | 0.020 | 0.028 | 0.040 |

Full table: `data/kokshetau/landsat-photo-fit.md`.

## Why: Sentinel-2 wobbles just as much

The same measurement on Sentinel-2 alone (each Sentinel-2 photo against the straight line between its own
neighbours, on unchanged fields) gives:

| season | typical Sentinel-2 photo's own B11 offset | 9 in 10 photos within | worst |
|---|---|---|---|
| Kokshetau 2023 | 0.019 | 0.028 | 0.039 |
| Kokshetau 2024 | 0.015 | 0.030 | 0.054 |
| Kokshetau 2025 | 0.015 | 0.041 | 0.046 |
| Kokshetau 2026 | 0.012 | 0.029 | 0.048 |
| Kostanay 2023 | 0.018 | 0.039 | 0.054 |
| Kostanay 2024 | 0.011 | 0.025 | 0.027 |
| Kostanay 2025 | 0.011 | 0.030 | 0.050 |

So a field's B11 moves by 0.01 to 0.02 from one clear photo to the next on the same satellite, and by up to 0.05,
with no cut: haze, damp soil after rain, sun angle. The "photo-to-photo offset" seen in the Landsat fit is mostly
this, on both satellites, not a Landsat defect. A Landsat photo cannot be pulled onto a Sentinel-2 photo more closely
than Sentinel-2 photos agree with each other, and correcting it towards its neighbours only adds their wobble.

## What this means

- **Landsat with the fixed line is about as consistent as Sentinel-2 itself** (leftover 0.006 to 0.017 against
  Sentinel-2's own 0.011 to 0.019). It can go into the series as it is.
- **The wobble is a problem for the harvest rule on either satellite.** The frozen rule looks for a rise of 0.035;
  one photo in ten moves a field by 0.03 or more with no cut. The rule's use of medians of three dates per side
  already softens this.
- **A lead for a later rule, not tested here:** measure each field against the other fields on the same photo
  (field minus the photo's median field). Whatever shifts a whole photo cancels, on both satellites.
