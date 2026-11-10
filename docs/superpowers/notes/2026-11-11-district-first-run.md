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
