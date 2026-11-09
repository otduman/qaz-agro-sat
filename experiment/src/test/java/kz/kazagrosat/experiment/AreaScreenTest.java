package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.Test;

class AreaScreenTest {

    @Test
    void landSharesLeaveOutPixelsWithoutData() {
        int[] classes = {40, 40, 40, 30, 80, 90, 0, 0, 10, 50};

        LandShares shares = LandShares.of(classes);

        assertEquals(8, shares.pixels());
        assertEquals(3 / 8.0, shares.cropland(), 1e-9);
        assertEquals(1 / 8.0, shares.grassland(), 1e-9);
        assertEquals(2 / 8.0, shares.water(), 1e-9, "open water and wetland");
        assertEquals(1 / 8.0, shares.trees(), 1e-9);
        assertEquals(1 / 8.0, shares.built(), 1e-9);
    }

    @Test
    void aWindowWithoutDataHasNoShares() {
        LandShares shares = LandShares.of(new int[] {0, 0, 0});

        assertEquals(0, shares.pixels());
        assertTrue(Double.isNaN(shares.cropland()));
    }

    @Test
    void squaresTileTheRegionAndEachFitsOneWorldCoverTile() {
        List<BoundingBox> squares = AreaScreen.squares(60, 66, 51, 54);

        assertEquals(20 * 15, squares.size());
        for (BoundingBox square : squares) {
            assertTrue(AreaScreen.worldCoverTile(square).isPresent(), square.toString());
        }
    }

    @Test
    void theWorldCoverTileIsNamedByItsLowerLeftCorner() {
        assertEquals(Optional.of("N51E060"), AreaScreen.worldCoverTile(new BoundingBox(62.40, 53.45, 62.70, 53.63)));
        assertEquals(Optional.of("N48E069"), AreaScreen.worldCoverTile(new BoundingBox(69.0, 50.8, 69.3, 51.0)));
        assertEquals(Optional.empty(), AreaScreen.worldCoverTile(new BoundingBox(62.9, 53.0, 63.2, 53.2)));
    }

    @Test
    void sharesInASquareReadOnlyThatSquaresPixels() {
        // A 4 x 2 degree grid of 1-degree pixels: cropland in the left column only.
        PixelGrid grid = new PixelGrid(4326, 60, 54, 1, 4, 2);
        int[] tile = {40, 30, 30, 30, 40, 30, 30, 30};

        assertEquals(1.0, AreaScreen.sharesIn(grid, tile, new BoundingBox(60, 52, 61, 54)).cropland(), 1e-9);
        assertEquals(0.0, AreaScreen.sharesIn(grid, tile, new BoundingBox(61, 52, 63, 54)).cropland(), 1e-9);
    }

    @Test
    void clearShareCountsUsableClassesAmongPixelsWithData() {
        int[] scl = {4, 5, 6, 8, 9, 3, 0, 0};

        assertEquals(3 / 6.0, AreaScreen.clearShare(scl), 1e-9);
    }

    @Test
    void theTypicalLongestGapIsTheMedianOverPixels() {
        LocalDate from = LocalDate.parse("2024-08-01");
        LocalDate to = LocalDate.parse("2024-08-31");
        List<LocalDate> dates = List.of(LocalDate.parse("2024-08-11"), LocalDate.parse("2024-08-21"));
        // Pixel 0 clear on both dates (longest 10 days), pixel 1 on the first only (20), pixel 2 never (30).
        List<boolean[]> clear = List.of(new boolean[] {true, true, false}, new boolean[] {true, false, false});

        assertEquals(20, AreaScreen.typicalLongestGap(dates, clear, from, to));
    }

    @Test
    void aDateSeenTwiceCountsOnceAndEitherPhotoCanClearAPixel() {
        LocalDate from = LocalDate.parse("2024-08-01");
        LocalDate to = LocalDate.parse("2024-08-31");
        LocalDate day = LocalDate.parse("2024-08-16");
        List<LocalDate> dates = List.of(day, day);
        List<boolean[]> clear = List.of(new boolean[] {true, false}, new boolean[] {false, true});

        assertEquals(15, AreaScreen.typicalLongestGap(dates, clear, from, to));
    }
}
