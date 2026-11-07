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
     * Empty when no scene covers the area.
     */
    public static Optional<SeasonScenes> choose(List<StacItem> found, BoundingBox area) {
        Map<String, List<StacItem>> byTile = found.stream()
                .filter(scene -> SceneSelector.covers(scene, area))
                .collect(Collectors.groupingBy(SeasonScenes::tileOf, TreeMap::new, Collectors.toList()));
        Optional<Map.Entry<String, List<StacItem>>> best = byTile.entrySet().stream()
                .max(Comparator.<Map.Entry<String, List<StacItem>>>comparingLong(e -> distinctDates(e.getValue()))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));
        return best.map(entry -> new SeasonScenes(entry.getKey(), onePerDate(entry.getValue())));
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
