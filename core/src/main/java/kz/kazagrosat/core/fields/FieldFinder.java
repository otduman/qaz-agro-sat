package kz.kazagrosat.core.fields;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * Finds a season's fields from that season's images alone:
 * <ol>
 *   <li>edge strength per date, gathered into the season edge;</li>
 *   <li>marker-based watershed on the season edge;</li>
 *   <li>per-region NDVI sums on every date (a second read of the season);</li>
 *   <li>merging of neighbours with weak borders and matching curves;</li>
 *   <li>dropping regions under {@code minRegionHectares} and filling holes under {@code maxHoleHectares};</li>
 *   <li>a land label and a uniformity score per region.</li>
 * </ol>
 * Images are read one date at a time.
 */
public final class FieldFinder {

    private FieldFinder() {}

    public static FieldMap find(SeasonImages season, FieldParameters p, Consumer<String> progress)
            throws IOException, InterruptedException {
        PixelGrid grid = season.grid();
        int width = grid.width();
        int height = grid.height();
        int pixels = width * height;
        List<LocalDate> dates = List.copyOf(season.dates());
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("The season has no dates with images");
        }

        SeasonEdges gathered = new SeasonEdges(pixels, p.edgeRank());
        for (int d = 0; d < dates.size(); d++) {
            DateImage image = checked(season.read(dates.get(d)), pixels);
            gathered.add(EdgeStrength.of(image.ndvi(), image.brightness(), image.clear(), width, height,
                    p.brightnessWeight()));
            progress.accept(String.format(Locale.ROOT, "Edges: %s (%d of %d)", dates.get(d), d + 1, dates.size()));
        }
        float[] edges = gathered.result();

        int[] watershed = Watershed.flood(edges, width, height, p);
        int regionCount = max(watershed);
        progress.accept("Watershed: " + regionCount + " regions");

        RegionSeries series = new RegionSeries(regionCount, dates.size());
        for (int d = 0; d < dates.size(); d++) {
            DateImage image = checked(season.read(dates.get(d)), pixels);
            series.add(d, watershed, image.ndvi(), image.clear());
            progress.accept(String.format(Locale.ROOT, "Regions: %s (%d of %d)", dates.get(d), d + 1, dates.size()));
        }

        int[] newIdOf = RegionMerger.merge(watershed, edges, width, height, series, counts(watershed, regionCount),
                p);
        int mergedCount = max(newIdOf);
        int[] merged = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            merged[i] = newIdOf[watershed[i]];
        }
        progress.accept("Merged: " + (regionCount - mergedCount) + " merges, " + mergedCount + " regions left");

        int minPixels = FieldParameters.pixels(p.minRegionHectares(), grid.pixelSize());
        int[] mergedPixels = counts(merged, mergedCount);
        int dropped = 0;
        for (int r = 1; r <= mergedCount; r++) {
            if (mergedPixels[r] < minPixels) {
                dropped++;
            }
        }
        int[] kept = Cleanup.dropSmall(merged, mergedCount, minPixels);
        Cleanup.Holes holes = Cleanup.fillHoles(kept, width, height,
                FieldParameters.pixels(p.maxHoleHectares(), grid.pixelSize()));
        progress.accept(String.format(Locale.ROOT, "Clean-up: %d regions dropped, %d holes filled, %d kept",
                dropped, holes.filled(), holes.kept()));

        int[] labels = renumber(holes.labels(), mergedCount);
        int finalCount = max(labels);
        long droppedPixels = 0;
        long noDataPixels = 0;
        for (int i = 0; i < pixels; i++) {
            if (labels[i] == 0) {
                if (merged[i] == 0) {
                    noDataPixels++;
                } else {
                    droppedPixels++;
                }
            }
        }

        // Watershed regions move whole through merging, dropping and filling, so each one's final id is
        // the final label of any of its pixels.
        int[] finalIdOf = new int[regionCount + 1];
        for (int i = 0; i < pixels; i++) {
            if (watershed[i] != 0) {
                finalIdOf[watershed[i]] = labels[i];
            }
        }
        RegionSeries finalSeries = series.combine(finalIdOf, finalCount);
        int[] finalPixels = counts(labels, finalCount);

        List<Region> regions = new ArrayList<>(finalCount);
        Map<String, Integer> otherReasons = new TreeMap<>();
        for (int r = 1; r <= finalCount; r++) {
            double[] values = new double[dates.size()];
            for (int d = 0; d < dates.size(); d++) {
                values[d] = finalSeries.mean(r, d, finalPixels[r], p.minClearShare());
            }
            LandLabel.Labelled labelled = LandLabel.of(dates, values, p);
            if (labelled.reason() != null) {
                otherReasons.merge(labelled.reason(), 1, Integer::sum);
            }
            regions.add(new Region(r, finalPixels[r], labelled.label(), finalSeries.uniformity(r, finalPixels[r], p)));
        }

        FieldSummary summary = new FieldSummary(dates, regionCount, regionCount - mergedCount, dropped,
                droppedPixels, holes.filled(), holes.filledPixels(), holes.kept(), noDataPixels, otherReasons);
        return new FieldMap(grid, labels, edges, List.copyOf(regions), summary);
    }

    private static DateImage checked(DateImage image, int pixels) {
        if (image.ndvi().length != pixels || image.brightness().length != pixels || image.clear().length != pixels) {
            throw new IllegalArgumentException("The image of " + image.date() + " doesn't fill the grid");
        }
        return image;
    }

    /** Numbers the regions from 1 in row-major order of their first pixel. */
    private static int[] renumber(int[] labels, int maxLabel) {
        int[] idOf = new int[maxLabel + 1];
        int next = 1;
        int[] result = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int label = labels[i];
            if (label == 0) {
                continue;
            }
            if (idOf[label] == 0) {
                idOf[label] = next++;
            }
            result[i] = idOf[label];
        }
        return result;
    }

    private static int[] counts(int[] labels, int maxLabel) {
        int[] counts = new int[maxLabel + 1];
        for (int label : labels) {
            counts[label]++;
        }
        return counts;
    }

    private static int max(int[] values) {
        int max = 0;
        for (int value : values) {
            max = Math.max(max, value);
        }
        return max;
    }
}
