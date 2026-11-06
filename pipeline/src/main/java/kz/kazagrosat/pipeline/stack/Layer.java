package kz.kazagrosat.pipeline.stack;

import java.util.List;

/** The seven layers a season stack keeps for each date, at their native resolution. */
public enum Layer {
    BLUE("blue"),
    GREEN("green"),
    RED("red"),
    NIR("nir"),
    SWIR16("swir16"),
    SWIR22("swir22"),
    SCL("scl");

    private final String assetKey;

    Layer(String assetKey) {
        this.assetKey = assetKey;
    }

    /** The asset's name in an Earth Search Sentinel-2 item. */
    public String assetKey() {
        return assetKey;
    }

    public String fileName() {
        return assetKey + ".tif";
    }

    /** The cloud mask holds classes 0 to 11; the bands hold 16-bit digital numbers. */
    public int bits() {
        return this == SCL ? 8 : 16;
    }

    /** Blue, green, red and near-infrared are 10 m; the shortwave bands and the cloud mask are 20 m. */
    public boolean tenMetre() {
        return this == BLUE || this == GREEN || this == RED || this == NIR;
    }

    /** Every layer except the cloud mask. */
    public static List<Layer> bands() {
        return List.of(BLUE, GREEN, RED, NIR, SWIR16, SWIR22);
    }
}
