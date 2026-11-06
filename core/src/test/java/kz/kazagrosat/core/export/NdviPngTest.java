package kz.kazagrosat.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NdviPngTest {

    @TempDir
    Path folder;

    private BufferedImage render(float[] ndvi, int width, int height) throws Exception {
        Path file = folder.resolve("ndvi.png");
        NdviPng.write(file, ndvi, width, height);
        return ImageIO.read(file.toFile());
    }

    private static Color at(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y), true);
    }

    @Test
    void keepsTheRasterSizeAndPixelOrder() throws Exception {
        BufferedImage image = render(new float[] {0.8f, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0.8f}, 3, 2);

        assertEquals(3, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(255, at(image, 0, 0).getAlpha());
        assertEquals(255, at(image, 2, 1).getAlpha());
        assertEquals(0, at(image, 1, 0).getAlpha());
    }

    @Test
    void missingPixelsAreTransparent() throws Exception {
        assertEquals(0, at(render(new float[] {Float.NaN}, 1, 1), 0, 0).getAlpha());
    }

    @Test
    void denseCropIsGreen() throws Exception {
        Color color = at(render(new float[] {0.8f}, 1, 1), 0, 0);

        assertTrue(color.getGreen() > color.getRed() && color.getGreen() > color.getBlue(), color.toString());
    }

    @Test
    void bareSoilIsWarm() throws Exception {
        Color color = at(render(new float[] {0.1f}, 1, 1), 0, 0);

        assertTrue(color.getRed() > color.getGreen() && color.getGreen() > color.getBlue(), color.toString());
    }

    @Test
    void waterIsBlue() throws Exception {
        Color color = at(render(new float[] {-0.3f}, 1, 1), 0, 0);

        assertTrue(color.getBlue() > color.getRed() && color.getBlue() > color.getGreen(), color.toString());
    }

    @Test
    void higherNdviIsGreenerAcrossTheCropRange() throws Exception {
        BufferedImage image = render(new float[] {0.3f, 0.5f, 0.7f}, 3, 1);

        double low = at(image, 0, 0).getRed();
        double middle = at(image, 1, 0).getRed();
        double high = at(image, 2, 0).getRed();
        assertTrue(low > middle && middle > high, "red fades as NDVI rises: " + low + " " + middle + " " + high);
    }

    @Test
    void valuesBeyondTheRampUseItsEndColours() throws Exception {
        BufferedImage image = render(new float[] {1.0f, 0.95f, -1.0f, -0.2f}, 4, 1);

        assertEquals(at(image, 1, 0), at(image, 0, 0));
        assertEquals(at(image, 3, 0), at(image, 2, 0));
    }

    @Test
    void sizeMustMatchTheData() {
        assertThrows(IllegalArgumentException.class,
                () -> NdviPng.write(folder.resolve("x.png"), new float[5], 2, 2));
    }
}
