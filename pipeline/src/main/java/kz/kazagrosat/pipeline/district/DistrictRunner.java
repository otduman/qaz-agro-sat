package kz.kazagrosat.pipeline.district;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.geo.DistrictPlan;

/**
 * Runs one season over a district's squares, one square at a time: season, fields, landsat, series (with Landsat)
 * and harvest, each into the square's folder. A square whose harvest summary exists is skipped, so a run can be
 * stopped and started again. A failed step ends that square, not the run. Every step's time, exit code and the
 * square's size on disk go into {@code run-<year>.csv}; each step's own output into
 * {@code <square>/<step>-<year>.log}. A {@code run.lock} file keeps a second run out.
 */
public final class DistrictRunner {

    /** One command, as the command line takes it; its output goes to {@code log}. Returns the exit code. */
    @FunctionalInterface
    public interface Step {
        int run(String[] args, Path log) throws IOException, InterruptedException;
    }

    /** Squares finished in this run, skipped as already finished, and failed with the step and code. */
    public record Report(List<String> done, List<String> skipped, Map<String, String> failed) {}

    private DistrictRunner() {}

    public static Report run(Path district, int year, List<String> only, Step step, Consumer<String> progress)
            throws IOException, InterruptedException {
        List<DistrictPlan.Square> squares = District.readPlan(district);
        Set<String> known = squares.stream().map(DistrictPlan.Square::id).collect(Collectors.toSet());
        for (String id : only) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("No square " + id + " in " + district.resolve(District.PLAN));
            }
        }
        List<DistrictPlan.Square> todo = only.isEmpty() ? squares
                : squares.stream().filter(s -> only.contains(s.id())).toList();
        Path lock = district.resolve("run.lock");
        try {
            Files.writeString(lock, "A district run started at " + java.time.Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new IOException(lock + " exists: another district run is going. If none is (check for running"
                    + " java), delete the file and start again.", e);
        }
        try {
            return runSquares(district, year, todo, step, progress);
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    private static Report runSquares(Path district, int year, List<DistrictPlan.Square> todo, Step step,
                                     Consumer<String> progress) throws IOException, InterruptedException {
        Path log = district.resolve("run-" + year + ".csv");
        if (!Files.exists(log)) {
            Files.writeString(log, "square,step,seconds,exit,square_mb" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        List<String> done = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (int i = 0; i < todo.size(); i++) {
            DistrictPlan.Square s = todo.get(i);
            Path square = district.resolve(s.id());
            String count = " (" + (i + 1) + " of " + todo.size() + ")";
            if (Files.isRegularFile(square.resolve("harvest-" + year).resolve("summary-" + year + ".txt"))) {
                skipped.add(s.id());
                progress.accept(s.id() + ": already done" + count);
                continue;
            }
            Files.createDirectories(square);
            String failure = null;
            for (String[] args : steps(s.bbox(), square, year)) {
                progress.accept(s.id() + ": " + args[0] + count + "...");
                long start = System.nanoTime();
                int exit = step.run(args, square.resolve(args[0] + "-" + year + ".log"));
                double seconds = (System.nanoTime() - start) / 1e9;
                Files.writeString(log, String.format(Locale.ROOT, "%s,%s,%.0f,%d,%.0f%n", s.id(), args[0], seconds,
                        exit, size(square) / 1e6), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                if (exit != 0) {
                    failure = args[0] + " exited with " + exit + "; see " + square.resolve(args[0] + "-" + year
                            + ".log");
                    break;
                }
            }
            if (failure == null) {
                done.add(s.id());
                progress.accept(s.id() + ": done" + count);
            } else {
                failed.put(s.id(), failure);
                progress.accept(s.id() + ": failed, " + failure + count);
            }
        }
        return new Report(List.copyOf(done), List.copyOf(skipped), failed);
    }

    static List<String[]> steps(BoundingBox box, Path square, int year) {
        String y = Integer.toString(year);
        String season = square.resolve("season-" + y).toString();
        String fields = square.resolve("fields-" + y).toString();
        String landsat = square.resolve("landsat-" + y).toString();
        String series = square.resolve("series-" + y).toString();
        String harvest = square.resolve("harvest-" + y).toString();
        String bbox = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", box.west(), box.south(), box.east(),
                box.north());
        return List.of(
                new String[] {"season", "--bbox", bbox, "--year", y, "--out", season},
                new String[] {"fields", "--stack", season, "--out", fields},
                new String[] {"landsat", "--stack", season, "--out", landsat},
                new String[] {"series", "--stack", season, "--fields", fields, "--landsat", landsat, "--out", series},
                new String[] {"harvest", "--series", series, "--fields", fields, "--out", harvest});
    }

    private static long size(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }
}
