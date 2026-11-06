package kz.kazagrosat.pipeline.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Sends requests, retrying network failures and the statuses that mean "try again". */
public final class RetryingHttp {

    private final HttpClient client;
    private final RetryPolicy policy;

    public RetryingHttp(RetryPolicy policy) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.policy = policy;
    }

    /** Returns the first response that is not retryable; a 4xx is returned for the caller to judge. */
    public HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        Duration delay = policy.initialDelay();
        IOException failure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (attempt > 1) {
                Thread.sleep(delay);
                delay = delay.multipliedBy(2);
            }
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (!isRetryable(response.statusCode())) {
                    return response;
                }
                failure = new IOException("HTTP " + response.statusCode() + " from " + request.uri());
            } catch (IOException e) {
                failure = e;
            }
        }
        throw failure;
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }
}
