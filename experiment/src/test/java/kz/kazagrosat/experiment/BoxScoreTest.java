package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BoxScoreTest {

    private static final String TWO = FieldCheck.TWO_FIELDS;

    @Test
    void splitsAgreementByWhatTheReadingExpects() {
        BoxScore score = BoxScore.of(
                List.of(TWO, TWO, "cut seen", "cut seen", "unclear", FieldCheck.MIXED, "no reading"),
                List.of(true, false, false, true, false, true, true));

        assertEquals(2, score.twoFieldBoxes());
        assertEquals(1, score.twoFieldCrossed());
        assertEquals(3, score.oneFieldBoxes());
        assertEquals(2, score.oneFieldNotCrossed());
        assertEquals(3, score.agree());
        assertEquals(5, score.scored());
        assertEquals(2, score.unscored());
    }

    @Test
    void aMapWithNoBoundariesAgreesOnEveryOneFieldBox() {
        BoxScore score = BoxScore.of(
                List.of(TWO, "cut seen", "cut seen", "cut seen"),
                List.of(true, true, false, false));

        assertEquals(3, score.noBoundaryAgree());
        assertEquals(3, score.agree());
    }
}
