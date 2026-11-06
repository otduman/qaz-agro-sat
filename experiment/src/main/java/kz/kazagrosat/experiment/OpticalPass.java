package kz.kazagrosat.experiment;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.core.raster.Reflectance;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.HttpByteSource;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.stac.SceneSelector;
import kz.kazagrosat.pipeline.stac.StacAsset;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;

/** Measures every field on every usable Sentinel-2 date, and saves a small true-colour photo of each. */
final class OpticalPass {

    /** One field on one date. Band values are median reflectance over the clear pixels. */
    record Row(String field, LocalDate date, String scene, double clear, double bare, double ndvi, double ndti,
               double blue, double green, double red, double nir, double swir16, double swir22) {}

    private static final String COLLECTION = "sentinel-2-c1-l2a";
    private static final double MAX_TILE_CLOUD = 80;
    private static final int BARE_SOIL_CLASS = 5;

    /** How much surrounding land each photo shows beyond the box, in 10 m pixels. */
    private static final int PHOTO_MARGIN = 40;
    private static final double PHOTO_MIN_CLEAR = 0.9;
    private static final double PHOTO_WHITE_POINT = 0.32;

    private final RetryingHttp http = new RetryingHttp(RetryPolicy.DEFAULT);
    private final Study.Season season;
    private final List<Field> fields;
    private final Path photoFolder;

    OpticalPass(Study.Season season, List<Field> fields, Path photoFolder) {
        this.season = season;
        this.fields = fields;
        this.photoFolder = photoFolder;
    }

    /** The least cloudy scene of each date that fully covers the study area. */
    static List<StacItem> scenes(Study.Season season) throws IOException, InterruptedException {
        List<StacItem> found = new StacClient(StacClient.EARTH_SEARCH, RetryPolicy.DEFAULT)
                .search(new StacSearch(COLLECTION, Study.AREA, season.from(), season.to(), MAX_TILE_CLOUD));
        Map<LocalDate, StacItem> byDate = new TreeMap<>();
        found.stream()
                .filter(scene -> scene.id().contains(Study.TILE))
                .filter(scene -> SceneSelector.covers(scene, Study.AREA))
                .sorted(Comparator.comparingDouble(StacItem::cloudCover).reversed())
                .forEach(scene -> byDate.put(dateOf(scene), scene));
        return new ArrayList<>(byDate.values());
    }

    static LocalDate dateOf(StacItem item) {
        return item.datetime().atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** The study area's 10 m grid, the frame that field boxes are counted in. */
    PixelGrid areaGrid(StacItem scene) throws IOException, InterruptedException {
        CogReader red = open(scene.asset("red"));
        CogReader scl = open(scene.asset("scl"));
        return red.grid().subGrid(maskWindow(scl).scaled(factor(red, scl)));
    }

    List<Row> measure(StacItem scene) throws IOException, InterruptedException {
        CogReader scl = open(scene.asset("scl"));
        CogReader nirReader = open(scene.asset("nir"));
        int factor = factor(nirReader, scl);
        PixelWindow maskWindow = maskWindow(scl);
        PixelWindow bandWindow = maskWindow.scaled(factor);

        int[] classes = scl.readWindow(maskWindow);
        boolean[] clear = SclMask.usable(classes);
        float[] red = reflectance(scene, "red", bandWindow);
        float[] green = reflectance(scene, "green", bandWindow);
        float[] blue = reflectance(scene, "blue", bandWindow);
        float[] nir = reflectance(scene, "nir", bandWindow);
        float[] swir16 = reflectance(scene, "swir16", maskWindow);
        float[] swir22 = reflectance(scene, "swir22", maskWindow);

        List<Row> rows = new ArrayList<>();
        LocalDate date = dateOf(scene);
        for (Field field : fields) {
            Row row = measureField(field, scene, date, factor, maskWindow.width(), bandWindow.width(), clear, classes,
                    red, green, blue, nir, swir16, swir22);
            rows.add(row);
            if (row.clear() >= PHOTO_MIN_CLEAR && !date.isBefore(season.chartFrom()) && !date.isAfter(season.photosTo())) {
                savePhoto(field, date, red, green, blue, bandWindow.width(), bandWindow.height());
            }
        }
        return rows;
    }

    private static Row measureField(
            Field field, StacItem scene, LocalDate date, int factor, int maskWidth, int bandWidth,
            boolean[] clear, int[] classes, float[] red, float[] green, float[] blue, float[] nir,
            float[] swir16, float[] swir22) {
        int maskLeft = field.left() / factor;
        int maskTop = field.top() / factor;
        int maskRight = field.right() / factor;
        int maskBottom = field.bottom() / factor;
        int maskPixels = (maskRight - maskLeft) * (maskBottom - maskTop);
        int bandPixels = maskPixels * factor * factor;

        float[] ndvi = new float[bandPixels];
        float[] blues = new float[bandPixels];
        float[] greens = new float[bandPixels];
        float[] reds = new float[bandPixels];
        float[] nirs = new float[bandPixels];
        float[] ndti = new float[maskPixels];
        float[] swir16s = new float[maskPixels];
        float[] swir22s = new float[maskPixels];
        int clearCount = 0;
        int bareCount = 0;
        int bandCount = 0;

        for (int y = maskTop; y < maskBottom; y++) {
            for (int x = maskLeft; x < maskRight; x++) {
                int mask = y * maskWidth + x;
                if (!clear[mask] || Float.isNaN(swir16[mask]) || Float.isNaN(swir22[mask])) {
                    continue;
                }
                ndti[clearCount] = (swir16[mask] - swir22[mask]) / (swir16[mask] + swir22[mask]);
                swir16s[clearCount] = swir16[mask];
                swir22s[clearCount] = swir22[mask];
                clearCount++;
                if (classes[mask] == BARE_SOIL_CLASS) {
                    bareCount++;
                }
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        int band = (y * factor + dy) * bandWidth + x * factor + dx;
                        ndvi[bandCount] = (nir[band] - red[band]) / (nir[band] + red[band]);
                        blues[bandCount] = blue[band];
                        greens[bandCount] = green[band];
                        reds[bandCount] = red[band];
                        nirs[bandCount] = nir[band];
                        bandCount++;
                    }
                }
            }
        }
        return new Row(field.name(), date, scene.id(),
                (double) clearCount / maskPixels,
                clearCount == 0 ? Double.NaN : (double) bareCount / clearCount,
                Study.median(ndvi, bandCount),
                Study.median(ndti, clearCount),
                Study.median(blues, bandCount),
                Study.median(greens, bandCount),
                Study.median(reds, bandCount),
                Study.median(nirs, bandCount),
                Study.median(swir16s, clearCount),
                Study.median(swir22s, clearCount));
    }

    private void savePhoto(Field field, LocalDate date, float[] red, float[] green, float[] blue,
                           int width, int height) throws IOException {
        int left = Math.max(0, field.left() - PHOTO_MARGIN);
        int top = Math.max(0, field.top() - PHOTO_MARGIN);
        int right = Math.min(width, field.right() + PHOTO_MARGIN);
        int bottom = Math.min(height, field.bottom() + PHOTO_MARGIN);
        BufferedImage photo = new BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_RGB);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int i = y * width + x;
                photo.setRGB(x - left, y - top, stretch(red[i]) << 16 | stretch(green[i]) << 8 | stretch(blue[i]));
            }
        }
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.drawRect(field.left() - left, field.top() - top,
                field.right() - field.left() - 1, field.bottom() - field.top() - 1);
        g.dispose();
        Files.createDirectories(photoFolder);
        ImageIO.write(photo, "png", photoFolder.resolve(field.name() + "_" + date + ".png").toFile());
    }

    private static int stretch(float reflectance) {
        double value = Math.clamp(reflectance / PHOTO_WHITE_POINT, 0.0, 1.0);
        return (int) Math.round(Math.pow(value, 0.8) * 255);
    }

    private static PixelWindow maskWindow(CogReader scl) {
        UtmProjection projection = UtmProjection.forEpsg(scl.grid().epsg());
        return scl.grid().windowCovering(projection.project(Study.AREA));
    }

    private static int factor(CogReader band, CogReader mask) {
        return (int) Math.round(mask.grid().pixelSize() / band.grid().pixelSize());
    }

    private float[] reflectance(StacItem scene, String band, PixelWindow window)
            throws IOException, InterruptedException {
        StacAsset asset = scene.asset(band);
        return Reflectance.fromDigitalNumbers(
                open(asset).readWindow(window), asset.scale(), asset.offset(), asset.nodata());
    }

    private CogReader open(StacAsset asset) throws IOException, InterruptedException {
        return CogReader.open(new HttpByteSource(URI.create(asset.href()), http));
    }
}
