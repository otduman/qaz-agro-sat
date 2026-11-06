# Phase 2 build: per-field harvest dates (draft for approval)

Status: draft, written 2026-11-06 while the owner was away. Nothing in it is built yet.
Evidence: the harvest-signal experiment on branch `harvest-signal-experiment`, results page
https://claude.ai/artifact/HTU548DNFfXB2v5jMcWSHW.

## Intent

Phase 2 of `PLAN.md` ends with a harvest-date map for one district and season, checked against
ground truth. The experiment showed which signal to build on and where a naive rule fails. This
document turns that into a build plan.

## What the experiment settled

- **Signal.** The cut shows as a rise in shortwave-infrared brightness (Sentinel-2 B11) after the
  crop has ripened. NDVI marks ripening, not the cut. NDTI adds nothing. Radar barely separates cut
  from uncut fields and has no 2024 coverage here, so it stays out of the first build.
- **Accuracy so far.** A simple rule dated the cut within one photo, about a week, for 16 of 22
  fields, and for 10 of 16 in the blind rounds. It never fired on 11 uncut boxes. The dates it was
  scored against are my reading of the photos, not ground truth.
- **Known failure modes.** These are: fields cut before any clear ripe photo, late-October snow and
  wet ground, haze the cloud mask misses, swathing, which brightens slowly, and long cloud gaps.
- **Fields move.** 7 of 35 boxes straddled a field boundary in some year. Boundaries must be found
  per season, not drawn once.

## Goals

1. Per season, find field boundaries automatically for the test area.
2. Build each field's clear-date time series (NDVI, B11, visible brightness, clear share).
3. Date the cut per field, with an uncertainty window and a status: dated, cut in a cloud gap,
   no cut found, or unclear.
4. Write a harvest-date map (PNG and GeoTIFF) and a per-field CSV, the way Phase 1 writes NDVI.
5. Check it against ground truth before calling Phase 2 done.

## Non-goals

- PostGIS, an API or a dashboard. Those are Phase 4.
- Linking fields to farms. That's Phase 3.
- Radar gap filling. That's Phase 3 at the earliest, and only if a better radar test works.

## Anti-goals

- A harvest date shown without its uncertainty and status. A wrong confident date damages a
  dealer's trust more than "unclear" does.
- Re-tuning the rule on the three experiment rounds and then quoting its score on those rounds.

## The how, in build order

Each step ends with something to look at, and each is test-first in Java like Phase 1.

1. **Season series for an area** (`pipeline`). Fetch every usable Sentinel-2 date for an area and
   season, reusing the experiment's scene choice. Keep the needed bands as one stack on disk.
   CLI: `season --bbox ... --year 2025 --out ...`.
2. **Field boundaries** (`core`). Segment from a mid-season NDVI image plus a post-harvest image.
   Threshold, then edge detection, then connected components, dropping anything under about 5 ha.
   CLI `fields` writes polygons as GeoJSON and a preview PNG. Check: overlay on photos.
3. **Per-field series** (`core`). Median of each index over each field's clear pixels per date,
   skipping dates under 60% clear. CLI writes one CSV per season.
4. **Harvest dating** (`core`). The SWIR-jump rule with fixes for the failure modes above: a season
   cut-off before the first snow, a haze and snow screen, and a ripe gate that keeps the last
   standing-crop date. Output is the date between the two photos around the jump, plus status.
5. **Map and CSV** (`cli`). `harvest` command writes the map and table.
6. **Validation.** Score against ground truth, see the open questions.

## Decided by me, open to change

- Java, in the existing modules, with no new heavy dependencies. Segmentation is written by hand
  on top of JTS, which is already a dependency.
- The test area and seasons stay Kostanay T41UMV, 2024 and 2025, until a district with ground
  truth is chosen.
- The experiment module is deleted once steps 1 to 5 replace it.

## Open questions for the owner

1. **Ground truth.** Who can give real cut dates for 20 or more fields, even roughly? Without
   that, Phase 2's gate can only be met with photo readings.
2. **A fresh holdout first?** The rule's fixes could be checked on a season it has never seen,
   such as 2023, before building. That adds about an hour of my time.
3. **District.** Is Kostanay still the area, or is there a district where ground truth is easier?
