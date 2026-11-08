package kz.kazagrosat.core.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextureTest {

    private static final int SIZE = 60;
    private static final int[] ALL = IntStream.range(0, SIZE * SIZE).toArray();
    private static final boolean[] USABLE = usable(SIZE * SIZE);

    private static boolean[] usable(int n) {
        boolean[] usable = new boolean[n];
        Arrays.fill(usable, true);
        return usable;
    }

    /**
     * Stripes whose lines run {@code bearing} degrees east of north, with a period of 6 pixels across
     * them. North is up, so the image row number grows southwards.
     */
    private static float[] stripes(double bearing) {
        double b = Math.toRadians(bearing);
        float[] brightness = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double across = x * Math.cos(b) + y * Math.sin(b);
                brightness[y * SIZE + x] = (float) (0.2 + 0.05 * Math.sin(2 * Math.PI * across / 6));
            }
        }
        return brightness;
    }

    private static double angleGap(double a, double b) {
        double gap = Math.abs(a - b) % 180;
        return Math.min(gap, 180 - gap);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 30, 45, 90, 135})
    void stripesGiveHighLineStrength(double bearing) {
        Texture.Measures m = Texture.of(stripes(bearing), USABLE, ALL, SIZE, SIZE);

        assertTrue(m.lineStrength() > 0.9, "strength " + m.lineStrength());
        assertTrue(angleGap(m.lineDirection(), bearing) < 2.0, "direction " + m.lineDirection());
        assertTrue(m.lineDirection() >= 0 && m.lineDirection() < 180, "direction " + m.lineDirection());
    }

    @Test
    void speckleGivesLowLineStrength() {
        Random random = new Random(42);
        float[] brightness = new float[SIZE * SIZE];
        for (int i = 0; i < brightness.length; i++) {
            brightness[i] = 0.15f + 0.1f * random.nextFloat();
        }

        Texture.Measures m = Texture.of(brightness, USABLE, ALL, SIZE, SIZE);

        assertTrue(m.lineStrength() < 0.1, "strength " + m.lineStrength());
        assertTrue(Double.isNaN(m.lineDirection()), "no direction under 0.05: " + m.lineStrength());
        assertTrue(m.roughness() > 0.1, "roughness " + m.roughness());
    }

    @Test
    void aFlatFieldHasNoRoughness() {
        float[] brightness = new float[SIZE * SIZE];
        Arrays.fill(brightness, 0.2f);

        Texture.Measures m = Texture.of(brightness, USABLE, ALL, SIZE, SIZE);

        assertTrue(m.roughness() < 1e-6, "roughness " + m.roughness());
        assertEquals(0.0, m.lineStrength());
        assertTrue(Double.isNaN(m.lineDirection()));
    }

    @Test
    void roughnessIgnoresOverallBrightness() {
        float[] brightness = stripes(30);
        float[] twice = new float[brightness.length];
        for (int i = 0; i < brightness.length; i++) {
            twice[i] = 2 * brightness[i];
        }

        double once = Texture.of(brightness, USABLE, ALL, SIZE, SIZE).roughness();

        assertTrue(once > 0.05, "roughness " + once);
        assertEquals(once, Texture.of(twice, USABLE, ALL, SIZE, SIZE).roughness(), 1e-6);
    }

    @Test
    void onlyPixelsWhoseNeighboursAreAllUsableCount() {
        boolean[] usable = new boolean[SIZE * SIZE];
        for (int y = 10; y < 20; y++) {
            for (int x = 10; x < 20; x++) {
                usable[y * SIZE + x] = true;
            }
        }

        Texture.Measures m = Texture.of(stripes(0), usable, ALL, SIZE, SIZE);

        assertEquals(64, m.pixels(), "8 x 8 inside the usable 10 x 10, and the image edge never counts");
    }

    @Test
    void onlyTheListedPixelsCount() {
        int[] listed = IntStream.range(0, SIZE).map(x -> 30 * SIZE + x).toArray();

        assertEquals(SIZE - 2, Texture.of(stripes(0), USABLE, listed, SIZE, SIZE).pixels());
    }

    @Test
    void tooFewPixelsGiveNoTexture() {
        boolean[] usable = new boolean[SIZE * SIZE];
        for (int y = 10; y < 17; y++) {
            for (int x = 10; x < 17; x++) {
                usable[y * SIZE + x] = true; // 7 x 7, so 25 pixels with all neighbours usable
            }
        }

        Texture.Measures m = Texture.of(stripes(0), usable, ALL, SIZE, SIZE);

        assertEquals(25, m.pixels());
        assertTrue(Double.isNaN(m.roughness()));
        assertTrue(Double.isNaN(m.lineStrength()));
        assertTrue(Double.isNaN(m.lineDirection()));
    }
}
