package kz.kazagrosat.pipeline.cog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import kz.kazagrosat.pipeline.http.RetryingHttp;

/** Reads parts of a remote file with HTTP range requests. */
public final class HttpByteSource implements ByteSource {

    private static final int PARTIAL_CONTENT = 206;

    private final URI uri;
    private final RetryingHttp http;

    public HttpByteSource(URI uri, RetryingHttp http) {
        this.uri = uri;
        this.http = http;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Range", "bytes=" + offset + "-" + (offset + length - 1))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request);
        if (response.statusCode() == PARTIAL_CONTENT) {
            return response.body();
        }
        if (response.statusCode() == 200) {
            throw new IOException("Server ignored the range request and sent the whole file: " + uri);
        }
        throw new IOException("HTTP " + response.statusCode() + " reading " + uri);
    }
}
