package kz.kazagrosat.core.series;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FieldInteriorsTest {

    private static void fill(int[] labels, int width, int x0, int y0, int x1, int y1, int label) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                labels[y * width + x] = label;
            }
        }
    }

    /** The pixels of the rectangle x0..x1, y0..y1 in row-major order. */
    private static int[] rectangle(int width, int x0, int y0, int x1, int y1) {
        return IntStream.rangeClosed(y0, y1)
                .flatMap(y -> IntStream.rangeClosed(x0, x1).map(x -> y * width + x))
                .toArray();
    }

    @Test
    void aRectangleShrinksByTwoPixelsOnEverySide() {
        int width = 30;
        int height = 20;
        int[] labels = new int[width * height];
        fill(labels, width, 5, 5, 24, 14, 1); // 20 x 10

        FieldInteriors interiors = FieldInteriors.of(labels, width, height, 2);

        assertEquals(1, interiors.fieldCount());
        assertArrayEquals(rectangle(width, 7, 7, 22, 12), interiors.pixelsOf(1), "16 x 6");
    }

    @Test
    void theImageEdgeCountsAsAnotherField() {
        int width = 20;
        int height = 10;
        int[] labels = new int[width * height];
        fill(labels, width, 0, 0, 9, 9, 1);
        fill(labels, width, 10, 0, 19, 9, 2);

        FieldInteriors interiors = FieldInteriors.of(labels, width, height, 2);

        assertEquals(2, interiors.fieldCount());
        assertArrayEquals(rectangle(width, 2, 2, 7, 7), interiors.pixelsOf(1));
        assertArrayEquals(rectangle(width, 12, 2, 17, 7), interiors.pixelsOf(2));
    }

    @Test
    void aThinFieldHasNoInterior() {
        int width = 30;
        int height = 30;
        int[] labels = new int[width * height];
        fill(labels, width, 0, 0, 29, 29, 1);
        fill(labels, width, 10, 3, 13, 26, 2); // 4 pixels wide

        FieldInteriors interiors = FieldInteriors.of(labels, width, height, 2);

        assertEquals(0, interiors.pixelsOf(2).length);
    }

    @Test
    void aHoleShrinksItsSurroundingsToo() {
        int width = 30;
        int height = 30;
        int[] labels = new int[width * height];
        fill(labels, width, 0, 0, 29, 29, 1);
        labels[15 * width + 15] = 0; // a one-pixel hole in the middle

        int[] interior = FieldInteriors.of(labels, width, height, 2).pixelsOf(1);

        for (int y = 13; y <= 17; y++) {
            for (int x = 13; x <= 17; x++) {
                int pixel = y * width + x;
                assertTrue(java.util.Arrays.binarySearch(interior, pixel) < 0,
                        "pixel " + x + "," + y + " is within 2 of the hole");
            }
        }
        assertEquals(26 * 26 - 25, interior.length);
    }

    @Test
    void aFieldNumberWithNoPixelsHasAnEmptyInterior() {
        int[] labels = new int[100];
        java.util.Arrays.fill(labels, 3);

        FieldInteriors interiors = FieldInteriors.of(labels, 10, 10, 2);

        assertEquals(3, interiors.fieldCount());
        assertEquals(0, interiors.pixelsOf(1).length);
        assertEquals(36, interiors.pixelsOf(3).length);
        assertThrows(IllegalArgumentException.class, () -> interiors.pixelsOf(4));
        assertThrows(IllegalArgumentException.class, () -> interiors.pixelsOf(0));
    }

    @Test
    void noShrinkKeepsEveryPixel() {
        int[] labels = new int[100];
        fill(labels, 10, 0, 0, 4, 9, 1);
        fill(labels, 10, 5, 0, 9, 9, 2);

        assertEquals(50, FieldInteriors.of(labels, 10, 10, 0).pixelsOf(1).length);
    }

    @Test
    void refusesLabelsOfTheWrongSize() {
        assertThrows(IllegalArgumentException.class, () -> FieldInteriors.of(new int[99], 10, 10, 2));
    }
}
