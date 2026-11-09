package kz.kazagrosat.experiment;

/**
 * NASA HLS v2.0's {@code Fmask} flags. A pixel is clear when none of cloud (bit 1), next to cloud or shadow (bit 2),
 * cloud shadow (bit 3) or snow and ice (bit 4) is set. Bit 0 is reserved in v2.0, water (bit 5) is kept, and the
 * aerosol level (bits 6 and 7) is ignored. 255 is the fill value.
 */
final class HlsFmask {

    private static final int PROBLEMS = 0b1_1110;
    private static final int FILL = 255;

    private HlsFmask() {}

    static boolean clear(int fmask) {
        return fmask != FILL && (fmask & PROBLEMS) == 0;
    }
}
