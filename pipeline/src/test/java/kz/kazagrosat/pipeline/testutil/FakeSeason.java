package kz.kazagrosat.pipeline.testutil;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;

/**
 * A local stand-in for Earth Search with a whole season of scenes over the FakeEarthSearch area, each
 * with the seven layers a season stack keeps. Same geometry as FakeEarthSearch: the 10 m bands are
 * 64 x 64 pixels and the 20 m layers 32 x 32, both from (460000, 5922800) in EPSG:32641.
 *
 * <p>Band values follow {@link #expectedDn}, so a test can tell exactly which pixel landed where.
 */
public final class FakeSeason implements AutoCloseable {

    /** The assets in stack layer order; band index = position in this list. */
    private static final List<String> BANDS = List.of("blue", "green", "red", "nir", "swir16", "swir22");
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final double ORIGIN_X = 460000;
    private static final double ORIGIN_Y = 5922800;

    private record Scaling(double scale, double offset) {}

    private record Scene(LocalDate date, double cloud, boolean covering, Map<String, Scaling> scaling) {
        String id() {
            return "S2B_T41UMV_" + date.format(COMPACT) + "T070939_L2A";
        }
    }

    private final TestHttpServer server;
    private final List<Scene> scenes = new ArrayList<>();
    private final Set<String> broken = ConcurrentHashMap.newKeySet();

    public FakeSeason() throws IOException {
        server = new TestHttpServer();
        server.handle("/search", exchange -> TestHttpServer.respondJson(exchange, 200, searchResponse()));
    }

    /** The digital number every band file holds at full-image pixel (x, y). */
    public static int expectedDn(String assetKey, int x, int y) {
        return 1000 + 100 * BANDS.indexOf(assetKey) + (3 * x + y) % 50;
    }

    /** A scene covering the area, clear everywhere. */
    public FakeSeason scene(String date, double cloud) {
        return scene(date, cloud, (x, y) -> 4);
    }

    /** A scene covering the area, with cloud mask classes from {@code scl} at full-image pixel (x, y). */
    public FakeSeason scene(String date, double cloud, IntBinaryOperator scl) {
        return add(new Scene(LocalDate.parse(date), cloud, true, new HashMap<>()), scl);
    }

    /** A scene whose footprint lies far from the area. */
    public FakeSeason sceneOutside(String date) {
        return add(new Scene(LocalDate.parse(date), 1, false, new HashMap<>()), (x, y) -> 4);
    }

    /** Makes one layer file of one date answer 404 until {@link #repair()}. */
    public FakeSeason breakLayer(String date, String assetKey) {
        broken.add(path(LocalDate.parse(date), assetKey));
        return this;
    }

    public FakeSeason repair() {
        broken.clear();
        return this;
    }

    /** Gives one band of one date a different scale and offset in the catalogue. */
    public FakeSeason scaling(String date, String assetKey, double scale, double offset) {
        LocalDate day = LocalDate.parse(date);
        scenes.stream().filter(s -> s.date().equals(day)).forEach(s -> s.scaling().put(assetKey, new Scaling(scale, offset)));
        return this;
    }

    public URI stacUri() {
        return server.uri("");
    }

    public TestHttpServer server() {
        return server;
    }

    /** The path a layer file of a date is served at. */
    public static String path(LocalDate date, String assetKey) {
        return "/" + date.format(COMPACT) + "/" + assetKey + ".tif";
    }

    private FakeSeason add(Scene scene, IntBinaryOperator scl) {
        scenes.add(scene);
        for (String band : BANDS) {
            boolean tenMetre = !band.startsWith("swir");
            SyntheticTiff tiff = tenMetre
                    ? new SyntheticTiff(64, 64, 32, 32).geo(32641, ORIGIN_X, ORIGIN_Y, 10)
                    : new SyntheticTiff(32, 32, 16, 16).geo(32641, ORIGIN_X, ORIGIN_Y, 20);
            serve(path(scene.date(), band), tiff.pixels((x, y) -> expectedDn(band, x, y)).build());
        }
        serve(path(scene.date(), "scl"), new SyntheticTiff(32, 32, 16, 16).bits(8)
                .geo(32641, ORIGIN_X, ORIGIN_Y, 20).pixels(scl::applyAsInt).build());
        return this;
    }

    private void serve(String path, byte[] file) {
        server.handle(path, exchange -> {
            if (broken.contains(path)) {
                TestHttpServer.respond(exchange, 404, new byte[0]);
            } else {
                TestHttpServer.respondRange(exchange, file);
            }
        });
    }

    private String searchResponse() {
        String features = scenes.stream().map(this::feature).collect(Collectors.joining(","));
        return "{\"type\":\"FeatureCollection\",\"features\":[" + features + "],\"links\":[]}";
    }

    private String feature(Scene scene) {
        String ring = scene.covering()
                ? "[[62.0,53.0],[63.0,53.0],[63.0,54.0],[62.0,54.0],[62.0,53.0]]"
                : "[[70.0,50.0],[71.0,50.0],[71.0,51.0],[70.0,51.0],[70.0,50.0]]";
        String assets = BANDS.stream().map(band -> {
            Scaling scaling = scene.scaling().getOrDefault(band, new Scaling(0.0001, -0.1));
            return "\"%s\":{\"href\":\"%s\",\"raster:bands\":[{\"nodata\":0,\"data_type\":\"uint16\",\"scale\":%s,\"offset\":%s}]}"
                    .formatted(band, server.uri(path(scene.date(), band)), scaling.scale(), scaling.offset());
        }).collect(Collectors.joining(","));
        assets += ",\"scl\":{\"href\":\"%s\",\"raster:bands\":[{\"nodata\":0,\"data_type\":\"uint8\"}]}"
                .formatted(server.uri(path(scene.date(), "scl")));
        return """
                {"id":"%s","geometry":{"type":"Polygon","coordinates":[%s]},
                 "properties":{"datetime":"%sT07:17:06.123Z","eo:cloud_cover":%s,"proj:epsg":32641},
                 "assets":{%s}}"""
                .formatted(scene.id(), ring, scene.date(), scene.cloud(), assets);
    }

    @Override
    public void close() {
        server.close();
    }
}
