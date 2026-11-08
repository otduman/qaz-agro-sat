package kz.kazagrosat.pipeline.fields;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stack.ContactSheet;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;

/**
 * Pictures for judging field boundaries by eye: outlines over a true-colour photo, and the season edge.
 *
 * <p>An outline is drawn wherever a pixel's region differs from its right or lower neighbour. The
 * pixel takes its region's land-label colour and the pixel across the border is painted black. The
 * bright colour shows on dark crops and the black on pale stubble; no single colour does both.
 */
public final class FieldPictures {

    public static final int FIELD_COLOUR = 0xFFD60A;
    public static final int FALLOW_COLOUR = 0x00E5FF;
    public static final int OTHER_COLOUR = 0xFF4FD8;
    public static final int LEFT_OUT_COLOUR = 0xFFFFFF;
    public static final int CASING_COLOUR = 0x000000;
    /** Season edge shown as full white; stronger edges clip. */
    public static final double EDGE_WHITE = 0.3;
    public static final int NO_DATA_COLOUR = 0x8B0000;

    private FieldPictures() {}

    /** The stored date in the window with the highest clear share; ties go to the earlier date. */
    public static Optional<LocalDate> clearestIn(List<StackEntry> entries, MonthDay from, MonthDay to) {
        return entries.stream()
                .filter(e -> e.status() == StackEntry.Status.STORED)
                .filter(e -> {
                    MonthDay day = MonthDay.from(e.date());
                    return !day.isBefore(from) && !day.isAfter(to);
                })
                .min(Comparator.comparingDouble(StackEntry::clearShare).reversed()
                        .thenComparing(StackEntry::date))
                .map(StackEntry::date);
    }

    /** Outlines of the map's regions over the true-colour photo of one date, at full resolution. */
    public static void preview(Path png, SeasonStack stack, LocalDate date, FieldMap map)
            throws IOException, InterruptedException {
        PixelGrid grid = map.grid();
        int width = grid.width();
        int height = grid.height();
        float[] red = stack.reflectance(date, Layer.RED);
        float[] green = stack.reflectance(date, Layer.GREEN);
        float[] blue = stack.reflectance(date, Layer.BLUE);
        if (red.length != width * height) {
            throw new IllegalArgumentException("The stack's grid differs from the field map's");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                boolean missing = Float.isNaN(red[i]) || Float.isNaN(green[i]) || Float.isNaN(blue[i]);
                int rgb = missing ? 0 : ContactSheet.stretch(red[i]) << 16 | ContactSheet.stretch(green[i]) << 8
                        | ContactSheet.stretch(blue[i]);
                image.setRGB(x, y, rgb);
            }
        }

        int[] labels = map.labels();
        int[] colourOf = new int[map.regions().size() + 1];
        colourOf[0] = LEFT_OUT_COLOUR;
        for (Region region : map.regions()) {
            colourOf[region.id()] = colour(region.land());
        }
        // Casings first, so a colour never gets painted over by a neighbour's casing.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labels[y * width + x];
                if (x < width - 1 && labels[y * width + x + 1] != label) {
                    image.setRGB(x + 1, y, CASING_COLOUR);
                }
                if (y < height - 1 && labels[(y + 1) * width + x] != label) {
                    image.setRGB(x, y + 1, CASING_COLOUR);
                }
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labels[y * width + x];
                boolean border = x < width - 1 && labels[y * width + x + 1] != label
                        || y < height - 1 && labels[(y + 1) * width + x] != label;
                if (border) {
                    image.setRGB(x, y, colourOf[label]);
                }
            }
        }
        ImageIO.write(image, "png", png.toFile());
    }

    /** The season edge in grey, black at 0 and white from {@link #EDGE_WHITE}; dark red where missing. */
    public static void edges(Path png, float[] seasonEdges, int width, int height) throws IOException {
        if (seasonEdges.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " edge values");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float edge = seasonEdges[y * width + x];
                int rgb;
                if (Float.isNaN(edge)) {
                    rgb = NO_DATA_COLOUR;
                } else {
                    int grey = (int) Math.round(Math.clamp(edge / EDGE_WHITE, 0.0, 1.0) * 255);
                    rgb = grey << 16 | grey << 8 | grey;
                }
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", png.toFile());
    }

    private static int colour(LandLabel land) {
        return switch (land) {
            case FIELD -> FIELD_COLOUR;
            case FALLOW -> FALLOW_COLOUR;
            case OTHER -> OTHER_COLOUR;
        };
    }
}
