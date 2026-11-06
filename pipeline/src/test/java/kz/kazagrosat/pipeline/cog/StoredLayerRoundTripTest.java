package kz.kazagrosat.pipeline.cog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A stored layer must come back from disk bit for bit, through the same reader used for remote files. */
class StoredLayerRoundTripTest {

    @TempDir
    Path folder;

    @Test
    void sixteenBitValuesComeBackUnchanged() throws Exception {
        // 300 x 270 leaves partial tiles on the right and bottom edges.
        PixelGrid grid = new PixelGrid(32641, 460140, 5922780, 10, 300, 270);
        int[] values = new int[300 * 270];
        for (int y = 0; y < 270; y++) {
            for (int x = 0; x < 300; x++) {
                values[y * 300 + x] = (x * 7919 + y * 104729) % 65536;
            }
        }
        Path file = folder.resolve("red.tif");
        TiledTiffWriter.write(file, values, grid, 16, 0);

        CogReader reader = CogReader.open(new FileByteSource(file));
        assertEquals(grid, reader.grid());
        assertArrayEquals(values, reader.readWindow(new PixelWindow(0, 0, 300, 270)));

        int[] crossing = reader.readWindow(new PixelWindow(250, 250, 40, 20));
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                assertEquals(values[(250 + y) * 300 + 250 + x], crossing[y * 40 + x], "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void eightBitValuesComeBackUnchanged() throws Exception {
        PixelGrid grid = new PixelGrid(32641, 460140, 5922780, 20, 18, 15);
        int[] values = new int[18 * 15];
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < 18; x++) {
                values[y * 18 + x] = (x + y) % 12;
            }
        }
        Path file = folder.resolve("scl.tif");
        TiledTiffWriter.write(file, values, grid, 8, 0);

        CogReader reader = CogReader.open(new FileByteSource(file));
        assertEquals(grid, reader.grid());
        assertArrayEquals(values, reader.readWindow(new PixelWindow(0, 0, 18, 15)));
    }

    @Test
    void compressesSmoothBandsWellBelowTheirRawSize() throws Exception {
        PixelGrid grid = new PixelGrid(32641, 460140, 5922780, 10, 512, 512);
        int[] values = new int[512 * 512];
        for (int i = 0; i < values.length; i++) {
            values[i] = 1000 + (i % 512) / 64;
        }
        Path file = folder.resolve("smooth.tif");
        TiledTiffWriter.write(file, values, grid, 16, 0);

        assertTrue(Files.size(file) < 512 * 512 * 2 / 4, "file is " + Files.size(file) + " bytes");
    }
}
