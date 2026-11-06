package kz.kazagrosat.pipeline.stac;

import java.time.Instant;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;

/**
 * One satellite scene.
 *
 * @param cloudCover cloud share of the whole tile in percent, not of any smaller area
 * @param footprint  where the scene really has data, in WGS84 lon/lat; often much less than the tile
 */
public record StacItem(
        String id,
        Instant datetime,
        double cloudCover,
        int epsg,
        Geometry footprint,
        Map<String, StacAsset> assets) {

    public StacAsset asset(String key) {
        StacAsset asset = assets.get(key);
        if (asset == null) {
            throw new IllegalArgumentException("Item " + id + " has no asset named " + key);
        }
        return asset;
    }
}
