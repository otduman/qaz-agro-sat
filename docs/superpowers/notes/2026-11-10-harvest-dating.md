# Cut dating in the pipeline: the `harvest` command and its first volume run

10 November 2026, branch `harvest-dating`. Design: `docs/superpowers/specs/2026-11-10-dating-and-district-design.md`.

## What was built

- `core.harvest`: `CutRule` (the frozen rule v1, unchanged), `LateScreen`, `HarvestDater` (statuses `dated`,
  `cut in a cloud gap` (window over 10 days), `no cut found`, `unclear` (under 5 usable readings from 1 August, or
  never ripe)).
- `pipeline.harvest`: `SeriesTable` reads a series folder; `Harvest` writes `harvest-<year>.csv`, a 16-bit
  day-of-year GeoTIFF (0 = none), a PNG (pale yellow early to deep blue late, paler for cloud gaps, grey no cut,
  white unclear) and `summary-<year>.txt` with the checks.
- `harvest --series S --fields F --out H`. 537 tests pass (`mvn clean verify`).

## The late-photo screen

Planned before any run (design doc): snow on at least 25% of the fields with a reading, and an area-wide flash in
blue (two-sided, 0.02). Added after the first volume run (ruling below): wet spells, one or two judged dates in a row
each at least 3.5 points of B11 (the cut rule's own threshold) below both the date before and the date after.

Ruling: wet-spell screen added after seeing Kokshetau 2023 - rain on 10-12 September darkened every field and
fallow region for a week and the recovery faked cuts on 11 of 12 fallow regions. Its threshold is the rule's own,
so nothing was tuned, but it was motivated by data it is now scored on; the district run is its fresh test. Cost if
wrong: real cuts inside a wet week are lost (Kokshetau 2023 went from 69 dated fields to 22).

Ruling: the fallow check counts only bare fallow (NDVI never above 0.3 from August). Kostanay 2023 "fallow" regions
greened to NDVI 0.5-0.8 in September (late crops or weeds) and browned by mid October, so a cut found there can be
real. Green fallow is reported on its own line.

## Volume run: 8 seasons, 2 squares

| season | dated | cloud gap | no cut found | unclear | half cut by | bare fallow "cut" | green fallow "cut" | Sentinel-2 alone agrees | screened |
|---|---|---|---|---|---|---|---|---|---|
| Kokshetau 2023 | 22 (2,557 ha) | 11 | 113 | 1 | 22 Sep | 1 of 2 | 1 of 10 | 9 of 10 | 4 |
| Kokshetau 2024 | 135 (16,163 ha) | 39 | 91 | 3 | 17 Sep | 1 of 7 | 1 of 3 | 139 of 144 | 2 |
| Kokshetau 2025 | 134 (19,038 ha) | 35 | 57 | 1 | 21 Sep | 0 of 11 | 2 of 5 | 118 of 141 | 2 |
| Kokshetau 2026 | 179 (22,119 ha) | 2 | 69 | 0 | 4 Sep | 0 of 5 | 0 of 1 | 137 of 154 | 2 |
| Kostanay 2023 | 93 (7,907 ha) | 46 | 151 | 11 | 22 Sep | **15 of 19** | 10 of 26 | 75 of 89 | 4 |
| Kostanay 2024 | 192 (15,074 ha) | 16 | 143 | 26 | 24 Sep | 3 of 10 | 3 of 8 | 130 of 181 | 2 |
| Kostanay 2025 | 136 (6,745 ha) | 99 | 134 | 0 | 21 Sep | 1 of 6 | 1 of 5 | 185 of 199 | 0 |
| Kostanay 2026 | 282 (17,537 ha) | 1 | 134 | 0 | 27 Aug | 0 of 7 | 1 of 7 | 214 of 262 | 0 |

- Bare fallow stays uncut in 7 seasons (0-3 false cuts, 7 of 60 regions in all). Kostanay 2023 fails: wet bare soil
  on a Sentinel-2 date next to a drier Landsat date a day later; only bare land darkens, so the area-wide screen
  cannot see it. That season's dates should not be used.
- Sentinel-2 alone and Sentinel-2 with Landsat agree on 72-97% of fields cut in both.
- Hand-read cuts (Kokshetau 2025-2026): 14 of 22 within one photo, as without the screen; false fires on fields with
  no harvest or part natural land 2 -> 0. Holder A's three false October 2024 dates are gone (now no cut found).
- 2023 is weak in both squares: few dated fields, many no cut found.

## Open

- A large share of fields gets no cut found (20-77% of field area). Gradual cuts and cuts hidden in gaps; the rule
  is unchanged on purpose.
- The adjusted Landsat B11 reads a few points above Sentinel-2 on wet or green bare land (Kostanay 2023); the fit
  was made on Kokshetau 2025.
