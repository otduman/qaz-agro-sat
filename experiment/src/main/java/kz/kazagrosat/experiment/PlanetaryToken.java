package kz.kazagrosat.experiment;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Signs Planetary Computer asset links with a free anonymous access token, fetching a fresh one shortly before the
 * current one expires.
 */
final class PlanetaryToken {

    static final Duration MARGIN = Duration.ofMinutes(5);

    record Token(String value, Instant expiry) {}

    @FunctionalInterface
    interface Fetch {
        Token fetch() throws IOException, InterruptedException;
    }

    private final Fetch fetch;
    private Token token;

    PlanetaryToken(Fetch fetch) {
        this.fetch = fetch;
    }

    String sign(String href, Instant now) throws IOException, InterruptedException {
        if (token == null || now.isAfter(token.expiry().minus(MARGIN))) {
            token = fetch.fetch();
        }
        return href + (href.contains("?") ? "&" : "?") + token.value();
    }
}
