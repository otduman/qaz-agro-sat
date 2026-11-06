package kz.kazagrosat.pipeline.stac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StacClientTest {

    private static final RetryPolicy FAST_RETRY = new RetryPolicy(3, Duration.ofMillis(1));
    private static final StacSearch SEARCH = new StacSearch(
            "sentinel-2-c1-l2a",
            new BoundingBox(62.7, 53.6, 63.0, 53.8),
            LocalDate.of(2025, 7, 1),
            LocalDate.of(2025, 7, 31),
            10.0);

    private TestHttpServer server;
    private StacClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new TestHttpServer();
        client = new StacClient(server.uri(""), FAST_RETRY);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    static String realResponse() throws IOException {
        try (InputStream in = StacClientTest.class.getResourceAsStream("/stac/search-july-2025.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void postsTheFilterAsStacSearchJson() throws Exception {
        server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, "{\"features\":[],\"links\":[]}"));

        client.search(SEARCH);

        TestHttpServer.Recorded request = server.requests().getFirst();
        assertEquals("POST", request.method());
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertEquals("sentinel-2-c1-l2a", body.get("collections").get(0).asText());
        assertEquals(62.7, body.get("bbox").get(0).asDouble());
        assertEquals(53.6, body.get("bbox").get(1).asDouble());
        assertEquals(63.0, body.get("bbox").get(2).asDouble());
        assertEquals(53.8, body.get("bbox").get(3).asDouble());
        assertEquals("2025-07-01T00:00:00Z/2025-07-31T23:59:59Z", body.get("datetime").asText());
        assertEquals(10.0, body.get("query").get("eo:cloud_cover").get("lt").asDouble());
    }

    @Test
    void omitsTheCloudFilterWhenNoLimitIsGiven() throws Exception {
        server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, "{\"features\":[],\"links\":[]}"));

        client.search(new StacSearch("c", SEARCH.bbox(), SEARCH.from(), SEARCH.to(), null));

        assertFalse(new ObjectMapper().readTree(server.requests().getFirst().body()).has("query"));
    }

    @Test
    void parsesARealEarthSearchResponse() throws Exception {
        String json = realResponse();
        server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, json));

        List<StacItem> items = client.search(SEARCH);

        assertEquals(4, items.size());
        StacItem item = items.stream()
                .filter(i -> i.id().equals("S2B_T41UMV_20250712T070939_L2A"))
                .findFirst().orElseThrow();
        assertEquals(1.318524, item.cloudCover(), 1e-6);
        assertEquals(32641, item.epsg());
        assertEquals(Instant.parse("2025-07-12T07:17:06Z").getEpochSecond() / 86400,
                item.datetime().getEpochSecond() / 86400, "item is dated 12 July 2025");
        assertFalse(item.footprint().isEmpty());

        StacAsset red = item.asset("red");
        assertTrue(red.href().endsWith("/S2B_T41UMV_20250712T070939_L2A/B04.tif"), red.href());
        assertEquals(0.0001, red.scale());
        assertEquals(-0.1, red.offset());
        assertEquals(0, red.nodata());
    }

    @Test
    void classAssetsWithoutScaleDefaultToIdentity() throws Exception {
        String json = realResponse();
        server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, json));

        StacAsset scl = client.search(SEARCH).getFirst().asset("scl");

        assertEquals(1.0, scl.scale());
        assertEquals(0.0, scl.offset());
    }

    @Test
    void assetsWithoutRasterBandsHaveNoNodata() throws Exception {
        server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, """
                {"features":[{"id":"x","geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]},
                 "properties":{"datetime":"2025-07-12T07:17:06.123Z","eo:cloud_cover":1.0,"proj:code":"EPSG:32641"},
                 "assets":{"thumbnail":{"href":"https://example.test/t.jpg"}}}],"links":[]}"""));

        StacItem item = client.search(SEARCH).getFirst();

        assertNull(item.asset("thumbnail").nodata());
        assertEquals(32641, item.epsg(), "reads the newer proj:code form too");
    }

    @Test
    void missingAssetFailsNamingTheItemAndKey() throws Exception {
        String json = realResponse();
        server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, json));
        StacItem item = client.search(SEARCH).getFirst();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> item.asset("no-such-band"));

        assertTrue(error.getMessage().contains("no-such-band"), error.getMessage());
        assertTrue(error.getMessage().contains(item.id()), error.getMessage());
    }

    @Test
    void followsNextLinksUsingTheirPostBody() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String feature = """
                {"id":"%s","geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]},
                 "properties":{"datetime":"2025-07-12T07:17:06Z","eo:cloud_cover":1.0,"proj:epsg":32641},
                 "assets":{}}""";
        server.handle("/search", ex -> {
            if (calls.incrementAndGet() == 1) {
                TestHttpServer.respondJson(ex, 200, "{\"features\":[" + feature.formatted("first") + "],"
                        + "\"links\":[{\"rel\":\"next\",\"method\":\"POST\",\"merge\":false,"
                        + "\"href\":\"" + server.uri("/search") + "\",\"body\":{\"next\":\"token-2\"}}]}");
            } else {
                TestHttpServer.respondJson(ex, 200,
                        "{\"features\":[" + feature.formatted("second") + "],\"links\":[]}");
            }
        });

        List<StacItem> items = client.search(SEARCH);

        assertEquals(List.of("first", "second"), items.stream().map(StacItem::id).toList());
        assertEquals("token-2",
                new ObjectMapper().readTree(server.requests().get(1).body()).get("next").asText());
    }

    @Test
    void retriesTransientServerErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle("/search", ex -> {
            if (calls.incrementAndGet() < 3) {
                TestHttpServer.respond(ex, 503, new byte[0]);
            } else {
                TestHttpServer.respondJson(ex, 200, "{\"features\":[],\"links\":[]}");
            }
        });

        assertTrue(client.search(SEARCH).isEmpty());
        assertEquals(3, calls.get());
    }

    @Test
    void givesUpAfterTheRetryLimit() {
        server.handle("/search", ex -> TestHttpServer.respond(ex, 500, new byte[0]));

        IOException error = assertThrows(IOException.class, () -> client.search(SEARCH));

        assertTrue(error.getMessage().contains("500"), error.getMessage());
        assertEquals(3, server.requests().size());
    }

    @Test
    void doesNotRetryClientErrors() {
        server.handle("/search", ex -> TestHttpServer.respond(ex, 400, new byte[0]));

        IOException error = assertThrows(IOException.class, () -> client.search(SEARCH));

        assertTrue(error.getMessage().contains("400"), error.getMessage());
        assertEquals(1, server.requests().size());
    }
}
