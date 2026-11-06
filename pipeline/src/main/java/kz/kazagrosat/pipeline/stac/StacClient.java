package kz.kazagrosat.pipeline.stac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/** Searches any STAC API. Nothing here is specific to one provider. */
public final class StacClient {

    /** The free public catalogue the plan starts with. */
    public static final URI EARTH_SEARCH = URI.create("https://earth-search.aws.element84.com/v1");

    private static final int PAGE_SIZE = 100;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final URI baseUri;
    private final RetryingHttp http;

    public StacClient(URI baseUri, RetryPolicy retry) {
        this.baseUri = baseUri;
        this.http = new RetryingHttp(retry);
    }

    public List<StacItem> search(StacSearch search) throws IOException, InterruptedException {
        List<StacItem> items = new ArrayList<>();
        URI uri = URI.create(baseUri + "/search");
        JsonNode body = firstPageBody(search);
        while (uri != null) {
            JsonNode page = post(uri, body);
            for (JsonNode feature : page.path("features")) {
                items.add(parseItem(feature));
            }
            JsonNode next = nextLink(page);
            uri = next == null ? null : URI.create(next.get("href").asText());
            body = next == null ? null : next.get("body");
        }
        return items;
    }

    private static JsonNode firstPageBody(StacSearch search) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("collections").add(search.collection());
        body.putArray("bbox")
                .add(search.bbox().west()).add(search.bbox().south())
                .add(search.bbox().east()).add(search.bbox().north());
        body.put("datetime", search.from() + "T00:00:00Z/" + search.to() + "T23:59:59Z");
        body.put("limit", PAGE_SIZE);
        if (search.maxCloudCover() != null) {
            body.putObject("query").putObject("eo:cloud_cover").put("lt", search.maxCloudCover());
        }
        return body;
    }

    private JsonNode post(URI uri, JsonNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<byte[]> response = http.send(request);
        if (response.statusCode() != 200) {
            throw new IOException("STAC search failed: HTTP " + response.statusCode() + " from " + uri);
        }
        return JSON.readTree(response.body());
    }

    private static JsonNode nextLink(JsonNode page) {
        for (JsonNode link : page.path("links")) {
            if ("next".equals(link.path("rel").asText())) {
                return link;
            }
        }
        return null;
    }

    private static StacItem parseItem(JsonNode feature) {
        JsonNode properties = feature.get("properties");
        Map<String, StacAsset> assets = new HashMap<>();
        feature.path("assets").properties().forEach(e -> assets.put(e.getKey(), parseAsset(e.getValue())));
        return new StacItem(
                feature.get("id").asText(),
                Instant.parse(properties.get("datetime").asText()),
                properties.path("eo:cloud_cover").asDouble(Double.NaN),
                parseEpsg(properties),
                parseGeometry(feature.get("geometry")),
                Map.copyOf(assets));
    }

    private static StacAsset parseAsset(JsonNode asset) {
        JsonNode band = asset.path("raster:bands").path(0);
        return new StacAsset(
                asset.get("href").asText(),
                band.path("scale").asDouble(1.0),
                band.path("offset").asDouble(0.0),
                band.has("nodata") ? Integer.valueOf(band.get("nodata").asInt()) : null);
    }

    private static int parseEpsg(JsonNode properties) {
        if (properties.has("proj:epsg")) {
            return properties.get("proj:epsg").asInt();
        }
        return Integer.parseInt(properties.path("proj:code").asText("EPSG:0").substring("EPSG:".length()));
    }

    private static Geometry parseGeometry(JsonNode geometry) {
        String type = geometry.get("type").asText();
        JsonNode coordinates = geometry.get("coordinates");
        return switch (type) {
            case "Polygon" -> parsePolygon(coordinates);
            case "MultiPolygon" -> {
                Polygon[] parts = new Polygon[coordinates.size()];
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parsePolygon(coordinates.get(i));
                }
                yield GEOMETRY.createMultiPolygon(parts);
            }
            default -> throw new IllegalArgumentException("Unsupported footprint type: " + type);
        };
    }

    private static Polygon parsePolygon(JsonNode rings) {
        LinearRing shell = parseRing(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = parseRing(rings.get(i + 1));
        }
        return GEOMETRY.createPolygon(shell, holes);
    }

    private static LinearRing parseRing(JsonNode ring) {
        Coordinate[] points = new Coordinate[ring.size()];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Coordinate(ring.get(i).get(0).asDouble(), ring.get(i).get(1).asDouble());
        }
        return GEOMETRY.createLinearRing(points);
    }
}
