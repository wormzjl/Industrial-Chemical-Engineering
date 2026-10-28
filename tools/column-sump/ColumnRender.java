package com.wormzjl.icheme.science.equipment;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Column sump scratch renderer (batch 2026-10-27-column-sump), adapted from
 * tools/multi-machine-framework/w3-arrows/ArrowRender.java: a column's real shell cell polygons, the mod's sprites, the
 * game's directional shade, culled back faces, a depth buffer, a one-block pipe stub at every port. Every tile of a
 * sheet is drawn at one scale, bottoms aligned, so heights compare.
 *
 * <p>Args: {@code out.png pxPerMetre view spec...}; view front, back, left, right, iso, iso3 (back left), low; a spec
 * {@code diameter:trays:config[:skirt]}, config as NewSweep's (kettle, steam+draw, kettle+steam+draw+PA, full). Run from
 * the worktree root: it reads src/main/resources/assets/icheme/.
 */
public class ColumnRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";

    private record Triangle(double[][] p, double[][] uv, double[] normal, Mesh.Material material) {
    }

    private record Sprite(int width, int height, int[] argb) {
        int sample(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * width), width), y = Math.floorMod((int) Math.floor(v * height), height);
            return argb[y * width + x];
        }
    }

    private record Tile(BufferedImage image, String caption, String detail) {
    }

    public static void main(String[] args) throws Exception {
        String out = args[0];
        double scale = Double.parseDouble(args[1]);
        String view = args[2];
        var sprites = sprites();
        var tiles = new ArrayList<Tile>();
        for (int a = 3; a < args.length; a++) {
            var spec = args[a].split(":");
            double d = Double.parseDouble(spec[0]);
            int n = Integer.parseInt(spec[1]);
            var trays = NewSweep.trays(n, spec[2]);
            int skirt = spec.length > 3 ? Integer.parseInt(spec[3]) : EquipmentShape.minimumSkirt(d);
            var shape = EquipmentShape.column(d, trays, EquipmentShape.Heading.NORTH, skirt);
            var misfit = ColumnDesign.misfit(shape);
            var triangles = new ArrayList<Triangle>();
            double[] diameters = new double[shape.nozzles().size()];
            for (int i = 0; i < diameters.length; i++) diameters[i] = PipeSizes.outsideOf(PipeSizes.code(shape.nozzles().get(i).defaultOd()));
            for (var cell : ShapeRasterizer.layout(shape).shell()) {
                var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z(), ShapeRasterizer.cellCodes(shape, cell, diameters));
                for (var polygon : ShapeRasterizer.surface(key)) add(triangles, polygon, cell.x(), cell.y(), cell.z());
                for (var polygon : PortMarkings.surface(key)) add(triangles, polygon, cell.x(), cell.y(), cell.z());
            }
            for (var port : ShapeRasterizer.ports(shape)) {
                int face = 0;
                for (int i = 0; i < 6; i++)
                    if (LineGeometry.STEPS[i][0] == -port.dx() && LineGeometry.STEPS[i][1] == -port.dy() && LineGeometry.STEPS[i][2] == -port.dz()) face = i;
                int code = PipeSizes.code(diameters[port.nozzle()]), mask = (1 << face) | (1 << (face ^ 1));
                long sizes = ((long) code << (7 * face)) | ((long) code << (7 * (face ^ 1)));
                var key = new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE, face, mask, code, sizes, 1 << face, false);
                int x = port.cell().x() + port.dx(), y = port.cell().y() + port.dy(), z = port.cell().z() + port.dz();
                for (var polygon : LineGeometry.polygons(key)) add(triangles, polygon, x, y, z);
            }
            double[] depth = switch (view) {
                case "front" -> new double[]{0, 0, 1};
                case "back" -> new double[]{0, 0, -1};
                case "left" -> new double[]{1, 0, 0};
                case "right" -> new double[]{-1, 0, 0};
                case "iso3" -> unit(new double[]{1, -.6, -1.15});
                case "low" -> unit(new double[]{1, -.12, 1.15});
                default -> unit(new double[]{1, -.6, 1.15});
            };
            String caption = String.format(Locale.ROOT, "D %s m, %d trays, %s", spec[0], n, spec[2]);
            String detail = String.format(Locale.ROOT, "sump %.1f m, T/T %d m, skirt %d m%s", ColumnTrays.bottomSpace(d), (int) shape.tangentLength(), skirt,
                    misfit.isPresent() ? ", REFUSED" : "");
            System.out.println(caption + ": " + detail + misfit.map(m -> " (" + m + ")").orElse(""));
            for (var nozzle : shape.nozzles())
                System.out.printf(Locale.ROOT, "  %-16s y %.2f (above bottom tangent %.2f) od %.2f axis %d sign %d%n", nozzle.role(), nozzle.base()[1], nozzle.base()[1] - shape.bottomTangent(),
                        nozzle.defaultOd(), nozzle.axis(), nozzle.sign());
            tiles.add(new Tile(render(triangles, shape, depth, sprites, scale), caption, detail));
        }
        int caption = 40, gap = 16, width = gap, height = 0;
        for (var tile : tiles) {
            width += Math.max(tile.image.getWidth(), 260) + gap;
            height = Math.max(height, tile.image.getHeight());
        }
        var sheet = new BufferedImage(width, height + caption + gap, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0xEEF1F4));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        int x = gap;
        for (var tile : tiles) {
            int w = Math.max(tile.image.getWidth(), 260);
            g.drawImage(tile.image, x + (w - tile.image.getWidth()) / 2, caption + height - tile.image.getHeight(), null);
            g.setColor(Color.DARK_GRAY);
            g.drawString(tile.caption, x, 16);
            g.drawString(tile.detail, x, 32);
            x += w + gap;
        }
        g.dispose();
        new File(out).getAbsoluteFile().getParentFile().mkdirs();
        ImageIO.write(sheet, "png", new File(out));
        System.out.println("wrote " + out);
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
