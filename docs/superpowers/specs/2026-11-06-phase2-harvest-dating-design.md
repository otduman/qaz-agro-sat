# Phase 2 build: per-field harvest dates (draft for approval)

Status: draft, written 2026-11-06 while the owner was away. Nothing in it is built yet.
Evidence: the harvest-signal experiment on branch `harvest-signal-experiment`, results page
https://claude.ai/artifact/HTU548DNFfXB2v5jMcWSHW.

## Intent

Phase 2 of `PLAN.md` ends with a harvest-date map for one district and season, checked against
ground truth. The experiment showed what to build on, and that the dating rule itself isn't ready.
This document splits Phase 2 into the parts that are safe to build now and the part that still
needs evidence.

## What the experiment settled

- **Signal.** At the cut, a field usually turns paler in shortwave infrared (Sentinel-2 B11).
  NDVI marks ripening, not the cut. NDTI adds nothing. Radar barely separates cut from uncut fields
  and has no 2023 or 2024 coverage here.
- **The simple rule isn't good enough.** Rule v1 is the biggest SWIR rise after ripening. On
  data it had never seen (rounds 2 to 4), it dated the cut within one photo for only 17 of 29
  fields. It never fired on the 10 uncut boxes in those rounds.
- **A tuned rule did worse.** Rule v2 fixed the misses seen in rounds 1 to 3 and was committed
  before the 2023 data existed. On 2023 it got 5 of 13, against 7 of 13 for v1. Tuning on these
  boxes overfits.
- **Why it misses.** Some cuts darken a field instead of brightening it. Late crops never drop
  below the NDVI ripe gate. Fields cut before any clear ripe photo can't be seen. Snow, wet
  ground and haze slip past the cloud mask. Swathing brightens slowly.
- **Texture is the lead to follow.** Reading the photos by eye, the clearest sign of a cut was
  parallel lines appearing: combine tracks and swath rows.
- **Fields move.** 7 of 35 boxes straddled a field boundary in some year. Boundaries must be found
  per season.

## Goals

1. Per season, find field boundaries automatically for the test area.
2. Build each field's clear-date time series: NDVI, B11, visible brightness, clear share, and a
   texture measure.
3. Date the cut per field with an uncertainty window and a status: dated, cut in a cloud gap, no
   cut found, or unclear. Use the best method the evidence supports at the time.
4. Write a harvest-date map (PNG and GeoTIFF) and a per-field CSV, the way Phase 1 writes NDVI.
5. Check it against ground truth before calling Phase 2 done.

## Non-goals

- PostGIS, an API or a dashboard. Those are Phase 4.
- Linking fields to farms. That's Phase 3.
- Radar gap filling. That's Phase 3 at the earliest, and only if a better radar test works.

## Anti-goals

- A harvest date shown without its uncertainty and status. A wrong confident date damages a
  dealer's trust more than "unclear" does.
- Tuning the dating method on boxes and then quoting its score on the same boxes.

## The how, in build order

Steps 1 to 3 are needed whatever dating method wins, so they can start now. Each step ends with
something to look at, and each is test-first in Java like Phase 1.

1. **Season series for an area** (`pipeline`). Fetch every usable Sentinel-2 date for an area and
   season, reusing the experiment's scene choice. Keep the needed bands as one stack on disk.
   CLI: `season --bbox ... --year 2025 --out ...`.
2. **Field boundaries** (`core`). Segment from a mid-season NDVI image plus a post-harvest image.
   Threshold, then edge detection, then connected components, dropping anything under about 5 ha.
   CLI `fields` writes polygons as GeoJSON and a preview PNG. Check: overlay on photos.
3. **Per-field series** (`core`). Median of each index over each field's clear pixels per date,
   skipping dates under 60% clear. Add a texture measure, such as local variance or directional
   edge strength in the 10 m bands. CLI writes one CSV per season.
4. **Harvest dating** (`core`). Not designed yet. It should be decided by a texture experiment
   scored blind on a season it hasn't seen, and ideally on ground truth.
5. **Map and CSV** (`cli`). `harvest` command writes the map and table.
6. **Validation.** Score against ground truth.

## Decided by me, open to change

- Java, in the existing modules, with no new heavy dependencies. Segmentation is written by hand
  on top of JTS, which is already a dependency.
- The test area stays Kostanay tile T41UMV, over 2023 to 2025, until a district with ground truth
  is chosen.
- The experiment module is deleted once steps 1 to 5 replace it.

## Open questions for the owner

1. **Ground truth.** Who can give real cut dates for 20 or more fields, even roughly? This is now
   the main blocker. Photo readings can't fairly judge a texture method, because the reader uses
   texture too.
2. **Order.** Should I build steps 1 to 3 now, try the texture experiment first, or both?
3. **District.** Is Kostanay still the area, or is there a district where ground truth is easier?
