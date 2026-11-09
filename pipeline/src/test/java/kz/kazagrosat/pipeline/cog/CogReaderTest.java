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
import org.junit.jupiter.params.provider.ValueSource;

class CogReaderTest {

    private static CogReader open(byte[] file) throws Exception {
        return CogReader.open(new CountingSource(file));
    }

    private static SyntheticTiff tenBySeven() {
        return new SyntheticTiff(10, 7, 4, 4).pixels((x, y) -> y * 100 + x);
    }

    /** Laid out like the Sentinel-1 RTC files: 32-bit floats with -32768 as nodata. */
    private static SyntheticTiff floats(int width, int height, int tileWidth, int tileHeight) {
        return new SyntheticTiff(width, height, tileWidth, tileHeight)
                .bits(32).sampleFormat(3).predictor(1).nodata("-32768");
    }

    private static int bitsOf(float value) {
        return Float.floatToRawIntBits(value);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    @Test
    void readsTheGridFromGeoTiffTags() throws Exception {
        byte[] file = new SyntheticTiff(30, 20, 16, 16).geo(32641, 399960, 6000000, 20).build();

        assertEquals(new PixelGrid(32641, 399960, 6000000, 20, 30, 20), open(file).grid());
    }

    @Test
    void readsTheGridOfALonLatFile() throws Exception {
        byte[] file = new SyntheticTiff(30, 20, 16, 16).bits(8).predictor(1)
                .geo(4326, 63, 54, 0.001).geographic().build();

        assertEquals(new PixelGrid(4326, 63, 54, 0.001, 30, 20), open(file).grid());
    }

    @Test
    void readsAnOverviewOnTheFullImagesGroundWithLargerPixels() throws Exception {
        SyntheticTiff half = new SyntheticTiff(10, 6, 4, 4).pixels((x, y) -> 1000 + y * 10 + x);
        byte[] file = new SyntheticTiff(20, 12, 8, 8).geo(32641, 399960, 6000000, 20)
                .pixels((x, y) -> y * 100 + x).overview(half).build();

        CogReader overview = CogReader.open(new CountingSource(file), 1);

        assertEquals(new PixelGrid(32641, 399960, 6000000, 40, 10, 6), overview.grid());
        assertArrayEquals(new int[] {1034, 1035, 1044, 1045}, overview.readWindow(new PixelWindow(4, 3, 2, 2)));
    }

    @Test
    void levelZeroIsTheFullImage() throws Exception {
        byte[] file = new SyntheticTiff(20, 12, 8, 8).pixels((x, y) -> y * 100 + x)
                .overview(new SyntheticTiff(10, 6, 4, 4)).build();

        CogReader full = CogReader.open(new CountingSource(file), 0);

        assertEquals(20, full.grid().width());
        assertArrayEquals(new int[] {1105}, full.readWindow(new PixelWindow(5, 11, 1, 1)));
    }

    @Test
    void walksAChainOfSeveralOverviews() throws Exception {
        SyntheticTiff quarter = new SyntheticTiff(5, 3, 4, 4).pixels((x, y) -> 7);
        SyntheticTiff half = new SyntheticTiff(10, 6, 4, 4).overview(quarter);
        byte[] file = new SyntheticTiff(20, 12, 8, 8).geo(32641, 0, 120, 1).overview(half).build();

        CogReader second = CogReader.open(new CountingSource(file), 2);

        assertEquals(new PixelGrid(32641, 0, 120, 4, 5, 3), second.grid());
        assertArrayEquals(new int[] {7}, second.readWindow(new PixelWindow(4, 2, 1, 1)));
    }

    @Test
    void refusesAnOverviewLevelTheFileDoesNotHave() {
        byte[] file = new SyntheticTiff(20, 12, 8, 8).overview(new SyntheticTiff(10, 6, 4, 4)).build();

        IOException error = assertThrows(IOException.class, () -> CogReader.open(new CountingSource(file), 2));

        assertTrue(error.getMessage().contains("2"), error.getMessage());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stitchesFloatPixelsAcrossTiles(boolean bigEndian) throws Exception {
        byte[] file = floats(10, 7, 4, 4)
                .order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                .pixels((x, y) -> bitsOf(y + x / 8f))
                .build();

        float[] window = open(file).readFloatWindow(new PixelWindow(3, 2, 2, 3));

        assertArrayEquals(new float[] {2.375f, 2.5f, 3.375f, 3.5f, 4.375f, 4.5f}, window);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void undoesTheFloatingPointPredictorRowByRow(boolean bigEndian) throws Exception {
        // Worked by hand from TIFF Technical Note 3. 1.0 is 3F 80 00 00 and 2.0 is 40 00 00 00. A row
        // stores the first byte of every pixel, then the second, and so on, with each byte kept as
        // its difference from the byte before. Every row starts afresh, and the layout is the same
        // whatever the byte order of the file.
        byte[] tile = bytes(
                0x3F, 0x01, 0x40, 0x80, 0x00, 0x00, 0x00, 0x00,
                0x40, 0xFF, 0xC1, 0x80, 0x80, 0x00, 0x00, 0x00);
        byte[] file = floats(2, 2, 2, 2)
                .predictor(3)
                .order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                .rawTile(tile)
                .build();

        assertArrayEquals(new float[] {1f, 2f, 2f, 1f}, open(file).readFloatWindow(new PixelWindow(0, 0, 2, 2)));
    }

    @Test
    void turnsFloatNodataIntoNaN() throws Exception {
        byte[] file = floats(2, 1, 2, 2).pixels((x, y) -> bitsOf(x == 0 ? -32768f : 0.25f)).build();

        assertArrayEquals(new float[] {Float.NaN, 0.25f}, open(file).readFloatWindow(new PixelWindow(0, 0, 2, 1)));
    }

    @Test
    void fillsSparseFloatTilesWithNaN() throws Exception {
        byte[] file = floats(4, 2, 2, 2).sparseTile(1).pixels((x, y) -> bitsOf(1.5f)).build();

        assertArrayEquals(new float[] {1.5f, 1.5f, Float.NaN, Float.NaN},
                open(file).readFloatWindow(new PixelWindow(0, 0, 4, 1)));
    }

    @Test
    void acceptsNanAsTheFloatNodataValue() throws Exception {
        byte[] file = floats(2, 1, 2, 2).nodata("nan").sparseTile(0).build();

        assertArrayEquals(new float[] {Float.NaN, Float.NaN}, open(file).readFloatWindow(new PixelWindow(0, 0, 2, 1)));
    }

    @Test
    void refusesToReadFloatPixelsAsIntegers() throws Exception {
        CogReader reader = open(floats(2, 2, 2, 2).build());

        assertThrows(IllegalStateException.class, () -> reader.readWindow(new PixelWindow(0, 0, 1, 1)));
    }

    @Test
    void refusesToReadIntegerPixelsAsFloats() throws Exception {
        CogReader reader = open(tenBySeven().build());

        assertThrows(IllegalStateException.class, () -> reader.readFloatWindow(new PixelWindow(0, 0, 1, 1)));
    }

    @ParameterizedTest
    @CsvSource({"16", "64"})
    void rejectsFloatsThatAreNot32Bit(int bits) {
        byte[] file = floats(2, 2, 2, 2).bits(bits).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("bit depth " + bits), error.getMessage());
    }

    @ParameterizedTest(name = "signed 16-bit compression={0} predictor={1}")
    @CsvSource({"8, 2", "8, 1", "1, 1"})
    void decodes16BitSignedTiles(int compression, int predictor) throws Exception {
        // Laid out like NASA's HLS bands: signed 16-bit reflectance with -9999 as nodata. Crossing zero along the
        // row makes the predictor's differences wrap.
        byte[] file = new SyntheticTiff(8, 8, 4, 4)
                .bits(16).sampleFormat(2).compression(compression).predictor(predictor).nodata("-9999")
                .pixels((x, y) -> x * 300 - 600 + y)
                .build();

        int[] pixels = open(file).readWindow(new PixelWindow(1, 1, 5, 1));

        assertArrayEquals(new int[] {-299, 1, 301, 601, 901}, pixels);
    }

    @Test
    void fillsSparseSignedTilesWithANegativeNodata() throws Exception {
        byte[] file = new SyntheticTiff(8, 8, 4, 4)
                .bits(16).sampleFormat(2).nodata("-9999").sparseTile(1)
                .pixels((x, y) -> -x)
                .build();

        int[] pixels = open(file).readWindow(new PixelWindow(3, 3, 2, 1));

        assertArrayEquals(new int[] {-3, -9999}, pixels);
    }

    @Test
    void rejectsSigned8BitPixels() {
        byte[] file = tenBySeven().bits(8).sampleFormat(2).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("signed 8-bit"), error.getMessage());
    }

    @Test
    void rejectsTheFloatingPointPredictorOnIntegerPixels() {
        byte[] file = tenBySeven().predictor(3).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("predictor 3"), error.getMessage());
    }

    @Test
    void rejectsTheIntegerPredictorOnFloatPixels() {
        byte[] file = floats(2, 2, 2, 2).predictor(2).build();

        IOException error = assertThrows(IOException.class, () -> open(file));

        assertTrue(error.getMessage().contains("predictor 2"), error.getMessage());
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
