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

        out.printf("%-4s %-33s %-15s %-21s %-14s %-21s %s%n",
                "box", "photos", "photo window", "SWIR jump", "result", "rule v2 jump", "rule v2 result");
        for (Field field : run.fields()) {
            Analysis.Reading reading = readings.get(field.name());
            if (reading == null) {
                out.printf("%-4s no photo reading%n", field.name());
                continue;
            }
            List<Analysis.OpticalRow> clear = Analysis.clearRows(optical, field.name());
            Optional<Analysis.Step> jump = Analysis.swirJump(clear);
            Optional<Analysis.Step> jumpV2 = Analysis.swirJumpV2(clear);
            String window = reading.hasWindow()
                    ? reading.from().toString().substring(5) + " to " + reading.to().toString().substring(5)
                    : "-";
            out.printf("%-4s %-33s %-15s %-21s %-14s %-21s %s%n", field.name(), reading.label(), window,
                    jump.map(AnalysisTable::describe).orElse("no usable dates"), Report.result(reading, jump),
                    jumpV2.map(AnalysisTable::describe).orElse("no usable dates"), Report.result(reading, jumpV2));
        }
    }

    private static String describe(Analysis.Step step) {
        return String.format(Locale.ROOT, "%+5.1f %s to %s", step.size(),
                step.before().toString().substring(5), step.after().toString().substring(5));
    }
}
