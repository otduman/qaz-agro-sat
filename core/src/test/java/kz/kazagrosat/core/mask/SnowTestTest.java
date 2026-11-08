package kz.kazagrosat.core.mask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnowTestTest {

    private static boolean snow(double green, double nir, double swir16) {
        return SnowTest.snow(new float[] {(float) green}, new float[] {(float) nir},
                new float[] {(float) swir16})[0];
    }

    @Test
    void snowIsNotClear() {
        assertTrue(snow(0.6, 0.55, 0.1), "snow index (0.6 - 0.1) / 0.7 = 0.71");
    }

    @Test
    void waterIsNotSnow() {
        assertFalse(snow(0.08, 0.03, 0.01), "a high snow index, but nir is under 0.11");
    }

    @Test
    void wheatIsNotSnow() {
        assertFalse(snow(0.08, 0.4, 0.2), "a negative snow index");
    }

    @Test
    void nirMustReachTheThresholdAndTheIndexMustReach0point4() {
        // index (0.71 - 0.3) / 1.01 = 0.406, nir 0.11 exactly
        assertTrue(snow(0.71, 0.11, 0.3));
        assertFalse(snow(0.71, 0.1099, 0.3));
        assertFalse(snow(0.69, 0.5, 0.3), "index 0.39 / 0.99 = 0.394");
    }

    @Test
    void aMissingValueIsNotSnow() {
        assertArrayEquals(new boolean[] {false, false, false},
                SnowTest.snow(new float[] {Float.NaN, 0.6f, 0.6f}, new float[] {0.55f, Float.NaN, 0.55f},
                        new float[] {0.1f, 0.1f, Float.NaN}));
    }

    @Test
    void refusesArraysOfDifferentLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> SnowTest.snow(new float[2], new float[2], new float[3]));
    }
}
