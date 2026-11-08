package kz.kazagrosat.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import kz.kazagrosat.core.mask.SnowTest;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.core.series.FieldInteriors;
import kz.kazagrosat.core.series.Percentiles;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.cog.HttpByteSource;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.stac.StacAsset;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;
import kz.kazagrosat.pipeline.stack.StackArea;

/**
 * The Landsat test: would Landsat 8 and 9, free from Planetary Computer with no account, fill the cloudy gaps in
 * our Sentinel-2 seasons, and do they read our fields the same way? Run from the repository root after the
 * {@code series} command: {@code LandsatCheck <dataFolder> [<year>...]}.
 *
 * <p>Each field is read as step 3 reads it: 20 m inside its edge, values only when 60% of that is clear, medians.
 * Writes {@code <data>/landsat-<year>/landsat-series-<year>.csv} and prints the agreement and gap tables.
 */
public final class LandsatCheck {

    static final URI STAC = URI.create("https://planetarycomputer.microsoft.com/api/stac/v1");
    static final URI TOKEN = URI.create("https://planetarycomputer.microsoft.com/api/sas/v1/token/landsat-c2-l2");
    static final String COLLECTION = "landsat-c2-l2";
    /** In the order {@link LandsatGrid.Day} keeps them. */
    static final String[] BANDS = {"red", "nir08", "swir16", "swir22", "blue", "green"};
    static final int RED = 0;
    static final int NIR = 1;
    static final int SWIR16 = 2;
    static final int SWIR22 = 3;
    static final int BLUE = 4;
    static final int GREEN = 5;
    static final int SHRINK = 2;
    static final double MIN_CLEAR = 0.6;
    static final double MIN_AREA_CLEAR = 0.01;
    static final int MAX_DAYS_APART = 1;
    static final String[] COMPARED = {"ndvi_median", "b11", "b12", "nir", "blue"};

    private record Row(String field, LocalDate date, String satellite, double clear, double[] values) {}

    private LandsatCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LandsatCheck <dataFolder> [<year>...]");
            System.exit(2);
        }
        Path data = Path.of(args[0]);
        List<Integer> years = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            years.add(Integer.parseInt(args[i]));
        }
        if (years.isEmpty()) {
            years.addAll(List.of(2025, 2024, 2023));
        }
        RetryingHttp http = new RetryingHttp(RetryPolicy.DEFAULT);
        PlanetaryToken token = new PlanetaryToken(() -> fetchToken(http));
        for (int year : years) {
            season(data, year, http, token);
        }
    }

    private static PlanetaryToken.Token fetchToken(RetryingHttp http) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(TOKEN).timeout(Duration.ofSeconds(30)).GET().build());
        if (response.statusCode() != 200) {
            throw new IOException("Token request failed: HTTP " + response.statusCode());
        }
        JsonNode json = new ObjectMapper().readTree(response.body());
        return new PlanetaryToken.Token(json.get("token").asText(), Instant.parse(json.get("msft:expiry").asText()));
    }

    private static void season(Path data, int year, RetryingHttp http, PlanetaryToken token) throws Exception {
        StackArea area = StackArea.read(data.resolve("season-" + year))
                .orElseThrow(() -> new IOException("No season stack for " + year));
        PixelGrid grid = area.grid();
        CogReader labelReader = CogReader.open(new FileByteSource(data.resolve("fields-" + year).resolve("fields-" + year + ".tif")));
        int[] labels = labelReader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
        FieldInteriors interiors = FieldInteriors.of(labels, grid.width(), grid.height(), SHRINK);
        Map<String, int[]> interiorOf = new LinkedHashMap<>();
        for (String[] f : readCsv(data.resolve("series-" + year).resolve("fields-" + year + ".csv"))) {
            if (f[6].equals("yes")) {
                int region = Integer.parseInt(f[0].substring(f[0].indexOf("-F") + 2));
                interiorOf.put(f[0], interiors.pixelsOf(region));
            }
        }

        List<StacItem> items = new StacClient(STAC, RetryPolicy.DEFAULT).search(new StacSearch(COLLECTION, area.bbox(),
                LocalDate.of(year, 5, 1), LocalDate.of(year, 10, 31), null));
        Map<String, List<StacItem>> days = new TreeMap<>();
        int landsat7 = 0;
        for (StacItem item : items) {
            String satellite = item.id().substring(0, 4);
            if (!satellite.equals("LC08") && !satellite.equals("LC09")) {
                landsat7++;
                continue;
            }
            LocalDate date = item.datetime().atZone(ZoneOffset.UTC).toLocalDate();
            days.computeIfAbsent(date + " " + satellite, k -> new ArrayList<>()).add(item);
        }
        System.out.printf(Locale.ROOT, "%n%d: %d Landsat scenes found (%d Landsat 7 left out), %d satellite-days%n",
                year, items.size(), landsat7, days.size());

        List<Row> rows = new ArrayList<>();
        int kept = 0;
        for (Map.Entry<String, List<StacItem>> entry : days.entrySet()) {
            LocalDate date = LocalDate.parse(entry.getKey().substring(0, 10));
            String satellite = entry.getKey().substring(11);
            LandsatGrid.Day day = new LandsatGrid.Day(grid.width() * grid.height(), BANDS.length);
            for (StacItem item : entry.getValue()) {
                addScene(day, item, grid, http, token);
            }
            boolean[] clear = day.clear();
            boolean[] snow = SnowTest.snow(day.band(GREEN), day.band(NIR), day.band(SWIR16));
            long clearCount = 0;
            for (int i = 0; i < clear.length; i++) {
                clear[i] &= !snow[i];
                if (clear[i]) {
                    clearCount++;
                }
            }
            double areaClear = (double) clearCount / clear.length;
            System.err.printf(Locale.ROOT, "%s %s: %d scenes, area %.0f%% clear%n", date, satellite, day.scenes(),
                    areaClear * 100);
            if (areaClear < MIN_AREA_CLEAR) {
                continue;
            }
            kept++;
            float[] ndvi = new float[clear.length];
            float[] red = day.band(RED);
            float[] nir = day.band(NIR);
            for (int i = 0; i < ndvi.length; i++) {
                ndvi[i] = (nir[i] - red[i]) / (nir[i] + red[i]);
            }
            float[][] compared = {ndvi, day.band(SWIR16), day.band(SWIR22), nir, day.band(BLUE)};
            for (Map.Entry<String, int[]> field : interiorOf.entrySet()) {
                int[] interior = field.getValue();
                if (interior.length == 0) {
                    continue;
                }
                int n = 0;
                for (int i : interior) {
                    if (clear[i]) {
                        n++;
                    }
                }
                double share = (double) n / interior.length;
                double[] values = new double[compared.length];
                for (int b = 0; b < compared.length; b++) {
                    values[b] = share >= MIN_CLEAR ? Percentiles.of(compared[b], interior, clear, 0.5) : Double.NaN;
                }
                rows.add(new Row(field.getKey(), date, satellite, share, values));
            }
        }
        System.out.printf(Locale.ROOT, "%d satellite-days at least %.0f%% clear over the area%n", kept, MIN_AREA_CLEAR * 100);
        write(data.resolve("landsat-" + year).resolve("landsat-series-" + year + ".csv"), rows);
        report(year, rows, readSentinel(data.resolve("series-" + year).resolve("series-" + year + ".csv")), interiorOf);
    }

    private static void addScene(LandsatGrid.Day day, StacItem item, PixelGrid area, RetryingHttp http,
                                 PlanetaryToken token) throws Exception {
        CogReader qaReader = open(item.asset("qa_pixel"), http, token);
        PixelGrid scene = qaReader.grid();
        if (scene.epsg() != area.epsg()) {
            System.err.println(item.id() + ": EPSG " + scene.epsg() + ", not the area's; left out");
            return;
        }
        PixelWindow window = LandsatGrid.windowFor(area, scene);
        if (window == null) {
            return;
        }
        int[] lookup = LandsatGrid.lookup(area, scene, window);
        int[] qa = qaReader.readWindow(window);
        boolean any = false;
        for (int k : lookup) {
            if (k >= 0 && QaPixel.clear(qa[k])) {
                any = true;
                break;
            }
        }
        if (!any) {
            day.add(lookup, qa, new float[BANDS.length][qa.length]);
            return;
        }
        float[][] values = new float[BANDS.length][];
        for (int b = 0; b < BANDS.length; b++) {
            StacAsset asset = item.asset(BANDS[b]);
            CogReader reader = open(asset, http, token);
            if (!reader.grid().equals(scene)) {
                throw new IOException(item.id() + " " + BANDS[b] + " is on another grid than qa_pixel");
            }
            int[] dn = reader.readWindow(window);
            float[] reflectance = new float[dn.length];
            for (int i = 0; i < dn.length; i++) {
                boolean missing = asset.nodata() != null && dn[i] == asset.nodata();
                reflectance[i] = missing ? Float.NaN : (float) (dn[i] * asset.scale() + asset.offset());
            }
            values[b] = reflectance;
        }
        day.add(lookup, qa, values);
    }

    private static CogReader open(StacAsset asset, RetryingHttp http, PlanetaryToken token) throws Exception {
        return CogReader.open(new HttpByteSource(URI.create(token.sign(asset.href(), Instant.now())), http));
    }

    private static void write(Path csv, List<Row> rows) throws IOException {
        Files.createDirectories(csv.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            out.write("field,date,satellite,clear_share,ndvi_median,b11,b12,nir,blue\n");
            for (Row r : rows) {
                StringBuilder line = new StringBuilder(r.field() + "," + r.date() + "," + r.satellite() + ","
                        + String.format(Locale.ROOT, "%.4f", r.clear()));
                for (double v : r.values()) {
                    line.append(',').append(Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v));
                }
                out.write(line.append('\n').toString());
            }
        }
    }

    /** field -> column -> date -> value, for Sentinel-2 rows with values. */
    private static Map<String, Map<String, Map<LocalDate, Double>>> readSentinel(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String> header = List.of(lines.get(0).split(","));
        Map<String, Map<String, Map<LocalDate, Double>>> series = new HashMap<>();
        for (String text : lines.subList(1, lines.size())) {
            String[] p = text.split(",", -1);
            for (String column : COMPARED) {
                String cell = p[header.indexOf(column)];
                if (!cell.isEmpty()) {
                    series.computeIfAbsent(p[0], k -> new HashMap<>()).computeIfAbsent(column, k -> new TreeMap<>())
                            .put(LocalDate.parse(p[1]), Double.parseDouble(cell));
                }
            }
        }
        return series;
    }

    private static void report(int year, List<Row> rows, Map<String, Map<String, Map<LocalDate, Double>>> sentinel,
                               Map<String, int[]> fields) {
        Map<String, Map<LocalDate, double[]>> landsat = new HashMap<>();
        for (Row r : rows) {
            if (!Double.isNaN(r.values()[0])) {
                // two satellites on one day: keep the first
                landsat.computeIfAbsent(r.field(), k -> new TreeMap<>()).putIfAbsent(r.date(), r.values());
            }
        }
        System.out.println("Agreement, Landsat against Sentinel-2 on the same fields at most "
                + MAX_DAYS_APART + " day apart:");
        System.out.println("band          pairs   median diff   median |diff|   slope   intercept");
        for (int b = 0; b < COMPARED.length; b++) {
            List<Double> l = new ArrayList<>();
            List<Double> s = new ArrayList<>();
            for (Map.Entry<String, Map<LocalDate, double[]>> field : landsat.entrySet()) {
                Map<LocalDate, Double> ls = new TreeMap<>();
                for (Map.Entry<LocalDate, double[]> e : field.getValue().entrySet()) {
                    ls.put(e.getKey(), e.getValue()[COMPARED_INDEX[b]]);
                }
                Map<LocalDate, Double> ss = sentinel.getOrDefault(field.getKey(), Map.of())
                        .getOrDefault(COMPARED[b], Map.of());
                for (double[] pair : Agreement.pairs(ls, ss, MAX_DAYS_APART)) {
                    l.add(pair[0]);
                    s.add(pair[1]);
                }
            }
            Agreement.Stats st = Agreement.of(l.stream().mapToDouble(Double::doubleValue).toArray(),
                    s.stream().mapToDouble(Double::doubleValue).toArray());
            System.out.printf(Locale.ROOT, "%-12s %6d   %+11.4f   %13.4f   %5.3f   %+9.4f%n", COMPARED[b], st.pairs(),
                    st.bias(), st.medianAbs(), st.slope(), st.intercept());
        }

        LocalDate from = LocalDate.of(year, 8, 1);
        LocalDate to = LocalDate.of(year, 9, 30);
        List<Long> alone = new ArrayList<>();
        List<Long> together = new ArrayList<>();
        List<Integer> sentinelDates = new ArrayList<>();
        List<Integer> landsatDates = new ArrayList<>();
        int within10Alone = 0;
        int within10Together = 0;
        int within15Alone = 0;
        int within15Together = 0;
        for (String field : fields.keySet()) {
            List<LocalDate> s = sentinel.getOrDefault(field, Map.of()).getOrDefault("ndvi_median", Map.of()).keySet()
                    .stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
            List<LocalDate> l = landsat.getOrDefault(field, Map.of()).keySet().stream()
                    .filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
            long a = Gaps.longest(s, from, to);
            long t = Gaps.longest(Gaps.merge(s, l), from, to);
            alone.add(a);
            together.add(t);
            sentinelDates.add(s.size());
            landsatDates.add(l.size());
            within10Alone += a <= 10 ? 1 : 0;
            within10Together += t <= 10 ? 1 : 0;
            within15Alone += a <= 15 ? 1 : 0;
            within15Together += t <= 15 ? 1 : 0;
        }
        System.out.printf(Locale.ROOT, "Harvest window 1 August to 30 September, %d fields (medians across fields):%n",
                fields.size());
        System.out.printf(Locale.ROOT, "  clear dates per field: Sentinel-2 %d, Landsat %d%n", median(sentinelDates),
                median(landsatDates));
        System.out.printf(Locale.ROOT, "  longest stretch without a clear date: Sentinel-2 alone %d days, with Landsat %d days%n",
                median(alone), median(together));
        System.out.printf(Locale.ROOT, "  fields never more than 10 days without a clear date: %d alone, %d with Landsat%n",
                within10Alone, within10Together);
        System.out.printf(Locale.ROOT, "  fields never more than 15 days without a clear date: %d alone, %d with Landsat%n",
                within15Alone, within15Together);
    }

    /** Where each compared Sentinel-2 column sits in a Landsat row's values. */
    private static final int[] COMPARED_INDEX = {0, 1, 2, 3, 4};

    private static <T extends Comparable<T>> T median(List<T> values) {
        List<T> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static List<String[]> readCsv(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String[]> rows = new ArrayList<>();
        for (String text : lines.subList(1, lines.size())) {
            if (!text.isBlank()) {
                rows.add(text.split(",", -1));
            }
        }
        return rows;
    }
}
