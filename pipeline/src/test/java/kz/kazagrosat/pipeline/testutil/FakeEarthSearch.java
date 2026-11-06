package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.net.URI;

/**
 * A local stand-in for Earth Search and its S3 bucket: one scene over a small area, with the same
 * JSON shape, band files, scale and offset as a real Sentinel-2 C1 L2A item.
 *
 * <p>The 10 m bands are 64 x 64 pixels from (460000, 5922800). Red is 0.1 everywhere; NIR is 0.5.
 * SCL is 32 x 32 at 20 m, class 4 (vegetation), except for class 9 (cloud) at SCL col 10, row 3.
 */
public final class FakeEarthSearch implements AutoCloseable {

    public static final String SCENE_ID = "S2B_T41UMV_20250712T070939_L2A";

    /** Falls inside the bands above; its 20 m window is col 7, row 1, 18 x 15 (from Krueger-series UTM). */
    public static final String AREA = "62.400,53.450,62.405,53.4525";

    private static final double ORIGIN_X = 460000;
    private static final double ORIGIN_Y = 5922800;
    private static final int RED_DN = 2000;
    private static final int NIR_DN = 6000;

    private final TestHttpServer server;

    public FakeEarthSearch() throws IOException {
        this(new SyntheticTiff(32, 32, 16, 16)
                .bits(8)
                .geo(32641, ORIGIN_X, ORIGIN_Y, 20)
                .pixels((x, y) -> x == 10 && y == 3 ? 9 : 4));
    }

    public FakeEarthSearch(SyntheticTiff scl) throws IOException {
        server = new TestHttpServer();
        server.serveFile("/B04.tif", band(RED_DN));
        server.serveFile("/B08.tif", band(NIR_DN));
        server.serveFile("/SCL.tif", scl.build());
        server.handle("/search", exchange -> TestHttpServer.respondJson(exchange, 200, searchResponse()));
    }

    private static byte[] band(int digitalNumber) {
        return new SyntheticTiff(64, 64, 32, 32)
                .geo(32641, ORIGIN_X, ORIGIN_Y, 10)
                .pixels((x, y) -> digitalNumber)
                .build();
    }

    private String searchResponse() {
        return """
                {"type":"FeatureCollection","features":[{
                  "id":"%s",
                  "geometry":{"type":"Polygon","coordinates":[[[62.0,53.0],[63.0,53.0],[63.0,54.0],[62.0,54.0],[62.0,53.0]]]},
                  "properties":{"datetime":"2025-07-12T07:17:06.123Z","eo:cloud_cover":1.3,"proj:epsg":32641},
                  "assets":{
                    "red":{"href":"%s","raster:bands":[{"nodata":0,"data_type":"uint16","scale":0.0001,"offset":-0.1}]},
                    "nir":{"href":"%s","raster:bands":[{"nodata":0,"data_type":"uint16","scale":0.0001,"offset":-0.1}]},
                    "scl":{"href":"%s","raster:bands":[{"nodata":0,"data_type":"uint8"}]}
                  }}],"links":[]}"""
                .formatted(SCENE_ID, server.uri("/B04.tif"), server.uri("/B08.tif"), server.uri("/SCL.tif"));
    }

    public URI stacUri() {
        return server.uri("");
    }

    public TestHttpServer server() {
        return server;
    }

    @Override
    public void close() {
        server.close();
    }
}
