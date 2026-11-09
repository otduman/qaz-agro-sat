package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldStudyTest {

    private static List<String> fieldsTable() {
        List<String> lines = new ArrayList<>();
        lines.add("id,land,area_ha,interior_ha,centroid_lat,centroid_lon,in_series");
        for (int i = 1; i <= 40; i++) {
            lines.add(String.format("2025-F%04d,field,%d.0,50.0,53.9,68.8,yes", i, 20 + i * 10));
        }
        for (int i = 41; i <= 50; i++) {
            lines.add(String.format("2025-F%04d,fallow,80.0,50.0,53.9,68.8,yes", i));
        }
        lines.add("2025-F0051,other,90.0,50.0,53.9,68.8,no");
        lines.add("2025-F0052,field,90.0,2.0,53.9,68.8,no");
        return lines;
    }

    @Test
    void picksCropFieldsOfOrdinarySizeAndFallowControls() {
        List<FieldStudy.Pick> picks = FieldStudy.pick(fieldsTable(), 2025, 16, 4);

        assertEquals(20, picks.size());
        assertEquals(16, picks.stream().filter(p -> p.kind().equals("crop")).count());
        assertEquals(4, picks.stream().filter(p -> p.kind().equals("control")).count());
        for (FieldStudy.Pick pick : picks) {
            int number = Integer.parseInt(pick.id().substring(6));
            if (pick.kind().equals("crop")) {
                assertTrue(number >= 1 && number <= 38, pick.id() + ": 30 to 400 ha, in the series");
            } else {
                assertTrue(number >= 41 && number <= 50, pick.id());
            }
        }
    }

    @Test
    void theSameSeedPicksTheSameFields() {
        assertEquals(FieldStudy.pick(fieldsTable(), 7, 16, 4), FieldStudy.pick(fieldsTable(), 7, 16, 4));
    }

    @Test
    void readsAFieldsRowsWithValuesOnly() {
        List<String> series = List.of(
                "field,date,clear_share,ndvi_p25,ndvi_median,ndvi_p75,b11,b12,ndti",
                "F1,2025-08-10,0.9000,0.5,0.6000,0.7,0.2000,0.15,0.1",
                "F1,2025-08-15,0.3000,,,,,,",
                "F2,2025-08-10,1.0000,0.5,0.5000,0.7,0.2500,0.15,0.1");

        List<Analysis.OpticalRow> rows = FieldStudy.rows(series, "F1");

        assertEquals(1, rows.size());
        assertEquals(LocalDate.parse("2025-08-10"), rows.get(0).date());
        assertEquals(0.9, rows.get(0).clear(), 1e-9);
        assertEquals(0.6, rows.get(0).ndvi(), 1e-9);
        assertEquals(0.2, rows.get(0).swir16(), 1e-9);
    }

    @Test
    void landsatRowsAreAdjustedAndNeverReplaceASentinelDate() {
        List<Analysis.OpticalRow> sentinel = List.of(
                new Analysis.OpticalRow("F1", LocalDate.parse("2025-08-10"), 1, 0.6, Double.NaN, 0.20));
        List<String> landsat = List.of(
                "field,date,satellite,clear_share,ndvi_median,b11,b12,nir,blue",
                "F1,2025-08-10,LC09,1.0000,0.9000,0.9000,0.1,0.3,0.03",
                "F1,2025-08-14,LC08,0.9000,0.5000,0.3000,0.1,0.3,0.03",
                "F1,2025-08-20,LC08,0.5000,,,,,",
                "F2,2025-08-14,LC08,0.9000,0.5000,0.3000,0.1,0.3,0.03");
        Map<String, BandFit> fits = Map.of("ndvi_median", new BandFit(1.0, -0.04, 10), "b11", new BandFit(0.9, 0.05, 10));

        List<Analysis.OpticalRow> merged = FieldStudy.withLandsat(sentinel, landsat, fits, "F1");

        assertEquals(2, merged.size());
        assertEquals(0.20, merged.get(0).swir16(), 1e-9, "the Sentinel-2 reading stays");
        assertEquals(LocalDate.parse("2025-08-14"), merged.get(1).date());
        assertEquals(0.46, merged.get(1).ndvi(), 1e-9);
        assertEquals(0.9 * 0.3 + 0.05, merged.get(1).swir16(), 1e-9);
        assertEquals(0.9, merged.get(1).clear(), 1e-9);
    }
}
