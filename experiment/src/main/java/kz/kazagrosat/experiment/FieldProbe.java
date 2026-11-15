package kz.kazagrosat.experiment;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kz.kazagrosat.core.fields.DateImage;
import kz.kazagrosat.core.fields.EdgeStrength;
import kz.kazagrosat.core.fields.FieldParameters;
import kz.kazagrosat.core.fields.LineStrength;
import kz.kazagrosat.core.fields.SeasonEdges;
import kz.kazagrosat.core.fields.Watershed;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.fields.StackImages;
import kz.kazagrosat.pipeline.stack.SeasonStack;

/**
 * Field-detection check of 12 November 2026: writes the field method's in-between results for one season stack,
 * so that merge rules can be tried offline without rerunning the whole method. Little-endian raw files:
 * {@code raw-edges.f32} (season edge), {@code lines.f32} (line strength), {@code edges.f32} (season edge after
 * lifting straight lines), {@code watershed.i32}, and per watershed region (0..n) and date: {@code count.i32}
 * (clear pixels), {@code ndvi.f64} and {@code bright.f64} (sums). {@code meta.txt} holds the sizes and dates.
 *
 * <p>Usage: {@code FieldProbe STACK_FOLDER OUT_FOLDER}
 */
public final class FieldProbe {

    private FieldProbe() {}

    public static void main(String[] args) throws Exception {
        Path stackFolder = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        FieldParameters p = FieldParameters.DEFAULTS;
        StackImages season = new StackImages(SeasonStack.open(stackFolder));
        PixelGrid grid = season.grid();
        int width = grid.width();
        int height = grid.height();
        int pixels = width * height;
        List<LocalDate> dates = List.copyOf(season.dates());

        SeasonEdges gathered = new SeasonEdges(pixels, p.edgeRank());
        for (int d = 0; d < dates.size(); d++) {
            DateImage image = season.read(dates.get(d));
            gathered.add(EdgeStrength.of(image.ndvi(), image.brightness(), image.clear(), width, height,
                    p.brightnessWeight()));
            if (d % 10 == 0) {
                System.err.println("edges " + dates.get(d));
            }
        }
        float[] raw = gathered.result();
        float[] lines = LineStrength.of(raw, width, height, p.lineRadius());
        float[] lifted = raw.clone();
        for (int i = 0; i < pixels; i++) {
            if (lines[i] >= p.lineEdge()) {
                lifted[i] = (float) Math.max(lifted[i], p.markerEdge());
            }
        }
        int[] watershed = Watershed.flood(lifted, width, height, p);
        int regions = 0;
        for (int v : watershed) {
            regions = Math.max(regions, v);
        }
        System.err.println("watershed " + regions);
        int cells = (regions + 1) * dates.size();
        int[] count = new int[cells];
        double[] ndvi = new double[cells];
        double[] bright = new double[cells];
        for (int d = 0; d < dates.size(); d++) {
            DateImage image = season.read(dates.get(d));
            for (int i = 0; i < pixels; i++) {
                int r = watershed[i];
                if (r == 0 || !image.clear()[i] || Float.isNaN(image.ndvi()[i])) {
                    continue;
                }
                int c = r * dates.size() + d;
                count[c]++;
                ndvi[c] += image.ndvi()[i];
                bright[c] += image.brightness()[i];
            }
            if (d % 10 == 0) {
                System.err.println("series " + dates.get(d));
            }
        }
        writeFloats(out.resolve("raw-edges.f32"), raw);
        writeFloats(out.resolve("lines.f32"), lines);
        writeFloats(out.resolve("edges.f32"), lifted);
        writeInts(out.resolve("watershed.i32"), watershed);
        writeInts(out.resolve("count.i32"), count);
        writeDoubles(out.resolve("ndvi.f64"), ndvi);
        writeDoubles(out.resolve("bright.f64"), bright);
        List<String> meta = new ArrayList<>();
        meta.add(String.format(Locale.ROOT, "width=%d", width));
        meta.add(String.format(Locale.ROOT, "height=%d", height));
        meta.add(String.format(Locale.ROOT, "regions=%d", regions));
        meta.add("dates=" + String.join(",", dates.stream().map(LocalDate::toString).toList()));
        Files.write(out.resolve("meta.txt"), meta, StandardCharsets.UTF_8);
        System.err.println("done " + out);
    }

    private static void writeFloats(Path file, float[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values);
        write(file, buffer);
    }

    private static void writeInts(Path file, int[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(values);
        write(file, buffer);
    }

    private static void writeDoubles(Path file, double[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asDoubleBuffer().put(values);
        write(file, buffer);
    }

    private static void write(Path file, ByteBuffer buffer) throws IOException {
        try (OutputStream stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            stream.write(buffer.array());
        }
    }
}
