package kz.kazagrosat.pipeline.stack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonStackTest {

    private static final LocalDate JULY = LocalDate.of(2025, 7, 12);
    private static final LocalDate AUGUST = LocalDate.of(2025, 8, 3);

    @TempDir
    Path folder;

    @Test
    void readsRawValuesOfAStoredLayer() throws Exception {
        int[] red = new int[36 * 30];
        for (int i = 0; i < red.length; i++) {
            red[i] = 1500 + i;
        }
        SeasonStack stack = new HandStack(folder).stored("2025-07-12", 2000).layer(JULY, Layer.RED, red).finish();

        assertArrayEquals(red, stack.raw(JULY, Layer.RED));
        assertEquals(18 * 15, stack.raw(JULY, Layer.SWIR16).length);
    }

    @Test
    void appliesTheStoredScaleAndOffset() throws Exception {
        int[] red = new int[36 * 30];
        red[0] = 2000;
        red[1] = 0;
        red[2] = 3500;
        SeasonStack stack = new HandStack(folder).stored("2025-07-12", 2000).layer(JULY, Layer.RED, red).finish();

        float[] reflectance = stack.reflectance(JULY, Layer.RED);

        assertEquals(0.1f, reflectance[0], 1e-6f);
        assertTrue(Float.isNaN(reflectance[1]), "DN 0 is no data");
        assertEquals(0.25f, reflectance[2], 1e-6f);
    }

    @Test
    void refusesBandsOfACloudyDateButReadsItsMask() throws Exception {
        SeasonStack stack = new HandStack(folder).stored("2025-07-12", 2000).cloudy("2025-08-03").finish();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> stack.raw(AUGUST, Layer.RED));
        assertTrue(error.getMessage().contains("cloudy"), error.getMessage());
        assertEquals(9, stack.raw(AUGUST, Layer.SCL)[0]);
    }

    @Test
    void refusesAFolderThatIsNotAStack() throws Exception {
        Path empty = Files.createDirectories(folder.resolve("empty"));

        IOException error = assertThrows(IOException.class, () -> SeasonStack.open(empty));
        assertTrue(error.getMessage().contains("not a season stack"), error.getMessage());
    }

    @Test
    void refusesReflectanceOfTheCloudMask() throws Exception {
        SeasonStack stack = new HandStack(folder).stored("2025-07-12", 2000).finish();

        assertThrows(IllegalArgumentException.class, () -> stack.reflectance(JULY, Layer.SCL));
    }
}
