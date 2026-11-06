package kz.kazagrosat.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NdviTest {

    @ParameterizedTest
    @CsvSource({
            // red, nir, expected
            "0.10, 0.50, 0.666667",    // dense crop
            "0.30, 0.35, 0.076923",    // bare soil
            "0.05, 0.02, -0.428571",   // water
    })
    void computesTheNormalisedDifference(float red, float nir, float expected) {
        assertEquals(expected, Ndvi.compute(new float[] {red}, new float[] {nir})[0], 1e-5f);
    }

    @Test
    void missingInputGivesMissingOutput() {
        float[] ndvi = Ndvi.compute(new float[] {Float.NaN, 0.1f}, new float[] {0.5f, Float.NaN});

        assertTrue(Float.isNaN(ndvi[0]));
        assertTrue(Float.isNaN(ndvi[1]));
    }

    @Test
    void slightlyNegativeReflectanceCannotPushTheResultOutOfRange() {
        // The -0.1 offset makes very dark pixels negative. Unclamped, (0.2+0.01)/(0.2-0.01) = 1.105.
        float[] ndvi = Ndvi.compute(new float[] {-0.01f, 0.2f}, new float[] {0.2f, -0.01f});

        assertEquals(1.0f, ndvi[0]);
        assertEquals(-1.0f, ndvi[1]);
    }

    @Test
    void noReflectedLightAtAllIsUndefined() {
        float[] ndvi = Ndvi.compute(new float[] {0f, -0.02f}, new float[] {0f, -0.01f});

        assertTrue(Float.isNaN(ndvi[0]));
        assertTrue(Float.isNaN(ndvi[1]));
    }

    @Test
    void bandsMustMatchInSize() {
        assertThrows(IllegalArgumentException.class, () -> Ndvi.compute(new float[1], new float[2]));
    }
}
