# Cut dating in the pipeline, then a whole district

Status: written 2026-11-10. The owner chose the direction ("late photo check, covering big districts, being sure by
volume tests"; dealer on hold; checking dates against a farm's records comes later) and handed the details to me
("proceed as you think is necessary"). Every choice below not quoted from the owner is mine, open to change.

## Intent

The cut-dating rule works well enough to find a late harvester (holder A, 2024, confirmed by eye), but it lives in
the throwaway experiment, it is fooled by snow on late photos, and it has only been looked at on 22 hand-read
fields in one 20 x 20 km square. This work makes dating a real pipeline step, makes it safe against late photos,
and runs it over a whole district so it is judged on thousands of fields instead of 22.

"Being sure by volume" (owner) means: no single field is proven, but the dating is checked for behaviour that must
hold everywhere, on every field of every square and season, and the program is shown to cope with a district.

## Goals

1. `harvest` command: per field of a season, a cut window and a status, from the series (Sentinel-2 + Landsat).
2. A late-photo screen, fixed in advance, so snow, frost and haze cannot fake a cut.
3. Run steps season -> fields -> landsat -> series -> harvest over every square of one district, resumably, within
   1 GB of memory, and join the results into district tables and maps.
4. Volume tests that run on every output and are reported on the results page.

## Non-goals

- Dealer demo, lead list, ranking (owner: on hold).
- Checking dates against farm records (owner: later).
- Linking district fields to land users. Reading the cadastral map by hand does not scale, and bulk use needs the
  map's terms and legal advice (PLAN.md risk). The district stays nameless; the demo band keeps its names.
- Retuning the dating rule or the field maps on the data used to judge them.
- PostGIS, API, dashboard (Phase 4).

## Anti-goals

- A date shown without its window and status.
- A screen tuned until the 2024 Kokshetau snow date disappears and then scored on that same date. The screen's
  thresholds are set from what snow and haze do physically, written here before any volume run, and judged mostly on
  the Kostanay seasons and the new district.
- Silent loss: every screened date, skipped field and failed square is listed with its reason.

## Step 1: `harvest` (core + cli), branch `harvest-dating`

### Dating rule (frozen rule v1, unchanged)

On a field's clear readings (clear share at least 0.85, values present), Sentinel-2 rows plus Landsat rows on dates
Sentinel-2 lacks: from the first August-or-later reading with NDVI below 0.4, find the split with the biggest rise
in B11 (median of up to 3 readings each side, at least 2 after); a rise of at least 3.5 reflectance points dates the
cut between the readings either side of the split.

### Late-photo screen (new, fixed here)

Seen on Kokshetau 2024-10-16: thin snow on half the fields. Snow is bright in visible light and dark in B11, so a
one-date dip in B11 followed by recovery moved the three-reading medians and faked a rise. The scene mask counted
those fields clear.

- **Snowy readings out.** A field's reading with snow share above 0 is not used.
- **Snowy dates out.** A date where at least 25% of the area's fields with a reading have snow share above 0 is not
  used for any field (thin snow the mask misses on the rest).
- **Area-wide flashes out.** A date whose median blue reflectance over all fields differs from both the previous
  and the next usable date by at least 0.02, in the same direction, is not used (frost, haze, low sun). A cut
  changes one field, never the whole area at once. Checked before any dating run (blue only, no cut dates looked
  at): the area's median blue typically moves 0.007-0.012 between dates; a two-sided 0.02 flash marks 0 to 4 dates
  per season across both squares' 8 seasons, including Kokshetau 2024-10-16, and is the only date the snow test
  marks.

Each screened date is listed with its reason in the summary.

### Statuses

- `dated`: cut window of at most 10 days between the two readings.
- `cut in a cloud gap`: a cut found, but the window is longer than 10 days.
- `no cut found`: ripe readings exist, no rise reaches 3.5.
- `unclear`: fewer than 5 usable readings from 1 August, or never ripe.
- Regions that step 2 labelled other than `field` are not dated (`not a field`).

### Outputs

`harvest-<year>.csv` (field, land, area_ha, status, cut_from, cut_to, cut_middle, window_days, rise, readings),
`summary-<year>.txt` (counts and area by status, screened dates, halfway and 10/90% dates of dated area), a PNG map
coloured by cut week (grey for no cut found, hatched or pale for cloud gap, white for unclear) and a GeoTIFF of the
cut's middle day of year (0 where none).

## Step 2: the district, branch `district-run`

- **District:** Tayynsha district, North Kazakhstan oblast (holds the Kokshetau square and the demo band). Boundary
  from OpenStreetMap (free, no account), stored in the repo as GeoJSON with its source and date.
- **Squares:** a grid of squares of about 20 x 20 km (0.30 deg x 0.18 deg) covering the district, overlapping their
  neighbours by about 1 km; keep squares that touch the district. Each square runs the existing commands unchanged
  (the season command already picks the Sentinel-2 tile; a square on a tile edge takes the tile holding its
  centre).
- **Runner:** `district plan` writes the squares; `district run` processes them one at a time, one season at a time,
  resumably (a square-season is done when its harvest summary exists), logs time, peak memory and disk per
  square, and never runs two copies (a lock file).
- **Joining:** every field keeps its square id. A field touching its square's outer edge is marked `cut by edge`;
  in the overlap, the copy whose centroid lies in the square's core wins. District tables: fields, harvest, per
  square summary; district PNG at reduced resolution.
- **Seasons:** 2025 and 2026 first (raw stacks about 1.5 GB per square-season; about 30 squares need about 90 GB of
  the 318 GB free). Raw stacks are kept (re-downloadable, but kept for re-runs).

## Step 3: volume tests (with steps 1 and 2)

Checks that must hold on every square and season, reported per square:

1. **Fallow stays uncut:** share of `fallow` regions the rule would date (run on them for the test only).
   Expected low; Kokshetau readings had 1-2 false fires in 10 controls.
2. **Two satellites agree:** the cut window from Sentinel-2 alone against the window with Landsat: share of fields
   whose windows overlap or touch. Disagreement flags fragile dates.
3. **Overlap agrees:** fields dated in two neighbouring squares' overlap: share with matching windows.
4. **Dates are plausible:** no cut before the field ripened or after 31 October; share of cuts per week, and share
   of dated area that falls on dates right after a cloud gap (bunching).
5. **Screen does no harm:** the 22 hand-read Kokshetau cuts and holder A's 2024 readings score at least as well
   with the screen as without; screened dates per season listed.
6. **Seasons agree in shape:** each square's halfway date against the district's, per season.
7. **The program copes:** time, peak memory (under 1 GB) and disk per square; failures and retries.

## Constraints

Java 21 in the existing modules, test-first with small synthetic inputs, no new heavy dependencies, free data, no
accounts, memory under 1 GB, never touch `data/season-*` or `experiment-output/`, one branch per step, merge to
`main` only with the owner's OK, each step ends on the results page
(https://claude.ai/artifact/F1VMc3K459nUk52Zfc1EAU or a new page per step), no land-user names on any page.

## Left to the builder

Class and file names, how the map is drawn, the order of work inside each step.
