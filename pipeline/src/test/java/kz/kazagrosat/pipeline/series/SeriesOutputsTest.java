package kz.kazagrosat.pipeline.series;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.testutil.HandFields;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SeriesOutputsTest {

    @TempDir
    Path folder;

    private Series series;
    private Path out;

    @BeforeEach
    void buildSeries() throws Exception {
        HandStack hand = HandFields.stack(folder.resolve("stack"));
        int[] cloud = new int[40 * 30];
        java.util.Arrays.fill(cloud, 9);
        hand.layer(LocalDate.parse("2025-07-25"), Layer.SCL, cloud);
        SeasonStack stack = hand.finish();
        HandFields.write(stack, folder.resolve("fields"));
        series = SeriesBuilder.build(stack, folder.resolve("fields"), line -> { });
        out = folder.resolve("series");
    }

    private List<String> lines(String name) throws IOException {
        return Files.readAllLines(out.resolve(name), StandardCharsets.UTF_8);
    }

    private List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesTheSeriesTheFieldListAndASummary() throws Exception {
        List<Path> written = SeriesOutputs.write(series, out);

        assertEquals(List.of(out.resolve("series-2025.csv"), out.resolve("fields-2025.csv"),
                out.resolve("summary-2025.txt")), written);
        assertEquals(List.of("fields-2025.csv", "series-2025.csv", "summary-2025.txt"), filesIn(out));
    }

    @Test
    void theSeriesHasOneLinePerRowWithBlanksEmpty() throws Exception {
        SeriesOutputs.write(series, out);
        List<String> lines = lines("series-2025.csv");

        assertEquals("field,date,clear_share,ndvi_p25,ndvi_median,ndvi_p75,b11,b12,ndti,nir,brightness,blue,"
                + "snow_share,roughness,line_strength,line_direction", lines.get(0));
        assertEquals(1 + 3 * 8, lines.size());
        String clearRow = lines.stream().filter(l -> l.startsWith("2025-F0001,2025-07-10,")).findFirst().orElseThrow();
        String[] cells = clearRow.split(",", -1);
        assertEquals(16, cells.length, clearRow);
        assertEquals("1.0000", cells[2]);
        assertEquals("0.4200", cells[4]);
        assertEquals("0.2000", cells[6]);
        assertEquals("", cells[15], "lines on a flat field have no direction");
        assertTrue(lines.contains("2025-F0001,2025-07-25,0.0000,,,,,,,,,,0.0000,,,"), "a clouded date");
        assertTrue(lines.contains("2025-F0004,2025-07-25,,,,,,,,,,,,,,"), "a field with no interior");
    }

    @Test
    void theFieldListHasEveryRegion() throws Exception {
        SeriesOutputs.write(series, out);
        List<String> lines = lines("fields-2025.csv");

        assertEquals("id,land,area_ha,interior_ha,centroid_lat,centroid_lon,in_series", lines.get(0));
        assertEquals(5, lines.size());
        assertTrue(lines.get(1).startsWith("2025-F0001,field,16.00,12.96,53.4"), lines.get(1));
        assertTrue(lines.get(1).endsWith(",yes"), lines.get(1));
        assertTrue(lines.get(3).startsWith("2025-F0003,other,"), lines.get(3));
        assertTrue(lines.get(3).endsWith(",no"), lines.get(3));
        assertTrue(lines.get(4).startsWith("2025-F0004,field,3.20,0.00,"), lines.get(4));
    }

    @Test
    void theSummaryCountsWhatHasNoValues() throws Exception {
        SeriesOutputs.write(series, out);
        String summary = Files.readString(out.resolve("summary-2025.txt"), StandardCharsets.UTF_8);

        assertTrue(summary.contains("Season: 2025, tile T41UMV"), summary);
        assertTrue(summary.contains("Dates: 8 ("), summary);
        assertTrue(summary.contains("Fields in the series: 3 (field 2, fallow 1); other regions listed only: 1"),
                summary);
        assertTrue(summary.contains("Fields with nothing left after the 20 m shrink: 1 (2025-F0004)"), summary);
        assertTrue(summary.contains("Rows: 24; with blank values: 10 (under 60% clear 2, no interior 8)"), summary);
    }

    @Test
    void aRerunWritesIdenticalFiles() throws Exception {
        List<Path> first = SeriesOutputs.write(series, out);
        List<byte[]> before = new ArrayList<>();
        for (Path file : first) {
            before.add(Files.readAllBytes(file));
        }

        List<Path> second = SeriesOutputs.write(series, out);

        for (int i = 0; i < second.size(); i++) {
            assertArrayEquals(before.get(i), Files.readAllBytes(second.get(i)), second.get(i).toString());
        }
        assertTrue(filesIn(out).stream().noneMatch(n -> n.endsWith(".part") || n.endsWith(".old")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedFileLeavesEveryOutputAsItWas() throws Exception {
        List<Path> written = SeriesOutputs.write(series, out);
        Files.writeString(written.get(1), "edited by hand\n");
        List<byte[]> before = new ArrayList<>();
        for (Path file : written) {
            before.add(Files.readAllBytes(file));
        }

        IOException failure;
        try (FileInputStream held = new FileInputStream(out.resolve("series-2025.csv").toFile())) {
            failure = assertThrows(IOException.class, () -> SeriesOutputs.write(series, out));
        }

        assertTrue(failure.getMessage().contains("series-2025.csv"), failure.getMessage());
        for (int i = 0; i < written.size(); i++) {
            assertArrayEquals(before.get(i), Files.readAllBytes(written.get(i)), written.get(i) + " changed");
        }
        assertTrue(filesIn(out).stream().noneMatch(n -> n.endsWith(".part") || n.endsWith(".old")),
                filesIn(out).toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedLastFilePutsTheEarlierOnesBack() throws Exception {
        List<Path> written = SeriesOutputs.write(series, out);
        Files.writeString(written.get(0), "edited by hand\n");
        List<byte[]> before = new ArrayList<>();
        for (Path file : written) {
            before.add(Files.readAllBytes(file));
        }

        try (FileInputStream held = new FileInputStream(out.resolve("summary-2025.txt").toFile())) {
            assertThrows(IOException.class, () -> SeriesOutputs.write(series, out));
        }

        for (int i = 0; i < written.size(); i++) {
            assertArrayEquals(before.get(i), Files.readAllBytes(written.get(i)), written.get(i) + " changed");
        }
        assertTrue(filesIn(out).stream().noneMatch(n -> n.endsWith(".part") || n.endsWith(".old")),
                filesIn(out).toString());
    }

    @Test
    void aTinyNegativeValueIsWrittenAsZero() throws Exception {
        SeriesRow row = new SeriesRow("2025-F0001", LocalDate.parse("2025-07-10"), 1.0, 0.4, 0.42, 0.44, 0.2, 0.2,
                -0.00001, 0.12, 0.05, 0.05, 0.0, 0.0, 0.0, Double.NaN);
        Series one = new Series(2025, "T41UMV", List.of(row.date()), series.fields(), List.of(row));

        SeriesOutputs.write(one, out);

        String line = lines("series-2025.csv").get(1);
        assertTrue(line.contains(",0.2000,0.2000,0.0000,"), line);
        assertTrue(!line.contains("-0.0000"), line);
    }
}
