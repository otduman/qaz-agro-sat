package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.landsat.LandsatEntry;
import kz.kazagrosat.pipeline.landsat.LandsatFolder;
import kz.kazagrosat.pipeline.stack.StackArea;

/**
 * A Landsat folder written by hand for a stack's area, as the {@code landsat} command would leave it. Scenes lie on a
 * 30 m grid whose corner is the area's, so Landsat column {@code c} holds area columns {@code 3c} to {@code 3c + 2}.
 * Every band of a scene holds one reflectance everywhere: {@link #RED} and so on.
 */
public final class HandLandsat {

    public static final double SCALE = 0.0000275;
    public static final double OFFSET = -0.2;
    public static final int CLEAR = 21824;
    public static final int CLOUD = (1 << 6) | (1 << 3);
    public static final int SNOW = (1 << 6) | (1 << 5);
    public static final double BLUE = 0.04;
    public static final double GREEN = 0.07;
    public static final double RED = 0.05;
    public static final double NIR = 0.30;
    public static final double SWIR16 = 0.25;
    public static final double SWIR22 = 0.18;

    private final Path folder;
    private final StackArea area;
    private final PixelGrid grid;
    private final List<LandsatEntry> entries = new ArrayList<>();

    public HandLandsat(Path folder, StackArea area) {
        this.folder = folder;
        this.area = area;
        PixelGrid g = area.grid();
        this.grid = new PixelGrid(g.epsg(), g.originX(), g.originY(), 30, (g.width() + 2) / 3, (g.height() + 2) / 3);
    }

    public PixelGrid grid() {
        return grid;
    }

    /** The digital number that stores this reflectance. */
    public static int dn(double reflectance) {
        return (int) Math.round((reflectance - OFFSET) / SCALE);
    }

    /** The reflectance a band holding {@link #dn(double)} of {@code reflectance} comes back as. */
    public static float stored(double reflectance) {
        return (float) (dn(reflectance) * SCALE + OFFSET);
    }

    /** A stored scene whose quality flags are {@code qa(column, row)} on the 30 m grid. */
    public HandLandsat scene(String id, String date, IntBinaryOperator qa) throws IOException {
        Path sceneFolder = folder.resolve(id);
        Files.createDirectories(sceneFolder);
        int[] flags = new int[grid.width() * grid.height()];
        int clear = 0;
        for (int i = 0; i < flags.length; i++) {
            flags[i] = qa.applyAsInt(i % grid.width(), i / grid.width());
        }
        for (int y = 0; y < area.grid().height(); y++) {
            for (int x = 0; x < area.grid().width(); x++) {
                int f = flags[(y / 3) * grid.width() + x / 3];
                clear += (f & 0b11_1111) == 0 && (f & 0b1100_0000) != 0 ? 1 : 0;
            }
        }
        TiledTiffWriter.write(sceneFolder.resolve("qa_pixel.tif"), flags, grid, 16, 1);
        double[] values = {BLUE, GREEN, RED, NIR, SWIR16, SWIR22};
        for (int b = 0; b < values.length; b++) {
            int[] dn = new int[flags.length];
            java.util.Arrays.fill(dn, dn(values[b]));
            TiledTiffWriter.write(sceneFolder.resolve(LandsatFolder.BANDS.get(b) + ".tif"), dn, grid, 16, 0);
        }
        double share = (double) clear / (area.grid().width() * area.grid().height());
        entries.add(new LandsatEntry(id, LocalDate.parse(date), id.substring(0, 4), share, LandsatEntry.Status.STORED,
                SCALE, OFFSET, 0));
        return this;
    }

    /** A scene skipped as cloudy: only its quality layer, cloud everywhere. */
    public HandLandsat cloudy(String id, String date) throws IOException {
        Path sceneFolder = folder.resolve(id);
        Files.createDirectories(sceneFolder);
        int[] flags = new int[grid.width() * grid.height()];
        java.util.Arrays.fill(flags, CLOUD);
        TiledTiffWriter.write(sceneFolder.resolve("qa_pixel.tif"), flags, grid, 16, 1);
        entries.add(new LandsatEntry(id, LocalDate.parse(date), id.substring(0, 4), 0, LandsatEntry.Status.CLOUDY,
                SCALE, OFFSET, 0));
        return this;
    }

    /** Writes the area file and the index; call after adding every scene. */
    public LandsatFolder finish() throws IOException {
        area.write(folder, LandsatFolder.AREA_FILE);
        StringBuilder index = new StringBuilder("scene,date,satellite,clear_share,status,scale,offset,nodata\n");
        for (LandsatEntry e : entries) {
            index.append(e.scene()).append(',').append(e.date()).append(',').append(e.satellite()).append(',')
                    .append(e.clearShare()).append(',').append(e.status().name().toLowerCase()).append(',')
                    .append(e.scale()).append(',').append(e.offset()).append(',').append(e.nodata()).append('\n');
        }
        Files.writeString(folder.resolve(LandsatFolder.INDEX), index);
        return LandsatFolder.open(folder);
    }
}
