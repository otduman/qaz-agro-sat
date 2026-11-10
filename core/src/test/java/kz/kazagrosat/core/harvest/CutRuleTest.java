package kz.kazagrosat.core.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CutRuleTest {

    static Reading reading(String date, double ndvi, double b11) {
        return new Reading(LocalDate.parse(date), "S2", 1.0, ndvi, b11, 0.07, 0.0);
    }

    /** Six clear readings five days apart from 1 August, B11 0.22, 0.21, 0.20, then 0.29 three times. */
    static List<Reading> cutAfterThird(double ndvi) {
        List<Reading> rows = new ArrayList<>();
        double[] b11 = {0.22, 0.21, 0.20, 0.29, 0.29, 0.29};
        for (int i = 0; i < b11.length; i++) {
            rows.add(reading(LocalDate.parse("2026-08-01").plusDays(5L * i).toString(), ndvi, b11[i]));
        }
        return rows;
    }

    @Test
    void findsTheBiggestRiseAfterRipening() {
        Optional<CutRule.Step> step = CutRule.biggestRise(cutAfterThird(0.30));

        assertTrue(step.isPresent());
        assertEquals(LocalDate.parse("2026-08-11"), step.get().before());
        assertEquals(LocalDate.parse("2026-08-16"), step.get().after());
        assertEquals(8.0, step.get().rise(), 1e-9, "in reflectance points: 29 - 21");
    }

    @Test
    void readingsBeforeAugustOrStillGreenAreNotRipe() {
        List<Reading> rows = new ArrayList<>();
        rows.add(reading("2026-07-20", 0.20, 0.10));
        rows.addAll(cutAfterThird(0.70));

        assertTrue(CutRule.biggestRise(rows).isEmpty(), "never below NDVI 0.4 from August");
    }

    @Test
    void ripenessStartsAtTheFirstRipeReading() {
        List<Reading> rows = new ArrayList<>(List.of(
                reading("2026-08-01", 0.70, 0.40),
                reading("2026-08-03", 0.70, 0.10)));
        rows.addAll(cutAfterThird(0.30).stream()
                .map(r -> new Reading(r.date().plusDays(10), r.satellite(), r.clear(), r.ndvi(), r.b11(), r.blue(),
                        r.snow()))
                .toList());

        CutRule.Step step = CutRule.biggestRise(rows).orElseThrow();

        assertEquals(LocalDate.parse("2026-08-21"), step.before(), "the green readings are left out");
    }
}
