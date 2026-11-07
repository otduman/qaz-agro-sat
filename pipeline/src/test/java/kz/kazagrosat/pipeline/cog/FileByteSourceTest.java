package kz.kazagrosat.pipeline.cog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileByteSourceTest {

    @TempDir
    Path folder;

    private Path file;

    @BeforeEach
    void writeHundredBytes() throws Exception {
        byte[] bytes = new byte[100];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        file = folder.resolve("bytes.bin");
        Files.write(file, bytes);
    }

    @Test
    void readsTheRequestedRange() throws Exception {
        assertArrayEquals(new byte[] {10, 11, 12, 13, 14}, new FileByteSource(file).read(10, 5));
    }

    @Test
    void returnsFewerBytesAtTheEndOfTheFile() throws Exception {
        assertArrayEquals(new byte[] {95, 96, 97, 98, 99}, new FileByteSource(file).read(95, 10));
    }

    @Test
    void returnsNothingPastTheEnd() throws Exception {
        assertEquals(0, new FileByteSource(file).read(200, 10).length);
    }

    /**
     * A rerun replaces a stored file by renaming a new one over it. On Windows that fails while any
     * handle is still open; plain deletion would not catch a leak, because Java's handles allow it.
     */
    @Test
    void aRerunCanReplaceTheFileAfterReading() throws Exception {
        new FileByteSource(file).read(0, 50);

        Path replacement = Files.write(folder.resolve("bytes.bin.part"), new byte[] {7, 7, 7});
        Files.move(replacement, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        assertArrayEquals(new byte[] {7, 7, 7}, Files.readAllBytes(file));
    }
}
