package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.pipeline.testutil.FakeEarthSearch;
import kz.kazagrosat.pipeline.testutil.FakeSeason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonCommandTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private FakeSeason fake;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeSeason().scene("2025-07-12", 3).scene("2025-08-03", 7);
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

    private int runSeason(String year) {
        return run("season", "--bbox", FakeEarthSearch.AREA, "--year", year,
                "--out", folder.resolve("season").toString(), "--stac-url", fake.stacUri().toString());
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void storesTheSeasonAndPrintsASummary() {
        assertEquals(0, runSeason("2025"), stderr());

        assertTrue(stdout().contains("Tile:    T41UMV"), stdout());
        assertTrue(stdout().contains("2 found, 0 already stored, 2 stored, 0 cloudy"), stdout());
        assertTrue(Files.exists(folder.resolve("season").resolve("index.csv")));
        assertTrue(Files.exists(folder.resolve("season").resolve("contact-sheet.png")));
    }

    @Test
    void keepsProgressOnStderr() {
        runSeason("2025");

        assertFalse(stdout().contains("Searching"), stdout());
        assertTrue(stderr().contains("Searching"), stderr());
    }

    @Test
    void exitsWith1AndListsFailedDates() {
        fake.breakLayer("2025-08-03", "red");

        assertEquals(1, runSeason("2025"));

        assertTrue(stderr().contains("2025-08-03"), stderr());
        assertTrue(stderr().contains("Run the same command again"), stderr());
    }

    @Test
    void rejectsAYearThatIsNotANumber() {
        assertEquals(2, runSeason("25x"));

        assertTrue(stderr().contains("--year"), stderr());
    }

    @Test
    void helpListsTheSeasonCommand() {
        assertEquals(0, run("--help"));

        assertTrue(stdout().contains("season --bbox"), stdout());
    }
}
