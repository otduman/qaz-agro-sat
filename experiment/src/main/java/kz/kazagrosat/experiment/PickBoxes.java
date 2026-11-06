package kz.kazagrosat.experiment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Picks boxes for a blind test by a fixed rule instead of by eye. A crop box must be evenly green on
 * the July NDVI map; a control box must be evenly bare. A fixed seed makes the choice repeatable.
 *
 * <p>Usage: {@code java -cp harvest-signal.jar kz.kazagrosat.experiment.PickBoxes NDVI.tif OLD.csv OUT.csv}
 * where the NDVI file comes from the Phase 1 {@code ndvi} command for 12 July 2025.
 */
final class PickBoxes {

    private static final int SIZE = 60;
    private static final int STEP = 30;
    private static final int EDGE = 45;
    private static final int MIN_DISTANCE = 160;
    private static final int OLD_BOX_MARGIN = 40;
    private static final int CROPS = 20;
    private static final int CONTROLS = 4;
    private static final long SEED = 2025;

    private record Window(int left, int top, double mean, double spread) {}

    private PickBoxes() {}

    public static void main(String[] args) throws IOException {
        byte[] file = Files.readAllBytes(Path.of(args[0]));
        ByteBuffer tiff = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int width = tag(tiff, 256);
        int height = tag(tiff, 257);
        int start = tag(tiff, 273);
        float[] ndvi = new float[width * height];
        tiff.position(start);
        tiff.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(ndvi);
        List<Field> old = Field.load(Path.of(args[1]));

        List<Window> crops = new ArrayList<>();
        List<Window> controls = new ArrayList<>();
        for (int top = EDGE; top + SIZE <= height - EDGE; top += STEP) {
            for (int left = EDGE; left + SIZE <= width - EDGE; left += STEP) {
                Window w = measure(ndvi, width, left, top);
                if (w == null || overlapsOld(w, old)) {
                    continue;
                }
                if (w.mean() >= 0.70 && w.spread() <= 0.04) {
                    crops.add(w);
                } else if (w.mean() <= 0.22 && w.spread() <= 0.03) {
                    controls.add(w);
                }
            }
        }
        Random random = new Random(SEED);
        Collections.shuffle(crops, random);
        Collections.shuffle(controls, random);
        List<Window> picked = new ArrayList<>();
        take(crops, CROPS, picked);
        int cropCount = picked.size();
        take(controls, CONTROLS, picked);

        List<String> lines = new ArrayList<>(List.of("name,kind,left,top,right,bottom"));
        for (int i = 0; i < picked.size(); i++) {
            Window w = picked.get(i);
            boolean crop = i < cropCount;
            String name = crop ? String.format("B%02d", i + 1) : String.format("D%02d", i - cropCount + 1);
            lines.add(name + "," + (crop ? "crop" : "bare control") + "," + w.left() + "," + w.top() + ","
                    + (w.left() + SIZE) + "," + (w.top() + SIZE));
        }
        Files.write(Path.of(args[2]), lines);
        System.out.println("candidates: " + crops.size() + " crop, " + controls.size() + " bare; picked "
                + cropCount + " crop and " + (picked.size() - cropCount) + " bare");
    }

    private static void take(List<Window> candidates, int count, List<Window> picked) {
        for (Window w : candidates) {
            boolean farEnough = picked.stream().allMatch(p ->
                    Math.hypot(p.left() - w.left(), p.top() - w.top()) >= MIN_DISTANCE);
            if (farEnough) {
                picked.add(w);
                count--;
                if (count == 0) {
                    return;
                }
            }
        }
    }

    private static Window measure(float[] ndvi, int width, int left, int top) {
        double sum = 0;
        double squares = 0;
        int count = 0;
        int missing = 0;
        for (int y = top; y < top + SIZE; y++) {
            for (int x = left; x < left + SIZE; x++) {
                float v = ndvi[y * width + x];
                if (Float.isNaN(v)) {
                    missing++;
                } else {
                    sum += v;
                    squares += v * v;
                    count++;
                }
            }
        }
        if (missing > SIZE * SIZE * 0.02) {
            return null;
        }
        double mean = sum / count;
        return new Window(left, top, mean, Math.sqrt(Math.max(0, squares / count - mean * mean)));
    }

    private static boolean overlapsOld(Window w, List<Field> old) {
        for (Field f : old) {
            boolean apart = w.left() + SIZE + OLD_BOX_MARGIN <= f.left() || f.right() + OLD_BOX_MARGIN <= w.left()
                    || w.top() + SIZE + OLD_BOX_MARGIN <= f.top() || f.bottom() + OLD_BOX_MARGIN <= w.top();
            if (!apart) {
                return true;
            }
        }
        return false;
    }

    /** Reads a one-value SHORT or LONG tag from the first image directory. */
    private static int tag(ByteBuffer tiff, int wanted) throws IOException {
        int directory = tiff.getInt(4);
        int entries = Short.toUnsignedInt(tiff.getShort(directory));
        for (int i = 0; i < entries; i++) {
            int at = directory + 2 + i * 12;
            if (Short.toUnsignedInt(tiff.getShort(at)) == wanted) {
                int type = Short.toUnsignedInt(tiff.getShort(at + 2));
                return type == 3 ? Short.toUnsignedInt(tiff.getShort(at + 8)) : tiff.getInt(at + 8);
            }
        }
        throw new IOException("No TIFF tag " + wanted);
    }
}
