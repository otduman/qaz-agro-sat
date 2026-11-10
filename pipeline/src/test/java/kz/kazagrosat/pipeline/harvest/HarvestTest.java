package kz.kazagrosat.pipeline.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.testutil.HandFields;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarvestTest {

    private static final String HEADER = "field,date,clear_share,ndvi_p25,ndvi_median,ndvi_p75,b11,b12,ndti,nir,"
            + "brightness,blue,snow_share,roughness,line_strength,line_direction,satellite";

    @TempDir
    Path folder;

    private Path fields;
    private Path series;

    @BeforeEach
    void setUp() throws Exception {
        HandStack stack = HandFields.stack(folder.resolve("stack"));
        fields = folder.resolve("fields");
        HandFields.write(stack.finish(), fields);
        series = folder.resolve("series");
        Files.createDirectories(series);
        Files.write(series.resolve("fields-2025.csv"), List.of(
                "id,land,area_ha,interior_ha,centroid_lat,centroid_lon,in_series",
                "2025-F0001,field,16.00,12.00,53.5,62.5,yes",
                "2025-F0002,fallow,16.00,12.00,53.5,62.5,yes",
                "2025-F0003,other,12.80,10.00,53.5,62.5,no",
                "2025-F0004,field,3.20,0.00,53.5,62.5,yes"));
        List<String> rows = new ArrayList<>(List.of(HEADER));
        // The crop greened in July (NDVI 0.75) before ripening.
        rows.add("2025-F0001,2025-07-15,1.0000,0.7,0.7500,0.8,0.1500,0.1,0.0,0.3,0.05,0.0400,0.0000,,,,S2");
        double[] cut = {0.22, 0.21, 0.20, 0.29, 0.29, 0.29};
        for (int i = 0; i < cut.length; i++) {
            String date = LocalDate.parse("2025-08-01").plusDays(5L * i).toString();
            rows.add(row("2025-F0001", date, cut[i], "S2"));
            rows.add(row("2025-F0002", date, 0.25, "S2"));
            rows.add("2025-F0004," + date + ",0.0000,,,,,,,,,,,,,,S2");
        }
        Files.write(series.resolve("series-2025.csv"), rows);
    }

    private static String row(String field, String date, double b11, String satellite) {
        return String.format(java.util.Locale.ROOT,
                "%s,%s,1.0000,0.2,0.3000,0.4,%.4f,0.2,0.0,0.2,0.08,0.0700,0.0000,,,,%s", field, date, b11, satellite);
    }

    @Test
    void writesEachFieldsCutWithItsStatus() throws Exception {
        HarvestReport report = Harvest.run(series, fields, folder.resolve("out"), s -> { });

        List<String> csv = Files.readAllLines(folder.resolve("out").resolve("harvest-2025.csv"));
        assertEquals("field,land,area_ha,status,cut_from,cut_to,cut_middle,window_days,rise,readings", csv.get(0));
        assertEquals("2025-F0001,field,16.00,dated,2025-08-11,2025-08-16,2025-08-13,5,8.00,6", csv.get(1));
        assertEquals("2025-F0004,field,3.20,unclear,,,,,,0", csv.get(2));
        assertEquals(3, csv.size(), "fallow and other regions are not dated");
        assertEquals(1, report.dated());
        assertEquals(1, report.unclear());
    }

    @Test
    void theMapHoldsTheCutsDayOfYear() throws Exception {
        Harvest.run(series, fields, folder.resolve("out"), s -> { });

        CogReader reader = CogReader.open(new FileByteSource(folder.resolve("out").resolve("harvest-2025.tif")));
        int[] days = reader.readWindow(new PixelWindow(0, 0, HandFields.WIDTH, HandFields.HEIGHT));
        assertEquals(LocalDate.parse("2025-08-13").getDayOfYear(), days[10 * HandFields.WIDTH + 10]);
        assertEquals(0, days[10 * HandFields.WIDTH + 60], "fallow");
        BufferedImage png = ImageIO.read(folder.resolve("out").resolve("harvest-2025.png").toFile());
        assertEquals(HandFields.WIDTH, png.getWidth());
        assertEquals(0, png.getRGB(10, 50) >>> 24, "other land is transparent");
        assertEquals(255, png.getRGB(10, 10) >>> 24);
    }

    @Test
    void theSummaryCarriesTheChecks() throws Exception {
        Harvest.run(series, fields, folder.resolve("out"), s -> { });

        String summary = Files.readString(folder.resolve("out").resolve("summary-2025.txt"));
        assertTrue(summary.contains("dated: 1 fields, 16 ha"), summary);
        assertTrue(summary.contains("Bare fallow (NDVI never above 0.3 from August) with a cut found: 0 of 1"), summary);
        assertTrue(summary.contains("Other fallow (green at some point from August) with a cut found: 0 of 0"), summary);
        assertTrue(summary.contains("Sentinel-2 alone agrees: 1 of 1"), summary);
        assertTrue(summary.contains("Screened dates: none"), summary);
        assertTrue(summary.contains("Half of the dated area cut by: 2025-08-13"), summary);
    }

    @Test
    void refusesFieldsFromAnotherSeason() throws Exception {
        Files.move(series.resolve("series-2025.csv"), series.resolve("series-2024.csv"));
        Files.move(series.resolve("fields-2025.csv"), series.resolve("fields-2024.csv"));

        IOException error = assertThrows(IOException.class,
                () -> Harvest.run(series, fields, folder.resolve("out"), s -> { }));

        assertTrue(error.getMessage().contains("fields-2024.tif"), error.getMessage());
    }
}
