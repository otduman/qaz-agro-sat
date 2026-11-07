package kz.kazagrosat.core.geo;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Converts between WGS84 lon/lat and a northern-hemisphere UTM zone, the grids Sentinel-2 uses over Kazakhstan. */
public final class UtmProjection {

    private static final int FIRST_NORTH_EPSG = 32601;
    private static final int LAST_NORTH_EPSG = 32660;

    /** Even, so the midpoint of each side is sampled: parallels bulge most there. */
    private static final int SEGMENTS_PER_SIDE = 32;

    private final CoordinateTransform transform;
    private final CoordinateTransform inverse;

    private UtmProjection(int zone) {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem wgs84 =
                factory.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem utm = factory.createFromParameters(
                "UTM" + zone, "+proj=utm +zone=" + zone + " +datum=WGS84 +units=m +no_defs");
        CoordinateTransformFactory transforms = new CoordinateTransformFactory();
        this.transform = transforms.createTransform(wgs84, utm);
        this.inverse = transforms.createTransform(utm, wgs84);
    }

    public static UtmProjection forEpsg(int epsg) {
        if (epsg < FIRST_NORTH_EPSG || epsg > LAST_NORTH_EPSG) {
            throw new IllegalArgumentException("Not a northern UTM zone: EPSG:" + epsg);
        }
        return new UtmProjection(epsg - FIRST_NORTH_EPSG + 1);
    }

    /** The WGS84 position of a point given in this zone's UTM metres. */
    public LonLat unproject(double easting, double northing) {
        ProjCoordinate lonLat = new ProjCoordinate();
        inverse.transform(new ProjCoordinate(easting, northing), lonLat);
        return new LonLat(lonLat.x, lonLat.y);
    }

    public UtmPoint project(double longitude, double latitude) {
        ProjCoordinate projected = new ProjCoordinate();
        transform.transform(new ProjCoordinate(longitude, latitude), projected);
        return new UtmPoint(projected.x, projected.y);
    }

    /**
     * The smallest UTM rectangle holding the whole box. Sides are sampled, not just corners, because
     * a straight lon/lat edge is curved in UTM and can reach past both of its corners.
     */
    public Extent project(BoundingBox box) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= SEGMENTS_PER_SIDE; i++) {
            double fraction = (double) i / SEGMENTS_PER_SIDE;
            double lon = box.west() + fraction * (box.east() - box.west());
            double lat = box.south() + fraction * (box.north() - box.south());
            UtmPoint[] onPerimeter = {
                    project(lon, box.south()), project(lon, box.north()),
                    project(box.west(), lat), project(box.east(), lat),
            };
            for (UtmPoint point : onPerimeter) {
                minX = Math.min(minX, point.easting());
                minY = Math.min(minY, point.northing());
                maxX = Math.max(maxX, point.easting());
                maxY = Math.max(maxY, point.northing());
            }
        }
        return new Extent(minX, minY, maxX, maxY);
    }
}
