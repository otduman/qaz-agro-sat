package kz.kazagrosat.experiment;

/**
 * Shares of an area's land-cover classes on ESA WorldCover 2021, among pixels with data (class 0 is no
 * data). WorldCover's classes: 10 trees, 20 shrubs, 30 grassland, 40 cropland, 50 built-up, 60 bare,
 * 70 snow, 80 open water, 90 wetland, 95 mangroves, 100 moss and lichen.
 */
record LandShares(int pixels, double cropland, double grassland, double water, double trees, double built,
        double bare) {

    static LandShares of(int[] classes) {
        int pixels = 0;
        int cropland = 0;
        int grassland = 0;
        int water = 0;
        int trees = 0;
        int built = 0;
        int bare = 0;
        for (int value : classes) {
            if (value == 0) {
                continue;
            }
            pixels++;
            switch (value) {
                case 40 -> cropland++;
                case 30 -> grassland++;
                case 80, 90 -> water++;
                case 10, 20 -> trees++;
                case 50 -> built++;
                case 60 -> bare++;
                default -> { }
            }
        }
        double n = pixels;
        return new LandShares(pixels, cropland / n, grassland / n, water / n, trees / n, built / n, bare / n);
    }
}
