package kz.kazagrosat.pipeline.landsat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import org.junit.jupiter.api.Test;

class LandsatGridTest {

    /** A 6 x 3 area at 10 m whose corner sits 15 m inside a 30 m Landsat pixel, as in the real area. */
    private static final PixelGrid AREA = new PixelGrid(32641, 1015, 2985, 10, 6, 3);
    private static final PixelGrid SCENE = new PixelGrid(32641, 1000, 3000, 30, 10, 10);

    @Test
    void eachAreaPixelTakesTheLandsatPixelItsCentreFallsIn() {
        PixelWindow window = LandsatGrid.windowFor(AREA, SCENE);
        int[] lookup = LandsatGrid.lookup(AREA, SCENE, window);

        assertEquals(new PixelWindow(0, 0, 3, 2), window);
        // centres x 1020, 1030, 1040, 1050, 1060, 1070 -> columns 0, 1, 1, 1, 2, 2
        // centres y 2980, 2970, 2960 -> rows 0, 1, 1
        assertArrayEquals(new int[] {0, 1, 1, 1, 2, 2, 3, 4, 4, 4, 5, 5, 3, 4, 4, 4, 5, 5}, lookup);
    }

    @Test
    void pixelsOutsideTheSceneAreMissing() {
        PixelGrid smallScene = new PixelGrid(32641, 1030, 3000, 30, 1, 1);
        PixelWindow window = LandsatGrid.windowFor(AREA, smallScene);
        int[] lookup = LandsatGrid.lookup(AREA, smallScene, window);

        assertEquals(new PixelWindow(0, 0, 1, 1), window);
        assertArrayEquals(new int[] {-1, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}, lookup);
    }

    @Test
    void anAreaOutsideTheSceneHasNoWindow() {
        PixelGrid far = new PixelGrid(32641, 90000, 3000, 30, 10, 10);

        assertEquals(null, LandsatGrid.windowFor(AREA, far));
    }

    @Test
    void aSecondSceneFillsOnlyWhatTheFirstLacks() {
        LandsatDay day = new LandsatDay(4, 1);
        int[] lookup = {0, 1, 2, 3};
        int clear = 1 << 6;
        int cloud = clear | 1 << 3;
        day.add(lookup, new int[] {clear, cloud, cloud, 1}, new float[][] {{0.1f, 0.2f, 0.3f, 0.4f}});
        day.add(lookup, new int[] {clear, clear, cloud, clear}, new float[][] {{0.5f, 0.6f, 0.7f, 0.8f}});

        assertArrayEquals(new boolean[] {true, true, false, true}, day.clear());
        assertArrayEquals(new float[] {0.1f, 0.6f, Float.NaN, 0.8f}, day.band(0));
        assertEquals(2, day.scenes());
    }

    @Test
    void aPixelNoSceneCoversIsNotClear() {
        LandsatDay day = new LandsatDay(2, 1);
        day.add(new int[] {0, -1}, new int[] {1 << 6}, new float[][] {{0.3f}});

        assertTrue(day.clear()[0]);
        assertFalse(day.clear()[1]);
    }
}
