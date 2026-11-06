package kz.kazagrosat.pipeline.stac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.pipeline.http.RetryPolicy;
import kz.kazagrosat.pipeline.testutil.TestHttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs against the real Earth Search response for July 2025 over Kostanay oblast. */
class SceneSelectorTest {

    private static List<StacItem> julyScenes;

    @BeforeAll
    static void loadRealScenes() throws Exception {
        String json = StacClientTest.realResponse();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/search", ex -> TestHttpServer.respondJson(ex, 200, json));
            julyScenes = new StacClient(server.uri(""), RetryPolicy.DEFAULT).search(new StacSearch(
                    "sentinel-2-c1-l2a", new BoundingBox(62.7, 53.6, 63.0, 53.8),
                    LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 31), null));
        }
    }

    @Test
    void rejectsTheClearestSceneWhenItIsAnEmptySliver() {
        // S2C_T41UNV_20250717 has the lowest cloud cover (0.24%) but is 97% nodata.
        BoundingBox area = new BoundingBox(62.75, 53.62, 62.85, 53.70);

        Optional<StacItem> scene = SceneSelector.bestCovering(julyScenes, area);

        assertEquals("S2B_T41UMV_20250712T070939_L2A", scene.orElseThrow().id());
    }

    @Test
    void returnsNothingWhenNoSceneCoversTheWholeArea() {
        BoundingBox elsewhere = new BoundingBox(70.0, 50.0, 70.1, 50.1);

        assertTrue(SceneSelector.bestCovering(julyScenes, elsewhere).isEmpty());
    }

    @Test
    void reportsWhetherASceneCoversTheArea() {
        BoundingBox area = new BoundingBox(62.75, 53.62, 62.85, 53.70);
        StacItem sliver = julyScenes.stream()
                .filter(i -> i.id().equals("S2C_T41UNV_20250717T071229_L2A")).findFirst().orElseThrow();
        StacItem full = julyScenes.stream()
                .filter(i -> i.id().equals("S2B_T41UMV_20250712T070939_L2A")).findFirst().orElseThrow();

        assertEquals(false, SceneSelector.covers(sliver, area));
        assertEquals(true, SceneSelector.covers(full, area));
    }
}
