package kz.kazagrosat.pipeline.landsat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LandsatAdjustmentTest {

    @TempDir
    Path folder;

    @Test
    void theDefaultIsTheKokshetau2025Fit() {
        LandsatAdjustment fit = LandsatAdjustment.DEFAULT;

        assertEquals(0.880184 * 0.30 + 0.044665, fit.apply(LandsatAdjustment.Measure.B11, 0.30), 1e-9);
        assertEquals(1.037294 * 0.50 - 0.044746, fit.apply(LandsatAdjustment.Measure.NDVI, 0.50), 1e-9);
        assertTrue(fit.source().contains("Kokshetau 2025"), fit.source());
    }

    @Test
    void readsAFitFileAsLandsatFitWritesIt() throws IOException {
        Path file = folder.resolve("landsat-fit.csv");
        Files.writeString(file, """
                measure,slope,intercept,pairs,fitted_on
                ndvi_median,1.0,-0.04,10,somewhere:2024
                b11,0.9,0.05,10,somewhere:2024
                b12,1.0,0.0,10,somewhere:2024
                nir,1.0,0.0,10,somewhere:2024
                blue,0.5,0.01,10,somewhere:2024
                """);

        LandsatAdjustment fit = LandsatAdjustment.read(file);

        assertEquals(0.9 * 0.2 + 0.05, fit.apply(LandsatAdjustment.Measure.B11, 0.2), 1e-12);
        assertEquals(0.5 * 0.1 + 0.01, fit.apply(LandsatAdjustment.Measure.BLUE, 0.1), 1e-12);
        assertTrue(fit.source().contains("landsat-fit.csv"), fit.source());
    }

    @Test
    void aFitFileMissingAMeasureIsRefused() throws IOException {
        Path file = folder.resolve("landsat-fit.csv");
        Files.writeString(file, """
                measure,slope,intercept,pairs,fitted_on
                ndvi_median,1.0,-0.04,10,x
                b11,0.9,0.05,10,x
                """);

        IOException error = assertThrows(IOException.class, () -> LandsatAdjustment.read(file));

        assertTrue(error.getMessage().contains("b12"), error.getMessage());
    }

    @Test
    void aFallingLineIsRefused() throws IOException {
        Path file = folder.resolve("landsat-fit.csv");
        Files.writeString(file, """
                measure,slope,intercept,pairs,fitted_on
                ndvi_median,1.0,0,10,x
                b11,-0.9,0.05,10,x
                b12,1.0,0,10,x
                nir,1.0,0,10,x
                blue,1.0,0,10,x
                """);

        IOException error = assertThrows(IOException.class, () -> LandsatAdjustment.read(file));

        assertTrue(error.getMessage().contains("b11"), error.getMessage());
    }

    @Test
    void noValueStaysNoValue() {
        assertTrue(Double.isNaN(LandsatAdjustment.DEFAULT.apply(LandsatAdjustment.Measure.NIR, Double.NaN)));
    }
}
