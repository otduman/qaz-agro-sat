package kz.kazagrosat.pipeline.district;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kz.kazagrosat.core.geo.DistrictPlan;
import kz.kazagrosat.core.geo.LonLat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistrictTest {

    @TempDir
    Path folder;

    private Path boundary(String geometry) throws IOException {
        Path file = folder.resolve("boundary.geojson");
        Files.writeString(file, """
                {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"test"},
                 "geometry":%s}]}""".formatted(geometry));
        return file;
    }

    @Test
    void readsTheOuterRingOfTheLargestPolygon() throws Exception {
        Path file = boundary("""
                {"type":"MultiPolygon","coordinates":[
                  [[[69.0,53.5],[69.1,53.5],[69.1,53.6],[69.0,53.5]]],
                  [[[69.0,53.0],[70.0,53.0],[70.0,54.0],[69.0,54.0],[69.0,53.0]],
                   [[69.4,53.4],[69.6,53.4],[69.6,53.6],[69.4,53.4]]]]}""");

        List<LonLat> ring = District.readBoundary(file);

        assertEquals(5, ring.size());
        assertEquals(new LonLat(70.0, 54.0), ring.get(2));
    }

    @Test
    void writesThePlanAsATable() throws Exception {
        Path file = boundary("""
                {"type":"Polygon","coordinates":[[[69.0,53.6],[69.4,53.6],[69.4,53.8],[69.0,53.8],[69.0,53.6]]]}""");

        List<DistrictPlan.Square> squares = District.plan(file, folder.resolve("plan"));

        List<String> lines = Files.readAllLines(folder.resolve("plan").resolve("squares.csv"));
        assertEquals("square,min_e,min_n,max_e,max_n,west,south,east,north,share_inside,epsg", lines.get(0));
        assertEquals(squares.size() + 1, lines.size());
        assertTrue(lines.get(1).endsWith(",32642"), lines.get(1));
        assertEquals(squares, District.readPlan(folder.resolve("plan")));
    }

    @Test
    void refusesADistrictAcrossTwoUtmZones() throws Exception {
        Path file = boundary("""
                {"type":"Polygon","coordinates":[[[71.5,53.6],[72.5,53.6],[72.5,53.8],[71.5,53.8],[71.5,53.6]]]}""");

        IOException error = assertThrows(IOException.class, () -> District.plan(file, folder.resolve("plan")));

        assertTrue(error.getMessage().contains("UTM zones 42 and 43"), error.getMessage());
    }
}
