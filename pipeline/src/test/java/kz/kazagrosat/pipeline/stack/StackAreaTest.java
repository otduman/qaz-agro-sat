package kz.kazagrosat.pipeline.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.raster.PixelGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackAreaTest {

    private static final BoundingBox BBOX = new BoundingBox(62.40, 53.45, 62.70, 53.63);
    private static final StackArea AREA = new StackArea(BBOX, 2025, "T41UMV",
            new PixelGrid(32641, 460140, 5942540, 10, 2004, 2018),
            new PixelGrid(32641, 460140, 5942540, 20, 1002, 1009));

    @TempDir
    Path folder;

    @Test
    void roundTripsThroughThePropertiesFile() throws Exception {
        AREA.write(folder);

        assertEquals(AREA, StackArea.read(folder).orElseThrow());
    }

    @Test
    void sameRequestComparesBboxAndYear() {
        assertTrue(AREA.sameRequest(new BoundingBox(62.40, 53.45, 62.70, 53.63), 2025));
        assertFalse(AREA.sameRequest(BBOX, 2024));
        assertFalse(AREA.sameRequest(new BoundingBox(62.41, 53.45, 62.70, 53.63), 2025));
    }

    @Test
    void absentWhenTheFolderHasNoStack() throws Exception {
        assertTrue(StackArea.read(folder).isEmpty());
    }
}
