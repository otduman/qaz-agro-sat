package kz.kazagrosat.experiment;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stac.StacItem;

/**
 * Throwaway experiment: does any satellite signal show the harvest cut on real Kostanay fields?
 * It downloads the 2025 season for a few hand-picked fields and writes the numbers and photos.
 */
public final class HarvestSignalExperiment {

    private static final int OPTICAL_THREADS = 4;
    private static final int RADAR_THREADS = 6;

    @FunctionalInterface
    private interface SceneTask<T> {
        List<T> run(StacItem scene) throws IOException, InterruptedException;
    }

    private HarvestSignalExperiment() {}

    public static void main(String[] args) throws Exception {
        Path fieldsFile = Path.of("experiment", "fields.csv");
        Path readingsFile = Path.of("experiment", "photo-readings.csv");
        Path output = Path.of("experiment-output");
        boolean reportOnly = args.length > 0 && args[0].equals("--report-only");
        List<Field> fields = Field.load(fieldsFile);
        Files.createDirectories(output);

        PixelGrid areaGrid = areaGrid(output.resolve("area.csv"));
        if (!reportOnly) {
            download(fields, output, areaGrid);
        }
        Path rain = output.resolve("rain.csv");
        if (!reportOnly || !Files.exists(rain)) {
            Rainfall.download(rain);
        }
        for (Field field : fields) {
            PhotoStrips.write(field, output.resolve("photos"), output.resolve("strips").resolve(field.name() + ".jpg"));
        }
        Report.write(fields, output, readingsFile, areaGrid);
        AnalysisTable.print(fields, output, readingsFile, System.out);
        System.err.println("Done. Open " + output.resolve("index.html").toAbsolutePath());
    }

    /** The study area's grid, looked up once and then kept, so the page can be rebuilt offline. */
    private static PixelGrid areaGrid(Path file) throws IOException, InterruptedException {
        if (Files.exists(file)) {
            String[] p = Files.readString(file).trim().split(",");
            return new PixelGrid(Integer.parseInt(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
        }
        PixelGrid grid = new OpticalPass(List.of(), file).areaGrid(OpticalPass.scenes().getFirst());
        Files.writeString(file, String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%d,%d%n", grid.epsg(), grid.originX(),
                grid.originY(), grid.pixelSize(), grid.width(), grid.height()));
        return grid;
    }

    private static void download(List<Field> fields, Path output, PixelGrid areaGrid)
            throws IOException, InterruptedException {
        System.err.println("Looking for Sentinel-2 scenes...");
        List<StacItem> opticalScenes = OpticalPass.scenes();
        System.err.println("Found " + opticalScenes.size() + " dates. This is the slow part, usually 5 to 15 minutes.");
        OpticalPass optical = new OpticalPass(fields, output.resolve("photos"));
        List<OpticalPass.Row> opticalRows = measureAll(opticalScenes, optical::measure, OPTICAL_THREADS);
        opticalRows.sort(Comparator.comparing(OpticalPass.Row::field).thenComparing(OpticalPass.Row::date));
        writeOptical(output.resolve("optical.csv"), opticalRows);

        System.err.println("Looking for Sentinel-1 radar scenes...");
        List<StacItem> radarScenes = RadarPass.scenes();
        System.err.println("Found " + radarScenes.size() + " dates.");
        RadarPass radar = new RadarPass(fields, areaGrid);
        List<RadarPass.Row> radarRows = measureAll(radarScenes, radar::measure, RADAR_THREADS);
        radarRows.sort(Comparator.comparing(RadarPass.Row::field).thenComparing(RadarPass.Row::date));
        writeRadar(output.resolve("radar.csv"), radarRows);
    }

    private static <T> List<T> measureAll(List<StacItem> scenes, SceneTask<T> task, int threads)
            throws InterruptedException {
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<T> rows = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<T>>> results = new ArrayList<>();
            for (StacItem scene : scenes) {
                results.add(pool.submit(() -> {
                    List<T> measured = task.run(scene);
                    System.err.printf("  %d of %d done: %s%n", done.incrementAndGet(), scenes.size(),
                            OpticalPass.dateOf(scene));
                    return measured;
                }));
            }
            for (int i = 0; i < results.size(); i++) {
                try {
                    rows.addAll(results.get(i).get());
                } catch (ExecutionException e) {
                    failed.incrementAndGet();
                    System.err.println("  Skipped " + scenes.get(i).id() + ": " + e.getCause());
                }
            }
        }
        if (failed.get() > 0) {
            System.err.println("  " + failed.get() + " of " + scenes.size() + " scenes failed and are missing.");
        }
        return rows;
    }

    private static void writeOptical(Path file, List<OpticalPass.Row> rows) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println("field,date,scene,clear,bare,ndvi,ndti,blue,green,red,nir,swir16,swir22");
            for (OpticalPass.Row r : rows) {
                out.println(String.format(Locale.ROOT, "%s,%s,%s,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                        r.field(), r.date(), r.scene(), r.clear(), r.bare(), r.ndvi(), r.ndti(),
                        r.blue(), r.green(), r.red(), r.nir(), r.swir16(), r.swir22()));
            }
        }
    }

    private static void writeRadar(Path file, List<RadarPass.Row> rows) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println("field,date,scene,track,vv,vh");
            for (RadarPass.Row r : rows) {
                out.println(String.format(Locale.ROOT, "%s,%s,%s,%d,%.3f,%.3f",
                        r.field(), r.date(), r.scene(), r.track(), r.vv(), r.vh()));
            }
        }
    }
}
