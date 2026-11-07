package kz.kazagrosat.pipeline.fields;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import kz.kazagrosat.core.fields.DateImage;
import kz.kazagrosat.core.fields.SeasonImages;
import kz.kazagrosat.core.index.Ndvi;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;

/**
 * A season stack as the field method sees it: the stored dates, each with NDVI from red and nir,
 * brightness as the mean of blue, green and red, and a clear flag where the 20 m cloud mask says
 * vegetation, bare soil or water and all four bands have a value.
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
        boolean[] usable = cloudMask(stack.raw(date, Layer.SCL), maskGrid, grid);

        float[] brightness = new float[red.length];
        boolean[] clear = new boolean[red.length];
        for (int i = 0; i < red.length; i++) {
            brightness[i] = (blue[i] + green[i] + red[i]) / 3f;
            clear[i] = usable[i] && !Float.isNaN(brightness[i]) && !Float.isNaN(nir[i]);
        }
        return new DateImage(date, Ndvi.compute(red, nir), brightness, clear);
    }

    /** The cloud mask's usable flags on the 10 m grid. Both grids share their top-left corner. */
    private static boolean[] cloudMask(int[] classes, PixelGrid maskGrid, PixelGrid grid) throws IOException {
        int factor = (int) Math.round(maskGrid.pixelSize() / grid.pixelSize());
        if (maskGrid.width() * factor < grid.width() || maskGrid.height() * factor < grid.height()) {
            throw new IOException("The cloud mask grid " + maskGrid + " doesn't cover the band grid " + grid);
        }
        int[] upsampled = SclMask.upsample(classes, maskGrid.width(), maskGrid.height(), factor);
        int upWidth = maskGrid.width() * factor;
        int[] window = new int[grid.width() * grid.height()];
        for (int y = 0; y < grid.height(); y++) {
            System.arraycopy(upsampled, y * upWidth, window, y * grid.width(), grid.width());
        }
        return SclMask.usable(window);
    }
}
