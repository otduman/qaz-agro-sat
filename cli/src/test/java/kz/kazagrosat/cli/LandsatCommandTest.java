package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.pipeline.testutil.FakePlanetaryComputer;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LandsatCommandTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private FakePlanetaryComputer fake;
    private Path stack;
    private Path landsat;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakePlanetaryComputer();
        stack = folder.resolve("stack");
        landsat = folder.resolve("landsat");
        new HandStack(stack).stored("2025-07-12", 1000).finish();
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

    private int download() {
        return run("landsat", "--stack", stack.toString(), "--out", landsat.toString(),
                "--stac-url", fake.stacUri().toString(), "--token-url", fake.tokenUri().toString());
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void downloadsTheStacksSeasonAndPrintsASummary() {
        assertEquals(0, download(), stderr());

        assertTrue(stdout().contains("Scenes:  4 found, 1 Landsat 7 left out, 1 in another UTM zone left out"),
                stdout());
        assertTrue(stdout().contains("1 stored, 1 cloudy (bands skipped), 0 already stored"), stdout());
        assertTrue(Files.isRegularFile(landsat.resolve("index.csv")));
        assertTrue(Files.isRegularFile(landsat.resolve(FakePlanetaryComputer.CLEAR).resolve("red.tif")));
    }

    @Test
    void keepsProgressOnStderr() {
        assertEquals(0, download(), stderr());

        assertTrue(stderr().contains(FakePlanetaryComputer.CLEAR), stderr());
        assertTrue(!stdout().contains("Searching"), stdout());
    }

    @Test
    void needsAStack() {
        assertEquals(2, run("landsat", "--out", landsat.toString()));

        assertTrue(stderr().contains("Missing option: --stack"), stderr());
    }

    @Test
    void refusesAFolderThatIsNotAStack() {
        assertEquals(1, run("landsat", "--stack", folder.resolve("nothing").toString(), "--out", landsat.toString(),
                "--stac-url", fake.stacUri().toString(), "--token-url", fake.tokenUri().toString()));

        assertTrue(stderr().contains("is not a season stack folder"), stderr());
    }

    @Test
    void refusesToWriteIntoTheStack() {
        assertEquals(2, run("landsat", "--stack", stack.toString(), "--out", stack.toString()));

        assertTrue(stderr().contains("Give the Landsat scenes a folder of their own"), stderr());
    }
}
