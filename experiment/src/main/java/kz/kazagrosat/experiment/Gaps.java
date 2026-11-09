package kz.kazagrosat.experiment;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;

/** The longest stretch without a clear photo inside a window of the season. */
final class Gaps {

    private Gaps() {}

    /** @return the most days between neighbouring clear dates, counting the window's ends as edges */
    static long longest(List<LocalDate> clear, LocalDate from, LocalDate to) {
        LocalDate previous = from;
        long longest = 0;
        for (LocalDate date : new TreeSet<>(clear)) {
            if (date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            longest = Math.max(longest, ChronoUnit.DAYS.between(previous, date));
            previous = date;
        }
        return Math.max(longest, ChronoUnit.DAYS.between(previous, to));
    }

    static List<LocalDate> merge(List<LocalDate> a, List<LocalDate> b) {
        TreeSet<LocalDate> all = new TreeSet<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
