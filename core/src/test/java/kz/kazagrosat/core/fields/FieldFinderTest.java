package kz.kazagrosat.core.fields;

import static kz.kazagrosat.core.fields.SyntheticSeason.FALLOW;
import static kz.kazagrosat.core.fields.SyntheticSeason.HEIGHT;
import static kz.kazagrosat.core.fields.SyntheticSeason.WATER;
import static kz.kazagrosat.core.fields.SyntheticSeason.WIDTH;
import static kz.kazagrosat.core.fields.SyntheticSeason.lateWheat;
import static kz.kazagrosat.core.fields.SyntheticSeason.noise;
import static kz.kazagrosat.core.fields.SyntheticSeason.wheat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class FieldFinderTest {

    private static final FieldParameters P = FieldParameters.DEFAULTS;

    private static FieldMap find(SeasonImages season) throws Exception {
        List<String> progress = new ArrayList<>();
        FieldMap map = FieldFinder.find(season, P, progress::add);
        assertTrue(progress.stream().anyMatch(line -> line.startsWith("Edges: ")), "progress: " + progress);
        return map;
    }

    private static int label(FieldMap map, int x, int y) {
        return map.labels()[y * WIDTH + x];
    }

    private static Set<Integer> regionIds(FieldMap map) {
        Set<Integer> ids = new TreeSet<>();
        for (int label : map.labels()) {
            if (label != 0) {
                ids.add(label);
            }
        }
        return ids;
    }

    private static Region region(FieldMap map, int id) {
        return map.regions().stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    @Test
    void knownFieldsComeBackExactly() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) -> x < 50 ? wheat(d) : x < 110 ? FALLOW : lateWheat(d)));

        assertEquals(Set.of(1, 2, 3), regionIds(map));
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int expected = x < 50 ? 1 : x < 110 ? 2 : 3;
                assertEquals(expected, label(map, x, y), "pixel " + x + "," + y);
            }
        }
        assertEquals(50 * HEIGHT, region(map, 1).pixels());
        assertEquals(3, map.regions().size());
    }

    @Test
    void fourFieldsMeetingAtAPointComeBackExceptAtTheJunction() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) -> {
            if (y < 60) {
                return x < 80 ? wheat(d) : FALLOW;
            }
            return x < 80 ? lateWheat(d) : WATER;
        }));

        assertEquals(4, regionIds(map).size());
        int topLeft = label(map, 0, 0);
        int topRight = label(map, WIDTH - 1, 0);
        int bottomLeft = label(map, 0, HEIGHT - 1);
        int bottomRight = label(map, WIDTH - 1, HEIGHT - 1);
        assertEquals(4, Set.of(topLeft, topRight, bottomLeft, bottomRight).size());
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                // The junction is the corner point between pixels 79/80 and rows 59/60.
                boolean nearJunction = x >= 78 && x <= 81 && y >= 58 && y <= 61;
                if (nearJunction) {
                    continue;
                }
                int expected = y < 60 ? (x < 80 ? topLeft : topRight) : (x < 80 ? bottomLeft : bottomRight);
                assertEquals(expected, label(map, x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void aBoundaryLineWithAGapStillGivesTwoFields() throws Exception {
        // Two wheat halves split by a 2-pixel bare road in columns 79 and 80, with a gap in rows 58 to 61.
        FieldMap map = find(new SyntheticSeason((x, y, d) -> {
            boolean road = (x == 79 || x == 80) && (y < 58 || y > 61);
            return road ? FALLOW : wheat(d);
        }));

        assertEquals(2, regionIds(map).size());
        int left = label(map, 0, 0);
        int right = label(map, WIDTH - 1, 0);
        assertNotEquals(left, right);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (x <= 74) {
                    assertEquals(left, label(map, x, y), "pixel " + x + "," + y);
                } else if (x >= 85) {
                    assertEquals(right, label(map, x, y), "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void aPondGivesOneFieldWithAHole() throws Exception {
        // A 20 x 20 pond (4 ha) and an 8 x 8 wet patch (0.64 ha) inside one wheat field.
        FieldMap map = find(new SyntheticSeason((x, y, d) -> {
            if (x >= 30 && x < 50 && y >= 40 && y < 60) {
                return WATER;
            }
            if (x >= 100 && x < 108 && y >= 50 && y < 58) {
                return wheat(d) - 0.2f;
            }
            return wheat(d);
        }));

        assertEquals(Set.of(1), regionIds(map));
        for (int y = 40; y < 60; y++) {
            for (int x = 30; x < 50; x++) {
                boolean corner = (x == 30 || x == 49) && (y == 40 || y == 59);
                if (!corner) {
                    assertEquals(0, label(map, x, y), "pond pixel " + x + "," + y);
                }
            }
        }
        for (int y = 50; y < 58; y++) {
            for (int x = 100; x < 108; x++) {
                assertEquals(1, label(map, x, y), "wet patch pixel " + x + "," + y);
            }
        }
        assertEquals(1, map.summary().keptHoles());
        assertEquals(1, map.summary().droppedRegions());
        assertEquals(LandLabel.FIELD, region(map, 1).land());
    }

    /**
     * Pixel-to-pixel speckle under the calm level leaves each field whole. It doesn't break the calm middles
     * up, so it doesn't exercise joining: white noise goes from all calm to no seed at all between amplitudes
     * of about 0.1 and 0.16, unlike real fields, whose texture is patchy. Joining is covered by
     * {@link #aFaintStripAcrossAFieldIsMergedBack}.
     */
    @Test
    void speckleUnderTheCalmLevelDoesNotSplitAField() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) ->
                (x < 80 ? wheat(d) : FALLOW) + noise(x, y, d, 0.04)));

        assertEquals(2, regionIds(map).size());
        int left = label(map, 0, 0);
        int right = label(map, WIDTH - 1, 0);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (x < 77) {
                    assertEquals(left, label(map, x, y), "pixel " + x + "," + y);
                } else if (x > 82) {
                    assertEquals(right, label(map, x, y), "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void aFaintRoadBetweenTwoFieldsSplitsThem() throws Exception {
        // A 1-pixel road in column 80, 0.1 lower in NDVI on every date: its edge (0.1) is under the calm level,
        // so no single date or the season edge marks it. The right field is 0.08 lower on 3 dates: too little
        // for an edge, but enough that the two curves don't match, so once split they stay split.
        FieldMap map = find(new SyntheticSeason((x, y, d) -> {
            if (x == 80) {
                return wheat(d) - 0.1f;
            }
            boolean lower = x > 80 && (d == 3 || d == 6 || d == 9);
            return lower ? wheat(d) - 0.08f : wheat(d);
        }));

        assertEquals(2, regionIds(map).size());
        int left = label(map, 0, 0);
        int right = label(map, WIDTH - 1, 0);
        assertNotEquals(left, right);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (x <= 76) {
                    assertEquals(left, label(map, x, y), "pixel " + x + "," + y);
                } else if (x >= 84) {
                    assertEquals(right, label(map, x, y), "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void aFaintStripAcrossAFieldIsMergedBack() throws Exception {
        // A 3-row wet strip, lower on 3 dates by a step between the calm level and the weak-ridge limit: its
        // edges split the calm area in two, but the ridge is weak and the two halves have the same curve, so
        // they merge back into one field.
        float depth = (float) ((P.markerEdge() + P.ridgeWeak()) / 2);
        SyntheticSeason season = new SyntheticSeason((x, y, d) -> {
            boolean wet = y >= 59 && y <= 61 && (d == 3 || d == 6 || d == 9);
            return wet ? wheat(d) - depth : wheat(d);
        });

        FieldMap map = find(season);
        FieldMap withoutMerging = FieldFinder.find(season, withRidgeWeak(0.0), line -> { });

        assertEquals(Set.of(1), regionIds(map));
        assertTrue(map.summary().merges() >= 1);
        assertEquals(2, regionIds(withoutMerging).size(), "the strip really does split the calm area");
    }

    private static FieldParameters withRidgeWeak(double ridgeWeak) {
        FieldParameters d = P;
        return new FieldParameters(d.brightnessWeight(), d.edgeRank(), d.markerEdge(), d.markerShrink(),
                d.minMarkerPixels(), d.lineRadius(), d.lineEdge(), ridgeWeak, d.curveTolerance(),
                d.minCommonDates(), d.minClearShare(),
                d.minRegionHectares(), d.maxHoleHectares(), d.minLabelDates(), d.springEnd(), d.springBare(),
                d.peakGreen(), d.peakFrom(), d.dropBy(), d.dropUntil(), d.fallowBare(), d.fallowShare(),
                d.waterMedian(), d.uniformitySpread(), d.simplifyMetres());
    }

    @Test
    void sameCropNeighboursThatDifferOnThreeDatesStillSplit() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) -> {
            boolean different = x >= 80 && (d == 3 || d == 6 || d == 9);
            return different ? wheat(d) - 0.3f : wheat(d);
        }));

        assertEquals(2, regionIds(map).size());
        assertNotEquals(label(map, 0, 0), label(map, WIDTH - 1, 0));
    }

    @Test
    void aLineOnTwoDatesDoesNotSplitAField() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) -> {
            boolean haze = y >= 60 && (d == 4 || d == 8);
            return haze ? wheat(d) - 0.3f : wheat(d);
        }));

        assertEquals(Set.of(1), regionIds(map));
        assertEquals(WIDTH * HEIGHT, region(map, 1).pixels());
    }

    @Test
    void pixelsWithTooFewClearDatesBelongToNoFieldAndAreCounted() throws Exception {
        // Block A, 30 x 30 at (40,40), and block B, 6 x 6 at (120,60), are clear on only 2 dates.
        FieldMap map = find(new SyntheticSeason((x, y, d) -> wheat(d)).clear((x, y, d) -> {
            boolean blockA = x >= 40 && x < 70 && y >= 40 && y < 70;
            boolean blockB = x >= 120 && x < 126 && y >= 60 && y < 66;
            return !(blockA || blockB) || d < 2;
        }));

        assertEquals(Set.of(1), regionIds(map));
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                boolean aWithRim = x >= 39 && x <= 70 && y >= 39 && y <= 70;
                assertEquals(aWithRim ? 0 : 1, label(map, x, y), "pixel " + x + "," + y);
            }
        }
        FieldSummary summary = map.summary();
        assertEquals(1024, summary.noDataPixels());
        assertEquals(1, summary.filledHoles());
        assertEquals(64, summary.filledPixels());
        assertEquals(1, summary.keptHoles());
    }

    @Test
    void labelsEachRegion() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) ->
                x < 40 ? wheat(d) : x < 80 ? FALLOW : x < 120 ? lateWheat(d) : WATER));

        assertEquals(Set.of(1, 2, 3, 4), regionIds(map));
        assertEquals(LandLabel.FIELD, region(map, 1).land());
        assertEquals(LandLabel.FALLOW, region(map, 2).land());
        assertEquals(LandLabel.FIELD, region(map, 3).land());
        assertEquals(LandLabel.OTHER, region(map, 4).land());
        assertEquals(1.0, region(map, 1).uniformity(), 1e-6);
    }

    @Test
    void everyPixelIsAccountedFor() throws Exception {
        FieldMap map = find(new SyntheticSeason((x, y, d) ->
                x >= 30 && x < 50 && y >= 40 && y < 60 ? WATER : wheat(d)).clear((x, y, d) ->
                !(x >= 100 && x < 130 && y >= 40 && y < 70) || d < 2));

        long inRegions = map.regions().stream().mapToLong(Region::pixels).sum();
        FieldSummary summary = map.summary();
        assertTrue(summary.droppedPixels() > 0);
        assertTrue(summary.noDataPixels() > 0);
        assertEquals((long) WIDTH * HEIGHT, inRegions + summary.droppedPixels() + summary.noDataPixels());
        assertEquals(SyntheticSeason.DATES, summary.dates());
    }
}
