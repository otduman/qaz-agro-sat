package kz.kazagrosat.pipeline.harvest;

import java.nio.file.Path;
import java.util.List;

/** What a harvest run found and wrote, for the command's own output. */
public record HarvestReport(int year, int fields, int dated, int cloudGap, int noCut, int unclear, int screenedDates,
                            List<Path> written) {}
