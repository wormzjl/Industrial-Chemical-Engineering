package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Software renders of the equipment reflection pass (batch 2026-11-18-equipment-reflections): a drum, a column, an
 * exchanger and a pump, each from two views, as the chunk mesh draws them today (shade and baked sheen) and with the pass
 * (shade without sheen, plus {@link ReflectionLaw#added} with the classes of {@link ReflectionClass}): the production law
 * and table, not a copy. Colours: each material's sprite mean from the shell model JSON, painted ones in the default
 * paint (RAL 9006 body, RAL 7031 structure). Sky: the law's preview constants at noon-like daylight, the sun at 44 degrees
 * in the east. Lightmap full, no fog, no textures, no nozzles (the pass draws none).
 *
 * <p>Run through {@code render.init.gradle} (README); writes {@code <out>/<shape>.png} and {@code <out>/sheet.png}.
 */
public final class ReflectionRender {
    private static final Path ASSETS = Path.of("src/main/resources/assets/icheme");
    private static final int W = 520, H = 420;
    private static final double SHEEN = .2, SUN_ANGLE = -.8;

    private record Tri(double[][] p, double[][] n, Mesh.Material m, boolean curved) {}

    public static void main(String[] args) throws Exception {
        var out = new File(args.length > 0 ? args[0] : "tmp/reflection-render");
        out.mkdirs();
        var colours = colours();
        var shapes = new EquipmentShape[]{drum(), column(), exchanger(), pump()};
        var names = new String[]{"drum", "column", "exchanger", "pump"};
        var rows = new ArrayList<BufferedImage>();
        for (int s = 0; s < shapes.length; s++) {
            var tris = triangles(shapes[s]);
            var row = new BufferedImage(4 * W, H + 22, BufferedImage.TYPE_INT_RGB);
            var g = row.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, row.getWidth(), row.getHeight());
            double[][] views = {{35, 22}, {215, 8}};
            int k = 0;
            for (var view : views)
                for (boolean pass : new boolean[]{false, true}) {
                    var image = render(shapes[s], tris, colours, view[0], view[1], pass);
                    g.drawImage(image, k * W, 22, null);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                    g.drawString(String.format("%s  view %.0f/%.0f deg  %s", names[s], view[0], view[1], pass ? "reflection pass (sheen 0)" : "today: baked sheen 0.2"), k * W + 8, 16);
                    k++;
                }
            g.dispose();
            ImageIO.write(row, "png", new File(out, names[s] + ".png"));
            rows.add(row);
            System.out.println("wrote " + new File(out, names[s] + ".png"));
        }
        var sheet = new BufferedImage(4 * W, rows.size() * (H + 22), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        for (int i = 0; i < rows.size(); i++) g.drawImage(rows.get(i), 0, i * (H + 22), null);
        g.dispose();
        ImageIO.write(sheet, "png", new File(out, "sheet.png"));
        System.out.println("wrote " + new File(out, "sheet.png"));
    }

    private static EquipmentShape drum() {
        return EquipmentShape.drum(2, 6, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 5,
                EquipmentShape.defaultConnections(2, 6, EquipmentShape.Orientation.HORIZONTAL, 5, true), true);
    }

    private static EquipmentShape column() {
        return EquipmentShape.column(2, ColumnTrays.simple(12, 6), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(2));
    }

    private static EquipmentShape designed(EquipmentShape.Kind kind, double size) {
        var d = UnitDesign.of(kind);
        int length = d.defaultLength(size);
        return EquipmentShape.designed(kind, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    private static EquipmentShape exchanger() {
        return designed(EquipmentShape.Kind.EXCHANGER, 1.5);
    }

    private static EquipmentShape pump() {
        var d = UnitDesign.of(EquipmentShape.Kind.PUMP);
        return designed(EquipmentShape.Kind.PUMP, d.minSize());
    }

    /** Each material's drawn colour, 0..1: its sprite's mean, painted ones as the tint makes them (the paint itself, scaled by the sprite over the neutral 236). */
    private static Map<Mesh.Material, double[]> colours() throws Exception {
        String json = Files.readString(ASSETS.resolve("models/block/equipment_shell.json"));
        var out = new EnumMap<Mesh.Material, double[]>(Mesh.Material.class);
        var paint = Paint.DEFAULT;
        for (var m : Mesh.Material.values()) {
            var matcher = Pattern.compile("\"" + m.texture() + "\"\\s*:\\s*\"icheme:block/(\\w+)\"").matcher(json);
            double[] c = {.6, .6, .6};
            if (matcher.find()) {
                var file = ASSETS.resolve("textures/block/" + matcher.group(1) + ".png").toFile();
                if (file.isFile()) {
                    var image = ImageIO.read(file);
                    c = new double[3];
                    for (int y = 0; y < image.getHeight(); y++)
                        for (int x = 0; x < image.getWidth(); x++)
                            for (int i = 0; i < 3; i++) c[i] += (image.getRGB(x, y) >> 16 - 8 * i & 255) / 255.0 / (image.getWidth() * image.getHeight());
                }
            }
            if (m.channel() >= 0) {
                int rgb = paint.colour(m.channel());
                for (int i = 0; i < 3; i++) c[i] = Math.min(1, c[i] * 255 / Paint.SPRITE_VALUE * (rgb >> 16 - 8 * i & 255) / 255.0);
            }
            out.put(m, c);
        }
        return out;
    }

    private static List<Tri> triangles(EquipmentShape shape) {
        var out = new ArrayList<Tri>();
        for (var polygon : ShapeRasterizer.renderBody(shape, RenderDetail.NORMAL)) {
            double[] p = polygon.positions(), n = polygon.normals();
            boolean curved = false;
            for (int i = 1; i < polygon.size(); i++)
                curved |= Math.abs(n[3 * i] - n[0]) + Math.abs(n[3 * i + 1] - n[1]) + Math.abs(n[3 * i + 2] - n[2]) > 1e-4;
            for (int k = 1; k + 1 < polygon.size(); k++) {
                int[] c = {0, k, k + 1};
                double[][] pp = new double[3][], nn = new double[3][];
                for (int j = 0; j < 3; j++) {
                    pp[j] = new double[]{p[3 * c[j]], p[3 * c[j] + 1], p[3 * c[j] + 2]};
                    nn[j] = new double[]{n[3 * c[j]], n[3 * c[j] + 1], n[3 * c[j] + 2]};
                }
                out.add(new Tri(pp, nn, polygon.material(), curved));
            }
        }
        return out;
    }

    /** NeoForge's QuadLighter.calculateShade (constant ambient off) plus the baked sheen, as EquipmentShellModel.shade. */
    private static double shade(double[] n, boolean sheen) {
        double s = n[0] * n[0] * .6 + n[1] * n[1] * ((3 + n[1]) / 4) + n[2] * n[2] * .8;
        if (sheen) {
            double x2 = n[0] * n[0], z2 = n[2] * n[2];
            s += SHEEN * (z2 * z2 * z2 * z2 + .5 * x2 * x2 * x2 * x2);
        }
        return Math.min(1, s);
    }

    private static boolean sheens(Mesh.Material m) {
        var c = ReflectionClass.of(m);
        return c == ReflectionClass.PAINT && m != Mesh.Material.HANDRAIL || c == ReflectionClass.CLADDING;
    }

    private static BufferedImage render(EquipmentShape shape, List<Tri> tris, Map<Mesh.Material, double[]> colours, double azimuth, double elevation, boolean pass) {
        double cx = shape.sizeX() / 2.0, cy = shape.sizeY() / 2.0, cz = shape.sizeZ() / 2.0;
        double radius = Math.sqrt(cx * cx + cy * cy + cz * cz);
        double a = Math.toRadians(azimuth), e = Math.toRadians(elevation), distance = 2.6 * radius;
        double[] eye = {cx + distance * Math.cos(e) * Math.sin(a), cy + distance * Math.sin(e), cz + distance * Math.cos(e) * Math.cos(a)};
        double[] f = unit(new double[]{cx - eye[0], cy - eye[1], cz - eye[2]});
        double[] r = unit(cross(f, new double[]{0, 1, 0})), u = cross(r, f);
        double focal = H / 2.0 / Math.tan(Math.toRadians(25));
        var image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        double[] depth = new double[W * H];
        java.util.Arrays.fill(depth, Double.MAX_VALUE);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double t = (double) y / H;
                image.setRGB(x, y, rgb(new double[]{.86 - .2 * t, .9 - .15 * t, .96 - .1 * t}));
            }
        double[] light = ReflectionLaw.celestial(SUN_ANGLE, 0);
        for (var tri : tris) {
            double[][] s = new double[3][];
            boolean behind = false;
            for (int j = 0; j < 3; j++) {
                double[] d = {tri.p[j][0] - eye[0], tri.p[j][1] - eye[1], tri.p[j][2] - eye[2]};
                double z = dot(d, f);
                behind |= z < .05;
                s[j] = new double[]{W / 2.0 + focal * dot(d, r) / z, H / 2.0 - focal * dot(d, u) / z, z};
            }
            if (behind) continue;
            // Back faces culled as the shells are: the facet's normal away from the eye.
            double[] mid = new double[3], nm = new double[3];
            for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
                mid[i] += tri.p[j][i] / 3;
                nm[i] += tri.n[j][i];
            }
            if (dot(nm, new double[]{eye[0] - mid[0], eye[1] - mid[1], eye[2] - mid[2]}) <= 0) continue;
            double area = (s[1][0] - s[0][0]) * (s[2][1] - s[0][1]) - (s[2][0] - s[0][0]) * (s[1][1] - s[0][1]);
            if (Math.abs(area) < 1e-9) continue;
            int x0 = (int) Math.max(0, Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0])))), x1 = (int) Math.min(W - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1])))), y1 = (int) Math.min(H - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
            var base = colours.get(tri.m);
            var type = ReflectionClass.of(tri.m);
            int tint = type == null ? 0xffffff : ReflectionClass.tint(tri.m, Paint.DEFAULT.body());
            for (int py = y0; py <= y1; py++)
                for (int px = x0; px <= x1; px++) {
                    double X = px + .5, Y = py + .5;
                    double w0 = ((s[1][0] - X) * (s[2][1] - Y) - (s[2][0] - X) * (s[1][1] - Y)) / area;
                    double w1 = ((s[2][0] - X) * (s[0][1] - Y) - (s[0][0] - X) * (s[2][1] - Y)) / area;
                    double w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    // perspective-correct weights
                    double q0 = w0 / s[0][2], q1 = w1 / s[1][2], q2 = w2 / s[2][2], qs = q0 + q1 + q2;
                    double z = 1 / qs;
                    int at = py * W + px;
                    if (z >= depth[at]) continue;
                    depth[at] = z;
                    double[] n = new double[3], p = new double[3];
                    for (int i = 0; i < 3; i++) {
                        n[i] = (q0 * tri.n[0][i] + q1 * tri.n[1][i] + q2 * tri.n[2][i]) / qs;
                        p[i] = (q0 * tri.p[0][i] + q1 * tri.p[1][i] + q2 * tri.p[2][i]) / qs;
                    }
                    n = unit(n);
                    double sh = shade(n, !pass && tri.curved && sheens(tri.m));
                    double[] c = {base[0] * sh, base[1] * sh, base[2] * sh};
                    if (pass && type != null) {
                        double[] v = unit(new double[]{eye[0] - p[0], eye[1] - p[1], eye[2] - p[2]});
                        var add = ReflectionLaw.added(n, v, type, tint, ReflectionLaw.ZENITH, ReflectionLaw.HORIZON, 1, light, 1, 1, 1);
                        for (int i = 0; i < 3; i++) c[i] += add[i];
                    }
                    image.setRGB(px, py, rgb(c));
                }
        }
        return image;
    }

    private static int rgb(double[] c) {
        int r = (int) Math.round(255 * Math.max(0, Math.min(1, c[0]))), g = (int) Math.round(255 * Math.max(0, Math.min(1, c[1]))), b = (int) Math.round(255 * Math.max(0, Math.min(1, c[2])));
        return r << 16 | g << 8 | b;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return l < 1e-12 ? a : new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
