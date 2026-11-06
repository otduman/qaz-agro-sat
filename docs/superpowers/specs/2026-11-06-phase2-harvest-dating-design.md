# Phase 2 build: per-field harvest dates

Status:
- Written 2026-11-06.
- The owner chose to build steps 1 to 3 first ("option A, but being careful").
- This version adds the method for steps 1 to 3 and is waiting for the owner's approval.
- Nothing in it is built yet.

Evidence: the harvest-signal experiment on branch `harvest-signal-experiment`. Its results page is
https://claude.ai/artifact/HTU548DNFfXB2v5jMcWSHW.

## Intent

Phase 2 of `PLAN.md` ends with a harvest-date map for one district and season, checked against
ground truth. The experiment showed what to build on, and that the dating rule itself isn't ready.
This document splits Phase 2 into the parts that are safe to build now and the part that still
needs evidence.

## What the experiment settled

- **Signal.**
  - At the cut, a field usually turns paler in shortwave infrared (Sentinel-2 B11).
  - NDVI marks ripening, not the cut. NDTI adds nothing.
  - Radar barely separates cut fields from uncut ones, and has no 2023 or 2024 coverage here.
- **The simple rule isn't good enough.** Rule v1 is the biggest SWIR rise after ripening. On data
  it had never seen (rounds 2 to 4), it dated the cut within one photo for only 17 of 29 fields. It
  never fired on the 10 uncut boxes in those rounds.
- **A tuned rule did worse.** Rule v2 fixed the misses seen in rounds 1 to 3 and was committed
  before the 2023 data existed. On 2023 it got 5 of 13, against 7 of 13 for v1. Tuning on these
  boxes overfits.
- **Why it misses:**
  - Some cuts darken a field instead of brightening it.
  - Late crops never drop below the NDVI ripe gate.
  - Fields cut before any clear ripe photo can't be seen.
  - Snow, wet ground and haze slip past the cloud mask.
  - Swathing brightens slowly.
- **Texture is the lead to follow.** Reading the photos by eye, the clearest sign of a cut was
  parallel lines appearing: combine tracks and swath rows.
- **Fields move.** 7 of 35 boxes straddled a field boundary in some year. Boundaries must be found
  per season.

## Goals

1. Per season, find field boundaries automatically for the test area.
2. Build each field's clear-date time series, holding:
   - NDVI;
   - B11;
   - visible brightness;
   - clear share;
   - texture measures.
3. Date the cut per field, with an uncertainty window and one of these statuses:
   - dated;
   - cut in a cloud gap;
   - no cut found;
   - unclear.

   Use the best method the evidence supports at the time.
4. Write a harvest-date map (PNG and GeoTIFF) and a per-field CSV, the way Phase 1 writes NDVI.
5. Check it against ground truth before calling Phase 2 done.

## Non-goals

- PostGIS, an API or a dashboard. Those are Phase 4.
- Linking fields to farms. That's Phase 3.
- Radar gap filling. That's Phase 3 at the earliest, and only if a better radar test works.
- Areas that need more than one Sentinel-2 tile. The test area sits inside tile T41UMV. A district
  will need several tiles stitched together, which comes later.
- Crop type. The step 2 land label only separates fields from other land.

## Anti-goals

- A harvest date shown without its uncertainty and status. A wrong confident date damages a
  dealer's trust more than "unclear" does.
- Tuning a method on one set of data and then quoting its score on the same data. This applies to
  boundary thresholds as well as to dating.
- Data that disappears silently. Any date, field or pixel left out must be recorded somewhere with
  the reason.

## Constraints

- Java 21 in the existing Maven modules, test-first with small synthetic rasters, like Phase 1.
- No new heavy dependencies. JTS and proj4j are already available.
- Free data only, with no accounts.
- Memory stays under about 1 GB. Process one date at a time instead of loading a whole season.
- Each step goes on its own branch and is merged into `main` only after the owner says OK.
- Each step ends with something the owner can look at.

## Test area and seasons

- Area: lon 62.40 to 62.70, lat 53.45 to 53.63, about 20 × 20 km in Kostanay oblast. Its grid at
  10 m is 2004 × 2018 pixels.
- Tile: T41UMV (UTM zone 41N, EPSG:32641).
- Seasons: 2025, 2024 and 2023. Each runs from 1 May to 31 October and has about 50 Sentinel-2
  dates. Between 22 and 30 of those dates are mostly clear over the area.
- No free outside map of the fields exists here. OpenStreetMap has 3 farmland polygons covering
  861 ha of the area's roughly 40,000 ha.

## The how, in build order

Steps 1 to 3 are needed whatever dating method wins, so they start now.

### Step 1. Season stack (`pipeline` and `cli`), branch `season-stack`

Downloads every usable Sentinel-2 date of a season for the area once and keeps it on disk. Later
steps and experiments then run offline in seconds instead of downloading for minutes.

**Scene choice.** Search for scenes with tile cloud cover under 80%. For each date, use the least
cloudy scene of tile T41UMV whose data footprint covers the area. This is the experiment's choice,
moved from `experiment` into `pipeline`.

**Why one tile.** Every date then shares exactly the same pixel grid, so pixels line up across
dates without resampling.

**What it reads.** Seven layers per date, at their native resolution:
- 10 m: blue (B02), green (B03), red (B04) and near-infrared (B08);
- 20 m: SWIR B11, SWIR B12 and the cloud mask SCL.

**Order of reading.** Read the cloud mask first. If less than 1% of the area is clear, record the
date as cloudy and skip its bands. This is my call.

**Storage.** One file per layer per date, under `<out>/<date>/`:
- Each file is a tiled, deflate-compressed GeoTIFF. GeoTIFFs open in QGIS.
- Values are kept raw, exactly as delivered: 16-bit for the bands and 8-bit for the cloud mask.
  They are not converted to reflectance. Nothing is lost, and the band files are half the size of
  32-bit values.
- `CogReader` reads the files back from disk.
- The writer needs to learn 8-bit and 16-bit, tiled and compressed output. Today it writes
  uncompressed 32-bit floats.

**Index.** `index.csv` has one row per date with these columns:
- date;
- scene id;
- tile cloud cover;
- clear share of the area;
- status: stored, or cloudy and skipped;
- the scale and offset of each band.

Reflectance is computed when the files are read, using the stored scale and offset.

**Resuming.**
- Each file is written under a temporary name and renamed once complete.
- A date gets its index row only after all of its files exist.
- A rerun skips dates already in the index, so an interrupted run never leaves a half-written date
  that looks complete.

**Size.** About 1 GB per season for the test area. This is an estimate to confirm on the first run.

**CLI.** `season --bbox 62.40,53.45,62.70,53.63 --year 2025 --out data/season-2025`. Progress goes
to stderr, as the `ndvi` command does. `data/` is ignored by git.

**Checks:**
- Synthetic tests cover:
  - the index;
  - resuming after a failure halfway through;
  - the cloudy-date skip;
  - a write and read round trip that returns identical values.
- **Regression check.** Recompute the experiment's 35 box medians from the stored 2025, 2024 and
  2023 stacks. On every stored date they must match the experiment's `optical.csv` files: same
  pixels, same arithmetic.
- **Something to look at.** A contact sheet holding one small true-colour thumbnail per date,
  labelled with the date and its clear share. The whole season can then be seen at a glance.

### Step 2. Field boundaries (`core` and `cli`), branch `field-boundaries`

Finds each season's fields from that season's images alone, because fields get re-split between
years.

**Idea.** A field is land farmed as one unit: sown, sprayed and cut together. Its pixels look alike
on almost every date, and its edges show up as lines on many dates. Roads, shelterbelts and the
change from one crop to another all make such lines. Two neighbouring fields of the same crop may
look identical for most of the season. Even then, they usually differ on a few dates, when one is
sown, sprayed or cut before the other. So edges are gathered over the whole season, not taken from
one image.

**Method:**
1. **Edge strength per date.** On each date, measure how sharply each pixel differs from its
   neighbours. Use the Sobel gradient of NDVI and of visible brightness from the 10 m bands. Count a
   pixel only where it and all its neighbours are clear.
2. **Season edge strength.** Per pixel, keep a high value across the season's clear dates, such as
   the third-highest. This is my call, to be tuned.
   - A plain maximum would let a single bad date draw false lines, such as a haze edge or a cloud
     shadow that the mask missed.
   - A median would lose boundaries that show on only a few dates.
   - Values are kept per pixel as the season streams past, one date at a time.
3. **Regions.** Run a marker-based watershed on the season edge image.
   - Flooding starts from calm areas and stops where floods meet on edge ridges.
   - Unlike simple thresholding, a short gap in a boundary line doesn't merge two fields into one.
4. **Merging.** Merge neighbouring regions when both hold:
   - the ridge along their shared border is weak;
   - their NDVI curves over the season are nearly identical.

   This repairs fields that were split by wet patches or soil differences.
5. **Clean-up:**
   - Fill holes under 1 ha.
   - Keep larger holes, such as birch groves and wet hollows, as holes.
   - Drop regions under 5 ha.
6. **Land label.** Label each region with simple, stated rules:
   - **field:** sown, meaning bare in spring, then green, then dropping;
   - **fallow:** bare most of the season;
   - **other:** water, trees, grassland or buildings.

   These are checked by eye. Only fields and fallow go on to step 3. This is my call: the
   experiment's salt-lake box showed what "other" land does to the numbers.
7. **Polygons.**
   - Trace each region's outline, holes included, into a JTS polygon.
   - Smooth the 10 m staircase with a topology-preserving simplify, at about 5 m.
   - Compute the area in hectares.

**Output:**
- `fields-<year>.geojson` in WGS84 longitude and latitude.
  - Attributes: id, area in ha, land label and a uniformity score.
  - The score measures how alike the field's pixel curves are. A low score marks a region that may
    hold two fields.
- Two preview PNGs: outlines over a mid-season photo and over a post-harvest photo.
- Ids are numbered per season (`2025-F0001` and so on). They are not linked across years.

**CLI.** `fields --stack data/season-2025 --out data/fields-2025`.

**Tuning discipline:**
- Thresholds are tuned on 2025 only.
- Then 2024 and 2023 run unchanged.
- The page reports what they look like, good or bad.

**Checks:**
- Synthetic tests:
  - known rectangles come back exactly;
  - a boundary line with a gap still gives two fields;
  - a field with a pond in it gives one field with a hole;
  - noise inside a field doesn't split it;
  - two same-crop neighbours that differ on only three dates still split.
- **Real data, by eye.** The owner and I look at the previews.
- **Real data, the experiment boxes.** Boxes whose photo reading says "two fields in the box"
  should be crossed by a boundary, and the others shouldn't. These readings are mine, so this is a
  consistency check, not ground truth.
- **Stability.** Count the share of 2025 boundaries that reappear in 2024 and 2023. Most fields
  here keep their shape, so a low share would point to noise. Re-splitting is real, so a high
  share doesn't prove accuracy either.

### Step 3. Per-field series (`core` and `cli`), branch `field-series`

Turns the stack and the boundaries into one row per field per date. This is the table that harvest
dating, and every later feature, reads.

**Which pixels.**
- Shrink each field inward by 20 m. Edge pixels mix with roads and neighbours, and a 20 m SWIR
  pixel on the border is half outside the field.
- Count the clear pixels. If less than 60% of the field is clear, the row keeps its clear share,
  but its values stay blank.

**Values per field and date:**
- NDVI: the median, the 25th percentile and the 75th percentile. A field half cut on that date
  shows up as a wide spread before the median moves.
- Medians of:
  - B11;
  - B12;
  - NDTI;
  - NIR;
  - visible brightness, the mean of blue, green and red.

**Texture.** Measured on 10 m visible brightness, over the clear interior pixels:
- **Roughness.** The median of the local standard deviation in 3 × 3 windows, divided by the
  field's median brightness. Dividing means a brighter field doesn't score higher just for being
  brighter.
- **Line strength and direction.**
  - Add up the gradient structure tensor over the field. Its coherence runs from 0 (no common
    direction) to 1 (all edges parallel), and its angle gives the lines' direction in degrees.
  - This is the "parallel lines" the photo reader used.
- **Caveat.** Sowing rows and sprayer tramlines are also parallel lines, and they exist before the
  cut. Line strength may therefore be high all season. Step 3 only measures it; the texture
  experiment decides whether it dates anything.

**Output:**
- `series-<year>.csv`: one row per field and date.
- `fields-<year>.csv`: id, area, land label, centroid latitude and longitude.

**Something to look at.** A page like the experiment's, showing:
- a map of the fields;
- the curves and photo strip of any field.

**CLI.** `series --stack data/season-2025 --fields data/fields-2025 --out data/series-2025`.

**Checks:**
- Synthetic tests:
  - stripes at several angles give high line strength and the right direction;
  - random speckle gives low line strength;
  - a flat field gives roughness near 0;
  - a field under 60% clear gives a row with blank values.
- Real data: where a field fully contains an experiment box, the field's curves should follow the
  box's curves on clear dates.

### Steps 4 to 6, not designed yet

4. **Harvest dating** (`core`). A texture experiment on the step 3 series decides it. It should be
   scored blind on a season it hasn't seen, and ideally on ground truth.
5. **Map and CSV** (`cli`). The `harvest` command writes the map and table.
6. **Validation.** Score against ground truth.

## Decided by me, open to change

- **Language and libraries.** Java in the existing modules, with segmentation written by hand on
  top of JTS.
- **Area and seasons.** The test area stays Kostanay tile T41UMV over 2023 to 2025, until a
  district with ground truth is chosen.
- **Experiment module.** It is deleted once steps 1 to 5 replace it.
- **Numbers to revisit.** Each was picked by me and can be changed:
  - skip a date under 1% clear;
  - third-highest edge value;
  - fill holes under 1 ha;
  - drop regions under 5 ha;
  - shrink fields by 20 m;
  - leave values blank under 60% clear.
- **Storage.** Raw GeoTIFFs on disk, about 1 GB per season.
- **Boundary method.** Classic image processing, not a trained neural network.
  - Fields here are large rectangles, which classic methods handle well.
  - Public field-boundary models weren't trained on Kazakhstan.
  - Running one from Java would add a heavy dependency.
  - If the previews look poor, this is the first thing to revisit.

## Open questions for the owner

1. **Ground truth.** Who can give real cut dates for 20 or more fields, even roughly? This is the
   main blocker for steps 4 to 6, not for steps 1 to 3. Photo readings can't fairly judge a texture
   method, because the reader uses texture too.
2. **District.** Is Kostanay still the area, or is there a district where ground truth is easier?
