import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.tutorial.FilmLayout;
import com.wormzjl.icheme.client.gui.tutorial.FilmOverlay;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.scene.Bodies;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Projection;
import com.wormzjl.icheme.science.tutorial.scene.Scene;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Offline look at the tutorial films (batch 2026-11-12-tutorial-system, P0, P2b; tools/tutorial-renders): every stop of
 * every film from {@link Scene#at} as the film screen shows it (its frame from {@link FilmLayout}, the title, the pips,
 * the scene on the HMI glass, the overlays of {@link FilmOverlay}, the caption and the footer keys), light theme, °C, in
 * the game's bitmap font at {@link HeatDiagramRender#SCALE} image px a GUI px: at 464 by 254, and the last stop at the
 * 320 by 240 window's panel (312 by 232). The scene goes through the one {@link Projection} the game's view uses.
 * Polygons are rasterised with a z-buffer, the mod's sprites, the paint tint and the game's directional shade per vertex;
 * back faces are culled as the chunk renderer culls them, except on a cut actor, drawn two-sided as the game's view draws
 * it. Dots are depth-tested discs on a dark rim, fires opaque tongues in the same pass, liquids translucent after it,
 * writing no depth ({@link Bodies}).
 *
 * <p>Args: {@code outDir [film,...]}; {@code -Dframes=n} adds n frames between stops ({@code <film>-frames.png}). Run from
 * the worktree root (it reads the sprites and the English lines from src/main/resources, the font from the game's
 * resources jar).
 */
public class TutorialRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";
    /** Image pixels a GUI pixel. */
    static final int SCALE = HeatDiagramRender.SCALE;
    /** The smallest window's panel (320 by 240 less the screen's margins). */
    static final int SMALL_W = 312, SMALL_H = 232;

    record Sprite(int width, int height, int[] argb) {
        int sample(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * width), width), y = Math.floorMod((int) Math.floor(v * height), height);
            return argb[y * width + x];
        }
    }

    public static void main(String[] args) throws Exception {
        var out = new File(args.length > 0 ? args[0] : "tmp/tutorial-renders");
        out.mkdirs();
        List<String> names = new ArrayList<>();
        if (args.length > 1) names.addAll(Arrays.asList(args[1].split(",")));
        else Films.names().forEach(names::add);
        int frames = Integer.getInteger("frames", 0);
        var sprites = sprites();
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of(ASSETS + "lang/en_us.json"));
        var jar = Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        var lang = lang();
        for (var name : names) {
            var scene = Films.build(name);
            if (scene == null) throw new IllegalArgumentException("No film " + name);
            var stops = new ArrayList<BufferedImage>();
            for (int k = 0; k < scene.stops().size(); k++) {
                var image = render(scene, scene.stops().get(k), FilmLayout.DESIGN_WIDTH, FilmLayout.DESIGN_HEIGHT, sprites, lang);
                write(image, new File(out, name + "-stop" + (k + 1) + ".png"));
                stops.add(image);
            }
            write(sheet(List.of(stops)), new File(out, name + "-sheet.png"));
            // One stop at the smallest window: the last, where most is shown.
            write(render(scene, scene.length(), SMALL_W, SMALL_H, sprites, lang), new File(out, name + "-small.png"));
            if (frames > 0) {
                var rows = new ArrayList<List<BufferedImage>>();
                double from = 0;
                for (double stop : scene.stops()) {
                    var row = new ArrayList<BufferedImage>();
                    for (int j = 0; j <= frames + 1; j++)
                        row.add(render(scene, from + (stop - from) * j / (frames + 1), FilmLayout.DESIGN_WIDTH, FilmLayout.DESIGN_HEIGHT, sprites, lang));
                    rows.add(row);
                    from = stop;
                }
                write(sheet(rows), new File(out, name + "-frames.png"));
            }
        }
    }

    private static void write(BufferedImage image, File file) throws Exception {
        ImageIO.write(image, "png", file);
        System.out.println("wrote " + file.getPath());
    }

    /** The film screen of a {@code w} by {@code h} panel at {@code t}, as {@code FilmScreen} draws it; a grey line under it names the film, time and size. */
    static BufferedImage render(Scene scene, double t, int w, int h, Map<Mesh.Material, Sprite> sprites, Map<String, String> lang) {
        int margin = 4;
        var image = new BufferedImage((w + 2 * margin) * SCALE, (h + 2 * margin + 10) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.translate(margin, margin);
        var canvas = new HeatDiagramRender.Java2dCanvas(g);
        canvas.fill(-margin, -margin, w + margin, h + margin + 10, 0xFF101418);
        Skin skin = PanelSkin.LIGHT;
        Function<String, String> tr = key -> lang.getOrDefault(key, key);
        var captions = new ArrayList<String>();
        for (var key : scene.captionKeys()) captions.add(tr.apply(key));
        var layout = FilmLayout.of(w, h, scene.stops().size(), FilmLayout.captionLines(captions, w, canvas.measure()));
        skin.page(canvas, new Rect(0, 0, w, h));
        var glass = skin.well(canvas, layout.scene());
        var frame = scene.at(t);
        skin.title(canvas, 10, 10, tr.apply(scene.title()));
        var pips = layout.pips();
        for (int i = 0; i < pips.size(); i++) {
            var p = pips.get(i);
            if (i <= frame.stop()) canvas.fill(p.x(), p.y(), p.right(), p.bottom(), skin.tone(Palette.ACCENT));
            else canvas.outline(p.x(), p.y(), p.w(), p.h(), skin.tone(Palette.MUTED));
        }
        double fit = FilmLayout.fit(glass);
        var view = new Rect((glass.x() + margin) * SCALE, (glass.y() + margin) * SCALE, glass.w() * SCALE, glass.h() * SCALE);
        var projection = Projection.of(frame.camera(), 0, 0, 1, fit * SCALE, view.x() + view.w() / 2.0, view.y() + view.h() / 2.0);
        draw(image, view, frame, projection, sprites);
        // The overlays in GUI px, on the projection the scene was drawn with.
        var gui = Projection.of(frame.camera(), 0, 0, 1, fit, glass.x() + glass.w() / 2.0, glass.y() + glass.h() / 2.0);
        var overlay = FilmOverlay.layout(frame, gui, glass, canvas.measure(), tr, Card.CELSIUS);
        FilmOverlay.draw(canvas, frame, overlay, tr);
        var caption = layout.caption();
        var lines = new ArrayList<String>();
        for (var key : frame.captions()) lines.addAll(canvas.measure().wrap(tr.apply(key), caption.w()));
        for (int i = 0; i < lines.size(); i++) skin.text(canvas, lines.get(i), caption.x(), caption.y() + i * 10, Palette.TEXT);
        boolean end = frame.stop() == scene.stops().size() - 1 && t >= scene.length();
        var keys = new String[]{"back", "replay", end ? "close" : "next"};
        for (int i = 0; i < 3; i++)
            skin.button(canvas, layout.buttons().get(i), tr.apply("tutorial.icheme.film." + keys[i]),
                    i == 2 ? Skin.Press.PRIMARY : i == 0 && frame.stop() == 0 ? Skin.Press.OFF : Skin.Press.REST, false);
        int missing = 0;
        for (var box : overlay.labels()) if (box == null) missing++;
        canvas.text(String.format(java.util.Locale.ROOT, "%s  t = %.2f s  stop %d  %d x %d%s", tr.apply(scene.title()), t, frame.stop() + 1, w, h,
                missing > 0 ? "  LABELS NOT PLACED: " + missing : ""), -2, h + 3, 0xFF8A8A86);
        g.dispose();
        return image;
    }

    /** The frame's actors, dots, fires and liquids in {@code view} (image pixels), depth-tested. */
    static void draw(BufferedImage image, Rect view, Frame frame, Projection projection, Map<Mesh.Material, Sprite> sprites) {
        int w = image.getWidth();
        var zbuf = new double[w * image.getHeight()];
        Arrays.fill(zbuf, Double.MAX_VALUE);
        var forward = projection.forward();
        for (var actor : frame.actors()) {
            for (var polygon : actor.polygons()) {
                var p = polygon.positions();
                var uv = polygon.uvs();
                var nm = polygon.normals();
                var sprite = sprites.get(polygon.material());
                int channel = polygon.material().channel();
                int tint = channel < 0 ? 0xFFFFFF : Paint.tint(Paint.DEFAULT.colour(channel));
                for (int corner = 1; corner + 1 < polygon.size(); corner++) {
                    int[] c = {0, corner, corner + 1};
                    double[] a = vertex(p, c[0]), b = vertex(p, c[1]), d = vertex(p, c[2]);
                    double[] n = cross(sub(b, a), sub(d, a));
                    if (!actor.twoSided() && dot(n, forward) >= 0) continue; // back face: culled as the chunk renderer culls it
                    double[][] s = new double[3][];
                    double[] light = new double[3];
                    for (int k = 0; k < 3; k++) {
                        s[k] = projection.screen(p[3 * c[k]], p[3 * c[k] + 1], p[3 * c[k] + 2]);
                        light[k] = shade(nm[3 * c[k]], nm[3 * c[k] + 1], nm[3 * c[k] + 2]);
                    }
                    double alpha = actor.alpha();
                    triangle(image, zbuf, view, s, (w0, w1, w2) -> {
                        int texel = 0xFFFF00FF;
                        if (sprite != null) {
                            double u = w0 * uv[2 * c[0]] + w1 * uv[2 * c[1]] + w2 * uv[2 * c[2]], v = w0 * uv[2 * c[0] + 1] + w1 * uv[2 * c[1] + 1] + w2 * uv[2 * c[2] + 1];
                            texel = sprite.sample(u, v);
                            if ((texel >>> 24) == 0) return -1; // as the GUI shader discards
                        }
                        double l = w0 * light[0] + w1 * light[1] + w2 * light[2];
                        int r = (int) Math.min(255, (texel >> 16 & 255) * (tint >> 16 & 255) / 255.0 * l), gr = (int) Math.min(255, (texel >> 8 & 255) * (tint >> 8 & 255) / 255.0 * l),
                                bl = (int) Math.min(255, (texel & 255) * (tint & 255) / 255.0 * l);
                        return r << 16 | gr << 8 | bl;
                    }, alpha, true);
                }
            }
        }
        double ppm = projection.pixelsPerMetre();
        for (var d : frame.dots()) {
            var s = projection.screen(d.x(), d.y(), d.z());
            double r = d.size() * ppm;
            disc(image, zbuf, view, s, r * 1.3, 0xFF1A1A1A);
            disc(image, zbuf, view, new double[]{s[0], s[1], s[2] - .05 / 15}, r, 0xFF000000 | d.kind().rgb);
        }
        // Fires: opaque tongues facing the camera, in the depth pass.
        for (var f : frame.flames())
            for (var tongue : Bodies.flame(f, projection.right(), projection.up(), projection.forward())) {
                var s = new double[][]{screen(projection, tongue.a()), screen(projection, tongue.b()), screen(projection, tongue.c())};
                int rgb = tongue.rgb();
                triangle(image, zbuf, view, s, (w0, w1, w2) -> rgb, 1, true);
            }
        // Liquids: translucent after everything opaque, depth-tested, writing no depth, both sides.
        for (var l : frame.liquids())
            for (var face : Bodies.liquid(l)) {
                var p = face.positions();
                for (int corner = 1; corner + 1 < face.size(); corner++) {
                    int[] c = {0, corner, corner + 1};
                    var s = new double[3][];
                    for (int k = 0; k < 3; k++) s[k] = projection.screen(p[3 * c[k]], p[3 * c[k] + 1], p[3 * c[k] + 2]);
                    triangle(image, zbuf, view, s, (w0, w1, w2) -> l.rgb(), l.alpha(), false);
                }
            }
    }

    private static double[] screen(Projection p, double[] v) {
        return p.screen(v[0], v[1], v[2]);
    }

    private interface Colour {
        /** The colour at barycentric weights, or -1 for none (a discarded texel). */
        int at(double w0, double w1, double w2);
    }

    private static void triangle(BufferedImage image, double[] zbuf, Rect view, double[][] s, Colour colour, double alpha, boolean depth) {
        int w = image.getWidth();
        int x0 = (int) Math.max(view.x(), Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0])))), x1 = (int) Math.min(view.right() - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
        int y0 = (int) Math.max(view.y(), Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1])))), y1 = (int) Math.min(view.bottom() - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
        double area = edge(s[0], s[1], s[2][0], s[2][1]);
        if (Math.abs(area) < 1e-12) return;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                double px = x + .5, py = y + .5;
                double w0 = edge(s[1], s[2], px, py) / area, w1 = edge(s[2], s[0], px, py) / area, w2 = edge(s[0], s[1], px, py) / area;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                double z = w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2];
                if (z > zbuf[y * w + x]) continue;
                int rgb = colour.at(w0, w1, w2);
                if (rgb == -1) continue;
                if (depth) zbuf[y * w + x] = z;
                image.setRGB(x, y, alpha >= 1 ? rgb : blend(image.getRGB(x, y), rgb, alpha));
            }
    }

    private static void disc(BufferedImage image, double[] zbuf, Rect view, double[] s, double r, int argb) {
        int w = image.getWidth();
        for (int y = (int) Math.max(view.y(), Math.floor(s[1] - r)); y <= Math.min(view.bottom() - 1, Math.ceil(s[1] + r)); y++)
            for (int x = (int) Math.max(view.x(), Math.floor(s[0] - r)); x <= Math.min(view.right() - 1, Math.ceil(s[0] + r)); x++) {
                double dx = x + .5 - s[0], dy = y + .5 - s[1];
                if (dx * dx + dy * dy > r * r || s[2] > zbuf[y * w + x]) continue;
                zbuf[y * w + x] = s[2];
                image.setRGB(x, y, argb);
            }
    }

    /** Stops (or frames) side by side, each row a stop's way; 12 px apart. */
    static BufferedImage sheet(List<List<BufferedImage>> rows) {
        int gap = 12, iw = rows.getFirst().getFirst().getWidth(), ih = rows.getFirst().getFirst().getHeight(), cols = 0;
        for (var row : rows) cols = Math.max(cols, row.size());
        var sheet = new BufferedImage(gap + cols * (iw + gap), gap + rows.size() * (ih + gap), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(new Color(0x3A3D40));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int r = 0; r < rows.size(); r++)
            for (int c = 0; c < rows.get(r).size(); c++) g.drawImage(rows.get(r).get(c), gap + c * (iw + gap), gap + r * (ih + gap), null);
        g.dispose();
        return sheet;
    }

    /** The game's directional shade of a normal (NeoForge {@code QuadLighter.calculateShade}), as the shell model bakes it, without the sheen. */
    static double shade(double x, double y, double z) {
        return Math.min(x * x * .6 + y * y * ((3 + y) / 4) + z * z * .8, 1);
    }

    static int blend(int under, int over, double alpha) {
        int r = (int) Math.round((under >> 16 & 255) * (1 - alpha) + (over >> 16 & 255) * alpha);
        int g = (int) Math.round((under >> 8 & 255) * (1 - alpha) + (over >> 8 & 255) * alpha);
        int b = (int) Math.round((under & 255) * (1 - alpha) + (over & 255) * alpha);
        return r << 16 | g << 8 | b;
    }

    /** The sprites by material, from the shell model's texture names (as tools/pump-sprites/probes/PumpRender reads them). */
    static Map<Mesh.Material, Sprite> sprites() throws Exception {
        String json = Files.readString(Path.of(ASSETS + "models/block/equipment_shell.json"));
        var names = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"icheme:block/(\\w+)\"").matcher(json);
        var files = new HashMap<String, String>();
        while (names.find()) files.put(names.group(1), names.group(2));
        var out = new EnumMap<Mesh.Material, Sprite>(Mesh.Material.class);
        for (var material : Mesh.Material.values()) {
            String file = files.get(material.texture());
            if (file == null) continue;
            var image = ImageIO.read(new File(ASSETS + "textures/block/" + file + ".png"));
            // An animated strip shows its first frame.
            int w = image.getWidth(), h = image.getHeight() > w && image.getHeight() % w == 0 ? w : image.getHeight();
            int[] argb = new int[w * h];
            image.getRGB(0, 0, w, h, argb, 0, w);
            out.put(material, new Sprite(w, h, argb));
        }
        return out;
    }

    /** The English lines. */
    static Map<String, String> lang() throws Exception {
        var json = com.google.gson.JsonParser.parseString(Files.readString(Path.of(ASSETS + "lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var out = new HashMap<String, String>();
        for (var e : json.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    private static double[] vertex(double[] p, int i) {
        return new double[]{p[3 * i], p[3 * i + 1], p[3 * i + 2]};
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
}
