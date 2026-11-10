package kz.kazagrosat.core.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarvestDaterTest {

    private static final LocalDate START = LocalDate.parse("2026-08-01");

    private static Reading s2(LocalDate date, double ndvi, double b11) {
        return new Reading(date, "S2", 1.0, ndvi, b11, 0.07, 0.0);
    }

    /** A clear reading every {@code step} days from 1 August; B11 0.22, 0.21, 0.20, then 0.29. */
    private static List<Reading> cut(int readings, int step) {
        List<Reading> rows = new ArrayList<>();
        for (int i = 0; i < readings; i++) {
            double b11 = i < 3 ? 0.22 - 0.01 * i : 0.29;
            rows.add(s2(START.plusDays((long) step * i), 0.3, b11));
        }
        return rows;
    }

    private static HarvestDater.FieldCut only(Map<String, List<Reading>> rows, HarvestDater.Options options) {
        return HarvestDater.date(rows, options).cuts().get(0);
    }

    @Test
    void aCutBetweenCloseReadingsIsDated() {
        HarvestDater.FieldCut cut = only(Map.of("F1", cut(6, 5)), HarvestDater.Options.FULL);

        assertEquals(HarvestDater.Status.DATED, cut.status());
        assertEquals(LocalDate.parse("2026-08-11"), cut.from());
        assertEquals(LocalDate.parse("2026-08-16"), cut.to());
        assertEquals(LocalDate.parse("2026-08-13"), cut.middle());
        assertEquals(5, cut.windowDays());
        assertEquals(8.0, cut.rise(), 1e-9);
        assertEquals(6, cut.readings());
    }

    @Test
    void aCutAcrossALongGapIsACloudGap() {
        HarvestDater.FieldCut cut = only(Map.of("F1", cut(6, 12)), HarvestDater.Options.FULL);

        assertEquals(HarvestDater.Status.CLOUD_GAP, cut.status());
        assertEquals(12, cut.windowDays());
    }

    @Test
    void aFlatFieldHasNoCut() {
        List<Reading> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(s2(START.plusDays(5L * i), 0.3, 0.20 + 0.001 * i));
        }

        HarvestDater.FieldCut cut = only(Map.of("F1", rows), HarvestDater.Options.FULL);

        assertEquals(HarvestDater.Status.NO_CUT, cut.status());
        assertNull(cut.from());
    }

    @Test
    void tooFewReadingsOrNeverRipeIsUnclear() {
        List<Reading> green = cut(6, 5).stream()
                .map(r -> new Reading(r.date(), r.satellite(), r.clear(), 0.7, r.b11(), r.blue(), r.snow()))
                .toList();
        Map<String, List<Reading>> rows = new LinkedHashMap<>();
        rows.put("few", cut(4, 5));
        rows.put("green", green);

        List<HarvestDater.FieldCut> cuts = HarvestDater.date(rows, HarvestDater.Options.FULL).cuts();

        assertEquals(HarvestDater.Status.UNCLEAR, cuts.get(0).status(), "4 readings from 1 August");
        assertEquals(HarvestDater.Status.UNCLEAR, cuts.get(1).status(), "never below NDVI 0.4");
    }

    @Test
    void cloudyAndSnowyReadingsAreNotUsed() {
        List<Reading> rows = new ArrayList<>(cut(6, 5));
        rows.add(new Reading(START.plusDays(12), "S2", 0.5, 0.3, 0.40, 0.07, 0.0));
        rows.add(new Reading(START.plusDays(13), "S2", 1.0, 0.3, 0.40, 0.07, 0.2));

        HarvestDater.FieldCut cut = only(Map.of("F1", rows), HarvestDater.Options.FULL);

        assertEquals(LocalDate.parse("2026-08-16"), cut.to());
        assertEquals(6, cut.readings());
    }

    @Test
    void landsatFillsOnlyDatesSentinelLacksAndCanBeLeftOut() {
        List<Reading> rows = new ArrayList<>(cut(6, 5));
        rows.add(new Reading(START.plusDays(10), "LC09", 1.0, 0.3, 0.40, 0.07, 0.0));
        rows.add(new Reading(START.plusDays(13), "LC08", 1.0, 0.3, 0.29, 0.07, 0.0));

        HarvestDater.FieldCut with = only(Map.of("F1", rows), HarvestDater.Options.FULL);
        HarvestDater.FieldCut without = only(Map.of("F1", rows), new HarvestDater.Options(false, true));

        assertEquals(LocalDate.parse("2026-08-11"), with.from(), "the Landsat reading on a Sentinel-2 date is ignored");
        assertEquals(LocalDate.parse("2026-08-14"), with.to(), "the Landsat date narrows the window");
        assertEquals(LocalDate.parse("2026-08-16"), without.to());
    }

    @Test
    void screenedDatesAreUsedForNoField() {
        Map<String, List<Reading>> rows = new LinkedHashMap<>();
        for (int f = 0; f < 4; f++) {
            List<Reading> field = new ArrayList<>(cut(6, 5));
            // A snowy date on every field: snow is flagged on all, so the whole date goes.
            field.add(new Reading(START.plusDays(13), "S2", 1.0, 0.3, 0.10, 0.12, f < 2 ? 0.0 : 0.3));
            field.sort((a, b) -> a.date().compareTo(b.date()));
            rows.put("F" + f, field);
        }

        HarvestDater.Result result = HarvestDater.date(rows, HarvestDater.Options.FULL);

        assertTrue(result.screened().containsKey(START.plusDays(13)), result.screened().toString());
        assertEquals(LocalDate.parse("2026-08-16"), result.cuts().get(0).to(),
                "field F0 had no snow flag itself, but the date is gone");
        HarvestDater.FieldCut unscreened = HarvestDater.date(rows, new HarvestDater.Options(true, false)).cuts().get(0);
        assertEquals(LocalDate.parse("2026-08-14"), unscreened.from(), "without the screen the dark date is used");
    }
}
