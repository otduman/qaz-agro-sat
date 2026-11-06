package kz.kazagrosat.core.geo;

/** A longitude/latitude rectangle in WGS84 degrees. */
public record BoundingBox(double west, double south, double east, double north) {

    public BoundingBox {
        if (!(west >= -180 && east <= 180 && south >= -90 && north <= 90)) {
            throw new IllegalArgumentException("Coordinates out of range: " + describe(west, south, east, north));
        }
        if (!(west < east && south < north)) {
            throw new IllegalArgumentException(
                    "Expected west < east and south < north: " + describe(west, south, east, north));
        }
    }

    /** Parses "west,south,east,north". */
    public static BoundingBox parse(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Expected west,south,east,north but got: " + text);
        }
        try {
            return new BoundingBox(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected four numbers but got: " + text, e);
        }
    }

    private static String describe(double west, double south, double east, double north) {
        return west + "," + south + "," + east + "," + north;
    }
}
