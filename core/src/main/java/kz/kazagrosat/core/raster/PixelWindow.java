package kz.kazagrosat.core.raster;

/** A rectangle of pixels, counted from the top-left of a grid. */
public record PixelWindow(int col, int row, int width, int height) {

    /** The same ground area on a grid whose pixels are {@code factor} times smaller. */
    public PixelWindow scaled(int factor) {
        return new PixelWindow(col * factor, row * factor, width * factor, height * factor);
    }
}
