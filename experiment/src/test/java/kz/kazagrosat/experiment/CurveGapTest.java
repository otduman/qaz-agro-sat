package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CurveGapTest {

    private static final LocalDate DAY = LocalDate.parse("2025-07-01");

    private static Map<LocalDate, Double> curve(double... values) {
        Map<LocalDate, Double> curve = new TreeMap<>();
        for (int i = 0; i < values.length; i++) {
            curve.put(DAY.plusDays(5L * i), values[i]);
        }
        return curve;
    }

    @Test
    void identicalCurvesHaveNoGap() {
        CurveGap.Gap gap = CurveGap.of(curve(0.2, 0.5, 0.8), curve(0.2, 0.5, 0.8));

        assertEquals(0.0, gap.median(), 1e-12);
        assertEquals(3, gap.dates());
    }

    @Test
    void curvesWithNoCommonDateHaveNoGap() {
        Map<LocalDate, Double> later = new TreeMap<>(Map.of(DAY.plusDays(1), 0.5));

        CurveGap.Gap gap = CurveGap.of(curve(0.5), later);

        assertTrue(Double.isNaN(gap.median()));
        assertEquals(0, gap.dates());
    }

    @Test
    void oneDateApartLeavesTheMedianAtZero() {
        CurveGap.Gap gap = CurveGap.of(curve(0.1, 0.2, 0.3, 0.4, 0.5), curve(0.1, 0.2, 0.4, 0.4, 0.5));

        assertEquals(0.0, gap.median(), 1e-12);
        assertEquals(0.1, gap.largest(), 1e-12);
    }

    @Test
    void missingValuesAreNotCommonDates() {
        CurveGap.Gap gap = CurveGap.of(curve(0.1, Double.NaN, 0.3), curve(0.2, 0.2, 0.5));

        assertEquals(2, gap.dates());
        assertEquals(0.15, gap.median(), 1e-12, "the median of 0.1 and 0.2");
    }
}
