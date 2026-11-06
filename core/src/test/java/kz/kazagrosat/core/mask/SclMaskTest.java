package kz.kazagrosat.core.mask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SclMaskTest {

    @ParameterizedTest(name = "class {0} usable={1}")
    @CsvSource({
            "0, false",    // no data
            "1, false",    // saturated or defective
            "2, false",    // dark area
            "3, false",    // cloud shadow
            "4, true",     // vegetation
            "5, true",     // bare soil
            "6, true",     // water
            "7, false",    // unclassified
            "8, false",    // cloud, medium probability
            "9, false",    // cloud, high probability
            "10, false",   // thin cirrus
            "11, false",   // snow or ice
    })
    void keepsOnlyClearSurfaceClasses(int sclClass, boolean usable) {
        assertEquals(usable, SclMask.usable(new int[] {sclClass})[0]);
    }

    @Test
    void upsamplingCopiesEachPixelIntoABlock() {
        int[] upsampled = SclMask.upsample(new int[] {1, 2, 3, 4, 5, 6}, 3, 2, 2);

        assertArrayEquals(new int[] {
                1, 1, 2, 2, 3, 3,
                1, 1, 2, 2, 3, 3,
                4, 4, 5, 5, 6, 6,
                4, 4, 5, 5, 6, 6,
        }, upsampled);
    }

    @Test
    void applyingTheMaskBlanksOnlyUnusablePixels() {
        float[] masked = SclMask.apply(new float[] {0.1f, 0.2f, 0.3f}, new boolean[] {true, false, true});

        assertEquals(0.1f, masked[0]);
        assertTrue(Float.isNaN(masked[1]));
        assertEquals(0.3f, masked[2]);
    }

    @Test
    void maskMustMatchTheValuesInSize() {
        assertThrows(IllegalArgumentException.class, () -> SclMask.apply(new float[2], new boolean[3]));
    }
}
