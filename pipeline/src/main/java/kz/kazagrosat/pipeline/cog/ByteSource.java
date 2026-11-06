package kz.kazagrosat.pipeline.cog;

import java.io.IOException;

/** Random access to a file that may be remote. */
public interface ByteSource {

    /** Returns up to {@code length} bytes; fewer only when the file ends first. */
    byte[] read(long offset, int length) throws IOException, InterruptedException;
}
