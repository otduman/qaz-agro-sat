package kz.kazagrosat.pipeline.landsat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LandsatQaTest {

    private static final int CLEAR_LAND = 1 << 6;
    private static final int WATER = 1 << 7;

    @Test
    void clearLandAndWaterAreClear() {
        assertTrue(LandsatQa.clear(CLEAR_LAND));
        assertTrue(LandsatQa.clear(WATER));
        assertTrue(LandsatQa.clear(21824), "the usual clear-land value with low-confidence bits set");
    }

    @Test
    void eachProblemFlagMakesAPixelUnclear() {
        for (int bit = 0; bit <= 5; bit++) { // fill, dilated cloud, cirrus, cloud, shadow, snow
            assertFalse(LandsatQa.clear(CLEAR_LAND | 1 << bit), "bit " + bit);
        }
    }

    @Test
    void aPixelFlaggedNeitherClearNorWaterIsNotClear() {
        assertFalse(LandsatQa.clear(0));
        assertFalse(LandsatQa.clear(1), "fill");
    }
}
