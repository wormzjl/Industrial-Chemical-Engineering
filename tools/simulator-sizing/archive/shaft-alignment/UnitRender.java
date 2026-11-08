package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Offline look at a unit as the chunk renderer receives it (2026-10-18, new-units lanes): every shell cell's polygons
 * from {@link ShapeRasterizer#surface}, textured with the mod's sprites (painted ones tinted with {@link Paint#DEFAULT}),
 * shaded by the game's directional shade, back faces culled as the chunk renderer culls them, on a grass ground with a
 * block grid. Orthographic views, written as PNGs.
 *
 * <p>Args: {@code kind size length support outPrefix [views]}; views from front, back, left, right, top, iso (front
 * left, above), iso2 (front right), iso3 (back left), low (front left, at eye height); default {@code iso,front,right,top}.
 * The unit faces north: its front (the constructor's side) is -z. Run from the worktree root (it reads the sprites from
 * src/main/resources). Moving parts are drawn at rest.
 */
public class UnitRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";

    private record Triangle(double[][] p, double[][] uv, double[] normal, Mesh.Material material) {
    }

    private record Sprite(int width, int height, int[] argb) {
        int sample(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * width), width), y = Math.floorMod((int) Math.floor(v * height), height);
            return argb[y * width + x];
        }
    }

    public static void main(String[] args) throws Exception {
        if(args[0].equals("SHAFT")) {
            System.setProperty("shaftPreview","true");
            var shape=EquipmentShape.unit(Kind.PUMP,.15,1,Heading.valueOf(System.getProperty("heading","NORTH")),0);
            var triangles=new ArrayList<Triangle>();
            for(var polygon:ShaftGeometry.polygons(Integer.getInteger("axis",1),8))add(triangles,polygon,0,0,0);
            var sprites=sprites();
            render(triangles,shape,unit(new double[]{-1,-.75,-1.15}),sprites,args[1]);
            return;
        }
        boolean drum = args[0].equals("DRUM");
        var kind = drum ? Kind.DRUM : Kind.valueOf(args[0]);
        double size = Double.parseDouble(args[1]);
        int length = Integer.parseInt(args[2]), support = Integer.parseInt(args[3]);
        if(length<0) length=ProcessUnit.defaultLength(kind,size);
        String out = args[4];
        var views = args.length > 5 ? args[5].split(",") : new String[]{"iso", "front", "right", "top"};
        if(support<0) support=kind==Kind.DRUM?EquipmentShape.defaultSupport(size,EquipmentShape.Orientation.VERTICAL):kind==Kind.COLUMN?EquipmentShape.minimumSkirt(size):ProcessUnit.defaultSupport(kind,size,length);
        var shape = kind==Kind.COLUMN?EquipmentShape.column(size,ColumnTrays.simple(length,length/2),Heading.valueOf(System.getProperty("heading","NORTH")),support):
                drum?EquipmentShape.drum(size,length,EquipmentShape.Orientation.VERTICAL,Heading.valueOf(System.getProperty("heading","NORTH")),support):
                kind==Kind.SPHERE?EquipmentShape.storage(kind,size,1,Heading.valueOf(System.getProperty("heading","NORTH")),support):EquipmentShape.unit(kind,size,length,Heading.valueOf(System.getProperty("heading","NORTH")),support);
        var sprites = sprites();
        var triangles = new ArrayList<Triangle>();
        for (var cell : ShapeRasterizer.layout(shape).shell())
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                add(triangles, polygon, cell.x(), cell.y(), cell.z());
        if(Boolean.getBoolean("join"))for(var end:ShapeRasterizer.shafts(shape)) {
            var cell=end.block();int axis=end.dx()!=0?0:end.dy()!=0?1:2;
            for(var polygon:ShaftGeometry.polygons(axis,8))add(triangles,polygon,cell.x(),cell.y(),cell.z());
        }
        new File(out).getAbsoluteFile().getParentFile().mkdirs();
        for (var view : views) {
            double[] depth = switch (view) {
                case "joint" -> {
                    var end=ShapeRasterizer.shafts(shape).getFirst();
                    yield new double[]{-end.dx()-.5*end.dz(),-.5,-end.dz()+.5*end.dx()};
                }
                case "front" -> new double[]{0, 0, 1};
                case "back" -> new double[]{0, 0, -1};
                case "left" -> new double[]{-1, 0, 0};
                case "right" -> new double[]{1, 0, 0};
                case "top" -> new double[]{0, -1, 0};
                case "iso" -> new double[]{-1, -.75, 1.15};
                case "iso2" -> new double[]{1, -.75, 1.15};
                case "iso3" -> new double[]{-1, -.75, -1.15};
                case "low" -> new double[]{-1, -.12, 1.3};
                default -> throw new IllegalArgumentException("No view " + view);
            };
            render(triangles, shape, unit(depth), sprites, out + "-" + view + ".png");
            System.out.println("wrote " + out + "-" + view + ".png");
        }
    }

    /** The sprites by material, from the shell model's texture names. */
    private static Map<Mesh.Material, Sprite> sprites() throws Exception {
        String json = Files.readString(Path.of(ASSETS + "models/block/equipment_shell.json"));
        var names = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"icheme:block/(\\w+)\"").matcher(json);
        var files = new java.util.HashMap<String, String>();
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

    private static void add(List<Triangle> out, Mesh.Polygon polygon, int x, int y, int z) {
        double[] p = polygon.positions(), uv = polygon.uvs();
        int n = polygon.size();
        for (int i = 1; i + 1 < n; i++) {
            int[] c = {0, i, i + 1};
            double[][] pts = new double[3][], uvs = new double[3][];
            for (int k = 0; k < 3; k++) {
                pts[k] = new double[]{p[3 * c[k]] + x, p[3 * c[k] + 1] + y, p[3 * c[k] + 2] + z};
                if(Boolean.getBoolean("shaftPreview")) {
                    double angle=Double.parseDouble(System.getProperty("angle","0")), cosine=Math.cos(angle),sine=Math.sin(angle);
                    int axis=Integer.getInteger("axis",1), a=(axis+1)%3,b=(axis+2)%3;
                    double u=pts[k][a]-.5,v=pts[k][b]-.5;
                    pts[k][a]=.5+cosine*u-sine*v;pts[k][b]=.5+sine*u+cosine*v;
                }
                uvs[k] = new double[]{uv[2 * c[k]], uv[2 * c[k] + 1]};
            }
            var normal = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]));
            double l = Math.sqrt(dot(normal, normal));
            if (l < 1e-12) continue;
            out.add(new Triangle(pts, uvs, new double[]{normal[0] / l, normal[1] / l, normal[2] / l}, polygon.material()));
        }
    }

    /** The game's directional shade of a face normal (NeoForge {@code QuadLighter.calculateShade}, no constant ambient). */
    private static double shade(double[] n) {
        return Math.min(n[0] * n[0] * .6 + n[1] * n[1] * ((3 + n[1]) / 4) + n[2] * n[2] * .8, 1);
    }

    private static void render(List<Triangle> triangles, EquipmentShape shape, double[] depth, Map<Mesh.Material, Sprite> sprites, String file) throws Exception {
        double[] right = unit(Math.abs(depth[1]) > .99 ? new double[]{1, 0, 0} : cross(depth, new double[]{0, 1, 0}));
        double[] up = Math.abs(depth[1]) > .99 ? new double[]{0, 0, -1} : cross(right, depth);
        // The ground: two blocks round the box, with its block grid.
        int gx0 = -2, gz0 = -2, gx1 = shape.sizeX() + 2, gz1 = shape.sizeZ() + 2;
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        var corners = new ArrayList<double[]>();
        for (var t : triangles) corners.addAll(Arrays.asList(t.p));
        for (int x : new int[]{gx0, gx1})
            for (int z : new int[]{gz0, gz1}) corners.add(new double[]{x, 0, z});
        if(Boolean.getBoolean("manway") || Boolean.getBoolean("shaft") || Boolean.getBoolean("shaftPreview")) {
            double[] focus;
            if(Boolean.getBoolean("shaftPreview")) {
                focus=new double[]{.5,.5,.5};
            } else if(Boolean.getBoolean("shaft")) {
                focus=shape.transform().point(shape.shaftEnds().getFirst().face());
            } else if(shape.kind()==Kind.COLUMN) {
                var platform=ColumnStructure.plan(shape).platforms().stream().filter(ColumnStructure.Platform::manway).findFirst().orElseThrow();
                focus=shape.transform().point(new double[]{shape.axis(),platform.manwayHeight(),shape.axis()+shape.outerRadius()+Manway.REACH});
            } else {
                var manway=shape.manway();
                focus=shape.transform().point(manway.at(manway.face(),0,0));
            }
            corners.clear();
            for(double x:new double[]{focus[0]-1.1,focus[0]+1.1})for(double y:new double[]{focus[1]-1.1,focus[1]+1.1})for(double z:new double[]{focus[2]-1.3,focus[2]+.3})corners.add(new double[]{x,y,z});
        }
        for (var v : corners) {
            minX = Math.min(minX, dot(v, right));
            maxX = Math.max(maxX, dot(v, right));
            minY = Math.min(minY, dot(v, up));
            maxY = Math.max(maxY, dot(v, up));
        }
        double scale = Boolean.getBoolean("manway") || Boolean.getBoolean("shaft") || Boolean.getBoolean("shaftPreview")?280:Math.max(10, Math.min(100, 1400 / Math.max(maxX - minX, maxY - minY)));
        int w = (int) Math.ceil((maxX - minX) * scale) + 20, h = (int) Math.ceil((maxY - minY) * scale) + 20;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var zbuf = new double[w * h];
        Arrays.fill(zbuf, Double.MAX_VALUE);
        int sky = 0x9CC3F5;
        for (int i = 0; i < w * h; i++) image.setRGB(i % w, i / w, sky);
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
        // Grass with the block grid (a line every block) under the unit.
        for (int x = gx0; x < gx1; x++)
            for (int z = gz0; z < gz1; z++) {
                double[] a = {x, -.001, z}, b = {x + 1, -.001, z}, c = {x + 1, -.001, z + 1}, d = {x, -.001, z + 1};
                int fx0 = x, fz0 = z;
                Colour grass = (w0, w1, w2) -> 0x6A9A3C;
                raster.fill(a, b, c, grass);
                raster.fill(a, c, d, grass);
                double t = .03;
                for (double[][] line : new double[][][]{{{fx0, -.0005, fz0}, {fx0 + 1, -.0005, fz0}, {fx0 + 1, -.0005, fz0 + t}, {fx0, -.0005, fz0 + t}},
                        {{fx0, -.0005, fz0}, {fx0 + t, -.0005, fz0}, {fx0 + t, -.0005, fz0 + 1}, {fx0, -.0005, fz0 + 1}}}) {
                    Colour dark = (w0, w1, w2) -> 0x557D30;
                    raster.fill(line[0], line[1], line[2], dark);
                    raster.fill(line[0], line[2], line[3], dark);
                }
            }
        for (var t : triangles) {
            if (dot(t.normal, depth) >= 0) continue; // back face: culled as the chunk renderer culls it
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
                    if ((texel >>> 24) < 128) return -1; // cutout
                }
                int r = (int) Math.min(255, (texel >> 16 & 255) * ftr * light), g = (int) Math.min(255, (texel >> 8 & 255) * ftg * light),
                        b = (int) Math.min(255, (texel & 255) * ftb * light);
                return r << 16 | g << 8 | b;
            });
        }
        ImageIO.write(image, "png", new File(file));
    }

    private interface Colour {
        /** The colour at barycentric weights, or -1 for none (a cutout texel). */
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
