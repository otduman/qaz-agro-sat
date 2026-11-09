package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
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
}
