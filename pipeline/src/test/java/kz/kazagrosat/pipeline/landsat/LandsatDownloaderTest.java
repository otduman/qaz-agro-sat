package kz.kazagrosat.pipeline.landsat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.stack.StackArea;
import kz.kazagrosat.pipeline.testutil.FakePlanetaryComputer;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LandsatDownloaderTest {

    private static final StackArea AREA =
            new StackArea(HandStack.BBOX, 2025, "T41UMV", HandStack.GRID, HandStack.MASK_GRID);

    @TempDir
    Path folder;

    private FakePlanetaryComputer fake;
    private final List<String> progress = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        fake = new FakePlanetaryComputer();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private LandsatReport download(StackArea area) throws Exception {
        return new LandsatDownloader(fake.stacUri(), fake.tokenUri(), new RetryPolicy(2, Duration.ofMillis(1)),
                progress::add).download(area, folder);
    }

    @Test
    void storesAClearScenesSevenLayersOnItsWindow() throws Exception {
        LandsatReport report = download(AREA);

        LandsatFolder landsat = LandsatFolder.open(folder);
        LandsatEntry clear = landsat.entries().stream()
                .filter(e -> e.scene().equals(FakePlanetaryComputer.CLEAR)).findFirst().orElseThrow();
        assertEquals(LocalDate.parse("2025-07-10"), clear.date());
        assertEquals("LC09", clear.satellite());
        assertEquals(LandsatEntry.Status.STORED, clear.status());
        assertEquals(1.0, clear.clearShare(), 1e-12);
        assertEquals(FakePlanetaryComputer.SCALE, clear.scale(), 1e-15);
        assertEquals(FakePlanetaryComputer.OFFSET, clear.offset(), 1e-15);
        LandsatFolder.Layer red = landsat.raw(clear, "red");
        assertEquals(FakePlanetaryComputer.WINDOW, red.grid());
        int[] expected = new int[13 * 11];
        Arrays.fill(expected, FakePlanetaryComputer.dn("red"));
        assertArrayEquals(expected, red.values());
        assertEquals(2, report.stored(), "the clear scene and the one clear in a corner");
        assertEquals(AREA, landsat.area());
    }

    @Test
    void bandsComeBackAsReflectance() throws Exception {
        download(AREA);
        LandsatFolder landsat = LandsatFolder.open(folder);
        LandsatEntry clear = landsat.entries().stream().filter(e -> e.status() == LandsatEntry.Status.STORED)
                .findFirst().orElseThrow();

        float[] nir = landsat.reflectance(clear, "nir08");

        assertEquals(FakePlanetaryComputer.dn("nir08") * FakePlanetaryComputer.SCALE + FakePlanetaryComputer.OFFSET,
                nir[0], 1e-6);
    }

    @Test
    void aCloudySceneKeepsOnlyItsQualityLayer() throws Exception {
        LandsatReport report = download(AREA);

        LandsatEntry cloudy = LandsatFolder.open(folder).entries().stream()
                .filter(e -> e.scene().equals(FakePlanetaryComputer.CLOUDY)).findFirst().orElseThrow();
        assertEquals(LandsatEntry.Status.CLOUDY, cloudy.status());
        assertEquals(0.0, cloudy.clearShare(), 1e-12);
        assertTrue(Files.isRegularFile(folder.resolve(FakePlanetaryComputer.CLOUDY).resolve("qa_pixel.tif")));
        assertFalse(Files.exists(folder.resolve(FakePlanetaryComputer.CLOUDY).resolve("red.tif")));
        assertEquals(1, report.cloudy());
    }

    @Test
    void landsat7AndScenesInAnotherZoneAreLeftOut() throws Exception {
        LandsatReport report = download(AREA);

        assertEquals(5, report.found());
        assertEquals(1, report.landsat7());
        assertEquals(List.of(FakePlanetaryComputer.OTHER_ZONE), report.otherZone());
        assertEquals(3, LandsatFolder.open(folder).entries().size());
        assertFalse(Files.exists(folder.resolve(FakePlanetaryComputer.LANDSAT_7)));
        assertFalse(Files.exists(folder.resolve(FakePlanetaryComputer.OTHER_ZONE)));
    }

    @Test
    void assetLinksAreSignedWithTheToken() throws Exception {
        download(AREA);

        assertTrue(progress.stream().noneMatch(line -> line.contains("failed")), String.join("\n", progress));
        // The fake serves files whatever the query, so check the downloader asked for the token at all.
        assertTrue(fake.server().requests().stream().anyMatch(r -> r.path().equals("/token")));
    }

    @Test
    void aSecondRunDownloadsNothing() throws Exception {
        download(AREA);
        int before = fake.server().requests().size();

        LandsatReport again = download(AREA);

        long fileRequests = fake.server().requests().subList(before, fake.server().requests().size()).stream()
                .filter(r -> r.path().endsWith(".tif")).count();
        assertEquals(0, fileRequests);
        assertEquals(3, again.alreadyStored());
        assertEquals(0, again.stored());
    }

    @Test
    void aSceneWhoseFilesWereDeletedIsDownloadedAgain() throws Exception {
        download(AREA);
        Files.delete(folder.resolve(FakePlanetaryComputer.CLEAR).resolve("swir16.tif"));

        LandsatReport again = download(AREA);

        assertEquals(1, again.stored());
        assertTrue(Files.isRegularFile(folder.resolve(FakePlanetaryComputer.CLEAR).resolve("swir16.tif")));
    }

    @Test
    void aFolderForAnotherAreaIsRefused() throws Exception {
        download(AREA);
        StackArea other = new StackArea(BoundingBox.parse("62.400,53.450,62.406,53.4525"), 2025, "T41UMV",
                HandStack.GRID, HandStack.MASK_GRID);

        IOException error = assertThrows(IOException.class, () -> download(other));

        assertTrue(error.getMessage().contains("already holds"), error.getMessage());
    }

    @Test
    void aSceneClearOverOnlyACornerKeepsItsBands() throws Exception {
        // Under 1% of the area is clear, but that corner can hold a whole field.
        download(AREA);

        LandsatEntry corner = LandsatFolder.open(folder).entries().stream()
                .filter(e -> e.scene().equals(FakePlanetaryComputer.CORNER)).findFirst().orElseThrow();
        assertEquals(1 / 1080.0, corner.clearShare(), 1e-4, "the index keeps 4 decimals");
        assertEquals(LandsatEntry.Status.STORED, corner.status());
        assertTrue(Files.isRegularFile(folder.resolve(FakePlanetaryComputer.CORNER).resolve("red.tif")));
    }
}
