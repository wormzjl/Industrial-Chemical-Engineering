import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ConstructorDefaults;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The equipment reference items' icons (batch 2026-11-12-tutorial-system, P3; tools/tutorial-renders): every
 * {@link EquipmentShape.Kind} at its native reference size (the constructor's default size, as
 * tools/equipment-drawings/EngineeringExport builds it), its complete drawing ({@link ShapeRasterizer#renderPreview},
 * the constructor ghost: moving parts and nozzles in their neutral pose), seen from one fixed three-quarter view in
 * orthographic projection, the mod's sprites and default paints, the game's directional shade, on a transparent
 * background, the body filling the frame. Rendered at {@link #SUPER} samples a pixel each way and box-filtered down;
 * a one-pixel dark rim around the silhouette keeps the shape readable on any slot.
 *
 * <p>Args: {@code textureDir sheetDir}. Writes {@code equipment_<kind>.png} ({@link #SIZE} px) to the texture folder and
 * {@code icons-sheet.png} (every kind at 1x and 4x, with its name) to the sheet folder.
 */
public class IconRender {
    /** The icon's side in px. */
    static final int SIZE = Integer.getInteger("iconSize", 32);
    /** Samples a pixel along each axis. */
    static final int SUPER = 8;
    /** The view: from the south-east, 30 degrees above the horizon (a Minecraft block item's view is 30 degrees). */
    static final double YAW = Math.toRadians(-45), PITCH = Math.toRadians(30);
    /** Per-kind view turns (radians about the vertical) where the default view hides what tells a kind apart. */
    static final Map<EquipmentShape.Kind, Double> TURN = new EnumMap<>(EquipmentShape.Kind.class);
    /**
     * The tallest view kept, as height over width: a slender kind (a flare or a steel stack, 20 times taller than wide)
     * would be a line one pixel wide, so its icon shows its top part, where its tip, platforms or taper are.
     */
    static final double CAP = Double.parseDouble(System.getProperty("iconCap", "3.5"));

    static {
        TURN.put(EquipmentShape.Kind.KETTLE, Double.parseDouble(System.getProperty("kettleTurn", "90")) * Math.PI / 180);
    }

    public static void main(String[] args) throws Exception {
        var textures = new File(args.length > 0 ? args[0] : "src/main/resources/assets/icheme/textures/item");
        var sheets = new File(args.length > 1 ? args[1] : "tmp/tutorial-renders/p3");
        textures.mkdirs();
        boolean write = !Boolean.getBoolean("iconPreview");
        sheets.mkdirs();
        var sprites = TutorialRender.sprites();
        var lang = TutorialRender.lang();
        var icons = new ArrayList<BufferedImage>();
        var names = new ArrayList<String>();
        for (var kind : EquipmentShape.Kind.values()) {
            var shape = reference(kind);
            var polygons = ShapeRasterizer.renderPreview(shape, RenderDetail.NORMAL);
            var icon = render(polygons, TURN.getOrDefault(kind, 0.0), sprites);
            if (write) ImageIO.write(icon, "png", new File(textures, EquipmentInfo.itemId(kind) + ".png"));
            icons.add(icon);
            names.add(lang.getOrDefault(EquipmentInfo.nameKey(kind), kind.name()));
            System.out.println(kind + ": " + polygons.size() + " polygons, " + shape.diameter() + " by " + shape.tangentLength());
        }
        ImageIO.write(sheet(icons, names), "png", new File(sheets, "icons-sheet.png"));
        System.out.println("wrote " + new File(sheets, "icons-sheet.png").getPath());
    }

    /** The kind at its native reference size: the constructor's default (EngineeringExport's first scene of the kind; a drum lying). */
    static EquipmentShape reference(EquipmentShape.Kind kind) {
        var heading = EquipmentShape.Heading.NORTH;
        VesselPlacement.Form form = null;
        for (var f : VesselPlacement.Form.values())
            if (f != VesselPlacement.Form.CROSSWISE && f.kind() == kind && (form == null || f == VesselPlacement.Form.LENGTHWISE)) form = f;
        if (form == null) throw new IllegalStateException(kind + ": no constructor form");
        var size = ConstructorDefaults.sizes(form);
        double d = size.diameter();
        if (kind == EquipmentShape.Kind.DRUM) return EquipmentShape.drum(d, size.length(), form.orientation(), heading, EquipmentShape.defaultSupport(d, form.orientation()));
        if (kind == EquipmentShape.Kind.COLUMN)
            return EquipmentShape.column(d, new ColumnTrays(32, 18, List.of(10, 24), List.of(33), List.of(new ColumnTrays.Pumparound(26, 20))), heading, EquipmentShape.minimumSkirt(d));
        var design = UnitDesign.of(kind);
        if (kind.storage())
            return new EquipmentShape(kind, d, size.length(), design.orientation(), heading, design.minSupport(d, size.length()), null, List.of(),
                    com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE, false, 0, com.wormzjl.icheme.science.equipment.TowerBank.ONE,
                    com.wormzjl.icheme.science.equipment.MachineDuty.NONE);
        return EquipmentShape.unit(kind, d, size.length(), heading, design.minSupport(d, size.length()));
    }

    /** One icon: the polygons from the fixed view turned by {@code turn}, filling a {@link #SIZE} px square. */
    static BufferedImage render(List<Mesh.Polygon> polygons, double turn, Map<Mesh.Material, TutorialRender.Sprite> sprites) {
        double yaw = YAW + turn;
        // Camera axes: right, up, and toward the camera.
        double[] back = {Math.sin(yaw) * Math.cos(PITCH), Math.sin(PITCH), Math.cos(yaw) * Math.cos(PITCH)};
        double[] right = norm(cross(new double[]{0, 1, 0}, back));
        double[] up = cross(back, right);
        // The projected bounds of every vertex.
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (var p : polygons) {
            var v = p.positions();
            for (int i = 0; i < v.length; i += 3) {
                double x = v[i] * right[0] + v[i + 1] * right[1] + v[i + 2] * right[2], y = v[i] * up[0] + v[i + 1] * up[1] + v[i + 2] * up[2];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxY - minY > CAP * (maxX - minX)) minY = maxY - CAP * (maxX - minX);
        int n = SIZE * SUPER, margin = SUPER; // one icon px for the rim
        double scale = (n - 2.0 * margin) / Math.max(maxX - minX, maxY - minY);
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        var rgb = new int[n * n];
        var z = new double[n * n];
        Arrays.fill(z, Double.MAX_VALUE);
        var covered = new boolean[n * n];
        for (var p : polygons) {
            var v = p.positions();
            var uv = p.uvs();
            var nm = p.normals();
            var sprite = sprites.get(p.material());
            int channel = p.material().channel();
            int tint = channel < 0 ? 0xFFFFFF : Paint.tint(Paint.DEFAULT.colour(channel));
            for (int c = 1; c + 1 < p.size(); c++) {
                int[] k = {0, c, c + 1};
                double[][] s = new double[3][];
                double[] light = new double[3];
                for (int j = 0; j < 3; j++) {
                    int i = 3 * k[j];
                    double x = v[i] * right[0] + v[i + 1] * right[1] + v[i + 2] * right[2];
                    double y = v[i] * up[0] + v[i + 1] * up[1] + v[i + 2] * up[2];
                    double d = -(v[i] * back[0] + v[i + 1] * back[1] + v[i + 2] * back[2]);
                    s[j] = new double[]{n / 2.0 + (x - cx) * scale, n / 2.0 - (y - cy) * scale, d};
                    light[j] = TutorialRender.shade(nm[i], nm[i + 1], nm[i + 2]);
                }
                // Back faces culled, as the chunk renderer culls them.
                double[] a = {v[3 * k[0]], v[3 * k[0] + 1], v[3 * k[0] + 2]}, b1 = {v[3 * k[1]], v[3 * k[1] + 1], v[3 * k[1] + 2]}, b2 = {v[3 * k[2]], v[3 * k[2] + 1], v[3 * k[2] + 2]};
                double[] face = cross(new double[]{b1[0] - a[0], b1[1] - a[1], b1[2] - a[2]}, new double[]{b2[0] - a[0], b2[1] - a[1], b2[2] - a[2]});
                if (face[0] * back[0] + face[1] * back[1] + face[2] * back[2] <= 0) continue;
                double area = edge(s[0], s[1], s[2][0], s[2][1]);
                if (Math.abs(area) < 1e-12) continue;
                int x0 = (int) Math.max(0, Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0])))), x1 = (int) Math.min(n - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
                int y0 = (int) Math.max(0, Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1])))), y1 = (int) Math.min(n - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) {
                        double px = x + .5, py = y + .5;
                        double w0 = edge(s[1], s[2], px, py) / area, w1 = edge(s[2], s[0], px, py) / area, w2 = edge(s[0], s[1], px, py) / area;
                        if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                        double depth = w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2];
                        if (depth > z[y * n + x]) continue;
                        int texel = 0xFFB0B0B0;
                        if (sprite != null) {
                            double u = w0 * uv[2 * k[0]] + w1 * uv[2 * k[1]] + w2 * uv[2 * k[2]], t = w0 * uv[2 * k[0] + 1] + w1 * uv[2 * k[1] + 1] + w2 * uv[2 * k[2] + 1];
                            texel = sprite.sample(u, t);
                            if ((texel >>> 24) == 0) continue;
                        }
                        double l = w0 * light[0] + w1 * light[1] + w2 * light[2];
                        int r = (int) Math.min(255, (texel >> 16 & 255) * (tint >> 16 & 255) / 255.0 * l), g = (int) Math.min(255, (texel >> 8 & 255) * (tint >> 8 & 255) / 255.0 * l),
                                b = (int) Math.min(255, (texel & 255) * (tint & 255) / 255.0 * l);
                        z[y * n + x] = depth;
                        rgb[y * n + x] = r << 16 | g << 8 | b;
                        covered[y * n + x] = true;
                    }
            }
        }
        // Box filter down to the icon: colour the mean of the covered samples, alpha their share.
        var out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        var alpha = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) {
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int j = 0; j < SUPER; j++)
                    for (int i = 0; i < SUPER; i++) {
                        int at = (y * SUPER + j) * n + x * SUPER + i;
                        if (!covered[at]) continue;
                        r += rgb[at] >> 16 & 255;
                        g += rgb[at] >> 8 & 255;
                        b += rgb[at] & 255;
                        count++;
                    }
                // Item sprites are cut out (alpha 0 or 255): a pixel is the body's where half its samples are.
                if (count * 2 < SUPER * SUPER) continue;
                alpha[y * SIZE + x] = 255;
                out.setRGB(x, y, 0xFF000000 | (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count));
            }
        // The rim: a transparent pixel beside the body takes a dark outline colour.
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) {
                if (alpha[y * SIZE + x] != 0) continue;
                boolean edge = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int xx = x + d[0], yy = y + d[1];
                    if (xx >= 0 && yy >= 0 && xx < SIZE && yy < SIZE && alpha[yy * SIZE + xx] == 255) edge = true;
                }
                if (edge) out.setRGB(x, y, 0xFF1E2226);
            }
        return out;
    }

    /** Every icon at 1x and 4x with its name, ten a row, on an inventory slot's grey and a dark band. */
    static BufferedImage sheet(List<BufferedImage> icons, List<String> names) {
        int cols = 6, cellW = 300, cellH = 4 * SIZE + 28, rows = (icons.size() + cols - 1) / cols;
        var sheet = new BufferedImage(cols * cellW, rows * cellH, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x8B8B8B));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int i = 0; i < icons.size(); i++) {
            int x = (i % cols) * cellW, y = (i / cols) * cellH;
            g.setColor(new Color(0x373737));
            g.fillRect(x + 4 + 4 * SIZE + 8, y + 4, SIZE + 8, SIZE + 8);
            g.drawImage(icons.get(i), x + 4, y + 4, 4 * SIZE, 4 * SIZE, null);
            g.drawImage(icons.get(i), x + 4 + 4 * SIZE + 12, y + 8, null);
            g.setColor(new Color(0xC6C6C6));
            g.fillRect(x + 4 + 4 * SIZE + 8, y + 4 + SIZE + 12, SIZE + 8, SIZE + 8);
            g.drawImage(icons.get(i), x + 4 + 4 * SIZE + 12, y + 8 + SIZE + 12, null);
            g.setColor(Color.BLACK);
            g.drawString((i + 1) + ". " + names.get(i), x + 4, y + 4 * SIZE + 20);
            g.setColor(new Color(0x444444));
            g.drawString(EquipmentShape.Kind.values()[i].name().toLowerCase(Locale.ROOT), x + 4 + 4 * SIZE + 8 + SIZE + 14, y + 20);
        }
        g.dispose();
        return sheet;
    }

    private static double edge(double[] a, double[] b, double x, double y) {
        return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
