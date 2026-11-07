package kz.kazagrosat.pipeline.stack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes {@code index.csv}, the record of finished dates. A date is listed only once all of
 * its files exist, so the index is what tells a finished date from a half-written one.
 */
public final class StackIndex {

    static final String HEADER = "date,scene,tile_cloud,clear_share,status,scale,offset,nodata";

    private StackIndex() {}

    /** An index that doesn't exist yet means no dates are finished. */
    public static List<StackEntry> read(Path indexFile) throws IOException {
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IOException(indexFile + " does not start with the expected header: " + HEADER);
        }
        List<StackEntry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length != 8) {
                throw new IOException("Unreadable line in " + indexFile + ": " + line);
            }
            entries.add(new StackEntry(
                    LocalDate.parse(cells[0]),
                    cells[1],
                    Double.parseDouble(cells[2]),
                    Double.parseDouble(cells[3]),
                    StackEntry.Status.valueOf(cells[4].toUpperCase(Locale.ROOT)),
                    Double.parseDouble(cells[5]),
                    Double.parseDouble(cells[6]),
                    cells[7].isEmpty() ? null : Integer.valueOf(cells[7])));
        }
        return entries;
    }

    /** Writes the entries in date order, replacing the old index in one step. */
    public static void write(Path indexFile, List<StackEntry> entries) throws IOException {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        entries.stream().sorted(Comparator.comparing(StackEntry::date)).forEach(entry -> text.append(String.format(
                Locale.ROOT, "%s,%s,%.2f,%.4f,%s,%s,%s,%s\n",
                entry.date(), entry.scene(), entry.tileCloud(), entry.clearShare(),
                entry.status().name().toLowerCase(Locale.ROOT),
                Double.toString(entry.scale()), Double.toString(entry.offset()),
                entry.nodata() == null ? "" : entry.nodata().toString())));
        Path temporary = indexFile.resolveSibling(indexFile.getFileName() + ".part");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
