package kz.kazagrosat.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.testutil.FakeEarthSearch;
import kz.kazagrosat.pipeline.testutil.SyntheticTiff;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.Test;

/** Runs the whole chain over real HTTP: search, scene choice, range reads, scaling, masking, NDVI. */
class NdviPipelineTest {

    private static final BoundingBox AREA = BoundingBox.parse(FakeEarthSearch.AREA);
    private static final LocalDate DATE = LocalDate.of(2025, 7, 12);

    private static NdviPipeline pipeline(FakeEarthSearch fake) {
        return new NdviPipeline(fake.stacUri(), new RetryPolicy(2, Duration.ofMillis(1)));
    }

    @Test
    void producesNdviOnThe10MetreGridAlignedToTheMask() throws Exception {
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            NdviResult result = pipeline(fake).run(AREA, DATE).orElseThrow();

            assertEquals(FakeEarthSearch.SCENE_ID, result.scene().id());
            // 20 m window col 7, row 1, 18 x 15 becomes 36 x 30 at 10 m.
            assertEquals(new PixelGrid(32641, 460140, 5922780, 10, 36, 30), result.grid());
        }
    }

    @Test
    void appliesTheSceneOffsetBeforeComputingNdvi() throws Exception {
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            NdviResult result = pipeline(fake).run(AREA, DATE).orElseThrow();

            // Red 0.1, NIR 0.5 gives 0.6667. Ignoring the -0.1 offset would give 0.5.
            assertEquals(0.666667f, result.ndvi()[0], 1e-5f);
        }
    }

    @Test
    void blanksExactlyThe10MetrePixelsUnderACloudyMaskPixel() throws Exception {
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            NdviResult result = pipeline(fake).run(AREA, DATE).orElseThrow();

            // SCL pixel (10, 3) is window pixel (3, 2), so 10 m cols 6-7 and rows 4-5 of the 36-wide result.
            int width = result.grid().width();
            for (int row = 3; row <= 6; row++) {
                for (int col = 5; col <= 8; col++) {
                    boolean underCloud = col >= 6 && col <= 7 && row >= 4 && row <= 5;
                    assertEquals(underCloud, Float.isNaN(result.ndvi()[row * width + col]),
                            "pixel col " + col + " row " + row);
                }
            }
        }
    }

    @Test
    void reportsTheShareOfUsablePixels() throws Exception {
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            NdviResult result = pipeline(fake).run(AREA, DATE).orElseThrow();

            // 4 of 1080 pixels are under cloud.
            assertEquals(1076.0 / 1080.0, result.validFraction(), 1e-9);
        }
    }

    @Test
    void fetchesImagesOnlyThroughRangeRequests() throws Exception {
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            pipeline(fake).run(AREA, DATE).orElseThrow();

            for (TestHttpServer.Recorded request : fake.server().requests()) {
                if (request.path().endsWith(".tif")) {
                    assertNotNull(request.rangeHeader(), "whole-file read of " + request.path());
                }
            }
        }
    }

    @Test
    void searchesOnlyTheRequestedDay() throws Exception {
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            pipeline(fake).run(AREA, DATE).orElseThrow();

            String body = fake.server().requests().getFirst().body();
            assertTrue(body.contains("2025-07-12T00:00:00Z/2025-07-12T23:59:59Z"), body);
            assertTrue(body.contains("sentinel-2-c1-l2a"), body);
        }
    }

    @Test
    void findsNothingWhenNoSceneCoversTheArea() throws Exception {
        BoundingBox outsideFootprint = new BoundingBox(70.0, 50.0, 70.1, 50.1);
        try (FakeEarthSearch fake = new FakeEarthSearch()) {
            assertTrue(pipeline(fake).run(outsideFootprint, DATE).isEmpty());
        }
    }

    @Test
    void refusesAMaskThatDoesNotLineUpWithTheBands() throws Exception {
        // Shifted by 5 m: every mask pixel would straddle the wrong 10 m pixels.
        SyntheticTiff shifted = new SyntheticTiff(32, 32, 16, 16)
                .bits(8).geo(32641, 460005, 5922800, 20).pixels((x, y) -> 4);
        try (FakeEarthSearch fake = new FakeEarthSearch(shifted)) {
            IOException error = assertThrows(IOException.class, () -> pipeline(fake).run(AREA, DATE));

            assertTrue(error.getMessage().contains("line up"), error.getMessage());
        }
    }
}
