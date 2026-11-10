package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.pipeline.testutil.HandFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarvestCommandTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Path fields;
    private Path series;
    private Path harvest;

    @BeforeEach
    void writeSeries() throws Exception {
        Path stack = folder.resolve("stack");
        fields = folder.resolve("fields");
        series = folder.resolve("series");
        harvest = folder.resolve("harvest");
        HandFields.write(HandFields.stack(stack).finish(), fields);
        assertEquals(0, run("series", "--stack", stack.toString(), "--fields", fields.toString(), "--out",
                series.toString()), err.toString(StandardCharsets.UTF_8));
        out.reset();
    }

    private int run(String... args) {
        return Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void datesTheFieldsAndPrintsTheCounts() {
        int code = run("harvest", "--series", series.toString(), "--fields", fields.toString(), "--out",
                harvest.toString());

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        // The hand field's NDVI never passes 0.43, so it grew no crop; the thin strip has no readings at all.
        assertTrue(stdout().contains("Season:  2025, 2 fields: 0 dated, 0 cut in a cloud gap, 0 no cut found, "
                + "1 unclear, 1 no crop seen"), stdout());
        assertTrue(stdout().contains("Screened: 0 dates"), stdout());
        assertTrue(Files.exists(harvest.resolve("harvest-2025.csv")));
        assertTrue(Files.exists(harvest.resolve("summary-2025.txt")));
    }

    @Test
    void refusesToWriteIntoTheSeries() {
        int code = run("harvest", "--series", series.toString(), "--fields", fields.toString(), "--out",
                series.toString());

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("would overwrite"));
    }

    @Test
    void needsTheFieldsFolder() {
        assertEquals(2, run("harvest", "--series", series.toString(), "--out", harvest.toString()));
    }
}
