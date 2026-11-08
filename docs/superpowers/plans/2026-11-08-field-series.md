# Field Series Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `series` command that turns a season stack and that season's field map into one row per field per date. Each row holds greenness percentiles, shortwave infrared, tillage index, brightness and texture. Harvest dating and every later feature read this table.

**Architecture:**
- **Two fixes to step 2 come first.**
  - The watershed starts every marker at once (review finding M1).
  - The cloud mask also drops snow that the satellite's own mask counts as clear.
  - Step 2 is then rerun on all three seasons with its frozen values. This is reported as a method fix, not a retune.
- **Method, in `core` (`kz.kazagrosat.core.series`).** Pure array code:
  - each field's interior, shrunk by 20 m;
  - per-date percentiles and medians;
  - texture: roughness, line strength and line direction.
- **Files, in `pipeline` (`kz.kazagrosat.pipeline.series`).**
  - `SeriesBuilder` reads the stack one date at a time, together with the field map from step 2.
  - `SeriesOutputs` writes `series-<year>.csv`, `fields-<year>.csv` and a summary. It replaces them all together or not at all, sharing that logic with `FieldOutputs`.
- **Command.** `cli` gains `series`.
- **Checks.** The throwaway `experiment` module gets `SeriesCheck`. It compares field curves with the experiment's box curves, and draws the data for the results page.

**Tech Stack:** Java 21, Maven, JUnit, Jackson (already in `pipeline`).

**Spec:** `docs/superpowers/specs/2026-11-06-phase2-harvest-dating-design.md`, section "Step 3. Per-field series". The two step 2 fixes come from the step 2 review and the owner's choice of 8 November 2026 ("do A": step 3 with a snow and haze check).

## Global Constraints

- Java 21 in the existing Maven modules, test-first with small synthetic rasters.
- No new dependencies.
- Free data only, with no accounts.
- Memory stays under about 1 GB: one date at a time, with real runs under `java -Xmx1g`.
- Branch `field-series`. It is merged into `main` only after the owner says OK.
- Commit messages are plain imperative sentences with no attribution lines.
- Numbers use `Locale.ROOT`. Progress goes to stderr, results to stdout.
- Tests run from PowerShell: `.\mvnw.cmd -q -B -pl <module> -am test "-Dtest=A,B" "-Dsurefire.failIfNoSpecifiedTests=false"`. Separate test names with commas, never `+`.
- Never modify or delete `data/season-*` or `experiment-output/`.
- **No tuning in this step.** Step 2's values stay frozen. The 20 m shrink and the 60% clear share are the spec's values and are not tuned here.

## Decisions this plan makes

1. **Snow test.** A pixel counts as snow, and so as not clear, when:
   - its snow index `(green − SWIR16) / (green + SWIR16)` is at least 0.4;
   - and its near-infrared is at least 0.11.

   This is the long-standing MODIS and Sentinel snow-mapping rule. The near-infrared condition keeps water, which also has a high snow index, from being called snow. SWIR16 is at 20 m and is used upsampled to 10 m. The test goes into the clear flag that step 2 and step 3 both use.
2. **Haze is measured, not masked.** No simple haze rule has evidence behind it in our data yet. Instead each row carries the field's median blue reflectance, which haze raises, and step 4 can decide on a cut-off with evidence. Each row also carries the share of the field flagged as snow.
3. **Which fields.** Series rows are written for fields and fallow only, as the spec says. `fields-<year>.csv` lists every region with its label, so "other" regions are visible and not silently gone. A field with no pixels left after the 20 m shrink is listed with interior 0 ha and gets rows with blank values.
4. **SWIR at 20 m.** It is upsampled to 10 m and its median taken over the 10 m interior. Any 20 m pixel touching the interior reaches at most 10 m past it, so it still lies at least 10 m inside the field.
5. **Texture**, on 10 m brightness over clear interior pixels whose 3 × 3 neighbourhood is all clear interior:
   - **Roughness** is the median of the 3 × 3 standard deviation, divided by the median brightness.
   - **Line strength** is the coherence of the summed gradient structure tensor, from 0 to 1. Gradients use Sobel divided by 4, as in step 2.
   - **Line direction** is the lines' compass direction, 0 to 180 degrees east of north. It is blank when strength is under 0.05, where a direction means nothing.
6. **Fewer than 30 texture pixels** leave the texture values blank for that row.
7. **The results page** is interactive. It shows a map of the fields; clicking a field shows its curves and a strip of its photos. The photos are about 25 clear dates per season, each a 1000 px JPEG of the whole area, cropped in the browser, so every field gets a strip without a picture per field. It is published as a private multi-file page.
8. **Box check.** For each experiment box lying wholly inside one field's interior, compare the field's median NDVI with the box's NDVI from the experiment's `optical.csv`, on dates both have. Report the median absolute gap per box and in total. The expectation is under 0.05: same pixels, different crop.

## Review Focus

1. **A field fully clouded or snowed on a date.** It still gets a row, with its clear share and blank values. Test: Task 5 `aCloudedDateStillGetsARowWithBlankValues`.
2. **A field with nothing left after the 20 m shrink.** It is listed with interior 0 ha and blank values, and the summary counts it. Test: Task 3 `aThinFieldHasNoInterior` and Task 5 `listsAFieldWithNoInterior`.
3. **Snow that the satellite's mask calls clear.** It is not clear. Test: Task 2 `snowIsNotClear` and `waterIsNotSnow`.
4. **A field map from another season or grid.** Refused with a clear message, and nothing written. Test: Task 6 `refusesFieldsFromAnotherSeason`.
5. **An output open in Excel on a rerun.** Nothing is replaced, and the message names the file. Test: Task 5 `aLockedFileLeavesEveryOutputAsItWas` (Windows only).

---

### Task 1: Every marker starts flooding at once

**Files:** `core/.../fields/Watershed.java`, `core/.../fields/WatershedTest.java`

- [ ] **Step 1: Write the failing test.** `aHigherMarkerKeepsTheBandBesideIt`:
  - 60 × 20 grid. The left half is calm at 0.0. The right half's interior is 0.14, which is calm and a marker.
  - A 3-pixel band at 0.02 lies along the right side of a ridge of 1.0 at column 30.
  - The ridge has a 4-row gap.
  - Every band pixel takes the right region's label.
- [ ] **Step 2: Run it and see it fail.** Today the left flood gets through the gap first and takes the band.
- [ ] **Step 3: Push marker pixels at level 0** instead of their own level.
- [ ] **Step 4: Run the `core` tests and see them pass.**
- [ ] **Step 5: Commit:** `Start every marker's flood at once`.

### Task 2: Snow is not clear

**Files:** create `core/.../mask/SnowTest.java` and `SnowTestTest.java`; modify `pipeline/.../fields/StackImages.java` and `StackImagesTest.java`.

**Interfaces:** `static boolean[] SnowTest.snow(float[] green, float[] nir, float[] swir16)`. NaN anywhere means not snow. `StackImages.read` also reads SWIR16, upsampled to 10 m, and sets `clear = false` where `snow` is true.

- [ ] **Step 1: Write the failing tests.**
  - `snowIsNotClear`: green 0.6, nir 0.55, swir16 0.1 gives an index of 0.71, so snow.
  - `waterIsNotSnow`: green 0.08, nir 0.03, swir16 0.01 gives a high index, but nir is under 0.11.
  - `wheatIsNotSnow`: green 0.08, nir 0.4, swir16 0.2 gives a negative index.
  - `StackImagesTest.aSnowyPixelIsNotClear`.
- [ ] **Step 2: Run them and see them fail.** **Step 3: Implement.** **Step 4: Run them and see them pass.**
- [ ] **Step 5: Check on real data.** Count the snow pixels per date in each season. Expected: next to nothing from May to September, and most of the area on the late-October snow dates (27 October 2024, 26 October 2023). Write the counts down.
- [ ] **Step 6: Commit:** `Treat snow the cloud mask missed as not clear`.
- [ ] **Step 7: Rerun step 2 on all three seasons** with the frozen values, then `FieldCheck`. Add a section "After the freeze: two method fixes" to the tuning notes. It gives the old and new region counts, box split and stability, with the reason for each fix. Nothing is retuned, whatever the numbers do. Commit: `Rerun field boundaries after the two method fixes`.

### Task 3: Field interiors and per-date statistics

**Files:** create in `core/.../series/`: `FieldInteriors.java`, `Percentiles.java`, plus tests.

**Interfaces:**
- `FieldInteriors.of(int[] labels, int width, int height, int shrinkPixels)` returns `FieldInteriors`, which has `int[] pixelsOf(int field)` and `int fieldCount()`.
  - The shrink removes every pixel within `shrinkPixels` (Chebyshev distance) of a pixel of another label, or of the image edge.
  - At 10 m, `shrinkPixels` is 2.
- `static double Percentiles.of(float[] values, int[] pixels, boolean[] use, double p)`: linear interpolation over the used, non-NaN values, or NaN when there are none.

- [ ] **Step 1: Write the failing tests.**
  - `aRectangleShrinksByTwoPixelsOnEverySide`: 20 × 10 becomes 16 × 6.
  - `aThinFieldHasNoInterior`: a 4-pixel-wide strip has an empty interior.
  - `aHoleShrinksItsSurroundingsToo`.
  - `percentilesInterpolate`: values 1 to 5 give a p25 of 2, a median of 3 and a p75 of 4.
  - `unusedAndMissingValuesAreSkipped`.
- [ ] **Steps 2–4:** fail, implement, pass. **Step 5: Commit:** `Find each field's interior and summarise values over it`.

### Task 4: Texture

**Files:** create `core/.../series/Texture.java` and `TextureTest.java`.

**Interfaces:** `static Texture.Measures of(float[] brightness, boolean[] usable, int[] pixels, int width, int height)` returns `record Measures(double roughness, double lineStrength, double lineDirection, int pixels)`. Values are NaN when fewer than 30 pixels qualify, and the direction is NaN when the strength is under 0.05.

- [ ] **Step 1: Write the failing tests** on a 60 × 60 field:
  - `stripesGiveHighLineStrength`: sinusoidal stripes with a 6-pixel period, at 0°, 30°, 45°, 90° and 135° east of north, give a strength over 0.9 and a direction within 2° of the truth.
  - `speckleGivesLowLineStrength`: seeded uniform noise gives under 0.1.
  - `aFlatFieldHasNoRoughness`: under 1e-6.
  - `roughnessIgnoresOverallBrightness`: the same pattern at twice the brightness gives the same roughness.
  - `tooFewPixelsGiveNoTexture`.
- [ ] **Steps 2–4.** **Step 5: Commit:** `Measure roughness and line strength inside a field`.

### Task 5: Building and writing the series

**Files:**
- Create in `pipeline/.../series/`: `SeriesBuilder.java`, `SeriesRow.java`, `SeriesOutputs.java`.
- Extract `pipeline/.../fields/OutputSwap.java` from `FieldOutputs.replaceAll` and the move logic, used by both.
- Tests: `SeriesBuilderTest`, `SeriesOutputsTest`. `HandStack` gains SWIR and green control where needed.

**Interfaces:**
- `SeriesBuilder.build(SeasonStack stack, Path fieldsFolder, Consumer<String> progress)` returns `Series`, holding the regions, the rows and summary counts.
  - It reads `fields-<year>.tif` and the GeoJSON properties (id, area, land).
  - The stack's year and grid must match.
- `SeriesRow`: `field, date, clearShare, ndviP25, ndviMedian, ndviP75, b11, b12, ndti, nir, brightness, blue, snowShare, roughness, lineStrength, lineDirection`.
  - `clearShare` is the share of the interior that is clear.
  - Below 0.6 every value is blank except `clearShare` and `snowShare`.
- `SeriesOutputs.write(Series series, Path folder)` writes:
  - `series-<year>.csv`, with 4 decimals and blanks empty;
  - `fields-<year>.csv`: `id, land, area_ha, interior_ha, centroid_lat, centroid_lon, in_series`;
  - `summary-<year>.txt`: dates, fields in the series, fields without an interior, rows with blank values.

  All three go through `OutputSwap`.

- [ ] **Step 1: Write the failing tests** on a `HandStack` with two fields over 8 dates:
  - `oneRowPerFieldAndDate`.
  - `valuesMatchHandComputedMedians`.
  - `aCloudedDateStillGetsARowWithBlankValues`.
  - `otherRegionsAreListedButGetNoRows`.
  - `listsAFieldWithNoInterior`.
  - `aLockedFileLeavesEveryOutputAsItWas` (Windows).
  - `aRerunWritesIdenticalFiles`.
  - The existing `FieldOutputsTest` must still pass after the extraction.
- [ ] **Steps 2–4.** **Step 5: Commit:** `Build each field's series and write it as tables`.

### Task 6: The `series` command

**Files:** `cli/.../Main.java` and `SeriesCommandTest.java`.

The command is `series --stack FOLDER --fields FOLDER --out FOLDER`. Stdout shows season, dates, fields in the series, rows and blank rows, then `Wrote:` lines.
- Exit 1 for a folder that isn't a stack, missing field files, or fields from another season or grid (`refusesFieldsFromAnotherSeason`), with nothing written.
- Exit 2 for a missing option.

- [ ] **Steps 1–4** as before, with the full build at the end. **Step 5: Commit:** `Add the series command`.

### Task 7: The box check and the page data

**Files:** create `experiment/.../SeriesCheck.java`, plus a unit-tested comparison helper `CurveGap` (median absolute gap on common dates).

`SeriesCheck <data>` does two things:
- It prints decision 8's table per season.
- It writes the page data into `output/phase2-groundwork/step3/`:
  - per season, a JSON of every field's rows;
  - a 1002 px label PNG with field ids encoded as colours, for click lookup;
  - JPEGs of about 25 of the clearest dates, 1002 px wide.

- [ ] **Step 1: Failing tests for `CurveGap`:** identical curves give 0; non-overlapping dates give NaN; one date apart by 0.1 out of 5 gives a median of 0.
- [ ] **Steps 2–4.** **Step 5: Commit:** `Compare field series with the experiment boxes`.

### Task 8: Real seasons

- [ ] **Step 1:** `java -Xmx1g -jar cli\target\kazagrosat.jar series --stack data\season-2025 --fields data\fields-2025 --out data\series-2025`, then the same for 2024 and 2023, timing each. Expected: exit 0, with rows equal to fields × dates.
- [ ] **Step 2:** Run `SeriesCheck`. Expected: the median gap per box is under 0.05. Explain any box over 0.05 by looking at it, rather than adjusting anything.
- [ ] **Step 3: Write down per season:**
  - run time;
  - fields in the series;
  - rows with blank values;
  - snow-flagged rows;
  - the box-check table;
  - one clearly harvested field's line strength over the season, as a first look for step 4. This only describes; it decides nothing.

### Task 9: Show the owner

- [ ] **Step 1: Build the interactive step 3 page.** Load the `artifact-design` skill first. Season switch, field map, click to see curves (NDVI band, SWIR, line strength, blue) and a photo strip, and the box check. Publish it, with the step 1 and step 2 pages, as one private multi-file artifact, and give the owner the link.
- [ ] **Step 2: One fresh reviewer** checks the whole branch, then one fix pass with tests.
- [ ] **Step 3: Push `field-series`** and ask the owner for the OK to merge, as one question.
