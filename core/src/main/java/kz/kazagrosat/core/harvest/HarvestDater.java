package kz.kazagrosat.core.harvest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Dates each field's cut: usable readings (clear, no snow, Landsat only on dates Sentinel-2 lacks, screened dates
 * left out), then the frozen {@link CutRule}, then a status.
 */
public final class HarvestDater {

    /** A cut window longer than this many days is a cloud gap, not a date. */
    public static final int MAX_WINDOW_DAYS = 10;
    /** Fewer usable readings than this from 1 August make a field unclear. */
    public static final int MIN_READINGS = 5;

    private HarvestDater() {}

    public enum Status {
        DATED("dated"),
        CLOUD_GAP("cut in a cloud gap"),
        NO_CUT("no cut found"),
        UNCLEAR("unclear");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param landsat use Landsat readings on dates Sentinel-2 has none
     * @param screen  leave out the dates {@link LateScreen} drops
     */
    public record Options(boolean landsat, boolean screen) {
        public static final Options FULL = new Options(true, true);
    }

    /**
     * One field's cut, between {@code from} and {@code to} (both null unless dated or a cloud gap).
     *
     * @param rise     biggest B11 rise after ripening, in reflectance points; NaN when there was none to compare
     * @param readings usable readings from 1 August
     */
    public record FieldCut(String field, Status status, LocalDate from, LocalDate to, double rise, int readings) {

        public LocalDate middle() {
            return from == null ? null : from.plusDays(windowDays() / 2);
        }

        public int windowDays() {
            return from == null ? 0 : (int) ChronoUnit.DAYS.between(from, to);
        }
    }

    /** The cuts in the order the fields were given, and the dates the screen dropped, with reasons. */
    public record Result(List<FieldCut> cuts, Map<LocalDate, String> screened) {}

    /** Every field's readings, any satellite, any clear share, in any order. */
    public static Result date(Map<String, List<Reading>> rows, Options options) {
        Map<String, List<Reading>> usable = new LinkedHashMap<>();
        rows.forEach((field, readings) -> usable.put(field, usable(readings, options.landsat())));
        Map<LocalDate, String> screened = options.screen() ? LateScreen.screen(usable) : Map.of();
        List<FieldCut> cuts = new ArrayList<>();
        usable.forEach((field, readings) -> {
            List<Reading> kept = readings.stream()
                    .filter(r -> !screened.containsKey(r.date()) && r.snow() <= 0)
                    .toList();
            cuts.add(cut(field, kept));
        });
        return new Result(List.copyOf(cuts), screened);
    }

    /** Clear readings with values, one per date, Sentinel-2 first, in date order. Snowy ones stay for the screen. */
    private static List<Reading> usable(List<Reading> readings, boolean landsat) {
        TreeMap<LocalDate, Reading> byDate = new TreeMap<>();
        for (Reading r : readings) {
            if (r.clear() < CutRule.MIN_CLEAR || Double.isNaN(r.ndvi()) || Double.isNaN(r.b11())) {
                continue;
            }
            boolean sentinel = r.satellite().equals("S2");
            if (!sentinel && (!landsat || byDate.containsKey(r.date()))) {
                continue;
            }
            if (sentinel || !byDate.containsKey(r.date())) {
                byDate.put(r.date(), r);
            }
        }
        return List.copyOf(byDate.values());
    }

    private static FieldCut cut(String field, List<Reading> readings) {
        int count = (int) readings.stream().filter(r -> r.date().getMonthValue() >= 8).count();
        Optional<CutRule.Step> step = CutRule.biggestRise(readings);
        double rise = step.map(CutRule.Step::rise).orElse(Double.NaN);
        if (count < MIN_READINGS || step.isEmpty()) {
            return new FieldCut(field, Status.UNCLEAR, null, null, rise, count);
        }
        if (rise < CutRule.JUMP) {
            return new FieldCut(field, Status.NO_CUT, null, null, rise, count);
        }
        CutRule.Step s = step.get();
        long days = ChronoUnit.DAYS.between(s.before(), s.after());
        Status status = days <= MAX_WINDOW_DAYS ? Status.DATED : Status.CLOUD_GAP;
        return new FieldCut(field, status, s.before(), s.after(), rise, count);
    }
}
