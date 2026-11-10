package kz.kazagrosat.pipeline.harvest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.export.TiledTiffWriter;
import kz.kazagrosat.core.harvest.HarvestDater;
import kz.kazagrosat.core.harvest.HarvestDater.FieldCut;
import kz.kazagrosat.core.harvest.HarvestDater.Status;
import kz.kazagrosat.core.harvest.Reading;
import kz.kazagrosat.core.raster.PixelGrid;
import kz.kazagrosat.core.raster.PixelWindow;
import kz.kazagrosat.pipeline.cog.CogReader;
import kz.kazagrosat.pipeline.cog.FileByteSource;

/**
 * The {@code harvest} step: every field of a series dated by {@link HarvestDater}, written as
 * <ul>
 *   <li>{@code harvest-<year>.csv}, one row per field-labelled region;</li>
 *   <li>{@code harvest-<year>.tif}, 16-bit, the day of year of each field's cut (its window's middle), 0 where none;</li>
 *   <li>{@code harvest-<year>.png}, the same coloured by date, grey for no cut, white for unclear;</li>
 *   <li>{@code summary-<year>.txt}, counts, screened dates and the volume checks.</li>
 * </ul>
 * Fallow regions are dated too, for the checks only: a cut found on fallow land is a false one.
 */
public final class Harvest {

    static final String HEADER = "field,land,area_ha,status,cut_from,cut_to,cut_middle,window_days,rise,readings";

    private Harvest() {}

    public static HarvestReport run(Path seriesFolder, Path fieldsFolder, Path out, Consumer<String> progress)
            throws IOException, InterruptedException {
        SeriesTable table = SeriesTable.read(seriesFolder);
        int year = table.year();
        Path labelFile = fieldsFolder.resolve("fields-" + year + ".tif");
        if (!Files.isRegularFile(labelFile)) {
            throw new IOException("No fields-" + year + ".tif in " + fieldsFolder + ". Give the fields folder of the "
                    + year + " season, the one the series was built from.");
        }
        CogReader reader = CogReader.open(new FileByteSource(labelFile));
        PixelGrid grid = reader.grid();
        int[] labels = reader.readWindow(new PixelWindow(0, 0, grid.width(), grid.height()));

        Map<String, SeriesTable.Field> dated = new LinkedHashMap<>();
        Map<String, List<Reading>> rows = new LinkedHashMap<>();
        for (SeriesTable.Field f : table.fields()) {
            if (f.inSeries() && (f.land().equals("field") || f.land().equals("fallow"))) {
                dated.put(f.id(), f);
                rows.put(f.id(), table.readings().getOrDefault(f.id(), List.of()));
            }
        }
        progress.accept("Dating " + rows.size() + " field and fallow regions of " + year + "...");
        HarvestDater.Result full = HarvestDater.date(rows, HarvestDater.Options.FULL);
        HarvestDater.Result sentinelOnly = HarvestDater.date(rows, new HarvestDater.Options(false, true));
        Map<String, FieldCut> alone = new HashMap<>();
        sentinelOnly.cuts().forEach(c -> alone.put(c.field(), c));

        List<FieldCut> fieldCuts = new ArrayList<>();
        List<FieldCut> fallowCuts = new ArrayList<>();
        for (FieldCut c : full.cuts()) {
            (dated.get(c.field()).land().equals("field") ? fieldCuts : fallowCuts).add(c);
        }

        Files.createDirectories(out);
        List<Path> written = new ArrayList<>();
        written.add(writeCsv(out.resolve("harvest-" + year + ".csv"), fieldCuts, dated));
        int[] days = new int[labels.length];
        int[] argb = new int[labels.length];
        Map<Integer, FieldCut> byRegion = new HashMap<>();
        fieldCuts.forEach(c -> byRegion.put(dated.get(c.field()).region(), c));
        for (int i = 0; i < labels.length; i++) {
            FieldCut c = byRegion.get(labels[i]);
            days[i] = c != null && c.middle() != null ? c.middle().getDayOfYear() : 0;
            argb[i] = c == null ? 0 : colour(c, year);
        }
        Path tif = out.resolve("harvest-" + year + ".tif");
        TiledTiffWriter.write(tif, days, grid, 16, 0);
        written.add(tif);
        Path png = out.resolve("harvest-" + year + ".png");
        BufferedImage image = new BufferedImage(grid.width(), grid.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, grid.width(), grid.height(), argb, 0, grid.width());
        if (!ImageIO.write(image, "png", png.toFile())) {
            throw new IOException("No PNG writer is available");
        }
        written.add(png);
        Path summary = out.resolve("summary-" + year + ".txt");
        Files.writeString(summary, summary(year, fieldCuts, fallowCuts, alone, full.screened(), dated, rows),
                StandardCharsets.UTF_8);
        written.add(summary);

        return new HarvestReport(year, fieldCuts.size(), count(fieldCuts, Status.DATED),
                count(fieldCuts, Status.CLOUD_GAP), count(fieldCuts, Status.NO_CUT), count(fieldCuts, Status.UNCLEAR),
                full.screened().size(), List.copyOf(written));
    }

    private static int count(List<FieldCut> cuts, Status status) {
        return (int) cuts.stream().filter(c -> c.status() == status).count();
    }

    private static Path writeCsv(Path file, List<FieldCut> cuts, Map<String, SeriesTable.Field> fields)
            throws IOException {
        List<String> lines = new ArrayList<>(List.of(HEADER));
        for (FieldCut c : cuts) {
            SeriesTable.Field f = fields.get(c.field());
            boolean window = c.from() != null;
            lines.add(String.join(",", c.field(), f.land(), String.format(Locale.ROOT, "%.2f", f.areaHectares()),
                    c.status().label(), window ? c.from().toString() : "", window ? c.to().toString() : "",
                    window ? c.middle().toString() : "", window ? Integer.toString(c.windowDays()) : "",
                    Double.isNaN(c.rise()) || c.status() == Status.UNCLEAR ? ""
                            : String.format(Locale.ROOT, "%.2f", c.rise()),
                    Integer.toString(c.readings())));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    /** Early cuts pale yellow through to late cuts deep blue; cloud gaps paler; no cut grey; unclear white. */
    static int colour(FieldCut c, int year) {
        return switch (c.status()) {
            case NO_CUT -> 0xFF8C8C8C;
            case UNCLEAR -> 0xFFFFFFFF;
            case DATED, CLOUD_GAP -> {
                LocalDate first = LocalDate.of(year, 8, 1);
                double t = ChronoUnit.DAYS.between(first, c.middle()) / 91.0;
                t = Math.max(0, Math.min(1, t));
                int r = (int) Math.round(240 + t * (30 - 240));
                int g = (int) Math.round(215 + t * (70 - 215));
                int b = (int) Math.round(90 + t * (150 - 90));
                if (c.status() == Status.CLOUD_GAP) {
                    r = (r + 255) / 2;
                    g = (g + 255) / 2;
                    b = (b + 255) / 2;
                }
                yield 0xFF000000 | r << 16 | g << 8 | b;
            }
        };
    }

    /** Fallow that never greened above this NDVI from August is a control: it has nothing to cut. */
    static final double BARE_NDVI = 0.3;

    private static boolean bare(List<Reading> readings) {
        return readings.stream().filter(r -> r.date().getMonthValue() >= 8 && r.clear() >= 0.85)
                .allMatch(r -> !(r.ndvi() > BARE_NDVI));
    }

    static String summary(int year, List<FieldCut> fields, List<FieldCut> fallow, Map<String, FieldCut> alone,
                          Map<LocalDate, String> screened, Map<String, SeriesTable.Field> info,
                          Map<String, List<Reading>> rows) {
        StringBuilder s = new StringBuilder();
        line(s, "Season %d: %d field regions dated by the frozen cut rule (Sentinel-2 and Landsat, late-photo screen)",
                year, fields.size());
        for (Status status : Status.values()) {
            List<FieldCut> these = fields.stream().filter(c -> c.status() == status).toList();
            line(s, "  %s: %d fields, %.0f ha", status.label(), these.size(), area(these, info));
        }
        List<FieldCut> datedOnly = fields.stream().filter(c -> c.status() == Status.DATED).toList();
        if (datedOnly.isEmpty()) {
            line(s, "No field dated.");
        } else {
            line(s, "10%% of the dated area cut by: %s", byShare(datedOnly, info, 0.1));
            line(s, "Half of the dated area cut by: %s", byShare(datedOnly, info, 0.5));
            line(s, "90%% of the dated area cut by: %s", byShare(datedOnly, info, 0.9));
        }
        if (screened.isEmpty()) {
            line(s, "Screened dates: none");
        } else {
            line(s, "Screened dates: %d", screened.size());
            screened.forEach((d, why) -> line(s, "  %s: %s", d, why));
        }
        line(s, "");
        line(s, "Checks");
        List<FieldCut> bare = fallow.stream().filter(c -> bare(rows.get(c.field()))).toList();
        List<FieldCut> greened = fallow.stream().filter(c -> !bare(rows.get(c.field()))).toList();
        line(s, "  Bare fallow (NDVI never above %.1f from August) with a cut found: %d of %d", BARE_NDVI,
                bare.stream().filter(c -> c.from() != null).count(), bare.size());
        line(s, "  Other fallow (green at some point from August) with a cut found: %d of %d",
                greened.stream().filter(c -> c.from() != null).count(), greened.size());
        int both = 0;
        int agree = 0;
        for (FieldCut c : fields) {
            FieldCut a = alone.get(c.field());
            if (c.from() != null && a != null && a.from() != null) {
                both++;
                if (!c.to().isBefore(a.from()) && !a.to().isBefore(c.from())) {
                    agree++;
                }
            }
        }
        line(s, "  Sentinel-2 alone agrees: %d of %d fields cut in both (windows overlap or touch)", agree, both);
        TreeMap<LocalDate, Double> weeks = new TreeMap<>();
        for (FieldCut c : datedOnly) {
            LocalDate monday = c.middle().minusDays(c.middle().getDayOfWeek().getValue() - 1L);
            weeks.merge(monday, info.get(c.field()).areaHectares(), Double::sum);
        }
        line(s, "  Dated area by week (week starting, ha):");
        weeks.forEach((w, ha) -> line(s, "    %s  %.0f", w, ha));
        return s.toString();
    }

    private static double area(List<FieldCut> cuts, Map<String, SeriesTable.Field> info) {
        return cuts.stream().mapToDouble(c -> info.get(c.field()).areaHectares()).sum();
    }

    /** The middle date by which this share of the cuts' area was cut. */
    static LocalDate byShare(List<FieldCut> cuts, Map<String, SeriesTable.Field> info, double share) {
        List<FieldCut> sorted = cuts.stream().sorted((a, b) -> a.middle().compareTo(b.middle())).toList();
        double total = area(sorted, info);
        double run = 0;
        for (FieldCut c : sorted) {
            run += info.get(c.field()).areaHectares();
            if (run >= share * total - 1e-9) {
                return c.middle();
            }
        }
        return sorted.isEmpty() ? null : sorted.get(sorted.size() - 1).middle();
    }

    private static void line(StringBuilder s, String format, Object... values) {
        s.append(String.format(Locale.ROOT, format, values)).append(System.lineSeparator());
    }
}
