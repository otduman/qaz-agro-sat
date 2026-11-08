package kz.kazagrosat.pipeline.series;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.geo.LonLat;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.index.Ndvi;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.core.mask.SnowTest;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.core.series.FieldInteriors;
import kz.kazagrosat.core.series.Percentiles;
import kz.kazagrosat.core.series.Texture;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;
import kz.kazagrosat.pipeline.fields.FieldsGeoJson;
import kz.kazagrosat.pipeline.fields.StackImages;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;

/**
 * Builds a season's per-field series from its stack and the field map the {@code fields} command wrote
 * for the same season: one row per field (and fallow field) per stored date. The stack is read one
 * date at a time.
 *
 * <p>Each field is read over its interior, shrunk {@link #INTERIOR_METRES} from every edge. A pixel is
 * clear as in the field boundaries ({@link StackImages}): the cloud mask allows it, the snow test finds
 * no snow and the bands have values. The 20 m SWIR bands are repeated onto the 10 m grid; any 20 m pixel
 * that reaches the interior still lies at least 10 m inside the field.
 */
public final class SeriesBuilder {

    /** How far inside a field's edge its readings come from. */
    public static final double INTERIOR_METRES = 20;
    /** The share of a field's interior that must be clear on a date for that date to have values. */
    public static final double MIN_CLEAR_SHARE = 0.6;
    private static final double[] QUARTILES = {0.25, 0.5, 0.75};

    private SeriesBuilder() {}

    public static Series build(SeasonStack stack, Path fieldsFolder, Consumer<String> progress)
            throws IOException, InterruptedException {
        int year = stack.area().year();
        PixelGrid grid = stack.area().grid();
        PixelGrid maskGrid = stack.area().maskGrid();
        int[] labels = readLabels(fieldsFolder, year, grid);
        List<SeriesField> fields = listFields(fieldsFolder, year, labels, grid);

        int shrink = (int) Math.round(INTERIOR_METRES / grid.pixelSize());
        FieldInteriors interiors = FieldInteriors.of(labels, grid.width(), grid.height(), shrink);
        List<int[]> interiorOf = new ArrayList<>();
        boolean[] inInterior = new boolean[labels.length];
        for (SeriesField field : fields) {
            int[] pixels = interiors.pixelsOf(field.region());
            interiorOf.add(pixels);
            for (int i : pixels) {
                inInterior[i] = true;
            }
        }
        List<SeriesField> withInteriors = new ArrayList<>();
        double pixelHectares = grid.pixelSize() * grid.pixelSize() / 10_000;
        for (int f = 0; f < fields.size(); f++) {
            SeriesField field = fields.get(f);
            int interior = interiorOf.get(f).length;
            withInteriors.add(new SeriesField(field.id(), field.region(), field.land(), field.areaHectares(),
                    interior, interior * pixelHectares, field.centroidLat(), field.centroidLon(),
                    field.inSeries()));
        }

        List<LocalDate> dates = stack.entries().stream()
                .filter(e -> e.status() == StackEntry.Status.STORED)
                .map(StackEntry::date)
                .sorted()
                .toList();
        List<List<SeriesRow>> rowsOf = new ArrayList<>();
        for (int f = 0; f < fields.size(); f++) {
            rowsOf.add(new ArrayList<>());
        }
        for (int d = 0; d < dates.size(); d++) {
            LocalDate date = dates.get(d);
            progress.accept(String.format(Locale.ROOT, "Date %d of %d: %s", d + 1, dates.size(), date));
            DateBands bands = DateBands.read(stack, date, grid, maskGrid, inInterior);
            for (int f = 0; f < fields.size(); f++) {
                if (fields.get(f).inSeries()) {
                    rowsOf.get(f).add(row(fields.get(f).id(), date, interiorOf.get(f), bands, grid));
                }
            }
        }
        List<SeriesRow> rows = new ArrayList<>();
        rowsOf.forEach(rows::addAll);
        return new Series(year, stack.area().tile(), dates, withInteriors, rows);
    }

    private static SeriesRow row(String field, LocalDate date, int[] interior, DateBands b, PixelGrid grid) {
        double nan = Double.NaN;
        if (interior.length == 0) {
            return new SeriesRow(field, date, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan);
        }
        int clear = 0;
        int snow = 0;
        for (int i : interior) {
            if (b.clear[i]) {
                clear++;
            }
            if (b.snow[i]) {
                snow++;
            }
        }
        double clearShare = (double) clear / interior.length;
        double snowShare = (double) snow / interior.length;
        if (clearShare < MIN_CLEAR_SHARE) {
            return new SeriesRow(field, date, clearShare, nan, nan, nan, nan, nan, nan, nan, nan, nan, snowShare,
                    nan, nan, nan);
        }
        double[] ndvi = Percentiles.of(b.ndvi, interior, b.clear, QUARTILES);
        Texture.Measures texture = Texture.of(b.brightness, b.usable, interior, grid.width(), grid.height());
        return new SeriesRow(field, date, clearShare, ndvi[0], ndvi[1], ndvi[2],
                median(b.swir16, interior, b.clear), median(b.swir22, interior, b.clear),
                median(b.ndti, interior, b.clear), median(b.nir, interior, b.clear),
                median(b.brightness, interior, b.clear), median(b.blue, interior, b.clear), snowShare,
                texture.roughness(), texture.lineStrength(), texture.lineDirection());
    }

    private static double median(float[] values, int[] pixels, boolean[] use) {
        return Percentiles.of(values, pixels, use, 0.5);
    }

    /** One date's bands on the 10 m grid, with its clear and snow flags. */
    private record DateBands(float[] blue, float[] nir, float[] swir16, float[] swir22, float[] ndvi, float[] ndti,
                             float[] brightness, boolean[] clear, boolean[] snow, boolean[] usable) {

        static DateBands read(SeasonStack stack, LocalDate date, PixelGrid grid, PixelGrid maskGrid,
                              boolean[] inInterior) throws IOException, InterruptedException {
            float[] blue = stack.reflectance(date, Layer.BLUE);
            float[] green = stack.reflectance(date, Layer.GREEN);
            float[] red = stack.reflectance(date, Layer.RED);
            float[] nir = stack.reflectance(date, Layer.NIR);
            float[] swir16 = StackImages.toBandGrid(stack.reflectance(date, Layer.SWIR16), maskGrid, grid);
            float[] swir22 = StackImages.toBandGrid(stack.reflectance(date, Layer.SWIR22), maskGrid, grid);
            int[] classes = StackImages.classesOnBandGrid(stack.raw(date, Layer.SCL), maskGrid, grid);
            boolean[] snowTest = SnowTest.snow(green, nir, swir16);
            float[] brightness = StackImages.brightness(blue, green, red);
            boolean[] usableByMask = SclMask.usable(classes);
            boolean[] clear = StackImages.clear(usableByMask, snowTest, brightness, nir);

            boolean[] snow = new boolean[classes.length];
            boolean[] usable = new boolean[classes.length];
            float[] ndti = new float[classes.length];
            for (int i = 0; i < classes.length; i++) {
                // the mask's own snow, plus snow the mask took for clear ground; ice cloud also passes the
                // snow test, so it doesn't count where the mask already calls it cloud
                snow[i] = classes[i] == SclMask.SNOW || (snowTest[i] && usableByMask[i]);
                usable[i] = clear[i] && inInterior[i];
                ndti[i] = (swir16[i] - swir22[i]) / (swir16[i] + swir22[i]);
            }
            return new DateBands(blue, nir, swir16, swir22, Ndvi.compute(red, nir), ndti, brightness, clear, snow,
                    usable);
        }
    }

    private static int[] readLabels(Path folder, int year, PixelGrid grid) throws IOException, InterruptedException {
        Path file = folder.resolve("fields-" + year + ".tif");
        if (!Files.isRegularFile(file)) {
            String found = otherSeasons(folder);
            throw new IOException("No fields-" + year + ".tif in " + folder + (found.isEmpty() ? "" :
                    "; it holds fields for " + found + ", not " + year)
                    + ". Run the fields command on the " + year + " stack first.");
        }
        CogReader reader = CogReader.open(new FileByteSource(file));
        if (!reader.grid().equals(grid)) {
            throw new IllegalArgumentException("The fields in " + file + " are on the grid " + reader.grid()
                    + ", but the stack is on the grid " + grid + ". Use fields found from this stack.");
        }
        return reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));
    }

    /** The seasons whose label files are in the folder, such as {@code 2024}, or empty. */
    private static String otherSeasons(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return "";
        }
        List<String> years = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "fields-*.tif")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                years.add(name.substring("fields-".length(), name.length() - ".tif".length()));
            }
        }
        years.sort(null);
        return String.join(", ", years);
    }

    /** Every region in label order, with its land label from the GeoJSON and its area and centre from the labels. */
    private static List<SeriesField> listFields(Path folder, int year, int[] labels, PixelGrid grid)
            throws IOException {
        int regions = 0;
        for (int label : labels) {
            regions = Math.max(regions, label);
        }
        long[] pixels = new long[regions + 1];
        double[] sumX = new double[regions + 1];
        double[] sumY = new double[regions + 1];
        for (int i = 0; i < labels.length; i++) {
            int label = labels[i];
            if (label > 0) {
                pixels[label]++;
                sumX[label] += i % grid.width();
                sumY[label] += i / grid.width();
            }
        }

        Path geojson = folder.resolve("fields-" + year + ".geojson");
        if (!Files.isRegularFile(geojson)) {
            throw new IOException("No fields-" + year + ".geojson in " + folder
                    + ". Run the fields command on the " + year + " stack first.");
        }
        LandLabel[] land = new LandLabel[regions + 1];
        for (JsonNode feature : new ObjectMapper().readTree(geojson.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            String id = properties.path("id").asText();
            int region = regionOf(id, year, geojson);
            if (region < 1 || region > regions) {
                throw new IOException(geojson + " lists " + id + ", which the label file doesn't have");
            }
            land[region] = LandLabel.valueOf(properties.path("land").asText().toUpperCase(Locale.ROOT));
        }

        UtmProjection utm = UtmProjection.forEpsg(grid.epsg());
        double pixelHectares = grid.pixelSize() * grid.pixelSize() / 10_000;
        List<SeriesField> fields = new ArrayList<>();
        for (int region = 1; region <= regions; region++) {
            if (land[region] == null) {
                throw new IOException(geojson + " has no region " + FieldsGeoJson.id(year, region)
                        + ", which the label file has. Run the fields command again.");
            }
            double lat = Double.NaN;
            double lon = Double.NaN;
            if (pixels[region] > 0) {
                double easting = grid.originX() + (sumX[region] / pixels[region] + 0.5) * grid.pixelSize();
                double northing = grid.originY() - (sumY[region] / pixels[region] + 0.5) * grid.pixelSize();
                LonLat centre = utm.unproject(easting, northing);
                lat = centre.latitude();
                lon = centre.longitude();
            }
            boolean inSeries = land[region] == LandLabel.FIELD || land[region] == LandLabel.FALLOW;
            fields.add(new SeriesField(FieldsGeoJson.id(year, region), region, land[region],
                    pixels[region] * pixelHectares, 0, 0, lat, lon, inSeries));
        }
        return fields;
    }

    private static int regionOf(String id, int year, Path geojson) throws IOException {
        String prefix = year + "-F";
        if (!id.startsWith(prefix)) {
            throw new IOException(geojson + " holds " + id + ", which isn't a region of " + year);
        }
        try {
            return Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IOException(geojson + " holds an id " + id + " that has no region number", e);
        }
    }
}
