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
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;
import kz.kazagrosat.pipeline.stack.StackArea;

/**
 * The HLS test: do NASA's Harmonized Landsat Sentinel-2 products (v2.0, 30 m, free from Planetary Computer with no
 * account) read our fields the same way from both satellites? Run from the repository root after the
 * {@code series} command: {@code HlsCheck <dataFolder> [<year>...]}.
 *
 * <p>Each field is read as step 3 and the Landsat test read it: 20 m inside its edge, values only when 60% of that is
 * clear (by HLS's own Fmask plus our snow test), medians. Writes {@code <data>/hls-<year>/hls-series-<year>.csv} and
 * prints three comparisons on the same fields at most a day apart: HLS Landsat against HLS Sentinel-2, HLS
 * Sentinel-2 against our Sentinel-2 series, and HLS Landsat against our series; then the harvest-window gaps.
 */
public final class HlsCheck {

    static final URI STAC = URI.create("https://planetarycomputer.microsoft.com/api/stac/v1");
    static final String TOKENS = "https://planetarycomputer.microsoft.com/api/sas/v1/token/";
    static final String S30 = "hls2-s30";
    static final String L30 = "hls2-l30";
    /** Red, near-infrared, SWIR 1.6, SWIR 2.2, blue, green; in the order {@link LandsatGrid.Day} keeps them. */
    static final Map<String, String[]> BANDS = Map.of(
            S30, new String[] {"B04", "B8A", "B11", "B12", "B02", "B03"},
            L30, new String[] {"B04", "B05", "B06", "B07", "B02", "B03"});
    static final int RED = 0;
    static final int NIR = 1;
    static final int SWIR16 = 2;
    static final int SWIR22 = 3;
    static final int BLUE = 4;
    static final int GREEN = 5;
    static final double SCALE = 0.0001;
    static final int NODATA = -9999;
    static final int SHRINK = 2;
    static final double MIN_CLEAR = 0.6;
    static final double MIN_AREA_CLEAR = 0.01;
    static final int MAX_DAYS_APART = 1;
    static final String[] COMPARED = {"ndvi_median", "b11", "b12", "nir", "blue"};

    private record Row(String field, LocalDate date, String product, double clear, double[] values) {}

    private HlsCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HlsCheck <dataFolder> [<year>...]");
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
        Map<String, PlanetaryToken> tokens = Map.of(
                S30, new PlanetaryToken(() -> fetchToken(http, S30)),
                L30, new PlanetaryToken(() -> fetchToken(http, L30)));
        for (int year : years) {
            season(data, year, http, tokens);
        }
    }

    private static PlanetaryToken.Token fetchToken(RetryingHttp http, String collection)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(TOKENS + collection))
                .timeout(Duration.ofSeconds(30)).GET().build());
        if (response.statusCode() != 200) {
            throw new IOException("Token request for " + collection + " failed: HTTP " + response.statusCode());
        }
        JsonNode json = new ObjectMapper().readTree(response.body());
        return new PlanetaryToken.Token(json.get("token").asText(), Instant.parse(json.get("msft:expiry").asText()));
    }

    private static void season(Path data, int year, RetryingHttp http, Map<String, PlanetaryToken> tokens)
            throws Exception {
        StackArea area = StackArea.read(data.resolve("season-" + year))
                .orElseThrow(() -> new IOException("No season stack for " + year));
        PixelGrid grid = area.grid();
        CogReader labelReader = CogReader.open(new FileByteSource(
                data.resolve("fields-" + year).resolve("fields-" + year + ".tif")));
        int[] labels = labelReader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
        FieldInteriors interiors = FieldInteriors.of(labels, grid.width(), grid.height(), SHRINK);
        Map<String, int[]> interiorOf = new LinkedHashMap<>();
        for (String[] f : readCsv(data.resolve("series-" + year).resolve("fields-" + year + ".csv"))) {
            if (f[6].equals("yes")) {
                int region = Integer.parseInt(f[0].substring(f[0].indexOf("-F") + 2));
                interiorOf.put(f[0], interiors.pixelsOf(region));
            }
        }

        StacClient stac = new StacClient(STAC, RetryPolicy.DEFAULT);
        Map<String, List<StacItem>> days = new TreeMap<>();
        for (String product : List.of(S30, L30)) {
            List<StacItem> items = stac.search(new StacSearch(product, area.bbox(),
                    LocalDate.of(year, 5, 1), LocalDate.of(year, 10, 31), null));
            for (StacItem item : items) {
                LocalDate date = item.datetime().atZone(ZoneOffset.UTC).toLocalDate();
                days.computeIfAbsent(date + " " + product, k -> new ArrayList<>()).add(item);
            }
            System.out.printf(Locale.ROOT, "%n%d: %d %s scenes%n", year, items.size(), product);
        }

        List<Row> rows = new ArrayList<>();
        Map<String, Integer> kept = new TreeMap<>();
        for (Map.Entry<String, List<StacItem>> entry : days.entrySet()) {
            LocalDate date = LocalDate.parse(entry.getKey().substring(0, 10));
            String product = entry.getKey().substring(11);
            LandsatGrid.Day day = new LandsatGrid.Day(grid.width() * grid.height(), 6);
            for (StacItem item : entry.getValue()) {
                addScene(day, item, product, grid, http, tokens.get(product));
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
            System.err.printf(Locale.ROOT, "%s %s: %d scenes, area %.0f%% clear%n", date, product, day.scenes(),
                    areaClear * 100);
            if (areaClear < MIN_AREA_CLEAR) {
                continue;
            }
            kept.merge(product, 1, Integer::sum);
            float[] red = day.band(RED);
            float[] nir = day.band(NIR);
            float[] ndvi = new float[clear.length];
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
                rows.add(new Row(field.getKey(), date, product, share, values));
            }
        }
        System.out.printf(Locale.ROOT, "Days at least %.0f%% clear over the area: %s%n", MIN_AREA_CLEAR * 100, kept);
        write(data.resolve("hls-" + year).resolve("hls-series-" + year + ".csv"), rows);

        Map<String, Map<String, Map<LocalDate, Double>>> ours = readSentinel(
                data.resolve("series-" + year).resolve("series-" + year + ".csv"));
        Map<String, Map<String, Map<LocalDate, Double>>> s30 = byField(rows, S30);
        Map<String, Map<String, Map<LocalDate, Double>>> l30 = byField(rows, L30);
        agreement("HLS Landsat (L30) against HLS Sentinel-2 (S30)", l30, s30);
        agreement("HLS Sentinel-2 (S30) against our Sentinel-2 series", s30, ours);
        agreement("HLS Landsat (L30) against our Sentinel-2 series", l30, ours);
        gaps(year, interiorOf.keySet(), ours, s30, l30);
    }

    private static void addScene(LandsatGrid.Day day, StacItem item, String product, PixelGrid area,
                                 RetryingHttp http, PlanetaryToken token) throws Exception {
        CogReader maskReader = open(item.asset("Fmask").href(), http, token);
        PixelGrid scene = maskReader.grid();
        if (scene.epsg() != area.epsg()) {
            System.err.println(item.id() + ": EPSG " + scene.epsg() + ", not the area's; left out");
            return;
        }
        PixelWindow window = LandsatGrid.windowFor(area, scene);
        if (window == null) {
            return;
        }
        int[] lookup = LandsatGrid.lookup(area, scene, window);
        int[] fmask = maskReader.readWindow(window);
        boolean any = false;
        for (int k : lookup) {
            if (k >= 0 && HlsFmask.clear(fmask[k])) {
                any = true;
                break;
            }
        }
        String[] bands = BANDS.get(product);
        if (!any) {
            day.add(lookup, fmask, new float[bands.length][fmask.length], HlsFmask::clear);
            return;
        }
        float[][] values = new float[bands.length][];
        for (int b = 0; b < bands.length; b++) {
            CogReader reader = open(item.asset(bands[b]).href(), http, token);
            if (!reader.grid().equals(scene)) {
                throw new IOException(item.id() + " " + bands[b] + " is on another grid than Fmask");
            }
            int[] dn = reader.readWindow(window);
            float[] reflectance = new float[dn.length];
            for (int i = 0; i < dn.length; i++) {
                reflectance[i] = dn[i] == NODATA ? Float.NaN : (float) (dn[i] * SCALE);
            }
            values[b] = reflectance;
        }
        day.add(lookup, fmask, values, HlsFmask::clear);
    }

    private static CogReader open(String href, RetryingHttp http, PlanetaryToken token) throws Exception {
        return CogReader.open(new HttpByteSource(URI.create(token.sign(href, Instant.now())), http));
    }

    private static void write(Path csv, List<Row> rows) throws IOException {
        Files.createDirectories(csv.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            out.write("field,date,product,clear_share,ndvi_median,b11,b12,nir,blue\n");
            for (Row r : rows) {
                StringBuilder line = new StringBuilder(r.field() + "," + r.date() + "," + r.product() + ","
                        + String.format(Locale.ROOT, "%.4f", r.clear()));
                for (double v : r.values()) {
                    line.append(',').append(Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v));
                }
                out.write(line.append('\n').toString());
            }
        }
    }

    /** field -> column -> date -> value, for one product's rows with values. */
    private static Map<String, Map<String, Map<LocalDate, Double>>> byField(List<Row> rows, String product) {
        Map<String, Map<String, Map<LocalDate, Double>>> series = new HashMap<>();
        for (Row r : rows) {
            if (!r.product().equals(product) || Double.isNaN(r.values()[0])) {
                continue;
            }
            for (int b = 0; b < COMPARED.length; b++) {
                series.computeIfAbsent(r.field(), k -> new HashMap<>()).computeIfAbsent(COMPARED[b], k -> new TreeMap<>())
                        .putIfAbsent(r.date(), r.values()[b]);
            }
        }
        return series;
    }

    /** field -> column -> date -> value, for our Sentinel-2 rows with values. */
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

    private static void agreement(String title, Map<String, Map<String, Map<LocalDate, Double>>> first,
                                  Map<String, Map<String, Map<LocalDate, Double>>> second) {
        System.out.println(title + ", same fields at most " + MAX_DAYS_APART + " day apart (first minus second):");
        System.out.println("band          pairs   median diff   median |diff|   slope   intercept");
        for (String column : COMPARED) {
            List<Double> a = new ArrayList<>();
            List<Double> b = new ArrayList<>();
            for (Map.Entry<String, Map<String, Map<LocalDate, Double>>> field : first.entrySet()) {
                Map<LocalDate, Double> x = field.getValue().getOrDefault(column, Map.of());
                Map<LocalDate, Double> y = second.getOrDefault(field.getKey(), Map.of()).getOrDefault(column, Map.of());
                for (double[] pair : Agreement.pairs(x, y, MAX_DAYS_APART)) {
                    a.add(pair[0]);
                    b.add(pair[1]);
                }
            }
            Agreement.Stats st = Agreement.of(a.stream().mapToDouble(Double::doubleValue).toArray(),
                    b.stream().mapToDouble(Double::doubleValue).toArray());
            System.out.printf(Locale.ROOT, "%-12s %6d   %+11.4f   %13.4f   %5.3f   %+9.4f%n", column, st.pairs(),
                    st.bias(), st.medianAbs(), st.slope(), st.intercept());
        }
    }

    private static void gaps(int year, Iterable<String> fields, Map<String, Map<String, Map<LocalDate, Double>>> ours,
                             Map<String, Map<String, Map<LocalDate, Double>>> s30,
                             Map<String, Map<String, Map<LocalDate, Double>>> l30) {
        LocalDate from = LocalDate.of(year, 8, 1);
        LocalDate to = LocalDate.of(year, 9, 30);
        List<Long> oursAlone = new ArrayList<>();
        List<Long> hlsBoth = new ArrayList<>();
        List<Long> oursWithL30 = new ArrayList<>();
        int[] within15 = new int[3];
        int count = 0;
        for (String field : fields) {
            List<LocalDate> o = dates(ours, field, from, to);
            List<LocalDate> s = dates(s30, field, from, to);
            List<LocalDate> l = dates(l30, field, from, to);
            long a = Gaps.longest(o, from, to);
            long h = Gaps.longest(Gaps.merge(s, l), from, to);
            long w = Gaps.longest(Gaps.merge(o, l), from, to);
            oursAlone.add(a);
            hlsBoth.add(h);
            oursWithL30.add(w);
            within15[0] += a <= 15 ? 1 : 0;
            within15[1] += h <= 15 ? 1 : 0;
            within15[2] += w <= 15 ? 1 : 0;
            count++;
        }
        System.out.printf(Locale.ROOT, "Harvest window 1 August to 30 September, %d fields: longest stretch without a "
                + "clear date (median across fields) and fields never over 15 days without one%n", count);
        System.out.printf(Locale.ROOT, "  our Sentinel-2 alone:        %d days, %d fields%n", median(oursAlone), within15[0]);
        System.out.printf(Locale.ROOT, "  HLS, both satellites:        %d days, %d fields%n", median(hlsBoth), within15[1]);
        System.out.printf(Locale.ROOT, "  our Sentinel-2 + HLS Landsat: %d days, %d fields%n", median(oursWithL30),
                within15[2]);
    }

    private static List<LocalDate> dates(Map<String, Map<String, Map<LocalDate, Double>>> series, String field,
                                         LocalDate from, LocalDate to) {
        return series.getOrDefault(field, Map.of()).getOrDefault("ndvi_median", Map.of()).keySet().stream()
                .filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
    }

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
