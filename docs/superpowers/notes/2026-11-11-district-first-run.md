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

## 2025 run and the held-out test of the crop gate (2026-11-11)

- 2025: the same 10 core squares, all steps exit 0 (owner's terminal run, `run-2025.csv`). Joined: 2,158 fields,
  225,321 ha; dated 63% of the area; overlap check 81 of 83; halfway dates 11-21 September.
- 2026 rejoined with all 14 squares (the four partly covered ones added): 2,533 fields; overlap check 85 of 95.
- Crop gate (commit a844774, frozen before any 2025 result), `tools/judge_gate_2025.sh`, output
  `data/tayynsha/gate-test-2025.txt`: bare fallow with a cut 14 of 44 -> 6 of 44; field regions turned "no crop seen"
  6 of 2,631. It passes on data it never saw. The joined tables still use the ungated program (as on `main`).

## Holders and the two-season lead list

Plot cards read in the owner's Chrome for three squares: E560N5960 (79% of field area), E520N5960 (78%), E540N5960
(76%). `tools/holders.py` -> `holders.csv`; `tools/lead_list.py ... 2026 2025` -> `lead-list.csv`. Names stay
in `data/`. Of 22 companies with a counted season, two are late in both seasons with sizable area (about 5,600 ha:
+5 and +10 days behind neighbours within 20 km; about 3,800 ha: +11 and +4); the largest (about 13,300 ha) is early in
both (-13, -15). Several companies are late in one season only (e.g. +21 then 0).
