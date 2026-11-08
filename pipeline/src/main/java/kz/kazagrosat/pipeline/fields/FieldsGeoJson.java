package kz.kazagrosat.pipeline.fields;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.core.geo.LonLat;
import kz.kazagrosat.core.geo.UtmProjection;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/**
 * Writes a season's regions as a GeoJSON FeatureCollection in WGS84 longitude and latitude, with 6
 * decimals (about 0.1 m). Each feature's properties are its id ({@code 2025-F0001}), area in hectares,
 * land label and uniformity score.
 */
public final class FieldsGeoJson {

    private FieldsGeoJson() {}

    /** @param outlines each region's outline in the grid's UTM metres, keyed by region id */
    public static void write(Path file, FieldMap map, Map<Integer, Polygon> outlines, int year) throws IOException {
        UtmProjection utm = UtmProjection.forEpsg(map.grid().epsg());
        double pixelHectares = map.grid().pixelSize() * map.grid().pixelSize() / 10_000;
        try (JsonGenerator json = new JsonFactory().createGenerator(file.toFile(), JsonEncoding.UTF8)) {
            json.writeStartObject();
            json.writeStringField("type", "FeatureCollection");
            json.writeArrayFieldStart("features");
            for (Region region : map.regions()) {
                Polygon outline = outlines.get(region.id());
                if (outline == null) {
                    throw new IllegalArgumentException("Region " + region.id() + " has no outline");
                }
                json.writeStartObject();
                json.writeStringField("type", "Feature");
                json.writeObjectFieldStart("properties");
                json.writeStringField("id", id(year, region.id()));
                json.writeFieldName("area_ha");
                json.writeNumber(String.format(Locale.ROOT, "%.2f", region.pixels() * pixelHectares));
                json.writeStringField("land", region.land().name().toLowerCase(Locale.ROOT));
                json.writeFieldName("uniformity");
                if (Double.isNaN(region.uniformity())) {
                    json.writeNull();
                } else {
                    json.writeNumber(String.format(Locale.ROOT, "%.2f", region.uniformity()));
                }
                json.writeEndObject();
                json.writeObjectFieldStart("geometry");
                json.writeStringField("type", "Polygon");
                json.writeArrayFieldStart("coordinates");
                writeRing(json, outline.getExteriorRing(), utm);
                for (int h = 0; h < outline.getNumInteriorRing(); h++) {
                    writeRing(json, outline.getInteriorRingN(h), utm);
                }
                json.writeEndArray();
                json.writeEndObject();
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
    }

    /** The id a region gets in the GeoJSON, numbered per season: {@code 2025-F0001}. */
    public static String id(int year, int region) {
        return String.format(Locale.ROOT, "%d-F%04d", year, region);
    }

    private static void writeRing(JsonGenerator json, LineString ring, UtmProjection utm) throws IOException {
        json.writeStartArray();
        for (Coordinate c : ring.getCoordinates()) {
            LonLat point = utm.unproject(c.x, c.y);
            json.writeStartArray();
            json.writeNumber(String.format(Locale.ROOT, "%.6f", point.longitude()));
            json.writeNumber(String.format(Locale.ROOT, "%.6f", point.latitude()));
            json.writeEndArray();
        }
        json.writeEndArray();
    }
}
