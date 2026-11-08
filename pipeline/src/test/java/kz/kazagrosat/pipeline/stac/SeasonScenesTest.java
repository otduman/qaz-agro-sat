package kz.kazagrosat.pipeline.stac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kz.kazagrosat.core.geo.BoundingBox;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;

class SeasonScenesTest {

    private static final BoundingBox AREA = new BoundingBox(62.40, 53.45, 62.70, 53.63);
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    /** A scene whose footprint covers the whole area. */
    private static StacItem covering(String tile, String datetime, double cloud) {
        return scene(tile, datetime, cloud, new Envelope(62.0, 63.0, 53.0, 54.0));
    }

    private static StacItem scene(String tile, String datetime, double cloud, Envelope footprint) {
        Instant time = Instant.parse(datetime);
        String stamp = datetime.substring(0, 19).replace("-", "").replace(":", "");
        String id = "S2B_" + tile + "_" + stamp + "_L2A_" + (int) (cloud * 10);
        return new StacItem(id, time, cloud, 32641, GEOMETRY.toGeometry(footprint), Map.of());
    }

    @Test
    void keepsTheLeastCloudySceneOfEachDate() {
        StacItem cloudy = covering("T41UMV", "2025-07-12T07:10:00Z", 30);
        StacItem clear = covering("T41UMV", "2025-07-12T07:20:00Z", 5);

        SeasonScenes season = SeasonScenes.choose(List.of(cloudy, clear), AREA).orElseThrow();

        assertEquals(List.of(clear), season.scenes());
    }

    @Test
    void dropsScenesThatDoNotCoverTheWholeArea() {
        StacItem halfOnly = scene("T41UMV", "2025-07-12T07:10:00Z", 1, new Envelope(62.0, 62.55, 53.0, 54.0));
        StacItem whole = covering("T41UMV", "2025-07-12T07:20:00Z", 40);

        SeasonScenes season = SeasonScenes.choose(List.of(halfOnly, whole), AREA).orElseThrow();

        assertEquals(List.of(whole), season.scenes());
    }

    @Test
    void sticksToTheTileThatCoversTheAreaOnMostDates() {
        List<StacItem> found = List.of(
                covering("T41UMV", "2025-07-12T07:10:00Z", 10),
                covering("T41UMV", "2025-07-17T07:10:00Z", 10),
                covering("T41UMV", "2025-07-22T07:10:00Z", 10),
                covering("T41UNV", "2025-07-14T07:10:00Z", 1));

        SeasonScenes season = SeasonScenes.choose(found, AREA).orElseThrow();

        assertEquals("T41UMV", season.tile());
        assertEquals(3, season.scenes().size());
        assertTrue(season.scenes().stream().allMatch(s -> s.id().contains("T41UMV")));
    }

    @Test
    void keepsTheTileAnEarlierRunChose() {
        List<StacItem> found = List.of(
                covering("T41UMV", "2025-07-12T07:10:00Z", 10),
                covering("T41UMV", "2025-07-17T07:10:00Z", 10),
                covering("T41UNV", "2025-07-14T07:10:00Z", 1));

        SeasonScenes season = SeasonScenes.choose(found, AREA, "T41UNV").orElseThrow();

        assertEquals("T41UNV", season.tile(), "the stack on disk is on T41UNV, so a rerun stays there");
        assertEquals(1, season.scenes().size());
        assertTrue(SeasonScenes.choose(found, AREA, "T42ABC").isEmpty(), "a tile with no scene gives nothing");
    }

    @Test
    void isEmptyWhenNoSceneCoversTheArea() {
        StacItem elsewhere = scene("T41UMV", "2025-07-12T07:10:00Z", 1, new Envelope(70.0, 71.0, 50.0, 51.0));

        assertTrue(SeasonScenes.choose(List.of(elsewhere), AREA).isEmpty());
    }

    @Test
    void readsTheTileFromTheSceneId() {
        StacItem real = new StacItem("S2B_T41UMV_20250712T070939_L2A", Instant.parse("2025-07-12T07:17:06Z"),
                1.3, 32641, GEOMETRY.toGeometry(new Envelope(62, 63, 53, 54)), Map.of());
        StacItem odd = new StacItem("no-tile-here", Instant.parse("2025-07-12T07:17:06Z"),
                1.3, 32641, GEOMETRY.toGeometry(new Envelope(62, 63, 53, 54)), Map.of());

        assertEquals("T41UMV", SeasonScenes.tileOf(real));
        assertThrows(IllegalArgumentException.class, () -> SeasonScenes.tileOf(odd));
    }

    @Test
    void datesScenesByUtcDay() {
        assertEquals(LocalDate.of(2025, 7, 12),
                SeasonScenes.dateOf(covering("T41UMV", "2025-07-12T23:30:00Z", 1)));
    }

    @Test
    void listsScenesInDateOrder() {
        List<StacItem> found = List.of(
                covering("T41UMV", "2025-09-01T07:10:00Z", 10),
                covering("T41UMV", "2025-07-12T07:10:00Z", 10),
                covering("T41UMV", "2025-08-03T07:10:00Z", 10));

        List<LocalDate> dates = SeasonScenes.choose(found, AREA).orElseThrow().scenes().stream()
                .map(SeasonScenes::dateOf).toList();

        assertEquals(List.of(LocalDate.of(2025, 7, 12), LocalDate.of(2025, 8, 3), LocalDate.of(2025, 9, 1)), dates);
    }
}
