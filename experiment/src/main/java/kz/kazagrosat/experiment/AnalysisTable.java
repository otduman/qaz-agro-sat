package kz.kazagrosat.experiment;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Prints the rule results for every box of a round, so they can be read without opening the page. */
final class AnalysisTable {

    private AnalysisTable() {}

    static void print(Run run, Path folder, PrintStream out) throws IOException {
        out.println();
        out.println(run.label());
        if (!run.photosRead()) {
            out.println("  The photos are not read yet, so the rule's results stay hidden. Write "
                    + run.readings() + " first.");
            return;
        }
        List<Analysis.OpticalRow> optical = Analysis.loadOptical(folder.resolve("optical.csv"));
        List<Analysis.RadarRow> radar = Analysis.loadRadar(folder.resolve("radar.csv"));
        Map<String, Analysis.Reading> readings = Analysis.loadReadings(run.readings());

        out.printf("%-4s %-33s %-15s %-21s %-21s %s%n",
                "box", "photos", "photo window", "SWIR jump", "radar gap drop", "result");
        for (Field field : run.fields()) {
            Analysis.Reading reading = readings.get(field.name());
            if (reading == null) {
                out.printf("%-4s no photo reading%n", field.name());
                continue;
            }
            Optional<Analysis.Step> jump = Analysis.swirJump(Analysis.clearRows(optical, field.name()));
            Optional<Analysis.Step> drop = Analysis.radarDrop(
                    radar.stream().filter(row -> row.field().equals(field.name())).toList());
            String window = reading.hasWindow()
                    ? reading.from().toString().substring(5) + " to " + reading.to().toString().substring(5)
                    : "-";
            out.printf("%-4s %-33s %-15s %-21s %-21s %s%n", field.name(), reading.label(), window,
                    jump.map(AnalysisTable::describe).orElse("no usable dates"),
                    drop.map(AnalysisTable::describe).orElse("-"), Report.result(reading, jump));
        }
    }

    private static String describe(Analysis.Step step) {
        return String.format(Locale.ROOT, "%+5.1f %s to %s", step.size(),
                step.before().toString().substring(5), step.after().toString().substring(5));
    }
}
