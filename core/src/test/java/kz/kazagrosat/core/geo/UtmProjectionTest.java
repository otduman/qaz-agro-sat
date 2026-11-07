package kz.kazagrosat.core.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Expected values come from an independent Krueger-series calculation, not from the library under test. */
class UtmProjectionTest {

    private static final double FIVE_CM = 0.05;

    @ParameterizedTest
    @CsvSource({
            // epsg, lon, lat, easting, northing
            "32641, 63.0,  45.0,  500000.000, 4982950.400",   // on the central meridian
            "32641, 62.55, 53.54, 470178.563, 5932438.003",   // Kostanay oblast
            "32639, 51.2,  47.1,  515176.720, 5216296.249",   // western Kazakhstan, zone 39
            "32642, 71.43, 51.13, 670024.411, 5667089.434",   // Astana, zone 42
    })
    void projectsLonLatToUtm(int epsg, double lon, double lat, double easting, double northing) {
        UtmPoint point = UtmProjection.forEpsg(epsg).project(lon, lat);

        assertEquals(easting, point.easting(), FIVE_CM);
        assertEquals(northing, point.northing(), FIVE_CM);
    }

    @Test
    void extentOfASmallBoxReachesItsExtremeCorners() {
        Extent extent = UtmProjection.forEpsg(32641).project(new BoundingBox(62.40, 53.45, 62.70, 53.63));

        assertEquals(460153.895, extent.minX(), FIVE_CM);    // south-west corner
        assertEquals(5922373.121, extent.minY(), FIVE_CM);   // south-east corner
        assertEquals(480161.199, extent.maxX(), FIVE_CM);    // north-east corner
        assertEquals(5942523.845, extent.maxY(), FIVE_CM);   // north-west corner
    }

    @Test
    void extentIncludesTheMidEdgeDipWhenABoxStraddlesTheCentralMeridian() {
        Extent extent = UtmProjection.forEpsg(32641).project(new BoundingBox(62.0, 53.0, 64.0, 54.0));

        // Parallels curve in UTM: the corners sit at 5872738.261, 468 m north of the mid-edge.
        assertEquals(5872270.528, extent.minY(), FIVE_CM);
        assertEquals(5983984.455, extent.maxY(), FIVE_CM);
        assertEquals(432890.565, extent.minX(), FIVE_CM);
        assertEquals(567109.435, extent.maxX(), FIVE_CM);
    }

    @ParameterizedTest
    @ValueSource(ints = {4326, 3857, 32600, 32661, 32741})
    void rejectsCodesThatAreNotNorthernUtmZones(int epsg) {
        assertThrows(IllegalArgumentException.class, () -> UtmProjection.forEpsg(epsg));
    }

    @Test
    void unprojectUndoesProject() {
        UtmProjection utm = UtmProjection.forEpsg(32641);
        UtmPoint point = utm.project(62.55, 53.54);

        LonLat back = utm.unproject(point.easting(), point.northing());

        assertEquals(62.55, back.longitude(), 1e-9);
        assertEquals(53.54, back.latitude(), 1e-9);
    }

    @Test
    void unprojectsTheTestAreaCorner() {
        // The test area's 10 m grid origin, 460140 E 5942540 N in zone 41N, lies a few hundred metres
        // from 62.40 E 53.63 N, the area's north-west corner.
        LonLat corner = UtmProjection.forEpsg(32641).unproject(460140, 5942540);

        assertEquals(62.40, corner.longitude(), 0.005);
        assertEquals(53.63, corner.latitude(), 0.005);
    }
}
