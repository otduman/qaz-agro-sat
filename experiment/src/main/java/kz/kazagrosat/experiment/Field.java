package kz.kazagrosat.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kz.kazagrosat.core.geo.Extent;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * A hand-picked box inside one field, in 10 m pixels counted from the top-left of the study area.
 *
 * @param kind "crop", or a control that nobody harvests
 */
record Field(String name, String kind, int left, int top, int right, int bottom) {

    static List<Field> load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<Field> fields = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(",");
            fields.add(new Field(parts[0], parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
        }
        return fields;
    }

    /** Where the box lies on the ground, given the study area's 10 m grid. */
    Extent extent(PixelGrid areaGrid) {
        double size = areaGrid.pixelSize();
        return new Extent(
                areaGrid.originX() + left * size,
                areaGrid.originY() - bottom * size,
                areaGrid.originX() + right * size,
                areaGrid.originY() - top * size);
    }
}
