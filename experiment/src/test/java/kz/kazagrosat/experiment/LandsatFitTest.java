package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LandsatFitTest {

    private static final List<String> LANDSAT = List.of(
            "field,date,satellite,clear_share,ndvi_median,b11,b12,nir,blue",
            "F1,2025-08-10,LC09,0.9,0.60,0.20,0.15,0.30,0.03",
            "F1,2025-08-20,LC08,0.9,0.30,0.30,,0.25,0.05",
            "F2,2025-08-10,LC09,0.3,,,,,");

    private static final List<String> SENTINEL = List.of(
            "field,date,clear_share,ndvi_p25,ndvi_median,ndvi_p75,b11,b12,ndti,nir,brightness,blue",
            "F1,2025-08-09,1.0,0.5,0.58,0.6,0.22,0.16,0.1,0.31,0.1,0.032",
            "F1,2025-08-25,1.0,0.2,0.25,0.3,0.33,0.25,0.1,0.24,0.1,0.06",
            "F2,2025-08-10,1.0,0.5,0.55,0.6,0.21,0.15,0.1,0.30,0.1,0.03");

    @Test
    void pairsEachMeasureOnTheSameFieldAtMostADayApart() {
        Map<String, double[][]> pairs = LandsatFit.pairs(LANDSAT, SENTINEL);

        assertArrayEquals(new double[][] {{0.60, 0.58}}, pairs.get("ndvi_median"), "20 August has no Sentinel-2 date within a day");
        assertArrayEquals(new double[][] {{0.20, 0.22}}, pairs.get("b11"));
        assertArrayEquals(new double[][] {{0.03, 0.032}}, pairs.get("blue"));
        assertEquals(LandsatFit.MEASURES.size(), pairs.size());
    }

    @Test
    void aBlankValueOnEitherSideMakesNoPair() {
        List<String> landsat = List.of(LANDSAT.get(0), "F1,2025-08-10,LC09,0.9,0.60,0.20,,0.30,0.03");

        Map<String, double[][]> pairs = LandsatFit.pairs(landsat, SENTINEL);

        assertEquals(0, pairs.get("b12").length);
        assertEquals(1, pairs.get("b11").length);
    }
}
