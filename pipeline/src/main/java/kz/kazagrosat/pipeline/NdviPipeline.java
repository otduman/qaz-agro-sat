package kz.kazagrosat.pipeline;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kz.kazagrosat.core.geo.BoundingBox;
import kz.kazagrosat.core.geo.UtmProjection;
import kz.kazagrosat.core.index.Ndvi;
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

/** Produces a cloud-masked NDVI map for one area on one date. */
public final class NdviPipeline {

    public static final String COLLECTION = "sentinel-2-c1-l2a";

    private final StacClient stac;
    private final RetryingHttp http;

    public NdviPipeline(URI stacUri, RetryPolicy retry) {
        this.stac = new StacClient(stacUri, retry);
        this.http = new RetryingHttp(retry);
    }

    /** Returns nothing when no scene from that day covers the whole area. */
    public Optional<NdviResult> run(BoundingBox area, LocalDate date) throws IOException, InterruptedException {
        List<StacItem> scenes = stac.search(new StacSearch(COLLECTION, area, date, date, null));
        Optional<StacItem> scene = SceneSelector.bestCovering(scenes, area);
        return scene.isPresent() ? Optional.of(compute(scene.get(), area)) : Optional.empty();
    }

    private NdviResult compute(StacItem scene, BoundingBox area) throws IOException, InterruptedException {
        StacAsset redAsset = scene.asset("red");
        StacAsset nirAsset = scene.asset("nir");
        CogReader red = open(redAsset);
        CogReader nir = open(nirAsset);
        CogReader scl = open(scene.asset("scl"));
        int factor = bandPixelsPerMaskPixel(red.grid(), nir.grid(), scl.grid());

        // The window is chosen on the coarser mask grid so that it lands on whole pixels of both.
        UtmProjection projection = UtmProjection.forEpsg(scl.grid().epsg());
        PixelWindow maskWindow = scl.grid().windowCovering(projection.project(area));
        PixelWindow bandWindow = maskWindow.scaled(factor);

        float[] redValues = reflectance(red, redAsset, bandWindow);
        float[] nirValues = reflectance(nir, nirAsset, bandWindow);
        int[] classes = SclMask.upsample(
                scl.readWindow(maskWindow), maskWindow.width(), maskWindow.height(), factor);

        float[] ndvi = SclMask.apply(Ndvi.compute(redValues, nirValues), SclMask.usable(classes));
        return new NdviResult(scene, red.grid().subGrid(bandWindow), ndvi);
    }

    private CogReader open(StacAsset asset) throws IOException, InterruptedException {
        return CogReader.open(new HttpByteSource(URI.create(asset.href()), http));
    }

    private static float[] reflectance(CogReader band, StacAsset asset, PixelWindow window)
            throws IOException, InterruptedException {
        return Reflectance.fromDigitalNumbers(
                band.readWindow(window), asset.scale(), asset.offset(), asset.nodata());
    }

    private static int bandPixelsPerMaskPixel(PixelGrid red, PixelGrid nir, PixelGrid mask) throws IOException {
        double ratio = mask.pixelSize() / red.pixelSize();
        boolean aligned = red.equals(nir)
                && ratio >= 1 && ratio == Math.rint(ratio)
                && red.epsg() == mask.epsg()
                && red.originX() == mask.originX()
                && red.originY() == mask.originY();
        if (!aligned) {
            throw new IOException("Bands and mask do not line up: red " + red + ", nir " + nir + ", mask " + mask);
        }
        return (int) ratio;
    }
}
