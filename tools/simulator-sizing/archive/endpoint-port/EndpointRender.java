import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Textured orthographic software view of production polygons, using their shipped UVs and sprites. */
public final class EndpointRender {
    private record Piece(Mesh.Polygon polygon, double[] offset) {}
    private static final Map<Mesh.Material, BufferedImage> TEXTURES = new EnumMap<>(Mesh.Material.class);
    private static final double[] EYE = unit(new double[]{1, .8, 1.3});
    private static final double[] RIGHT = unit(new double[]{EYE[2], 0, -EYE[0]});
    private static final double[] UP = cross(EYE, RIGHT);

    public static void main(String[] args) throws Exception {
        Path textures = Path.of(args[0]), output = Path.of(args[1]);
        for (var m : new Mesh.Material[]{Mesh.Material.PIPE, Mesh.Material.FLANGE, Mesh.Material.BOLT,
                Mesh.Material.OPENING, Mesh.Material.FEED, Mesh.Material.DRAIN})
            TEXTURES.put(m, ImageIO.read(textures.resolve("equipment_" + m.texture() + ".png").toFile()));
        var image = new BufferedImage(1400, 1480, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(0xE7ECEF)); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(new Color(0x22313A)); g.setFont(new Font("SansSerif", Font.BOLD, 25));
        g.drawString("Fluid generator and void | six equal faces", 30, 38);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.drawString("Production meshes and textures. Pipe necks and flanges match each connection's diameter.", 30, 67);
        for (int row = 0; row < 4; row++) for (int col = 0; col < 2; col++) {
            var kind = col == 0 ? Kind.GENERATOR : Kind.VOID;
            var scene = new ArrayList<Piece>();
            int mask = row == 0 ? 0 : row == 1 ? 48 : row == 2 ? 3 : 63;
            long codes = 0;
            for (int f = 0; f < 6; f++) if ((mask >> f & 1) != 0) {
                double bore = row == 3 ? new double[]{.05, .15, .4, .1, .25, .6}[f] : .25;
                int code = PipeSizes.code(PipeSizes.outside(bore)); codes |= (long) code << 7 * f;
                int opposite = f ^ 1;
                add(scene, LineGeometry.polygons(new LineGeometry.Key(Kind.PIPE, f, 1 << opposite, code,
                        (long) code << 7 * opposite, 1 << opposite, false)),
                        Arrays.stream(LineGeometry.STEPS[f]).asDoubleStream().toArray());
            }
            add(scene, LineGeometry.polygons(new LineGeometry.Key(kind, 2, mask, PipeSizes.code(.1), codes, mask, false)), new double[3]);
            String label = (col == 0 ? "Generator" : "Void") + " / " + new String[]{"unconnected", "horizontal 0.25 m bore", "vertical 0.25 m bore", "six ports, mixed bores"}[row];
            panel(g, scene, col * 700, 85 + row * 345, label, row == 0 ? 310 : 220);
        }
        g.dispose(); ImageIO.write(image, "png", output.toFile());
        System.out.println(output);
    }
    private static void add(List<Piece> scene, List<Mesh.Polygon> polygons, double[] offset) {
        for (var polygon : polygons) scene.add(new Piece(polygon, offset));
    }
    private static void panel(Graphics2D g, List<Piece> scene, int left, int top, String label, double scale) {
        int w = 686, h = 331;
        g.setColor(Color.WHITE); g.fillRoundRect(left + 7, top, w, h, 12, 12);
        g.setColor(new Color(0x34454D)); g.setFont(new Font("SansSerif", Font.BOLD, 17)); g.drawString(label, left + 25, top + 29);
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (var piece : scene) for (int i = 0; i < piece.polygon.size(); i++) {
            var p = new double[3];
            for (int k = 0; k < 3; k++) p[k] = piece.polygon.positions()[3 * i + k] + piece.offset[k] - .5;
            double x = dot(p, RIGHT), y = dot(p, UP);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        scale = Math.min(scale, Math.min((w - 55) / (maxX - minX), (h - 75) / (maxY - minY)));
        double ox = w / 2.0 - scale * (minX + maxX) / 2, oy = 45 + (h - 60) / 2.0 + scale * (minY + maxY) / 2;
        var pixels = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var depth = new double[w * h]; Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        for (var piece : scene) {
            var polygon = piece.polygon; var positions = polygon.positions();
            double[] n = cross(sub(positions, 1, 0), sub(positions, 2, 0));
            if (dot(n, EYE) <= 1e-10) continue;
            double shade = .55 + .45 * Math.max(0, dot(unit(n), unit(new double[]{-.25, .8, 1})));
            var projected = new double[polygon.size()][5];
            for (int i = 0; i < polygon.size(); i++) {
                double[] p = new double[3];
                for (int k = 0; k < 3; k++) p[k] = positions[3 * i + k] + piece.offset[k] - .5;
                projected[i] = new double[]{ox + scale * dot(p, RIGHT), oy - scale * dot(p, UP), dot(p, EYE), polygon.uvs()[2 * i], polygon.uvs()[2 * i + 1]};
            }
            for (int i = 1; i + 1 < polygon.size(); i++) triangle(pixels, depth, projected[0], projected[i], projected[i + 1], TEXTURES.get(polygon.material()), shade);
        }
        g.drawImage(pixels, left + 7, top, null);
    }
    private static void triangle(BufferedImage image, double[] depth, double[] a, double[] b, double[] c, BufferedImage texture, double shade) {
        double area = edge(a, b, c[0], c[1]); if (Math.abs(area) < 1e-10) return;
        int w = image.getWidth(), h = image.getHeight();
        int x0 = Math.max(0, (int)Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), x1 = Math.min(w - 1, (int)Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = Math.max(0, (int)Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), y1 = Math.min(h - 1, (int)Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            double u = edge(b, c, x + .5, y + .5) / area, v = edge(c, a, x + .5, y + .5) / area, t = 1 - u - v;
            if (u < -1e-8 || v < -1e-8 || t < -1e-8) continue;
            double z = u * a[2] + v * b[2] + t * c[2]; int index = y * w + x;
            if (z <= depth[index]) continue;
            int tx = Math.clamp((int)((u * a[3] + v * b[3] + t * c[3]) * texture.getWidth()), 0, texture.getWidth() - 1);
            int ty = Math.clamp((int)((u * a[4] + v * b[4] + t * c[4]) * texture.getHeight()), 0, texture.getHeight() - 1);
            int rgb = texture.getRGB(tx, ty);
            image.setRGB(x, y, 0xFF000000 | (int)((rgb >> 16 & 255) * shade) << 16 | (int)((rgb >> 8 & 255) * shade) << 8 | (int)((rgb & 255) * shade));
            depth[index] = z;
        }
    }
    private static double[] sub(double[] p, int a, int b) { return new double[]{p[3*a]-p[3*b], p[3*a+1]-p[3*b+1], p[3*a+2]-p[3*b+2]}; }
    private static double edge(double[] a, double[] b, double x, double y) { return (b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]); }
    private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
    private static double[] unit(double[] a) { double n=Math.sqrt(dot(a,a)); return new double[]{a[0]/n,a[1]/n,a[2]/n}; }
}
