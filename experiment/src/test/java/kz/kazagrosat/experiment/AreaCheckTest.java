package kz.kazagrosat.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AreaCheckTest {

    private static final List<String> FIELDS = List.of(
            "id,land,area_ha,interior_ha,centroid_lat,centroid_lon,in_series",
            "F1,field,100.0,80.0,53.6,62.4,yes",
            "F2,field,40.0,30.0,53.6,62.4,yes",
            "F3,field,10.0,6.0,53.6,62.4,yes",
            "F4,fallow,20.0,15.0,53.6,62.4,yes",
            "F5,other,30.0,20.0,53.6,62.4,no");

    @Test
    void sharesAndSizesComeFromTheFieldsTable() {
        AreaCheck.Land land = AreaCheck.land(FIELDS);

        assertEquals(150.0 / 200, land.fieldShare(), 1e-9);
        assertEquals(20.0 / 200, land.fallowShare(), 1e-9);
        assertEquals(3, land.fields());
        assertEquals(40.0, land.medianFieldHa(), 1e-9);
        assertEquals(100.0, land.largestFieldHa(), 1e-9);
        assertEquals(100.0 / 150, land.areaInFieldsOver(50), 1e-9, "share of field area in fields over 50 ha");
    }

    @Test
    void theHarvestGapCountsDatesWithAValueAndIsTheMedianOverFields() {
        List<String> series = List.of(
                "field,date,clear_share,ndvi_p25,ndvi_median",
                "F1,2025-08-11,0.9,0.1,0.2",
                "F1,2025-08-21,0.9,0.1,0.2",
                "F2,2025-08-11,0.9,0.1,0.2",
                "F2,2025-08-21,0.3,,",
                "F3,2025-08-21,0.2,,");

        long gap = AreaCheck.harvestGap(series, LocalDate.parse("2025-08-01"), LocalDate.parse("2025-08-31"));

        assertEquals(20, gap, "F1 10 days, F2 20, F3 30");
    }
}
