# Test area comparison: results

9 November 2026, branch `area-compare` (from `main`).

## Why

The owner asked whether our Kostanay square (62.40-62.70 E, 53.45-53.63 N, tile T41UMV) is the best example of
Kazakh farmland. It was picked quickly in phase 1, not by criteria. The owner chose to compare candidates first, then,
part way through, settled the direction: work in the north, near Kokshetau, on wheat first.

## How

Three passes, each narrower than the last. All free data, no accounts.

1. **Land** (`AreaScreen`). The Kostanay, Akmola and North Kazakhstan oblasts (geoBoundaries 2017 outlines, ODbL,
   `data/areas/kaz-adm1.geojson`) are cut into squares of 0.30 x 0.20 degrees (about 20 x 20 km), aligned so each
   lies inside one 3 x 3 degree WorldCover tile. A square counts when the three oblasts together cover it and
   belongs to the oblast holding its centre (the first run required one oblast to cover it, which emptied the
   Kokshetau area, near the Akmola / North Kazakhstan border; first run kept in `data/areas/first-screen/`). Each
   square's shares come from ESA WorldCover 2021 (public S3), read from its 80 m overview (level 3).
2. **Clouds at harvest** (`AreaScreen`). For our square and the three best squares per oblast at least 60 km apart:
   every Sentinel-2 L2A scene of 1 August to 30 September 2023-2025 on the one tile that most often covers the square,
   with its scene classification read from the 80 m overview (level 2). Per pixel, the longest stretch without a clear
   photo (SCL 4, 5 or 6), counting the window's ends (the `Gaps` rule of the Landsat and HLS tests); the median pixel's
   is the square's number. Named squares can be measured on their own (`AreaScreen data NAME=w,s,e,n ...`).
3. **Our own field map** (`AreaCheck`). For the promising squares, the 2025 season, steps 1 to 3 unchanged
   (`season`, `fields`, `series`), then: share of the square labelled field and fallow, field sizes, and the
   typical field's longest harvest gap as in step 3 (a date counts when the field has a value). Outputs in
   `data/areas/<west>_<south>/`.

To read only the overviews, `CogReader` now opens a chosen resolution level (the full image's grid scaled to the
overview's width) and reads files that name a geographic coordinate system (WorldCover is lon/lat). Both test-first.

## Results

### Land

WorldCover: our square is 78% cropland, 17% grassland, 5% water and wetland. 98 of 842 squares have as much
cropland or more; medians by oblast: Kostanay 27%, Akmola 46%, North Kazakhstan 54%; best squares 96-98%.

### Clouds at harvest

Typical pixel's longest wait for a clear photo, 1 August - 30 September (days):

| square | where | cropland | 2025 | 2024 | 2023 | sum |
|---|---|---|---|---|---|---|
| Ours | 62.40-62.70 E, 53.45-53.63 N | 78% | 22 | 17 | 15 | 54 |
| K1 | 63.00-63.30 E, 51.60-51.80 N | 97% | 10 | 10 | 20 | 40 |
| A2 | 65.70-66.00 E, 51.20-51.40 N | 96% | 15 | 15 | 12 | 42 |
| KB | 65.70-66.00 E, 52.80-53.00 N | 89% | 14 | 17 | 12 | 43 |
| AS | 70.50-70.80 E, 51.00-51.20 N | 96% | 13 | 18 | 15 | 46 |
| KK3 | 68.70-69.00 E, 53.80-54.00 N | 80% | 13 | 18 | 18 | 49 |
| KK4 | 69.90-70.20 E, 53.60-53.80 N | 71% | 15 | 15 | 20 | 50 |
| KK1 | 69.30-69.60 E, 53.80-54.00 N | 83% | 12 | 22 | 23 | 57 |
| KK5 | 68.70-69.00 E, 53.00-53.20 N | 65% | 20 | 26 | 15 | 61 |
| KK2 | 69.30-69.60 E, 53.60-53.80 N | 80% | 17 | 25 | 25 | 67 |
| Barayev | 70.87-71.17 E, 51.55-51.75 N | 61% | 12 | 27 | 15 | 54 |

KB, AS and A2 were measured in the first run (named K3, A3 and A1 there); the rest of the second run is in
`data/areas/clouds.csv`, the KK squares in `data/areas/clouds-extra.csv`. The per-pixel numbers are kinder than step
3's per-field ones (ours: 22, 17, 15 against 28, 25, 15 days), because a field needs 60% of its interior clear and
the stack keeps one photo per date. Compare areas with one measure only.

### Our own field map, 2025

| square | field | fallow | fields | median field | largest | field area in fields over 100 ha | longest harvest gap, typical field |
|---|---|---|---|---|---|---|---|
| Ours | 60% | 2% | 369 | 28 ha | 575 ha | 65% | 28 days |
| KK3 | 70% | 5% | 227 | 92 ha | 702 ha | 85% | 15 days |
| KK1 | 62% | 2% | 469 | 29 ha | 499 ha | 55% | 13 days |
| KK4 | 52% | 1% | 282 | 35 ha | 427 ha | 75% | 17 days |
| KB | 82% | 1% | 629 | 24 ha | 734 ha | 64% | 15 days |
| AS | 80% | 8% | 178 | 70 ha | 2341 ha | 92% | 15 days |
| K1 | 14% | 53% | 151 | 19 ha | 434 ha | 52% | 10 days |
| A2 | 22% | 63% | 84 | 58 ha | 976 ha | 84% | 16 days |

## What this means

- **WorldCover cropland is not land sown this year.** K1 (97% cropland) and A2 (96%) are mostly fallow on our map,
  and K1's July photo agrees: large square fields, brown in mid-summer, green only in hollows. Any candidate needs our
  own map.
- **Ours is good on land but cloudy at harvest.** Every new square had a far shorter 2025 harvest gap (10 to 17 days
  against 28).
- **KK3 is the cleanest farmland measured:** green rectangular fields in July, few wet hollows, one lake and three
  villages, fields about three times the size of ours, and outlines along the real field edges. AS has big fields too,
  but its 2,341 ha largest region suggests joined fields (not checked).
- **Recommendation:** keep our square as the area the settings are chosen on, and add KK3 (68.70-69.00 E,
  53.80-54.00 N, North Kazakhstan oblast, about 75 km north-west of Kokshetau) as the second area, never used for
  tuning, starting with its 2023-2025 seasons.
- **Ground truth** is still the gap. The Barayev grain centre (Shortandy, about 51 38' N, 71 01' E) harvests its
  plots with yield-mapping combines; only the owner can ask whether such records could be shared.
