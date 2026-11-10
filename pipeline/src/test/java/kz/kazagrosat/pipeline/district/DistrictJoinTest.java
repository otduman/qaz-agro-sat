package kz.kazagrosat.pipeline.district;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.geo.DistrictPlan;
import kz.kazagrosat.core.geo.LonLat;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistrictJoinTest {

    private static final UtmProjection UTM = UtmProjection.forEpsg(32642);

    @TempDir
    Path folder;

    private Path district;
    private DistrictPlan.Square west;
    private DistrictPlan.Square east;

    @BeforeEach
    void twoSquares() throws Exception {
        Path boundary = folder.resolve("b.geojson");
        LonLat a = UTM.unproject(481_000, 5_961_000);
        LonLat b = UTM.unproject(519_000, 5_978_000);
        Files.writeString(boundary, String.format(java.util.Locale.ROOT,
                "{\"type\":\"Polygon\",\"coordinates\":[[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]}",
                a.longitude(), a.latitude(), b.longitude(), a.latitude(), b.longitude(), b.latitude(),
                a.longitude(), b.latitude(), a.longitude(), a.latitude()));
        district = folder.resolve("d");
        List<DistrictPlan.Square> squares = District.plan(boundary, district);
        west = squares.stream().filter(s -> s.id().equals("E480N5960")).findFirst().orElseThrow();
        east = squares.stream().filter(s -> s.id().equals("E500N5960")).findFirst().orElseThrow();
        // West square: F0001 deep inside; F0002 in the 1 km overlap with the east square (its centre at 501 km),
        // touching the west square's east edge. East square: F0001 is the same overlap field, seen whole.
        writeSquare(west, List.of(field(1, 490_000, 5_970_000, "dated", "2026-09-01", "2026-09-04"),
                field(2, 501_000, 5_970_000, "dated", "2026-09-06", "2026-09-08")), 2);
        writeSquare(east, List.of(field(1, 501_000, 5_970_000, "dated", "2026-09-04", "2026-09-06"),
                field(2, 510_000, 5_970_000, "no cut found", "", "")), 0);
    }

    private record F(int region, double e, double n, String status, String from, String to) {}

    private static F field(int region, double e, double n, String status, String from, String to) {
        return new F(region, e, n, status, from, to);
    }

    /** Writes a square's series field list, harvest table, summary and a label map where {@code edge} touches the edge. */
    private void writeSquare(DistrictPlan.Square s, List<F> fields, int edge) throws Exception {
        Path sq = district.resolve(s.id());
        Files.createDirectories(sq.resolve("series-2026"));
        Files.createDirectories(sq.resolve("harvest-2026"));
        Files.createDirectories(sq.resolve("fields-2026"));
        StringBuilder list = new StringBuilder("id,land,area_ha,interior_ha,centroid_lat,centroid_lon,in_series\n");
        StringBuilder harvest = new StringBuilder(
                "field,land,area_ha,status,cut_from,cut_to,cut_middle,window_days,rise,readings\n");
        for (F f : fields) {
            LonLat c = UTM.unproject(f.e(), f.n());
            String id = String.format("2026-F%04d", f.region());
            list.append(String.format(java.util.Locale.ROOT, "%s,field,100.00,90.00,%.6f,%.6f,yes%n", id,
                    c.latitude(), c.longitude()));
            harvest.append(String.format("%s,field,100.00,%s,%s,%s,,,,9%n", id, f.status(), f.from(), f.to()));
        }
        Files.writeString(sq.resolve("series-2026").resolve("fields-2026.csv"), list);
        Files.writeString(sq.resolve("harvest-2026").resolve("harvest-2026.csv"), harvest);
        Files.writeString(sq.resolve("harvest-2026").resolve("summary-2026.txt"), """
                Season 2026: 2 field regions dated
                Half of the dated area cut by: 2026-09-05
                Screened dates: none
                  Bare fallow (NDVI never above 0.3 from August) with a cut found: 0 of 3
                  Other fallow (green at some point from August) with a cut found: 1 of 2
                  Sentinel-2 alone agrees: 2 of 2 fields cut in both (windows overlap or touch)
                """);
        int[] labels = new int[10 * 10];
        labels[0] = 1;
        if (edge > 0) {
            labels[9] = edge;
        }
        TiledTiffWriter.write(sq.resolve("fields-2026").resolve("fields-2026.tif"), labels,
                new PixelGrid(32642, s.minE(), s.maxN(), 2100, 10, 10), 16, 0);
    }

    @Test
    void eachFieldIsKeptOnceByTheSquareWhoseCoreHoldsIt() throws Exception {
        DistrictJoin.Result result = DistrictJoin.join(district, 2026);

        List<String> lines = Files.readAllLines(district.resolve("district-harvest-2026.csv"));
        assertEquals("square,field,land,area_ha,status,cut_from,cut_to,cut_middle,window_days,rise,readings,"
                + "centroid_lat,centroid_lon,touches_edge", lines.get(0));
        assertEquals(4, lines.size(), "the overlap field is kept once: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("E500N5960,2026-F0001,")), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("E480N5960,2026-F0002,")), lines.toString());
        assertEquals(3, result.fields());
    }

    @Test
    void theOverlapCheckComparesFieldsSeenTwice() throws Exception {
        DistrictJoin.Result result = DistrictJoin.join(district, 2026);

        assertEquals(1, result.overlapPairs());
        assertEquals(1, result.overlapAgree(), "6-8 and 4-6 September touch");
        String summary = Files.readString(district.resolve("district-summary-2026.txt"));
        assertTrue(summary.contains("Fields seen by two squares with the same cut window: 1 of 1"), summary);
        assertTrue(summary.contains("Squares joined: 2"), summary);
        assertTrue(summary.contains("E480N5960 | 2 | 2 | 0 | 0 | 0 | 0 | 2026-09-05 | 0 of 3 | 2 of 2"), summary);
    }

    @Test
    void fieldsTouchingTheSquaresEdgeAreMarked() throws Exception {
        DistrictJoin.join(district, 2026);

        List<String> lines = Files.readAllLines(district.resolve("district-harvest-2026.csv"));
        String inner = lines.stream().filter(l -> l.startsWith("E480N5960,2026-F0001,")).findFirst().orElseThrow();
        assertTrue(inner.endsWith(",yes"), "F0001 sits in the corner pixel of the label map: " + inner);
    }
}
