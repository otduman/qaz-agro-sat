package kz.kazagrosat.pipeline.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackArea;
import kz.kazagrosat.pipeline.testutil.HandFields;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeriesBuilderTest {

    private static final int MASK_WIDTH = HandFields.WIDTH / 2;
    private static final int MASK_HEIGHT = HandFields.HEIGHT / 2;

    @TempDir
    Path folder;

    private Series build(HandStack hand) throws Exception {
        SeasonStack stack = hand.finish();
        Path fields = folder.resolve("fields");
        HandFields.write(stack, fields);
        return SeriesBuilder.build(stack, fields, line -> { });
    }

    private static SeriesRow row(Series series, String field, String date) {
        return series.rows().stream()
                .filter(r -> r.field().equals(field) && r.date().equals(LocalDate.parse(date)))
                .findFirst().orElseThrow();
    }

    /** A cloud mask of class {@code maskClass} in mask columns below {@code columns}, vegetation elsewhere. */
    private static int[] maskWithLeftColumns(int columns, int maskClass) {
        int[] scl = new int[MASK_WIDTH * MASK_HEIGHT];
        for (int i = 0; i < scl.length; i++) {
            scl[i] = i % MASK_WIDTH < columns ? maskClass : 4;
        }
        return scl;
    }

    @Test
    void oneRowPerFieldAndDate() throws Exception {
        Series series = build(HandFields.stack(folder.resolve("stack")));

        assertEquals(8, series.dates().size());
        assertEquals(3 * 8, series.rows().size(), "fields 1, 2 and 4; the other region gets none");
        assertEquals("2025-F0001", series.rows().get(0).field());
        assertEquals(LocalDate.parse(HandStack.STRIP_DATES[0]), series.rows().get(0).date());
        assertEquals(LocalDate.parse(HandStack.STRIP_DATES[1]), series.rows().get(1).date(),
                "each field's dates in order");
    }

    @Test
    void valuesMatchHandComputedMedians() throws Exception {
        HandStack hand = HandFields.stack(folder.resolve("stack"));
        LocalDate date = LocalDate.parse("2025-07-10");
        int[] swir16 = new int[MASK_WIDTH * MASK_HEIGHT];
        int[] swir22 = new int[MASK_WIDTH * MASK_HEIGHT];
        Arrays.fill(swir16, HandStack.dn(0.25));
        Arrays.fill(swir22, HandStack.dn(0.15));
        hand.layer(date, Layer.SWIR16, swir16).layer(date, Layer.SWIR22, swir22);

        Series series = build(hand);
        SeriesRow wheat = row(series, "2025-F0001", "2025-07-10");

        assertEquals(1.0, wheat.clearShare(), 1e-9);
        assertEquals(0.41, wheat.ndviP25(), 1e-3);
        assertEquals(0.42, wheat.ndviMedian(), 1e-3);
        assertEquals(0.43, wheat.ndviP75(), 1e-3);
        assertEquals(HandStack.FLAT_RED * 1.42 / 0.58, wheat.nir(), 1e-3, "the nir that gives NDVI 0.42");
        assertEquals(0.25, wheat.b11(), 1e-4);
        assertEquals(0.15, wheat.b12(), 1e-4);
        assertEquals(0.25, wheat.ndti(), 1e-3, "(0.25 - 0.15) / 0.40");
        assertEquals(HandStack.FLAT_RED, wheat.brightness(), 1e-4);
        assertEquals(HandStack.FLAT_RED, wheat.blue(), 1e-4);
        assertEquals(0.0, wheat.snowShare(), 1e-9);
        assertEquals(0.0, wheat.roughness(), 1e-6, "brightness is flat");
        assertEquals(0.0, wheat.lineStrength(), 1e-9);
        assertTrue(Double.isNaN(wheat.lineDirection()));

        SeriesRow fallow = row(series, "2025-F0002", "2025-07-10");
        assertEquals(0.15, fallow.ndviMedian(), 1e-3);
    }

    @Test
    void aCloudedDateStillGetsARowWithBlankValues() throws Exception {
        HandStack hand = HandFields.stack(folder.resolve("stack"));
        hand.layer(LocalDate.parse("2025-07-25"), Layer.SCL, maskWithLeftColumns(20, 9));

        Series series = build(hand);
        SeriesRow wheat = row(series, "2025-F0001", "2025-07-25");

        assertEquals(0.0, wheat.clearShare(), 1e-9);
        assertFalse(wheat.hasValues());
        assertTrue(Double.isNaN(wheat.ndviMedian()));
        assertTrue(Double.isNaN(wheat.b11()));
        assertTrue(Double.isNaN(wheat.roughness()));
        assertTrue(row(series, "2025-F0002", "2025-07-25").hasValues(), "the fallow field is clear");
    }

    @Test
    void aFieldUnder60PercentClearKeepsItsShareButNoValues() throws Exception {
        HandStack hand = HandFields.stack(folder.resolve("stack"));
        hand.layer(LocalDate.parse("2025-08-10"), Layer.SCL, maskWithLeftColumns(10, 9));

        SeriesRow wheat = row(build(hand), "2025-F0001", "2025-08-10");

        assertEquals(18.0 / 36, wheat.clearShare(), 1e-9, "interior columns 2-19 of 2-37 are under cloud");
        assertFalse(wheat.hasValues());
    }

    @Test
    void snowCountsBothTheMasksSnowAndTheSnowTest() throws Exception {
        HandStack hand = HandFields.stack(folder.resolve("stack"));
        LocalDate date = LocalDate.parse("2025-08-25");
        hand.layer(date, Layer.SCL, maskWithLeftColumns(5, 11)); // 10 m columns 0-9: the mask's snow
        int[] green = new int[HandFields.WIDTH * HandFields.HEIGHT];
        int[] nir = new int[green.length];
        Arrays.fill(green, HandStack.dn(HandStack.FLAT_RED));
        Arrays.fill(nir, HandStack.dn(0.3));
        int[] swir16 = new int[MASK_WIDTH * MASK_HEIGHT];
        Arrays.fill(swir16, HandStack.dn(0.2));
        for (int y = 0; y < HandFields.HEIGHT; y++) {
            for (int x = 10; x < 14; x++) { // snow the mask missed: bright green, dark swir
                green[y * HandFields.WIDTH + x] = HandStack.dn(0.6);
                nir[y * HandFields.WIDTH + x] = HandStack.dn(0.55);
            }
        }
        for (int y = 0; y < MASK_HEIGHT; y++) {
            swir16[y * MASK_WIDTH + 5] = HandStack.dn(0.1);
            swir16[y * MASK_WIDTH + 6] = HandStack.dn(0.1);
        }
        hand.layer(date, Layer.GREEN, green).layer(date, Layer.NIR, nir).layer(date, Layer.SWIR16, swir16);

        SeriesRow wheat = row(build(hand), "2025-F0001", "2025-08-25");

        assertEquals(12.0 / 36, wheat.snowShare(), 1e-9, "columns 2-9 by the mask, 10-13 by the snow test");
        assertEquals(24.0 / 36, wheat.clearShare(), 1e-9);
        assertTrue(wheat.hasValues());
    }

    /** Ice cloud also has a high snow index; where the mask already calls it cloud, it isn't snow. */
    @Test
    void cloudThatPassesTheSnowTestIsNotSnow() throws Exception {
        HandStack hand = HandFields.stack(folder.resolve("stack"));
        LocalDate date = LocalDate.parse("2025-06-25");
        hand.layer(date, Layer.SCL, maskWithLeftColumns(20, 9));
        int[] green = new int[HandFields.WIDTH * HandFields.HEIGHT];
        int[] nir = new int[green.length];
        Arrays.fill(green, HandStack.dn(0.6));
        Arrays.fill(nir, HandStack.dn(0.55));
        int[] swir16 = new int[MASK_WIDTH * MASK_HEIGHT];
        Arrays.fill(swir16, HandStack.dn(0.1));
        hand.layer(date, Layer.GREEN, green).layer(date, Layer.NIR, nir).layer(date, Layer.SWIR16, swir16);

        Series series = build(hand);

        assertEquals(0.0, row(series, "2025-F0001", "2025-06-25").snowShare(), 1e-9, "all cloud by the mask");
        assertEquals(1.0, row(series, "2025-F0002", "2025-06-25").snowShare(), 1e-9,
                "the mask calls it clear ground, the snow test finds snow");
    }

    @Test
    void otherRegionsAreListedButGetNoRows() throws Exception {
        Series series = build(HandFields.stack(folder.resolve("stack")));

        assertEquals(List.of("2025-F0001", "2025-F0002", "2025-F0003", "2025-F0004"),
                series.fields().stream().map(SeriesField::id).toList());
        SeriesField other = series.fields().get(2);
        assertEquals(LandLabel.OTHER, other.land());
        assertFalse(other.inSeries());
        assertTrue(series.rows().stream().noneMatch(r -> r.field().equals("2025-F0003")));
        assertEquals(LandLabel.FALLOW, series.fields().get(1).land());
        assertTrue(series.fields().get(1).inSeries());
    }

    @Test
    void listsAFieldWithNoInterior() throws Exception {
        Series series = build(HandFields.stack(folder.resolve("stack")));

        SeriesField thin = series.fields().get(3);
        assertEquals(0, thin.interiorPixels());
        assertTrue(thin.inSeries());
        assertEquals(3.2, thin.areaHectares(), 1e-9, "80 x 4 pixels of 100 m2");
        SeriesRow row = row(series, "2025-F0004", "2025-06-01");
        assertTrue(Double.isNaN(row.clearShare()));
        assertTrue(Double.isNaN(row.snowShare()));
        assertFalse(row.hasValues());
    }

    @Test
    void eachFieldHasItsInteriorAndCentre() throws Exception {
        Series series = build(HandFields.stack(folder.resolve("stack")));

        SeriesField wheat = series.fields().get(0);
        assertEquals(36 * 36, wheat.interiorPixels());
        assertEquals(16.0, wheat.areaHectares(), 1e-9);
        assertEquals(12.96, wheat.interiorHectares(), 1e-9);
        // the centre is 460340 E, 5922580 N in UTM 41N: about 40 km west of the zone's 63 E meridian
        assertEquals(62.40, wheat.centroidLon(), 0.05);
        assertEquals(53.44, wheat.centroidLat(), 0.05);
        assertTrue(series.fields().get(1).centroidLon() > wheat.centroidLon(), "the fallow field lies east");
    }

    @Test
    void refusesFieldsFromAnotherSeason() throws Exception {
        SeasonStack stack = HandFields.stack(folder.resolve("stack")).finish();
        Path fields = folder.resolve("fields");
        HandFields.write(stack, fields);
        Path other = folder.resolve("stack-2024");
        SeasonStack season2025 = HandFields.stack(other).finish();
        StackArea area = season2025.area();
        new StackArea(area.bbox(), 2024, area.tile(), area.grid(), area.maskGrid()).write(other);

        IOException refused = assertThrows(IOException.class,
                () -> SeriesBuilder.build(SeasonStack.open(other), fields, line -> { }));

        assertTrue(refused.getMessage().contains("2024"), refused.getMessage());
        assertTrue(refused.getMessage().contains("2025"), refused.getMessage());
    }

    @Test
    void refusesFieldsOnAnotherGrid() throws Exception {
        SeasonStack stack = HandFields.stack(folder.resolve("stack")).finish();
        Path fields = folder.resolve("fields");
        HandFields.write(stack, fields);
        SeasonStack bigger = new HandStack(folder.resolve("bigger"), 82, 60).stored("2025-07-10", 2000).finish();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SeriesBuilder.build(bigger, fields, line -> { }));

        assertTrue(refused.getMessage().contains("grid"), refused.getMessage());
    }

    @Test
    void refusesAFolderWithoutFields() throws Exception {
        SeasonStack stack = HandFields.stack(folder.resolve("stack")).finish();
        Path empty = Files.createDirectories(folder.resolve("empty"));

        IOException refused = assertThrows(IOException.class, () -> SeriesBuilder.build(stack, empty, line -> { }));

        assertTrue(refused.getMessage().contains("fields-2025.tif"), refused.getMessage());
    }
}
