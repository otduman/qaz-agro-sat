package kz.kazagrosat.pipeline.cog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.testutil.CountingSource;
import kz.kazagrosat.pipeline.testutil.SyntheticTiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CogReaderTest {

    private static CogReader open(byte[] file) throws Exception {
        return CogReader.open(new CountingSource(file));
    }

    private static SyntheticTiff tenBySeven() {
        return new SyntheticTiff(10, 7, 4, 4).pixels((x, y) -> y * 100 + x);
    }

    @Test
    void readsTheGridFromGeoTiffTags() throws Exception {
        byte[] file = new SyntheticTiff(30, 20, 16, 16).geo(32641, 399960, 6000000, 20).build();

        assertEquals(new PixelGrid(32641, 399960, 6000000, 20, 30, 20), open(file).grid());
    }

    @Test
    void readsAWindowInsideOneTile() throws Exception {
        int[] pixels = open(tenBySeven().build()).readWindow(new PixelWindow(1, 1, 2, 2));

        assertArrayEquals(new int[] {101, 102, 201, 202}, pixels);
    }

    @Test
    void stitchesAWindowAcrossSeveralTiles() throws Exception {
        int[] pixels = open(tenBySeven().build()).readWindow(new PixelWindow(2, 2, 7, 4));

        assertArrayEquals(new int[] {
                202, 203, 204, 205, 206, 207, 208,
                302, 303, 304, 305, 306, 307, 308,
                402, 403, 404, 405, 406, 407, 408,
                502, 503, 504, 505, 506, 507, 508,
        }, pixels);
    }

    @Test
    void readsPartialTilesAtTheRightAndBottomEdges() throws Exception {
        int[] pixels = open(tenBySeven().build()).readWindow(new PixelWindow(8, 5, 2, 2));

        assertArrayEquals(new int[] {508, 509, 608, 609}, pixels);
    }

    @ParameterizedTest(name = "16-bit compression={0} predictor={1}")
    @CsvSource({"8, 2", "8, 1", "32946, 2", "1, 1", "1, 2"})
    void decodes16BitTilesAsUnsigned(int compression, int predictor) throws Exception {
        // Above 32767 catches sign errors; falling along the row makes predictor differences wrap.
        byte[] file = new SyntheticTiff(8, 8, 8, 8)
                .bits(16).compression(compression).predictor(predictor)
                .pixels((x, y) -> 60000 - x * 3 + y)
                .build();

        int[] pixels = open(file).readWindow(new PixelWindow(1, 1, 3, 2));

        assertArrayEquals(new int[] {59998, 59995, 59992, 59999, 59996, 59993}, pixels);
    }

    @ParameterizedTest(name = "8-bit compression={0} predictor={1}")
    @CsvSource({"8, 2", "8, 1", "1, 1"})
    void decodes8BitTilesAsUnsigned(int compression, int predictor) throws Exception {
        byte[] file = new SyntheticTiff(8, 8, 8, 8)
                .bits(8).compression(compression).predictor(predictor)
                .pixels((x, y) -> 250 - x * 3 + y)
                .build();

        int[] pixels = open(file).readWindow(new PixelWindow(1, 1, 3, 2));

        assertArrayEquals(new int[] {248, 245, 242, 249, 246, 243}, pixels);
    }

    @Test
    void readsBigEndianFiles() throws Exception {
        byte[] file = new SyntheticTiff(4, 4, 4, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .pixels((x, y) -> y * 1000 + x)
                .build();

        assertArrayEquals(new int[] {1001, 1002}, open(file).readWindow(new PixelWindow(1, 1, 2, 1)));
    }

    @Test
    void fillsSparseTilesWithTheNodataValue() throws Exception {
        byte[] file = new SyntheticTiff(8, 4, 4, 4)
                .pixels((x, y) -> 7).nodata("9").sparseTile(1).build();

        assertArrayEquals(new int[] {7, 7, 9, 9}, open(file).readWindow(new PixelWindow(2, 0, 4, 1)));
    }

    @Test
    void fetchesOnlyTheTilesTheWindowTouches() throws Exception {
        CountingSource source = new CountingSource(new SyntheticTiff(16, 16, 4, 4).build());
        CogReader reader = CogReader.open(source);
        source.resetReads();

        reader.readWindow(new PixelWindow(5, 5, 2, 2));
        assertEquals(1, source.reads(), "window inside one of 16 tiles");

        source.resetReads();
        reader.readWindow(new PixelWindow(3, 5, 2, 2));
        assertEquals(2, source.reads(), "window across two of 16 tiles");
    }

    @Test
    void opensWithOneReadWhenTheHeaderFitsTheFirstChunk() throws Exception {
        CountingSource source = new CountingSource(tenBySeven().build());

        CogReader.open(source);

        assertEquals(1, source.reads());
    }

    @Test
    void findsADirectoryBeyondTheFirstChunk() throws Exception {
        byte[] file = tenBySeven().ghostBytes(70_000).build();

        assertArrayEquals(new int[] {101}, open(file).readWindow(new PixelWindow(1, 1, 1, 1)));
    }

    @Test
    void rejectsAWindowOutsideTheImage() throws Exception {
        CogReader reader = open(tenBySeven().build());

        assertThrows(IllegalArgumentException.class, () -> reader.readWindow(new PixelWindow(8, 0, 3, 1)));
        assertThrows(IllegalArgumentException.class, () -> reader.readWindow(new PixelWindow(0, 6, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> reader.readWindow(new PixelWindow(-1, 0, 1, 1)));
    }

    @Test
    void rejectsBytesThatAreNotATiff() {
        IOException error = assertThrows(IOException.class, () -> open("not a tiff file".getBytes()));

        assertTrue(error.getMessage().contains("not a TIFF"), error.getMessage());
    }

    @Test
    void rejectsBigTiffWithAClearMessage() {
        byte[] file = tenBySeven().magic(43).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("BigTIFF"), error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"5", "50000"})
    void rejectsUnsupportedCompressionNamingTheCode(int compression) {
        byte[] file = tenBySeven().compression(compression).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("compression " + compression), error.getMessage());
    }

    @Test
    void rejectsMultiBandFiles() {
        byte[] file = tenBySeven().samples(3).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("3 samples"), error.getMessage());
    }

    @Test
    void reportsCorruptTileData() throws Exception {
        CogReader reader = open(tenBySeven().corruptTiles().build());

        assertThrows(IOException.class, () -> reader.readWindow(new PixelWindow(0, 0, 1, 1)));
    }

    @Test
    void reportsAFileCutShortInsideATile() throws Exception {
        byte[] file = tenBySeven().compression(1).predictor(1).build();
        CogReader reader = open(Arrays.copyOf(file, file.length - 5));

        // The last tile is the one the truncation damaged.
        assertThrows(IOException.class, () -> reader.readWindow(new PixelWindow(9, 6, 1, 1)));
    }
}
