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
 * Skirt shadow scratch renderer (batch 2026-10-27-skirt-shadow), adapted from tools/column-sump/ColumnRender.java: a
 * vessel's real shell cell polygons, the mod's sprites, the paint tint, the game's directional shade, culled back faces,
 * a depth buffer, a one-block pipe stub at every port. Optionally the in-game light: Minecraft's skylight over the
 * vessel's cells (interior cells opaque, shell cells and air passing skylight down, -1 a step sideways), each quad lit by
 * its own cell's level (vanilla flat lighting: the shell model asks for no ambient occlusion) through the vanilla noon
 * lightmap at the default brightness 0.5, divided by full skylight's value so a fully lit face draws as without light.
 *
 * <p>Args: {@code out.png pxPerMetre view light|flat spec...}; view front, back, left, right, iso, iso3 (back left), low;
 * a spec {@code drum:D:TT[:skirt]} (standing drum), {@code column:D:trays:config[:skirt]} (NewSweep's configs) or
 * {@code unit:KIND[:size[:length]]} (least size and length by default, the least support). {@code -Dcut=h} leaves out
 * every polygon wholly above h m (a section showing the floor inside a skirt). Interior cells pass skylight where
 * {@code ShapeRasterizer.skylight} says so. Prints the skylight of every cell holding the skirt's outer face and of the
 * shell cells inside the skirt. Run from the worktree root: it reads src/main/resources/assets/icheme/. Compile with
 * ../multi-machine-framework/f3-nozzle-rows/NewSweep.java and RowSweep.java.
 */
public class SkirtRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";

    private record Triangle(double[][] p, double[][] uv, double[] normal, Mesh.Material material, double light) {
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
        boolean lit = args[3].equals("light");
        var sprites = sprites();
        var tiles = new ArrayList<Tile>();
        for (int a = 4; a < args.length; a++) {
            var spec = args[a].split(":");
            EquipmentShape shape;
            String caption;
            if (spec[0].equals("drum")) {
                double d = Double.parseDouble(spec[1]), tt = Double.parseDouble(spec[2]);
                int skirt = spec.length > 3 ? Integer.parseInt(spec[3]) : EquipmentShape.minimumSkirt(d);
                shape = EquipmentShape.drum(d, tt, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, skirt);
                caption = String.format(Locale.ROOT, "standing drum D %s m, T/T %s m, skirt %d m", spec[1], spec[2], skirt);
            } else if (spec[0].equals("unit")) {
                var kind = EquipmentShape.Kind.valueOf(spec[1]);
                double d = spec.length > 2 ? Double.parseDouble(spec[2]) : ProcessUnit.minDiameter(kind);
                int length = spec.length > 3 ? Integer.parseInt(spec[3]) : ProcessUnit.minLength(kind, d);
                int support = ProcessUnit.minSupport(kind, d, length);
                shape = EquipmentShape.unit(kind, d, length, EquipmentShape.Heading.NORTH, support);
                caption = String.format(Locale.ROOT, "%s %s, %d, support %d", kind, ProcessUnit.minDiameter(kind) == d ? d + " (least)" : String.valueOf(d), length, support);
            } else {
                double d = Double.parseDouble(spec[1]);
                int n = Integer.parseInt(spec[2]);
                int skirt = spec.length > 4 ? Integer.parseInt(spec[4]) : EquipmentShape.minimumSkirt(d);
                shape = EquipmentShape.column(d, NewSweep.trays(n, spec[3]), EquipmentShape.Heading.NORTH, skirt);
                caption = String.format(Locale.ROOT, "column D %s m, %d trays, skirt %d m", spec[1], n, skirt);
            }
            var layout = ShapeRasterizer.layout(shape);
            var light = lit ? skylight(shape, layout) : null;
            var triangles = new ArrayList<Triangle>();
            double[] diameters = new double[shape.nozzles().size()];
            for (int i = 0; i < diameters.length; i++) diameters[i] = PipeSizes.outsideOf(PipeSizes.code(shape.nozzles().get(i).defaultOd()));
            var skirtLevels = new TreeMap<Integer, Integer>();
            var insideLevels = new TreeMap<Integer, Integer>();
            for (var cell : layout.shell()) {
                var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z(), ShapeRasterizer.cellCodes(shape, cell, diameters));
                boolean skirtFace = false;
                for (var polygon : ShapeRasterizer.surface(key)) {
                    add(triangles, polygon, cell.x(), cell.y(), cell.z(), light);
                    // The plate is SUPPORT before the change and SKIRT after it (by name, so one source compiles on both).
                    skirtFace |= (polygon.material() == Mesh.Material.SUPPORT || polygon.material().name().equals("SKIRT")) && outward(shape, polygon, cell) && cell.y() + 1 <= Math.ceil(shape.bottomTangent()) && cell.y() >= 0;
                }
                for (var polygon : PortMarkings.surface(key)) add(triangles, polygon, cell.x(), cell.y(), cell.z(), light);
                if (skirtFace && light != null) skirtLevels.merge(light.at(cell.x(), cell.y(), cell.z()), 1, Integer::sum);
                if (light != null && inside(shape, cell)) insideLevels.merge(light.at(cell.x(), cell.y(), cell.z()), 1, Integer::sum);
            }
            for (var port : ShapeRasterizer.ports(shape)) {
                int face = 0;
                for (int i = 0; i < 6; i++)
                    if (LineGeometry.STEPS[i][0] == -port.dx() && LineGeometry.STEPS[i][1] == -port.dy() && LineGeometry.STEPS[i][2] == -port.dz()) face = i;
                int code = PipeSizes.code(diameters[port.nozzle()]), mask = (1 << face) | (1 << (face ^ 1));
                long sizes = ((long) code << (7 * face)) | ((long) code << (7 * (face ^ 1)));
                var key = new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE, face, mask, code, sizes, 1 << face, false);
                int x = port.cell().x() + port.dx(), y = port.cell().y() + port.dy(), z = port.cell().z() + port.dz();
                for (var polygon : LineGeometry.polygons(key)) add(triangles, polygon, x, y, z, light);
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
            var paint = Paint.DEFAULT;
            String detail = lit ? String.format(Locale.ROOT, "in-game skylight, interior %s", clear(shape) ? "lets skylight through" : "opaque")
                    : String.format(Locale.ROOT, "no light, paint body #%06X structure #%06X", paint.colour(Paint.BODY), paint.colour(Paint.STRUCTURE));
            System.out.println(caption + ": " + detail + (light != null ? ", skirt outer cells by skylight " + skirtLevels + ", shell cells inside the skirt " + insideLevels : ""));
            tiles.add(new Tile(render(triangles, shape, depth, sprites, scale, paint), caption, detail));
        }
        int caption = 40, gap = 16, width = gap, height = 0;
        for (var tile : tiles) {
            width += Math.max(tile.image.getWidth(), 300) + gap;
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
            int w = Math.max(tile.image.getWidth(), 300);
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

    /** Whether a polygon faces away from the vessel's axis (the skirt's outer face, not its inner one). */
    private static boolean outward(EquipmentShape shape, Mesh.Polygon polygon, ShapeRasterizer.Cell cell) {
        double[] p = polygon.positions(), n = polygon.normals();
        var t = shape.transform();
        var axisPoint = t.point(new double[]{shape.axis(), 0, shape.axis()});
        double cx = p[0] + cell.x() - axisPoint[0], cz = p[2] + cell.z() - axisPoint[2];
        return n[0] * cx + n[2] * cz > 0 && Math.abs(n[1]) < .5;
    }

    /** Whether a cell lies inside the skirt, clear of its ring: under the bottom tangent and its centre a block inside the plate. */
    private static boolean inside(EquipmentShape shape, ShapeRasterizer.Cell cell) {
        var local = shape.transform().local(new double[]{cell.x() + .5, cell.y() + .5, cell.z() + .5});
        double dx = local[0] - shape.axis(), dz = local[2] - shape.axis();
        return shape.skirt() && local[1] < shape.bottomTangent() && Math.sqrt(dx * dx + dz * dz) < shape.radius() - 1;
    }

    /**
     * Whether the vessel's interior cells let skylight through ({@code ShapeRasterizer.skylight}, batch
     * 2026-10-27-skirt-shadow), looked up by name so one source compiles on the code before it (false there).
     */
    static boolean clear(EquipmentShape shape) {
        try {
            return (boolean) ShapeRasterizer.class.getMethod("skylight", EquipmentShape.class).invoke(null, shape);
        } catch (NoSuchMethodException before) {
            return false;
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** Skylight over a box round the vessel: what Minecraft's light engine stores in each cell at noon (interior cells opaque unless {@link #clear}). */
    private record Light(int x0, int y0, int z0, int sx, int sy, int sz, int[] level) {
        int at(int x, int y, int z) {
            x -= x0;
            y -= y0;
            z -= z0;
            if (x < 0 || y < 0 || z < 0 || x >= sx || z >= sz) return 15;
            if (y >= sy) return 15;
            return level[(x * sy + y) * sz + z];
        }
    }

    private static Light skylight(EquipmentShape shape, ShapeRasterizer.Layout layout) {
        int m = 3, x0 = -m, y0 = -1, z0 = -m, sx = shape.sizeX() + 2 * m, sy = shape.sizeY() + 3, sz = shape.sizeZ() + 2 * m;
        var opaque = new boolean[sx * sy * sz];
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) opaque[(x * sy) * sz + z] = true; // the ground under y = 0
        if (!clear(shape)) for (var c : layout.interior()) opaque[((c.x() - x0) * sy + c.y() - y0) * sz + c.z() - z0] = true;
        var level = new int[sx * sy * sz];
        var queue = new ArrayDeque<int[]>();
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++)
                for (int y = sy - 1; y >= 0; y--) {
                    int i = (x * sy + y) * sz + z;
                    if (opaque[i]) break;
                    level[i] = 15;
                    queue.add(new int[]{x, y, z});
                }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            var c = queue.poll();
            int l = level[(c[0] * sy + c[1]) * sz + c[2]] - 1;
            if (l <= 0) continue;
            for (var s : steps) {
                int x = c[0] + s[0], y = c[1] + s[1], z = c[2] + s[2];
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue;
                int i = (x * sy + y) * sz + z;
                if (opaque[i] || level[i] >= l) continue;
                level[i] = l;
                queue.add(new int[]{x, y, z});
            }
        }
        return new Light(x0, y0, z0, sx, sy, sz, level);
    }

    /** The vanilla lightmap's value for skylight {@code sky} and no block light, overworld, noon, brightness 0.5. */
    static double lightmap(int sky) {
        double f = sky / 15.0, b = f / (4 - 3 * f);
        double v = b + (.75 - b) * .04;
        v = Math.max(0, Math.min(1, v));
        double ng = 1 - Math.pow(1 - v, 4);
        v = v + (ng - v) * .5;
        v = v + (.75 - v) * .04;
        return Math.max(0, Math.min(1, v));
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

    /** A section: with {@code -Dcut=h}, every polygon wholly above h m is left out, so the floor inside a skirt shows. */
    private static final double CUT = Double.parseDouble(System.getProperty("cut", "1e9"));

    private static void add(List<Triangle> out, Mesh.Polygon polygon, int x, int y, int z, Light light) {
        double[] p = polygon.positions(), uv = polygon.uvs();
        int n = polygon.size();
        double low = 1e9;
        for (int k = 0; k < n; k++) low = Math.min(low, p[3 * k + 1] + y);
        if (low > CUT) return;
        double value = light == null ? 1 : lightmap(quadLight(polygon, x, y, z, light)) / lightmap(15);
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
            out.add(new Triangle(pts, uvs, new double[]{normal[0] / l, normal[1] / l, normal[2] / l}, polygon.material(), value));
        }
    }

    /**
     * The skylight vanilla's flat renderer lights a quad with: its own cell's, or the neighbour's where the quad lies flat
     * on the cell's face (ModelBlockRenderer.renderModelFaceFlat with calculateShape's flag 0).
     */
    private static int quadLight(Mesh.Polygon polygon, int x, int y, int z, Light light) {
        double[] p = polygon.positions();
        int n = polygon.size();
        for (int axis = 0; axis < 3; axis++) {
            double min = 9, max = -9;
            for (int k = 0; k < n; k++) {
                min = Math.min(min, p[3 * k + axis]);
                max = Math.max(max, p[3 * k + axis]);
            }
            if (max - min > 1e-6) continue;
            int step = min < 1e-4 ? -1 : max > .9999 ? 1 : 0;
            if (step == 0) continue;
            int[] c = {x, y, z};
            c[axis] += step;
            return light.at(c[0], c[1], c[2]);
        }
        return light.at(x, y, z);
    }

    private static double shade(double[] n) {
        return Math.min(n[0] * n[0] * .6 + n[1] * n[1] * ((3 + n[1]) / 4) + n[2] * n[2] * .8, 1);
    }

    private static BufferedImage render(List<Triangle> triangles, EquipmentShape shape, double[] depth, Map<Mesh.Material, Sprite> sprites, double scale, Paint paint) {
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
            double light = shade(t.normal) * t.light;
            int channel = t.material.channel();
            int tint = channel < 0 ? 0xFFFFFF : paint.colour(channel);
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
