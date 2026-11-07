package kz.kazagrosat.pipeline.fields;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import kz.kazagrosat.core.fields.FieldFinder;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.FieldParameters;
import kz.kazagrosat.core.fields.FieldSummary;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.testutil.HandStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FieldOutputsTest {

    @TempDir
    Path folder;

    private SeasonStack stack;
    private FieldMap map;
    private Path out;

    @BeforeEach
    void findFields() throws Exception {
        stack = HandStack.twoStrips(folder.resolve("stack"));
        map = FieldFinder.find(new StackImages(stack), FieldParameters.DEFAULTS, line -> { });
        out = folder.resolve("fields");
    }

    private List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesEveryFile() throws Exception {
        List<Path> written = FieldOutputs.write(stack, map, out);

        List<String> names = written.stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of(
                "fields-2025.geojson",
                "fields-2025.tif",
                "edges-2025.png",
                "outlines-mid-season-2025-07-10.png",
                "outlines-post-harvest-2025-09-10.png",
                "summary-2025.txt"), names);
        for (Path file : written) {
            assertTrue(Files.size(file) > 0, file + " is empty");
        }
        assertEquals(2, map.regions().size());
    }

    @Test
    void theLabelFileReadsBackAsTheRegionIds() throws Exception {
        FieldOutputs.write(stack, map, out);

        CogReader reader = CogReader.open(new FileByteSource(out.resolve("fields-2025.tif")));
        PixelGrid grid = stack.area().grid();
        assertEquals(grid, reader.grid());
        assertArrayEquals(map.labels(), reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height())));
    }

    @Test
    void theSummaryListsWhatWasLeftOut() throws Exception {
        FieldOutputs.write(stack, map, out);

        String summary = Files.readString(out.resolve("summary-2025.txt"));
        assertTrue(summary.contains("Dates used: 8"), summary);
        assertTrue(summary.contains("Cloudy dates skipped: 0"), summary);
        assertTrue(summary.contains("Regions: 2 (field 1, fallow 1, other 0)"), summary);
        assertTrue(summary.contains("Pixels with too few clear dates: 0"), summary);
        assertTrue(summary.contains("Mid-season picture: 2025-07-10"), summary);
    }

    @Test
    void refusesMoreRegionsThanTheLabelFileHolds() throws Exception {
        PixelGrid grid = new PixelGrid(32641, 460140, 5922780, 10, 256, 257);
        int[] labels = new int[grid.width() * grid.height()];
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < 65_536; i++) {
            labels[i] = i + 1;
            regions.add(new Region(i + 1, 1, LandLabel.OTHER, Double.NaN));
        }
        FieldMap tooMany = new FieldMap(grid, labels, new float[labels.length], regions,
                new FieldSummary(List.of(), 65_536, 0, 0, 0, 0, 0, 0, 0, Map.of()));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> FieldOutputs.write(stack, tooMany, out));

        assertTrue(refused.getMessage().contains("65,536"), refused.getMessage());
        assertFalse(Files.exists(out) && !filesIn(out).isEmpty(), "nothing is written");
    }

    @Test
    void aRerunWritesIdenticalFiles() throws Exception {
        List<Path> first = FieldOutputs.write(stack, map, out);
        List<byte[]> before = new ArrayList<>();
        for (Path file : first) {
            before.add(Files.readAllBytes(file));
        }

        FieldMap again = FieldFinder.find(new StackImages(stack), FieldParameters.DEFAULTS, line -> { });
        List<Path> second = FieldOutputs.write(stack, again, out);

        assertEquals(first, second);
        for (int i = 0; i < second.size(); i++) {
            assertArrayEquals(before.get(i), Files.readAllBytes(second.get(i)), second.get(i).toString());
        }
    }

    @Test
    void leavesNoPartFilesBehind() throws Exception {
        FieldOutputs.write(stack, map, out);

        assertTrue(filesIn(out).stream().noneMatch(name -> name.endsWith(".part")), filesIn(out).toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void explainsALockedOutputFile() throws Exception {
        FieldOutputs.write(stack, map, out);
        Path labels = out.resolve("fields-2025.tif");

        IOException failure;
        try (FileInputStream held = new FileInputStream(labels.toFile())) {
            failure = assertThrows(IOException.class, () -> FieldOutputs.write(stack, map, out));
        }

        assertTrue(failure.getMessage().contains("fields-2025.tif"), failure.getMessage());
        assertTrue(failure.getMessage().contains("open in another program"), failure.getMessage());
        assertTrue(filesIn(out).stream().noneMatch(name -> name.endsWith(".part")), filesIn(out).toString());
    }
}
