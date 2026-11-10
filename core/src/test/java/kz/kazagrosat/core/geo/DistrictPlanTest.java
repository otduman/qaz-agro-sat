package kz.kazagrosat.core.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DistrictPlanTest {

    private static final int EPSG = 32642;
    private static final UtmProjection UTM = UtmProjection.forEpsg(EPSG);

    /** A district drawn as a UTM rectangle, 470-530 km east and 5,955-5,985 km north, in longitude and latitude. */
    private static List<LonLat> rectangle(double minE, double minN, double maxE, double maxN) {
        return List.of(UTM.unproject(minE, minN), UTM.unproject(maxE, minN), UTM.unproject(maxE, maxN),
                UTM.unproject(minE, maxN), UTM.unproject(minE, minN));
    }

    @Test
    void squaresAreLaidOnTheHundredKilometreTileGrid() {
        List<DistrictPlan.Square> squares = DistrictPlan.plan(rectangle(470_000, 5_955_000, 530_000, 5_985_000), EPSG);

        DistrictPlan.Square first = squares.stream().filter(s -> s.id().equals("E480N5960")).findFirst().orElseThrow();
        assertEquals(480_500, first.minE(), 1e-6);
        assertEquals(501_500, first.maxE(), 1e-6, "21 km wide, still inside the tile that starts at 400 km");
        assertEquals(5_979_500, first.maxN(), 1e-6);
        assertEquals(5_958_500, first.minN(), 1e-6);
        for (DistrictPlan.Square s : squares) {
            long block = (long) Math.floor((s.minE() - 1) / 100_000);
            assertTrue(s.maxE() <= block * 100_000 + 109_800 - 40, s.id() + " stays inside one tile's width");
            long top = (long) Math.ceil((s.maxN() + 1) / 100_000);
            assertTrue(s.minN() >= top * 100_000 - 109_800 + 40, s.id() + " stays inside one tile's height");
        }
    }

    @Test
    void onlySquaresTouchingTheDistrictAreKept() {
        List<DistrictPlan.Square> squares = DistrictPlan.plan(rectangle(470_000, 5_955_000, 530_000, 5_985_000), EPSG);

        // Cores (20 km cells) touching 470-530 km east: 460, 480, 500, 520; north 5,955-5,985: 5,940 to 5,980.
        assertEquals(4 * 3, squares.size(), squares.stream().map(DistrictPlan.Square::id).toList().toString());
        assertTrue(squares.stream().noneMatch(s -> s.id().equals("E540N5960")));
        DistrictPlan.Square inner = squares.stream().filter(s -> s.id().equals("E480N5960")).findFirst().orElseThrow();
        assertEquals(1.0, inner.shareInside(), 1e-9);
        DistrictPlan.Square edge = squares.stream().filter(s -> s.id().equals("E460N5960")).findFirst().orElseThrow();
        assertTrue(edge.shareInside() > 0 && edge.shareInside() < 1, "a square across the border: " + edge.shareInside());
    }

    @Test
    void theLongitudeLatitudeBoxCoversTheWholeSquare() {
        for (DistrictPlan.Square s : DistrictPlan.plan(rectangle(470_000, 5_955_000, 530_000, 5_985_000), EPSG)) {
            BoundingBox box = s.bbox();
            for (double e : new double[] {s.minE(), s.maxE()}) {
                for (double n : new double[] {s.minN(), s.maxN()}) {
                    LonLat p = UTM.unproject(e, n);
                    assertTrue(p.longitude() >= box.west() && p.longitude() <= box.east()
                            && p.latitude() >= box.south() && p.latitude() <= box.north(), s.id());
                }
            }
        }
    }

    @Test
    void everyPointLiesInExactlyOneCore() {
        List<DistrictPlan.Square> squares = DistrictPlan.plan(rectangle(470_000, 5_955_000, 530_000, 5_985_000), EPSG);

        for (double e = 475_000; e <= 525_000; e += 777) {
            for (double n = 5_960_000; n <= 5_980_000; n += 555) {
                double fe = e;
                double fn = n;
                assertEquals(1, squares.stream().filter(s -> s.inCore(fe, fn)).count(), e + ", " + n);
            }
        }
        double onBlockEdge = 500_250;
        assertEquals(1, squares.stream().filter(s -> s.inCore(onBlockEdge, 5_970_000)).count());
    }

    @Test
    void pointsInsideAPolygon() {
        List<LonLat> square = List.of(new LonLat(0, 0), new LonLat(2, 0), new LonLat(2, 2), new LonLat(0, 2),
                new LonLat(0, 0));

        assertTrue(DistrictPlan.inside(square, 1, 1));
        assertFalse(DistrictPlan.inside(square, 3, 1));
        assertFalse(DistrictPlan.inside(square, 1, -0.5));
    }
}
