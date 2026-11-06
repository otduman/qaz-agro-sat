package kz.kazagrosat.pipeline.stac;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kz.kazagrosat.core.geo.BoundingBox;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Picks a scene for an area. Coverage comes first: a tile on the edge of a satellite pass can report
 * almost no cloud simply because it is almost empty, so cloud cover alone picks blank images.
 */
public final class SceneSelector {

    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private SceneSelector() {}

    /** The least cloudy scene whose real data footprint covers the whole area. */
    public static Optional<StacItem> bestCovering(List<StacItem> scenes, BoundingBox area) {
        return scenes.stream()
                .filter(scene -> covers(scene, area))
                .min(Comparator.comparingDouble(StacItem::cloudCover));
    }

    public static boolean covers(StacItem scene, BoundingBox area) {
        Geometry box = GEOMETRY.toGeometry(new Envelope(area.west(), area.east(), area.south(), area.north()));
        return scene.footprint().covers(box);
    }
}
