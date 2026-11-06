package kz.kazagrosat.pipeline.http;

import java.time.Duration;

/** How often to try a request, and the delay before the first retry (doubled after each failure). */
public record RetryPolicy(int maxAttempts, Duration initialDelay) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(4, Duration.ofMillis(500));

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }
}
