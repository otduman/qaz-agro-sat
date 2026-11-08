package kz.kazagrosat.pipeline.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.stack.StackEntry.Status;
import kz.kazagrosat.pipeline.testutil.FakeEarthSearch;
import kz.kazagrosat.pipeline.testutil.FakeSeason;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Downloads seasons from a local fake catalogue. The area's 20 m window is col 7, row 1, 18 x 15 pixels
 * of the fake tile, and its 10 m window col 14, row 2, 36 x 30 pixels.
 */
class SeasonStackBuilderTest {

    private static final BoundingBox AREA = BoundingBox.parse(FakeEarthSearch.AREA);
    private static final RetryPolicy RETRY = new RetryPolicy(2, Duration.ofMillis(1));
    private static final LocalDate JULY = LocalDate.of(2025, 7, 12);
    private static final LocalDate AUGUST = LocalDate.of(2025, 8, 3);
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @TempDir
    Path folder;

    private FakeSeason fake;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeSeason();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private StackReport build(int year) throws Exception {
        return build(year, message -> {});
    }

    private StackReport build(int year, Consumer<String> progress) throws Exception {
        return new SeasonStackBuilder(fake.stacUri(), RETRY, 2, progress).build(AREA, year, folder);
    }

    @Test
    void storesEveryLayerOfAClearDateCutToTheArea() throws Exception {
        fake.scene("2025-07-12", 3).scene("2025-08-03", 7);

        StackReport report = build(2025);

        SeasonStack stack = SeasonStack.open(folder);
        assertEquals(List.of(JULY, AUGUST), stack.entries().stream().map(StackEntry::date).toList());
        assertTrue(stack.entries().stream().allMatch(e -> e.status() == Status.STORED));
        assertEquals(new PixelGrid(32641, 460140, 5922780, 10, 36, 30), stack.area().grid());
        assertEquals(new PixelGrid(32641, 460140, 5922780, 20, 18, 15), stack.area().maskGrid());
        int[] red = stack.raw(JULY, Layer.RED);
        assertEquals(FakeSeason.expectedDn("red", 14, 2), red[0]);
        assertEquals(FakeSeason.expectedDn("red", 49, 31), red[red.length - 1]);
        assertEquals(FakeSeason.expectedDn("swir16", 7, 1), stack.raw(AUGUST, Layer.SWIR16)[0]);
        assertEquals("T41UMV", report.tile());
        assertEquals(2, report.stored());
    }

    @Test
    void searchesTheSeasonWithTheTileCloudLimit() throws Exception {
        fake.scene("2025-07-12", 3);

        build(2025);

        String body = fake.server().requests().getFirst().body();
        assertTrue(body.contains("2025-05-01T00:00:00Z/2025-10-31T23:59:59Z"), body);
        assertTrue(body.contains("\"lt\":80"), body);
    }

    @Test
    void keepsOnlyTheMaskOfAMostlyCloudyDate() throws Exception {
        fake.scene("2025-07-12", 3)
                .scene("2025-08-03", 60, (x, y) -> y == 3 && (x == 10 || x == 11) ? 4 : 9);

        StackReport report = build(2025);

        StackEntry august = SeasonStack.open(folder).entries().get(1);
        assertEquals(Status.CLOUDY, august.status());
        assertEquals(2.0 / 270, august.clearShare(), 5e-5, "the index keeps four decimals");
        assertTrue(Files.exists(SeasonStack.layerFile(folder, AUGUST, Layer.SCL)));
        assertFalse(Files.exists(SeasonStack.layerFile(folder, AUGUST, Layer.RED)));
        for (TestHttpServer.Recorded request : fake.server().requests()) {
            boolean augustBand = request.path().startsWith("/20250803/") && !request.path().endsWith("scl.tif");
            assertFalse(augustBand, "fetched " + request.path());
        }
        assertEquals(1, report.cloudy());
    }

    @Test
    void countsMissingDataAsNotClear() throws Exception {
        fake.scene("2025-07-12", 3, (x, y) -> x < 16 ? 0 : 4);

        build(2025);

        StackEntry july = SeasonStack.open(folder).entries().getFirst();
        assertEquals(0.5, july.clearShare(), 1e-12);
        assertEquals(Status.STORED, july.status());
    }

    @Test
    void resumesWithoutDownloadingStoredDatesAgain() throws Exception {
        fake.scene("2025-07-12", 3).scene("2025-08-03", 7);
        build(2025);
        int requestsAfterFirstRun = fake.server().requests().size();

        StackReport second = build(2025);

        assertEquals(2, second.alreadyStored());
        assertEquals(0, second.stored());
        List<TestHttpServer.Recorded> later = fake.server().requests()
                .subList(requestsAfterFirstRun, fake.server().requests().size());
        assertTrue(later.stream().noneMatch(r -> r.path().endsWith(".tif")), "refetched files: " + later);
    }

    @Test
    void aFailedDateIsLeftOutAndRetriedOnTheNextRun() throws Exception {
        fake.scene("2025-07-12", 3).scene("2025-08-03", 7).breakLayer("2025-08-03", "nir");

        StackReport first = build(2025);

        assertEquals(1, first.failures().size());
        assertTrue(first.failures().getFirst().startsWith("2025-08-03"), first.failures().toString());
        assertEquals(List.of(JULY), SeasonStack.open(folder).entries().stream().map(StackEntry::date).toList());

        fake.repair();
        StackReport second = build(2025);

        assertEquals(1, second.stored());
        assertEquals(2, SeasonStack.open(folder).entries().size());
        assertEquals(FakeSeason.expectedDn("nir", 14, 2), SeasonStack.open(folder).raw(AUGUST, Layer.NIR)[0]);
    }

    @Test
    void overwritesLeftoversOfAnInterruptedDate() throws Exception {
        fake.scene("2025-07-12", 3);
        Path red = SeasonStack.layerFile(folder, JULY, Layer.RED);
        Files.createDirectories(red.getParent());
        Files.write(red, new byte[] {1, 2, 3});
        Files.write(red.resolveSibling("red.tif.part"), new byte[] {4, 5, 6});

        build(2025);

        assertEquals(FakeSeason.expectedDn("red", 14, 2), SeasonStack.open(folder).raw(JULY, Layer.RED)[0]);
        try (Stream<Path> files = Files.walk(folder)) {
            List<Path> leftovers = files.filter(p -> p.toString().endsWith(".part")).toList();
            assertTrue(leftovers.isEmpty(), "leftovers: " + leftovers);
        }
    }

    @Test
    void refusesAFolderHoldingAnotherSeason() throws Exception {
        fake.scene("2025-07-12", 3).scene("2024-07-12", 3);
        build(2025);

        IOException error = assertThrows(IOException.class, () -> build(2024));

        assertTrue(error.getMessage().contains("already holds"), error.getMessage());
        assertEquals(2025, SeasonStack.open(folder).area().year());
    }

    @Test
    void theStackEntriesCannotBeChanged() throws Exception {
        fake.scene("2025-07-12", 3);
        build(2025);

        List<StackEntry> entries = SeasonStack.open(folder).entries();

        assertThrows(UnsupportedOperationException.class, () -> entries.add(entries.getFirst()));
    }

    @Test
    void refusesAnotherSeasonBeforeSearching() throws Exception {
        fake.scene("2025-07-12", 3).scene("2024-07-12", 3);
        build(2025);
        int requestsBefore = fake.server().requests().size();

        assertThrows(IOException.class, () -> build(2024));

        assertEquals(requestsBefore, fake.server().requests().size(), "no search for a season it would refuse");
    }

    @Test
    void saysSoWhenTheSearchFindsNoScene() {
        IOException error = assertThrows(IOException.class, () -> build(2025));

        assertTrue(error.getMessage().contains("No Sentinel-2 scene"), error.getMessage());
        assertFalse(error.getMessage().contains("several tiles"), error.getMessage());
    }

    @Test
    void aDateWhoseFilesWereDeletedIsDownloadedAgain() throws Exception {
        fake.scene("2025-07-12", 3).scene("2025-08-03", 7);
        build(2025);
        Path red = SeasonStack.layerFile(folder, JULY, Layer.RED);
        Files.delete(red);

        StackReport second = build(2025);

        assertEquals(1, second.stored(), "July is stored again");
        assertEquals(FakeSeason.expectedDn("red", 14, 2), SeasonStack.open(folder).raw(JULY, Layer.RED)[0]);
        assertEquals(2, SeasonStack.open(folder).entries().size());
    }

    @Test
    void refusesAnAreaNoSingleTileCovers() {
        fake.sceneOutside("2025-07-12");

        IOException error = assertThrows(IOException.class, () -> build(2025));

        assertTrue(error.getMessage().contains("No single Sentinel-2 tile"), error.getMessage());
    }

    @Test
    void refusesBandsWithDifferentScaling() throws Exception {
        fake.scene("2025-07-12", 3).scene("2025-08-03", 7).scaling("2025-08-03", "swir16", 0.0002, -0.1);

        StackReport report = build(2025);

        assertEquals(1, report.failures().size());
        assertTrue(report.failures().getFirst().contains("scaling"), report.failures().toString());
        assertEquals(List.of(JULY), SeasonStack.open(folder).entries().stream().map(StackEntry::date).toList());
    }

    /** Holding a file open the way Excel does: on Windows nothing can rename another file over it. */
    private static FileInputStream lock(Path file) throws IOException {
        return new FileInputStream(file.toFile());
    }

    @Test
    void stopsBeforeDownloadingWhenTheIndexIsLocked() throws Exception {
        assumeTrue(WINDOWS, "only Windows blocks replacing a file another program holds open");
        fake.scene("2025-07-12", 3);
        build(2025);
        fake.scene("2025-08-03", 7);
        int requestsBefore = fake.server().requests().size();

        try (FileInputStream excel = lock(folder.resolve("index.csv"))) {
            IOException error = assertThrows(IOException.class, () -> build(2025));

            assertTrue(error.getMessage().contains("index.csv"), error.getMessage());
            assertTrue(error.getMessage().contains("open in another program"), error.getMessage());
        }
        List<TestHttpServer.Recorded> later = fake.server().requests()
                .subList(requestsBefore, fake.server().requests().size());
        assertTrue(later.stream().noneMatch(r -> r.path().endsWith(".tif")), "downloaded anyway: " + later);

        assertEquals(1, build(2025).stored(), "a rerun after closing the file finishes the season");
    }

    @Test
    void abortsWhenTheIndexCannotBeUpdatedMidRun() throws Exception {
        assumeTrue(WINDOWS, "only Windows blocks replacing a file another program holds open");
        fake.scene("2025-07-12", 3).scene("2025-08-03", 7).scene("2025-09-01", 9);
        AtomicReference<FileInputStream> excel = new AtomicReference<>();
        Consumer<String> opensTheIndexAfterTheFirstDate = message -> {
            if (message.startsWith("2025-07-12: stored")) {
                try {
                    excel.set(lock(folder.resolve("index.csv")));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        try {
            IOException error = assertThrows(IOException.class, () -> new SeasonStackBuilder(
                    fake.stacUri(), RETRY, 1, opensTheIndexAfterTheFirstDate).build(AREA, 2025, folder));
            assertTrue(error.getMessage().contains("open in another program"), error.getMessage());
            assertTrue(fake.server().requests().stream().noneMatch(r -> r.path().startsWith("/20250901/")),
                    "kept downloading after the index broke");
        } finally {
            excel.get().close();
        }
        assertEquals(List.of(JULY), SeasonStack.open(folder).entries().stream().map(StackEntry::date).toList());

        StackReport rerun = build(2025);
        assertEquals(2, rerun.stored());
        assertEquals(3, SeasonStack.open(folder).entries().size());
    }

    @Test
    void explainsALockedLayerFile() throws Exception {
        assumeTrue(WINDOWS, "only Windows blocks replacing a file another program holds open");
        fake.scene("2025-07-12", 3);
        Path red = SeasonStack.layerFile(folder, JULY, Layer.RED);
        Files.createDirectories(red.getParent());
        Files.write(red, new byte[] {1, 2, 3});

        StackReport report;
        try (FileInputStream qgis = lock(red)) {
            report = build(2025);
        }

        assertEquals(1, report.failures().size());
        assertTrue(report.failures().getFirst().contains("red.tif"), report.failures().toString());
        assertTrue(report.failures().getFirst().contains("open in another program"), report.failures().toString());
    }

    @Test
    void reportsProgressAsItGoes() throws Exception {
        fake.scene("2025-07-12", 3);
        List<String> messages = new ArrayList<>();
        List<Integer> requestsAtMessage = new ArrayList<>();

        build(2025, message -> {
            messages.add(message);
            requestsAtMessage.add(fake.server().requests().size());
        });

        assertTrue(messages.getFirst().startsWith("Searching"), messages.toString());
        assertEquals(0, requestsAtMessage.getFirst());
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("2025-07-12: stored")), messages.toString());
    }
}
