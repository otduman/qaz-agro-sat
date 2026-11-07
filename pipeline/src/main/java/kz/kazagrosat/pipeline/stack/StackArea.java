package kz.kazagrosat.pipeline.stack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * What a stack folder holds: the area and season it was asked for, the tile used and the pixel grids
 * every date shares. Kept in {@code stack.properties}, so a rerun can refuse a different request.
 *
 * @param grid     the area at 10 m: blue, green, red and near-infrared
 * @param maskGrid the same area at 20 m: the shortwave bands and the cloud mask
 */
public record StackArea(BoundingBox bbox, int year, String tile, PixelGrid grid, PixelGrid maskGrid) {

    static final String FILE = "stack.properties";

    public static Optional<StackArea> read(Path folder) throws IOException {
        Path file = folder.resolve(FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        try {
            return Optional.of(new StackArea(
                    BoundingBox.parse(require(properties, "bbox", file)),
                    Integer.parseInt(require(properties, "year", file)),
                    require(properties, "tile", file),
                    parseGrid(require(properties, "grid", file)),
                    parseGrid(require(properties, "maskGrid", file))));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unreadable " + file + ": " + e.getMessage(), e);
        }
    }

    public void write(Path folder) throws IOException {
        Files.createDirectories(folder);
        String text = "bbox=" + bbox.west() + "," + bbox.south() + "," + bbox.east() + "," + bbox.north() + "\n"
                + "year=" + year + "\n"
                + "tile=" + tile + "\n"
                + "grid=" + formatGrid(grid) + "\n"
                + "maskGrid=" + formatGrid(maskGrid) + "\n";
        Files.writeString(folder.resolve(FILE), text, StandardCharsets.UTF_8);
    }

    /** True when a run asks for exactly this area and season. */
    public boolean sameRequest(BoundingBox otherBbox, int otherYear) {
        return bbox.equals(otherBbox) && year == otherYear;
    }

    private static String require(Properties properties, String key, Path file) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IOException(file + " has no " + key);
        }
        return value;
    }

    private static String formatGrid(PixelGrid grid) {
        return grid.epsg() + "," + grid.originX() + "," + grid.originY() + "," + grid.pixelSize() + ","
                + grid.width() + "," + grid.height();
    }

    private static PixelGrid parseGrid(String text) {
        String[] parts = text.split(",");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Expected epsg,originX,originY,pixelSize,width,height but got " + text);
        }
        return new PixelGrid(Integer.parseInt(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
    }
}
