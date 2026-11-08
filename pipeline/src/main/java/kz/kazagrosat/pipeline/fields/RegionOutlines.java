package kz.kazagrosat.pipeline.fields;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kz.kazagrosat.core.raster.PixelGrid;
import org.locationtech.jts.coverage.CoverageSimplifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * Turns a label map into one polygon per region, in the grid's UTM metres, along pixel edges.
 *
 * <p>Each region's outline is walked with the region on the left, so the shell runs counter-clockwise
 * and holes clockwise. Where the region touches itself at a pixel corner, the walk turns right, which
 * keeps every ring simple: a hole may touch the shell at that point, as JTS allows.
 */
public final class RegionOutlines {

    private static final GeometryFactory FACTORY = new GeometryFactory();

    /** Directions of a walk along pixel edges, counter-clockwise from east, in lattice steps. */
    private static final int[] DX = {1, 0, -1, 0};
    /** Lattice rows grow downward (south), so north is -1. */
    private static final int[] DY = {0, -1, 0, 1};
    private static final int EAST = 0;
    private static final int NORTH = 1;
    private static final int WEST = 2;
    private static final int SOUTH = 3;

    private RegionOutlines() {}

    /** @return each region's outline, keyed by its label; label 0 has none */
    public static Map<Integer, Polygon> trace(int[] labels, PixelGrid grid) {
        int width = grid.width();
        int height = grid.height();
        if (labels.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " labels but got " + labels.length);
        }
        Map<Integer, Edges> edgesByRegion = new TreeMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = labels[y * width + x];
                if (r == 0) {
                    continue;
                }
                Edges edges = edgesByRegion.computeIfAbsent(r, k -> new Edges());
                if (y == 0 || labels[(y - 1) * width + x] != r) {
                    edges.add(x + 1, y, WEST);
                }
                if (y == height - 1 || labels[(y + 1) * width + x] != r) {
                    edges.add(x, y + 1, EAST);
                }
                if (x == 0 || labels[y * width + x - 1] != r) {
                    edges.add(x, y, SOUTH);
                }
                if (x == width - 1 || labels[y * width + x + 1] != r) {
                    edges.add(x + 1, y + 1, NORTH);
                }
            }
        }
        Map<Integer, Polygon> outlines = new LinkedHashMap<>();
        for (Map.Entry<Integer, Edges> entry : edgesByRegion.entrySet()) {
            outlines.put(entry.getKey(), polygon(entry.getKey(), entry.getValue(), grid,
                    (i, j) -> junction(labels, width, height, i, j)));
        }
        return outlines;
    }

    /**
     * Smooths all outlines together with JTS's coverage simplifier, so neighbours keep sharing one border
     * with no gaps or overlaps.
     */
    public static Map<Integer, Polygon> simplify(Map<Integer, Polygon> outlines, double metres) {
        List<Integer> ids = new ArrayList<>(outlines.keySet());
        Geometry[] coverage = new Geometry[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            coverage[i] = outlines.get(ids.get(i));
        }
        Geometry[] simplified = CoverageSimplifier.simplify(coverage, metres);
        Map<Integer, Polygon> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (!(simplified[i] instanceof Polygon polygon)) {
                throw new IllegalStateException("Simplifying region " + ids.get(i) + " gave a "
                        + simplified[i].getGeometryType());
            }
            result.put(ids.get(i), polygon);
        }
        return result;
    }

    /** Whether three or more labels (outside the image counting as one) meet at lattice corner (i, j). */
    private static boolean junction(int[] labels, int width, int height, int i, int j) {
        int[] around = new int[4];
        int n = 0;
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -1; dx <= 0; dx++) {
                int x = i + dx;
                int y = j + dy;
                around[n++] = x < 0 || y < 0 || x >= width || y >= height ? -1 : labels[y * width + x];
            }
        }
        return Arrays.stream(around).distinct().count() >= 3;
    }

    @FunctionalInterface
    private interface Junction {
        boolean at(int i, int j);
    }

    private static Polygon polygon(int region, Edges edges, PixelGrid grid, Junction junction) {
        List<LinearRing> shells = new ArrayList<>();
        List<LinearRing> holes = new ArrayList<>();
        for (Coordinate[] ring : edges.rings(grid, junction)) {
            LinearRing linearRing = FACTORY.createLinearRing(ring);
            if (signedArea(ring) > 0) {
                shells.add(linearRing);
            } else {
                holes.add(linearRing);
            }
        }
        if (shells.size() != 1) {
            throw new IllegalStateException("Region " + region + " has " + shells.size()
                    + " outer outlines; each region must be one 4-connected piece");
        }
        Polygon polygon = FACTORY.createPolygon(shells.get(0), holes.toArray(LinearRing[]::new));
        if (!polygon.isValid()) {
            throw new IllegalStateException("Region " + region + " gives an invalid outline; each region must be one "
                    + "4-connected piece");
        }
        return polygon;
    }

    /** Positive for counter-clockwise rings in map coordinates. */
    private static double signedArea(Coordinate[] ring) {
        double sum = 0;
        for (int i = 0; i + 1 < ring.length; i++) {
            sum += ring[i].x * ring[i + 1].y - ring[i + 1].x * ring[i].y;
        }
        return sum / 2;
    }

    /**
     * The directed unit edges of one region. Each edge's successor is the next edge from its end corner;
     * at a corner with two, the right turn. That makes the successors a one-to-one map, so the edges fall
     * into closed rings.
     */
    private static final class Edges {
        private int[] startI = new int[64];
        private int[] startJ = new int[64];
        private int[] direction = new int[64];
        private int size;
        /** Start corner to its outgoing edge ids; the second slot is -1 unless the region touches itself there. */
        private final Map<Long, int[]> outgoing = new HashMap<>();

        void add(int i, int j, int dir) {
            if (size == startI.length) {
                startI = Arrays.copyOf(startI, size * 2);
                startJ = Arrays.copyOf(startJ, size * 2);
                direction = Arrays.copyOf(direction, size * 2);
            }
            startI[size] = i;
            startJ[size] = j;
            direction[size] = dir;
            int[] out = outgoing.computeIfAbsent(key(i, j), k -> new int[] {-1, -1});
            out[out[0] < 0 ? 0 : 1] = size;
            size++;
        }

        private int successor(int edge) {
            int dir = direction[edge];
            int[] out = outgoing.get(key(startI[edge] + DX[dir], startJ[edge] + DY[dir]));
            if (out[1] < 0) {
                return out[0];
            }
            int right = (dir + 3) % 4;
            return direction[out[0]] == right ? out[0] : out[1];
        }

        List<Coordinate[]> rings(PixelGrid grid, Junction junction) {
            List<Coordinate[]> rings = new ArrayList<>();
            boolean[] used = new boolean[size];
            List<Integer> cycle = new ArrayList<>();
            for (int first = 0; first < size; first++) {
                if (used[first]) {
                    continue;
                }
                cycle.clear();
                for (int e = first; !used[e]; e = successor(e)) {
                    used[e] = true;
                    cycle.add(e);
                }
                List<Coordinate> points = new ArrayList<>();
                for (int m = 0; m < cycle.size(); m++) {
                    int e = cycle.get(m);
                    int previous = cycle.get((m + cycle.size() - 1) % cycle.size());
                    if (direction[e] != direction[previous] || junction.at(startI[e], startJ[e])) {
                        points.add(corner(grid, startI[e], startJ[e]));
                    }
                }
                points.add(points.get(0));
                rings.add(points.toArray(Coordinate[]::new));
            }
            return rings;
        }

        private static long key(int i, int j) {
            return ((long) i << 32) | (j & 0xFFFFFFFFL);
        }

        private static Coordinate corner(PixelGrid grid, int i, int j) {
            return new Coordinate(grid.originX() + i * grid.pixelSize(), grid.originY() - j * grid.pixelSize());
        }
    }
}
