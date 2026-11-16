package kz.kazagrosat.pipeline.stac;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kz.kazagrosat.core.geo.BoundingBox;

/**
 * The scenes of one season for an area: one per date, all from one tile. Sticking to one tile means
 * every date shares the same pixel grid, so pixels line up across dates without resampling.
 *
 * @param tile   the Sentinel-2 tile, such as {@code T41UMV}
 * @param scenes in date order
 */
public record SeasonScenes(String tile, List<StacItem> scenes) {

    private static final Pattern TILE = Pattern.compile("_(T\\d{2}[A-Z]{3})_");

    /**
     * Keeps scenes covering the whole area, takes the tile that covers it on the most dates (ties go to
     * the alphabetically first tile), and per UTC date the least cloudy scene (ties go to the smaller id).
     *
     * <p>When no scene of any tile covers the whole area (a pass edge runs through it on every date), keeps the
     * scenes that see at least {@link #MIN_PART} of it instead, on the tile whose scenes see the most of it over the
     * season; the rest of each photo reads as no data, like cloud. Empty when no scene sees that much.
     */
    public static Optional<SeasonScenes> choose(List<StacItem> found, BoundingBox area) {
        return choose(found, area, MOST);
    }

    /** As {@link #choose(List, BoundingBox)}, keeping a scene on a date with no whole cover when it sees at least
     * {@code most} of the area (default {@link #MOST}). */
    public static Optional<SeasonScenes> choose(List<StacItem> found, BoundingBox area, double most) {
        Map<String, List<StacItem>> byTile = found.stream()
                .filter(scene -> SceneSelector.covers(scene, area))
                .collect(Collectors.groupingBy(SeasonScenes::tileOf, TreeMap::new, Collectors.toList()));
        if (byTile.isEmpty()) {
            Map<String, List<StacItem>> partly = found.stream()
                    .filter(scene -> share(scene, area) >= MIN_PART)
                    .collect(Collectors.groupingBy(SeasonScenes::tileOf, TreeMap::new, Collectors.toList()));
            return partly.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, List<StacItem>>>comparingDouble(
                                    e -> e.getValue().stream().mapToDouble(scene -> share(scene, area)).sum())
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(entry -> new SeasonScenes(entry.getKey(), onePerDate(entry.getValue())));
        }
        Optional<Map.Entry<String, List<StacItem>>> best = byTile.entrySet().stream()
                .max(Comparator.<Map.Entry<String, List<StacItem>>>comparingLong(e -> distinctDates(e.getValue()))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));
        return best.map(entry -> new SeasonScenes(entry.getKey(), withMostlyCovering(found, area, entry.getKey(),
                entry.getValue(), most)));
    }

    /** A scene that sees at least this share of the area is kept on a date when no scene sees all of it. */
    public static final double MOST = 0.5;

    /**
     * One scene per date on the tile: the least cloudy whole-cover scene, or on a date with none, the scene seeing
     * the most of the area if it sees at least {@link #MOST} of it (the rest reads as no data, like cloud).
     */
    private static List<StacItem> withMostlyCovering(List<StacItem> found, BoundingBox area, String tile,
            List<StacItem> whole, double most) {
        List<StacItem> full = onePerDate(whole);
        java.util.Set<LocalDate> fullDates = full.stream().map(SeasonScenes::dateOf)
                .collect(Collectors.toSet());
        Map<LocalDate, StacItem> extra = new TreeMap<>();
        for (StacItem scene : found) {
            if (!tileOf(scene).equals(tile) || fullDates.contains(dateOf(scene)) || share(scene, area) < most) {
                continue;
            }
            extra.merge(dateOf(scene), scene, (a, b) -> share(a, area) >= share(b, area) ? a : b);
        }
        List<StacItem> all = new java.util.ArrayList<>(full);
        all.addAll(extra.values());
        all.sort(Comparator.comparing(SeasonScenes::dateOf));
        return List.copyOf(all);
    }

    /**
     * As {@link #choose(List, BoundingBox)}, but on the given tile, so a resumed download stays on the tile it
     * started on. Empty when no scene on that tile covers the area.
     */
    public static Optional<SeasonScenes> choose(List<StacItem> found, BoundingBox area, String tile) {
        return choose(found, area, tile, MOST);
    }

    /** As {@link #choose(List, BoundingBox, String)} with the share of {@link #choose(List, BoundingBox, double)}. */
    public static Optional<SeasonScenes> choose(List<StacItem> found, BoundingBox area, String tile, double most) {
        List<StacItem> onTile = found.stream()
                .filter(scene -> tileOf(scene).equals(tile) && SceneSelector.covers(scene, area))
                .toList();
        if (onTile.isEmpty() && found.stream().noneMatch(scene -> SceneSelector.covers(scene, area))) {
            onTile = found.stream()
                    .filter(scene -> tileOf(scene).equals(tile) && share(scene, area) >= MIN_PART)
                    .toList();
            return onTile.isEmpty() ? Optional.empty() : Optional.of(new SeasonScenes(tile, onePerDate(onTile)));
        }
        return onTile.isEmpty() ? Optional.empty()
                : Optional.of(new SeasonScenes(tile, withMostlyCovering(found, area, tile, onTile, most)));
    }

    /** A scene must see at least this share of the area to be kept when none sees all of it. */
    public static final double MIN_PART = 0.1;

    /** The share of the area's box inside the scene's data footprint. */
    static double share(StacItem scene, BoundingBox area) {
        org.locationtech.jts.geom.Geometry box = new org.locationtech.jts.geom.GeometryFactory().toGeometry(
                new org.locationtech.jts.geom.Envelope(area.west(), area.east(), area.south(), area.north()));
        return scene.footprint().intersection(box).getArea() / box.getArea();
    }

    private static long distinctDates(List<StacItem> scenes) {
        return scenes.stream().map(SeasonScenes::dateOf).distinct().count();
    }

    private static List<StacItem> onePerDate(List<StacItem> scenes) {
        Comparator<StacItem> preferred = Comparator.comparingDouble(StacItem::cloudCover)
                .thenComparing(StacItem::id);
        Map<LocalDate, StacItem> byDate = new TreeMap<>();
        for (StacItem scene : scenes) {
            byDate.merge(dateOf(scene), scene, (a, b) -> preferred.compare(a, b) <= 0 ? a : b);
        }
        return List.copyOf(byDate.values());
    }

    /** The tile named in a Sentinel-2 scene id, such as {@code T41UMV}. */
    public static String tileOf(StacItem scene) {
        Matcher matcher = TILE.matcher(scene.id());
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot tell the tile of scene " + scene.id());
        }
        return matcher.group(1);
    }

    /** The UTC day the scene was taken. */
    public static LocalDate dateOf(StacItem scene) {
        return scene.datetime().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
