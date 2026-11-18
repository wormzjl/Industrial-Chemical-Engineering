import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The tutorial's photographs as the game ships them (batch 2026-11-18-tutorial-photos): each picked photo cropped where
 * listed, scaled so its longest side is {@link #SIDE} px (bicubic), reduced to 256 colours (median cut, Floyd-Steinberg
 * dithering, both written here) and written as an indexed PNG; and the manifest {@code photos.json} with each PNG's size
 * and its thumbnail's focus.
 *
 * <pre>java PhotoAssets.java &lt;originals&gt; &lt;out textures dir&gt; &lt;manifest&gt; [side]</pre>
 */
public final class PhotoAssets {
    /** A photo: its id, its original's file, its crop in original pixels (null: none), its thumbnail's focus as shares of the cropped photo. */
    record Photo(String id, String file, int[] crop, double fx, double fy) {
    }

    static final List<Photo> PHOTOS = List.of(
            new Photo("welcome", "welcome.jpg", null, .45, .55),
            new Photo("crude", "crude.jpg", null, .5, .78),
            new Photo("crude_gas", "crude_gas.jpg", null, .5, .5),
            new Photo("crude_naphtha", "crude_naphtha.jpg", null, .5, .45),
            new Photo("crude_kerosene", "crude_kerosene.jpg", null, .5, .45),
            new Photo("crude_diesel", "crude_diesel.jpg", null, .5, .45),
            new Photo("crude_gas_oil", "crude_gas_oil.jpg", null, .5, .45),
            new Photo("crude_residue", "crude_residue.jpg", null, .5, .5),
            // The three stills alone: the wall's sign (it ends near x 710) and the dark shape at the bottom left are cut away.
            // The prototype's focus x .8 of 1280 (1024) is (1024 - 712) / 568 = .55 of the crop.
            new Photo("still", "still.jpg", new int[]{712, 0, 568, 854}, .55, .6),
            new Photo("tower", "tower.jpg", null, .5, .35),
            new Photo("simulator", "simulator.jpg", null, .45, .4),
            new Photo("unit", "unit.jpg", null, .5, .3),
            new Photo("building", "building_b8.jpg", null, .4, .35),
            new Photo("start", "start.jpg", null, .3, .6));

    /** 384 px (owner, 2026-11-18): at 512 px the 14 files came to 2.38 MB, at 448 px to 1.84 MB, over the 1.5 MB budget. */
    static int SIDE = 384;
    /**
     * The share of each pixel's error the dither carries on (1: plain Floyd-Steinberg). Tried at .75 and .5 on 2026-11-18:
     * the files shrank by under 2 %, the photos' own grain is what deflate cannot pack; kept at 1.
     */
    static float DAMP = 1f;

    public static void main(String[] args) throws Exception {
        var originals = Path.of(args[0]);
        var out = Path.of(args[1]);
        var manifest = Path.of(args[2]);
        if (args.length > 3) SIDE = Integer.parseInt(args[3]);
        if (args.length > 4) DAMP = Float.parseFloat(args[4]);
        Files.createDirectories(out);
        var json = new StringBuilder("{\n");
        long total = 0;
        for (int i = 0; i < PHOTOS.size(); i++) {
            var p = PHOTOS.get(i);
            var image = ImageIO.read(originals.resolve(p.file()).toFile());
            if (p.crop() != null) image = image.getSubimage(p.crop()[0], p.crop()[1], p.crop()[2], p.crop()[3]);
            var scaled = scale(image, SIDE);
            var indexed = quantise(scaled, 256);
            var file = out.resolve(p.id() + ".png");
            write(indexed, file);
            long bytes = Files.size(file);
            total += bytes;
            System.out.printf(Locale.ROOT, "%-15s %4d x %4d  %7d bytes%n", p.id(), indexed.getWidth(), indexed.getHeight(), bytes);
            json.append(String.format(Locale.ROOT, "  \"%s\": { \"w\": %d, \"h\": %d, \"fx\": %.2f, \"fy\": %.2f }%s%n", p.id(), indexed.getWidth(), indexed.getHeight(),
                    p.fx(), p.fy(), i + 1 < PHOTOS.size() ? "," : ""));
        }
        json.append("}\n");
        Files.writeString(manifest, json.toString());
        System.out.printf(Locale.ROOT, "total %d bytes%n", total);
    }

    /** {@code image} scaled so its longest side is {@code side} (bicubic, in two halving passes where large, so the bicubic filter does not skip pixels). */
    static BufferedImage scale(BufferedImage image, int side) {
        var current = rgb(image);
        int longest = Math.max(current.getWidth(), current.getHeight());
        while (longest / 2 >= side) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
            longest = Math.max(current.getWidth(), current.getHeight());
        }
        double s = side / (double) longest;
        return resize(current, (int) Math.round(current.getWidth() * s), (int) Math.round(current.getHeight() * s));
    }

    static BufferedImage rgb(BufferedImage image) {
        var out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    static BufferedImage resize(BufferedImage image, int w, int h) {
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** A box of pixel indices for the median cut: [from, to) of the shared pixel array. */
    record Box(int from, int to) {
    }

    /** {@code image} in at most {@code colours} colours: a median-cut palette, Floyd-Steinberg dithered. */
    static BufferedImage quantise(BufferedImage image, int colours) {
        int w = image.getWidth(), h = image.getHeight();
        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        int[] work = px.clone();
        var boxes = new ArrayList<Box>();
        boxes.add(new Box(0, work.length));
        while (boxes.size() < colours) {
            // Split the box with the widest channel range (weighted by its size), at its median on that channel.
            int best = -1, bestChannel = 0;
            long bestScore = 0;
            for (int b = 0; b < boxes.size(); b++) {
                var box = boxes.get(b);
                if (box.to() - box.from() < 2) continue;
                int[] range = range(work, box);
                for (int c = 0; c < 3; c++) {
                    long score = (long) range[c] * (long) Math.sqrt(box.to() - box.from());
                    if (score > bestScore) {
                        bestScore = score;
                        best = b;
                        bestChannel = c;
                    }
                }
            }
            if (best < 0) break;
            var box = boxes.remove(best);
            sort(work, box, bestChannel);
            int mid = (box.from() + box.to()) / 2;
            boxes.add(new Box(box.from(), mid));
            boxes.add(new Box(mid, box.to()));
        }
        int n = boxes.size();
        byte[] r = new byte[n], g = new byte[n], b = new byte[n];
        int[][] pal = new int[n][3];
        for (int i = 0; i < n; i++) {
            var box = boxes.get(i);
            long sr = 0, sg = 0, sb = 0;
            for (int k = box.from(); k < box.to(); k++) {
                sr += work[k] >> 16 & 255;
                sg += work[k] >> 8 & 255;
                sb += work[k] & 255;
            }
            int count = box.to() - box.from();
            pal[i] = new int[]{(int) (sr / count), (int) (sg / count), (int) (sb / count)};
            r[i] = (byte) pal[i][0];
            g[i] = (byte) pal[i][1];
            b[i] = (byte) pal[i][2];
        }
        var model = new IndexColorModel(8, n, r, g, b);
        var out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, model);
        var raster = out.getRaster();
        // Floyd-Steinberg: each pixel's error spread 7/16 right, 3/16 down left, 5/16 down, 1/16 down right.
        float[][] err = new float[2][(w + 2) * 3];
        int[] cache = new int[1 << 18];
        Arrays.fill(cache, -1);
        for (int y = 0; y < h; y++) {
            float[] cur = err[y & 1], next = err[(y + 1) & 1];
            Arrays.fill(next, 0);
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int cr = clamp((p >> 16 & 255) + cur[(x + 1) * 3]), cg = clamp((p >> 8 & 255) + cur[(x + 1) * 3 + 1]), cb = clamp((p & 255) + cur[(x + 1) * 3 + 2]);
                int key = (cr >> 2) << 12 | (cg >> 2) << 6 | (cb >> 2);
                int idx = cache[key];
                if (idx < 0) cache[key] = idx = nearest(pal, (cr & ~3) + 2, (cg & ~3) + 2, (cb & ~3) + 2);
                raster.setSample(x, y, 0, idx);
                float er = (cr - pal[idx][0]) * DAMP, eg = (cg - pal[idx][1]) * DAMP, eb = (cb - pal[idx][2]) * DAMP;
                spread(cur, x + 2, er, eg, eb, 7 / 16f);
                spread(next, x, er, eg, eb, 3 / 16f);
                spread(next, x + 1, er, eg, eb, 5 / 16f);
                spread(next, x + 2, er, eg, eb, 1 / 16f);
            }
        }
        return out;
    }

    static void spread(float[] row, int slot, float er, float eg, float eb, float share) {
        if (slot * 3 + 2 >= row.length) return;
        row[slot * 3] += er * share;
        row[slot * 3 + 1] += eg * share;
        row[slot * 3 + 2] += eb * share;
    }

    static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    static int nearest(int[][] pal, int r, int g, int b) {
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < pal.length; i++) {
            int dr = r - pal[i][0], dg = g - pal[i][1], db = b - pal[i][2];
            // Weighted by the eye's sensitivity, roughly: green most, blue least.
            int d = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    static int[] range(int[] work, Box box) {
        int[] lo = {255, 255, 255}, hi = {0, 0, 0};
        for (int k = box.from(); k < box.to(); k++)
            for (int c = 0; c < 3; c++) {
                int v = work[k] >> (16 - 8 * c) & 255;
                lo[c] = Math.min(lo[c], v);
                hi[c] = Math.max(hi[c], v);
            }
        return new int[]{hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]};
    }

    static void sort(int[] work, Box box, int channel) {
        int shift = 16 - 8 * channel;
        // Counting sort on the channel: stable, linear.
        int[] count = new int[257];
        for (int k = box.from(); k < box.to(); k++) count[(work[k] >> shift & 255) + 1]++;
        for (int i = 0; i < 256; i++) count[i + 1] += count[i];
        int[] tmp = new int[box.to() - box.from()];
        for (int k = box.from(); k < box.to(); k++) tmp[count[work[k] >> shift & 255]++] = work[k];
        System.arraycopy(tmp, 0, work, box.from(), tmp.length);
    }

    /** Writes {@code image} as a PNG at the writer's strongest compression. */
    static void write(BufferedImage image, Path file) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        var param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0f);
        }
        Files.deleteIfExists(file);
        try (var stream = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
