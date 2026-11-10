package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HlsFmaskTest {

    @Test
    void cloudShadowSnowAndTheirNeighboursAreNotClear() {
        assertFalse(HlsFmask.clear(1 << 1), "cloud");
        assertFalse(HlsFmask.clear(1 << 2), "next to cloud or shadow");
        assertFalse(HlsFmask.clear(1 << 3), "cloud shadow");
        assertFalse(HlsFmask.clear(1 << 4), "snow or ice");
    }

    @Test
    void waterCirrusBitAndAerosolLevelsStayClear() {
        assertTrue(HlsFmask.clear(0));
        assertTrue(HlsFmask.clear(1), "bit 0 is reserved in HLS v2");
        assertTrue(HlsFmask.clear(1 << 5), "water");
        assertTrue(HlsFmask.clear(0b1100_0000), "high aerosol");
    }

    @Test
    void theFillValueIsNotClear() {
        assertFalse(HlsFmask.clear(255));
    }

    @Test
    void aDayCanUseTheHlsRule() {
        LandsatGrid.Day day = new LandsatGrid.Day(2, 1);

        day.add(new int[] {0, 1}, new int[] {0, 1 << 1}, new float[][] {{0.3f, 0.4f}}, HlsFmask::clear);

        assertArrayEquals(new boolean[] {true, false}, day.clear());
    }
}
