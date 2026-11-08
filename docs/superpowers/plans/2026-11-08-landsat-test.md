# Landsat Test Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Find out, with free data and no account, whether Landsat 8 and 9 photos would fill the cloudy gaps in our
Sentinel-2 seasons, and whether their per-field readings agree with Sentinel-2 closely enough to mix into one series.

**Architecture:** A throwaway check in the `experiment` module, like the harvest-signal experiment. It searches
Microsoft Planetary Computer's free `landsat-c2-l2` collection (anonymous access tokens, no account), reads only the
test area's window of each scene, puts it on our 10 m area grid, and reads every step 3 field exactly as
`SeriesBuilder` does. Nothing in `core`, `pipeline` or `cli` changes; if the owner wants Landsat in the product, that
is a later step with its own plan.

**Tech Stack:** Java 21, the existing STAC client and COG reader, JUnit.

**Spec:** the owner's request of 8 November 2026 ("then run a Landsat 8/9 test right after step 3"), and the Phase 2
design's note that cloud gaps in the harvest window hide cuts (`docs/superpowers/specs/2026-11-06-phase2-harvest-dating-design.md`).

## Global Constraints

- Branch `landsat-test`, cut from `field-series`; never merged without the owner's OK.
- Free data only, no accounts: Planetary Computer's public STAC API and its anonymous token endpoint.
- Landsat 8 and 9 only (item ids `LC08_*`, `LC09_*`); Landsat 7 has striped gaps since 2003.
- Never modify `data/season-*`, `data/fields-*`, `data/series-*` or `experiment-output/`. Landsat readings go to
  `data/landsat-<year>/`.
- Memory under 1 GB (`java -Xmx1g`); one scene at a time.
- No tuning: the 20 m interior and 60% clear share are step 3's values.

## Decisions this plan makes

1. **Clear pixel.** Landsat's own `qa_pixel` flags: a pixel is clear when it isn't fill, dilated cloud, cirrus,
   cloud, cloud shadow or snow (bits 0 to 5 all off). Our snow test is applied too, as for Sentinel-2.
2. **Grid.** Both satellites use UTM zone 41N here. Each 10 m pixel of our area takes the 30 m Landsat pixel its
   centre falls in. Scenes of the same satellite on the same day (neighbouring WRS rows) are joined, first clear
   value wins.
3. **Bands.** Landsat red, nir08, swir16, swir22, blue, green, scaled with the item's own scale and offset
   (0.0000275 and -0.2). These are the bands that match Sentinel-2's red, near-infrared (B8), B11, B12, blue and green.
4. **Field reading.** As step 3: interior 20 m inside the field, values only when at least 60% of the interior is
   clear, medians. No texture: at 30 m the combine lines don't show.
5. **Agreement.** For each field, pair a Landsat date with a Sentinel-2 date at most one day apart, both with values.
   Report per band the median difference (Landsat minus Sentinel-2), the median absolute difference, and a straight
   line fitted through the pairs (least squares), per season and pooled.
6. **Gaps filled.** For every field in the series: clear Sentinel-2 dates and clear Landsat dates from 1 August to
   30 September (the harvest window), and the longest run of days without a clear date with and without Landsat.

## Review Focus

1. A Landsat window that only partly covers the area (a scene edge): pixels outside it are not clear. Test:
   `LandsatGridTest.pixelsOutsideTheSceneAreMissing`.
2. Two scenes the same day: joined, not double counted. Test: `LandsatGridTest.aSecondSceneFillsOnlyWhatTheFirstLacks`.
3. QA bits: fill, cloud, shadow, cirrus, snow each make a pixel unclear. Test: `QaPixelTest`.
4. A token expires during a long run: a fresh token is fetched before it does. Test: `PlanetaryTokenTest`.
5. No pair within a day: agreement is blank, not zero. Test: `AgreementTest.noPairsGiveNoAgreement`.

---

### Task 1: QA flags, the grid and the token
- [ ] Tests `QaPixelTest`, `LandsatGridTest`, `PlanetaryTokenTest` fail, then pass. Commit `Read Landsat's clear flags onto the area grid`.

### Task 2: Agreement and gaps
- [ ] Tests `AgreementTest` (identical series give 0; one band off by +0.02 gives bias 0.02; a fitted line through
  y = 0.9x + 0.01 recovers it) and `GapsTest` (longest run without a clear date) fail, then pass. Commit
  `Measure how Landsat and Sentinel-2 agree and how much of the gaps Landsat fills`.

### Task 3: The check on real seasons
- [ ] `LandsatCheck <data>` for 2025, 2024 and 2023: per season scenes found, scenes kept, clear Landsat dates over the
  area, the agreement table and the gap table; writes `data/landsat-<year>/landsat-series-<year>.csv`. Commit
  `Run the Landsat check on three seasons`.
- [ ] Write the results to `docs/superpowers/notes/2026-11-08-landsat-test.md`, add a Landsat section to the results
  page, push, and add the owner's decision (whether to bring Landsat into the product) to the open questions.
