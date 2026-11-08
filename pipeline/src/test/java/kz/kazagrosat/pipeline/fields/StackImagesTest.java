package kz.kazagrosat.pipeline.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import kz.kazagrosat.core.fields.DateImage;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackImagesTest {

    @TempDir
    java.nio.file.Path folder;

    @Test
    void listsStoredDatesOnly() throws Exception {
        SeasonStack stack = new HandStack(folder)
                .stored("2025-07-12", 2000).cloudy("2025-07-20").stored("2025-08-03", 2000).finish();

        assertEquals(List.of(LocalDate.parse("2025-07-12"), LocalDate.parse("2025-08-03")),
                new StackImages(stack).dates());
        assertEquals(stack.area().grid(), new StackImages(stack).grid());
    }

    @Test
    void computesNdviBrightnessAndClearFromTheStoredLayers() throws Exception {
        LocalDate date = LocalDate.parse("2025-07-12");
        HandStack hand = new HandStack(folder).stored("2025-07-12", 2000);
        int[] red = new int[36 * 30];
        int[] nir = new int[36 * 30];
        Arrays.fill(red, 1500); // reflectance 0.05
        Arrays.fill(nir, 5500); // reflectance 0.45, so NDVI (0.45 - 0.05) / 0.5 = 0.8
        int[] blue = new int[36 * 30];
        Arrays.fill(blue, 2600); // reflectance 0.16; green is 0.10 (DN 2000), so brightness is 0.1033
        hand.layer(date, Layer.RED, red).layer(date, Layer.NIR, nir).layer(date, Layer.BLUE, blue);

        DateImage image = new StackImages(hand.finish()).read(date);

        assertEquals(0.8, image.ndvi()[100], 1e-5);
        assertEquals((0.16 + 0.10 + 0.05) / 3, image.brightness()[100], 1e-5);
        assertTrue(image.clear()[100]);
    }

    @Test
    void upsamplesTheCloudMask() throws Exception {
        LocalDate date = LocalDate.parse("2025-07-12");
        HandStack hand = new HandStack(folder).stored("2025-07-12", 2000);
        int[] scl = new int[18 * 15];
        Arrays.fill(scl, 4);
        scl[2 * 18 + 3] = 9;
        hand.layer(date, Layer.SCL, scl);

        boolean[] clear = new StackImages(hand.finish()).read(date).clear();

        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 36; x++) {
                boolean underCloud = (x == 6 || x == 7) && (y == 4 || y == 5);
                assertEquals(!underCloud, clear[y * 36 + x], "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void aPixelWithAMissingBandIsNotClear() throws Exception {
        LocalDate date = LocalDate.parse("2025-07-12");
        HandStack hand = new HandStack(folder).stored("2025-07-12", 2000);
        int[] green = new int[36 * 30];
        Arrays.fill(green, 2000);
        green[40] = 0; // the nodata value
        hand.layer(date, Layer.GREEN, green);

        DateImage image = new StackImages(hand.finish()).read(date);

        assertFalse(image.clear()[40]);
        assertTrue(image.clear()[41]);
    }
}
