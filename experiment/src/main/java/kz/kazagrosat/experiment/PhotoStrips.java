package kz.kazagrosat.experiment;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/** Lays each field's dated photos out in one image, so changes can be read left to right. */
final class PhotoStrips {

    private static final int TILE = 190;
    private static final int GAP = 6;
    private static final int LABEL_HEIGHT = 18;
    private static final int PER_ROW = 6;
    private static final LocalDate FROM = LocalDate.of(2025, 7, 7);

    private PhotoStrips() {}

    static void write(Field field, Path photos, Path target) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(photos)) {
            files = listing
                    .filter(file -> file.getFileName().toString().startsWith(field.name() + "_"))
                    .filter(file -> !dateOf(file).isBefore(FROM))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            return;
        }
        int rows = (files.size() + PER_ROW - 1) / PER_ROW;
        int columns = Math.min(PER_ROW, files.size());
        int cell = TILE + LABEL_HEIGHT;
        BufferedImage strip = new BufferedImage(
                columns * (TILE + GAP) - GAP, rows * (cell + GAP) - GAP, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        for (int i = 0; i < files.size(); i++) {
            BufferedImage photo = ImageIO.read(files.get(i).toFile());
            int x = (i % PER_ROW) * (TILE + GAP);
            int y = (i / PER_ROW) * (cell + GAP);
            double scale = Math.min((double) TILE / photo.getWidth(), (double) TILE / photo.getHeight());
            int width = (int) (photo.getWidth() * scale);
            int height = (int) (photo.getHeight() * scale);
            g.drawImage(photo, x + (TILE - width) / 2, y + LABEL_HEIGHT + (TILE - height) / 2, width, height, null);
            g.setColor(Color.BLACK);
            g.drawString(dateOf(files.get(i)).toString().substring(5), x + 2, y + 13);
        }
        g.dispose();
        Files.createDirectories(target.getParent());
        ImageIO.write(strip, "jpg", target.toFile());
    }

    private static LocalDate dateOf(Path photo) {
        String name = photo.getFileName().toString();
        return LocalDate.parse(name.substring(name.indexOf('_') + 1, name.lastIndexOf('.')));
    }
}
