package kz.kazagrosat.core.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * Writes one band of whole numbers as a tiled, deflate-compressed GeoTIFF, keeping the values exactly
 * as given. Used to store satellite layers on disk without losing anything.
 */
public final class TiledTiffWriter {

    private static final int TILE = 256;

    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_DOUBLE = 12;

    private static final int DIRECTORY_START = 8;

    private TiledTiffWriter() {}

    /**
     * @param bits   8 or 16; every value must fit, or nothing is written
     * @param nodata the value meaning "no data", also used to pad edge tiles; null if there is none
     */
    public static void write(Path file, int[] values, PixelGrid grid, int bits, Integer nodata) throws IOException {
        if (bits != 8 && bits != 16) {
            throw new IllegalArgumentException("Only 8 or 16 bits per value are supported, not " + bits);
        }
        if (values.length != grid.width() * grid.height()) {
            throw new IllegalArgumentException(values.length + " values do not fill grid " + grid);
        }
        int max = (1 << bits) - 1;
        for (int value : values) {
            if (value < 0 || value > max) {
                throw new IllegalArgumentException("Value " + value + " does not fit in " + bits + " bits");
            }
        }
        int padding = nodata == null ? 0 : nodata;

        int across = (grid.width() + TILE - 1) / TILE;
        int down = (grid.height() + TILE - 1) / TILE;
        List<byte[]> tiles = new ArrayList<>();
        for (int tileRow = 0; tileRow < down; tileRow++) {
            for (int tileCol = 0; tileCol < across; tileCol++) {
                tiles.add(deflate(tileBytes(values, grid.width(), grid.height(), tileCol, tileRow, bits, padding)));
            }
        }

        List<Entry> entries = entries(grid, bits, nodata, tiles.size());
        int valuesStart = DIRECTORY_START + 2 + entries.size() * 12 + 4;
        int dataStart = valuesStart;
        for (Entry entry : entries) {
            dataStart += entry.outOfLine() ? entry.value().length : 0;
        }
        long[] offsets = new long[tiles.size()];
        long[] counts = new long[tiles.size()];
        long position = dataStart;
        for (int i = 0; i < tiles.size(); i++) {
            offsets[i] = position;
            counts[i] = tiles.get(i).length;
            position += counts[i];
        }
        fill(entries, 324, longs(offsets));
        fill(entries, 325, longs(counts));

        ByteBuffer header = ByteBuffer.allocate(dataStart).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(DIRECTORY_START);
        header.putShort((short) entries.size());
        int valuePosition = valuesStart;
        for (Entry entry : entries) {
            header.putShort((short) entry.tag()).putShort((short) entry.type()).putInt(entry.count());
            if (entry.outOfLine()) {
                header.putInt(valuePosition);
                header.put(valuePosition, entry.value());
                valuePosition += entry.value().length;
            } else {
                byte[] inline = new byte[4];
                System.arraycopy(entry.value(), 0, inline, 0, entry.value().length);
                header.put(inline);
            }
        }
        header.putInt(0); // no further images

        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            for (byte[] tile : tiles) {
                out.write(tile);
            }
        }
    }

    /** One tile's raw bytes, little-endian, after horizontal differencing (TIFF predictor 2). */
    private static byte[] tileBytes(int[] values, int width, int height, int tileCol, int tileRow, int bits,
                                    int padding) {
        int bytesPerValue = bits / 8;
        int mask = (1 << bits) - 1;
        ByteBuffer tile = ByteBuffer.allocate(TILE * TILE * bytesPerValue).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < TILE; y++) {
            int previous = 0;
            for (int x = 0; x < TILE; x++) {
                int column = tileCol * TILE + x;
                int row = tileRow * TILE + y;
                int value = column < width && row < height ? values[row * width + column] : padding;
                int difference = x == 0 ? value : (value - previous) & mask;
                previous = value;
                if (bits == 16) {
                    tile.putShort((short) difference);
                } else {
                    tile.put((byte) difference);
                }
            }
        }
        return tile.array();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
            byte[] buffer = new byte[64 * 1024];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** The image directory, in ascending tag order as TIFF requires. Tile offsets are filled in later. */
    private static List<Entry> entries(PixelGrid grid, int bits, Integer nodata, int tileCount) {
        List<Entry> entries = new ArrayList<>();
        entries.add(longEntry(256, grid.width()));
        entries.add(longEntry(257, grid.height()));
        entries.add(shortEntry(258, bits));
        entries.add(shortEntry(259, 8));                 // deflate
        entries.add(shortEntry(262, 1));                 // black is zero
        entries.add(shortEntry(277, 1));                 // one band
        entries.add(shortEntry(284, 1));                 // planar configuration
        entries.add(shortEntry(317, 2));                 // horizontal differencing
        entries.add(shortEntry(322, TILE));
        entries.add(shortEntry(323, TILE));
        entries.add(new Entry(324, TYPE_LONG, tileCount, new byte[tileCount * 4]));
        entries.add(new Entry(325, TYPE_LONG, tileCount, new byte[tileCount * 4]));
        entries.add(shortEntry(339, 1));                 // unsigned whole numbers
        entries.add(new Entry(33550, TYPE_DOUBLE, 3,
                doubles(grid.pixelSize(), grid.pixelSize(), 0)));
        entries.add(new Entry(33922, TYPE_DOUBLE, 6,
                doubles(0, 0, 0, grid.originX(), grid.originY(), 0)));
        entries.add(new Entry(34735, TYPE_SHORT, 16, shorts(
                1, 1, 0, 3,                              // version 1.1.0, three keys
                1024, 0, 1, 1,                           // projected model
                1025, 0, 1, 1,                           // pixels are areas
                3072, 0, 1, grid.epsg())));              // EPSG code
        if (nodata != null) {
            byte[] text = (nodata + "\0").getBytes(StandardCharsets.US_ASCII);
            entries.add(new Entry(42113, TYPE_ASCII, text.length, text));
        }
        return entries;
    }

    private record Entry(int tag, int type, int count, byte[] value) {
        boolean outOfLine() {
            return value.length > 4;
        }
    }

    private static void fill(List<Entry> entries, int tag, byte[] value) {
        for (Entry entry : entries) {
            if (entry.tag() == tag) {
                System.arraycopy(value, 0, entry.value(), 0, value.length);
            }
        }
    }

    private static Entry longEntry(int tag, long value) {
        return new Entry(tag, TYPE_LONG, 1, longs(value));
    }

    private static Entry shortEntry(int tag, int value) {
        return new Entry(tag, TYPE_SHORT, 1, shorts(value));
    }

    private static byte[] longs(long... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (long value : values) {
            buffer.putInt((int) value);
        }
        return buffer.array();
    }

    private static byte[] shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private static byte[] doubles(double... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : values) {
            buffer.putDouble(value);
        }
        return buffer.array();
    }
}
