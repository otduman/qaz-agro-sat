package kz.kazagrosat.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One round of the experiment: a set of boxes over one season, with the photo readings that judge it.
 * Rounds are listed in experiment/runs.csv and each gets its own folder of results.
 *
 * @param readings may not exist yet; until it does, the rule's results stay hidden so the photos can be
 *                 read without knowing what the rule says
 */
record Run(String name, String label, Study.Season season, List<Path> fieldFiles, Path readings) {

    static List<Run> load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<Run> runs = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split(",", 5);
            List<Path> fieldFiles = new ArrayList<>();
            for (String file : p[2].split(";")) {
                fieldFiles.add(Path.of(file));
            }
            String label = p[4].strip().replaceAll("^\"|\"$", "");
            runs.add(new Run(p[0], label, new Study.Season(Integer.parseInt(p[1])), fieldFiles, Path.of(p[3])));
        }
        return runs;
    }

    List<Field> fields() throws IOException {
        List<Field> fields = new ArrayList<>();
        for (Path file : fieldFiles) {
            fields.addAll(Field.load(file));
        }
        return fields;
    }

    boolean photosRead() {
        return Files.exists(readings);
    }

    Path folder(Path output) {
        return output.resolve(name);
    }
}
