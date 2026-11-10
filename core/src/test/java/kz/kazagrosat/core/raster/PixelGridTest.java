package kz.kazagrosat.core.raster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import kz.kazagrosat.core.geo.Extent;
import org.junit.jupiter.api.Test;

class PixelGridTest {

    /** The SCL grid of Sentinel-2 tile T41UMV: 20 m pixels, 5490 x 5490. */
    private static final PixelGrid TILE_20M = new PixelGrid(32641, 399960, 6000000, 20, 5490, 5490);

    @Test
    void windowCoversTheExtentOutToWholePixels() {
        Extent extent = new Extent(460153.895, 5922373.121, 480161.199, 5942523.845);

        PixelWindow window = TILE_20M.windowCovering(extent);

        // Hand-derived: col floor(60193.895/20)=3009, right ceil(80201.199/20)=4011,
        // row floor(57476.155/20)=2873, bottom ceil(77626.879/20)=3882.
        assertEquals(new PixelWindow(3009, 2873, 1002, 1009), window);
    }

    @Test
    void extentOnPixelEdgesGainsNoExtraPixels() {
        Extent extent = new Extent(399960 + 200, 6000000 - 400, 399960 + 400, 6000000 - 200);

        assertEquals(new PixelWindow(10, 10, 10, 10), TILE_20M.windowCovering(extent));
    }

    @Test
    void extentPartlyOutsideTheGridIsRejected() {
        Extent crossesWestEdge = new Extent(399900, 5999000, 400100, 5999900);

        assertThrows(IllegalArgumentException.class, () -> TILE_20M.windowCovering(crossesWestEdge));
    }

    @Test
    void anExtentPokingSlightlyOutIsClippedToTheGrid() {
        Extent crossesWestEdge = new Extent(399940, 5999000, 400100, 5999900);

        PixelWindow window = TILE_20M.windowClippedTo(crossesWestEdge, 1000);

        assertEquals(new PixelWindow(0, 5, 7, 45), window, "the 20 m west of the grid are left out");
    }

    @Test
    void anExtentFarOutsideIsStillRejected() {
        Extent wellWest = new Extent(398000, 5999000, 400100, 5999900);

        assertThrows(IllegalArgumentException.class, () -> TILE_20M.windowClippedTo(wellWest, 1000));
    }

    @Test
    void extentPastTheBottomEdgeIsRejected() {
        Extent crossesSouthEdge = new Extent(400000, 5890000, 400100, 5890300);

        assertThrows(IllegalArgumentException.class, () -> TILE_20M.windowCovering(crossesSouthEdge));
    }

    @Test
    void scalingAWindowKeepsItAlignedOnTheFinerGrid() {
        assertEquals(new PixelWindow(6018, 5746, 2004, 2018),
                new PixelWindow(3009, 2873, 1002, 1009).scaled(2));
    }

    @Test
    void subGridStartsAtTheWindowsTopLeftCorner() {
        PixelGrid sub = TILE_20M.subGrid(new PixelWindow(3009, 2873, 1002, 1009));

        assertEquals(new PixelGrid(32641, 460140, 5942540, 20, 1002, 1009), sub);
    }
}
