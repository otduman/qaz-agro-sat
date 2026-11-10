package kz.kazagrosat.core.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LateScreenTest {

    private static final LocalDate START = LocalDate.parse("2026-09-01");

    /** Ten fields, a clear reading every 5 days for 8 dates; blue 0.07 everywhere unless changed. */
    private static Map<String, List<Reading>> area() {
        Map<String, List<Reading>> byField = new LinkedHashMap<>();
        for (int f = 0; f < 10; f++) {
            List<Reading> rows = new ArrayList<>();
            for (int d = 0; d < 8; d++) {
                rows.add(new Reading(START.plusDays(5L * d), "S2", 1.0, 0.3, 0.25, 0.07, 0.0));
            }
            byField.put("F" + f, rows);
        }
        return byField;
    }

    private static void set(Map<String, List<Reading>> area, int field, int date, double blue, double snow) {
        List<Reading> rows = area.get("F" + field);
        Reading r = rows.get(date);
        rows.set(date, new Reading(r.date(), r.satellite(), r.clear(), r.ndvi(), r.b11(), blue, snow));
    }

    @Test
    void aQuietAreaLosesNoDates() {
        assertTrue(LateScreen.screen(area()).isEmpty());
    }

    @Test
    void aDateWithSnowOnAQuarterOfTheFieldsIsDropped() {
        Map<String, List<Reading>> area = area();
        set(area, 0, 4, 0.07, 0.10);
        set(area, 1, 4, 0.07, 0.30);
        set(area, 2, 4, 0.07, 0.01);

        Map<LocalDate, String> dropped = LateScreen.screen(area);

        assertEquals(List.of(START.plusDays(20)), List.copyOf(dropped.keySet()));
        assertTrue(dropped.values().iterator().next().contains("snow"), dropped.toString());
    }

    @Test
    void snowOnAFewFieldsDropsNoDate() {
        Map<String, List<Reading>> area = area();
        set(area, 0, 4, 0.07, 0.10);
        set(area, 1, 4, 0.07, 0.30);

        assertTrue(LateScreen.screen(area).isEmpty(), "2 of 10 is under a quarter");
    }

    @Test
    void anAreaWideFlashInBlueIsDropped() {
        Map<String, List<Reading>> area = area();
        for (int f = 0; f < 10; f++) {
            set(area, f, 3, 0.10, 0.0);
        }

        Map<LocalDate, String> dropped = LateScreen.screen(area);

        assertEquals(List.of(START.plusDays(15)), List.copyOf(dropped.keySet()));
        assertTrue(dropped.values().iterator().next().contains("blue"), dropped.toString());
    }

    @Test
    void aLastingChangeInBlueIsNotAFlash() {
        Map<String, List<Reading>> area = area();
        for (int f = 0; f < 10; f++) {
            for (int d = 3; d < 8; d++) {
                set(area, f, d, 0.10, 0.0);
            }
        }

        assertTrue(LateScreen.screen(area).isEmpty(), "brighter from then on, as after a harvest of everything");
    }

    @Test
    void datesWithFewClearFieldsAreNeitherJudgedNorUsedAsNeighbours() {
        Map<String, List<Reading>> area = area();
        // Date 3 has a reading on only one field, and that one is bright: too few to call a flash.
        for (int f = 1; f < 10; f++) {
            area.get("F" + f).remove(3);
        }
        set(area, 0, 3, 0.20, 0.0);

        assertTrue(LateScreen.screen(area).isEmpty());
    }
}
