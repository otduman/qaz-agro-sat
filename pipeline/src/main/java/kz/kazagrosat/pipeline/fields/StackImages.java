package kz.kazagrosat.pipeline.fields;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import kz.kazagrosat.core.fields.DateImage;
import kz.kazagrosat.core.fields.SeasonImages;
import kz.kazagrosat.core.index.Ndvi;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.core.mask.SnowTest;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;

/**
 * A season stack as the field method sees it: the stored dates, each with NDVI from red and nir,
 * brightness as the mean of blue, green and red, and a clear flag. A pixel is clear where the 20 m cloud
 * mask says vegetation, bare soil or water, all four 10 m bands have a value, and {@link SnowTest} finds
 * no snow (the cloud mask counts some snow as bare soil).
 */
public final class StackImages implements SeasonImages {

    private final SeasonStack stack;

    public StackImages(SeasonStack stack) {
        this.stack = stack;
    }

    @Override
    public PixelGrid grid() {
        return stack.area().grid();
    }

    @Override
    public List<LocalDate> dates() {
        return stack.entries().stream()
                .filter(e -> e.status() == StackEntry.Status.STORED)
                .map(StackEntry::date)
                .sorted()
                .toList();
    }

    @Override
    public DateImage read(LocalDate date) throws IOException, InterruptedException {
        PixelGrid grid = grid();
        PixelGrid maskGrid = stack.area().maskGrid();
        float[] blue = stack.reflectance(date, Layer.BLUE);
        float[] green = stack.reflectance(date, Layer.GREEN);
        float[] red = stack.reflectance(date, Layer.RED);
        float[] nir = stack.reflectance(date, Layer.NIR);
        float[] swir16 = toBandGrid(stack.reflectance(date, Layer.SWIR16), maskGrid, grid);
        boolean[] usable = cloudMask(stack.raw(date, Layer.SCL), maskGrid, grid);
        boolean[] snow = SnowTest.snow(green, nir, swir16);

        float[] brightness = new float[red.length];
        boolean[] clear = new boolean[red.length];
        for (int i = 0; i < red.length; i++) {
            brightness[i] = (blue[i] + green[i] + red[i]) / 3f;
            clear[i] = usable[i] && !snow[i] && !Float.isNaN(brightness[i]) && !Float.isNaN(nir[i]);
        }
        return new DateImage(date, Ndvi.compute(red, nir), brightness, clear);
    }

    /** The cloud mask's usable flags on the 10 m grid. Both grids share their top-left corner. */
    public static boolean[] cloudMask(int[] classes, PixelGrid maskGrid, PixelGrid grid) throws IOException {
        int factor = factor(maskGrid, grid);
        int[] upsampled = SclMask.upsample(classes, maskGrid.width(), maskGrid.height(), factor);
        int upWidth = maskGrid.width() * factor;
        int[] window = new int[grid.width() * grid.height()];
        for (int y = 0; y < grid.height(); y++) {
            System.arraycopy(upsampled, y * upWidth, window, y * grid.width(), grid.width());
        }
        return SclMask.usable(window);
    }

    /** Values on the 20 m grid repeated onto the 10 m grid. Both grids share their top-left corner. */
    public static float[] toBandGrid(float[] values, PixelGrid maskGrid, PixelGrid grid) throws IOException {
        int factor = factor(maskGrid, grid);
        float[] out = new float[grid.width() * grid.height()];
        for (int y = 0; y < grid.height(); y++) {
            int row = (y / factor) * maskGrid.width();
            for (int x = 0; x < grid.width(); x++) {
                out[y * grid.width() + x] = values[row + x / factor];
            }
        }
        return out;
    }

    private static int factor(PixelGrid maskGrid, PixelGrid grid) throws IOException {
        int factor = (int) Math.round(maskGrid.pixelSize() / grid.pixelSize());
        if (maskGrid.width() * factor < grid.width() || maskGrid.height() * factor < grid.height()) {
            throw new IOException("The cloud mask grid " + maskGrid + " doesn't cover the band grid " + grid);
        }
        return factor;
    }
}
