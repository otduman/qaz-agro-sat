# Landsat in the series: results

10 November 2026, branch `landsat-series`. Plan: `docs/superpowers/plans/2026-11-10-landsat-in-series.md`.

## What was built

- `landsat --stack S --out L`: the Landsat 8 and 9 scenes (Collection 2 level 2, Planetary Computer, anonymous token)
  of a stack's area and season, each as its 30 m window over the area; resumable; Landsat 7 and scenes in another UTM
  zone left out; a scene with no clear pixel over the area keeps only its quality layer.
- `series ... --landsat L [--landsat-fit F]`: a row per field per Landsat satellite-day, read as the fit was made
  (10 m interior pixels take the Landsat pixel their centre falls in; QA clear and no snow; 60% of the interior), put on
  the Sentinel-2 scale by the built-in Kokshetau 2025 lines or a fit file. New last CSV column `satellite`
  (`S2`, `LC08`, `LC09`); Landsat rows leave brightness and texture blank. The summary gives the typical field's longest
  1 August - 30 September wait for a clear reading, with and without Landsat.
- 507 tests pass (`mvn clean verify`).

## Real runs

Kokshetau (`data/kokshetau`) and Kostanay (`data`), 2023-2026, into `landsat-scenes-Y` and `series-landsat-Y`
(the existing series are untouched).

Typical field's longest wait for a clear reading, 1 August - 30 September, Sentinel-2 alone -> with Landsat:

| | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|
| Kokshetau | 18 -> 18 days | 20 -> 15 | 15 -> 13 | 10 -> 8 |
| Kostanay | 15 -> 15 | 25 -> 15 | 28 -> 16 | 12 -> 8 |

Landsat satellite-days per season: 30 to 39.

## Check against the Landsat experiment

The series' Landsat rows were compared with the experiment's readings (`landsat-Y/landsat-series-Y.csv`) put through
the same lines (`data/kokshetau/check_landsat_series.py`):

- every experiment row is in the series; the typical difference is about 0.0001 (B11 at most 0.013, where two scenes of
  a day overlap and are joined in another order);
- clear shares agree everywhere but one row (0.012 apart);
- the series has 240 to 1,190 more rows per season: days the experiment skipped as under 1% clear over the whole area;
- NDVI differs on a handful of very dark fields (water, shadow): the series uses the product's NDVI (negative
  reflectance counts as zero, NDVI stays in [-1, 1]), the experiment a raw ratio that gave values such as -2.

The first check found a fault, fixed test-first: the downloader kept a scene's bands only when 1% of the area was
clear, but 1% of the square is about 440 ha, room for whole fields, so a few dozen field readings were lost (clear
share up to 0.86 too low). A scene now keeps its bands when any of the area is clear.

## On the way

Two copies of the run kept going after session restarts and wrote into the same folders at once; a few file renames
clashed. Both were stopped and one clean pass rebuilt everything. Long runs are not stopped by a session restart.
