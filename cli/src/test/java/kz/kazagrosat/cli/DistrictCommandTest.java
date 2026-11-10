package kz.kazagrosat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistrictCommandTest {

    @TempDir
    Path folder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Path boundary;

    @BeforeEach
    void writeBoundary() throws Exception {
        boundary = folder.resolve("b.geojson");
        Files.writeString(boundary, """
                {"type":"Polygon","coordinates":[[[69.0,53.6],[69.4,53.6],[69.4,53.8],[69.0,53.8],[69.0,53.6]]]}""");
    }

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void plansTheSquares() throws Exception {
        int code = run("district", "plan", "--boundary", boundary.toString(), "--out", folder.resolve("d").toString());

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        long squares = Files.readAllLines(folder.resolve("d").resolve("squares.csv")).size() - 1;
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Squares: " + squares + " of about 20 x 20 km"),
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runRefusesAnUnknownSquare() {
        run("district", "plan", "--boundary", boundary.toString(), "--out", folder.resolve("d").toString());

        int code = run("district", "run", "--district", folder.resolve("d").toString(), "--year", "2026",
                "--squares", "E1N1");

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("No square E1N1"));
    }

    @Test
    void joinWorksBeforeAnySquareIsDone() throws Exception {
        run("district", "plan", "--boundary", boundary.toString(), "--out", folder.resolve("d").toString());

        int code = run("district", "join", "--district", folder.resolve("d").toString(), "--year", "2026");

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Joined:  0 squares, 0 fields"),
                out.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(folder.resolve("d").resolve("district-summary-2026.txt")));
    }

    @Test
    void districtNeedsASubcommand() {
        assertEquals(2, run("district", "--out", "x"));
    }
}
