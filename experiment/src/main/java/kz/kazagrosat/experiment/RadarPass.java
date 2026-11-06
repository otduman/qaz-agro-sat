package kz.kazagrosat.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.HttpByteSource;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;

/** Measures radar backscatter over every field on every Sentinel-1 date, from Planetary Computer. */
final class RadarPass {

    /**
     * One field on one date.
     *
     * @param track the repeating orbit the image came from; each track looks from its own angle, so
     *              values should only be compared within a track
     * @param vv    mean backscatter in dB, averaged as power before converting
     * @param vh    the same for the cross-polarised channel
     */
    record Row(String field, LocalDate date, String scene, int track, double vv, double vh) {}

    private static final URI CATALOGUE = URI.create("https://planetarycomputer.microsoft.com/api/stac/v1");
    private static final URI TOKEN_URI =
            URI.create("https://planetarycomputer.microsoft.com/api/sas/v1/token/sentinel-1-rtc");
    private static final String COLLECTION = "sentinel-1-rtc";

    /** A Sentinel-1 satellite repeats its ground track every 175 orbits. */
    private static final int ORBITS_PER_CYCLE = 175;
    private static final int ORBIT_TOKEN = 6;
    private static final double MIN_VALID_SHARE = 0.9;

    private final RetryingHttp http = new RetryingHttp(RetryPolicy.DEFAULT);
    private final List<Field> fields;
    private final PixelGrid areaGrid;
    private final String token;

    RadarPass(List<Field> fields, PixelGrid areaGrid) throws IOException, InterruptedException {
        this.fields = fields;
        this.areaGrid = areaGrid;
        this.token = fetchToken();
    }

    static List<StacItem> scenes(Study.Season season) throws IOException, InterruptedException {
        List<StacItem> items = new ArrayList<>(new StacClient(CATALOGUE, RetryPolicy.DEFAULT)
                .search(new StacSearch(COLLECTION, Study.AREA, season.from(), season.to(), null)));
        items.sort(Comparator.comparing(StacItem::datetime));
        return items;
    }

    List<Row> measure(StacItem scene) throws IOException, InterruptedException {
        CogReader vv = open(scene, "vv");
        CogReader vh = open(scene, "vh");
        if (vv.grid().epsg() != areaGrid.epsg()) {
            throw new IOException("Expected EPSG:" + areaGrid.epsg() + " but " + scene.id()
                    + " uses EPSG:" + vv.grid().epsg());
        }
        int track = Integer.parseInt(scene.id().split("_")[ORBIT_TOKEN]) % ORBITS_PER_CYCLE;
        List<Row> rows = new ArrayList<>();
        for (Field field : fields) {
            PixelWindow window;
            try {
                window = vv.grid().windowCovering(field.extent(areaGrid));
            } catch (IllegalArgumentException outsideTheImage) {
                continue;
            }
            double vvDb = meanDb(vv.readFloatWindow(window));
            double vhDb = meanDb(vh.readFloatWindow(window));
            if (!Double.isNaN(vvDb) && !Double.isNaN(vhDb)) {
                rows.add(new Row(field.name(), OpticalPass.dateOf(scene), scene.id(), track, vvDb, vhDb));
            }
        }
        return rows;
    }

    private static double meanDb(float[] power) {
        double sum = 0;
        int count = 0;
        for (float value : power) {
            if (!Float.isNaN(value) && value > 0) {
                sum += value;
                count++;
            }
        }
        return count < power.length * MIN_VALID_SHARE ? Double.NaN : 10 * Math.log10(sum / count);
    }

    private CogReader open(StacItem scene, String band) throws IOException, InterruptedException {
        URI signed = URI.create(scene.asset(band).href() + "?" + token);
        return CogReader.open(new HttpByteSource(signed, http));
    }

    private String fetchToken() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(TOKEN_URI).timeout(Duration.ofSeconds(30)).GET().build());
        if (response.statusCode() != 200) {
            throw new IOException("Could not get a read token: HTTP " + response.statusCode());
        }
        return new ObjectMapper().readTree(response.body()).get("token").asText();
    }
}
