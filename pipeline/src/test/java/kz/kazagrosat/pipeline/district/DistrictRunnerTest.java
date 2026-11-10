package kz.kazagrosat.pipeline.district;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistrictRunnerTest {

    @TempDir
    Path folder;

    private Path district;
    private final List<String> calls = new ArrayList<>();
    private String failOn = null;

    @BeforeEach
    void plan() throws Exception {
        Path boundary = folder.resolve("b.geojson");
        Files.writeString(boundary, """
                {"type":"Polygon","coordinates":[[[69.0,53.6],[69.4,53.6],[69.4,53.8],[69.0,53.8],[69.0,53.6]]]}""");
        district = folder.resolve("district");
        District.plan(boundary, district);
    }

    /** A stand-in for the real commands: records each call and writes what the harvest step would. */
    private int step(String[] args, Path log) throws IOException {
        calls.add(String.join(" ", args));
        Files.writeString(log, "ran " + args[0]);
        String out = args[Arrays.asList(args).indexOf("--out") + 1];
        Files.createDirectories(Path.of(out));
        if (failOn != null && args[0].equals("fields") && out.contains(failOn)) {
            return 1;
        }
        if (args[0].equals("harvest")) {
            Files.writeString(Path.of(out).resolve("summary-2026.txt"), "done");
        }
        return 0;
    }

    private DistrictRunner.Report run(List<String> only) throws Exception {
        return DistrictRunner.run(district, 2026, only, this::step, line -> { });
    }

    @Test
    void runsTheFiveStepsForEachSquareInOrder() throws Exception {
        String square = District.readPlan(district).get(0).id();

        DistrictRunner.Report report = run(List.of(square));

        assertEquals(5, calls.size());
        assertTrue(calls.get(0).startsWith("season --bbox "), calls.get(0));
        assertTrue(calls.get(0).endsWith("--year 2026 --out " + district.resolve(square).resolve("season-2026")));
        assertTrue(calls.get(3).contains("--landsat " + district.resolve(square).resolve("landsat-2026")));
        assertTrue(calls.get(4).startsWith("harvest --series "), calls.get(4));
        assertEquals(List.of(square), report.done());
        assertTrue(Files.readString(district.resolve(square).resolve("season-2026.log")).contains("ran season"));
        List<String> log = Files.readAllLines(district.resolve("run-2026.csv"));
        assertEquals("square,step,seconds,exit,square_mb", log.get(0));
        assertEquals(6, log.size());
    }

    @Test
    void aFinishedSquareIsSkipped() throws Exception {
        String square = District.readPlan(district).get(0).id();
        run(List.of(square));
        calls.clear();

        DistrictRunner.Report again = run(List.of(square));

        assertTrue(calls.isEmpty());
        assertEquals(List.of(square), again.skipped());
    }

    @Test
    void aFailedSquareIsRecordedAndTheRunGoesOn() throws Exception {
        List<String> squares = District.readPlan(district).stream().map(s -> s.id()).limit(2).toList();
        failOn = squares.get(0);

        DistrictRunner.Report report = run(squares);

        assertEquals(List.of(squares.get(0)), report.failed().keySet().stream().toList());
        assertTrue(report.failed().get(squares.get(0)).contains("fields"));
        assertEquals(List.of(squares.get(1)), report.done());
        assertFalse(Files.exists(district.resolve("run.lock")), "the lock is gone after the run");
    }

    @Test
    void refusesToRunTwiceAtOnce() throws Exception {
        Files.writeString(district.resolve("run.lock"), "running");

        IOException error = assertThrows(IOException.class, () -> run(List.of()));

        assertTrue(error.getMessage().contains("run.lock"), error.getMessage());
        assertTrue(calls.isEmpty());
    }

    @Test
    void unknownSquaresAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> run(List.of("E1N1")));
    }
}
