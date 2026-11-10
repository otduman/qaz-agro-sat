package kz.kazagrosat.pipeline.harvest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.kazagrosat.core.harvest.Reading;

/**
 * A series folder as the {@code series} command writes it ({@code fields-<year>.csv} and
 * {@code series-<year>.csv}), read back as the cut rule needs it.
 *
 * @param readings every field's rows with values, by field id; fields without any have an empty list
 */
public record SeriesTable(int year, List<Field> fields, Map<String, List<Reading>> readings) {

    /** One region of the field map, as listed in {@code fields-<year>.csv}. */
    public record Field(String id, String land, double areaHectares, boolean inSeries) {

        /** The region number in the field map: {@code 2025-F0012} is 12. */
        public int region() {
            return Integer.parseInt(id.substring(id.indexOf("-F") + 2));
        }
    }

    public static SeriesTable read(Path folder) throws IOException {
        int year = year(folder);
        List<String> fieldLines = Files.readAllLines(folder.resolve("fields-" + year + ".csv"), StandardCharsets.UTF_8);
        List<String> head = List.of(fieldLines.get(0).replace("﻿", "").split(","));
        List<Field> fields = new ArrayList<>();
        Map<String, List<Reading>> readings = new LinkedHashMap<>();
        for (String line : fieldLines.subList(1, fieldLines.size())) {
            String[] c = line.split(",", -1);
            Field field = new Field(c[head.indexOf("id")], c[head.indexOf("land")],
                    Double.parseDouble(c[head.indexOf("area_ha")]), c[head.indexOf("in_series")].equals("yes"));
            fields.add(field);
            readings.put(field.id(), new ArrayList<>());
        }
        try (var lines = Files.lines(folder.resolve("series-" + year + ".csv"), StandardCharsets.UTF_8)) {
            var it = lines.iterator();
            List<String> h = List.of(it.next().replace("﻿", "").split(","));
            int clear = h.indexOf("clear_share");
            int ndvi = h.indexOf("ndvi_median");
            int b11 = h.indexOf("b11");
            int blue = h.indexOf("blue");
            int snow = h.indexOf("snow_share");
            int satellite = h.indexOf("satellite");
            while (it.hasNext()) {
                String[] c = it.next().split(",", -1);
                if (c[ndvi].isEmpty() || c[b11].isEmpty()) {
                    continue;
                }
                String sat = satellite < 0 || c[satellite].isEmpty() ? "S2" : c[satellite];
                readings.computeIfAbsent(c[0], k -> new ArrayList<>()).add(new Reading(LocalDate.parse(c[1]), sat,
                        Double.parseDouble(c[clear]), Double.parseDouble(c[ndvi]), Double.parseDouble(c[b11]),
                        number(c, blue), snow < 0 || c[snow].isEmpty() ? 0 : Double.parseDouble(c[snow])));
            }
        }
        return new SeriesTable(year, List.copyOf(fields), readings);
    }

    private static double number(String[] cells, int index) {
        return index < 0 || cells[index].isEmpty() ? Double.NaN : Double.parseDouble(cells[index]);
    }

    private static int year(Path folder) throws IOException {
        List<Integer> years = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "series-*.csv")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    years.add(Integer.parseInt(name.substring("series-".length(), name.length() - ".csv".length())));
                }
            }
        }
        if (years.size() != 1) {
            throw new IOException(folder + " should hold one series-<year>.csv, as the series command writes it; found "
                    + years.size() + ".");
        }
        return years.get(0);
    }
}
