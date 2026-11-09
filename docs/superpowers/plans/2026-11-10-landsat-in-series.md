# Landsat in the series: plan (for approval, not built)

10 November 2026. Step 6 of the Kokshetau run. Written because steps 1 and 4 showed Landsat helps: with the fixed line
it is about as consistent as Sentinel-2 itself, and it narrows dated harvest windows from about 15 days to 6.

## Goal

The `series` command also writes Landsat rows for each field, on the Sentinel-2 scale, so step 4 (harvest dating)
sees every clear photo from both satellites.

## What changes

1. **pipeline: Landsat scenes.** A Planetary Computer STAC search for `landsat-c2-l2` (Landsat 8 and 9 only) over the
   stack's area and season, with an anonymous SAS token per collection. Code exists in the experiment
   (`LandsatCheck`, `PlanetaryToken`, `LandsatGrid`, `QaPixel`); it moves to `pipeline` with its tests.
2. **core: Landsat field reading.** Per field and Landsat date: interior pixels (20 m shrink) on the Landsat 30 m grid,
   clear by QA_PIXEL and the snow test, at least 60% clear, medians of NDVI, B11, B12, NIR, blue; same as step 3.
3. **core: the adjustment.** `BandFit` lines per measure, stored in a small file next to the stack
   (`landsat-fit.csv`) and fitted by a new `fit` command from same-day pairs of a chosen season. Default: the
   Kokshetau 2025 fit. No per-photo correction (tested and rejected).
4. **series output.** A `satellite` column (`S2`, `LC08`, `LC09`); Landsat rows carry adjusted values; texture columns
   stay blank on Landsat rows (30 m is too coarse for combine tracks). A Landsat date that falls on a Sentinel-2 date
   is kept as its own row, so later steps can choose.
5. **summary.** Landsat dates used and skipped, the fit used, and the harvest-window gap with and without Landsat.

## Tests first

- Landsat interior pixels from a synthetic label grid and Landsat grid that do not line up.
- A field 59% clear gets no values; 60% does.
- The fit file round-trips; values are adjusted with it; a missing fit is an error, not a silent pass-through.
- Series rows are ordered by date then satellite; Sentinel-2 rows are unchanged by adding Landsat.

## Not in this step

Harvest dating itself; per-photo correction; Landsat 7; HLS.

## Cost

Network about 6 minutes per season per area (as LandsatCheck); memory within 1 GB.
