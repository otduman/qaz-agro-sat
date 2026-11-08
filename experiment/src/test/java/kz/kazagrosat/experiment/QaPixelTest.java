package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QaPixelTest {

    private static final int CLEAR_LAND = 1 << 6;
    private static final int WATER = 1 << 7;

    @Test
    void clearLandAndWaterAreClear() {
        assertTrue(QaPixel.clear(CLEAR_LAND));
        assertTrue(QaPixel.clear(WATER));
        assertTrue(QaPixel.clear(21824), "the usual clear-land value with low-confidence bits set");
    }

    @Test
    void eachProblemFlagMakesAPixelUnclear() {
        for (int bit = 0; bit <= 5; bit++) { // fill, dilated cloud, cirrus, cloud, shadow, snow
            assertFalse(QaPixel.clear(CLEAR_LAND | 1 << bit), "bit " + bit);
        }
    }

    @Test
    void aPixelFlaggedNeitherClearNorWaterIsNotClear() {
        assertFalse(QaPixel.clear(0));
        assertFalse(QaPixel.clear(1), "fill");
    }
}
