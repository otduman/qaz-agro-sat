package kz.kazagrosat.pipeline.landsat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.Test;

class PlanetaryTokenTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    @Test
    void signsWithOneTokenUntilItIsAboutToExpire() throws Exception {
        AtomicInteger fetched = new AtomicInteger();
        PlanetaryToken token = new PlanetaryToken(() -> {
            int n = fetched.incrementAndGet();
            return new PlanetaryToken.Token("t" + n, NOW.plusSeconds(3600 * n));
        });

        assertEquals("https://x/a.tif?t1", token.sign("https://x/a.tif", NOW));
        assertEquals("https://x/b.tif?t1", token.sign("https://x/b.tif", NOW.plusSeconds(3000)));
        assertEquals("https://x/c.tif?t2", token.sign("https://x/c.tif", NOW.plusSeconds(3400)),
                "under five minutes left, so a fresh token");
        assertEquals(2, fetched.get());
    }

    @Test
    void addsToAnExistingQuery() throws Exception {
        PlanetaryToken token = new PlanetaryToken(() -> new PlanetaryToken.Token("st=1&sig=2", NOW.plusSeconds(3600)));

        assertEquals("https://x/a.tif?v=1&st=1&sig=2", token.sign("https://x/a.tif?v=1", NOW));
    }

    @Test
    void fetchesATokenFromPlanetaryComputersReply() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token/landsat-c2-l2", exchange -> TestHttpServer.respondJson(exchange, 200,
                    "{\"msft:expiry\":\"2026-11-08T13:00:00Z\",\"token\":\"st=1&se=2&sig=a%2Bb\"}"));
            RetryingHttp http = new RetryingHttp(new RetryPolicy(1, Duration.ofMillis(1)));

            PlanetaryToken.Token token = PlanetaryToken.fetcher(http, server.uri("/token/landsat-c2-l2")).fetch();

            assertEquals("st=1&se=2&sig=a%2Bb", token.value());
            assertEquals(Instant.parse("2026-11-08T13:00:00Z"), token.expiry());
        }
    }

    @Test
    void aRefusedTokenRequestNamesTheStatus() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token/landsat-c2-l2", exchange -> TestHttpServer.respondJson(exchange, 403, "{}"));
            RetryingHttp http = new RetryingHttp(new RetryPolicy(1, Duration.ofMillis(1)));

            IOException error = assertThrows(IOException.class,
                    () -> PlanetaryToken.fetcher(http, server.uri("/token/landsat-c2-l2")).fetch());

            assertTrue(error.getMessage().contains("403"), error.getMessage());
        }
    }
}
