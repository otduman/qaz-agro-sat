package kz.kazagrosat.experiment;

import java.util.List;

/**
 * The box check's score, split by what each reading expects, beside the score a map with no boundaries
 * at all would get. A pooled agreement alone flatters: most boxes are one field, so drawing no line
 * agrees with nearly all of them.
 *
 * @param twoFieldBoxes      boxes read as two fields
 * @param twoFieldCrossed    ... of which crossed by a boundary
 * @param oneFieldBoxes      boxes read as one field (any other scored reading)
 * @param oneFieldNotCrossed ... of which not crossed
 * @param unscored           boxes read as part natural part field, or with no reading
 */
record BoxScore(int twoFieldBoxes, int twoFieldCrossed, int oneFieldBoxes, int oneFieldNotCrossed, int unscored) {

    static BoxScore of(List<String> readings, List<Boolean> crossed) {
        if (readings.size() != crossed.size()) {
            throw new IllegalArgumentException(readings.size() + " readings but " + crossed.size() + " results");
        }
        int twoBoxes = 0;
        int twoCrossed = 0;
        int oneBoxes = 0;
        int oneClear = 0;
        int unscored = 0;
        for (int i = 0; i < readings.size(); i++) {
            String reading = readings.get(i);
            if (!isScored(reading)) {
                unscored++;
            } else if (reading.equals(FieldCheck.TWO_FIELDS)) {
                twoBoxes++;
                twoCrossed += crossed.get(i) ? 1 : 0;
            } else {
                oneBoxes++;
                oneClear += crossed.get(i) ? 0 : 1;
            }
        }
        return new BoxScore(twoBoxes, twoCrossed, oneBoxes, oneClear, unscored);
    }

    static boolean isScored(String reading) {
        return !reading.equals(FieldCheck.MIXED) && !reading.equals(FieldCheck.NO_READING);
    }

    int scored() {
        return twoFieldBoxes + oneFieldBoxes;
    }

    int agree() {
        return twoFieldCrossed + oneFieldNotCrossed;
    }

    /** A map with no boundaries never crosses a box, so it agrees on every one-field box and no other. */
    int noBoundaryAgree() {
        return oneFieldBoxes;
    }
}
