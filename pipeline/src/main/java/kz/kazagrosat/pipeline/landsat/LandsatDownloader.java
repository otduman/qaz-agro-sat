package kz.kazagrosat.pipeline.landsat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.HttpByteSource;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.http.RetryingHttp;
import kz.kazagrosat.pipeline.stac.StacAsset;
import kz.kazagrosat.pipeline.stac.StacClient;
import kz.kazagrosat.pipeline.stac.StacItem;
import kz.kazagrosat.pipeline.stac.StacSearch;
import kz.kazagrosat.pipeline.stack.StackArea;

/**
 * Downloads a season's Landsat 8 and 9 scenes of a stack's area into a {@link LandsatFolder}: Collection 2 level 2
 * from Planetary Computer, free with an anonymous token. For each scene, the window of its 30 m grid that the area's
 * 10 m pixel centres fall in; the quality layer always, the six bands only when at least {@link #MIN_CLEAR_SHARE} of
 * the area is clear. Scenes already in the index with their files on disk are not downloaded again.
 */
public final class LandsatDownloader {

    public static final URI PLANETARY_STAC = URI.create("https://planetarycomputer.microsoft.com/api/stac/v1");
    public static final URI PLANETARY_TOKEN =
            URI.create("https://planetarycomputer.microsoft.com/api/sas/v1/token/landsat-c2-l2");
    public static final String COLLECTION = "landsat-c2-l2";
    /** Below this clear share of the area, a scene keeps only its quality layer. */
    public static final double MIN_CLEAR_SHARE = 0.01;
    private static final Set<String> SATELLITES = Set.of("LC08", "LC09");

    private final StacClient stac;
    private final RetryingHttp http;
    private final PlanetaryToken token;
    private final Consumer<String> progress;

    public LandsatDownloader(URI stacUri, URI tokenUri, RetryPolicy retry, Consumer<String> progress) {
        this.stac = new StacClient(stacUri, retry);
        this.http = new RetryingHttp(retry);
        this.token = new PlanetaryToken(PlanetaryToken.fetcher(http, tokenUri));
        this.progress = progress;
    }

    public LandsatReport download(StackArea area, Path folder) throws IOException, InterruptedException {
        Optional<StackArea> existing = StackArea.read(folder, LandsatFolder.AREA_FILE);
        if (existing.isPresent() && !existing.get().equals(area)) {
            StackArea held = existing.get();
            throw new IOException(folder + " already holds Landsat scenes for the " + held.year() + " season of "
                    + held.bbox() + ". Use another folder.");
        }
        LocalDate from = LocalDate.of(area.year(), 5, 1);
        LocalDate to = LocalDate.of(area.year(), 10, 31);
        progress.accept("Searching Planetary Computer for Landsat scenes from " + from + " to " + to + "...");
        List<StacItem> found = stac.search(new StacSearch(COLLECTION, area.bbox(), from, to, null));
        List<StacItem> wanted = found.stream().filter(item -> SATELLITES.contains(satellite(item))).toList();
        int landsat7 = found.size() - wanted.size();

        Path indexFile = folder.resolve(LandsatFolder.INDEX);
        List<LandsatEntry> finished = new ArrayList<>(LandsatFolder.readIndex(indexFile).stream()
                .filter(entry -> filesExist(folder, entry)).toList());
        Set<String> done = finished.stream().map(LandsatEntry::scene).collect(Collectors.toSet());
        List<StacItem> todo = wanted.stream().filter(item -> !done.contains(item.id())).toList();
        int alreadyStored = wanted.size() - todo.size();
        Files.createDirectories(folder);
        area.write(folder, LandsatFolder.AREA_FILE);
        writeIndex(indexFile, finished);
        progress.accept(found.size() + " scenes found, " + landsat7 + " of them Landsat 7 (left out); "
                + alreadyStored + " already stored, " + todo.size() + " to download");

        List<String> otherZone = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int outside = 0;
        int stored = 0;
        int cloudy = 0;
        for (int i = 0; i < todo.size(); i++) {
            StacItem item = todo.get(i);
            String step = " (" + (i + 1) + " of " + todo.size() + ")";
            if (item.epsg() != area.grid().epsg()) {
                otherZone.add(item.id());
                progress.accept(item.id() + ": on EPSG " + item.epsg() + ", not the stack's " + area.grid().epsg()
                        + "; left out" + step);
                continue;
            }
            Optional<LandsatEntry> entry;
            try {
                entry = store(item, area.grid(), folder);
            } catch (IOException | RuntimeException e) {
                String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
                failures.add(item.id() + ": " + reason);
                progress.accept(item.id() + ": failed: " + reason + step);
                continue;
            }
            if (entry.isEmpty()) {
                outside++;
                progress.accept(item.id() + ": does not cover the area; left out" + step);
                continue;
            }
            finished.add(entry.get());
            writeIndex(indexFile, finished);
            if (entry.get().status() == LandsatEntry.Status.STORED) {
                stored++;
            } else {
                cloudy++;
            }
            progress.accept(describe(entry.get()) + step);
        }
        return new LandsatReport(found.size(), landsat7, List.copyOf(otherZone), outside, alreadyStored, stored,
                cloudy, List.copyOf(failures));
    }

    /** Empty when the scene's grid holds none of the area's pixel centres. */
    private Optional<LandsatEntry> store(StacItem item, PixelGrid area, Path folder)
            throws IOException, InterruptedException {
        StacAsset qaAsset = item.asset(LandsatFolder.QA);
        CogReader qaReader = open(qaAsset);
        PixelGrid scene = qaReader.grid();
        if (scene.epsg() != area.epsg()) {
            throw new IOException("its quality layer is on EPSG " + scene.epsg() + ", not " + area.epsg());
        }
        PixelWindow window = LandsatGrid.windowFor(area, scene);
        if (window == null) {
            return Optional.empty();
        }
        PixelGrid windowGrid = scene.subGrid(window);
        int[] qa = qaReader.readWindow(window);
        int[] lookup = LandsatGrid.lookup(area, scene, window);
        int clear = 0;
        for (int k : lookup) {
            if (k >= 0 && LandsatQa.clear(qa[k])) {
                clear++;
            }
        }
        double clearShare = (double) clear / lookup.length;

        StacAsset first = item.asset(LandsatFolder.BANDS.getFirst());
        for (String band : LandsatFolder.BANDS) {
            StacAsset asset = item.asset(band);
            if (asset.scale() != first.scale() || asset.offset() != first.offset()
                    || !Objects.equals(asset.nodata(), first.nodata())) {
                throw new IOException("its bands have different scaling: " + band + " has scale " + asset.scale()
                        + " and offset " + asset.offset() + ", " + LandsatFolder.BANDS.getFirst() + " has "
                        + first.scale() + " and " + first.offset());
            }
        }
        Path sceneFolder = folder.resolve(item.id());
        Files.createDirectories(sceneFolder);
        LandsatEntry.Status status = clearShare >= MIN_CLEAR_SHARE
                ? LandsatEntry.Status.STORED : LandsatEntry.Status.CLOUDY;
        if (status == LandsatEntry.Status.STORED) {
            for (String band : LandsatFolder.BANDS) {
                StacAsset asset = item.asset(band);
                CogReader reader = open(asset);
                if (!reader.grid().equals(scene)) {
                    throw new IOException("its " + band + " band is on " + reader.grid() + ", not the quality layer's "
                            + scene);
                }
                writeLayer(sceneFolder, band, reader.readWindow(window), windowGrid, asset.nodata());
            }
        }
        // The quality layer last: a scene folder whose quality layer is there has all it should.
        writeLayer(sceneFolder, LandsatFolder.QA, qa, windowGrid, qaAsset.nodata());
        return Optional.of(new LandsatEntry(item.id(), dateOf(item), satellite(item), clearShare, status,
                first.scale(), first.offset(), first.nodata()));
    }

    private static String satellite(StacItem item) {
        return item.id().length() >= 4 ? item.id().substring(0, 4) : item.id();
    }

    static LocalDate dateOf(StacItem item) {
        return item.datetime().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static boolean filesExist(Path folder, LandsatEntry entry) {
        List<String> layers = new ArrayList<>(List.of(LandsatFolder.QA));
        if (entry.status() == LandsatEntry.Status.STORED) {
            layers.addAll(LandsatFolder.BANDS);
        }
        return layers.stream().allMatch(layer -> Files.isRegularFile(LandsatFolder.layerFile(folder, entry.scene(),
                layer)));
    }

    private static void writeLayer(Path sceneFolder, String layer, int[] values, PixelGrid grid, Integer nodata)
            throws IOException {
        Path file = sceneFolder.resolve(layer + ".tif");
        Path part = sceneFolder.resolve(layer + ".tif.part");
        TiledTiffWriter.write(part, values, grid, 16, nodata);
        Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeIndex(Path indexFile, List<LandsatEntry> entries) throws IOException {
        try {
            LandsatFolder.writeIndex(indexFile, entries);
        } catch (AccessDeniedException e) {
            throw new IOException("Cannot update " + indexFile + ": it is open in another program, such as Excel."
                    + " Close it and run the same command again.", e);
        }
    }

    private CogReader open(StacAsset asset) throws IOException, InterruptedException {
        return CogReader.open(new HttpByteSource(URI.create(token.sign(asset.href(), Instant.now())), http));
    }

    private static String describe(LandsatEntry entry) {
        String clear = String.format(Locale.ROOT, "%.1f%% clear", entry.clearShare() * 100);
        return entry.scene() + " (" + entry.date() + "): " + (entry.status() == LandsatEntry.Status.STORED
                ? "stored, " + clear : "cloudy, " + clear + ", bands skipped");
    }
}
