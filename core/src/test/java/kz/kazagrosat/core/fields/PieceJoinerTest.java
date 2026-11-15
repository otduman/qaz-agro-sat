package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PieceJoinerTest {

    private static final FieldParameters P = FieldParameters.DEFAULTS;
    private static final int HEIGHT = 100;
    private static final int DATES = 10;
    private static final double PIXEL = 10;

    /** Side-by-side strips 100 pixels high; strip k holds label k + 1 and is widths[k] pixels wide. */
    private static final class Strips {
        final int width;
        final int[] labels;
        final float[] lines;
        final RegionSeries series;
        final int[] pixels;

        /** @param borderLines line strength on the two columns either side of each border */
        Strips(int[] widths, float[] borderLines, float[][] curves) {
            width = Arrays.stream(widths).sum();
            int count = widths.length;
            labels = new int[width * HEIGHT];
            lines = new float[width * HEIGHT];
            pixels = new int[count + 1];
            int[] stripOf = new int[width];
            int x0 = 0;
            for (int k = 0; k < count; k++) {
                for (int x = x0; x < x0 + widths[k]; x++) {
                    stripOf[x] = k;
                }
                x0 += widths[k];
            }
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < width; x++) {
                    int strip = stripOf[x];
                    labels[y * width + x] = strip + 1;
                    pixels[strip + 1]++;
                    if (x + 1 < width && stripOf[x + 1] != strip) {
                        lines[y * width + x] = borderLines[strip];
                        lines[y * width + x + 1] = borderLines[strip];
                    }
                }
            }
            series = new RegionSeries(count, DATES);
            for (int d = 0; d < DATES; d++) {
                float[] ndvi = new float[width * HEIGHT];
                boolean[] clear = new boolean[width * HEIGHT];
                for (int i = 0; i < ndvi.length; i++) {
                    ndvi[i] = curves[stripOf[i % width]][d];
                    clear[i] = true;
                }
                series.add(d, labels, ndvi, clear);
            }
        }

        int[] join() {
            return PieceJoiner.join(labels, lines, width, HEIGHT, series, pixels, PIXEL, P);
        }
    }

    private static float[] curve(float value) {
        float[] curve = new float[DATES];
        Arrays.fill(curve, value);
        return curve;
    }

    /** Lower than {@code value} by {@code drop} on dates 1, 3, 5 and 7. */
    private static float[] wetter(float value, float drop) {
        float[] curve = curve(value);
        for (int d = 1; d < 8; d += 2) {
            curve[d] = value - drop;
        }
        return curve;
    }

    // At 10 m, 100 rows: a strip w pixels wide is w ha.

    @Test
    void aPieceWhoseCurveIsCloseJoinsItsField() {
        // 300 ha field and a 20 ha piece 0.08 lower on 4 dates: too far apart for the first pass (0.05), close
        // enough for a piece; their border is not on a straight line.
        Strips strips = new Strips(new int[] {300, 20}, new float[] {0f},
                new float[][] {curve(0.6f), wetter(0.6f, 0.08f)});

        assertArrayEquals(new int[] {0, 1, 1}, strips.join());
    }

    @Test
    void aPieceBehindAStraightBorderStaysApart() {
        Strips strips = new Strips(new int[] {300, 20}, new float[] {0.1f},
                new float[][] {curve(0.6f), wetter(0.6f, 0.08f)});

        assertArrayEquals(new int[] {0, 1, 2}, strips.join());
    }

    @Test
    void aPieceWithAFarCurveStaysApart() {
        Strips strips = new Strips(new int[] {300, 20}, new float[] {0f},
                new float[][] {curve(0.6f), wetter(0.6f, 0.2f)});

        assertArrayEquals(new int[] {0, 1, 2}, strips.join());
    }

    @Test
    void twoLargeRegionsAreNotPieces() {
        // 300 ha beside 200 ha: the smaller is over the piece size, so they stay apart whatever their curves.
        Strips strips = new Strips(new int[] {300, 200}, new float[] {0f},
                new float[][] {curve(0.6f), wetter(0.6f, 0.08f)});

        assertArrayEquals(new int[] {0, 1, 2}, strips.join());
    }

    @Test
    void aPieceTooLargeForItsNeighbourStaysApart() {
        // A 40 ha piece beside a 50 ha region is more than the share of it a piece may be.
        Strips strips = new Strips(new int[] {50, 40}, new float[] {0f},
                new float[][] {curve(0.6f), wetter(0.6f, 0.08f)});

        assertArrayEquals(new int[] {0, 1, 2}, strips.join());
    }

    @Test
    void aPieceJoinsTheNeighbourWithTheClosestCurve() {
        // A 20 ha piece between two 300 ha fields; it is 0.09 from the left one and 0.06 from the right one.
        Strips strips = new Strips(new int[] {300, 20, 300}, new float[] {0f, 0f},
                new float[][] {curve(0.69f), curve(0.6f), curve(0.54f)});

        assertArrayEquals(new int[] {0, 1, 2, 2}, strips.join());
    }

    @Test
    void piecesJoinInRounds() {
        // Two 10 ha pieces side by side at the end of a field; only the inner one touches the field. It joins the
        // field in the first round, and the outer one joins the grown field in the next.
        Strips strips = new Strips(new int[] {300, 10, 10}, new float[] {0f, 0f},
                new float[][] {curve(0.6f), wetter(0.6f, 0.06f), wetter(0.6f, 0.07f)});

        assertArrayEquals(new int[] {0, 1, 1, 1}, strips.join());
    }
}
