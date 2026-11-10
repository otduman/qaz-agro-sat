package kz.kazagrosat.core.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Squares that cover a district, each small enough for one season stack and each inside a single Sentinel-2 tile.
 *
 * <p>Sentinel-2 tiles start on the 100 km UTM grid and reach 109.8 km east and south of their corner. Each 100 km
 * block is cut into 5 x 5 cores of 20 km; a square is its core moved 0.5 km inward from the block's west and north
 * edges and widened to {@link #SIZE}, so it overlaps its east and south neighbours by 1 km and still lies inside the
 * block's tile. A square is named after its core's south-west corner in kilometres, such as {@code E480N5960}.
 */
public final class DistrictPlan {

    public static final double CORE = 20_000;
    public static final double SIZE = 21_000;
    public static final double INSET = 500;
    private static final double BLOCK = 100_000;
    private static final int SAMPLES = 21;

    private DistrictPlan() {}

    /**
     * One square: its extent in UTM metres, the longitude-latitude box that covers it (for the season command) and
     * the share of it inside the district.
     */
    public record Square(String id, double minE, double minN, double maxE, double maxN, BoundingBox bbox,
                         double shareInside) {

        /**
         * Whether a point lies in the square's core: the square without the 1 km it shares with its east and south
         * neighbours. Cores cover the plane once, so each field belongs to the square whose core holds its centre.
         */
        public boolean inCore(double easting, double northing) {
            return easting >= minE && easting < minE + CORE && northing > maxN - CORE && northing <= maxN;
        }
    }

    /** The squares touching the district outlined by {@code boundary} (a closed ring), in the UTM zone of {@code epsg}. */
    public static List<Square> plan(List<LonLat> boundary, int epsg) {
        UtmProjection utm = UtmProjection.forEpsg(epsg);
        double minE = Double.MAX_VALUE;
        double maxE = -Double.MAX_VALUE;
        double minN = Double.MAX_VALUE;
        double maxN = -Double.MAX_VALUE;
        for (LonLat p : boundary) {
            UtmPoint u = utm.project(p.longitude(), p.latitude());
            minE = Math.min(minE, u.easting());
            maxE = Math.max(maxE, u.easting());
            minN = Math.min(minN, u.northing());
            maxN = Math.max(maxN, u.northing());
        }
        List<Square> squares = new ArrayList<>();
        for (double coreN = Math.floor((minN - CORE) / CORE) * CORE; coreN < maxN + CORE; coreN += CORE) {
            for (double coreE = Math.floor((minE - CORE) / CORE) * CORE; coreE < maxE + CORE; coreE += CORE) {
                double blockE = Math.floor(coreE / BLOCK) * BLOCK;
                double blockTop = Math.floor(coreN / BLOCK) * BLOCK + BLOCK;
                double sMinE = blockE + INSET + Math.round((coreE - blockE) / CORE) * CORE;
                double sMaxN = blockTop - INSET - Math.round((blockTop - coreN - CORE) / CORE) * CORE;
                Square s = square(utm, boundary, coreE, coreN, sMinE, sMaxN - SIZE, sMinE + SIZE, sMaxN);
                if (s.shareInside() > 0 || anyVertexInside(utm, boundary, s)) {
                    squares.add(s);
                }
            }
        }
        return List.copyOf(squares);
    }

    private static Square square(UtmProjection utm, List<LonLat> boundary, double coreE, double coreN,
                                 double minE, double minN, double maxE, double maxN) {
        double west = Double.MAX_VALUE;
        double east = -Double.MAX_VALUE;
        double south = Double.MAX_VALUE;
        double north = -Double.MAX_VALUE;
        int inside = 0;
        for (int i = 0; i < SAMPLES; i++) {
            for (int j = 0; j < SAMPLES; j++) {
                double e = minE + (maxE - minE) * i / (SAMPLES - 1);
                double n = minN + (maxN - minN) * j / (SAMPLES - 1);
                LonLat p = utm.unproject(e, n);
                west = Math.min(west, p.longitude());
                east = Math.max(east, p.longitude());
                south = Math.min(south, p.latitude());
                north = Math.max(north, p.latitude());
                if (inside(boundary, p.longitude(), p.latitude())) {
                    inside++;
                }
            }
        }
        // A thousandth of a degree (about 70-110 m) of margin so rounding never leaves a corner pixel out.
        double margin = 0.001;
        BoundingBox box = new BoundingBox(round(west - margin), round(south - margin), round(east + margin),
                round(north + margin));
        String id = String.format(java.util.Locale.ROOT, "E%dN%d", Math.round(coreE / 1000), Math.round(coreN / 1000));
        return new Square(id, minE, minN, maxE, maxN, box, inside / (double) (SAMPLES * SAMPLES));
    }

    private static double round(double degrees) {
        return Math.round(degrees * 1e6) / 1e6;
    }

    private static boolean anyVertexInside(UtmProjection utm, List<LonLat> boundary, Square s) {
        for (LonLat p : boundary) {
            UtmPoint u = utm.project(p.longitude(), p.latitude());
            if (u.easting() >= s.minE() && u.easting() <= s.maxE() && u.northing() >= s.minN()
                    && u.northing() <= s.maxN()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the point lies inside the closed ring (even-odd rule). */
    public static boolean inside(List<LonLat> ring, double longitude, double latitude) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i).longitude();
            double yi = ring.get(i).latitude();
            double xj = ring.get(j).longitude();
            double yj = ring.get(j).latitude();
            if ((yi > latitude) != (yj > latitude) && longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }
}
