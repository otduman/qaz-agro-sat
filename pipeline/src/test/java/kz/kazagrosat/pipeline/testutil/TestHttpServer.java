package kz.kazagrosat.pipeline.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A real loopback HTTP server, so tests exercise the actual HTTP client code. */
public final class TestHttpServer implements AutoCloseable {

    public record Recorded(String method, String path, String body, String rangeHeader) {}

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public TestHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    public void handle(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Range")));
            try (exchange) {
                handler.handle(exchange);
            }
        });
    }

    public URI uri(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + path);
    }

    public List<Recorded> requests() {
        return requests;
    }

    /** Serves a file the way S3 does: Range requests get 206 and just those bytes. */
    public void serveFile(String path, byte[] file) {
        handle(path, exchange -> respondRange(exchange, file));
    }

    /** Answers a request for a file the way S3 does: a Range request gets 206 and just those bytes. */
    public static void respondRange(HttpExchange exchange, byte[] file) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null) {
            respond(exchange, 200, file);
            return;
        }
        String[] bounds = range.substring("bytes=".length()).split("-");
        int start = Integer.parseInt(bounds[0]);
        int end = Math.min(Integer.parseInt(bounds[1]) + 1, file.length);
        respond(exchange, 206, java.util.Arrays.copyOfRange(file, start, Math.max(start, end)));
    }

    public static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    public static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/geo+json");
        respond(exchange, status, json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
