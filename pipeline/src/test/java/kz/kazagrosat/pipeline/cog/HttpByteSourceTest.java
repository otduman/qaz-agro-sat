package kz.kazagrosat.pipeline.cog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpByteSourceTest {

    private TestHttpServer server;
    private HttpByteSource source;

    @BeforeEach
    void start() throws IOException {
        server = new TestHttpServer();
        source = new HttpByteSource(
                server.uri("/B04.tif"), new RetryingHttp(new RetryPolicy(3, Duration.ofMillis(1))));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void asksForExactlyTheRequestedBytes() throws Exception {
        server.handle("/B04.tif", ex -> TestHttpServer.respond(ex, 206, new byte[] {1, 2, 3}));

        byte[] bytes = source.read(100, 3);

        assertArrayEquals(new byte[] {1, 2, 3}, bytes);
        assertEquals("bytes=100-102", server.requests().getFirst().rangeHeader());
    }

    @Test
    void refusesAServerThatIgnoresTheRangeRequest() {
        // Status 200 means the whole file came back; using it as the range would be silent garbage.
        server.handle("/B04.tif", ex -> TestHttpServer.respond(ex, 200, new byte[] {9, 9, 9, 9}));

        IOException error = assertThrows(IOException.class, () -> source.read(100, 3));

        assertTrue(error.getMessage().contains("range"), error.getMessage());
    }

    @Test
    void reportsMissingFiles() {
        server.handle("/B04.tif", ex -> TestHttpServer.respond(ex, 404, new byte[0]));

        IOException error = assertThrows(IOException.class, () -> source.read(0, 10));

        assertTrue(error.getMessage().contains("404"), error.getMessage());
    }

    @Test
    void retriesTransientFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.handle("/B04.tif", ex -> {
            if (calls.incrementAndGet() == 1) {
                TestHttpServer.respond(ex, 503, new byte[0]);
            } else {
                TestHttpServer.respond(ex, 206, new byte[] {5});
            }
        });

        assertArrayEquals(new byte[] {5}, source.read(0, 1));
    }
}
