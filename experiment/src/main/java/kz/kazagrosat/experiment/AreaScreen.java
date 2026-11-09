package kz.kazagrosat.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.geo.Extent;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.NdviPipeline;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.HttpByteSource;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.stac.SceneSelector;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/**
 * Compares candidate test areas across the grain belt (Kostanay, Akmola and North Kazakhstan oblasts)
 * before any downloading. Throwaway, like the rest of this module; run from the repository root:
 * {@code java -Xmx1g -cp experiment/target/harvest-signal.jar kz.kazagrosat.experiment.AreaScreen data}.
 *
 * <ol>
 *   <li><b>Land.</b> Tiles the three oblasts into squares of 0.30 x 0.20 degrees (about 20 x 20 km) and
 *       measures each one's cropland, grassland and water shares on ESA WorldCover 2021, read from its
 *       80 m overview. Our own area (Kostanay, 62.40-62.70 E, 53.45-53.63 N) is measured too.</li>
 *   <li><b>Clouds.</b> For the best squares of each oblast, at least 60 km apart, and our own area: every
 *       Sentinel-2 photo of 1 August to 30 September 2023, 2024 and 2025, read from the scene
 *       classification's 80 m overview. Per pixel, the longest stretch without a clear photo; the typical
 *       (median) pixel's is the area's number, comparable with the per-field number of step 3.</li>
 * </ol>
 */
public final class AreaScreen {

    static final double SQUARE_WIDTH = 0.30;
    static final double SQUARE_HEIGHT = 0.20;
    static final BoundingBox OUR_AREA = new BoundingBox(62.40, 53.45, 62.70, 53.63);
    static final List<String> OBLASTS = List.of("Kostanay Region", "Akmola Region", "North Kazakhstan Region");
    static final int[] YEARS = {2025, 2024, 2023};
    static final int PICKS_PER_OBLAST = 3;
    static final double MIN_SPACING_KM = 60;
    static final double CLEAR_PHOTO = 0.8;

    private static final int WORLDCOVER_80M = 3;
    private static final int SCL_80M = 2;
    private static final String WORLDCOVER = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/"
            + "ESA_WorldCover_10m_2021_v200_%s_Map.tif";
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    record Square(String oblast, BoundingBox box, LandShares land) {}

    record Clouds(int year, String tile, int photos, int clearPhotos, long typicalGap) {}

    private AreaScreen() {}

    /** Squares of 0.30 x 0.20 degrees tiling the box from its south-west corner. */
    static List<BoundingBox> squares(double west, double east, double south, double north) {
        List<BoundingBox> squares = new ArrayList<>();
        int across = (int) Math.round((east - west) / SQUARE_WIDTH);
        int down = (int) Math.round((north - south) / SQUARE_HEIGHT);
        for (int row = 0; row < down; row++) {
            for (int col = 0; col < across; col++) {
                double w = round(west + col * SQUARE_WIDTH);
                double s = round(south + row * SQUARE_HEIGHT);
                squares.add(new BoundingBox(w, s, round(w + SQUARE_WIDTH), round(s + SQUARE_HEIGHT)));
            }
        }
        return squares;
    }

    private static double round(double degrees) {
        return Math.round(degrees * 1e6) / 1e6;
    }

    /** The 3 x 3 degree WorldCover tile holding the whole box, named by its lower-left corner. */
    static Optional<String> worldCoverTile(BoundingBox box) {
        int lat = (int) Math.floor(box.south() / 3) * 3;
        int lon = (int) Math.floor(box.west() / 3) * 3;
        if (box.north() > lat + 3 + 1e-9 || box.east() > lon + 3 + 1e-9) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "N%02dE%03d", lat, lon));
    }

    static LandShares sharesIn(PixelGrid grid, int[] tile, BoundingBox box) {
        // Pull the edges in by a hair so a square on pixel edges does not pick up a neighbouring row.
        double inset = grid.pixelSize() * 1e-3;
        PixelWindow window = grid.windowCovering(new Extent(box.west() + inset, box.south() + inset,
                box.east() - inset, box.north() - inset));
        int[] classes = new int[window.width() * window.height()];
        for (int y = 0; y < window.height(); y++) {
            System.arraycopy(tile, (window.row() + y) * grid.width() + window.col(), classes, y * window.width(),
                    window.width());
        }
        return LandShares.of(classes);
    }

    /** The share of pixels with data that the scene classification calls clear (vegetation, bare soil, water). */
    static double clearShare(int[] scl) {
        boolean[] usable = SclMask.usable(scl);
        int withData = 0;
        int clear = 0;
        for (int i = 0; i < scl.length; i++) {
            if (scl[i] != 0) {
                withData++;
                if (usable[i]) {
                    clear++;
                }
            }
        }
        return withData == 0 ? 0 : (double) clear / withData;
    }

    /** Per pixel, the longest stretch without a clear photo; the median over pixels. */
    static long typicalLongestGap(List<LocalDate> dates, List<boolean[]> clear, LocalDate from, LocalDate to) {
        int pixels = clear.isEmpty() ? 0 : clear.get(0).length;
        if (pixels == 0) {
            return Gaps.longest(List.of(), from, to);
        }
        long[] gaps = new long[pixels];
        for (int i = 0; i < pixels; i++) {
            List<LocalDate> seen = new ArrayList<>();
            for (int d = 0; d < dates.size(); d++) {
                if (clear.get(d)[i]) {
                    seen.add(dates.get(d));
                }
            }
            gaps[i] = Gaps.longest(seen, from, to);
        }
        Arrays.sort(gaps);
        return gaps[pixels / 2];
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AreaScreen <dataFolder> [<name>=<west,south,east,north> ...]");
            System.exit(2);
        }
        Path folder = Path.of(args[0]).resolve("areas");
        if (args.length > 1) {
            measureOnly(folder, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Map<String, Geometry> oblasts = loadOblasts(folder.resolve("kaz-adm1.geojson"));
        RetryingHttp http = new RetryingHttp(RetryPolicy.DEFAULT);

        List<Square> squares = landShares(oblasts, http);
        Square ours = squares.stream().filter(s -> s.oblast().equals("our area")).findFirst().orElseThrow();
        writeLand(folder.resolve("land.csv"), squares);
        printLand(squares, ours);

        StacClient stac = new StacClient(StacClient.EARTH_SEARCH, RetryPolicy.DEFAULT);
        Map<Square, List<Clouds>> clouds = new LinkedHashMap<>();
        Map<Square, String> names = new LinkedHashMap<>();
        clouds.put(ours, clouds(stac, http, ours.box()).orElseThrow());
        names.put(ours, "Ours");
        for (String oblast : OBLASTS) {
            List<Square> ranked = squares.stream()
                    .filter(s -> s.oblast().equals(oblast))
                    .sorted(Comparator.comparingDouble((Square s) -> s.land().cropland()).reversed())
                    .toList();
            List<Square> picked = new ArrayList<>();
            for (Square candidate : ranked) {
                if (picked.size() == PICKS_PER_OBLAST) {
                    break;
                }
                if (picked.stream().anyMatch(p -> km(p.box(), candidate.box()) < MIN_SPACING_KM)) {
                    continue;
                }
                Optional<List<Clouds>> measured = clouds(stac, http, candidate.box());
                if (measured.isEmpty()) {
                    System.out.println("  " + label(candidate.box()) + ": no single Sentinel-2 tile covers it, skipped");
                    continue;
                }
                picked.add(candidate);
                clouds.put(candidate, measured.get());
                names.put(candidate, oblast.substring(0, 1) + picked.size());
                System.out.println("  measured " + oblast + " " + label(candidate.box()));
            }
        }
        writeClouds(folder.resolve("clouds.csv"), clouds, names);
        printClouds(clouds, names);
        AreaMap.write(folder.resolve("map.png"), oblasts, squares, names);
    }

    /** Land and clouds for the named boxes only, added to clouds-extra.csv. */
    private static void measureOnly(Path folder, String[] named) throws Exception {
        RetryingHttp http = new RetryingHttp(RetryPolicy.DEFAULT);
        StacClient stac = new StacClient(StacClient.EARTH_SEARCH, RetryPolicy.DEFAULT);
        Map<Square, List<Clouds>> clouds = new LinkedHashMap<>();
        Map<Square, String> names = new LinkedHashMap<>();
        for (String arg : named) {
            String name = arg.substring(0, arg.indexOf('='));
            BoundingBox box = BoundingBox.parse(arg.substring(arg.indexOf('=') + 1));
            URI uri = URI.create(String.format(Locale.ROOT, WORLDCOVER, worldCoverTile(box).orElseThrow()));
            CogReader reader = CogReader.open(new HttpByteSource(uri, http), WORLDCOVER_80M);
            PixelGrid grid = reader.grid();
            int[] pixels = reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
            Square square = new Square("extra", box, sharesIn(grid, pixels, box));
            Optional<List<Clouds>> measured = clouds(stac, http, box);
            if (measured.isEmpty()) {
                System.out.println(name + ": no single Sentinel-2 tile covers it");
                continue;
            }
            clouds.put(square, measured.get());
            names.put(square, name);
        }
        writeClouds(folder.resolve("clouds-extra.csv"), clouds, names);
        printClouds(clouds, names);
    }

    private static List<Square> landShares(Map<String, Geometry> oblasts, RetryingHttp http)
            throws IOException, InterruptedException {
        List<Square> squares = new ArrayList<>();
        Map<String, List<BoundingBox>> byTile = new TreeMap<>();
        Map<BoundingBox, String> oblastOf = new LinkedHashMap<>();
        for (BoundingBox box : squares(60, 75, 48, 57)) {
            Geometry polygon = polygon(box);
            for (String oblast : OBLASTS) {
                if (oblasts.get(oblast).covers(polygon)) {
                    oblastOf.put(box, oblast);
                    byTile.computeIfAbsent(worldCoverTile(box).orElseThrow(), t -> new ArrayList<>()).add(box);
                }
            }
        }
        String ourTile = worldCoverTile(OUR_AREA).orElseThrow();
        byTile.computeIfAbsent(ourTile, t -> new ArrayList<>());
        for (Map.Entry<String, List<BoundingBox>> tile : byTile.entrySet()) {
            URI uri = URI.create(String.format(Locale.ROOT, WORLDCOVER, tile.getKey()));
            CogReader reader = CogReader.open(new HttpByteSource(uri, http), WORLDCOVER_80M);
            PixelGrid grid = reader.grid();
            int[] pixels = reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
            for (BoundingBox box : tile.getValue()) {
                squares.add(new Square(oblastOf.get(box), box, sharesIn(grid, pixels, box)));
            }
            if (tile.getKey().equals(ourTile)) {
                squares.add(new Square("our area", OUR_AREA, sharesIn(grid, pixels, OUR_AREA)));
            }
            System.out.println("  WorldCover " + tile.getKey() + ": " + tile.getValue().size() + " squares");
        }
        return squares;
    }

    /** Empty when no single Sentinel-2 tile covers the box. */
    private static Optional<List<Clouds>> clouds(StacClient stac, RetryingHttp http, BoundingBox box)
            throws Exception {
        List<Clouds> years = new ArrayList<>();
        for (int year : YEARS) {
            LocalDate from = LocalDate.of(year, 8, 1);
            LocalDate to = LocalDate.of(year, 9, 30);
            List<StacItem> covering = stac.search(new StacSearch(NdviPipeline.COLLECTION, box, from, to, null))
                    .stream().filter(scene -> SceneSelector.covers(scene, box)).toList();
            if (covering.isEmpty()) {
                return Optional.empty();
            }
            String tile = covering.stream()
                    .collect(Collectors.groupingBy(AreaScreen::tileOf, TreeMap::new, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            List<StacItem> scenes = covering.stream().filter(s -> tileOf(s).equals(tile))
                    .sorted(Comparator.comparing(StacItem::datetime)).toList();
            List<int[]> classes = readAll(scenes, http, box);
            List<LocalDate> dates = new ArrayList<>();
            List<boolean[]> clear = new ArrayList<>();
            int clearPhotos = 0;
            for (int i = 0; i < scenes.size(); i++) {
                dates.add(LocalDate.ofInstant(scenes.get(i).datetime(), ZoneOffset.UTC));
                clear.add(SclMask.usable(classes.get(i)));
                if (clearShare(classes.get(i)) >= CLEAR_PHOTO) {
                    clearPhotos++;
                }
            }
            long distinctDates = dates.stream().distinct().count();
            years.add(new Clouds(year, tile, (int) distinctDates, clearPhotos,
                    typicalLongestGap(dates, clear, from, to)));
        }
        return Optional.of(years);
    }

    private static List<int[]> readAll(List<StacItem> scenes, RetryingHttp http, BoundingBox box) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<int[]>> reads = new ArrayList<>();
            for (StacItem scene : scenes) {
                reads.add(pool.submit(() -> {
                    CogReader scl = CogReader.open(new HttpByteSource(URI.create(scene.asset("scl").href()), http),
                            SCL_80M);
                    UtmProjection projection = UtmProjection.forEpsg(scl.grid().epsg());
                    return scl.readWindow(scl.grid().windowCovering(projection.project(box)));
                }));
            }
            List<int[]> classes = new ArrayList<>();
            for (Future<int[]> read : reads) {
                classes.add(read.get());
            }
            return classes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String tileOf(StacItem scene) {
        return scene.id().split("_")[1];
    }

    static double km(BoundingBox a, BoundingBox b) {
        double lat = Math.toRadians((a.south() + a.north() + b.south() + b.north()) / 4);
        double dx = ((a.west() + a.east()) - (b.west() + b.east())) / 2 * 111.32 * Math.cos(lat);
        double dy = ((a.south() + a.north()) - (b.south() + b.north())) / 2 * 110.57;
        return Math.hypot(dx, dy);
    }

    static String label(BoundingBox box) {
        return String.format(Locale.ROOT, "%.2f-%.2f E, %.2f-%.2f N", box.west(), box.east(), box.south(), box.north());
    }

    static Polygon polygon(BoundingBox box) {
        return (Polygon) GEOMETRY.toGeometry(new Envelope(box.west(), box.east(), box.south(), box.north()));
    }

    private static Map<String, Geometry> loadOblasts(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        Map<String, Geometry> oblasts = new LinkedHashMap<>();
        for (JsonNode feature : root.get("features")) {
            String name = feature.get("properties").get("shapeName").asText();
            JsonNode geometry = feature.get("geometry");
            List<Polygon> polygons = new ArrayList<>();
            if (geometry.get("type").asText().equals("Polygon")) {
                polygons.add(polygonOf(geometry.get("coordinates")));
            } else {
                for (JsonNode part : geometry.get("coordinates")) {
                    polygons.add(polygonOf(part));
                }
            }
            oblasts.put(name, GEOMETRY.createMultiPolygon(polygons.toArray(Polygon[]::new)).buffer(0));
        }
        Map<String, Geometry> wanted = new LinkedHashMap<>();
        for (String name : OBLASTS) {
            wanted.put(name, oblasts.get(name));
        }
        for (Map.Entry<String, Geometry> oblast : oblasts.entrySet()) {
            if (!OBLASTS.contains(oblast.getKey())) {
                wanted.put("other:" + oblast.getKey(), oblast.getValue());
            }
        }
        return wanted;
    }

    private static Polygon polygonOf(JsonNode rings) {
        LinearRing shell = ringOf(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ringOf(rings.get(i));
        }
        return GEOMETRY.createPolygon(shell, holes);
    }

    private static LinearRing ringOf(JsonNode ring) {
        Coordinate[] points = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            points[i] = new Coordinate(ring.get(i).get(0).asDouble(), ring.get(i).get(1).asDouble());
        }
        return GEOMETRY.createLinearRing(points);
    }

    private static void writeLand(Path file, List<Square> squares) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("oblast,west,south,east,north,pixels,cropland,grassland,water,trees,built,bare");
        for (Square s : squares) {
            LandShares l = s.land();
            lines.add(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    s.oblast(), s.box().west(), s.box().south(), s.box().east(), s.box().north(), l.pixels(),
                    l.cropland(), l.grassland(), l.water(), l.trees(), l.built(), l.bare()));
        }
        Files.write(file, lines);
    }

    private static void writeClouds(Path file, Map<Square, List<Clouds>> clouds, Map<Square, String> names)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("name,oblast,west,south,east,north,cropland,year,tile,photos,clear_photos,typical_longest_gap_days");
        for (Map.Entry<Square, List<Clouds>> entry : clouds.entrySet()) {
            Square s = entry.getKey();
            for (Clouds c : entry.getValue()) {
                lines.add(String.format(Locale.ROOT, "%s,%s,%.2f,%.2f,%.2f,%.2f,%.4f,%d,%s,%d,%d,%d",
                        names.get(s), s.oblast(),
                        s.box().west(), s.box().south(), s.box().east(), s.box().north(), s.land().cropland(),
                        c.year(), c.tile(), c.photos(), c.clearPhotos(), c.typicalGap()));
            }
        }
        Files.write(file, lines);
    }

    private static void printLand(List<Square> squares, Square ours) {
        System.out.println();
        System.out.printf(Locale.ROOT, "Our area: cropland %.0f%%, grassland %.0f%%, water and wetland %.0f%%%n",
                100 * ours.land().cropland(), 100 * ours.land().grassland(), 100 * ours.land().water());
        Map<String, List<Square>> byOblast = squares.stream().filter(s -> OBLASTS.contains(s.oblast()))
                .collect(Collectors.groupingBy(Square::oblast, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Square>> entry : byOblast.entrySet()) {
            double[] crop = entry.getValue().stream().mapToDouble(s -> s.land().cropland()).sorted().toArray();
            long atLeastOurs = Arrays.stream(crop).filter(c -> c >= ours.land().cropland()).count();
            System.out.printf(Locale.ROOT, "%s: %d squares, median cropland %.0f%%, best %.0f%%, %d at or above ours%n",
                    entry.getKey(), crop.length, 100 * crop[crop.length / 2], 100 * crop[crop.length - 1],
                    atLeastOurs);
        }
    }

    private static void printClouds(Map<Square, List<Clouds>> clouds, Map<Square, String> names) {
        System.out.println();
        System.out.println("| name | area | oblast | cropland | 2025 gap, clear photos | 2024 | 2023 |");
        System.out.println("|---|---|---|---|---|---|---|");
        Function<Clouds, String> cell = c -> c.typicalGap() + " days, " + c.clearPhotos() + " of " + c.photos();
        for (Map.Entry<Square, List<Clouds>> entry : clouds.entrySet()) {
            Square s = entry.getKey();
            List<Clouds> c = entry.getValue();
            System.out.printf(Locale.ROOT, "| %s | %s | %s | %.0f%% | %s | %s | %s |%n", names.get(s),
                    label(s.box()), s.oblast(),
                    100 * s.land().cropland(), cell.apply(c.get(0)), cell.apply(c.get(1)), cell.apply(c.get(2)));
        }
    }
}
