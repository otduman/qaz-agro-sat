package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class AgreementTest {

    @Test
    void identicalReadingsAgreeExactly() {
        Agreement.Stats s = Agreement.of(new double[] {0.1, 0.4, 0.7}, new double[] {0.1, 0.4, 0.7});

        assertEquals(3, s.pairs());
        assertEquals(0.0, s.bias(), 1e-12);
        assertEquals(0.0, s.medianAbs(), 1e-12);
        assertEquals(1.0, s.slope(), 1e-12);
        assertEquals(0.0, s.intercept(), 1e-12);
    }

    @Test
    void anOffsetShowsAsBias() {
        Agreement.Stats s = Agreement.of(new double[] {0.12, 0.42, 0.72}, new double[] {0.1, 0.4, 0.7});

        assertEquals(0.02, s.bias(), 1e-12);
        assertEquals(0.02, s.medianAbs(), 1e-12);
    }

    @Test
    void aFittedLineRecoversAScaleAndOffset() {
        double[] sentinel = {0.1, 0.2, 0.5, 0.8};
        double[] landsat = new double[4];
        for (int i = 0; i < 4; i++) {
            landsat[i] = 0.9 * sentinel[i] + 0.01;
        }

        Agreement.Stats s = Agreement.of(landsat, sentinel);

        assertEquals(0.9, s.slope(), 1e-9);
        assertEquals(0.01, s.intercept(), 1e-9);
    }

    @Test
    void noPairsGiveNoAgreement() {
        Agreement.Stats s = Agreement.of(new double[0], new double[0]);

        assertEquals(0, s.pairs());
        assertTrue(Double.isNaN(s.bias()));
        assertTrue(Double.isNaN(s.slope()));
    }

    @Test
    void pairsTheNearestSentinelDateWithinADay() {
        LocalDate d = LocalDate.parse("2024-08-10");
        Map<LocalDate, Double> landsat = new TreeMap<>(Map.of(d, 0.5, d.plusDays(5), 0.6, d.plusDays(10), 0.7));
        Map<LocalDate, Double> sentinel = new TreeMap<>(Map.of(d.plusDays(1), 0.45, d.plusDays(4), 0.55,
                d.plusDays(6), 0.65, d.plusDays(12), 0.8));

        List<double[]> pairs = Agreement.pairs(landsat, sentinel, 1);

        assertEquals(2, pairs.size());
        assertEquals(0.5, pairs.get(0)[0]);
        assertEquals(0.45, pairs.get(0)[1]);
        assertEquals(0.6, pairs.get(1)[0]);
        assertEquals(0.55, pairs.get(1)[1], "a tie goes to the earlier date");
    }
}
