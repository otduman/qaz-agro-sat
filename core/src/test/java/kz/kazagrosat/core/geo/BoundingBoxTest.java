package kz.kazagrosat.core.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundingBoxTest {

    @Test
    void parsesWestSouthEastNorthOrder() {
        BoundingBox box = BoundingBox.parse("62.40,53.45,62.70,53.63");

        assertEquals(62.40, box.west());
        assertEquals(53.45, box.south());
        assertEquals(62.70, box.east());
        assertEquals(53.63, box.north());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "62.4,53.4,62.7",          // too few values
            "62.4,53.4,62.7,53.6,1",   // too many values
            "a,53.4,62.7,53.6",        // not a number
            "62.7,53.4,62.4,53.6",     // west is east of east
            "62.4,53.6,62.7,53.4",     // south is north of north
            "62.4,53.4,62.4,53.6",     // zero width
            "62.4,-91,62.7,53.6",      // latitude out of range
            "62.4,53.4,181,53.6",      // longitude out of range
    })
    void rejectsInvalidBoxes(String text) {
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.parse(text));
    }
}
