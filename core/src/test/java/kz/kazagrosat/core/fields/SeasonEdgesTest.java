package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeasonEdgesTest {

    private static float[] one(float value) {
        return new float[] {value};
    }

    @Test
    void keepsTheThirdHighestValue() {
        SeasonEdges season = new SeasonEdges(1, 3);
        for (float value : new float[] {0.1f, 0.5f, 0.3f, 0.9f, 0.2f}) {
            season.add(one(value));
        }

        assertEquals(0.3f, season.result()[0], 0.0f);
    }

    @Test
    void keepsTheValueOfEachPixelApart() {
        SeasonEdges season = new SeasonEdges(2, 2);
        season.add(new float[] {0.1f, 0.7f});
        season.add(new float[] {0.4f, 0.6f});
        season.add(new float[] {0.2f, 0.8f});

        float[] result = season.result();
        assertEquals(0.2f, result[0], 0.0f);
        assertEquals(0.7f, result[1], 0.0f);
    }

    @Test
    void skipsMissingValues() {
        SeasonEdges season = new SeasonEdges(1, 3);
        for (float value : new float[] {0.9f, Float.NaN, 0.5f, Float.NaN, 0.2f}) {
            season.add(one(value));
        }

        assertEquals(0.2f, season.result()[0], 0.0f);
    }

    @Test
    void isMissingWithFewerValuesThanTheRank() {
        SeasonEdges season = new SeasonEdges(1, 3);
        season.add(one(0.4f));
        season.add(one(Float.NaN));
        season.add(one(0.6f));

        assertTrue(Float.isNaN(season.result()[0]));
    }

    @Test
    void rejectsARankBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonEdges(4, 0));
    }

    @Test
    void rejectsADateOfTheWrongSize() {
        SeasonEdges season = new SeasonEdges(4, 3);
        assertThrows(IllegalArgumentException.class, () -> season.add(new float[3]));
    }
}
