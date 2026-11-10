package kz.kazagrosat.experiment;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import kz.kazagrosat.core.geo.BoundingBox;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

/** Draws the screened squares, shaded by cropland share, over the oblast outlines. */
final class AreaMap {

    private static final double WEST = 60;
    private static final double EAST = 75;
    private static final double SOUTH = 48;
    private static final double NORTH = 56;
    private static final double PX_PER_LON = 62;
    private static final double PX_PER_LAT = PX_PER_LON / Math.cos(Math.toRadians(52));
    private static final int MARGIN = 20;
    private static final int LEGEND = 60;
    private static final Color[] GREENS = {
        new Color(0xedf8e9), new Color(0xc7e9c0), new Color(0xa1d99b), new Color(0x74c476), new Color(0x31a354)
    };

    private AreaMap() {}

    static void write(Path file, Map<String, Geometry> oblasts, List<AreaScreen.Square> squares,
            Map<AreaScreen.Square, String> names) throws IOException {
        int width = (int) ((EAST - WEST) * PX_PER_LON) + 2 * MARGIN;
        int height = (int) ((NORTH - SOUTH) * PX_PER_LAT) + 2 * MARGIN + LEGEND;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        for (Map.Entry<String, Geometry> oblast : oblasts.entrySet()) {
            if (oblast.getKey().startsWith("other:")) {
                fill(g, oblast.getValue(), new Color(0xf3f3f3));
            }
        }
        for (AreaScreen.Square square : squares) {
            if (AreaScreen.OBLASTS.contains(square.oblast())) {
                int bin = Math.min(GREENS.length - 1, (int) (square.land().cropland() * GREENS.length));
                g.setColor(GREENS[bin]);
                g.fill(box(square.box()));
            }
        }
        g.setStroke(new BasicStroke(1f));
        for (Map.Entry<String, Geometry> oblast : oblasts.entrySet()) {
            boolean target = !oblast.getKey().startsWith("other:");
            g.setStroke(new BasicStroke(target ? 1.6f : 0.8f));
            outline(g, oblast.getValue(), target ? new Color(0x333333) : new Color(0xbbbbbb));
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        for (Map.Entry<AreaScreen.Square, String> named : names.entrySet()) {
            Path2D shape = box(named.getKey().box());
            boolean ours = named.getValue().equals("Ours");
            g.setStroke(new BasicStroke(2.5f));
            g.setColor(ours ? new Color(0xc0392b) : Color.BLACK);
            g.draw(shape);
            double x = shape.getBounds2D().getMaxX() + 3;
            double y = shape.getBounds2D().getMinY() + 11;
            g.setColor(Color.WHITE);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    g.drawString(named.getValue(), (float) x + dx, (float) y + dy);
                }
            }
            g.setColor(ours ? new Color(0xc0392b) : Color.BLACK);
            g.drawString(named.getValue(), (float) x, (float) y);
        }
        legend(g, height - LEGEND + 10);
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    private static void legend(Graphics2D g, int top) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int x = MARGIN;
        g.setColor(new Color(0x333333));
        g.drawString("Cropland share of each 20 x 20 km square (ESA WorldCover 2021):", x, top + 12);
        String[] labels = {"under 20%", "20-40%", "40-60%", "60-80%", "80% or more"};
        x = MARGIN;
        for (int i = 0; i < GREENS.length; i++) {
            g.setColor(GREENS[i]);
            g.fillRect(x, top + 22, 18, 14);
            g.setColor(new Color(0x999999));
            g.drawRect(x, top + 22, 18, 14);
            g.setColor(new Color(0x333333));
            g.drawString(labels[i], x + 24, top + 34);
            x += 115;
        }
        g.setColor(Color.BLACK);
        g.drawString("Black: measured for clouds.  Red: our area.", x + 10, top + 34);
    }

    private static double px(double lon) {
        return MARGIN + (lon - WEST) * PX_PER_LON;
    }

    private static double py(double lat) {
        return MARGIN + (NORTH - lat) * PX_PER_LAT;
    }

    private static Path2D box(BoundingBox box) {
        Path2D path = new Path2D.Double();
        path.moveTo(px(box.west()), py(box.north()));
        path.lineTo(px(box.east()), py(box.north()));
        path.lineTo(px(box.east()), py(box.south()));
        path.lineTo(px(box.west()), py(box.south()));
        path.closePath();
        return path;
    }

    private static Path2D shape(Geometry geometry) {
        Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Polygon polygon = (Polygon) geometry.getGeometryN(i);
            ring(path, polygon.getExteriorRing().getCoordinates());
            for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                ring(path, polygon.getInteriorRingN(h).getCoordinates());
            }
        }
        return path;
    }

    private static void ring(Path2D path, Coordinate[] points) {
        path.moveTo(px(points[0].x), py(points[0].y));
        for (int i = 1; i < points.length; i++) {
            path.lineTo(px(points[i].x), py(points[i].y));
        }
        path.closePath();
    }

    private static void fill(Graphics2D g, Geometry geometry, Color color) {
        g.setColor(color);
        g.fill(shape(geometry));
    }

    private static void outline(Graphics2D g, Geometry geometry, Color color) {
        g.setColor(color);
        g.draw(shape(geometry));
    }
}
