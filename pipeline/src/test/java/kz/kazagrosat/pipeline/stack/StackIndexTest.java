package kz.kazagrosat.pipeline.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import kz.kazagrosat.pipeline.stack.StackEntry.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackIndexTest {

    private static final StackEntry JULY = new StackEntry(LocalDate.of(2025, 7, 12),
            "S2B_T41UMV_20250712T070939_L2A", 1.3, 0.9312, Status.STORED, 0.0001, -0.1, 0);
    private static final StackEntry AUGUST = new StackEntry(LocalDate.of(2025, 8, 3),
            "S2C_T41UMV_20250803T070951_L2A", 62.0, 0.004, Status.CLOUDY, 0.0001, -0.1, 0);

    @TempDir
    Path folder;

    @Test
    void writesOneSortedRowPerDate() throws Exception {
        Path index = folder.resolve("index.csv");
        StackIndex.write(index, List.of(AUGUST, JULY));

        assertEquals("""
                date,scene,tile_cloud,clear_share,status,scale,offset,nodata
                2025-07-12,S2B_T41UMV_20250712T070939_L2A,1.30,0.9312,stored,1.0E-4,-0.1,0
                2025-08-03,S2C_T41UMV_20250803T070951_L2A,62.00,0.0040,cloudy,1.0E-4,-0.1,0
                """, Files.readString(index, StandardCharsets.UTF_8));
    }

    @Test
    void readsBackWhatItWrote() throws Exception {
        StackEntry noNodata = new StackEntry(LocalDate.of(2025, 9, 1),
                "S2B_T41UMV_20250901T070939_L2A", 12.5, 0.5, Status.STORED, 0.0001, -0.1, null);
        Path index = folder.resolve("index.csv");
        StackIndex.write(index, List.of(JULY, noNodata));

        assertEquals(List.of(JULY, noNodata), StackIndex.read(index));
    }

    @Test
    void aMissingIndexMeansNoDatesYet() throws Exception {
        assertTrue(StackIndex.read(folder.resolve("index.csv")).isEmpty());
    }

    @Test
    void replacesTheIndexWithoutLeavingATemporaryFile() throws Exception {
        Path index = folder.resolve("index.csv");
        StackIndex.write(index, List.of(JULY));
        StackIndex.write(index, List.of(JULY, AUGUST));

        try (Stream<Path> files = Files.list(folder)) {
            assertEquals(List.of("index.csv"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals(2, StackIndex.read(index).size());
    }
}
