package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import kz.kazagrosat.pipeline.testutil.FakeEarthSearch;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private FakeEarthSearch fake;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeEarthSearch();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private int run(String... args) {
        return Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private int runNdvi(String bbox, Path target) {
        return run("ndvi", "--bbox", bbox, "--date", "2025-07-12",
                "--out", target.toString(), "--stac-url", fake.stacUri().toString());
    }

    @Test
    void ndviWritesAPngAndAGeoTiff() throws Exception {
        int exit = runNdvi(FakeEarthSearch.AREA, folder);

        assertEquals(0, exit, stderr());
        Path png = folder.resolve(FakeEarthSearch.SCENE_ID + "_ndvi.png");
        Path tiff = folder.resolve(FakeEarthSearch.SCENE_ID + "_ndvi.tif");
        BufferedImage image = ImageIO.read(png.toFile());
        assertEquals(36, image.getWidth());
        assertEquals(30, image.getHeight());
        // 36 x 30 float pixels plus a header.
        assertTrue(Files.size(tiff) > 36 * 30 * 4, "GeoTIFF holds the pixels");
    }

    @Test
    void ndviCreatesTheOutputFolder() {
        Path nested = folder.resolve("runs").resolve("first");

        assertEquals(0, runNdvi(FakeEarthSearch.AREA, nested), stderr());

        assertTrue(Files.exists(nested.resolve(FakeEarthSearch.SCENE_ID + "_ndvi.png")));
    }

    @Test
    void ndviReportsTheSceneAndUsableShare() {
        runNdvi(FakeEarthSearch.AREA, folder);

        assertTrue(stdout().contains(FakeEarthSearch.SCENE_ID), stdout());
        assertTrue(stdout().contains("99.6%"), stdout());
    }

    @Test
    void numbersUseADotWhateverTheMachineLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ru-KZ"));
        try {
            runNdvi(FakeEarthSearch.AREA, folder);
        } finally {
            Locale.setDefault(original);
        }

        assertTrue(stdout().contains("99.6%"), stdout());
    }

    @Test
    void ndviExplainsWhenNoSceneCoversTheArea() {
        int exit = runNdvi("70.0,50.0,70.1,50.1", folder);

        assertEquals(1, exit);
        assertTrue(stderr().contains("scenes"), "points to the scenes command: " + stderr());
    }

    @Test
    void scenesListsDateCloudAndCoverage() {
        int exit = run("scenes", "--bbox", FakeEarthSearch.AREA, "--from", "2025-07-01", "--to", "2025-07-31",
                "--stac-url", fake.stacUri().toString());

        assertEquals(0, exit, stderr());
        String line = stdout().lines().filter(l -> l.contains(FakeEarthSearch.SCENE_ID)).findFirst().orElseThrow();
        assertTrue(line.contains("2025-07-12"), line);
        assertTrue(line.contains("1.3%"), line);
        assertTrue(line.contains("full"), line);
    }

    @Test
    void scenesMarksScenesThatOnlyPartlyCoverTheArea() {
        run("scenes", "--bbox", "62.9,53.9,63.1,54.1", "--from", "2025-07-01", "--to", "2025-07-31",
                "--stac-url", fake.stacUri().toString());

        String line = stdout().lines().filter(l -> l.contains(FakeEarthSearch.SCENE_ID)).findFirst().orElseThrow();
        assertTrue(line.contains("partial"), line);
    }

    @Test
    void scenesPassesTheCloudLimitToTheSearch() {
        run("scenes", "--bbox", FakeEarthSearch.AREA, "--from", "2025-07-01", "--to", "2025-07-31",
                "--max-cloud", "15", "--stac-url", fake.stacUri().toString());

        String body = fake.server().requests().getFirst().body();
        assertTrue(body.contains("\"lt\":15.0"), body);
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        assertEquals(0, run("--help"));

        assertTrue(stdout().contains("ndvi"), stdout());
        assertTrue(stdout().contains("scenes"), stdout());
    }

    @Test
    void noArgumentsIsAUsageError() {
        assertEquals(2, run());

        assertTrue(stderr().contains("ndvi"), stderr());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fly --bbox 1,2,3,4",                                         // unknown command
            "ndvi --date 2025-07-12 --out x",                             // no bbox
            "ndvi --bbox 62,53,63,54 --out x",                            // no date
            "ndvi --bbox 62,53,63,54 --date 2025-07-12",                  // no output folder
            "ndvi --bbox 63,53,62,54 --date 2025-07-12 --out x",          // west east of east
            "ndvi --bbox 62,53,63,54 --date 12.07.2025 --out x",          // wrong date format
            "ndvi --bbox 62,53,63,54 --date 2025-07-12 --out x --colour red",   // unknown option
            "ndvi --bbox 62,53,63,54 --date 2025-07-12 --out",            // option without a value
            "scenes --bbox 62,53,63,54 --from 2025-07-01",                // no end date
            "scenes --bbox 62,53,63,54 --from 2025-07-31 --to 2025-07-01",    // dates reversed
            "scenes --bbox 62,53,63,54 --from 2025-07-01 --to 2025-07-31 --max-cloud lots",
    })
    void invalidCommandsAreUsageErrors(String commandLine) {
        assertEquals(2, run(commandLine.split(" ")), stderr());

        assertFalse(stderr().isBlank());
    }

    @Test
    void aFailedSearchExitsWithAMessageNotAStackTrace() throws Exception {
        try (TestHttpServer empty = new TestHttpServer()) {
            int exit = run("ndvi", "--bbox", FakeEarthSearch.AREA, "--date", "2025-07-12",
                    "--out", folder.toString(), "--stac-url", empty.uri("").toString());

            assertEquals(1, exit);
            assertTrue(stderr().contains("404"), stderr());
            assertFalse(stderr().contains("\tat "), stderr());
        }
    }
}
