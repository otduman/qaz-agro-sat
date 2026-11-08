package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.pipeline.stack.StackArea;
import kz.kazagrosat.pipeline.testutil.HandFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeriesCommandTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Path stack;
    private Path fields;
    private Path series;

    @BeforeEach
    void writeStackAndFields() throws Exception {
        stack = folder.resolve("stack");
        fields = folder.resolve("fields");
        series = folder.resolve("series");
        HandFields.write(HandFields.stack(stack).finish(), fields);
    }

    private int run(String... args) {
        return Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int runSeries(Path stackFolder, Path fieldsFolder) {
        return run("series", "--stack", stackFolder.toString(), "--fields", fieldsFolder.toString(),
                "--out", series.toString());
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void writesTheSeriesAndPrintsASummary() {
        assertEquals(0, runSeries(stack, fields), stderr());

        assertTrue(stdout().contains("Season:  2025, 8 dates"), stdout());
        assertTrue(stdout().contains("Fields:  3 in the series (2 field, 1 fallow), 1 other listed only, "
                + "1 with nothing left after the 20 m shrink"), stdout());
        assertTrue(stdout().contains("Rows:    24, 8 with blank values"), stdout());
        assertTrue(stdout().contains("series-2025.csv"), stdout());
        assertTrue(Files.exists(series.resolve("series-2025.csv")));
        assertTrue(Files.exists(series.resolve("fields-2025.csv")));
        assertTrue(Files.exists(series.resolve("summary-2025.txt")));
    }

    @Test
    void keepsProgressOnStderr() {
        assertEquals(0, runSeries(stack, fields), stderr());

        assertTrue(stderr().contains("Date 1 of 8: 2025-05-10"), stderr());
        assertFalse(stdout().contains("Date 1 of 8"), stdout());
    }

    @Test
    void refusesFieldsFromAnotherSeason() throws Exception {
        Path stack2024 = folder.resolve("stack-2024");
        StackArea area = HandFields.stack(stack2024).finish().area();
        new StackArea(area.bbox(), 2024, area.tile(), area.grid(), area.maskGrid()).write(stack2024);

        assertEquals(1, runSeries(stack2024, fields));

        assertTrue(stderr().contains("holds fields for 2025, not 2024"), stderr());
        assertFalse(Files.exists(series), "--out is not created");
    }

    @Test
    void refusesAFolderThatIsNotAStack() throws Exception {
        Path notAStack = Files.createDirectories(folder.resolve("photos"));

        assertEquals(1, runSeries(notAStack, fields));

        assertTrue(stderr().contains("is not a season stack folder"), stderr());
        assertFalse(Files.exists(series), "--out is not created");
    }

    @Test
    void refusesAMissingFieldsFolder() {
        assertEquals(1, runSeries(stack, folder.resolve("nowhere")));

        assertTrue(stderr().contains("No fields-2025.tif"), stderr());
        assertFalse(Files.exists(series), "--out is not created");
    }

    @Test
    void aMissingOptionIsACommandError() {
        assertEquals(2, run("series", "--stack", stack.toString(), "--out", series.toString()));

        assertTrue(stderr().contains("Missing option: --fields"), stderr());
    }

    @Test
    void theHelpListsTheCommand() {
        assertEquals(0, run("--help"));

        assertTrue(stdout().contains("series --stack FOLDER --fields FOLDER --out FOLDER"), stdout());
    }

    @Test
    void refusesToWriteIntoTheFieldsOrStackFolder() throws Exception {
        java.util.List<String> before;
        try (java.util.stream.Stream<Path> files = Files.list(fields)) {
            before = files.map(p -> p.getFileName().toString()).sorted().toList();
        }

        assertEquals(1, run("series", "--stack", stack.toString(), "--fields", fields.toString(), "--out",
                fields.resolve(".").toString()));
        assertEquals(1, run("series", "--stack", stack.toString(), "--fields", fields.toString(), "--out",
                stack.toString()));

        assertTrue(stderr().contains("would overwrite"), stderr());
        try (java.util.stream.Stream<Path> files = Files.list(fields)) {
            assertEquals(before, files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
}
