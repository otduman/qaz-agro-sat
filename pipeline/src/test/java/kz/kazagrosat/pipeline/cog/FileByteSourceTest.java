package kz.kazagrosat.pipeline.cog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void leavesTheFileFreeToDeleteAfterReading() throws Exception {
        FileByteSource source = new FileByteSource(file);
        source.read(0, 50);

        Files.delete(file);
        assertFalse(Files.exists(file));
    }
}
