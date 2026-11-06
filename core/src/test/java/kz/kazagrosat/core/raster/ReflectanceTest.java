package kz.kazagrosat.core.raster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReflectanceTest {

    private static final float TOLERANCE = 1e-6f;

    @Test
    void appliesTheScaleAndOffsetFromTheMetadata() {
        float[] reflectance = Reflectance.fromDigitalNumbers(new int[] {2000, 6000}, 0.0001, -0.1, 0);

        assertEquals(0.1f, reflectance[0], TOLERANCE);
        assertEquals(0.5f, reflectance[1], TOLERANCE);
    }

    @Test
    void honoursADifferentOffsetRatherThanAssumingOne() {
        // Scenes from older processing baselines have no offset.
        float[] reflectance = Reflectance.fromDigitalNumbers(new int[] {2000}, 0.0001, 0, 0);

        assertEquals(0.2f, reflectance[0], TOLERANCE);
    }

    @Test
    void nodataBecomesNaN() {
        float[] reflectance = Reflectance.fromDigitalNumbers(new int[] {0, 2000}, 0.0001, -0.1, 0);

        assertTrue(Float.isNaN(reflectance[0]));
        assertEquals(0.1f, reflectance[1], TOLERANCE);
    }

    @Test
    void withoutANodataValueEveryNumberIsData() {
        float[] reflectance = Reflectance.fromDigitalNumbers(new int[] {0}, 0.0001, -0.1, null);

        assertEquals(-0.1f, reflectance[0], TOLERANCE);
    }
}
