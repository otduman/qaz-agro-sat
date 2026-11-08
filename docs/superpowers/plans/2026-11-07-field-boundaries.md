# Field Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `fields` command that finds one season's fields from that season's stack alone. It writes them as GeoJSON polygons with a land label, plus a label raster, preview pictures and a summary of everything left out.

**Architecture:**
- **Method, in `core` (`kz.kazagrosat.core.fields`).** Pure array code with no file access:
  - edge strength per date, and the season edge (the third-highest value per pixel);
  - marker-based watershed;
  - per-region NDVI curves, then merging;
  - clean-up and land labels;
  - `FieldFinder`, which runs them in order over a `SeasonImages` source, one date at a time.
- **Files, in `pipeline` (`kz.kazagrosat.pipeline.fields`).**
  - `StackImages` feeds a `SeasonStack` to the method.
  - `RegionOutlines` traces regions into JTS polygons and simplifies them as one coverage.
  - `FieldOutputs` writes GeoJSON, the label GeoTIFF, pictures and the summary. Every file goes under a temporary name first.
- **Command.** `cli` gains `fields`.
- **Checks.** The throwaway `experiment` module gets `FieldCheck`, for the box check and boundary stability.

**Tech Stack:**
- Java 21, Maven, JUnit.
- JTS 1.20 (`CoverageSimplifier`), Jackson and proj4j, all already in use.
- `java.awt` and `ImageIO` for pictures.

**Spec:** `docs/superpowers/specs/2026-11-06-phase2-harvest-dating-design.md`, section "Step 2. Field boundaries".

## Global Constraints

- Java 21 in the existing Maven modules, test-first with small synthetic rasters, like Phase 1.
- No new dependencies. `core` stays free of JTS and Jackson: geometry and JSON live in `pipeline`, which already has them.
- Free data only, with no accounts.
- Memory stays under about 1 GB. Process one date at a time instead of loading a whole season. Real runs use `java -Xmx1g`.
- Branch `field-boundaries`. It is merged into `main` only after the owner says OK.
- Commit messages are plain imperative sentences like the existing history, with no attribution lines.
- Numbers in files and on screen use `Locale.ROOT`. Progress goes to stderr, and results go to stdout.
- Run tests from PowerShell with `.\mvnw.cmd -q -B -pl <module> -am test "-Dtest=<Class>" "-Dsurefire.failIfNoSpecifiedTests=false"`. Always quote `-D` arguments.
- Never modify or delete `data/season-*` or `experiment-output/`. New real outputs go to `data/fields-<year>/`.
- **Tuning discipline (spec).** Parameters are tuned on 2025 only. 2024 and 2023 then run unchanged, and the page reports them as they come out.

## Decisions this plan makes

The spec leaves these open. Each is the plan's call, and the owner sees the list before work starts.

1. **Where code lives.** The spec says `core` and `cli`. The stack reader, JTS and Jackson are in `pipeline`, so reading, outlines and files go there. The method itself stays in `core`.
2. **Markers come from calm areas shrunk by 3 pixels (30 m).** A calm area is where the season edge is under `markerEdge`. Without the shrink, a gap in a boundary line joins two calm areas into one marker, so two fields come out as one. The shrink is what makes the spec's "a gap doesn't merge two fields" true for gaps up to about 60 m.
3. **Edge strength.** It uses Sobel divided by 4, so a sharp step of height h reads as h on the pixels either side. Per date it is `max(|∇NDVI|, 5 × |∇brightness|)`: a 0.02 step in brightness counts like a 0.1 step in NDVI.
4. **Extra outputs.**
   - `fields-<year>.tif` holds the region id per pixel. Step 3 reads exact pixel membership from it, instead of re-rasterising polygons.
   - `edges-<year>.png` shows the season edge, for judging the tuning by eye.
   - `summary-<year>.txt` records every date, region and pixel left out, with the reason. This is what the anti-goal on silently dropped data asks for.
5. **Simplifying.** JTS `CoverageSimplifier` at 5 m smooths all outlines together, so neighbours keep sharing one border, with no slivers or overlaps. Area in hectares is the region's pixel count × 0.01: exact for the raster, and within 0.1% of the true area here.
6. **Land label rules.** Starting values are below, and are tuned on 2025. A region needs a value on at least 5 dates. Its value on a date counts only when at least 60% of its pixels are clear that date.
   - **Other (water, salt):** the median NDVI is under 0.05.
   - **Field:**
     - the lowest NDVI on dates up to 15 June is under 0.3;
     - the peak NDVI is at least 0.5, on or after 20 June;
     - some date after the peak, up to 30 September, is at least 0.25 below the peak.
   - **Fallow:** at least 70% of dates are under NDVI 0.3.
   - **Other:** everything else, including regions with too few dates or no spring date. The summary counts these by reason.
7. **Uniformity score.** Take the region's NDVI standard deviation on each date, then the third-highest of those values, s. The score is `clamp(1 − s / 0.25, 0, 1)`. A region made of two halves 0.5 NDVI apart scores 0.
8. **Merging.** Neighbours merge when both of these hold:
   - the median ridge along their shared border is at most 0.08. The ridge across one pixel pair is the larger of the two pixels' season edges;
   - they have at least 5 common dates with a value, and the third-highest gap between their curves is at most 0.05.

   Pairs are tried from the weakest ridge up. Merged curves are recomputed exactly from pixel sums.
9. **"Exactly" in the rectangle test** holds everywhere except within one pixel of a point where three or more fields meet. There the flood order decides, and step 3 shrinks fields by 20 m anyway.
10. **Box check.**
    - A box counts as crossed when its most common region holds less than 90% of the box's pixels. Pixels left out count as their own region.
    - Boxes read as "part natural part field" are listed but not scored, because the spec's yes/no rule doesn't cover them.
    - 2025 results are marked as seen while tuning.
11. **Stability.**
    - It is the share of one year's boundary pixels that lie within 2 pixels (20 m) of the other year's boundaries, in both directions.
    - It is shown beside its chance level: the share of all pixels that lie within 20 m of the other year's boundaries.

## Parameters

`FieldParameters.DEFAULTS`, Task 1 onwards. These are the starting values for tuning:

| name | value | meaning |
|---|---|---|
| `brightnessWeight` | 5.0 | brightness gradient multiplier |
| `edgeRank` | 3 | season edge = rank-th highest per pixel |
| `markerEdge` | 0.05 | calm below this |
| `markerShrink` | 3 | pixels the calm mask is shrunk by |
| `minMarkerPixels` | 50 | smaller calm specks seed nothing |
| `ridgeWeak` | 0.08 | merge only below this median ridge |
| `curveTolerance` | 0.05 | third-highest curve gap allowed for a merge |
| `minCommonDates` | 5 | dates both regions need for a merge |
| `minClearShare` | 0.6 | region share clear for its value to count that date |
| `minRegionHectares` | 5.0 | regions under this are dropped |
| `maxHoleHectares` | 1.0 | holes under this are filled |
| `minLabelDates` | 5 | dates with a value needed for a land label |
| `springEnd` | 06-15 | |
| `springBare` | 0.3 | |
| `peakGreen` | 0.5 | |
| `peakFrom` | 06-20 | |
| `dropBy` | 0.25 | |
| `dropUntil` | 09-30 | |
| `fallowBare` | 0.3 | |
| `fallowShare` | 0.7 | |
| `waterMedian` | 0.05 | |
| `uniformitySpread` | 0.25 | |
| `simplifyMetres` | 10.0 | was 5.0; see the Task 5 ruling |

## Review Focus

These five problems are the most likely to bite someone using `fields`. Each is pinned by a test in the task named.

1. **Pixels with too few clear dates** (a cloudy season, or a corner always under cloud). They belong to no field, the summary counts them with the reason, and the run still succeeds. Test: Task 4 `pixelsWithTooFewClearDatesBelongToNoFieldAndAreCounted`.
2. **A haze edge or cloud shadow the mask missed, on one or two dates.** It must not draw a boundary. Test: Task 4 `aLineOnTwoDatesDoesNotSplitAField`.
3. **An output file open in QGIS on a rerun.** The run stops with a message naming the file and saying to close it. Test: Task 6 `explainsALockedOutputFile` (Windows only).
4. **More regions than the 16-bit label file holds** (a district-sized area later). The run is refused with a clear message, and ids never wrap around. Test: Task 6 `refusesMoreRegionsThanTheLabelFileHolds`.
5. **A stack with no stored dates, or a folder that isn't a stack.** The command exits 1 with a clear message and writes nothing. Tests: Task 7 `refusesAStackWithNoStoredDates` and `refusesAFolderThatIsNotAStack`.

---

### Task 1: Edge strength and the season edge

**Files:**
- Create in `core/src/main/java/kz/kazagrosat/core/fields/`: `FieldParameters.java`, `EdgeStrength.java`, `SeasonEdges.java`
- Create in `core/src/test/java/kz/kazagrosat/core/fields/`: `EdgeStrengthTest.java`, `SeasonEdgesTest.java`

**Interfaces:**
- Produces:
  - `record FieldParameters(...)`, holding every parameter in the table, with `static final FieldParameters DEFAULTS`. Dates are `MonthDay`.
  - `static float[] EdgeStrength.of(float[] ndvi, float[] brightness, boolean[] clear, int width, int height, double brightnessWeight)`. The result is NaN where the pixel or any of its 8 neighbours is not clear, or where a value is NaN. At the image border, the edge pixel stands in for the missing neighbours.
  - `new SeasonEdges(int pixels, int rank)`, with `void add(float[] edges)` (NaN skipped) and `float[] result()`. A pixel with fewer than `rank` values gives NaN. The class keeps `rank` floats per pixel, never the whole season.

- [ ] **Step 1: Write the failing tests**

`EdgeStrengthTest`, on a 10 × 6 grid, all clear unless said otherwise:
- `aStepInNdviGivesItsHeightOnBothSides`: NDVI 0.2 for x < 5 and 0.6 for x ≥ 5, with flat brightness. The edge is 0.4 (±1e-6) at x = 4 and 5, and 0 at x ≤ 3 and x ≥ 6.
- `brightnessCountsWithItsWeight`: flat NDVI, and brightness stepping from 0.10 to 0.14. With weight 5, the edge is 0.2 at the step.
- `takesTheLargerOfTheTwo`: an NDVI step of 0.1 and a brightness step of 0.04 in the same place give 0.2.
- `isMissingNextToAnUnclearPixel`: with (2,2) unclear, the 3 × 3 block around it is NaN and every other pixel is a number.
- `aFlatImageIsZeroUpToTheBorder`: 0 everywhere, borders included.
- `rejectsArraysOfTheWrongSize`: `IllegalArgumentException`.

`SeasonEdgesTest`:
- `keepsTheThirdHighestValue`: one pixel over 5 dates of 0.1, 0.5, 0.3, 0.9 and 0.2 gives 0.3.
- `skipsMissingValues`: NaN dates don't count toward the rank.
- `isMissingWithFewerValuesThanTheRank`: two values give NaN.
- `rejectsARankBelowOne`.

- [ ] **Step 2: Run them and see them fail.** `-pl core`, `-Dtest=EdgeStrengthTest+SeasonEdgesTest`. Expected: compilation failure, because the classes don't exist.
- [ ] **Step 3: Implement the three classes.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit:** `Measure edge strength per date and over a season`.

---

### Task 2: Marker-based watershed

**Files:**
- Create: `core/src/main/java/kz/kazagrosat/core/fields/Watershed.java`
- Create: `core/src/test/java/kz/kazagrosat/core/fields/WatershedTest.java`

**Interfaces:**
- Consumes: `FieldParameters`.
- Produces: `static int[] Watershed.flood(float[] edges, int width, int height, FieldParameters p)`.
  - Labels run from 1 to n, numbered in row-major order of each marker's first pixel. NaN pixels get 0, and the flood never crosses them.
  - **Markers:**
    1. Take the pixels with edge < `markerEdge`.
    2. Shrink that mask by `markerShrink` pixels, using a square window.
    3. Keep its 4-connected components of at least `minMarkerPixels` pixels.
  - **Flood:** 4-connected, lowest edge first, with ties broken first-in first-out so the result is deterministic. A pixel takes the label of the neighbour that reached it first.

- [ ] **Step 1: Write the failing tests** (`WatershedTest`). Edge values are given directly, with `DEFAULTS`:
- `twoCalmAreasSplitAlongTheRidge`: 40 × 20 with edge 0 everywhere except column 20, which is 1.0. The result has 2 labels: x < 20 holds one and x > 20 the other.
- `aGapInTheRidgeStillGivesTwoRegions`: as above, but rows 8 to 11 of column 20 are 0. Still 2 labels; every pixel at x ≤ 15 has the left label and every pixel at x ≥ 25 the right one.
- `aSmallCalmSpeckSeedsNothing`: 40 × 20 with the left half calm. The right half is 0.2, apart from a 3 × 3 calm speck. Result: 1 label everywhere.
- `missingPixelsGetNoRegion`: NaN pixels get 0.
- `labelsFollowScanOrder`: the marker nearest the top-left corner gets 1.

- [ ] **Step 2: Run them and see them fail** (compilation).
- [ ] **Step 3: Implement `Watershed`.** Use a bucket queue over the edge value quantized to 1/1000 and clamped at 1.0. Each bucket is first-in first-out.
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit:** `Split the season edge image into regions by watershed`.

---

### Task 3: Region curves, merging and uniformity

**Files:**
- Create in `core/src/main/java/kz/kazagrosat/core/fields/`: `RegionSeries.java`, `RegionMerger.java`
- Create in `core/src/test/java/kz/kazagrosat/core/fields/`: `RegionSeriesTest.java`, `RegionMergerTest.java`

**Interfaces:**
- Consumes: `FieldParameters`.
- Produces:
  - `new RegionSeries(int regions, int dates)`:
    - `void add(int dateIndex, int[] labels, float[] ndvi, boolean[] clear)` adds up, per region and date, the clear pixel count, the sum of NDVI and the sum of its squares. Label 0 is ignored.
    - `double mean(int region, int dateIndex, int regionPixels, double minClearShare)` and `double spread(int region, int dateIndex, int regionPixels, double minClearShare)` (the population standard deviation) give NaN when under the clear share.
    - `RegionSeries combine(int[] newIdOf, int newRegions)` sums regions together exactly. `newIdOf[old]` 0 means the region is left out.
    - `double uniformity(int region, int regionPixels, FieldParameters p)` follows decision 7. It is NaN when fewer than `edgeRank` dates have a value.
  - `static int[] RegionMerger.merge(int[] labels, float[] seasonEdges, int width, int height, RegionSeries series, int[] regionPixels, FieldParameters p)` returns `newIdOf`: for each old label, its merged id, numbered from 1 in order of the smallest old label. It follows decision 8.

- [ ] **Step 1: Write the failing tests**

`RegionSeriesTest`:
- `meanAndSpreadPerRegionAndDate`: region 1 holds pixels of 0.2, 0.4 and 0.6. Its mean is 0.4 and its spread (population standard deviation) is 0.1633 (±1e-4).
- `unclearPixelsDoNotCount`.
- `noValueUnderTheClearShare`: 50% clear, with `minClearShare` 0.6, gives NaN.
- `combiningAddsThePixelSums`: two regions combined give the same mean and spread as one region holding all their pixels.
- `uniformityIsOneForAFlatRegion` and `uniformityIsZeroForHalvesHalfAnNdviApart` (0.3 against 0.8 on 4 dates).

`RegionMergerTest` uses two side-by-side regions on a 20 × 10 grid with 10 dates:
- `mergesAWeakRidgeWithTheSameCurve`: ridge 0.02, identical curves. One id.
- `keepsAStrongRidgeApart`: ridge 0.3, identical curves. Two ids.
- `keepsCurvesThatDifferOnThreeDatesApart`: ridge 0.02, 0.3 apart on 3 dates. Two ids.
- `mergesCurvesThatDifferOnTwoDates`: ridge 0.02, 0.3 apart on 2 dates. One id.
- `needsEnoughCommonDates`: only 4 dates where both have a value. Two ids.
- `chainsMergesThroughTheMergedCurve`: three equal regions in a row, with weak ridges, give one id.

- [ ] **Step 2: Run them and see them fail** (compilation).
- [ ] **Step 3: Implement `RegionSeries` and `RegionMerger`.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit:** `Merge neighbouring regions with weak borders and matching curves`.

---

### Task 4: Clean-up, land labels and the whole method

**Files:**
- Create in `core/src/main/java/kz/kazagrosat/core/fields/`: `SeasonImages.java`, `DateImage.java`, `Cleanup.java`, `LandLabel.java`, `Region.java`, `FieldSummary.java`, `FieldMap.java`, `FieldFinder.java`
- Create in `core/src/test/java/kz/kazagrosat/core/fields/`: `CleanupTest.java`, `LandLabelTest.java`, `FieldFinderTest.java`, and a test helper `SyntheticSeason.java`

**Interfaces:**
- Consumes: Tasks 1 to 3, and `PixelGrid`.
- Produces:
  - `record DateImage(LocalDate date, float[] ndvi, float[] brightness, boolean[] clear)`
  - `interface SeasonImages { PixelGrid grid(); List<LocalDate> dates(); DateImage read(LocalDate date) throws IOException, InterruptedException; }`
  - `Cleanup`:
    - `static int[] dropSmall(int[] labels, int regionCount, int minPixels)` sets regions under the minimum to 0.
    - `static int[] fillHoles(int[] labels, int width, int height, int maxPixels)` gives a 4-connected component of 0s to the single region around it. The component must be under `maxPixels`, must not touch the image border, and must border exactly one region.
  - `enum LandLabel { FIELD, FALLOW, OTHER }` with `static Labelled of(List<LocalDate> dates, double[] values, FieldParameters p)`. `Labelled` is `(LandLabel label, String reason)`, and `reason` is null unless the region was labelled other for too few dates or no spring date.
  - `record Region(int id, int pixels, LandLabel land, double uniformity)`
  - `record FieldSummary(List<LocalDate> dates, int watershedRegions, int merges, int droppedRegions, long droppedPixels, int filledHoles, long filledPixels, int keptHoles, long noDataPixels, Map<String, Integer> otherReasons)`. Here `droppedPixels` and `noDataPixels` count what is left out after filling.
  - `record FieldMap(PixelGrid grid, int[] labels, float[] seasonEdges, List<Region> regions, FieldSummary summary)`. Final ids run from 1 to n in row-major order of first pixel.
  - `static FieldMap FieldFinder.find(SeasonImages season, FieldParameters p, Consumer<String> progress)`, which:
    1. reads each date for edges;
    2. floods;
    3. reads each date again for region sums;
    4. merges;
    5. drops small regions;
    6. fills holes;
    7. combines the sums;
    8. labels the regions and scores uniformity.

    Progress lines: `Edges: <date> (i of n)`, `Regions: <date> (i of n)`, then one line per stage with its counts.

- [ ] **Step 1: Write the failing tests**

`SyntheticSeason` implements `SeasonImages` from per-pixel functions. It has 12 dates, every 14 days from 2025-05-05, on a 160 × 120 grid at 10 m, with brightness 0.1 unless set.

Curves:
- **Wheat:** NDVI 0.15 until 1 June, rising to 0.8 by 10 July, falling to 0.3 by 1 September, and 0.2 after.
- **Late wheat:** wheat shifted 14 days later.
- **Fallow:** 0.15 throughout.
- **Water:** −0.2.

`CleanupTest`:
- `dropsRegionsUnderTheMinimum`.
- `fillsAHoleUnderTheMaximum`.
- `keepsALargerHole`.
- `doesNotFillAGapTouchingTheBorder`.
- `doesNotFillAGapBetweenTwoRegions`.

`LandLabelTest`:
- `wheatIsAField`.
- `bareAllSeasonIsFallow`.
- `waterIsOther`.
- `treesGreenFromSpringAreOther`: 0.6 from May and 0.75 in July, with no drop before 30 September.
- `grassPeakingInEarlyJuneIsOther`.
- `tooFewDatesIsOtherWithAReason`.
- `noSpringDateIsOtherWithAReason`.

`FieldFinderTest`, all with `DEFAULTS`:
- `knownFieldsComeBackExactly`: three full-height strips, 50, 60 and 50 pixels wide, of wheat, fallow and late wheat. Three regions, each exactly its strip.
- `fourFieldsMeetingAtAPointComeBackExceptAtTheJunction`: a 2 × 2 layout. Every pixel more than 1 pixel from the junction is in its own field's region.
- `aBoundaryLineWithAGapStillGivesTwoFields`: two wheat halves split by a 2-pixel bare road column (NDVI 0.15 all season) with a 4-pixel gap. Two regions; every pixel 5 or more pixels from the road is on its own side.
- `aPondGivesOneFieldWithAHole`: one wheat field with a 20 × 20 water pond (4 ha) and an 8 × 8 patch 0.2 below wheat (0.64 ha). One region. The pond pixels are 0 (the pond is dropped as under 5 ha, and kept as a hole as 1 ha or more), and `keptHoles` is 1. The patch is too small to seed a region of its own, so it is simply part of the field.
- `noiseInsideAFieldDoesNotSplitIt`: wheat and fallow strips with seeded uniform ±0.04 NDVI noise on every pixel and date. Two regions; every pixel more than 2 pixels from the border between them is right.
- `sameCropNeighboursThatDifferOnThreeDatesStillSplit`: two wheat halves, one 0.3 lower on 3 dates. Two regions.
- `aLineOnTwoDatesDoesNotSplitAField` (Review Focus 2): one wheat field, with a 0.3 NDVI step across its middle on 2 dates only. One region.
- `pixelsWithTooFewClearDatesBelongToNoFieldAndAreCounted` (Review Focus 1): a 30 × 30 block clear on only 2 dates, inside a wheat field. Its pixels and their 1-pixel rim (32 × 32 = 1024) are 0, `noDataPixels` is 1024, and the run succeeds. A second block of 6 × 6 elsewhere in the field (64 pixels with its rim, under 1 ha) is filled into the field: `filledHoles` is 1 and `filledPixels` is 64. Those 64 pixels are not in `noDataPixels`.
- `labelsEachRegion`: the strips above are labelled field, fallow and field. A water strip is labelled other.
- `everyPixelIsAccountedFor`: region pixels + `droppedPixels` + `noDataPixels` = width × height.

- [ ] **Step 2: Run them and see them fail** (compilation).
- [ ] **Step 3: Implement the classes.**
- [ ] **Step 4: Run the `core` tests and see them pass.** `.\mvnw.cmd -q -B -pl core test`.
- [ ] **Step 5: Commit:** `Find a season's fields from its images`.

---

### Task 5: Outlines and GeoJSON

**Files:**
- Modify: `core/src/main/java/kz/kazagrosat/core/geo/UtmProjection.java`. Add `LonLat unproject(double easting, double northing)`, and create `core/.../geo/LonLat.java` as a record `(double longitude, double latitude)`.
- Modify: `core/src/test/java/kz/kazagrosat/core/geo/UtmProjectionTest.java`
- Create in `pipeline/src/main/java/kz/kazagrosat/pipeline/fields/`: `RegionOutlines.java`, `FieldsGeoJson.java`
- Create in `pipeline/src/test/java/kz/kazagrosat/pipeline/fields/`: `RegionOutlinesTest.java`, `FieldsGeoJsonTest.java`

**Interfaces:**
- Consumes: `FieldMap`, `Region`, `PixelGrid`, `UtmProjection`.
- Produces:
  - `static Map<Integer, Polygon> RegionOutlines.trace(int[] labels, PixelGrid grid)`. This gives UTM metres along pixel edges, with collinear points removed. The shell is counter-clockwise and the holes clockwise. Each region is 4-connected, so it gives one polygon.
  - `static Map<Integer, Polygon> RegionOutlines.simplify(Map<Integer, Polygon> outlines, double metres)` uses `CoverageSimplifier`.
  - `static void FieldsGeoJson.write(Path file, FieldMap map, Map<Integer, Polygon> outlines, int year)`:
    - The output is a FeatureCollection in WGS84, with 6 decimals.
    - Each feature's properties are:
      - `id` (`2025-F0001`);
      - `area_ha` (pixels × pixel area, 2 decimals);
      - `land` (`field`, `fallow` or `other`);
      - `uniformity` (2 decimals, or null when it can't be scored).
    - It writes to the path it is given. Task 6 handles the temporary name.

- [ ] **Step 1: Write the failing tests**
- `unprojectUndoesProject`: (62.55, 53.54) round-trips within 1e-9 degrees.

`RegionOutlinesTest`, on grid origin (460140, 5942540) at 10 m:
- `aRectangleBecomesItsFourCorners`: a 5 × 4 region at column 2, row 1. Its shell is exactly (460160, 5942530), (460160, 5942490), (460210, 5942490), (460210, 5942530), closed, and its area is 2000 m².
- `aHoleBecomesAnInnerRing`.
- `anLShapeKeepsOnlyItsCorners`: 6 distinct vertices.
- `aRegionTouchingItselfAtACornerGivesAValidPolygon`: `isValid()`, with the exact area.
- `neighboursStillShareTheirBorderAfterSimplifying`: two regions with a staircase border. After `simplify(…, 5)`, the union's area equals the sum of the two areas (±1e-6), and the union has no interior rings.

`FieldsGeoJsonTest`, parsed back with Jackson:
- `writesOneFeaturePerRegionWithItsProperties`.
- `coordinatesAreLongitudeLatitude`: the first shell vertex equals `unproject` of the UTM corner within 1e-6.
- `shellsRunCounterClockwise`.

- [ ] **Step 2: Run them and see them fail** (compilation). `-pl pipeline -am`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit:** `Trace regions into polygons and write them as GeoJSON`.

---

### Task 6: Reading the stack, pictures and output files

**Files:**
- Create in `pipeline/src/main/java/kz/kazagrosat/pipeline/fields/`: `StackImages.java`, `FieldPictures.java`, `FieldOutputs.java`
- Modify: `pipeline/src/test/java/kz/kazagrosat/pipeline/testutil/HandStack.java`. Add `HandStack(Path folder, int width, int height)`, and `storedNdvi(String date, IntBinaryOperator ndviPercent)`, which writes red and nir DNs giving that NDVI, with brightness flat.
- Create in `pipeline/src/test/java/kz/kazagrosat/pipeline/fields/`: `StackImagesTest.java`, `FieldPicturesTest.java`, `FieldOutputsTest.java`

**Interfaces:**
- Consumes: `SeasonStack`, `StackEntry`, `Layer`, `SclMask`, `Ndvi`, `TiledTiffWriter`, `CogReader`, `FileByteSource`, and Tasks 4 and 5.
- Produces:
  - `new StackImages(SeasonStack stack)`, which implements `SeasonImages`.
    - `dates()` lists the `STORED` dates in order.
    - `read` builds a `DateImage`:
      - NDVI comes from red and nir;
      - brightness is the mean of blue, green and red;
      - clear means `SclMask.usable` at 20 m, upsampled by 2, and all four bands present.
  - `static Optional<LocalDate> FieldPictures.clearestIn(List<StackEntry> entries, MonthDay from, MonthDay to)` takes the stored date with the highest clear share; ties go to the earlier date.
    - The mid-season window is 1 July to 15 August.
    - The post-harvest window is 10 September to 15 October.
  - `static void FieldPictures.preview(Path png, SeasonStack stack, LocalDate date, FieldMap map)` draws:
    - true colour at full resolution, stretched like `ContactSheet`;
    - a 1-pixel outline wherever the label differs from the right or lower neighbour, coloured by the region's land label: field `#FFD60A`, fallow `#00E5FF`, other `#FF4FD8`, and white for pixels left out. Check these against the dataviz validator before use.
  - `static void FieldPictures.edges(Path png, float[] seasonEdges, int width, int height)` maps 0 to black and 0.3 or more to white, with NaN dark red.
  - `static List<Path> FieldOutputs.write(SeasonStack stack, FieldMap map, Path folder)` writes:
    - `fields-<year>.geojson`;
    - `fields-<year>.tif` (16-bit ids, nodata 0);
    - `edges-<year>.png`;
    - `outlines-mid-season-<date>.png` and `outlines-post-harvest-<date>.png`. If a window has no date, its picture is skipped and the summary says so;
    - `summary-<year>.txt`, which lists every `FieldSummary` count, the dates used, and the cloudy dates skipped from the stack index.

    Each file is written as `.part`, then moved into place.
    - If more than 65,535 regions exist, it throws `IllegalArgumentException` before writing anything.
    - An `AccessDeniedException` on a move becomes an `IOException` reading `Cannot replace <file>: it is open in another program, such as QGIS. Close it and run the same command again.`

- [ ] **Step 1: Write the failing tests**

`StackImagesTest`:
- `listsStoredDatesOnly`.
- `computesNdviBrightnessAndClearFromTheStoredLayers`: exact values from the DNs.
- `upsamplesTheCloudMask`: class 9 at mask pixel (3,2) makes 10 m pixels (6 to 7, 4 to 5) unclear.

`FieldPicturesTest`:
- `clearestDateInTheWindowWinsAndTiesGoToTheEarlier`.
- `outlinesTakeTheColourOfTheLandLabel`.
- `insidePixelsShowThePhoto`.

`FieldOutputsTest`, on an 80 × 60 `HandStack` with two strips over 8 dates:
- `writesEveryFile`.
- `theLabelFileReadsBackAsTheRegionIds`.
- `refusesMoreRegionsThanTheLabelFileHolds` (Review Focus 4): a `FieldMap` with 65,536 regions throws, and the folder holds no new files.
- `aRerunWritesIdenticalFiles`: byte-for-byte.
- `leavesNoPartFilesBehind`.
- `explainsALockedOutputFile` (Review Focus 3, `@EnabledOnOs(WINDOWS)`): it holds `fields-2025.tif` open with a `FileInputStream`, and the message names the file and says `open in another program`.

- [ ] **Step 2: Run them and see them fail** (compilation).
- [ ] **Step 3: Implement.** Run the dataviz palette validator on the four outline colours, against a dark and a light background, and keep the result for the page.
- [ ] **Step 4: Run `.\mvnw.cmd -q -B -pl pipeline -am test` and see it pass.**
- [ ] **Step 5: Commit:** `Write a season's fields as GeoJSON, a label raster and pictures`.

---

### Task 7: The `fields` command

**Files:**
- Modify: `cli/src/main/java/kz/kazagrosat/cli/Main.java`: `USAGE`, `parse`, and a new `fields(...)` method.
- Create: `cli/src/test/java/kz/kazagrosat/cli/FieldsCommandTest.java`

**Interfaces:**
- Consumes: `SeasonStack.open`, `StackImages`, `FieldFinder.find(…, FieldParameters.DEFAULTS, err::println)` and `FieldOutputs.write`.

The command is `fields --stack FOLDER --out FOLDER`. On success it prints to stdout:

```
Season:  2025, 50 dates used, 2 cloudy dates skipped
Fields:  312 regions: 241 field (28,410.5 ha), 33 fallow (2,014.0 ha), 38 other (6,120.3 ha)
Left out: 1,204.7 ha (dropped under 5 ha 980.2, no clear data 224.5)
Wrote:   <out>/fields-2025.geojson
         ... one line per file
```

It exits 1 with the message on stderr when:
- the folder isn't a stack;
- the stack has no stored dates (`<stack> has no stored dates, so there is nothing to find fields in`);
- an output can't be written.

Nothing is written in these cases. It exits 2 with usage when `--stack` or `--out` is missing.

- [ ] **Step 1: Write the failing tests**

`FieldsCommandTest`, on an 80 × 60 `HandStack` with two wheat and fallow strips over 8 dates:
- `findsTheFieldsAndPrintsASummary`: exit 0. stdout contains `2 regions` and `fields-2025.geojson`, and the files exist.
- `keepsProgressOnStderr`.
- `refusesAFolderThatIsNotAStack` (Review Focus 5): exit 1, the message says `is not a season stack folder`, and `--out` isn't created.
- `refusesAStackWithNoStoredDates` (Review Focus 5): exit 1 with that message, and no outputs.
- `needsStackAndOut`: exit 2.
- `helpListsTheFieldsCommand`.

- [ ] **Step 2: Run them and see them fail** with "Unknown command: fields".
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full build:** `.\mvnw.cmd -q -B verify`, expecting exit 0.
- [ ] **Step 5: Commit:** `Add the fields command`.

---

### Task 8: The box check and boundary stability

**Files:**
- Create in `experiment/src/main/java/kz/kazagrosat/experiment/`: `BoxCrossing.java`, `BoundaryStability.java`, `FieldCheck.java`
- Create in `experiment/src/test/java/kz/kazagrosat/experiment/`: `BoxCrossingTest.java`, `BoundaryStabilityTest.java`

**Interfaces:**
- Consumes: `Field.load`, `Run.load`, `CogReader` and `FileByteSource`.
- Produces:
  - `static double BoxCrossing.largestShare(int[] labels, int width, Field box)`. Label 0 counts as its own region, and `crossed` means a share under 0.9.
  - `static double BoundaryStability.share(int[] from, int[] to, int width, int height, int tolerance)` and `static double chance(int[] to, int width, int height, int tolerance)`. A boundary pixel is one whose label differs from its right or lower neighbour, and "within" uses the Chebyshev distance.

`FieldCheck <dataFolder>` reads `data/fields-<year>/fields-<year>.tif`, `experiment/fields*.csv` and each year's readings through `runs.csv`, with 2025 combining its two rounds.
- **Per year:** a table of box, reading, largest share, crossed, and agrees. Agreement is counted over the scored boxes (decision 10), and 2025 is labelled "seen while tuning".
- **Stability:** the shares for 2025→2024, 2024→2025, 2025→2023 and 2023→2025, each beside its chance level.

- [ ] **Step 1: Write the failing tests**
- `aBoxInsideOneRegionIsNotCrossed` (1.0), `aBoxSplitInHalfIsCrossed` (0.5), and `leftOutPixelsCountAsTheirOwnRegion`.
- `identicalMapsShareEveryBoundary` (1.0), `aBoundaryTwoPixelsAwayStillCounts`, `threePixelsAwayDoesNot`, and `chanceIsTheShareOfPixelsNearABoundary`.
- [ ] **Step 2: Run them and see them fail** (compilation). `-pl experiment -am`.
- [ ] **Step 3: Implement the two classes and `FieldCheck`.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit:** `Check field boundaries against the experiment boxes and across years`.

---

### Task 9: Real seasons: tune on 2025, then run 2024 and 2023 unchanged

- [ ] **Step 1: Build and run 2025 with the starting values, timing the run:**

```powershell
.\mvnw.cmd -q -B package "-DskipTests"
java -Xmx1g -jar cli\target\kazagrosat.jar fields --stack data\season-2025 --out data\fields-2025
```

Expected:
- exit 0, with no `OutOfMemoryError`;
- the summary accounts for every pixel;
- somewhere between about 100 and 500 field and fallow regions. If the count is far outside that range, look at the pictures before changing anything.

- [ ] **Step 2: Look at the 2025 results.**
  - The edge picture.
  - Both outline pictures, at full size, over the whole area and around the experiment boxes.
  - `java -cp experiment\target\harvest-signal.jar kz.kazagrosat.experiment.FieldCheck data`, for 2025 only.

  Things to look for:
  - fields split by wet patches or soil differences;
  - neighbours merged into one;
  - lines that follow haze, cloud shadow or late-October snow instead of the ground;
  - land labels that are wrong by eye.

- [ ] **Step 3: Tune, in at most 5 rounds.**
  - Each round changes `FieldParameters.DEFAULTS` values only, reruns 2025 and adds a line to the tuning log: what looked wrong, what changed, and why.
  - A change to the method itself is a ruling that also goes to the owner. One example is ending the edge season on 15 October because of snow.
  - The unit tests must still pass after each round. A tuned value that breaks a synthetic test means the method, not the test, needs another look.
  - Commit each round as `Tune field boundaries on 2025: <change>`.
  - If the 2025 pictures still look poor after round 5, stop and report to the owner. The spec names the classic method as the first thing to revisit.

- [ ] **Step 4: Freeze.** Commit `Freeze the field-boundary parameters tuned on 2025`, with the final values in the tuning log.

- [ ] **Step 5: Run 2024 and 2023 unchanged**, timing each:

```powershell
java -Xmx1g -jar cli\target\kazagrosat.jar fields --stack data\season-2024 --out data\fields-2024
java -Xmx1g -jar cli\target\kazagrosat.jar fields --stack data\season-2023 --out data\fields-2023
```

Then run `FieldCheck data` for all years.

- [ ] **Step 6: Write down, per year:**
  - run time;
  - regions by land label, in count and hectares;
  - hectares left out, by reason;
  - box agreement;
  - stability against chance.

  Report 2024 and 2023 as they come out, good or bad. Nothing is retuned after looking at them.

---

### Task 10: Show the owner

- [ ] **Step 1: Add a "Step 2: field boundaries" section to the Phase 2 groundwork page.** Publish it privately if the artifact tools work. Otherwise update the local page. It shows:
  - per year, both outline pictures (scaled down, with full-size crops around a few boxes) and the counts;
  - the 2025 edge picture;
  - the box check, with 2025 marked as seen while tuning;
  - stability beside chance;
  - the tuning log and the final parameters;
  - what I noticed;
  - the commands to run it yourself.
- [ ] **Step 2: Have one fresh reviewer check the whole branch**, then fix what it finds and rerun `.\mvnw.cmd -q -B verify`.
- [ ] **Step 3: Push `field-boundaries`.** Report to the owner and ask for the OK to merge it into `main`, as a single question.
