package kz.kazagrosat.pipeline.landsat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import kz.kazagrosat.pipeline.http.RetryingHttp;

/**
 * Signs Planetary Computer asset links with a free anonymous access token, fetching a fresh one shortly before the
 * current one expires. No account is needed.
 */
public final class PlanetaryToken {

    /** A token is replaced when less than this is left of it. */
    static final Duration MARGIN = Duration.ofMinutes(5);

    public record Token(String value, Instant expiry) {}

    @FunctionalInterface
    public interface Fetch {
        Token fetch() throws IOException, InterruptedException;
    }

    private final Fetch fetch;
    private Token token;

    public PlanetaryToken(Fetch fetch) {
        this.fetch = fetch;
    }

    /** Reads Planetary Computer's token reply, {@code {"msft:expiry": ..., "token": ...}}, from {@code uri}. */
    public static Fetch fetcher(RetryingHttp http, URI uri) {
        return () -> {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build());
            if (response.statusCode() != 200) {
                throw new IOException("Planetary Computer refused a token from " + uri + ": HTTP "
                        + response.statusCode());
            }
            JsonNode json = new ObjectMapper().readTree(response.body());
            if (!json.hasNonNull("token") || !json.hasNonNull("msft:expiry")) {
                throw new IOException("Planetary Computer's token reply from " + uri + " has no token or expiry");
            }
            return new Token(json.get("token").asText(), Instant.parse(json.get("msft:expiry").asText()));
        };
    }

    public synchronized String sign(String href, Instant now) throws IOException, InterruptedException {
        if (token == null || now.isAfter(token.expiry().minus(MARGIN))) {
            token = fetch.fetch();
        }
        return href + (href.contains("?") ? "&" : "?") + token.value();
    }
}
