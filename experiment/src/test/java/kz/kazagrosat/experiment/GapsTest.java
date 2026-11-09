package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class GapsTest {

    private static final LocalDate FROM = LocalDate.parse("2024-08-01");
    private static final LocalDate TO = LocalDate.parse("2024-08-31");

    @Test
    void noClearDateLeavesTheWholeWindow() {
        assertEquals(30, Gaps.longest(List.of(), FROM, TO));
    }

    @Test
    void theLongestStretchCountsTheWindowEdges() {
        List<LocalDate> clear = List.of(LocalDate.parse("2024-08-10"), LocalDate.parse("2024-08-20"));

        assertEquals(11, Gaps.longest(clear, FROM, TO), "20 to 31 August");
    }

    @Test
    void datesOutsideTheWindowDontCount() {
        List<LocalDate> clear = List.of(LocalDate.parse("2024-07-30"), LocalDate.parse("2024-08-16"),
                LocalDate.parse("2024-09-02"));

        assertEquals(15, Gaps.longest(clear, FROM, TO));
    }

    @Test
    void mergingTwoListsShortensTheGaps() {
        List<LocalDate> a = List.of(LocalDate.parse("2024-08-16"));
        List<LocalDate> b = List.of(LocalDate.parse("2024-08-08"), LocalDate.parse("2024-08-24"));

        assertEquals(8, Gaps.longest(Gaps.merge(a, b), FROM, TO));
    }
}
