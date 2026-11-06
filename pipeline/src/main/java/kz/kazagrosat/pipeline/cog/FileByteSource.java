package kz.kazagrosat.pipeline.cog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Random access to a file on disk. The file is opened for each read and closed straight after, so no
 * handle stays open: on Windows an open handle would stop a later run from replacing the file.
 */
public final class FileByteSource implements ByteSource {

    private final Path file;

    public FileByteSource(Path file) {
        this.file = file;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long position = offset;
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer, position);
                if (count < 0) {
                    break;
                }
                position += count;
            }
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }
}
