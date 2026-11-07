package kz.kazagrosat.core.fields;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * A made-up season for tests: 12 dates every 14 days from 2025-05-05 on a 160 x 120 grid at 10 m, with
 * NDVI and clear flags from per-pixel functions and brightness 0.1 everywhere.
 */
final class SyntheticSeason implements SeasonImages {

    static final int WIDTH = 160;
    static final int HEIGHT = 120;
    static final PixelGrid GRID = new PixelGrid(32641, 460140, 5942540, 10, WIDTH, HEIGHT);
    static final List<LocalDate> DATES = seasonDates();

    @FunctionalInterface
    interface Ndvi {
        float at(int x, int y, int dateIndex);
    }

    @FunctionalInterface
    interface Clear {
        boolean at(int x, int y, int dateIndex);
    }

    private final Ndvi ndvi;
    private Clear clear = (x, y, d) -> true;

    SyntheticSeason(Ndvi ndvi) {
        this.ndvi = ndvi;
    }

    SyntheticSeason clear(Clear clear) {
        this.clear = clear;
        return this;
    }

    @Override
    public PixelGrid grid() {
        return GRID;
    }

    @Override
    public List<LocalDate> dates() {
        return DATES;
    }

    @Override
    public DateImage read(LocalDate date) {
        int d = DATES.indexOf(date);
        if (d < 0) {
            throw new IllegalArgumentException("No date " + date);
        }
        float[] values = new float[WIDTH * HEIGHT];
        boolean[] clearFlags = new boolean[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = ndvi.at(x, y, d);
                clearFlags[y * WIDTH + x] = clear.at(x, y, d);
            }
        }
        float[] brightness = new float[WIDTH * HEIGHT];
        Arrays.fill(brightness, 0.1f);
        return new DateImage(date, values, brightness, clearFlags);
    }

    /** 0.15 until 1 June, up to 0.8 by 10 July, down to 0.3 by 1 September, and 0.2 after. */
    static float wheat(LocalDate date) {
        int year = date.getYear();
        LocalDate sown = LocalDate.of(year, 6, 1);
        LocalDate peak = LocalDate.of(year, 7, 10);
        LocalDate ripe = LocalDate.of(year, 9, 1);
        if (date.isBefore(sown)) {
            return 0.15f;
        }
        if (date.isBefore(peak)) {
            return (float) (0.15 + 0.65 * ChronoUnit.DAYS.between(sown, date) / ChronoUnit.DAYS.between(sown, peak));
        }
        if (!date.isAfter(ripe)) {
            return (float) (0.8 - 0.5 * ChronoUnit.DAYS.between(peak, date) / ChronoUnit.DAYS.between(peak, ripe));
        }
        return 0.2f;
    }

    static float wheat(int dateIndex) {
        return wheat(DATES.get(dateIndex));
    }

    /** Wheat sown 14 days later. */
    static float lateWheat(int dateIndex) {
        return wheat(DATES.get(dateIndex).minusDays(14));
    }

    static final float FALLOW = 0.15f;
    static final float WATER = -0.2f;

    /** Uniform noise in -amplitude..amplitude, the same for the same pixel and date on every read. */
    static float noise(int x, int y, int dateIndex, double amplitude) {
        long h = 0x9E3779B97F4A7C15L * (x + 1) ^ 0xC2B2AE3D27D4EB4FL * (y + 1) ^ 0x165667B19E3779F9L * (dateIndex + 1);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        double unit = (h >>> 11) / (double) (1L << 53);
        return (float) ((unit * 2 - 1) * amplitude);
    }

    private static List<LocalDate> seasonDates() {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            dates.add(LocalDate.of(2025, 5, 5).plusDays(14L * i));
        }
        return List.copyOf(dates);
    }
}
