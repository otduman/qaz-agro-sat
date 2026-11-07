package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;
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
 * an index. The grid is the FakeEarthSearch area, 36 x 30 pixels at 10 m, unless given.
 */
public final class HandStack {

    public static final BoundingBox BBOX = BoundingBox.parse(FakeEarthSearch.AREA);
    public static final PixelGrid GRID = new PixelGrid(32641, 460140, 5922780, 10, 36, 30);
    public static final PixelGrid MASK_GRID = new PixelGrid(32641, 460140, 5922780, 20, 18, 15);
    public static final double SCALE = 0.0001;
    public static final double OFFSET = -0.1;

    private final Path folder;
    private final PixelGrid grid;
    private final PixelGrid maskGrid;
    private final List<StackEntry> entries = new ArrayList<>();

    public HandStack(Path folder) throws IOException {
        this(folder, GRID.width(), GRID.height());
    }

    /** A stack on a grid of the given even size at 10 m, with the cloud mask at 20 m. */
    public HandStack(Path folder, int width, int height) throws IOException {
        if (width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("The grid size must be even: " + width + " x " + height);
        }
        this.folder = folder;
        this.grid = new PixelGrid(32641, 460140, 5922780, 10, width, height);
        this.maskGrid = new PixelGrid(32641, 460140, 5922780, 20, width / 2, height / 2);
        new StackArea(BBOX, 2025, "T41UMV", grid, maskGrid).write(folder);
    }

    /** A clear date whose bands all hold {@code dn}; the cloud mask says vegetation everywhere. */
    public HandStack stored(String date, int dn) throws IOException {
        LocalDate day = LocalDate.parse(date);
        for (Layer band : Layer.bands()) {
            layer(day, band, filled(band.tenMetre() ? grid : maskGrid, dn));
        }
        layer(day, Layer.SCL, filled(maskGrid, 4));
        entries.add(new StackEntry(day, "S2B_T41UMV_" + date.replace("-", "") + "T070939_L2A",
                1.0, 1.0, StackEntry.Status.STORED, SCALE, OFFSET, 0));
        return this;
    }

    /** Red reflectance in {@link #storedNdvi}; blue and green match it, so brightness is the same everywhere. */
    public static final double FLAT_RED = 0.05;

    /**
     * A clear date whose NDVI at (x, y) is {@code ndviPercent(x, y) / 100}: red, green and blue hold
     * {@link #FLAT_RED} and nir is set to give that NDVI. SWIR holds 0.2; the cloud mask says vegetation.
     */
    public HandStack storedNdvi(String date, IntBinaryOperator ndviPercent) throws IOException {
        LocalDate day = LocalDate.parse(date);
        int flat = dn(FLAT_RED);
        int[] nir = new int[grid.width() * grid.height()];
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                double v = ndviPercent.applyAsInt(x, y) / 100.0;
                nir[y * grid.width() + x] = dn(FLAT_RED * (1 + v) / (1 - v));
            }
        }
        layer(day, Layer.BLUE, filled(grid, flat));
        layer(day, Layer.GREEN, filled(grid, flat));
        layer(day, Layer.RED, filled(grid, flat));
        layer(day, Layer.NIR, nir);
        layer(day, Layer.SWIR16, filled(maskGrid, dn(0.2)));
        layer(day, Layer.SWIR22, filled(maskGrid, dn(0.2)));
        layer(day, Layer.SCL, filled(maskGrid, 4));
        entries.add(new StackEntry(day, "S2B_T41UMV_" + date.replace("-", "") + "T070939_L2A",
                1.0, 1.0, StackEntry.Status.STORED, SCALE, OFFSET, 0));
        return this;
    }

    /** Dates of {@link #twoStrips}, and the wheat strip's NDVI in percent on each. */
    public static final String[] STRIP_DATES = {
            "2025-05-10", "2025-06-01", "2025-06-25", "2025-07-10",
            "2025-07-25", "2025-08-10", "2025-08-25", "2025-09-10"};
    private static final int[] WHEAT_PERCENT = {15, 15, 55, 80, 70, 55, 40, 20};

    /**
     * An 80 x 60 season of 8 clear dates holding two 24 ha strips: wheat for x &lt; 40, bare fallow
     * (NDVI 0.15) for the rest.
     */
    public static SeasonStack twoStrips(Path folder) throws IOException {
        HandStack stack = new HandStack(folder, 80, 60);
        for (int d = 0; d < STRIP_DATES.length; d++) {
            int wheat = WHEAT_PERCENT[d];
            stack.storedNdvi(STRIP_DATES[d], (x, y) -> x < 40 ? wheat : 15);
        }
        return stack.finish();
    }

    /** The digital number that stores this reflectance. */
    public static int dn(double reflectance) {
        return (int) Math.round((reflectance - OFFSET) / SCALE);
    }

    /** A date skipped as cloudy: only its cloud mask, all class 9, is stored. */
    public HandStack cloudy(String date) throws IOException {
        LocalDate day = LocalDate.parse(date);
        layer(day, Layer.SCL, filled(maskGrid, 9));
        entries.add(new StackEntry(day, "S2B_T41UMV_" + date.replace("-", "") + "T070939_L2A",
                75.0, 0.0, StackEntry.Status.CLOUDY, SCALE, OFFSET, 0));
        return this;
    }

    /** Replaces one layer of a date with the given values. */
    public HandStack layer(LocalDate date, Layer layer, int[] values) throws IOException {
        Path file = SeasonStack.layerFile(folder, date, layer);
        Files.createDirectories(file.getParent());
        TiledTiffWriter.write(file, values, layer.tenMetre() ? grid : maskGrid, layer.bits(), 0);
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
