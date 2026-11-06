package kz.kazagrosat.pipeline.testutil;

import java.util.Arrays;
import kz.kazagrosat.pipeline.cog.ByteSource;

/** An in-memory file that counts reads, so tests can prove how little was fetched. */
public final class CountingSource implements ByteSource {

    private final byte[] file;
    private int reads;

    public CountingSource(byte[] file) {
        this.file = file;
    }

    @Override
    public byte[] read(long offset, int length) {
        reads++;
        int start = (int) Math.min(offset, file.length);
        int end = (int) Math.min(offset + length, file.length);
        return Arrays.copyOfRange(file, start, end);
    }

    public int reads() {
        return reads;
    }

    public void resetReads() {
        reads = 0;
    }
}
