package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.FieldSummary;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.pipeline.fields.FieldOutputs;
import kz.kazagrosat.pipeline.stack.SeasonStack;

/**
 * A season of 8 clear dates on an 80 x 60 grid with four regions, and their field map written as the
 * {@code fields} command would:
 * <ul>
 *   <li>1, field: x 0-39, y 0-39; NDVI {@code (40 + x % 5) / 100}, so its interior's quartiles are
 *       0.41, 0.42 and 0.43;</li>
 *   <li>2, fallow: x 40-79, y 0-39; NDVI 0.15;</li>
 *   <li>3, other: x 0-79, y 40-55; NDVI 0.30;</li>
 *   <li>4, field: x 0-79, y 56-59, only 4 pixels high, so nothing is left after a 20 m shrink.</li>
 * </ul>
 * Brightness is {@link HandStack#FLAT_RED} everywhere and both SWIR bands 0.2.
 */
public final class HandFields {

    public static final int WIDTH = 80;
    public static final int HEIGHT = 60;

    private HandFields() {}

    public static int region(int x, int y) {
        if (y >= 56) {
            return 4;
        }
        if (y >= 40) {
            return 3;
        }
        return x < 40 ? 1 : 2;
    }

    private static int ndviPercent(int x, int y) {
        return switch (region(x, y)) {
            case 1 -> 40 + x % 5;
            case 2 -> 15;
            case 3 -> 30;
            default -> 50;
        };
    }

    /** The stack, unfinished, so a test can still change layers of its dates. */
    public static HandStack stack(Path folder) throws IOException {
        HandStack stack = new HandStack(folder, WIDTH, HEIGHT);
        for (String date : HandStack.STRIP_DATES) {
            stack.storedNdvi(date, HandFields::ndviPercent);
        }
        return stack;
    }

    /** Writes the field map of the four regions into {@code folder}. */
    public static void write(SeasonStack stack, Path folder) throws IOException, InterruptedException {
        int[] labels = new int[WIDTH * HEIGHT];
        int[] pixels = new int[5];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                labels[y * WIDTH + x] = region(x, y);
                pixels[region(x, y)]++;
            }
        }
        List<Region> regions = new ArrayList<>();
        regions.add(new Region(1, pixels[1], LandLabel.FIELD, 1.0));
        regions.add(new Region(2, pixels[2], LandLabel.FALLOW, 1.0));
        regions.add(new Region(3, pixels[3], LandLabel.OTHER, Double.NaN));
        regions.add(new Region(4, pixels[4], LandLabel.FIELD, 1.0));
        List<LocalDate> dates = Arrays.stream(HandStack.STRIP_DATES).map(LocalDate::parse).toList();
        FieldMap map = new FieldMap(stack.area().grid(), labels, new float[labels.length], regions,
                new FieldSummary(dates, 4, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        FieldOutputs.write(stack, map, folder);
    }
}
