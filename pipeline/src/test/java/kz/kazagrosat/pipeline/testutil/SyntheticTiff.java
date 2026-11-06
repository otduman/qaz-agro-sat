package kz.kazagrosat.pipeline.testutil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * Writes small tiled GeoTIFFs laid out like the Sentinel-2 COGs on Earth Search: a gap before the
 * first directory, Deflate tiles with predictor 2, GeoTIFF tags and a GDAL nodata tag.
 */
public final class SyntheticTiff {

    public interface Pixels {
        int at(int x, int y);
    }

    private record Entry(int tag, int type, int count, byte[] value) {}

    private static final int SHORT = 3;
    private static final int LONG = 4;
    private static final int ASCII = 2;
    private static final int DOUBLE = 12;

    private final int width;
    private final int height;
    private final int tileWidth;
    private final int tileHeight;
    private final Set<Integer> sparseTiles = new HashSet<>();
    private Pixels pixels = (x, y) -> 0;
    private ByteOrder order = ByteOrder.LITTLE_ENDIAN;
    private int magic = 42;
    private int bits = 16;
    private int compression = 8;
    private int predictor = 2;
    private int samples = 1;
    private int sampleFormat = 1;
    private byte[] rawTile;
    private int ghostBytes = 16;
    private int epsg = 32641;
    private double originX = 499980;
    private double originY = 6000000;
    private double pixelSize = 10;
    private String nodata = "0";
    private boolean corruptTiles;

    public SyntheticTiff(int width, int height, int tileWidth, int tileHeight) {
        this.width = width;
        this.height = height;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
    }

    public SyntheticTiff pixels(Pixels value) { pixels = value; return this; }
    public SyntheticTiff order(ByteOrder value) { order = value; return this; }
    public SyntheticTiff magic(int value) { magic = value; return this; }
    public SyntheticTiff bits(int value) { bits = value; return this; }
    public SyntheticTiff compression(int value) { compression = value; return this; }
    public SyntheticTiff predictor(int value) { predictor = value; return this; }
    public SyntheticTiff samples(int value) { samples = value; return this; }
    public SyntheticTiff sampleFormat(int value) { sampleFormat = value; return this; }
    public SyntheticTiff ghostBytes(int value) { ghostBytes = value; return this; }
    public SyntheticTiff nodata(String value) { nodata = value; return this; }
    public SyntheticTiff sparseTile(int index) { sparseTiles.add(index); return this; }
    public SyntheticTiff corruptTiles() { corruptTiles = true; return this; }

    /** Stores these bytes as every tile, before compression, instead of encoding the pixels. */
    public SyntheticTiff rawTile(byte[] value) { rawTile = value; return this; }

    public SyntheticTiff geo(int code, double x, double y, double size) {
        epsg = code;
        originX = x;
        originY = y;
        pixelSize = size;
        return this;
    }

    public byte[] build() {
        int across = (width + tileWidth - 1) / tileWidth;
        int down = (height + tileHeight - 1) / tileHeight;
        byte[][] tiles = new byte[across * down][];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = sparseTiles.contains(i) ? new byte[0] : encodeTile(i % across, i / across);
        }

        long[] counts = new long[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            counts[i] = tiles[i].length;
        }
        long[] offsets = new long[tiles.length];

        // Placeholder offsets give the layout its final size; real values are filled in below.
        List<Entry> entries = entries(offsets, counts);
        int ifdStart = 8 + ghostBytes;
        int valuesStart = ifdStart + 2 + entries.size() * 12 + 4;
        int dataStart = valuesStart;
        for (Entry entry : entries) {
            dataStart += entry.value().length > 4 ? entry.value().length : 0;
        }
        int position = dataStart;
        for (int i = 0; i < tiles.length; i++) {
            offsets[i] = tiles[i].length == 0 ? 0 : position;
            position += tiles[i].length;
        }
        entries = entries(offsets, counts);

        ByteBuffer file = ByteBuffer.allocate(position).order(order);
        file.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        file.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        file.putShort((short) magic);
        file.putInt(ifdStart);
        file.position(ifdStart);
        file.putShort((short) entries.size());
        int valuePosition = valuesStart;
        for (Entry entry : entries) {
            file.putShort((short) entry.tag());
            file.putShort((short) entry.type());
            file.putInt(entry.count());
            if (entry.value().length > 4) {
                file.putInt(valuePosition);
                int resume = file.position();
                file.position(valuePosition);
                file.put(entry.value());
                file.position(resume);
                valuePosition += entry.value().length;
            } else {
                byte[] inline = new byte[4];
                System.arraycopy(entry.value(), 0, inline, 0, entry.value().length);
                file.put(inline);
            }
        }
        file.putInt(0);
        file.position(dataStart);
        for (byte[] tile : tiles) {
            file.put(tile);
        }
        return file.array();
    }

    private List<Entry> entries(long[] offsets, long[] counts) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(256, LONG, 1, longs(width)));
        list.add(new Entry(257, LONG, 1, longs(height)));
        list.add(new Entry(258, SHORT, 1, shorts(bits)));
        list.add(new Entry(259, SHORT, 1, shorts(compression)));
        list.add(new Entry(277, SHORT, 1, shorts(samples)));
        list.add(new Entry(284, SHORT, 1, shorts(1)));
        list.add(new Entry(317, SHORT, 1, shorts(predictor)));
        list.add(new Entry(322, SHORT, 1, shorts(tileWidth)));
        list.add(new Entry(323, SHORT, 1, shorts(tileHeight)));
        list.add(new Entry(324, LONG, offsets.length, longs(offsets)));
        list.add(new Entry(325, LONG, counts.length, longs(counts)));
        list.add(new Entry(339, SHORT, 1, shorts(sampleFormat)));
        list.add(new Entry(33550, DOUBLE, 3, doubles(pixelSize, pixelSize, 0)));
        list.add(new Entry(33922, DOUBLE, 6, doubles(0, 0, 0, originX, originY, 0)));
        list.add(new Entry(34735, SHORT, 16, shorts(
                1, 1, 0, 3,
                1024, 0, 1, 1,
                1025, 0, 1, 1,
                3072, 0, 1, epsg)));
        byte[] text = (nodata + "\0").getBytes(StandardCharsets.US_ASCII);
        list.add(new Entry(42113, ASCII, text.length, text));
        return list;
    }

    private byte[] encodeTile(int tileCol, int tileRow) {
        if (rawTile != null) {
            return compressed(rawTile);
        }
        int[] values = new int[tileWidth * tileHeight];
        for (int y = 0; y < tileHeight; y++) {
            for (int x = 0; x < tileWidth; x++) {
                int imageX = tileCol * tileWidth + x;
                int imageY = tileRow * tileHeight + y;
                if (imageX < width && imageY < height) {
                    values[y * tileWidth + x] = pixels.at(imageX, imageY);
                }
            }
        }
        if (predictor == 2) {
            int mask = (1 << bits) - 1;
            for (int y = 0; y < tileHeight; y++) {
                for (int x = tileWidth - 1; x > 0; x--) {
                    int i = y * tileWidth + x;
                    values[i] = (values[i] - values[i - 1]) & mask;
                }
            }
        }
        ByteBuffer raw = ByteBuffer.allocate(values.length * bits / 8).order(order);
        for (int value : values) {
            if (bits == 32) {
                raw.putInt(value);
            } else if (bits == 16) {
                raw.putShort((short) value);
            } else {
                raw.put((byte) value);
            }
        }
        if (corruptTiles) {
            return new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        }
        return compressed(raw.array());
    }

    private byte[] compressed(byte[] raw) {
        return compression == 8 || compression == 32946 ? deflate(raw) : raw;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private byte[] longs(long... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(order);
        for (long value : values) {
            buffer.putInt((int) value);
        }
        return buffer.array();
    }

    private byte[] shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(order);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private byte[] doubles(double... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(order);
        for (double value : values) {
            buffer.putDouble(value);
        }
        return buffer.array();
    }
}
