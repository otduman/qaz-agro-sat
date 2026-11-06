package kz.kazagrosat.pipeline.stack;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.mask.SclMask;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.NdviPipeline;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.HttpByteSource;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.stac.SeasonScenes;
import kz.kazagrosat.pipeline.stac.StacAsset;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;

/**
 * Downloads every usable Sentinel-2 date of one season for an area into a {@link SeasonStack} folder.
 *
 * <p>A run can stop at any moment and be started again. Every file is written under a temporary name
 * and renamed once complete, and a date enters the index only after all its files exist, so a rerun
 * redoes exactly the dates that are not in the index.
 */
public final class SeasonStackBuilder {

    /** Scenes cloudier than this over their whole tile are not searched for. */
    static final double MAX_TILE_CLOUD = 80.0;
    /** Below this clear share of the area, a date keeps only its cloud mask. */
    static final double MIN_CLEAR_SHARE = 0.01;

    private final StacClient stac;
    private final RetryingHttp http;
    private final int threads;
    private final Consumer<String> progress;

    /** Calls {@code progress} with a short message as each step starts and as each date finishes. */
    public SeasonStackBuilder(URI stacUri, RetryPolicy retry, int threads, Consumer<String> progress) {
        this.stac = new StacClient(stacUri, retry);
        this.http = new RetryingHttp(retry);
        this.threads = threads;
        this.progress = progress;
    }

    public StackReport build(BoundingBox area, int year, Path folder) throws IOException, InterruptedException {
        LocalDate from = LocalDate.of(year, 5, 1);
        LocalDate to = LocalDate.of(year, 10, 31);
        progress.accept("Searching for scenes from " + from + " to " + to + "...");
        List<StacItem> found = stac.search(new StacSearch(NdviPipeline.COLLECTION, area, from, to, MAX_TILE_CLOUD));
        SeasonScenes season = SeasonScenes.choose(found, area).orElseThrow(() -> new IOException(
                "No single Sentinel-2 tile covers the whole area on any date in " + year
                        + ". Areas that need several tiles are not supported yet."));

        Optional<StackArea> existing = StackArea.read(folder);
        if (existing.isPresent()
                && (!existing.get().sameRequest(area, year) || !existing.get().tile().equals(season.tile()))) {
            StackArea held = existing.get();
            throw new IOException(folder + " already holds the " + held.year() + " season for "
                    + describe(held.bbox()) + " on tile " + held.tile() + ". Use another folder.");
        }

        Path indexFile = folder.resolve(SeasonStack.INDEX);
        List<StackEntry> finished = new ArrayList<>(StackIndex.read(indexFile));
        Set<LocalDate> finishedDates = finished.stream().map(StackEntry::date).collect(Collectors.toSet());
        List<StacItem> todo = season.scenes().stream()
                .filter(scene -> !finishedDates.contains(SeasonScenes.dateOf(scene))).toList();
        int alreadyStored = season.scenes().size() - todo.size();
        progress.accept(alreadyStored + " of " + season.scenes().size() + " dates already stored on tile "
                + season.tile());
        if (todo.isEmpty()) {
            return new StackReport(season.tile(), season.scenes().size(), alreadyStored, 0, 0, List.of());
        }

        StackArea stackArea = existing.isPresent() ? existing.get() : locate(area, year, season.tile(), todo.getFirst());
        stackArea.write(folder);
        progress.accept("Downloading " + todo.size() + " dates, " + threads + " at a time...");
        return download(todo, stackArea, area, folder, indexFile, finished, season, alreadyStored);
    }

    private StackReport download(List<StacItem> todo, StackArea stackArea, BoundingBox area, Path folder,
                                 Path indexFile, List<StackEntry> finished, SeasonScenes season,
                                 int alreadyStored) throws IOException, InterruptedException {
        Object indexLock = new Object();
        AtomicInteger done = new AtomicInteger();
        List<String> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<StackEntry>> results = new ArrayList<>();
            for (StacItem scene : todo) {
                results.add(pool.submit(() -> {
                    LocalDate date = SeasonScenes.dateOf(scene);
                    try {
                        StackEntry entry = storeDate(scene, stackArea, area, folder);
                        synchronized (indexLock) {
                            finished.add(entry);
                            StackIndex.write(indexFile, finished);
                        }
                        progress.accept(describe(entry) + " (" + done.incrementAndGet() + " of " + todo.size() + ")");
                        return entry;
                    } catch (IOException | RuntimeException e) {
                        String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
                        progress.accept(date + ": failed: " + reason + " (" + done.incrementAndGet()
                                + " of " + todo.size() + ")");
                        synchronized (failures) {
                            failures.add(date + ": " + reason);
                        }
                        return null;
                    }
                }));
            }
            int stored = 0;
            int cloudy = 0;
            for (Future<StackEntry> result : results) {
                StackEntry entry = result.get();
                if (entry != null && entry.status() == StackEntry.Status.STORED) {
                    stored++;
                } else if (entry != null) {
                    cloudy++;
                }
            }
            List<String> sorted = failures.stream().sorted().toList();
            return new StackReport(season.tile(), season.scenes().size(), alreadyStored, stored, cloudy, sorted);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new IOException("A download task broke unexpectedly", e.getCause());
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw e;
        } finally {
            pool.shutdown();
        }
    }

    /** Finds the area's grids on the tile, from one scene's cloud mask and red band. */
    private StackArea locate(BoundingBox area, int year, String tile, StacItem scene)
            throws IOException, InterruptedException {
        CogReader scl = open(scene.asset(Layer.SCL.assetKey()));
        CogReader red = open(scene.asset(Layer.RED.assetKey()));
        PixelWindow maskWindow = maskWindow(scl, area);
        return new StackArea(area, year, tile, red.grid().subGrid(maskWindow.scaled(2)), scl.grid().subGrid(maskWindow));
    }

    private StackEntry storeDate(StacItem scene, StackArea stackArea, BoundingBox area, Path folder)
            throws IOException, InterruptedException {
        LocalDate date = SeasonScenes.dateOf(scene);
        StacAsset first = scene.asset(Layer.BLUE.assetKey());
        for (Layer band : Layer.bands()) {
            StacAsset asset = scene.asset(band.assetKey());
            if (asset.scale() != first.scale() || asset.offset() != first.offset()
                    || !Objects.equals(asset.nodata(), first.nodata())) {
                throw new IOException("Bands of " + scene.id() + " have different scaling: " + band.assetKey()
                        + " has scale " + asset.scale() + " and offset " + asset.offset() + ", blue has "
                        + first.scale() + " and " + first.offset());
            }
        }

        StacAsset sclAsset = scene.asset(Layer.SCL.assetKey());
        CogReader scl = open(sclAsset);
        PixelWindow maskWindow = maskWindow(scl, area);
        requireGrid(scl.grid().subGrid(maskWindow), stackArea.maskGrid(), scene, Layer.SCL);
        int[] classes = scl.readWindow(maskWindow);
        boolean[] usable = SclMask.usable(classes);
        int clearCount = 0;
        for (boolean clear : usable) {
            clearCount += clear ? 1 : 0;
        }
        double clearShare = (double) clearCount / classes.length;
        Path dateFolder = folder.resolve(date.toString());
        Files.createDirectories(dateFolder);
        writeLayer(dateFolder, Layer.SCL, classes, stackArea.maskGrid(), sclAsset.nodata());

        StackEntry.Status status = StackEntry.Status.CLOUDY;
        if (clearShare >= MIN_CLEAR_SHARE) {
            for (Layer band : Layer.bands()) {
                StacAsset asset = scene.asset(band.assetKey());
                CogReader reader = open(asset);
                PixelWindow window = band.tenMetre() ? maskWindow.scaled(2) : maskWindow;
                PixelGrid expected = band.tenMetre() ? stackArea.grid() : stackArea.maskGrid();
                requireGrid(reader.grid().subGrid(window), expected, scene, band);
                writeLayer(dateFolder, band, reader.readWindow(window), expected, asset.nodata());
            }
            status = StackEntry.Status.STORED;
        }
        return new StackEntry(date, scene.id(), scene.cloudCover(), clearShare, status,
                first.scale(), first.offset(), first.nodata());
    }

    private static PixelWindow maskWindow(CogReader scl, BoundingBox area) {
        UtmProjection projection = UtmProjection.forEpsg(scl.grid().epsg());
        return scl.grid().windowCovering(projection.project(area));
    }

    private static void requireGrid(PixelGrid actual, PixelGrid expected, StacItem scene, Layer layer)
            throws IOException {
        if (!actual.equals(expected)) {
            throw new IOException("The " + layer.assetKey() + " layer of " + scene.id() + " does not line up with"
                    + " the stack: " + actual + " instead of " + expected);
        }
    }

    /** Writes under a temporary name, then renames, so a file with the final name is always complete. */
    private static void writeLayer(Path dateFolder, Layer layer, int[] values, PixelGrid grid, Integer nodata)
            throws IOException {
        Path file = dateFolder.resolve(layer.fileName());
        Path temporary = dateFolder.resolve(layer.fileName() + ".part");
        TiledTiffWriter.write(temporary, values, grid, layer.bits(), nodata);
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private CogReader open(StacAsset asset) throws IOException, InterruptedException {
        return CogReader.open(new HttpByteSource(URI.create(asset.href()), http));
    }

    private static String describe(StackEntry entry) {
        String clear = String.format(Locale.ROOT, "%.1f%% clear", entry.clearShare() * 100);
        return entry.status() == StackEntry.Status.STORED
                ? entry.date() + ": stored, " + clear
                : entry.date() + ": cloudy, " + clear + ", bands skipped";
    }

    private static String describe(BoundingBox bbox) {
        return bbox.west() + "," + bbox.south() + "," + bbox.east() + "," + bbox.north();
    }
}
