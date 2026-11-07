package kz.kazagrosat.pipeline.stack;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.core.raster.Reflectance;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;

/**
 * A season of Sentinel-2 dates for one area, kept on disk by {@link SeasonStackBuilder}. Every layer
 * of every date covers the same area on the same grid, so pixels line up across dates.
 */
public final class SeasonStack {

    static final String INDEX = "index.csv";

    private final Path folder;
    private final StackArea area;
    private final List<StackEntry> entries;

    private SeasonStack(Path folder, StackArea area, List<StackEntry> entries) {
        this.folder = folder;
        this.area = area;
        this.entries = entries;
    }

    public static SeasonStack open(Path folder) throws IOException {
        StackArea area = StackArea.read(folder).orElseThrow(() -> new IOException(
                folder + " is not a season stack folder: it has no " + StackArea.FILE));
        return new SeasonStack(folder, area, StackIndex.read(folder.resolve(INDEX)));
    }

    /** Where a layer of a date lives inside a stack folder. */
    public static Path layerFile(Path folder, LocalDate date, Layer layer) {
        return folder.resolve(date.toString()).resolve(layer.fileName());
    }

    public StackArea area() {
        return area;
    }

    /** The finished dates, in date order. */
    public List<StackEntry> entries() {
        return entries;
    }

    /** A layer's stored digital numbers, row by row over the whole area. */
    public int[] raw(LocalDate date, Layer layer) throws IOException, InterruptedException {
        StackEntry entry = entry(date);
        if (entry.status() == StackEntry.Status.CLOUDY && layer != Layer.SCL) {
            throw new IllegalArgumentException(date + " was skipped as cloudy: only its cloud mask is stored");
        }
        PixelGrid expected = layer.tenMetre() ? area.grid() : area.maskGrid();
        CogReader reader = CogReader.open(new FileByteSource(layerFile(folder, date, layer)));
        if (!reader.grid().equals(expected)) {
            throw new IOException(layer.fileName() + " of " + date + " is on grid " + reader.grid()
                    + " instead of " + expected);
        }
        return reader.readWindow(new PixelWindow(0, 0, expected.width(), expected.height()));
    }

    /** A band as surface reflectance, using the scale and offset stored for its date; NaN for no data. */
    public float[] reflectance(LocalDate date, Layer layer) throws IOException, InterruptedException {
        if (layer == Layer.SCL) {
            throw new IllegalArgumentException("The cloud mask holds classes, not reflectance");
        }
        StackEntry entry = entry(date);
        return Reflectance.fromDigitalNumbers(raw(date, layer), entry.scale(), entry.offset(), entry.nodata());
    }

    private StackEntry entry(LocalDate date) {
        return entries.stream().filter(e -> e.date().equals(date)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("The stack in " + folder + " has no finished date " + date));
    }
}
