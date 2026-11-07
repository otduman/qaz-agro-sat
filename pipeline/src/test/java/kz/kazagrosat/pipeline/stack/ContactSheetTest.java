package kz.kazagrosat.pipeline.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Three dates on the 36 x 30 hand-built grid: stored, cloudy, stored. Blue, green and red are 0.1. */
class ContactSheetTest {

    @TempDir
    Path folder;

    private BufferedImage sheet;

    @BeforeEach
    void draw() throws Exception {
        SeasonStack stack = new HandStack(folder.resolve("stack"))
                .stored("2025-07-12", 2000)
                .cloudy("2025-08-03")
                .stored("2025-09-01", 2000)
                .finish();
        Path png = folder.resolve("contact-sheet.png");

        ContactSheet.write(stack, png);

        sheet = ImageIO.read(png.toFile());
    }

    @Test
    void storedDatesShowTheirTrueColour() {
        // Reflectance 0.1 stretched: (0.1 / 0.32)^0.8 * 255 = 100.55, so 101 in every channel.
        int rgb = sheet.getRGB(ContactSheet.GAP + 18, ContactSheet.GAP + 15) & 0xFFFFFF;

        assertEquals(0x656565, rgb, Integer.toHexString(rgb));
    }

    @Test
    void cloudyDatesShowAsGrey() {
        int x = ContactSheet.GAP + (ContactSheet.CELL_MIN_WIDTH + ContactSheet.GAP) + 18;
        int rgb = sheet.getRGB(x, ContactSheet.GAP + 15) & 0xFFFFFF;

        assertEquals(ContactSheet.CLOUDY_GREY, rgb, Integer.toHexString(rgb));
    }

    @Test
    void fitsAShortSeasonOnOneRow() {
        assertTrue(sheet.getHeight() < 2 * (30 + ContactSheet.LABEL_HEIGHT + ContactSheet.GAP),
                "height " + sheet.getHeight());
    }
}
