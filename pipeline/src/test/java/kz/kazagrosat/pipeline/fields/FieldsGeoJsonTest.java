package kz.kazagrosat.pipeline.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.FieldSummary;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.core.geo.LonLat;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Polygon;

class FieldsGeoJsonTest {

    private static final PixelGrid GRID = new PixelGrid(32641, 460140, 5942540, 10, 20, 10);

    @TempDir
    Path folder;

    private JsonNode features;

    @BeforeEach
    void writeTwoRegions() throws Exception {
        int[] labels = new int[GRID.width() * GRID.height()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = i % GRID.width() < 12 ? 1 : 2;
        }
        List<Region> regions = List.of(
                new Region(1, 120, LandLabel.FIELD, 0.876),
                new Region(2, 80, LandLabel.OTHER, Double.NaN));
        FieldSummary summary = new FieldSummary(List.of(), 2, 0, 0, 0, 0, 0, 0, 0, Map.of());
        FieldMap map = new FieldMap(GRID, labels, new float[labels.length], regions, summary);
        Map<Integer, Polygon> outlines = RegionOutlines.trace(labels, GRID);

        Path file = folder.resolve("fields-2025.geojson");
        FieldsGeoJson.write(file, map, outlines, 2025);

        JsonNode root = new ObjectMapper().readTree(Files.readString(file));
        assertEquals("FeatureCollection", root.get("type").asText());
        features = root.get("features");
    }

    @Test
    void writesOneFeaturePerRegionWithItsProperties() {
        assertEquals(2, features.size());
        JsonNode first = features.get(0).get("properties");
        assertEquals("2025-F0001", first.get("id").asText());
        assertEquals(1.20, first.get("area_ha").asDouble(), 1e-9);
        assertEquals("field", first.get("land").asText());
        assertEquals(0.88, first.get("uniformity").asDouble(), 1e-9);
        JsonNode second = features.get(1).get("properties");
        assertEquals("2025-F0002", second.get("id").asText());
        assertEquals("other", second.get("land").asText());
        assertTrue(second.get("uniformity").isNull(), "a uniformity that can't be scored is null");
        assertEquals("Polygon", features.get(0).get("geometry").get("type").asText());
    }

    @Test
    void coordinatesAreLongitudeLatitudeWithSixDecimals() {
        JsonNode shell = features.get(0).get("geometry").get("coordinates").get(0);
        UtmProjection utm = UtmProjection.forEpsg(32641);
        boolean foundOrigin = false;
        LonLat origin = utm.unproject(460140, 5942540);
        for (JsonNode point : shell) {
            double lon = point.get(0).asDouble();
            double lat = point.get(1).asDouble();
            assertEquals(lon, Math.round(lon * 1e6) / 1e6, 1e-12, "six decimals");
            if (Math.abs(lon - origin.longitude()) < 1e-6 && Math.abs(lat - origin.latitude()) < 1e-6) {
                foundOrigin = true;
            }
        }
        assertTrue(foundOrigin, "the grid origin is a corner of the first region");
    }

    @Test
    void shellsRunCounterClockwise() {
        JsonNode shell = features.get(0).get("geometry").get("coordinates").get(0);
        double twiceArea = 0;
        for (int i = 0; i + 1 < shell.size(); i++) {
            double x1 = shell.get(i).get(0).asDouble();
            double y1 = shell.get(i).get(1).asDouble();
            double x2 = shell.get(i + 1).get(0).asDouble();
            double y2 = shell.get(i + 1).get(1).asDouble();
            twiceArea += x1 * y2 - x2 * y1;
        }
        assertTrue(twiceArea > 0, "positive signed area means counter-clockwise");
    }
}
