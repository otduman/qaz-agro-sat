package kz.kazagrosat.pipeline.fields;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.fields.FieldMap;
import kz.kazagrosat.core.fields.FieldParameters;
import kz.kazagrosat.core.fields.FieldSummary;
import kz.kazagrosat.core.fields.LandLabel;
import kz.kazagrosat.core.fields.Region;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.pipeline.stack.SeasonStack;
import kz.kazagrosat.pipeline.stack.StackEntry;
import org.locationtech.jts.geom.Polygon;

/**
 * Writes a season's field map into a folder:
 * <ul>
 *   <li>{@code fields-<year>.geojson}, the outlines with their properties;</li>
 *   <li>{@code fields-<year>.tif}, the region id of every pixel (16-bit, 0 = no region);</li>
 *   <li>{@code edges-<year>.png}, the season edge;</li>
 *   <li>{@code outlines-mid-season-<date>.png} and {@code outlines-post-harvest-<date>.png};</li>
 *   <li>{@code summary-<year>.txt}, every count of what was found and what was left out.</li>
 * </ul>
 * They replace the old files all together through {@link OutputSwap}: if any old file is held open by
 * another program, none is replaced.
 */
public final class FieldOutputs {

    /** The most regions a 16-bit label file can number. */
    static final int MAX_REGIONS = 65_535;
    static final MonthDay MID_SEASON_FROM = MonthDay.of(7, 1);
    static final MonthDay MID_SEASON_TO = MonthDay.of(8, 15);
    static final MonthDay POST_HARVEST_FROM = MonthDay.of(9, 10);
    static final MonthDay POST_HARVEST_TO = MonthDay.of(10, 15);

    private FieldOutputs() {}

    /** @return the files written, in the order listed above */
    public static List<Path> write(SeasonStack stack, FieldMap map, Path folder)
            throws IOException, InterruptedException {
        if (map.regions().size() > MAX_REGIONS) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Found %,d regions, more than the %,d a 16-bit label file can hold. Use a smaller area.",
                    map.regions().size(), MAX_REGIONS));
        }
        int year = stack.area().year();
        PixelGrid grid = map.grid();
        FieldParameters p = FieldParameters.DEFAULTS;
        Optional<LocalDate> midSeason = FieldPictures.clearestIn(stack.entries(), MID_SEASON_FROM, MID_SEASON_TO);
        Optional<LocalDate> postHarvest =
                FieldPictures.clearestIn(stack.entries(), POST_HARVEST_FROM, POST_HARVEST_TO);

        try (OutputSwap swap = new OutputSwap(folder)) {
            Path geojson = swap.part("fields-" + year + ".geojson");
            Map<Integer, Polygon> outlines =
                    RegionOutlines.simplify(RegionOutlines.trace(map.labels(), grid), p.simplifyMetres());
            FieldsGeoJson.write(geojson, map, outlines, year);
            TiledTiffWriter.write(swap.part("fields-" + year + ".tif"), map.labels(), grid, 16, 0);
            FieldPictures.edges(swap.part("edges-" + year + ".png"), map.seasonEdges(), grid.width(),
                    grid.height());
            if (midSeason.isPresent()) {
                FieldPictures.preview(swap.part("outlines-mid-season-" + midSeason.get() + ".png"),
                        stack, midSeason.get(), map);
            }
            if (postHarvest.isPresent()) {
                FieldPictures.preview(swap.part("outlines-post-harvest-" + postHarvest.get() + ".png"),
                        stack, postHarvest.get(), map);
            }
            Files.writeString(swap.part("summary-" + year + ".txt"),
                    summary(stack, map, p, midSeason, postHarvest), StandardCharsets.UTF_8);

            List<Path> written = swap.commit();
            removeStalePictures(folder, Set.copyOf(written));
            return written;
        }
    }

    /** Outline pictures are named by date; a run that picked other dates leaves none of the old ones. */
    private static void removeStalePictures(Path folder, Set<Path> written) throws IOException {
        try (DirectoryStream<Path> pictures = Files.newDirectoryStream(folder, "outlines-*.png")) {
            for (Path picture : pictures) {
                String name = picture.getFileName().toString();
                boolean ours = name.startsWith("outlines-mid-season-") || name.startsWith("outlines-post-harvest-");
                if (ours && !written.contains(picture)) {
                    Files.delete(picture);
                }
            }
        }
    }

    static String summary(SeasonStack stack, FieldMap map, FieldParameters p, Optional<LocalDate> midSeason,
                          Optional<LocalDate> postHarvest) {
        FieldSummary s = map.summary();
        double hectaresPerPixel = map.grid().pixelSize() * map.grid().pixelSize() / 10_000;
        List<LocalDate> cloudy = stack.entries().stream()
                .filter(e -> e.status() == StackEntry.Status.CLOUDY).map(StackEntry::date).sorted().toList();
        Map<LandLabel, Integer> count = new LinkedHashMap<>();
        Map<LandLabel, Long> pixels = new LinkedHashMap<>();
        for (LandLabel land : LandLabel.values()) {
            count.put(land, 0);
            pixels.put(land, 0L);
        }
        for (Region region : map.regions()) {
            count.merge(region.land(), 1, Integer::sum);
            pixels.merge(region.land(), (long) region.pixels(), Long::sum);
        }

        StringBuilder text = new StringBuilder();
        line(text, "Season: %d, tile %s", stack.area().year(), stack.area().tile());
        line(text, "Dates used: %d (%s)", s.dates().size(), join(s.dates()));
        line(text, "Cloudy dates skipped: %d (%s)", cloudy.size(), join(cloudy));
        line(text, "Regions found by the watershed: %d", s.watershedRegions());
        line(text, "Merges of neighbouring regions: %d", s.merges());
        line(text, "Regions dropped as under %.1f ha: %d, leaving out %.2f ha", p.minRegionHectares(),
                s.droppedRegions(), s.droppedPixels() * hectaresPerPixel);
        line(text, "Holes under %.1f ha filled: %d, %.2f ha", p.maxHoleHectares(), s.filledHoles(),
                s.filledPixels() * hectaresPerPixel);
        line(text, "Holes kept: %d", s.keptHoles());
        line(text, "Pixels with too few clear dates, or cut off by them: %d, leaving out %.2f ha", s.noDataPixels(),
                s.noDataPixels() * hectaresPerPixel);
        line(text, "Regions: %d (field %d, fallow %d, other %d)", map.regions().size(), count.get(LandLabel.FIELD),
                count.get(LandLabel.FALLOW), count.get(LandLabel.OTHER));
        line(text, "Hectares: field %.2f, fallow %.2f, other %.2f", pixels.get(LandLabel.FIELD) * hectaresPerPixel,
                pixels.get(LandLabel.FALLOW) * hectaresPerPixel, pixels.get(LandLabel.OTHER) * hectaresPerPixel);
        line(text, "Labelled other for lack of data: %s", s.otherReasons().isEmpty() ? "none"
                : s.otherReasons().entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining(", ")));
        line(text, "Mid-season picture: %s", midSeason.map(LocalDate::toString)
                .orElse("none, no stored date from " + MID_SEASON_FROM + " to " + MID_SEASON_TO));
        line(text, "Post-harvest picture: %s", postHarvest.map(LocalDate::toString)
                .orElse("none, no stored date from " + POST_HARVEST_FROM + " to " + POST_HARVEST_TO));
        line(text, "Parameters: %s", parameters(p));
        return text.toString();
    }

    /** Every parameter by name, in the record's order, so a folder says which values made it. */
    static String parameters(FieldParameters p) {
        List<String> parts = new java.util.ArrayList<>();
        for (java.lang.reflect.RecordComponent c : FieldParameters.class.getRecordComponents()) {
            try {
                parts.add(c.getName() + " " + c.getAccessor().invoke(p));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return String.join(", ", parts);
    }

    private static void line(StringBuilder text, String format, Object... values) {
        text.append(String.format(Locale.ROOT, format, values)).append('\n');
    }

    private static String join(List<LocalDate> dates) {
        return dates.stream().map(LocalDate::toString).collect(Collectors.joining(" "));
    }
}
