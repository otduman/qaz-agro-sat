package kz.kazagrosat.pipeline.stack;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.raster.PixelGrid;

/**
 * Draws a whole season on one picture: a small true-colour thumbnail per date, in date order, each
 * labelled with its date and clear share. Dates skipped as cloudy show as a grey square.
 */
public final class ContactSheet {

    static final int MAX_THUMB_WIDTH = 240;
    static final int COLUMNS = 6;
    static final int GAP = 6;
    static final int LABEL_HEIGHT = 18;
    static final int CELL_MIN_WIDTH = 150;
    /** Reflectance shown as full white; brighter pixels clip. Matches the experiment's photo strips. */
    static final double WHITE_POINT = 0.32;
    static final double GAMMA = 0.8;
    static final int CLOUDY_GREY = 0x808080;

    private ContactSheet() {}

    public static void write(SeasonStack stack, Path png) throws IOException, InterruptedException {
        PixelGrid grid = stack.area().grid();
        int step = (int) Math.ceil(grid.width() / (double) MAX_THUMB_WIDTH);
        int thumbWidth = (grid.width() + step - 1) / step;
        int thumbHeight = (grid.height() + step - 1) / step;
        int cellWidth = Math.max(thumbWidth, CELL_MIN_WIDTH);
        int cellHeight = thumbHeight + LABEL_HEIGHT;

        List<StackEntry> entries = stack.entries();
        int count = Math.max(1, entries.size());
        int columns = Math.min(COLUMNS, count);
        int rows = (count + COLUMNS - 1) / COLUMNS;
        BufferedImage sheet = new BufferedImage(GAP + columns * (cellWidth + GAP), GAP + rows * (cellHeight + GAP),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        for (int i = 0; i < entries.size(); i++) {
            StackEntry entry = entries.get(i);
            int left = GAP + (i % COLUMNS) * (cellWidth + GAP);
            int top = GAP + (i / COLUMNS) * (cellHeight + GAP);
            String label;
            if (entry.status() == StackEntry.Status.STORED) {
                BufferedImage thumb = thumbnail(stack, entry, grid, step, thumbWidth, thumbHeight);
                g.drawImage(thumb, left, top, null);
                label = String.format(Locale.ROOT, "%s  %.0f%% clear", entry.date(), entry.clearShare() * 100);
            } else {
                g.setColor(new Color(CLOUDY_GREY));
                g.fillRect(left, top, thumbWidth, thumbHeight);
                label = entry.date() + "  cloudy, bands skipped";
            }
            g.setColor(Color.DARK_GRAY);
            g.drawString(label, left, top + thumbHeight + LABEL_HEIGHT - 5);
        }
        g.dispose();
        ImageIO.write(sheet, "png", png.toFile());
    }

    /** Each thumbnail pixel is the mean reflectance of a step x step block, skipping pixels with no data. */
    private static BufferedImage thumbnail(SeasonStack stack, StackEntry entry, PixelGrid grid, int step,
                                           int width, int height) throws IOException, InterruptedException {
        float[] red = stack.reflectance(entry.date(), Layer.RED);
        float[] green = stack.reflectance(entry.date(), Layer.GREEN);
        float[] blue = stack.reflectance(entry.date(), Layer.BLUE);
        BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int ty = 0; ty < height; ty++) {
            for (int tx = 0; tx < width; tx++) {
                double r = 0;
                double gr = 0;
                double b = 0;
                int n = 0;
                for (int y = ty * step; y < Math.min(grid.height(), (ty + 1) * step); y++) {
                    for (int x = tx * step; x < Math.min(grid.width(), (tx + 1) * step); x++) {
                        int i = y * grid.width() + x;
                        if (!Float.isNaN(red[i]) && !Float.isNaN(green[i]) && !Float.isNaN(blue[i])) {
                            r += red[i];
                            gr += green[i];
                            b += blue[i];
                            n++;
                        }
                    }
                }
                int rgb = n == 0 ? 0 : stretch(r / n) << 16 | stretch(gr / n) << 8 | stretch(b / n);
                thumb.setRGB(tx, ty, rgb);
            }
        }
        return thumb;
    }

    private static int stretch(double reflectance) {
        double value = Math.clamp(reflectance / WHITE_POINT, 0.0, 1.0);
        return (int) Math.round(Math.pow(value, GAMMA) * 255);
    }
}
