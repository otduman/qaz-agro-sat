package kz.kazagrosat.experiment;

/**
 * Landsat Collection 2's {@code qa_pixel} flags. A pixel is clear when none of fill, dilated cloud, cirrus, cloud,
 * cloud shadow or snow is set (bits 0 to 5), and it is flagged clear land or water (bit 6 or 7).
 */
final class QaPixel {

    private static final int PROBLEMS = 0b11_1111;
    private static final int CLEAR_OR_WATER = 0b1100_0000;

    private QaPixel() {}

    static boolean clear(int qa) {
        return (qa & PROBLEMS) == 0 && (qa & CLEAR_OR_WATER) != 0;
    }
}
