package kz.kazagrosat.pipeline.fields;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import kz.kazagrosat.core.fields.FieldParameters;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

class RegionOutlinesTest {

    private static PixelGrid grid(int width, int height) {
        return new PixelGrid(32641, 460140, 5942540, 10, width, height);
    }

    private static int[] labels(int width, String... rows) {
        int[] labels = new int[width * rows.length];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < width; x++) {
                char c = rows[y].charAt(x);
                labels[y * width + x] = c == '.' ? 0 : c - '0';
            }
        }
        return labels;
    }

    @Test
    void aRectangleBecomesItsFourCorners() {
        int width = 8;
        int height = 6;
        int[] labels = new int[width * height];
        for (int y = 1; y < 5; y++) {
            for (int x = 2; x < 7; x++) {
                labels[y * width + x] = 1;
            }
        }

        Polygon outline = RegionOutlines.trace(labels, grid(width, height)).get(1);

        Coordinate[] shell = outline.getExteriorRing().getCoordinates();
        assertEquals(5, shell.length);
        assertEquals(shell[0], shell[4]);
        java.util.Set<Coordinate> corners = java.util.Set.of(shell[0], shell[1], shell[2], shell[3]);
        assertEquals(java.util.Set.of(
                new Coordinate(460160, 5942530), new Coordinate(460160, 5942490),
                new Coordinate(460210, 5942490), new Coordinate(460210, 5942530)), corners);
        assertEquals(2000.0, outline.getArea(), 1e-9);
        assertEquals(0, outline.getNumInteriorRing());
        assertTrue(Orientation.isCCW(shell), "the shell runs counter-clockwise");
    }

    @Test
    void aHoleBecomesAnInnerRing() {
        int[] labels = labels(5,
                "11111",
                "11111",
                "11.11",
                "11111");

        Polygon outline = RegionOutlines.trace(labels, grid(5, 4)).get(1);

        assertEquals(1, outline.getNumInteriorRing());
        assertEquals(19 * 100.0, outline.getArea(), 1e-9);
        assertTrue(outline.isValid());
        assertTrue(!Orientation.isCCW(outline.getInteriorRingN(0).getCoordinates()), "holes run clockwise");
    }

    @Test
    void anLShapeKeepsOnlyItsCorners() {
        int[] labels = labels(4,
                "1...",
                "1...",
                "1...",
                "1111");

        Polygon outline = RegionOutlines.trace(labels, grid(4, 4)).get(1);

        assertEquals(7, outline.getExteriorRing().getNumPoints(), "6 corners plus the closing point");
        assertEquals(700.0, outline.getArea(), 1e-9);
    }

    @Test
    void aRegionTouchingItselfAtACornerGivesAValidPolygon() {
        // The hole at (2,2) touches the outside at its lower-right corner.
        int[] labels = labels(5,
                ".....",
                ".111.",
                ".1.1.",
                ".11..",
                ".....");

        Polygon outline = RegionOutlines.trace(labels, grid(5, 5)).get(1);

        assertTrue(outline.isValid(), outline.toText());
        assertEquals(700.0, outline.getArea(), 1e-9);
    }

    @Test
    void tracesEveryRegion() {
        int[] labels = labels(4,
                "1122",
                "1122",
                "3322");

        Map<Integer, Polygon> outlines = RegionOutlines.trace(labels, grid(4, 3));

        assertEquals(java.util.Set.of(1, 2, 3), outlines.keySet());
        assertEquals(400.0, outlines.get(1).getArea(), 1e-9);
        assertEquals(600.0, outlines.get(2).getArea(), 1e-9);
        assertEquals(200.0, outlines.get(3).getArea(), 1e-9);
    }

    @Test
    void neighboursStillShareTheirBorderAfterSimplifying() {
        int width = 40;
        int height = 20;
        int[] labels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                labels[y * width + x] = x < 10 + y ? 1 : 2;
            }
        }
        Map<Integer, Polygon> traced = RegionOutlines.trace(labels, grid(width, height));

        Map<Integer, Polygon> simplified = RegionOutlines.simplify(traced, FieldParameters.DEFAULTS.simplifyMetres());

        Polygon one = simplified.get(1);
        Polygon two = simplified.get(2);
        assertTrue(one.getNumPoints() < traced.get(1).getNumPoints(), "the staircase is smoothed");
        Geometry union = one.union(two);
        assertEquals(one.getArea() + two.getArea(), union.getArea(), 1e-6, "no overlap");
        assertEquals(width * height * 100.0, union.getArea(), 1e-6, "no gap");
        assertEquals(0, ((Polygon) union).getNumInteriorRing());
    }

    @Test
    void simplifyingKeepsARectangle() {
        int[] labels = labels(4,
                "1111",
                "1111");
        Map<Integer, Polygon> traced = RegionOutlines.trace(labels, grid(4, 2));

        Polygon simplified = RegionOutlines.simplify(traced, FieldParameters.DEFAULTS.simplifyMetres()).get(1);

        assertArrayEquals(traced.get(1).getCoordinates(), simplified.getCoordinates());
    }

    /** Where three regions meet, every one of them has a vertex, so the outlines form a proper coverage. */
    @Test
    void aJunctionOfThreeRegionsIsAVertexOfEach() {
        PixelGrid grid = grid(4, 4);
        Map<Integer, Polygon> traced = RegionOutlines.trace(labels(4, "1122", "1122", "1133", "1133"), grid);
        Coordinate junction = new Coordinate(grid.originX() + 20, grid.originY() - 20);

        for (int region = 1; region <= 3; region++) {
            assertTrue(java.util.Arrays.asList(traced.get(region).getCoordinates()).contains(junction),
                    "region " + region);
        }
        Geometry[] coverage = traced.values().toArray(Geometry[]::new);
        assertTrue(org.locationtech.jts.coverage.CoverageValidator.isValid(coverage));
        Geometry[] simplified = RegionOutlines.simplify(traced, 10).values().toArray(Geometry[]::new);
        assertTrue(org.locationtech.jts.coverage.CoverageValidator.isValid(simplified));
    }

    @Test
    void aRegionInTwoPiecesTouchingOnlyAtACornerIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RegionOutlines.trace(labels(2, "1.", ".1"), grid(2, 2)));

        assertTrue(refused.getMessage().contains("Region 1"), refused.getMessage());
    }
}
