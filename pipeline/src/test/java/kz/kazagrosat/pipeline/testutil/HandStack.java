package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackArea;
import kz.kazagrosat.pipeline.stack.StackEntry;
import kz.kazagrosat.pipeline.stack.StackIndex;

/**
 * Builds a small season stack folder by hand, without any download: the area file, layer files and
 * an index. The grid is the FakeEarthSearch area, 36 x 30 pixels at 10 m.
 */
public final class HandStack {

    public static final BoundingBox BBOX = BoundingBox.parse(FakeEarthSearch.AREA);
    public static final PixelGrid GRID = new PixelGrid(32641, 460140, 5922780, 10, 36, 30);
    public static final PixelGrid MASK_GRID = new PixelGrid(32641, 460140, 5922780, 20, 18, 15);
    public static final double SCALE = 0.0001;
    public static final double OFFSET = -0.1;

    private final Path folder;
    private final List<StackEntry> entries = new ArrayList<>();

    public HandStack(Path folder) throws IOException {
        this.folder = folder;
        new StackArea(BBOX, 2025, "T41UMV", GRID, MASK_GRID).write(folder);
    }

    /** A clear date whose bands all hold {@code dn}; the cloud mask says vegetation everywhere. */
    public HandStack stored(String date, int dn) throws IOException {
        LocalDate day = LocalDate.parse(date);
        for (Layer band : Layer.bands()) {
            layer(day, band, filled(band.tenMetre() ? GRID : MASK_GRID, dn));
        }
        layer(day, Layer.SCL, filled(MASK_GRID, 4));
        entries.add(new StackEntry(day, "S2B_T41UMV_" + date.replace("-", "") + "T070939_L2A",
                1.0, 1.0, StackEntry.Status.STORED, SCALE, OFFSET, 0));
        return this;
    }

    /** A date skipped as cloudy: only its cloud mask, all class 9, is stored. */
    public HandStack cloudy(String date) throws IOException {
        LocalDate day = LocalDate.parse(date);
        layer(day, Layer.SCL, filled(MASK_GRID, 9));
        entries.add(new StackEntry(day, "S2B_T41UMV_" + date.replace("-", "") + "T070939_L2A",
                75.0, 0.0, StackEntry.Status.CLOUDY, SCALE, OFFSET, 0));
        return this;
    }

    /** Replaces one layer of a date with the given values. */
    public HandStack layer(LocalDate date, Layer layer, int[] values) throws IOException {
        Path file = SeasonStack.layerFile(folder, date, layer);
        Files.createDirectories(file.getParent());
        TiledTiffWriter.write(file, values, layer.tenMetre() ? GRID : MASK_GRID, layer.bits(), 0);
        return this;
    }

    /** Writes the index; call after adding every date. */
    public SeasonStack finish() throws IOException {
        StackIndex.write(folder.resolve("index.csv"), entries);
        return SeasonStack.open(folder);
    }

    private static int[] filled(PixelGrid grid, int value) {
        int[] values = new int[grid.width() * grid.height()];
        Arrays.fill(values, value);
        return values;
    }
}
