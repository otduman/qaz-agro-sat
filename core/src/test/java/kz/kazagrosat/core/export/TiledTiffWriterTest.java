package kz.kazagrosat.core.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TiledTiffWriterTest {

    private static final PixelGrid SMALL = new PixelGrid(32641, 460140, 5922780, 20, 4, 3);

    @TempDir
    Path folder;

    @Test
    void rejectsBitDepthsOtherThan8And16() {
        Path file = folder.resolve("layer.tif");
        assertThrows(IllegalArgumentException.class,
                () -> TiledTiffWriter.write(file, new int[12], SMALL, 12, 0));
    }

    @Test
    void rejectsValuesThatDoNotFillTheGrid() {
        Path file = folder.resolve("layer.tif");
        assertThrows(IllegalArgumentException.class,
                () -> TiledTiffWriter.write(file, new int[10], SMALL, 16, 0));
    }

    @Test
    void rejectsValuesTooLargeForTheBitDepth() {
        Path file = folder.resolve("layer.tif");
        int[] tooBig = new int[12];
        tooBig[5] = 256;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TiledTiffWriter.write(file, tooBig, SMALL, 8, 0));
        assertTrue(error.getMessage().contains("256"), error.getMessage());

        int[] negative = new int[12];
        negative[0] = -1;
        assertThrows(IllegalArgumentException.class,
                () -> TiledTiffWriter.write(file, negative, SMALL, 16, 0));
    }

    @Test
    void startsWithALittleEndianTiffHeader() throws Exception {
        Path file = folder.resolve("layer.tif");
        TiledTiffWriter.write(file, new int[12], SMALL, 16, 0);

        byte[] head = Files.readAllBytes(file);
        assertArrayEquals(new byte[] {'I', 'I', 42, 0}, new byte[] {head[0], head[1], head[2], head[3]});
    }

    /**
     * The JDK's own TIFF decoder is independent code, so it judges the tiling, deflate and predictor.
     * It only handles the predictor for 8-bit values; 16-bit values are checked through CogReader.
     */
    @Test
    void theJdkDecoderReadsTheSameValues() throws Exception {
        PixelGrid grid = new PixelGrid(32641, 460140, 5922780, 10, 300, 270);
        int[] values = new int[300 * 270];
        for (int y = 0; y < 270; y++) {
            for (int x = 0; x < 300; x++) {
                values[y * 300 + x] = (x * 31 + y * 17) % 256;
            }
        }
        Path file = folder.resolve("layer.tif");
        TiledTiffWriter.write(file, values, grid, 8, 0);

        Raster raster;
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("tiff").next();
            reader.setInput(input);
            raster = reader.read(0).getRaster();
            reader.dispose();
        }
        assertArrayEquals(values, raster.getSamples(0, 0, 300, 270, 0, new int[300 * 270]));
    }
}
