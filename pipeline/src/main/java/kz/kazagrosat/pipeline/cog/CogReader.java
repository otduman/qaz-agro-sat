package kz.kazagrosat.pipeline.cog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;

/**
 * Reads pixel windows from the full-resolution image of a single-band, tiled GeoTIFF holding
 * unsigned integers or 32-bit floats, fetching only the tiles a window touches. Anything else is
 * rejected with a clear error rather than read wrongly.
 */
public final class CogReader {

    /** Sentinel-2 COGs keep their whole header in the first few kilobytes. */
    private static final int HEADER_CHUNK = 64 * 1024;

    private static final int TAG_WIDTH = 256;
    private static final int TAG_HEIGHT = 257;
    private static final int TAG_BITS = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_SAMPLES = 277;
    private static final int TAG_PREDICTOR = 317;
    private static final int TAG_TILE_WIDTH = 322;
    private static final int TAG_TILE_HEIGHT = 323;
    private static final int TAG_TILE_OFFSETS = 324;
    private static final int TAG_TILE_BYTE_COUNTS = 325;
    private static final int TAG_SAMPLE_FORMAT = 339;
    private static final int TAG_PIXEL_SCALE = 33550;
    private static final int TAG_TIEPOINT = 33922;
    private static final int TAG_GEO_KEYS = 34735;
    private static final int TAG_GDAL_NODATA = 42113;
    private static final Set<Integer> WANTED_TAGS = Set.of(
            TAG_WIDTH, TAG_HEIGHT, TAG_BITS, TAG_COMPRESSION, TAG_SAMPLES, TAG_PREDICTOR,
            TAG_TILE_WIDTH, TAG_TILE_HEIGHT, TAG_TILE_OFFSETS, TAG_TILE_BYTE_COUNTS,
            TAG_SAMPLE_FORMAT, TAG_PIXEL_SCALE, TAG_TIEPOINT, TAG_GEO_KEYS, TAG_GDAL_NODATA);

    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_DOUBLE = 12;

    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_DEFLATE = 8;
    private static final int COMPRESSION_DEFLATE_OLD = 32946;
    private static final int PREDICTOR_NONE = 1;
    private static final int PREDICTOR_HORIZONTAL = 2;
    private static final int PREDICTOR_FLOATING_POINT = 3;
    private static final int SAMPLE_UNSIGNED = 1;
    private static final int SAMPLE_FLOAT = 3;
    private static final int GEO_KEY_PROJECTED_CRS = 3072;

    private final ByteSource source;
    private final ByteOrder order;
    private final PixelGrid grid;
    private final boolean floating;
    private final int bits;
    private final int compression;
    private final int predictor;
    private final int tileWidth;
    private final int tileHeight;
    private final int tilesAcross;
    private final long[] tileOffsets;
    private final long[] tileByteCounts;

    /** The nodata value as stored: a whole number, or the raw bits of a float. */
    private final int nodata;

    private CogReader(ByteSource source, ByteOrder order, Directory directory) throws IOException {
        this.source = source;
        this.order = order;

        long samples = directory.number(TAG_SAMPLES, 1);
        if (samples != 1) {
            throw new IOException("Expected one band but the file has " + samples + " samples per pixel");
        }
        long sampleFormat = directory.number(TAG_SAMPLE_FORMAT, SAMPLE_UNSIGNED);
        if (sampleFormat != SAMPLE_UNSIGNED && sampleFormat != SAMPLE_FLOAT) {
            throw new IOException("Unsupported sample format " + sampleFormat);
        }
        this.floating = sampleFormat == SAMPLE_FLOAT;
        this.bits = (int) directory.number(TAG_BITS, 1);
        if (floating ? bits != 32 : bits != 8 && bits != 16) {
            throw new IOException("Unsupported bit depth " + bits);
        }
        this.compression = (int) directory.number(TAG_COMPRESSION, COMPRESSION_NONE);
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_DEFLATE
                && compression != COMPRESSION_DEFLATE_OLD) {
            throw new IOException("Unsupported compression " + compression);
        }
        this.predictor = (int) directory.number(TAG_PREDICTOR, PREDICTOR_NONE);
        int matchingPredictor = floating ? PREDICTOR_FLOATING_POINT : PREDICTOR_HORIZONTAL;
        if (predictor != PREDICTOR_NONE && predictor != matchingPredictor) {
            throw new IOException("Unsupported predictor " + predictor);
        }
        if (!directory.has(TAG_TILE_WIDTH) || !directory.has(TAG_TILE_OFFSETS)) {
            throw new IOException("The file is not tiled");
        }
        this.tileWidth = (int) directory.number(TAG_TILE_WIDTH, 0);
        this.tileHeight = (int) directory.number(TAG_TILE_HEIGHT, 0);
        this.tileOffsets = directory.numbers(TAG_TILE_OFFSETS);
        this.tileByteCounts = directory.numbers(TAG_TILE_BYTE_COUNTS);
        int width = (int) directory.number(TAG_WIDTH, 0);
        int height = (int) directory.number(TAG_HEIGHT, 0);
        this.tilesAcross = (width + tileWidth - 1) / tileWidth;
        this.grid = readGrid(directory, width, height);
        this.nodata = readNodata(directory, floating);
    }

    private static int readNodata(Directory directory, boolean floating) throws IOException {
        if (!directory.has(TAG_GDAL_NODATA)) {
            return floating ? Float.floatToRawIntBits(Float.NaN) : 0;
        }
        String text = directory.text(TAG_GDAL_NODATA);
        if (!floating) {
            return Integer.parseInt(text);
        }
        // GDAL writes "nan" in lower case, which Java's parser does not accept.
        return Float.floatToRawIntBits(text.equalsIgnoreCase("nan") ? Float.NaN : Float.parseFloat(text));
    }

    public static CogReader open(ByteSource source) throws IOException, InterruptedException {
        byte[] head = source.read(0, HEADER_CHUNK);
        ByteOrder order = detectOrder(head);
        ByteBuffer buffer = ByteBuffer.wrap(head).order(order);
        int magic = Short.toUnsignedInt(buffer.getShort(2));
        if (magic == 43) {
            throw new IOException("BigTIFF files are not supported");
        }
        if (magic != 42) {
            throw new IOException("This is not a TIFF file");
        }
        long directoryOffset = Integer.toUnsignedLong(buffer.getInt(4));
        return new CogReader(source, order, Directory.read(new Header(source, head), directoryOffset, order));
    }

    private static ByteOrder detectOrder(byte[] head) throws IOException {
        if (head.length >= 8 && head[0] == 'I' && head[1] == 'I') {
            return ByteOrder.LITTLE_ENDIAN;
        }
        if (head.length >= 8 && head[0] == 'M' && head[1] == 'M') {
            return ByteOrder.BIG_ENDIAN;
        }
        throw new IOException("This is not a TIFF file");
    }

    private static PixelGrid readGrid(Directory directory, int width, int height) throws IOException {
        double[] scale = directory.doubles(TAG_PIXEL_SCALE);
        double[] tiepoint = directory.doubles(TAG_TIEPOINT);
        if (scale[0] != scale[1]) {
            throw new IOException("Pixels are not square: " + scale[0] + " x " + scale[1]);
        }
        double originX = tiepoint[3] - tiepoint[0] * scale[0];
        double originY = tiepoint[4] + tiepoint[1] * scale[1];
        return new PixelGrid(readEpsg(directory), originX, originY, scale[0], width, height);
    }

    private static int readEpsg(Directory directory) throws IOException {
        long[] keys = directory.numbers(TAG_GEO_KEYS);
        for (int i = 4; i + 3 < keys.length; i += 4) {
            if (keys[i] == GEO_KEY_PROJECTED_CRS) {
                return (int) keys[i + 3];
            }
        }
        throw new IOException("The file does not name a projected coordinate system");
    }

    public PixelGrid grid() {
        return grid;
    }

    /** Returns the window's unsigned pixel values, row by row. */
    public int[] readWindow(PixelWindow window) throws IOException, InterruptedException {
        if (floating) {
            throw new IllegalStateException("This file holds floats: use readFloatWindow");
        }
        return stitch(window);
    }

    /** Returns the window's float pixel values, row by row, with NaN for no data. */
    public float[] readFloatWindow(PixelWindow window) throws IOException, InterruptedException {
        if (!floating) {
            throw new IllegalStateException("This file holds whole numbers: use readWindow");
        }
        int[] stored = stitch(window);
        float[] pixels = new float[stored.length];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = stored[i] == nodata ? Float.NaN : Float.intBitsToFloat(stored[i]);
        }
        return pixels;
    }

    /** Assembles a window from its tiles. Float pixels pass through as their raw bits. */
    private int[] stitch(PixelWindow window) throws IOException, InterruptedException {
        int right = window.col() + window.width();
        int bottom = window.row() + window.height();
        if (window.col() < 0 || window.row() < 0 || right > grid.width() || bottom > grid.height()) {
            throw new IllegalArgumentException("Window " + window + " is outside the image " + grid);
        }
        int[] pixels = new int[window.width() * window.height()];
        for (int tileRow = window.row() / tileHeight; tileRow <= (bottom - 1) / tileHeight; tileRow++) {
            for (int tileCol = window.col() / tileWidth; tileCol <= (right - 1) / tileWidth; tileCol++) {
                int[] tile = readTile(tileRow * tilesAcross + tileCol);
                int startX = Math.max(window.col(), tileCol * tileWidth);
                int endX = Math.min(right, (tileCol + 1) * tileWidth);
                int startY = Math.max(window.row(), tileRow * tileHeight);
                int endY = Math.min(bottom, (tileRow + 1) * tileHeight);
                for (int y = startY; y < endY; y++) {
                    int target = (y - window.row()) * window.width() + (startX - window.col());
                    if (tile == null) {
                        Arrays.fill(pixels, target, target + endX - startX, nodata);
                    } else {
                        int from = (y - tileRow * tileHeight) * tileWidth + (startX - tileCol * tileWidth);
                        System.arraycopy(tile, from, pixels, target, endX - startX);
                    }
                }
            }
        }
        return pixels;
    }

    /** Returns one decoded tile, or null for a sparse tile that holds no data. */
    private int[] readTile(int index) throws IOException, InterruptedException {
        int storedSize = Math.toIntExact(tileByteCounts[index]);
        if (storedSize == 0) {
            return null;
        }
        byte[] stored = source.read(tileOffsets[index], storedSize);
        if (stored.length < storedSize) {
            throw new IOException("Tile " + index + " is cut short: got " + stored.length + " of " + storedSize);
        }
        int rawSize = tileWidth * tileHeight * (bits / 8);
        byte[] raw = compression == COMPRESSION_NONE ? stored : inflate(stored, rawSize, index);
        return floating ? decodeFloatBits(raw) : decodeSamples(raw);
    }

    private static byte[] inflate(byte[] stored, int rawSize, int index) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(stored);
            byte[] raw = new byte[rawSize];
            int filled = 0;
            while (filled < rawSize && !inflater.finished()) {
                int count = inflater.inflate(raw, filled, rawSize - filled);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                filled += count;
            }
            if (filled != rawSize) {
                throw new IOException("Tile " + index + " decoded to " + filled + " of " + rawSize + " bytes");
            }
            return raw;
        } catch (DataFormatException e) {
            throw new IOException("Tile " + index + " is corrupt", e);
        } finally {
            inflater.end();
        }
    }

    private int[] decodeSamples(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(order);
        int[] samples = new int[tileWidth * tileHeight];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = bits == 16 ? Short.toUnsignedInt(buffer.getShort()) : Byte.toUnsignedInt(buffer.get());
        }
        if (predictor == PREDICTOR_HORIZONTAL) {
            int mask = (1 << bits) - 1;
            for (int y = 0; y < tileHeight; y++) {
                int row = y * tileWidth;
                for (int x = 1; x < tileWidth; x++) {
                    samples[row + x] = (samples[row + x] + samples[row + x - 1]) & mask;
                }
            }
        }
        return samples;
    }

    private int[] decodeFloatBits(byte[] raw) {
        int[] samples = new int[tileWidth * tileHeight];
        if (predictor != PREDICTOR_FLOATING_POINT) {
            ByteBuffer.wrap(raw).order(order).asIntBuffer().get(samples);
            return samples;
        }
        // Each row holds the first byte of every pixel, then the second, and so on, most significant
        // first and whatever the file's byte order, with every byte stored as a running difference.
        int rowBytes = tileWidth * Float.BYTES;
        for (int y = 0; y < tileHeight; y++) {
            int row = y * rowBytes;
            for (int i = 1; i < rowBytes; i++) {
                raw[row + i] += raw[row + i - 1];
            }
            for (int x = 0; x < tileWidth; x++) {
                samples[y * tileWidth + x] = Byte.toUnsignedInt(raw[row + x]) << 24
                        | Byte.toUnsignedInt(raw[row + tileWidth + x]) << 16
                        | Byte.toUnsignedInt(raw[row + 2 * tileWidth + x]) << 8
                        | Byte.toUnsignedInt(raw[row + 3 * tileWidth + x]);
            }
        }
        return samples;
    }

    /** The first bytes of the file, falling back to the source for anything stored past them. */
    private record Header(ByteSource source, byte[] head) {

        byte[] bytes(long offset, int length) throws IOException, InterruptedException {
            if (offset + length <= head.length) {
                return Arrays.copyOfRange(head, (int) offset, (int) offset + length);
            }
            byte[] fetched = source.read(offset, length);
            if (fetched.length < length) {
                throw new IOException("The TIFF header is cut short at offset " + offset);
            }
            return fetched;
        }
    }

    /** The tags of one image directory that this reader needs. */
    private static final class Directory {

        private record Entry(int type, int count, ByteBuffer value) {}

        private final Map<Integer, Entry> entries = new HashMap<>();

        static Directory read(Header header, long offset, ByteOrder order)
                throws IOException, InterruptedException {
            int count = Short.toUnsignedInt(ByteBuffer.wrap(header.bytes(offset, 2)).order(order).getShort());
            ByteBuffer table = ByteBuffer.wrap(header.bytes(offset + 2, count * 12)).order(order);
            Directory directory = new Directory();
            for (int i = 0; i < count; i++) {
                int tag = Short.toUnsignedInt(table.getShort(i * 12));
                if (!WANTED_TAGS.contains(tag)) {
                    continue;
                }
                int type = Short.toUnsignedInt(table.getShort(i * 12 + 2));
                int values = table.getInt(i * 12 + 4);
                int size = values * typeSize(type);
                ByteBuffer value;
                if (size <= 4) {
                    value = table.slice(i * 12 + 8, 4).order(order);
                } else {
                    long position = Integer.toUnsignedLong(table.getInt(i * 12 + 8));
                    value = ByteBuffer.wrap(header.bytes(position, size)).order(order);
                }
                directory.entries.put(tag, new Entry(type, values, value));
            }
            return directory;
        }

        private static int typeSize(int type) throws IOException {
            return switch (type) {
                case TYPE_ASCII -> 1;
                case TYPE_SHORT -> 2;
                case TYPE_LONG -> 4;
                case TYPE_DOUBLE -> 8;
                default -> throw new IOException("Unexpected TIFF field type " + type);
            };
        }

        boolean has(int tag) {
            return entries.containsKey(tag);
        }

        long number(int tag, long fallback) throws IOException {
            return has(tag) ? numbers(tag)[0] : fallback;
        }

        long[] numbers(int tag) throws IOException {
            Entry entry = require(tag);
            long[] values = new long[entry.count()];
            for (int i = 0; i < values.length; i++) {
                values[i] = switch (entry.type()) {
                    case TYPE_SHORT -> Short.toUnsignedLong(entry.value().getShort(i * 2));
                    case TYPE_LONG -> Integer.toUnsignedLong(entry.value().getInt(i * 4));
                    default -> throw new IOException("Tag " + tag + " is not a whole number");
                };
            }
            return values;
        }

        double[] doubles(int tag) throws IOException {
            Entry entry = require(tag);
            double[] values = new double[entry.count()];
            for (int i = 0; i < values.length; i++) {
                values[i] = entry.value().getDouble(i * 8);
            }
            return values;
        }

        String text(int tag) throws IOException {
            Entry entry = require(tag);
            byte[] bytes = new byte[entry.count()];
            entry.value().get(0, bytes);
            return new String(bytes, StandardCharsets.US_ASCII).replace("\0", "").trim();
        }

        private Entry require(int tag) throws IOException {
            Entry entry = entries.get(tag);
            if (entry == null) {
                throw new IOException("The file has no TIFF tag " + tag);
            }
            return entry;
        }
    }
}
