package kz.kazagrosat.pipeline.fields;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces a set of output files all together or not at all. Each file is written under a temporary
 * name ({@link #part}); {@link #commit} then moves the old files aside and the new ones into place. If
 * any old file is held open by another program, those already moved go back and nothing changes.
 * Closing removes any temporary file still left.
 */
public final class OutputSwap implements AutoCloseable {

    private static final String PART = ".part";
    private static final String OLD = ".old";

    private final Path folder;
    private final Map<Path, Path> parts = new LinkedHashMap<>();

    public OutputSwap(Path folder) throws IOException {
        this.folder = folder;
        Files.createDirectories(folder);
    }

    /** @return the temporary file to write the output {@code name} into */
    public Path part(String name) {
        Path target = folder.resolve(name);
        Path part = folder.resolve(name + PART);
        parts.put(target, part);
        return part;
    }

    /** Puts every new file in place, or none. @return the outputs, in the order their parts were asked for */
    public List<Path> commit() throws IOException {
        Map<Path, Path> asides = new LinkedHashMap<>();
        try {
            for (Path target : parts.keySet()) {
                if (Files.exists(target)) {
                    Path aside = target.resolveSibling(target.getFileName() + OLD);
                    move(target, aside, target);
                    asides.put(target, aside);
                }
            }
            List<Path> placed = new ArrayList<>();
            try {
                for (Map.Entry<Path, Path> entry : parts.entrySet()) {
                    move(entry.getValue(), entry.getKey(), entry.getKey());
                    placed.add(entry.getKey());
                }
            } catch (IOException e) {
                for (Path target : placed) {
                    Files.deleteIfExists(target);
                }
                throw e;
            }
        } catch (IOException e) {
            for (Map.Entry<Path, Path> entry : asides.entrySet()) {
                Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.ATOMIC_MOVE);
            }
            throw e;
        }
        for (Path aside : asides.values()) {
            Files.deleteIfExists(aside);
        }
        return new ArrayList<>(parts.keySet());
    }

    @Override
    public void close() throws IOException {
        for (Path part : parts.values()) {
            Files.deleteIfExists(part);
        }
    }

    /** Moves a file, explaining a refusal in terms of the output file it was for. */
    private static void move(Path from, Path to, Path output) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AccessDeniedException e) {
            throw new IOException("Cannot replace " + output + ": it is open in another program, such as QGIS or "
                    + "Excel. Close it and run the same command again. No output was changed.", e);
        } catch (FileSystemException e) {
            // Renaming a file another program holds open is a sharing violation on Windows, not a denial.
            throw new IOException("Cannot replace " + output + ": it is probably open in another program, such as "
                    + "QGIS or Excel (the system says: " + e.getReason() + "). Close it and run the same command "
                    + "again. No output was changed.", e);
        }
    }
}
