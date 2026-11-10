package kz.kazagrosat.pipeline.landsat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.core.raster.Reflectance;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.stack.StackArea;

/**
 * A season's Landsat scenes of a stack's area, as the {@code landsat} command stores them: one folder per scene
 * holding its 30 m window over the area, {@code index.csv} listing the finished scenes, and
 * {@code landsat.properties} naming the stack's area, season and grid.
 */
public final class LandsatFolder {

    public static final String AREA_FILE = "landsat.properties";
    public static final String INDEX = "index.csv";
    public static final String QA = "qa_pixel";
    /** The bands kept for a clear scene, by their asset names on Planetary Computer. */
    public static final List<String> BANDS = List.of("blue", "green", "red", "nir08", "swir16", "swir22");
    static final String HEADER = "scene,date,satellite,clear_share,status,scale,offset,nodata";

    /** A stored layer: its digital numbers on the scene's own 30 m window. */
    public record Layer(PixelGrid grid, int[] values) {}

    private final Path folder;
    private final StackArea area;
    private final List<LandsatEntry> entries;

    private LandsatFolder(Path folder, StackArea area, List<LandsatEntry> entries) {
        this.folder = folder;
        this.area = area;
        this.entries = List.copyOf(entries);
    }

    public static LandsatFolder open(Path folder) throws IOException {
        StackArea area = StackArea.read(folder, AREA_FILE).orElseThrow(() -> new IOException(
                folder + " is not a Landsat folder: it has no " + AREA_FILE + ". Run the landsat command first."));
        return new LandsatFolder(folder, area, readIndex(folder.resolve(INDEX)));
    }

    /** The stack area these scenes were downloaded for. */
    public StackArea area() {
        return area;
    }

    /** The finished scenes, by date and then scene. */
    public List<LandsatEntry> entries() {
        return entries;
    }

    public static Path layerFile(Path folder, String scene, String layer) {
        return folder.resolve(scene).resolve(layer + ".tif");
    }

    public Layer raw(LandsatEntry entry, String layer) throws IOException, InterruptedException {
        if (entry.status() == LandsatEntry.Status.CLOUDY && !layer.equals(QA)) {
            throw new IllegalArgumentException(entry.scene() + " was cloudy: only its quality layer is stored");
        }
        CogReader reader = CogReader.open(new FileByteSource(layerFile(folder, entry.scene(), layer)));
        PixelGrid grid = reader.grid();
        return new Layer(grid, reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height())));
    }

    /** A band as surface reflectance, NaN for no data. */
    public float[] reflectance(LandsatEntry entry, String band) throws IOException, InterruptedException {
        if (band.equals(QA)) {
            throw new IllegalArgumentException("The quality layer holds flags, not reflectance");
        }
        return Reflectance.fromDigitalNumbers(raw(entry, band).values(), entry.scale(), entry.offset(),
                entry.nodata());
    }

    static List<LandsatEntry> readIndex(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IOException(file + " does not start with the expected header: " + HEADER);
        }
        List<LandsatEntry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(",", -1);
            if (c.length != 8) {
                throw new IOException("Unreadable line in " + file + ": " + line);
            }
            entries.add(new LandsatEntry(c[0], LocalDate.parse(c[1]), c[2], Double.parseDouble(c[3]),
                    LandsatEntry.Status.valueOf(c[4].toUpperCase(Locale.ROOT)), Double.parseDouble(c[5]),
                    Double.parseDouble(c[6]), c[7].isEmpty() ? null : Integer.valueOf(c[7])));
        }
        entries.sort(order());
        return entries;
    }

    /** Writes the index aside and renames it, so a run stopped halfway leaves the old one whole. */
    static void writeIndex(Path file, List<LandsatEntry> entries) throws IOException {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        entries.stream().sorted(order()).forEach(e -> text.append(String.format(Locale.ROOT,
                "%s,%s,%s,%.4f,%s,%s,%s,%s\n", e.scene(), e.date(), e.satellite(), e.clearShare(),
                e.status().name().toLowerCase(Locale.ROOT), Double.toString(e.scale()), Double.toString(e.offset()),
                e.nodata() == null ? "" : e.nodata().toString())));
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, text, StandardCharsets.UTF_8);
        Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Comparator<LandsatEntry> order() {
        return Comparator.comparing(LandsatEntry::date).thenComparing(LandsatEntry::scene);
    }
}
