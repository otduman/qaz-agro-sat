package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FieldsCommandTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int runFields(Path stack, Path fields) {
        return run("fields", "--stack", stack.toString(), "--out", fields.toString());
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void findsTheFieldsAndPrintsASummary() throws Exception {
        Path stack = folder.resolve("stack");
        HandStack.twoStrips(stack);
        Path fields = folder.resolve("fields");

        assertEquals(0, runFields(stack, fields), stderr());

        assertTrue(stdout().contains("Season:  2025, 8 dates used, 0 cloudy dates skipped"), stdout());
        assertTrue(stdout().contains("Fields:  2 regions: 1 field (24.0 ha), 1 fallow (24.0 ha), 0 other (0.0 ha)"),
                stdout());
        assertTrue(stdout().contains("Left out: 0.0 ha"), stdout());
        assertTrue(stdout().contains("fields-2025.geojson"), stdout());
        assertTrue(Files.exists(fields.resolve("fields-2025.geojson")));
        assertTrue(Files.exists(fields.resolve("summary-2025.txt")));
    }

    @Test
    void keepsProgressOnStderr() throws Exception {
        Path stack = folder.resolve("stack");
        HandStack.twoStrips(stack);

        assertEquals(0, runFields(stack, folder.resolve("fields")), stderr());

        assertTrue(stderr().contains("Edges: 2025-05-10 (1 of 8)"), stderr());
        assertFalse(stdout().contains("Edges:"), stdout());
    }

    @Test
    void refusesAFolderThatIsNotAStack() throws Exception {
        Path notAStack = Files.createDirectories(folder.resolve("photos"));
        Path fields = folder.resolve("fields");

        assertEquals(1, runFields(notAStack, fields));

        assertTrue(stderr().contains("is not a season stack folder"), stderr());
        assertFalse(Files.exists(fields), "--out is not created");
    }

    @Test
    void refusesAStackWithNoStoredDates() throws Exception {
        Path stack = folder.resolve("stack");
        new HandStack(stack).cloudy("2025-07-20").finish();
        Path fields = folder.resolve("fields");

        assertEquals(1, runFields(stack, fields));

        assertTrue(stderr().contains("has no stored dates, so there is nothing to find fields in"), stderr());
        assertFalse(Files.exists(fields), "nothing is written");
    }

    @Test
    void needsStackAndOut() {
        assertEquals(2, run("fields", "--stack", folder.toString()));
        assertTrue(stderr().contains("Missing option: --out"), stderr());
        assertEquals(2, run("fields", "--out", folder.toString()));
        assertTrue(stderr().contains("Missing option: --stack"), stderr());
    }

    @Test
    void helpListsTheFieldsCommand() {
        assertEquals(0, run("--help"));

        assertTrue(stdout().contains("fields --stack FOLDER --out FOLDER"), stdout());
    }
}
