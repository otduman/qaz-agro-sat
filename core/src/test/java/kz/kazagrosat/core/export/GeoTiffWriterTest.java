package kz.kazagrosat.core.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.Raster;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFImageReadParam;
import javax.imageio.stream.ImageInputStream;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads the output back with the JDK's own TIFF decoder, so the writer is judged by independent code. */
class GeoTiffWriterTest {

    private static final PixelGrid GRID = new PixelGrid(32641, 460140, 5942540, 10, 3, 2);
    private static final float[] VALUES = {0.5f, Float.NaN, -0.25f, 0.75f, 0.125f, 1.0f};

    @TempDir
    Path folder;

    private Raster raster;
    private TIFFDirectory directory;

    @BeforeEach
    void writeAndReadBack() throws Exception {
        Path file = folder.resolve("ndvi.tif");
        GeoTiffWriter.write(file, VALUES, GRID);

        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("tiff").next();
            reader.setInput(input);
            TIFFImageReadParam keepGdalTags = new TIFFImageReadParam();
            keepGdalTags.setReadUnknownTags(true);
            raster = reader.read(0, keepGdalTags).getRaster();
            directory = TIFFDirectory.createFromMetadata(reader.getImageMetadata(0));
            reader.dispose();
        }
    }

    private TIFFField tag(int number) {
        return directory.getTIFFField(number);
    }

    @Test
    void pixelsSurviveInRowOrder() {
        assertEquals(3, raster.getWidth());
        assertEquals(2, raster.getHeight());
        assertEquals(0.5f, raster.getSampleFloat(0, 0, 0));
        assertTrue(Float.isNaN(raster.getSampleFloat(1, 0, 0)));
        assertEquals(-0.25f, raster.getSampleFloat(2, 0, 0));
        assertEquals(0.75f, raster.getSampleFloat(0, 1, 0));
        assertEquals(1.0f, raster.getSampleFloat(2, 1, 0));
    }

    @Test
    void pixelsAreStoredAsFloats() {
        assertEquals(32, tag(258).getAsInt(0));
        assertEquals(3, tag(339).getAsInt(0), "SampleFormat 3 means IEEE float");
    }

    @Test
    void recordsThePixelSize() {
        assertArrayEquals(new double[] {10, 10, 0}, tag(33550).getAsDoubles());
    }

    @Test
    void tiesTheFirstPixelToItsGroundPosition() {
        assertArrayEquals(new double[] {0, 0, 0, 460140, 5942540, 0}, tag(33922).getAsDoubles());
    }

    @Test
    void namesTheCoordinateSystem() {
        int[] keys = new int[tag(34735).getCount()];
        Arrays.setAll(keys, i -> tag(34735).getAsInt(i));

        assertArrayEquals(new int[] {
                1, 1, 0, 3,            // version 1.1.0, three keys
                1024, 0, 1, 1,         // projected model
                1025, 0, 1, 1,         // pixels are areas
                3072, 0, 1, 32641,     // EPSG code
        }, keys);
    }

    @Test
    void declaresNaNAsNodata() {
        assertEquals("nan", tag(42113).getAsString(0));
    }

    @Test
    void sizeMustMatchTheData() {
        assertThrows(IllegalArgumentException.class,
                () -> GeoTiffWriter.write(folder.resolve("x.tif"), new float[5], GRID));
    }
}
