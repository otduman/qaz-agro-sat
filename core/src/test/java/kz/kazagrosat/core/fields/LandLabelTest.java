package kz.kazagrosat.core.fields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LandLabelTest {

    private static final FieldParameters P = FieldParameters.DEFAULTS;

    private static LandLabel.Labelled label(double... values) {
        return LandLabel.of(SyntheticSeason.DATES, values, P);
    }

    private static double[] curve(java.util.function.IntToDoubleFunction value) {
        double[] values = new double[SyntheticSeason.DATES.size()];
        for (int d = 0; d < values.length; d++) {
            values[d] = value.applyAsDouble(d);
        }
        return values;
    }

    @Test
    void wheatIsAField() {
        LandLabel.Labelled labelled = label(curve(SyntheticSeason::wheat));

        assertEquals(LandLabel.FIELD, labelled.label());
        assertNull(labelled.reason());
    }

    @Test
    void lateWheatIsAField() {
        assertEquals(LandLabel.FIELD, label(curve(SyntheticSeason::lateWheat)).label());
    }

    @Test
    void bareAllSeasonIsFallow() {
        assertEquals(LandLabel.FALLOW, label(curve(d -> 0.15)).label());
    }

    @Test
    void waterIsOther() {
        LandLabel.Labelled labelled = label(curve(d -> -0.1));

        assertEquals(LandLabel.OTHER, labelled.label());
        assertNull(labelled.reason());
    }

    @Test
    void treesGreenFromSpringAreOther() {
        // Dates: 05-05, 05-19, 06-02, 06-16, 06-30, 07-14, 07-28, 08-11, 08-25, 09-08, 09-22, 10-06.
        assertEquals(LandLabel.OTHER,
                label(0.6, 0.65, 0.7, 0.72, 0.75, 0.75, 0.74, 0.72, 0.7, 0.68, 0.65, 0.4).label());
    }

    @Test
    void grassPeakingInEarlyJuneIsOther() {
        assertEquals(LandLabel.OTHER,
                label(0.25, 0.5, 0.55, 0.5, 0.45, 0.4, 0.38, 0.35, 0.33, 0.32, 0.31, 0.3).label());
    }

    @Test
    void aFieldMustDropBeforeTheEndOfSeptember() {
        // Peaks in July but only drops in October.
        assertEquals(LandLabel.OTHER,
                label(0.15, 0.2, 0.25, 0.5, 0.7, 0.8, 0.8, 0.78, 0.76, 0.75, 0.7, 0.3).label());
    }

    @Test
    void tooFewDatesIsOtherWithAReason() {
        double[] values = curve(SyntheticSeason::wheat);
        Arrays.fill(values, 4, values.length, Double.NaN);

        LandLabel.Labelled labelled = label(values);

        assertEquals(LandLabel.OTHER, labelled.label());
        assertEquals(LandLabel.TOO_FEW_DATES, labelled.reason());
    }

    @Test
    void noSpringDateIsOtherWithAReason() {
        double[] values = curve(SyntheticSeason::wheat);
        Arrays.fill(values, 0, 3, Double.NaN);

        LandLabel.Labelled labelled = label(values);

        assertEquals(LandLabel.OTHER, labelled.label());
        assertEquals(LandLabel.NO_SPRING_DATE, labelled.reason());
    }

    @Test
    void fallowNeedsNoSpringDate() {
        double[] values = curve(d -> 0.15);
        Arrays.fill(values, 0, 3, Double.NaN);

        assertEquals(LandLabel.FALLOW, label(values).label());
    }
}
