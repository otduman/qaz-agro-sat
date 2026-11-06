package kz.kazagrosat.core.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kz.kazagrosat.core.raster.PixelGrid;

/** Writes one float band as an uncompressed GeoTIFF, with NaN as no data. */
public final class GeoTiffWriter {

    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_DOUBLE = 12;

    private static final int ENTRY_COUNT = 15;
    private static final int DIRECTORY_START = 8;
    private static final int PIXEL_SCALE_START = DIRECTORY_START + 2 + ENTRY_COUNT * 12 + 4;
    private static final int TIEPOINT_START = PIXEL_SCALE_START + 3 * 8;
    private static final int GEO_KEYS_START = TIEPOINT_START + 6 * 8;
    private static final int PIXELS_START = GEO_KEYS_START + 16 * 2;

    private GeoTiffWriter() {}

    public static void write(Path file, float[] values, PixelGrid grid) throws IOException {
        if (values.length != grid.width() * grid.height()) {
            throw new IllegalArgumentException(values.length + " values do not fill grid " + grid);
        }
        int pixelBytes = values.length * Float.BYTES;
        ByteBuffer tiff = ByteBuffer.allocate(PIXELS_START + pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(DIRECTORY_START);

        tiff.putShort((short) ENTRY_COUNT);
        entry(tiff, 256, TYPE_LONG, 1, grid.width());                // image width
        entry(tiff, 257, TYPE_LONG, 1, grid.height());               // image height
        shortEntry(tiff, 258, 32);                                   // bits per sample
        shortEntry(tiff, 259, 1);                                    // no compression
        shortEntry(tiff, 262, 1);                                    // black is zero
        entry(tiff, 273, TYPE_LONG, 1, PIXELS_START);                // strip offset
        shortEntry(tiff, 277, 1);                                    // samples per pixel
        entry(tiff, 278, TYPE_LONG, 1, grid.height());               // all rows in one strip
        entry(tiff, 279, TYPE_LONG, 1, pixelBytes);                  // strip byte count
        shortEntry(tiff, 284, 1);                                    // planar configuration
        shortEntry(tiff, 339, 3);                                    // IEEE floating point
        entry(tiff, 33550, TYPE_DOUBLE, 3, PIXEL_SCALE_START);       // model pixel scale
        entry(tiff, 33922, TYPE_DOUBLE, 6, TIEPOINT_START);          // model tiepoint
        entry(tiff, 34735, TYPE_SHORT, 16, GEO_KEYS_START);          // GeoKey directory
        nodataEntry(tiff);
        tiff.putInt(0);                                              // no further images

        tiff.putDouble(grid.pixelSize()).putDouble(grid.pixelSize()).putDouble(0);
        tiff.putDouble(0).putDouble(0).putDouble(0);
        tiff.putDouble(grid.originX()).putDouble(grid.originY()).putDouble(0);
        int[] geoKeys = {
                1, 1, 0, 3,                 // version 1.1.0, three keys
                1024, 0, 1, 1,              // projected model
                1025, 0, 1, 1,              // pixels are areas
                3072, 0, 1, grid.epsg(),    // EPSG code
        };
        for (int key : geoKeys) {
            tiff.putShort((short) key);
        }
        tiff.asFloatBuffer().put(values);
        Files.write(file, tiff.array());
    }

    private static void entry(ByteBuffer tiff, int tag, int type, int count, int value) {
        tiff.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    private static void shortEntry(ByteBuffer tiff, int tag, int value) {
        tiff.putShort((short) tag).putShort((short) TYPE_SHORT).putInt(1);
        tiff.putShort((short) value).putShort((short) 0);
    }

    /** GDAL_NODATA: "nan" and its terminator fit in the entry's four value bytes. */
    private static void nodataEntry(ByteBuffer tiff) {
        tiff.putShort((short) 42113).putShort((short) TYPE_ASCII).putInt(4);
        tiff.put("nan\0".getBytes(StandardCharsets.US_ASCII));
    }
}
