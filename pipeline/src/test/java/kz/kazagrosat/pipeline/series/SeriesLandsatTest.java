package kz.kazagrosat.pipeline.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.pipeline.landsat.LandsatAdjustment;
import kz.kazagrosat.pipeline.landsat.LandsatAdjustment.Measure;
import kz.kazagrosat.pipeline.landsat.LandsatFolder;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackArea;
import kz.kazagrosat.pipeline.testutil.HandFields;
import kz.kazagrosat.pipeline.testutil.HandLandsat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeriesLandsatTest {

    private static final LandsatAdjustment FIT = LandsatAdjustment.DEFAULT;

    @TempDir
    Path folder;

    private SeasonStack stack;
    private Path fields;
    private HandLandsat landsat;

    @BeforeEach
    void setUp() throws Exception {
        stack = HandFields.stack(folder.resolve("stack")).finish();
        fields = folder.resolve("fields");
        HandFields.write(stack, fields);
        landsat = new HandLandsat(folder.resolve("landsat"), stack.area());
    }

    private Series build(LandsatFolder folder) throws Exception {
        return SeriesBuilder.build(stack, fields, new SeriesBuilder.LandsatInput(folder, FIT), line -> { });
    }

    private static List<SeriesRow> rows(Series series, String field, String date, String satellite) {
        return series.rows().stream().filter(r -> r.field().equals(field) && r.date().equals(LocalDate.parse(date))
                && r.satellite().equals(satellite)).toList();
    }

    private static SeriesRow row(Series series, String field, String date, String satellite) {
        List<SeriesRow> found = rows(series, field, date, satellite);
        assertEquals(1, found.size(), field + " " + date + " " + satellite);
        return found.getFirst();
    }

    @Test
    void landsatRowsCarryTheAdjustedMedians() throws Exception {
        Series series = build(landsat.scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR).finish());

        SeriesRow r = row(series, "2025-F0001", "2025-07-20", "LC09");
        float red = HandLandsat.stored(HandLandsat.RED);
        float nir = HandLandsat.stored(HandLandsat.NIR);
        float ndvi = (nir - red) / (nir + red);
        double b11 = FIT.apply(Measure.B11, HandLandsat.stored(HandLandsat.SWIR16));
        double b12 = FIT.apply(Measure.B12, HandLandsat.stored(HandLandsat.SWIR22));
        assertEquals(1.0, r.clearShare(), 1e-12);
        assertEquals(FIT.apply(Measure.NDVI, ndvi), r.ndviMedian(), 1e-5);
        assertEquals(FIT.apply(Measure.NDVI, ndvi), r.ndviP25(), 1e-5);
        assertEquals(b11, r.b11(), 1e-5);
        assertEquals(b12, r.b12(), 1e-5);
        assertEquals((b11 - b12) / (b11 + b12), r.ndti(), 1e-5);
        assertEquals(FIT.apply(Measure.NIR, nir), r.nir(), 1e-5);
        assertEquals(FIT.apply(Measure.BLUE, HandLandsat.stored(HandLandsat.BLUE)), r.blue(), 1e-5);
        assertEquals(0.0, r.snowShare(), 1e-12);
    }

    @Test
    void landsatRowsLeaveBrightnessAndTextureBlank() throws Exception {
        Series series = build(landsat.scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR).finish());

        SeriesRow r = row(series, "2025-F0001", "2025-07-20", "LC09");
        assertTrue(Double.isNaN(r.brightness()));
        assertTrue(Double.isNaN(r.roughness()));
        assertTrue(Double.isNaN(r.lineStrength()));
        assertTrue(Double.isNaN(r.lineDirection()));
    }

    @Test
    void sentinelRowsAreUnchanged() throws Exception {
        Series without = SeriesBuilder.build(stack, fields, line -> { });
        Series with = build(landsat.scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR).finish());

        assertEquals(without.rows(), with.rows().stream().filter(r -> r.satellite().equals("S2")).toList());
        assertTrue(without.rows().stream().allMatch(r -> r.satellite().equals("S2")));
    }

    @Test
    void aFieldUnder60PercentClearOnLandsatKeepsItsShareButNoValues() throws Exception {
        // Landsat columns 0 to 6 are area columns 0 to 20: 19 of field 1's 36 interior columns (2 to 37).
        Series series = build(landsat.scene("LC09_A", "2025-07-20",
                (c, r) -> c < 7 ? HandLandsat.CLOUD : HandLandsat.CLEAR).finish());

        SeriesRow r = row(series, "2025-F0001", "2025-07-20", "LC09");
        assertEquals(17 / 36.0, r.clearShare(), 1e-9);
        assertTrue(Double.isNaN(r.ndviMedian()));
        assertTrue(Double.isNaN(r.b11()));
    }

    @Test
    void twoScenesOfOneDayAndSatelliteJoin() throws Exception {
        Series series = build(landsat
                .scene("LC09_A", "2025-07-20", (c, r) -> c < 7 ? HandLandsat.CLOUD : HandLandsat.CLEAR)
                .scene("LC09_B", "2025-07-20", (c, r) -> c < 7 ? HandLandsat.CLEAR : HandLandsat.CLOUD)
                .finish());

        SeriesRow r = row(series, "2025-F0001", "2025-07-20", "LC09");
        assertEquals(1.0, r.clearShare(), 1e-12);
        assertTrue(!Double.isNaN(r.ndviMedian()));
    }

    @Test
    void landsatSnowCountsTheQualityFlagAndTheSnowTest() throws Exception {
        Series series = build(landsat.scene("LC09_A", "2025-07-20",
                (c, r) -> c < 7 ? HandLandsat.SNOW : HandLandsat.CLEAR).finish());

        SeriesRow r = row(series, "2025-F0001", "2025-07-20", "LC09");
        assertEquals(19 / 36.0, r.snowShare(), 1e-9);
    }

    @Test
    void aCloudySceneAddsNoRows() throws Exception {
        Series series = build(landsat.cloudy("LC08_C", "2025-07-28").finish());

        assertTrue(series.rows().stream().noneMatch(r -> r.date().equals(LocalDate.parse("2025-07-28"))));
    }

    @Test
    void eachFieldsRowsAreInDateOrderWithSentinel2FirstOnAShareDate() throws Exception {
        Series series = build(landsat.scene("LC08_A", "2025-07-10", (c, r) -> HandLandsat.CLEAR).finish());

        List<SeriesRow> field = series.rows().stream().filter(r -> r.field().equals("2025-F0001")).toList();
        assertEquals(9, field.size());
        assertEquals("2025-07-10 S2", field.get(3).date() + " " + field.get(3).satellite());
        assertEquals("2025-07-10 LC08", field.get(4).date() + " " + field.get(4).satellite());
        assertEquals(LocalDate.parse("2025-07-25"), field.get(5).date());
        assertEquals("2025-F0002", series.rows().get(9).field(), "fields stay in region order");
    }

    @Test
    void theSeriesListsTheLandsatDaysAndTheFit() throws Exception {
        Series series = build(landsat.scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR)
                .cloudy("LC08_C", "2025-07-28").finish());

        assertEquals(List.of("2025-07-20 LC09"), series.landsatDays());
        assertEquals(FIT.source(), series.landsatSource());
    }

    @Test
    void aFieldWithNoInteriorGetsBlankLandsatRows() throws Exception {
        Series series = build(landsat.scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR).finish());

        SeriesRow r = row(series, "2025-F0004", "2025-07-20", "LC09");
        assertTrue(Double.isNaN(r.clearShare()));
        assertTrue(Double.isNaN(r.ndviMedian()));
    }

    @Test
    void refusesALandsatFolderForAnotherArea() throws Exception {
        StackArea other = new StackArea(BoundingBox.parse("62.400,53.450,62.406,53.4525"), 2025,
                stack.area().tile(), stack.area().grid(), stack.area().maskGrid());
        LandsatFolder elsewhere = new HandLandsat(folder.resolve("other"), other)
                .scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR).finish();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> build(elsewhere));

        assertTrue(error.getMessage().contains("Landsat"), error.getMessage());
    }

    @Test
    void theCsvEndsWithTheSatelliteAndTheSummaryNamesLandsat() throws Exception {
        Series series = build(landsat.scene("LC09_A", "2025-07-20", (c, r) -> HandLandsat.CLEAR).finish());
        Path out = folder.resolve("out");

        SeriesOutputs.write(series, out);

        List<String> lines = Files.readAllLines(out.resolve("series-2025.csv"));
        assertTrue(lines.getFirst().endsWith(",line_direction,satellite"), lines.getFirst());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("2025-F0001,2025-07-20,") && l.endsWith(",LC09")));
        assertTrue(lines.get(1).endsWith(",S2"), lines.get(1));
        String summary = Files.readString(out.resolve("summary-2025.txt"));
        assertTrue(summary.contains("Landsat: 1 satellite-day"), summary);
        assertTrue(summary.contains(FIT.source()), summary);
    }

    @Test
    void theSummaryGivesTheHarvestWaitWithAndWithoutLandsat() throws Exception {
        // Sentinel-2 dates in the window: 10 Aug, 25 Aug, 10 Sep, so the longest wait is 10 to 30 September, 20 days;
        // a clear Landsat day on 20 September halves that stretch, leaving 25 August to 10 September, 16 days.
        Series series = build(landsat.scene("LC09_A", "2025-09-20", (c, r) -> HandLandsat.CLEAR).finish());
        Path out = folder.resolve("out");

        SeriesOutputs.write(series, out);

        String summary = Files.readString(out.resolve("summary-2025.txt"));
        assertTrue(summary.contains("typical field: Sentinel-2 20 days, with Landsat 16 days"), summary);
    }
}
