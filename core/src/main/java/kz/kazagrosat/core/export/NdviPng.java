package kz.kazagrosat.core.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Renders NDVI as a colour map: blue water, tan soil, then yellow to dark green crops. No data is transparent. */
public final class NdviPng {

    private static final double[] STOPS = {-0.2, 0.0, 0.2, 0.4, 0.6, 0.8, 0.95};
    private static final int[][] COLOURS = {
            {43, 108, 176},
            {181, 162, 122},
            {222, 200, 130},
            {190, 205, 85},
            {110, 175, 70},
            {40, 130, 60},
            {10, 80, 40},
    };

    private NdviPng() {}

    public static void write(Path file, float[] ndvi, int width, int height) throws IOException {
        if (ndvi.length != width * height) {
            throw new IllegalArgumentException(
                    ndvi.length + " values do not fill a " + width + " x " + height + " image");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[ndvi.length];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = Float.isNaN(ndvi[i]) ? 0 : colour(ndvi[i]);
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    private static int colour(float value) {
        int last = STOPS.length - 1;
        if (value <= STOPS[0]) {
            return opaque(COLOURS[0]);
        }
        if (value >= STOPS[last]) {
            return opaque(COLOURS[last]);
        }
        int upper = 1;
        while (value > STOPS[upper]) {
            upper++;
        }
        double fraction = (value - STOPS[upper - 1]) / (STOPS[upper] - STOPS[upper - 1]);
        int[] mixed = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            double low = COLOURS[upper - 1][channel];
            double high = COLOURS[upper][channel];
            mixed[channel] = (int) Math.round(low + fraction * (high - low));
        }
        return opaque(mixed);
    }

    private static int opaque(int[] rgb) {
        return 0xFF000000 | rgb[0] << 16 | rgb[1] << 8 | rgb[2];
    }
}
