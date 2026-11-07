package kz.kazagrosat.pipeline.testutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDirDeletionStrategy.DeletionResult;

/**
 * A virus scanner may hold a freshly written file for a moment; a leaked handle in our own code holds
 * it for good. The cleanup must wait out the first and still report the second.
 */
class RetryingTempDirDeletionTest {

    @Test
    void deletesAFolderOnceABriefHoldOnAFileIsReleased() throws Exception {
        Path folder = Files.createTempDirectory("retry-brief");
        Path file = Files.writeString(folder.resolve("scanned.tif"), "data");
        FileInputStream scanner = new FileInputStream(file.toFile());
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(300);
                scanner.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        release.start();

        DeletionResult result = new RetryingTempDirDeletion().delete(folder, null, null);

        release.join();
        assertTrue(result.isSuccessful(), "failures: " + result.failures());
        assertFalse(Files.exists(folder));
    }

    @Test
    void stillReportsAFileThatStaysOpen() throws Exception {
        Path folder = Files.createTempDirectory("retry-leak");
        Path file = Files.writeString(folder.resolve("leaked.tif"), "data");
        try (FileInputStream leak = new FileInputStream(file.toFile())) {
            DeletionResult result = new RetryingTempDirDeletion().delete(folder, null, null);

            assertFalse(result.isSuccessful());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }
}
