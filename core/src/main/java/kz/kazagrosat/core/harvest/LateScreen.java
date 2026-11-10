package kz.kazagrosat.core.harvest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dates no field's cut may be read from, because something area-wide changed the photo: snow, frost, haze. A cut
 * changes one field; these change all of them at once. Fixed before any volume run
 * (docs/superpowers/specs/2026-11-10-dating-and-district-design.md):
 * <ul>
 *   <li>a date where at least {@link #SNOWY_SHARE} of the fields with a reading carry some snow;</li>
 *   <li>a date whose median blue over all fields differs from both the previous and the next judged date by at
 *       least {@link #FLASH} in the same direction.</li>
 * </ul>
 * Only dates on which at least {@link #MIN_COVER} of the fields have a reading are judged, or used as neighbours:
 * the median of a handful of fields is too noisy.
 */
public final class LateScreen {

    public static final double SNOWY_SHARE = 0.25;
    public static final double FLASH = 0.02;
    public static final double MIN_COVER = 0.2;

    private LateScreen() {}

    /** The dropped dates, in date order, each with its reason. The readings should be the clear ones. */
    public static Map<LocalDate, String> screen(Map<String, List<Reading>> byField) {
        TreeMap<LocalDate, List<Reading>> byDate = new TreeMap<>();
        for (Collection<Reading> rows : byField.values()) {
            for (Reading r : rows) {
                byDate.computeIfAbsent(r.date(), d -> new ArrayList<>()).add(r);
            }
        }
        int fields = byField.size();
        List<LocalDate> judged = new ArrayList<>();
        List<Double> blue = new ArrayList<>();
        Map<LocalDate, String> dropped = new TreeMap<>();
        for (Map.Entry<LocalDate, List<Reading>> e : byDate.entrySet()) {
            List<Reading> rows = e.getValue();
            if (rows.size() < MIN_COVER * fields) {
                continue;
            }
            long snowy = rows.stream().filter(r -> r.snow() > 0).count();
            if (snowy >= SNOWY_SHARE * rows.size()) {
                dropped.put(e.getKey(), String.format(Locale.ROOT, "snow on %d of %d fields", snowy, rows.size()));
                continue;
            }
            judged.add(e.getKey());
            blue.add(CutRule.median(rows.stream().mapToDouble(Reading::blue).toArray()));
        }
        for (int i = 1; i + 1 < judged.size(); i++) {
            double toPrevious = blue.get(i) - blue.get(i - 1);
            double toNext = blue.get(i) - blue.get(i + 1);
            if ((toPrevious >= FLASH && toNext >= FLASH) || (toPrevious <= -FLASH && toNext <= -FLASH)) {
                dropped.put(judged.get(i), String.format(Locale.ROOT,
                        "area-wide flash in blue: median %.3f against %.3f before and %.3f after",
                        blue.get(i), blue.get(i - 1), blue.get(i + 1)));
            }
        }
        return dropped;
    }
}
