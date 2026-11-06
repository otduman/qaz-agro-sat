# Season Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `season` command that downloads every usable Sentinel-2 date of one season for an area once, keeps it on disk as raw GeoTIFFs with an index, and shows the season as a contact sheet.

**Architecture:**
- **Files.** A new `TiledTiffWriter` in `core` writes compressed 8-bit and 16-bit GeoTIFFs. `CogReader` reads them back through a new `FileByteSource`.
- **Downloading.** `SeasonScenes` (`pipeline/stac`) picks one scene per date on one tile. `SeasonStackBuilder` (`pipeline/stack`) downloads dates in parallel, writes every file under a temporary name and keeps `index.csv` as the record of finished dates.
- **Reading.** `SeasonStack` reads a stack folder for later steps.
- **Command.** `cli` gains the `season` command.
- **Regression check.** The throwaway `experiment` module gets a `StackCheck` that recomputes the experiment's box numbers from the stored stacks.

**Tech Stack:**
- Java 21, Maven, JUnit 5.
- `java.util.zip.Deflater`, `java.awt` and `ImageIO` for the contact sheet.
- Existing JTS and proj4j.

**Spec:** `docs/superpowers/specs/2026-11-06-phase2-harvest-dating-design.md`, section "Step 1. Season stack".

## Global Constraints

- Java 21 in the existing Maven modules, test-first with small synthetic rasters, like Phase 1.
- No new heavy dependencies. JTS and proj4j are already available.
- Free data only, with no accounts.
- Memory stays under about 1 GB. Process one date at a time instead of loading a whole season.
- Branch `season-stack`. It is merged into `main` only after the owner says OK.
- Commit messages are plain imperative sentences like the existing history, with no attribution lines.
- Numbers in files and on screen are formatted with `Locale.ROOT`. Progress goes to stderr, and results go to stdout.
- Run tests from PowerShell with `.\mvnw.cmd -q -B -pl <module> -am test "-Dtest=<Class>" "-Dsurefire.failIfNoSpecifiedTests=false"`. Always quote `-D` arguments.

## Review Focus

These five problems are the most likely to bite someone using `season`. Each is pinned by a test in the task named.

1. **A run cut off halfway** (network drop, closed terminal). Rerunning finishes the season, and a half-written date is never counted as stored. Tests: Task 4 `aFailedDateIsLeftOutAndRetriedOnTheNextRun` and `overwritesLeftoversOfAnInterruptedDate`.
2. **The same `--out` folder with a different bbox or year.** The run is refused with a clear message, and two seasons are never mixed. Test: Task 4 `refusesAFolderHoldingAnotherSeason`.
3. **An area crossing a tile edge.** The run stops with a message saying areas needing several tiles aren't supported yet. It must not crash or leave a partial stack. Test: Task 4 `refusesAnAreaNoSingleTileCovers`.
4. **Part of the area outside the satellite pass on some date.** Pixels with no data count as not clear and the date is still stored. Test: Task 4 `countsMissingDataAsNotClear`.
5. **Windows file locks.** Reading a stored file must not hold it open, or a rerun can't replace it. Test: Task 1 `leavesTheFileFreeToDeleteAfterReading`.

---

### Task 1: Write layer files and read them back

**Files:**
- Create: `core/src/main/java/kz/kazagrosat/core/export/TiledTiffWriter.java`
- Create: `core/src/test/java/kz/kazagrosat/core/export/TiledTiffWriterTest.java`
- Create: `pipeline/src/main/java/kz/kazagrosat/pipeline/cog/FileByteSource.java`
- Create: `pipeline/src/test/java/kz/kazagrosat/pipeline/cog/FileByteSourceTest.java`
- Create: `pipeline/src/test/java/kz/kazagrosat/pipeline/cog/StoredLayerRoundTripTest.java`

**Interfaces:**
- Consumes: `PixelGrid`, `CogReader.open(ByteSource)`, `CogReader.grid()`, `CogReader.readWindow(PixelWindow)`.
- Produces:
  - `TiledTiffWriter.write(Path file, int[] values, PixelGrid grid, int bits, Integer nodata) throws IOException`
  - `new FileByteSource(Path file)`, which implements `ByteSource`

- [ ] **Step 1: Write the failing tests**

`TiledTiffWriterTest`:
- `rejectsBitDepthsOtherThan8And16`: `bits` 12 throws `IllegalArgumentException`.
- `rejectsValuesThatDoNotFillTheGrid`: 10 values for a 4 × 3 grid throw `IllegalArgumentException`.
- `rejectsValuesTooLargeForTheBitDepth`: the value 256 at 8 bits, or −1, throws `IllegalArgumentException` whose message names the value. Values must never be silently cut down.
- `startsWithALittleEndianTiffHeader`: bytes 0 to 3 are `'I','I',42,0`.

`FileByteSourceTest`:
- `readsTheRequestedRange`: bytes 0 to 99, read at offset 10 for length 5, give `{10,11,12,13,14}`.
- `returnsFewerBytesAtTheEndOfTheFile`: offset 95, length 10 gives 5 bytes.
- `returnsNothingPastTheEnd`: offset 200 gives an empty array.
- `leavesTheFileFreeToDeleteAfterReading`: after a read, `Files.delete(file)` succeeds.

`StoredLayerRoundTripTest`. Each case writes with `TiledTiffWriter` into a `@TempDir`, then reads back with `CogReader.open(new FileByteSource(file))`:
- `sixteenBitValuesComeBackUnchanged`:
  - The grid is `new PixelGrid(32641, 460140, 5922780, 10, 300, 270)`, so the edge tiles are partial.
  - The value is `(x * 7919 + y * 104729) % 65536`.
  - `grid()` equals the grid.
  - The full window equals the values.
  - The window `new PixelWindow(250, 250, 40, 20)` matches the same values. It crosses tiles.
- `eightBitValuesComeBackUnchanged`: grid `(32641, 460140, 5922780, 20, 18, 15)`, value `(x + y) % 12`.
- `compressesSmoothBandsWellBelowTheirRawSize`: a 512 × 512 16-bit layer with the value `1000 + x / 64` makes a file smaller than `512 * 512 * 2 / 4` bytes.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\mvnw.cmd -q -B -pl core,pipeline -am test "-Dtest=TiledTiffWriterTest,FileByteSourceTest,StoredLayerRoundTripTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

Expected: compilation fails, because `TiledTiffWriter` and `FileByteSource` don't exist.

- [ ] **Step 3: Implement `TiledTiffWriter.write`**

Write a single image, little-endian, with no overviews:
- 256 × 256 tiles, padding partial edge tiles with `nodata`, or 0 when `nodata` is null;
- compression 8 (deflate, `java.util.zip.Deflater`);
- predictor 2 (horizontal differencing);
- sample format 1.

IFD tags must be in ascending order: 256, 257, 258, 259, 262 (=1), 277 (=1), 284 (=1), 317 (=2), 322, 323, 324, 325, 339 (=1), 33550, 33922 and 34735. Add 42113 (the nodata value as ASCII text) only when `nodata` is not null.

Write three GeoKeys, as `GeoTiffWriter` does: 1024=1, 1025=1 and 3072=`grid.epsg()`. Scale and tiepoint also follow `GeoTiffWriter`. Put the IFD and tag arrays before the tile data, so the header sits in the first bytes.

- [ ] **Step 4: Implement `FileByteSource`**

Open the file with `FileChannel.open(file, READ)` inside try-with-resources on every `read`, so no handle stays open between reads.

- [ ] **Step 5: Run the tests to verify they pass**

Run the command from step 2. Expected: all 11 tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/src pipeline/src
git commit -m "Write compressed tiled GeoTIFFs and read them back from disk"
```

---

### Task 2: Pick one scene per date for a season

**Files:**
- Create: `pipeline/src/main/java/kz/kazagrosat/pipeline/stac/SeasonScenes.java`
- Create: `pipeline/src/test/java/kz/kazagrosat/pipeline/stac/SeasonScenesTest.java`

**Interfaces:**
- Consumes: `SceneSelector.covers(StacItem, BoundingBox)`, `StacItem`.
- Produces:
  - `record SeasonScenes(String tile, List<StacItem> scenes)`
  - `static Optional<SeasonScenes> choose(List<StacItem> found, BoundingBox area)`
  - `static String tileOf(StacItem scene)`
  - `static LocalDate dateOf(StacItem scene)`, the UTC date

- [ ] **Step 1: Write the failing tests**

Build each `StacItem` directly. The footprint is a JTS envelope polygon. The area is `new BoundingBox(62.40, 53.45, 62.70, 53.63)`.

- `keepsTheLeastCloudySceneOfEachDate`: two covering T41UMV scenes on 2025-07-12, with clouds 30 and 5. The chosen scene has cloud 5.
- `dropsScenesThatDoNotCoverTheWholeArea`: a scene with cloud 1 that covers only half the area loses to a covering scene with cloud 40 on the same date.
- `sticksToTheTileThatCoversTheAreaOnMostDates`: T41UMV covers on 3 dates and T41UNV covers on 1 other date. The tile is `"T41UMV"`, with 3 scenes.
- `isEmptyWhenNoSceneCoversTheArea`: `choose` returns `Optional.empty()`.
- `readsTheTileFromTheSceneId`: `"S2B_T41UMV_20250712T070939_L2A"` gives `"T41UMV"`, and `"no-tile-here"` throws `IllegalArgumentException`.
- `datesScenesByUtcDay`: `2025-07-12T23:30:00Z` gives `2025-07-12`.
- `listsScenesInDateOrder`: scenes found in the order 09-01, 07-12, 08-03 come back in date order.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\mvnw.cmd -q -B -pl pipeline -am test "-Dtest=SeasonScenesTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

Expected: compilation fails.

- [ ] **Step 3: Implement `SeasonScenes`**

Rules:
- Keep only scenes that cover the area.
- The tile is the regex `_(T\d{2}[A-Z]{3})_` in the id.
- Choose the tile with the most distinct covering dates. A tie goes to the tile whose name comes first in alphabetical order.
- Per UTC date, take the lowest `cloudCover`. A tie goes to the smaller id.

- [ ] **Step 4: Run the tests to verify they pass.** Expected: 7 pass.

- [ ] **Step 5: Commit** with the message `Choose one scene per date on one tile for a season`.

---

### Task 3: The stack folder: index, area file and reader

**Files:**
- Create in `pipeline/src/main/java/kz/kazagrosat/pipeline/stack/`:
  - `Layer.java`
  - `StackEntry.java`
  - `StackIndex.java`
  - `StackArea.java`
  - `SeasonStack.java`
- Create in `pipeline/src/test/java/kz/kazagrosat/pipeline/stack/`:
  - `StackIndexTest.java`
  - `StackAreaTest.java`
  - `SeasonStackTest.java`

**Interfaces:**
- Consumes: `TiledTiffWriter.write`, `FileByteSource`, `CogReader`, `Reflectance.fromDigitalNumbers`, `BoundingBox`, `PixelGrid`.
- Produces:

  **`Layer`:**
  - `enum Layer { BLUE("blue"), GREEN("green"), RED("red"), NIR("nir"), SWIR16("swir16"), SWIR22("swir22"), SCL("scl") }`
  - `assetKey()` returns the Earth Search asset key.
  - `fileName()` returns `assetKey() + ".tif"`.
  - `bits()` returns 8 for SCL and 16 for the rest.
  - `tenMetre()` is true for BLUE, GREEN, RED and NIR.
  - `static List<Layer> bands()` lists all layers except SCL.

  **`StackEntry`:**
  - `record StackEntry(LocalDate date, String scene, double tileCloud, double clearShare, Status status, double scale, double offset, Integer nodata)`
  - It holds the nested `enum Status { STORED, CLOUDY }`.

  **`StackIndex`:**
  - `static List<StackEntry> read(Path indexFile)`
  - `static void write(Path indexFile, List<StackEntry> entries)`

  **`StackArea`:**
  - `record StackArea(BoundingBox bbox, int year, String tile, PixelGrid grid, PixelGrid maskGrid)`
  - `static Optional<StackArea> read(Path folder)`
  - `void write(Path folder)`
  - `boolean sameRequest(BoundingBox bbox, int year)`

  **`SeasonStack`:**
  - `static SeasonStack open(Path folder)`
  - `StackArea area()`
  - `List<StackEntry> entries()`
  - `int[] raw(LocalDate date, Layer layer)`
  - `float[] reflectance(LocalDate date, Layer layer)`
  - `static Path layerFile(Path folder, LocalDate date, Layer layer)`, which returns `folder/<date>/<fileName>`.
  - All reads throw `IOException` and `InterruptedException`.

- [ ] **Step 1: Write the failing tests**

`StackIndexTest`:
- `writesOneSortedRowPerDate`: entries for 08-03 and 07-12 are written. The file text is exactly:
  ```
  date,scene,tile_cloud,clear_share,status,scale,offset,nodata
  2025-07-12,S2B_T41UMV_20250712T070939_L2A,1.30,0.9312,stored,1.0E-4,-0.1,0
  2025-08-03,S2C_T41UMV_20250803T070951_L2A,62.00,0.0040,cloudy,1.0E-4,-0.1,0
  ```
- `readsBackWhatItWrote`: this includes an entry whose `nodata` is null, which is written as an empty field.
- `aMissingIndexMeansNoDatesYet`: the result is an empty list.
- `replacesTheIndexWithoutLeavingATemporaryFile`: after two writes, the folder holds only `index.csv`.

`StackAreaTest`:
- `roundTripsThroughThePropertiesFile`: the record read back equals the one written.
- `sameRequestComparesBboxAndYear`: the same bbox and year give true. Another year, or a bbox off by 0.01°, gives false.
- `absentWhenTheFolderHasNoStack`.

`SeasonStackTest`. Build a stack by hand in a `@TempDir`: the area file, an index, and layer files via `TiledTiffWriter`.
- `readsRawValuesOfAStoredLayer`.
- `appliesTheStoredScaleAndOffset`:
  - DN 2000 with scale 0.0001 and offset −0.1 gives `0.1f` (delta 1e-6).
  - DN 0 with nodata 0 gives NaN.
- `refusesBandsOfACloudyDateButReadsItsMask`:
  - `raw(cloudyDate, RED)` throws `IllegalArgumentException`, and the message contains "cloudy".
  - `raw(cloudyDate, SCL)` works.
- `refusesAFolderThatIsNotAStack`: `open(emptyDir)` throws `IOException`, and the message contains "not a season stack".
- `refusesReflectanceOfTheCloudMask`: `reflectance(date, SCL)` throws `IllegalArgumentException`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\mvnw.cmd -q -B -pl pipeline -am test "-Dtest=StackIndexTest,StackAreaTest,SeasonStackTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

Expected: compilation fails.

- [ ] **Step 3: Implement**

`StackIndex`:
- Format numbers with `Locale.ROOT`: `tile_cloud` as `%.2f`, `clear_share` as `%.4f`, and `scale` and `offset` with `Double.toString`.
- Write the status in lower case.
- Write to `index.csv.part`, then `Files.move` with `ATOMIC_MOVE` and `REPLACE_EXISTING`.

`StackArea`:
- The file is `stack.properties` with the keys `bbox` (`W,S,E,N`), `year`, `tile`, `grid` and `maskGrid`.
- Grids are written as `epsg,originX,originY,pixelSize,width,height`.

`SeasonStack.raw`:
- Read the whole layer: the window from 0,0 at the layer's grid size, through `CogReader` and `FileByteSource`.
- Use `area().grid()` for 10 m layers and `area().maskGrid()` for 20 m layers.

- [ ] **Step 4: Run the tests to verify they pass.** Expected: 12 pass.

- [ ] **Step 5: Commit** with the message `Define the season stack folder: index, area file and reader`.

---

### Task 4: Download a season

**Files:**
- Create: `pipeline/src/main/java/kz/kazagrosat/pipeline/stack/SeasonStackBuilder.java`
- Create: `pipeline/src/main/java/kz/kazagrosat/pipeline/stack/StackReport.java`
- Create: `pipeline/src/test/java/kz/kazagrosat/pipeline/testutil/FakeSeason.java`
- Create: `pipeline/src/test/java/kz/kazagrosat/pipeline/stack/SeasonStackBuilderTest.java`

**Interfaces:**
- Consumes:
  - `SeasonScenes.choose` and `SeasonScenes.dateOf`;
  - `StacClient`, `StacSearch`, `NdviPipeline.COLLECTION`;
  - `CogReader`, `HttpByteSource`, `RetryingHttp`;
  - `UtmProjection.forEpsg(int).project(BoundingBox)`, `SclMask.usable(int[])`;
  - `TiledTiffWriter`, and everything from Task 3.
- Produces:
  - `new SeasonStackBuilder(URI stacUri, RetryPolicy retry, int threads, Consumer<String> progress)`
  - `StackReport build(BoundingBox area, int year, Path folder) throws IOException, InterruptedException`
  - `record StackReport(String tile, int datesFound, int alreadyStored, int stored, int cloudy, List<String> failures)`. Each failure reads `"YYYY-MM-DD: message"`, and the list is sorted.
  - `FakeSeason` (test-jar):
    - `FakeSeason scene(String date, double cloud)`, a covering scene that is clear everywhere;
    - `scene(String date, double cloud, IntBinaryOperator scl)`;
    - `sceneOutside(String date)`;
    - `breakLayer(String date, String assetKey)`;
    - `repair()`;
    - `scaling(String date, String assetKey, double scale, double offset)`;
    - `static int expectedDn(String assetKey, int x, int y)`;
    - `URI stacUri()`, `TestHttpServer server()`, `close()`.

The fake reuses `FakeEarthSearch`'s geometry and area. The 10 m bands are 64 × 64 with 32-pixel tiles, and the 20 m layers are 32 × 32 with 16-pixel tiles. All have origin `(460000, 5922800)` in EPSG:32641.
- Band DN = `1000 + 100 * bandIndex + (3 * x + y) % 50`, with `bandIndex` in `Layer` order.
- Ids look like `S2B_T41UMV_<yyyyMMdd>T070939_L2A`.
- Default scaling is 0.0001, −0.1 and nodata 0.
- The search handler returns every scene in one page.

Fixed values, from the spec:
- `MAX_TILE_CLOUD = 80.0`;
- `MIN_CLEAR_SHARE = 0.01`;
- the season runs from 1 May to 31 October of `year`.

- [ ] **Step 1: Write the failing tests**

Use `FakeEarthSearch.AREA`. Its 20 m window is col 7, row 1, 18 × 15, and its 10 m window is col 14, row 2, 36 × 30. Run with `threads` = 2 and `new RetryPolicy(2, Duration.ofMillis(1))`.

- `storesEveryLayerOfAClearDateCutToTheArea`: two dates.
  - Both entries are `STORED`.
  - `area().grid()` equals `new PixelGrid(32641, 460140, 5922780, 10, 36, 30)`.
  - `area().maskGrid()` equals `new PixelGrid(32641, 460140, 5922780, 20, 18, 15)`.
  - `raw(date, RED)[0]` equals `expectedDn("red", 14, 2)`.
  - `raw(date, SWIR16)[0]` equals `expectedDn("swir16", 7, 1)`.
  - The report has `stored == 2`.
- `searchesTheSeasonWithTheTileCloudLimit`: the first request body contains `2025-05-01T00:00:00Z/2025-10-31T23:59:59Z` and `"lt":80`.
- `keepsOnlyTheMaskOfAMostlyCloudyDate`: SCL is 9 except at 2 window pixels.
  - The entry is `CLOUDY`, with `clearShare` equal to `2.0 / 270`.
  - `scl.tif` exists and `red.tif` doesn't.
  - No request touches that date's band files.
- `countsMissingDataAsNotClear`: SCL is 0 where `x < 16` and 4 elsewhere, so `clearShare == 0.5`. The date is `STORED`.
- `resumesWithoutDownloadingStoredDatesAgain`: build twice. The second report has `alreadyStored == 2` and `stored == 0`, and no `.tif` requests are made after the first run.
- `aFailedDateIsLeftOutAndRetriedOnTheNextRun`:
  - After `breakLayer(date2, "nir")`, the report has 1 failure starting with `date2`, and the index holds only `date1`.
  - After `repair()` and a second build, `stored == 1` and the index holds 2 entries.
- `overwritesLeftoversOfAnInterruptedDate`:
  - Before the build, put junk `red.tif` and `red.tif.part` files in `folder/<date>/`.
  - After the build, `raw(date, RED)` holds the expected values.
  - No file under the folder ends with `.part`.
- `refusesAFolderHoldingAnotherSeason`: build 2025, then build 2024 into the same folder. This throws `IOException`, and the message contains "already holds".
- `refusesAnAreaNoSingleTileCovers`: with only `sceneOutside` dates, this throws `IOException`, and the message contains "No single Sentinel-2 tile".
- `refusesBandsWithDifferentScaling`: after `scaling(date, "swir16", 0.0002, -0.1)`, that date's failure contains "scaling" and it has no index entry.
- `reportsProgressAsItGoes`:
  - The first message starts with "Searching" and is sent before any request.
  - One message starts with `"2025-07-12: stored"`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\mvnw.cmd -q -B -pl pipeline -am test "-Dtest=SeasonStackBuilderTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

Expected: compilation fails.

- [ ] **Step 3: Implement `SeasonStackBuilder.build`**

1. Send progress `Searching for scenes from <year>-05-01 to <year>-10-31...`.
2. Search with `MAX_TILE_CLOUD` and call `SeasonScenes.choose`. If it is empty, throw `IOException("No single Sentinel-2 tile covers the whole area on any date in " + year + ". Areas that need several tiles are not supported yet.")`.
3. Check any existing `StackArea`. If it isn't the same request, or its tile differs, throw `IOException(folder + " already holds the " + year + " season for " + bbox + ". Use another folder.")`. Report the old area's year and bbox in this message.
4. Without an area file, take the grid from the first scene:
   - the mask window is `scl.grid().windowCovering(UtmProjection.forEpsg(scl.grid().epsg()).project(area))`;
   - `maskGrid` is `scl.grid().subGrid(window)`;
   - `grid` is `red.grid().subGrid(window.scaled(2))`.

   Write the area file before any date.
5. Skip dates already in the index. Send progress `<k> of <n> dates already stored`, then `Downloading <m> dates, <threads> at a time...`.
6. Handle each date as one task in a fixed pool of `threads`:
   1. Read SCL over the mask window, after checking that its tile grid matches.
   2. Compute `clearShare` as the share of window pixels where `SclMask.usable` is true. No-data pixels are not usable.
   3. Write `scl.tif`.
   4. If `clearShare < MIN_CLEAR_SHARE`, the date is `CLOUDY`. Otherwise:
      - Check that the six band assets share one scale, offset and nodata. If not, throw `IOException` with the message "Bands of <scene> have different scaling".
      - Write each band, 10 m bands over `window.scaled(2)` and 20 m bands over the mask window. Check that each grid lines up with SCL's, or the date fails with "does not line up".
   5. Write each file as `<name>.part`, then move it with `ATOMIC_MOVE` and `REPLACE_EXISTING`.
   6. Add the entry to the index under one lock, which rewrites the whole file.
   7. Send progress `<date>: stored, 93.1% clear (12 of 50)` or `<date>: cloudy, 0.4% clear, bands skipped`.

   A task's exception becomes a failure, `<date>: failed: <message>`, and the other dates carry on. `InterruptedException` calls `shutdownNow` and propagates.
7. Return the report.

- [ ] **Step 4: Run the tests to verify they pass.** Expected: 11 pass. Then run the whole pipeline module, `.\mvnw.cmd -q -B -pl pipeline -am test`, and expect no failures.

- [ ] **Step 5: Commit** with the message `Download a season into a resumable stack`.

---

### Task 5: Contact sheet

**Files:**
- Create: `pipeline/src/main/java/kz/kazagrosat/pipeline/stack/ContactSheet.java`
- Create: `pipeline/src/test/java/kz/kazagrosat/pipeline/stack/ContactSheetTest.java`

**Interfaces:**
- Consumes: `SeasonStack`, `StackEntry`, `Layer`.
- Produces: `static void write(SeasonStack stack, Path png) throws IOException, InterruptedException`.

The layout constants are package-private so the test can use them:
- `MAX_THUMB_WIDTH = 240`;
- `COLUMNS = 6`;
- `GAP = 6`;
- `LABEL_HEIGHT = 18`;
- `CELL_MIN_WIDTH = 150`;
- `WHITE_POINT = 0.32`;
- `GAMMA = 0.8`;
- `CLOUDY_GREY = 0x808080`.

- [ ] **Step 1: Write the failing tests**

Build a stack by hand, as in `SeasonStackTest`, with a 36 × 30 grid. Blue, green and red are uniform DN 2000, which is reflectance 0.1. The stack has three dates; the second is `CLOUDY`.

- `storedDatesShowTheirTrueColour`: the pixel at `(GAP + 18, GAP + 15)` has all three channels equal to `(int) Math.round(Math.pow(0.1 / 0.32, 0.8) * 255)`.
- `cloudyDatesShowAsGrey`: the pixel at `(GAP + (CELL_MIN_WIDTH + GAP) + 18, GAP + 15)` is `0x808080`. That is the centre of the second thumbnail, because 36 px thumbnails sit in 150 px cells.
- `fitsAShortSeasonOnOneRow`: the image height is less than `2 * (30 + LABEL_HEIGHT + GAP)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\mvnw.cmd -q -B -pl pipeline -am test "-Dtest=ContactSheetTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

Expected: compilation fails.

- [ ] **Step 3: Implement `ContactSheet.write`**

The thumbnail step is `ceil(grid.width / 240.0)`. Each thumbnail pixel is the mean reflectance of its step × step block, skipping NaN. It is stretched as `clamp(r / WHITE_POINT, 0, 1)^GAMMA * 255`.

Cells are `max(thumbWidth, CELL_MIN_WIDTH)` wide, in date order and six to a row. Each thumbnail is drawn at its cell's top-left corner. Each cell has a label under it: `2025-07-12  93% clear`, or `2025-08-03  cloudy, bands skipped`.

Read one date at a time, and only blue, green and red.

- [ ] **Step 4: Run the tests to verify they pass.** Expected: 3 pass.

- [ ] **Step 5: Commit** with the message `Draw a contact sheet of a season stack`.

---

### Task 6: The `season` command

**Files:**
- Modify: `cli/src/main/java/kz/kazagrosat/cli/Main.java`: `USAGE`, `parse` and a new `season(...)` method.
- Create: `cli/src/test/java/kz/kazagrosat/cli/SeasonCommandTest.java`
- Modify: `.gitignore`, adding `data/`.

**Interfaces:**
- Consumes: `SeasonStackBuilder`, `StackReport`, `SeasonStack.open`, `ContactSheet.write`, `FakeSeason` (test-jar).

The command is `season --bbox W,S,E,N --year YYYY --out FOLDER [--stac-url URL]`. It runs the builder with 4 threads and `err::println` as progress. It always writes `<out>/contact-sheet.png` when any date is stored, then prints to stdout:

```
Tile:    T41UMV
Dates:   50 found, 0 already stored, 44 stored, 6 cloudy (bands skipped)
Size:    0.93 GB in <out>
Wrote:   <out>/contact-sheet.png
```

- **Size** is the sum of file sizes under `<out>`, as `%.2f GB`.
- **On failures** it also prints `Failed dates:` to stderr, one line per date, then `Run the same command again to retry them.`, and returns 1.
- **Bad `--year`:** if it isn't a whole number from 2015 to 2100, the command returns 2 with a usage error.

- [ ] **Step 1: Write the failing tests**

`SeasonCommandTest` uses `FakeSeason` with dates 2025-07-12 and 2025-08-03.
- `storesTheSeasonAndPrintsASummary`:
  - The exit code is 0.
  - stdout contains `Tile:    T41UMV` and `2 found, 0 already stored, 2 stored, 0 cloudy`.
  - `index.csv` and `contact-sheet.png` exist.
- `keepsProgressOnStderr`: stdout doesn't contain "Searching", and stderr does.
- `exitsWith1AndListsFailedDates`: after `breakLayer`, the exit code is 1, and stderr contains `2025-08-03` and `Run the same command again`.
- `rejectsAYearThatIsNotANumber`: `--year 25x` exits with 2, and stderr contains `--year`.
- `helpListsTheSeasonCommand`: `--help` output contains `season --bbox`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\mvnw.cmd -q -B -pl cli -am test "-Dtest=SeasonCommandTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

Expected: the tests fail with "Unknown command: season".

- [ ] **Step 3: Implement the `season` case and method in `Main`, and add `data/` to `.gitignore`.**

- [ ] **Step 4: Run the full build.** Run `.\mvnw.cmd -q -B verify` and expect exit 0.

- [ ] **Step 5: Commit** with the message `Add the season command`.

---

### Task 7: Real seasons and the regression check

**Files:**
- Modify: `experiment/src/main/java/kz/kazagrosat/experiment/OpticalPass.java`. Make `measureField` package-private.
- Modify: `experiment/src/main/java/kz/kazagrosat/experiment/HarvestSignalExperiment.java`. Pull the row format out into `static String opticalLine(OpticalPass.Row row)` and use it in `writeOptical`.
- Create: `experiment/src/main/java/kz/kazagrosat/experiment/StackCheck.java`. It is throwaway, so it has no unit test; running it is the check.

**Interfaces:**
- Consumes: `SeasonStack`, `Layer`, `SclMask.usable`, `Run.load`, `Field.load`, `OpticalPass.measureField`, `opticalLine`.

`StackCheck` takes two arguments: `<dataFolder> <experimentOutputFolder>`.

For each run in `experiment/runs.csv`:
1. Open `<data>/season-<year>`.
2. For every `STORED` date, build the same arrays `OpticalPass.measure` builds, from `stack.raw` and `stack.reflectance`.
3. Format each box row with `opticalLine` and compare it with the row in `<output>/<run>/optical.csv` for the same field and date.

It prints, per run:
- the number of rows compared, matching and differing, with the first 10 that differ;
- experiment dates the stack doesn't hold, with the highest box clear share on each;
- stack dates the experiment doesn't hold.

It exits 0 only when no compared row differs.

- [ ] **Step 1: Make the two experiment changes and add `StackCheck`.** Then run `.\mvnw.cmd -q -B package "-DskipTests"`, which must exit 0.

- [ ] **Step 2: Download the three seasons**, one at a time, timing each:

```powershell
java -jar cli\target\kazagrosat.jar season --bbox 62.40,53.45,62.70,53.63 --year 2025 --out data\season-2025
java -jar cli\target\kazagrosat.jar season --bbox 62.40,53.45,62.70,53.63 --year 2024 --out data\season-2024
java -jar cli\target\kazagrosat.jar season --bbox 62.40,53.45,62.70,53.63 --year 2023 --out data\season-2023
```

Expected:
- each run exits 0;
- each shows `Tile:    T41UMV` and about 50 dates found;
- the 10 m grid in `stack.properties` is `32641,460140.0,5942540.0,10.0,2004,2018`, which is the experiment's `area.csv`.

If a run exits 1, rerun the same command once and record that it resumed. Write down each run's time and size.

- [ ] **Step 3: Run the regression check**

`java -cp experiment\target\harvest-signal.jar kz.kazagrosat.experiment.StackCheck data experiment-output`

Expected:
- exit 0 with 0 differing rows in all four runs;
- every experiment date missing from a stack is one of that stack's cloudy dates.

If a box was 60% or more clear on a skipped cloudy date, report it to the owner. That would be a reason to revisit the 1% rule. Don't change the rule on the quiet.

- [ ] **Step 4: Look at the three contact sheets** against the experiment's photo strips. Check that the dates and colours look right and the cloudy dates show grey.

- [ ] **Step 5: Commit** with the message `Check the season stacks against the experiment's numbers`.

---

### Task 8: Show the owner

- [ ] **Step 1: Publish a private page, "Phase 2 groundwork".** Later steps will add to this page. It shows:
  - the three contact sheets;
  - dates per season (stored and cloudy), size on disk and download time;
  - the regression result;
  - the commands to run it yourself.
- [ ] **Step 2: Have one fresh reviewer check the whole branch**, then fix what it finds and rerun `.\mvnw.cmd -q -B verify`.
- [ ] **Step 3: Push `season-stack`.** Report to the owner and ask for the OK to merge it into `main`, as a single question.
