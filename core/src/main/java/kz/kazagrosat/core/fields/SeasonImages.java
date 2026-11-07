package kz.kazagrosat.core.fields;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import kz.kazagrosat.core.raster.PixelGrid;

/** A season's images, read one date at a time so a whole season never sits in memory. */
public interface SeasonImages {

    PixelGrid grid();

    /** The dates with images, in order. */
    List<LocalDate> dates();

    DateImage read(LocalDate date) throws IOException, InterruptedException;
}
