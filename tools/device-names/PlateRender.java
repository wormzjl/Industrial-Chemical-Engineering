package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * N1 nameplate looks (batch 2026-11-02-process-simulator), adapted from tools/column-sump/ColumnRender.java: the real
 * shell cell polygons, the mod's sprites and shade, a depth buffer; the plate {@link Nameplate} places, drawn after the
 * model as an affine image (an orthographic view keeps a rectangle a parallelogram) in the looks of {@code LOOKS}. Args
 * {@code out.png pxPerMetre [close]} (close: a 4 by 3 m window round each plate). Run from the worktree root (it reads src/main/resources/assets/icheme/).
 */
public class PlateRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";
    /** The looks: a light plate with dark text, a stencil on the shell, a dark plate with light text. */
    private static final String[] LOOKS = {"P1 light plate", "P2 stencil", "P3 dark plate"};
    /** The last render's projection: right, up, depth, its origin and its scale. */
    private static double[] pr, pu, pd;
    private static double pfx, pfy, pscale;
    private static int ph;

    private record Triangle(double[][] p, double[][] uv, double[] normal, Mesh.Material material) {
    }

    private record Sprite(int width, int height, int[] argb) {
        int sample(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * width), width), y = Math.floorMod((int) Math.floor(v * height), height);
            return argb[y * width + x];
        }
    }

    private record Item(String name, EquipmentShape shape) {
    }

    public static void main(String[] args) throws Exception {
        String out = args[0];
        double scale = Double.parseDouble(args[1]);
        boolean crop = args.length > 2 && args[2].equals("close");
        var sprites = sprites();
        var items = List.of(
                new Item("V-101", EquipmentShape.drum(2, 4, Orientation.VERTICAL, EquipmentShape.Heading.NORTH, EquipmentShape.defaultSupport(2, Orientation.VERTICAL))),
                new Item("D-102", EquipmentShape.drum(2, 6, Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, EquipmentShape.defaultSupport(2, Orientation.HORIZONTAL))),
                new Item("E-101", EquipmentShape.designed(EquipmentShape.Kind.EXCHANGER, 1.5, 5, EquipmentShape.Heading.NORTH, 2, ProcessUnit.Bank.ONE, 0)),
                new Item("P-101", EquipmentShape.designed(EquipmentShape.Kind.PUMP, 0.35, 1, EquipmentShape.Heading.NORTH, 0, ProcessUnit.Bank.ONE, 0)),
                new Item("T-101", EquipmentShape.column(1.5, ColumnTrays.simple(10, 5), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(1.5))));
        double[] depth = unit(new double[]{.55, -.35, 1.15});
        var rows = new ArrayList<List<BufferedImage>>();
        for (int look = 0; look < LOOKS.length; look++) {
            var row = new ArrayList<BufferedImage>();
            for (var item : items) {
                var shape = item.shape();
                var triangles = new ArrayList<Triangle>();
                double[] diameters = new double[shape.nozzles().size()];
                for (int i = 0; i < diameters.length; i++) diameters[i] = PipeSizes.outsideOf(PipeSizes.code(shape.nozzles().get(i).defaultOd()));
                for (var cell : ShapeRasterizer.layout(shape).shell()) {
                    var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z(), ShapeRasterizer.cellCodes(shape, cell, diameters));
                    for (var polygon : ShapeRasterizer.surface(key)) add(triangles, polygon, cell.x(), cell.y(), cell.z());
                    for (var polygon : PortMarkings.surface(key)) add(triangles, polygon, cell.x(), cell.y(), cell.z());
                }
                var image = render(triangles, shape, depth, sprites, scale);
                var plate = Nameplate.of(shape);
                if (look == 0) System.out.println(item.name() + " " + shape.kind() + ": " + plate);
                int l = look;
                plate.ifPresent(p -> plate(image, p, item.name(), l));
                row.add(crop && plate.isPresent() ? around(image, plate.get(), scale) : image);
            }
            rows.add(row);
        }
        int caption = 22, gap = 14, width = gap;
        int[] widths = new int[items.size()];
        int rowHeight = 0;
        for (var row : rows)
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).getWidth());
                rowHeight = Math.max(rowHeight, row.get(i).getHeight());
            }
        for (int w : widths) width += w + gap;
        int height = rows.size() * (rowHeight + caption + gap) + gap;
        var sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0xEEF1F4));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        int y = gap;
        for (int r = 0; r < rows.size(); r++) {
            g.setColor(Color.DARK_GRAY);
            g.drawString(LOOKS[r], gap, y + 16);
            int x = gap;
            for (int i = 0; i < rows.get(r).size(); i++) {
                var image = rows.get(r).get(i);
                g.drawImage(image, x + (widths[i] - image.getWidth()) / 2, y + caption + rowHeight - image.getHeight(), null);
                x += widths[i] + gap;
            }
            y += rowHeight + caption + gap;
        }
        g.dispose();
        new File(out).getAbsoluteFile().getParentFile().mkdirs();
        ImageIO.write(sheet, "png", new File(out));
        System.out.println("wrote " + out);
    }

    /** {@code p} on {@code image} in look {@code look}, its text {@code name}; nothing where the plate faces away. */
    private static void plate(BufferedImage image, Nameplate.Plate p, String name, int look) {
        double[] out = {p.facing().dx, 0, p.facing().dz};
        if (dot(out, pd) >= 0) return;
        int[] r = p.right();
        double[] right = {r[0], 0, r[1]};
        int k = 200, w = (int) Math.round(p.width() * k), h = (int) Math.round(p.height() * k);
        var face = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = face.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color ink;
        switch (look) {
            case 0 -> {
                g.setColor(new Color(0xF2F2EC));
                g.fillRect(0, 0, w, h);
                g.setColor(new Color(0x23282C));
                g.setStroke(new BasicStroke(Math.max(2, h / 14f)));
                g.drawRect(h / 14, h / 14, w - h / 7, h - h / 7);
                ink = new Color(0x23282C);
            }
            case 1 -> ink = new Color(0x1C1F22);
            default -> {
                g.setColor(new Color(0x24313B));
                g.fillRect(0, 0, w, h);
                g.setColor(new Color(0xC9D2D8));
                g.setStroke(new BasicStroke(Math.max(2, h / 18f)));
                g.drawRect(h / 14, h / 14, w - h / 7, h - h / 7);
                ink = new Color(0xF4F6F7);
            }
        }
        // The text fills 80 % of the width, at most 64 % of the height (a stencil 90 %).
        float size = h * (look == 1 ? .9f : .64f);
        var font = new Font(Font.MONOSPACED, Font.BOLD, Math.round(size));
        var metrics = g.getFontMetrics(font);
        if (metrics.stringWidth(name) > w * .8) font = font.deriveFont(size * (float) (w * .8 / metrics.stringWidth(name)));
        g.setFont(font);
        metrics = g.getFontMetrics(font);
        g.setColor(ink);
        g.drawString(name, (w - metrics.stringWidth(name)) / 2, (h - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
        double[] c = {p.x(), p.y(), p.z()};
        double[] tl = screen(offset(c, right, -p.width() / 2, p.height() / 2)), tr = screen(offset(c, right, p.width() / 2, p.height() / 2)),
                bl = screen(offset(c, right, -p.width() / 2, -p.height() / 2));
        var at = new AffineTransform((tr[0] - tl[0]) / w, (tr[1] - tl[1]) / w, (bl[0] - tl[0]) / h, (bl[1] - tl[1]) / h, tl[0], tl[1]);
        var gi = image.createGraphics();
        gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gi.drawImage(face, at, null);
        gi.dispose();
    }

    /** A window 4 by 3 m round {@code p} on {@code image}. */
    private static BufferedImage around(BufferedImage image, Nameplate.Plate p, double scale) {
        double[] c = screen(new double[]{p.x(), p.y(), p.z()});
        int w = (int) (4 * scale), h = (int) (3 * scale), x = (int) Math.max(0, Math.min(image.getWidth() - w, c[0] - w / 2.0)), y = (int) Math.max(0, Math.min(image.getHeight() - h, c[1] - h / 2.0));
        return image.getSubimage(x, y, Math.min(w, image.getWidth()), Math.min(h, image.getHeight()));
    }

    private static double[] offset(double[] c, double[] right, double across, double up) {
        return new double[]{c[0] + right[0] * across, c[1] + up, c[2] + right[2] * across};
    }

    private static double[] screen(double[] v) {
        return new double[]{(dot(v, pr) - pfx) * pscale + 10, ph - ((dot(v, pu) - pfy) * pscale + 10)};
    }

    /** The sprites by material, from the shell model's texture names. */
    private static Map<Mesh.Material, Sprite> sprites() throws Exception {
        String json = Files.readString(Path.of(ASSETS + "models/block/equipment_shell.json"));
        var names = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"icheme:block/(\\w+)\"").matcher(json);
        var files = new HashMap<String, String>();
        while (names.find()) files.put(names.group(1), names.group(2));
        var out = new EnumMap<Mesh.Material, Sprite>(Mesh.Material.class);
        for (var material : Mesh.Material.values()) {
            String file = files.get(material.texture());
            if (file == null) continue;
            var image = ImageIO.read(new File(ASSETS + "textures/block/" + file + ".png"));
            int w = image.getWidth(), h = image.getHeight() > w && image.getHeight() % w == 0 ? w : image.getHeight();
            int[] argb = new int[w * h];
            image.getRGB(0, 0, w, h, argb, 0, w);
            out.put(material, new Sprite(w, h, argb));
        }
        return out;
    }

    private static void add(List<Triangle> out, Mesh.Polygon polygon, int x, int y, int z) {
        double[] p = polygon.positions(), uv = polygon.uvs();
        int n = polygon.size();
        for (int i = 1; i + 1 < n; i++) {
            int[] c = {0, i, i + 1};
            double[][] pts = new double[3][], uvs = new double[3][];
            for (int k = 0; k < 3; k++) {
                pts[k] = new double[]{p[3 * c[k]] + x, p[3 * c[k] + 1] + y, p[3 * c[k] + 2] + z};
                uvs[k] = new double[]{uv[2 * c[k]], uv[2 * c[k] + 1]};
            }
            var normal = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]));
            double l = Math.sqrt(dot(normal, normal));
            if (l < 1e-12) continue;
            out.add(new Triangle(pts, uvs, new double[]{normal[0] / l, normal[1] / l, normal[2] / l}, polygon.material()));
        }
    }

    private static double shade(double[] n) {
        return Math.min(n[0] * n[0] * .6 + n[1] * n[1] * ((3 + n[1]) / 4) + n[2] * n[2] * .8, 1);
    }

    private static BufferedImage render(List<Triangle> triangles, EquipmentShape shape, double[] depth, Map<Mesh.Material, Sprite> sprites, double scale) {
        double[] right = unit(Math.abs(depth[1]) > .99 ? new double[]{1, 0, 0} : cross(depth, new double[]{0, 1, 0}));
        double[] up = Math.abs(depth[1]) > .99 ? new double[]{0, 0, -1} : cross(right, depth);
        int gx0 = -2, gz0 = -2, gx1 = shape.sizeX() + 2, gz1 = shape.sizeZ() + 2;
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        var corners = new ArrayList<double[]>();
        for (var t : triangles) corners.addAll(Arrays.asList(t.p));
        for (int x : new int[]{gx0, gx1}) for (int z : new int[]{gz0, gz1}) corners.add(new double[]{x, 0, z});
        for (var v : corners) {
            minX = Math.min(minX, dot(v, right));
            maxX = Math.max(maxX, dot(v, right));
            minY = Math.min(minY, dot(v, up));
            maxY = Math.max(maxY, dot(v, up));
        }
        int w = (int) Math.ceil((maxX - minX) * scale) + 20, h = (int) Math.ceil((maxY - minY) * scale) + 20;
        pr = right;
        pu = up;
        pd = depth;
        pfx = minX;
        pfy = minY;
        pscale = scale;
        ph = h;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var zbuf = new double[w * h];
        Arrays.fill(zbuf, Double.MAX_VALUE);
        for (int i = 0; i < w * h; i++) image.setRGB(i % w, i / w, 0x9CC3F5);
        double fx = minX, fy = minY;
        Rasterizer raster = (a, b, c, colour) -> {
            double[][] s = new double[3][];
            int k = 0;
            for (var v : new double[][]{a, b, c}) s[k++] = new double[]{(dot(v, right) - fx) * scale + 10, h - ((dot(v, up) - fy) * scale + 10), dot(v, depth)};
            int x0 = (int) Math.max(0, Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0])))), x1 = (int) Math.min(w - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1])))), y1 = (int) Math.min(h - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
            double area = edge(s[0], s[1], s[2][0], s[2][1]);
            if (Math.abs(area) < 1e-12) return;
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    double px = x + .5, py = y + .5;
                    double w0 = edge(s[1], s[2], px, py) / area, w1 = edge(s[2], s[0], px, py) / area, w2 = edge(s[0], s[1], px, py) / area;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    double z = w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2];
                    if (z >= zbuf[y * w + x]) continue;
                    int rgb = colour.at(w0, w1, w2);
                    if (rgb < 0) continue;
                    zbuf[y * w + x] = z;
                    image.setRGB(x, y, rgb);
                }
        };
        for (int x = gx0; x < gx1; x++)
            for (int z = gz0; z < gz1; z++) {
                double[] a = {x, -.001, z}, b = {x + 1, -.001, z}, c = {x + 1, -.001, z + 1}, d = {x, -.001, z + 1};
                int grass = (x + z & 1) == 0 ? 0x6A9A3C : 0x64933A;
                raster.fill(a, b, c, (w0, w1, w2) -> grass);
                raster.fill(a, c, d, (w0, w1, w2) -> grass);
            }
        for (var t : triangles) {
            if (dot(t.normal, depth) >= 0) continue;
            var sprite = sprites.get(t.material);
            double light = shade(t.normal);
            int channel = t.material.channel();
            int tint = channel < 0 ? 0xFFFFFF : Paint.DEFAULT.colour(channel);
            double tr = Math.min(255, (tint >> 16 & 255) * 255.0 / Paint.SPRITE_VALUE) / 255, tg = Math.min(255, (tint >> 8 & 255) * 255.0 / Paint.SPRITE_VALUE) / 255,
                    tb = Math.min(255, (tint & 255) * 255.0 / Paint.SPRITE_VALUE) / 255;
            if (channel < 0) tr = tg = tb = 1;
            double ftr = tr, ftg = tg, ftb = tb;
            raster.fill(t.p[0], t.p[1], t.p[2], (w0, w1, w2) -> {
                int texel = 0xFFFF00FF;
                if (sprite != null) {
                    double u = w0 * t.uv[0][0] + w1 * t.uv[1][0] + w2 * t.uv[2][0], v = w0 * t.uv[0][1] + w1 * t.uv[1][1] + w2 * t.uv[2][1];
                    texel = sprite.sample(u, v);
                    if ((texel >>> 24) < 128) return -1;
                }
                int r = (int) Math.min(255, (texel >> 16 & 255) * ftr * light), g = (int) Math.min(255, (texel >> 8 & 255) * ftg * light),
                        b = (int) Math.min(255, (texel & 255) * ftb * light);
                return r << 16 | g << 8 | b;
            });
        }
        return image;
    }

    private interface Colour {
        int at(double w0, double w1, double w2);
    }

    private interface Rasterizer {
        void fill(double[] a, double[] b, double[] c, Colour colour);
    }

    private static double edge(double[] a, double[] b, double x, double y) {
        return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
