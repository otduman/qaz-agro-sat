package kz.kazagrosat.experiment;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import kz.kazagrosat.pipeline.fields.FieldPictures;
import kz.kazagrosat.pipeline.stack.ContactSheet;
import kz.kazagrosat.pipeline.stack.Layer;
import kz.kazagrosat.pipeline.stack.SeasonStack;

/**
 * Plain true-colour photos of a season, no outlines: the clearest stored date in each of four windows (sowing, peak
 * green, harvest, after harvest). Throwaway; run from the repository root:
 * {@code java -cp experiment/target/harvest-signal.jar kz.kazagrosat.experiment.SeasonPhotos data/kokshetau/season-2025 out}.
 */
public final class SeasonPhotos {

    private record Window(String name, MonthDay from, MonthDay to) {}

    private static final List<Window> WINDOWS = List.of(
            new Window("sowing", MonthDay.of(5, 15), MonthDay.of(6, 15)),
            new Window("peak", MonthDay.of(7, 5), MonthDay.of(8, 5)),
            new Window("harvest", MonthDay.of(8, 20), MonthDay.of(9, 10)),
            new Window("after", MonthDay.of(9, 15), MonthDay.of(10, 20)));

    private SeasonPhotos() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: SeasonPhotos <stackFolder> <outFolder>");
            System.exit(2);
        }
        SeasonStack stack = SeasonStack.open(Path.of(args[0]));
        Path out = Files.createDirectories(Path.of(args[1]));
        for (Window window : WINDOWS) {
            Optional<LocalDate> date = FieldPictures.clearestIn(stack.entries(), window.from(), window.to());
            if (date.isEmpty()) {
                System.out.println(window.name() + ": no stored date");
                continue;
            }
            float[] red = stack.reflectance(date.get(), Layer.RED);
            float[] green = stack.reflectance(date.get(), Layer.GREEN);
            float[] blue = stack.reflectance(date.get(), Layer.BLUE);
            int width = stack.area().grid().width();
            int height = red.length / width;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < red.length; i++) {
                boolean missing = Float.isNaN(red[i]) || Float.isNaN(green[i]) || Float.isNaN(blue[i]);
                int rgb = missing ? 0 : ContactSheet.stretch(red[i]) << 16 | ContactSheet.stretch(green[i]) << 8
                        | ContactSheet.stretch(blue[i]);
                image.setRGB(i % width, i / width, rgb);
            }
            Path file = out.resolve(date.get() + "-" + window.name() + ".png");
            ImageIO.write(image, "png", file.toFile());
            double clear = stack.entries().stream().filter(e -> e.date().equals(date.get())).findFirst()
                    .orElseThrow().clearShare();
            System.out.printf("%s: %s, %.0f%% clear%n", window.name(), date.get(), 100 * clear);
        }
    }
}
