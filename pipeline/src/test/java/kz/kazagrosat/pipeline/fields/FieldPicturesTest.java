package kz.kazagrosat.pipeline.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.FieldSummary;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FieldPicturesTest {

    private static final int W = 36;
    private static final int H = 30;

    @TempDir
    Path folder;

    private static StackEntry entry(String date, double clearShare, StackEntry.Status status) {
        return new StackEntry(LocalDate.parse(date), "S2B_T41UMV_x", 10, clearShare, status, 0.0001, -0.1, 0);
    }

    @Test
    void clearestDateInTheWindowWinsAndTiesGoToTheEarlier() {
        List<StackEntry> entries = List.of(
                entry("2025-06-30", 0.99, StackEntry.Status.STORED),
                entry("2025-07-05", 0.80, StackEntry.Status.STORED),
                entry("2025-07-12", 0.95, StackEntry.Status.STORED),
                entry("2025-07-20", 0.95, StackEntry.Status.STORED),
                entry("2025-08-01", 0.97, StackEntry.Status.CLOUDY),
                entry("2025-08-16", 1.00, StackEntry.Status.STORED));

        assertEquals(Optional.of(LocalDate.parse("2025-07-12")),
                FieldPictures.clearestIn(entries, MonthDay.of(7, 1), MonthDay.of(8, 15)));
        assertEquals(Optional.empty(),
                FieldPictures.clearestIn(entries, MonthDay.of(9, 10), MonthDay.of(10, 15)));
    }

    /** Region 1 on the left (field), region 2 on the right (fallow) with a 3 x 3 block left out. */
    private FieldMap map() {
        int[] labels = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean leftOut = x >= 25 && x <= 27 && y >= 10 && y <= 12;
                labels[y * W + x] = leftOut ? 0 : x < 18 ? 1 : 2;
            }
        }
        List<Region> regions = List.of(new Region(1, 0, LandLabel.FIELD, 1), new Region(2, 0, LandLabel.FALLOW, 1));
        return new FieldMap(HandStack.GRID, labels, new float[W * H], regions,
                new FieldSummary(List.of(), 2, 0, 0, 0, 0, 0, 0, 0, Map.of()));
    }

    private BufferedImage preview() throws Exception {
        SeasonStack stack = new HandStack(folder.resolve("stack")).stored("2025-07-12", 2000).finish();
        Path png = folder.resolve("preview.png");
        FieldPictures.preview(png, stack, LocalDate.parse("2025-07-12"), map());
        return ImageIO.read(png.toFile());
    }

    @Test
    void outlinesTakeTheColourOfTheLandLabel() throws Exception {
        BufferedImage image = preview();

        assertEquals(W, image.getWidth());
        assertEquals(H, image.getHeight());
        assertEquals(FieldPictures.FIELD_COLOUR, image.getRGB(17, 5) & 0xFFFFFF, "field side of the border");
        assertEquals(0x000000, image.getRGB(18, 5) & 0xFFFFFF, "dark casing across the border");
        assertEquals(FieldPictures.FALLOW_COLOUR, image.getRGB(24, 11) & 0xFFFFFF, "fallow next to the block");
        assertEquals(FieldPictures.LEFT_OUT_COLOUR, image.getRGB(27, 11) & 0xFFFFFF, "left-out pixel at its edge");
    }

    @Test
    void insidePixelsShowThePhoto() throws Exception {
        BufferedImage image = preview();

        // Every band holds DN 2000, reflectance 0.1: (0.1 / 0.32) ^ 0.8 * 255 = 100.56, so grey 101.
        assertEquals(0x656565, image.getRGB(5, 5) & 0xFFFFFF);
        assertEquals(0x656565, image.getRGB(30, 25) & 0xFFFFFF);
    }

    @Test
    void theEdgePictureShadesByStrength() throws Exception {
        float[] edges = {0f, 0.18f, 0.3f, 0.9f, Float.NaN, 0.06f};
        Path png = folder.resolve("edges.png");

        FieldPictures.edges(png, edges, 3, 2);

        BufferedImage image = ImageIO.read(png.toFile());
        assertEquals(0x000000, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x999999, image.getRGB(1, 0) & 0xFFFFFF);
        assertEquals(0xFFFFFF, image.getRGB(2, 0) & 0xFFFFFF);
        assertEquals(0xFFFFFF, image.getRGB(0, 1) & 0xFFFFFF);
        assertEquals(FieldPictures.NO_DATA_COLOUR, image.getRGB(1, 1) & 0xFFFFFF);
        assertEquals(0x333333, image.getRGB(2, 1) & 0xFFFFFF);
    }
}
