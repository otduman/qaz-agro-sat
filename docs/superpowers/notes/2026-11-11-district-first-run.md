# Tayynsha district: first run, 2026

Plan: 51 squares (`district plan`, boundary `docs/districts/tayynsha.geojson`). Run: the 14 squares fully inside the
district, in the owner's own terminal (`district run --squares ...`). Join: `district join`.

## What ran

- 10 of 14 squares finished, 6-16 minutes of download each plus about 5 minutes of Landsat, 1.0-2.2 GB each.
- 4 failed in the season step (E600N5940, E600N5960, E560N5980, E580N5980): "No single Sentinel-2 tile covers the
  whole area on any date". The squares sit inside a tile's grid, but an orbit's swath edge cuts that tile on every
  date, so no single photo covers the square. Fix for later: join the scenes of one day (two orbits or two tiles).

## Results (10 squares, `data/tayynsha/district-summary-2026.txt`)

- 2,010 fields, 245,694 ha, each once. Dated 1,049 (130,939 ha, 53%); cut in a cloud gap 81; no cut found 872
  (103,712 ha, 42%); unclear 8. 114 fields touch their square's edge.
- Overlap check (fresh data): fields seen by two neighbouring squares got the same cut window in 57 of 64.
- Sentinel-2 alone agrees with Sentinel-2 + Landsat on 1,046 of 1,242 fields cut in both (84%).
- Bare fallow with a cut found: 10 of 49 (20%), higher than on the 8 old seasons (7 of 60). This is the first fresh
  test of the wet-spell screen; false cuts on bare land remain the weak point.
- Halfway dates by square: 12 August to 2 September.

## The four failed squares (branch `partial-scenes`)

Two Sentinel-2 passes never cover one place on the same day here, so "join one day's scenes" was the wrong idea:
those squares sit across a pass edge, and every photo sees only part of them. Fixes, test-first:

- `SeasonScenes.choose`: when no scene of any tile covers the whole area, keep scenes that see at least 10% of it,
  on the tile whose scenes see the most of it over the season; the rest of each photo reads as no data.
- `PixelGrid.windowClippedTo`: the square's longitude-latitude box poked 17 m past tile T42UXE's west edge; the
  stack now clips to the tile when the overshoot is at most 1 km.

E600N5940, 2026: 35 dates on T42UXE (partly covering), 134 field regions, 46 dated, bare fallow 0 of 3,
Sentinel-2 alone agrees 29 of 43. E600N5960, E560N5980 and E580N5980 rerun the same way. Their `harvest-2026` is
made with the program before the crop gate, like the other ten; `harvest-2026-gate` holds the gated version.

## Why "no cut found" (2026 district, diagnosis only)

Of the 117,139 ha with no cut found, 63% had no gap over 15 days after ripening: the cut was not hidden by cloud,
the B11 rise was small (biggest rise 1-3 points on 67% of that area; 3-3.5 on 16%). Gradual cutting, swathing.
Texture (`line_strength`) is a weak sign on its own: across dated cuts it changes by +0.03 at the median (+0.32 at
the 90th percentile) against 0.00 (+0.12) on other date pairs. Not used; a texture rule needs its own test.
