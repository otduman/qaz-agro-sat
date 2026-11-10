package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * A stand-in for Planetary Computer's Landsat Collection 2 level 2: a STAC search, the anonymous token, and the
 * scenes' files, around the {@link HandStack} area. Four scenes:
 * <ul>
 *   <li>{@link #CLEAR}, Landsat 9 on 10 July 2025, clear everywhere, bands holding {@link #dn(String)};</li>
 *   <li>{@link #CLOUDY}, Landsat 8 on 18 July 2025, cloud everywhere;</li>
 *   <li>{@link #LANDSAT_7}, on 14 July 2025, which is left out;</li>
 *   <li>{@link #OTHER_ZONE}, Landsat 8 on 26 July 2025, on a grid in UTM zone 42, which is left out;</li>
 *   <li>{@link #CORNER}, Landsat 8 on 2 July 2025, clear in one 30 m pixel over the area (1 of its 1,080 pixels), cloud
 *       elsewhere.</li>
 * </ul>
 */
public final class FakePlanetaryComputer implements AutoCloseable {

    public static final String CLEAR = "LC09_L2SP_160023_20250710_02_T1";
    public static final String CLOUDY = "LC08_L2SP_161023_20250718_02_T1";
    public static final String LANDSAT_7 = "LE07_L2SP_160023_20250714_02_T1";
    public static final String OTHER_ZONE = "LC08_L2SP_159023_20250726_02_T1";
    public static final String CORNER = "LC08_L2SP_161023_20250702_02_T1";
    public static final String[] BANDS = {"blue", "green", "red", "nir08", "swir16", "swir22"};
    public static final String TOKEN = "st=2025&sig=fake";

    /** The scenes' 30 m grid: the HandStack area's pixel centres fall in columns 4 to 16 and rows 0 to 10. */
    public static final PixelGrid SCENE = new PixelGrid(32641, 460005, 5922795, 30, 20, 16);
    /** The part of {@link #SCENE} a download keeps. */
    public static final PixelGrid WINDOW = new PixelGrid(32641, 460125, 5922795, 30, 13, 11);
    public static final double SCALE = 0.0000275;
    public static final double OFFSET = -0.2;
    public static final int CLEAR_QA = 21824;
    public static final int CLOUD_QA = (1 << 6) | (1 << 3);

    private final TestHttpServer server;
    private final List<String> scenes = new ArrayList<>();

    public FakePlanetaryComputer() throws IOException {
        server = new TestHttpServer();
        scene(CLEAR, "2025-07-10T06:40:00Z", CLEAR_QA, 32641);
        scene(CLOUDY, "2025-07-18T06:46:00Z", CLOUD_QA, 32641);
        scene(LANDSAT_7, "2025-07-14T06:20:00Z", CLEAR_QA, 32641);
        scene(OTHER_ZONE, "2025-07-26T06:34:00Z", CLEAR_QA, 32642);
        scene(CORNER, "2025-07-02T06:46:00Z", (x, y) -> x == 4 && y == 0 ? CLEAR_QA : CLOUD_QA, 32641);
        server.handle("/search", exchange -> TestHttpServer.respondJson(exchange, 200,
                "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", scenes) + "],\"links\":[]}"));
        server.handle("/token", exchange -> TestHttpServer.respondJson(exchange, 200,
                "{\"msft:expiry\":\"2099-01-01T00:00:00Z\",\"token\":\"" + TOKEN + "\"}"));
    }

    /** The digital number every pixel of a band holds. */
    public static int dn(String band) {
        return switch (band) {
            case "blue" -> 9000;
            case "green" -> 10000;
            case "red" -> 9500;
            case "nir08" -> 20000;
            case "swir16" -> 16000;
            case "swir22" -> 13000;
            default -> throw new IllegalArgumentException(band);
        };
    }

    private void scene(String id, String datetime, int qa, int epsg) {
        scene(id, datetime, (x, y) -> qa, epsg);
    }

    private void scene(String id, String datetime, SyntheticTiff.Pixels qa, int epsg) {
        double originX = epsg == 32641 ? SCENE.originX() : 200005;
        server.serveFile("/" + id + "/qa_pixel.tif", tiff(epsg, originX, qa));
        StringBuilder assets = new StringBuilder("\"qa_pixel\":" + asset(id, "qa_pixel", "\"nodata\":1"));
        for (String band : BANDS) {
            server.serveFile("/" + id + "/" + band + ".tif", tiff(epsg, originX, (x, y) -> dn(band)));
            assets.append(",\"").append(band).append("\":")
                    .append(asset(id, band, "\"nodata\":0,\"scale\":" + SCALE + ",\"offset\":" + OFFSET));
        }
        scenes.add("""
                {"id":"%s",
                 "geometry":{"type":"Polygon","coordinates":[[[61.0,53.0],[64.0,53.0],[64.0,54.5],[61.0,54.5],[61.0,53.0]]]},
                 "properties":{"datetime":"%s","eo:cloud_cover":10.0,"proj:epsg":%d},
                 "assets":{%s}}""".formatted(id, datetime, epsg, assets));
    }

    private String asset(String id, String band, String raster) {
        return "{\"href\":\"" + server.uri("/" + id + "/" + band + ".tif") + "\",\"raster:bands\":[{" + raster + "}]}";
    }

    private static byte[] tiff(int epsg, double originX, SyntheticTiff.Pixels pixels) {
        return new SyntheticTiff(SCENE.width(), SCENE.height(), 16, 16)
                .geo(epsg, originX, SCENE.originY(), SCENE.pixelSize())
                .nodata("0")
                .pixels(pixels)
                .build();
    }

    public URI stacUri() {
        return server.uri("");
    }

    public URI tokenUri() {
        return server.uri("/token");
    }

    public TestHttpServer server() {
        return server;
    }

    @Override
    public void close() {
        server.close();
    }
}
