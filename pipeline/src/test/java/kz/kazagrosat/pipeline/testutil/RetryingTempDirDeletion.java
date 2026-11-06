package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * Deletes a test's temporary folder, retrying for up to two seconds. On Windows a virus scanner can
 * hold a file it just saw being written, which briefly leaves the folder "not empty". A handle leaked
 * by our own code never closes, so it still fails the test after the last attempt.
 *
 * <p>Set as the default in {@code junit-platform.properties}.
 */
public final class RetryingTempDirDeletion implements TempDirDeletionStrategy {

    private static final int ATTEMPTS = 20;
    private static final long PAUSE_MILLIS = 100;

    @Override
    public DeletionResult delete(Path folder, AnnotatedElementContext element, ExtensionContext context)
            throws IOException {
        DeletionResult result = Standard.INSTANCE.delete(folder, element, context);
        for (int attempt = 1; attempt < ATTEMPTS && !result.isSuccessful(); attempt++) {
            try {
                Thread.sleep(PAUSE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            if (!Files.exists(folder)) {
                return DeletionResult.builder(folder).build();
            }
            result = Standard.INSTANCE.delete(folder, element, context);
        }
        return result;
    }
}
