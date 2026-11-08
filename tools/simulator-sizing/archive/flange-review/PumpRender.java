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
 * Offline look at a unit as the chunk renderer receives it (2026-10-18, new-units lanes; this copy, batch
 * 2026-11-03-rotating-machinery, adds -Dground=blocks, -Dcap=pixels per metre, -Dfocus=x,y,z,radius for close views and -Dpaint=RRGGBB for the body paint): every shell cell's polygons
 * from {@link ShapeRasterizer#surface}, textured with the mod's sprites (painted ones tinted with {@link Paint#DEFAULT}),
 * shaded by the game's directional shade, back faces culled as the chunk renderer culls them, on a grass ground with a
 * block grid. Orthographic views, written as PNGs.
 *
 * <p>Args: {@code kind size length support outPrefix [views]}; views from front, back, left, right, top, iso (front
 * left, above), iso2 (front right), iso3 (back left), low (front left, at eye height); default {@code iso,front,right,top}.
 * The unit faces north: its front (the constructor's side) is -z. Run from the worktree root (it reads the sprites from
 * src/main/resources). Moving parts are drawn at rest.
 */
public class PumpRender {
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
        var out = Path.of(args[0]);
        Files.createDirectories(out);
        var shapes = new java.util.LinkedHashMap<String, EquipmentShape>();
        for (var form : VesselPlacement.Form.values()) {
            if (!form.designed()) continue;
            if (java.util.Set.of(Kind.STEAM_TURBINE, Kind.ADSORBER, Kind.EJECTOR).contains(form.kind())) continue;
            var size = ConstructorDefaults.sizes(form);
            var design = UnitDesign.of(form.kind());
            var shape = EquipmentShape.designed(form.kind(), size.diameter(), size.length(), Heading.NORTH,
                    design.defaultSupport(size.diameter(), size.length()), ProcessUnit.Bank.ONE);
            shapes.put(form.name(), shape);
            if (form.kind() == Kind.EXCHANGER) shapes.put("EXCHANGER_CROSSFLOW", shape.withExchangerType(ExchangerType.CROSSFLOW));
        }
        for (var axis : EquipmentShape.Orientation.values()) {
            int support = EquipmentShape.defaultSupport(5.5, axis);
            shapes.put("DRUM_" + axis, EquipmentShape.drum(5.5, 8, axis, Heading.NORTH, support,
                    EquipmentShape.defaultConnections(5.5, 8, axis, support, false)));
        }
        shapes.put("COLUMN", EquipmentShape.column(3, new ColumnTrays(12, 6, List.of(3), List.of(10), List.of()),
                Heading.NORTH, EquipmentShape.minimumSkirt(3)));
        int rendered = 0;
        for (var entry : shapes.entrySet()) {
            var shape = entry.getValue();
            if(Boolean.getBoolean("onlyRotating") && !java.util.Set.of(Kind.PUMP,Kind.COMPRESSOR,Kind.CENTRIFUGAL_COMPRESSOR).contains(shape.kind())) continue;
            var nozzles = shape.nozzles();
            if (nozzles.isEmpty()) {System.out.println("NO PIPE PORTS " + entry.getKey()); continue;}
            var ports = new java.util.LinkedHashSet<Integer>();
            ports.add(0); ports.add(nozzles.size()-1);
            for (int port : ports) {
                var nozzle = nozzles.get(port);
                String name=entry.getKey()+"_"+port+"_"+nozzle.role();
                if (Files.exists(out.resolve(name+".png"))) {rendered++; continue;}
                double bore=NozzleBore.proposed(shape, port);
                if(System.getProperty("bore")!=null) bore=Math.min(bore,Double.parseDouble(System.getProperty("bore")));
                double od = PipeSizes.outside(bore);
                var pipes = new double[nozzles.size()]; pipes[port] = od;
                var transform=shape.transform();
                var dir=transform.direction(nozzle.direction());
                int axis=Math.abs(dir[0])>.5?0:Math.abs(dir[1])>.5?1:2;
                int sign=(int)dir[axis];
                if(Boolean.getBoolean("onlyDown") && (axis!=1 || sign>0)) continue;
                var focus = transform.point(nozzle.face());
                System.setProperty("focus", focus[0]+","+focus[1]+","+focus[2]+",1.4");
                var sprites = sprites();
                var triangles = new ArrayList<Triangle>();
                var cellCentre=nozzle.pipeBlock();
                var placed=transform.point(new double[]{cellCentre[0]+.5,cellCentre[1]+.5,cellCentre[2]+.5});
                int[] centre={(int)Math.floor(placed[0]),(int)Math.floor(placed[1]),(int)Math.floor(placed[2])};
                for (int x = Math.max(0,centre[0]-3); x <= Math.min(shape.sizeX()-1,centre[0]+3); x++)
                    for (int y = Math.max(0,centre[1]-3); y <= Math.min(shape.sizeY()-1,centre[1]+3); y++)
                        for (int z = Math.max(0,centre[2]-3); z <= Math.min(shape.sizeZ()-1,centre[2]+3); z++) {
                            var cell = new ShapeRasterizer.Cell(x,y,z);
                            long codes = ShapeRasterizer.cellCodes(shape, cell, pipes);
                            var key = ShapeRasterizer.key(shape,x,y,z,codes);
                            for (var polygon : ShapeRasterizer.surface(key)) add(triangles,polygon,x,y,z);
                            for (var polygon : PortMarkings.surface(key)) add(triangles,polygon,x,y,z);
                        }
                int face = axis==0 ? (sign>0?5:4) : axis==1 ? (sign>0?1:0) : (sign>0?3:2);
                int code = PipeSizes.code(od), mask = 1<<face | 1<<(face^1);
                long faces = (long)code<<(7*face) | (long)code<<(7*(face^1));
                for (int step=0;step<2;step++) {
                    int[] at=centre.clone(); at[axis]+=sign*step;
                    var key=new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,
                            face,mask,code,faces,step==0?1<<(face^1):0,false);
                    for(var polygon:LineGeometry.polygons(key)) add(triangles,polygon,at[0],at[1],at[2]);
                }
                double[] depth = axis==1 ? new double[]{-.7,-.5*sign,1}
                        : new double[]{-dir[0]+.55*dir[2],-.4,-dir[2]-.55*dir[0]};
                render(triangles,shape,unit(depth),sprites,out.resolve(name+".png").toString());
                System.out.println("RENDERED " + name + " OD="+PipeSizes.outsideOf(code)+" bolts="+PipeSizes.drawnBolts(od));
                rendered++;
            }
        }
        System.out.println("TOTAL " + rendered + " connection views across " + shapes.size() + " equipment configurations");
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
        int gm = Integer.getInteger("ground", 2), gx0 = -gm, gz0 = -gm, gx1 = shape.sizeX() + gm, gz1 = shape.sizeZ() + gm;
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        var corners = new ArrayList<double[]>();
        for (var t : triangles) corners.addAll(Arrays.asList(t.p));
        for (int x : new int[]{gx0, gx1})
            for (int z : new int[]{gz0, gz1}) corners.add(new double[]{x, 0, z});
        var focus = System.getProperty("focus");
        if (focus != null) {
            var fp = Arrays.stream(focus.split(",")).mapToDouble(Double::parseDouble).toArray();
            corners.clear();
            for (int i = 0; i < 8; i++) corners.add(new double[]{fp[0] + ((i & 1) * 2 - 1) * fp[3], fp[1] + ((i >> 1 & 1) * 2 - 1) * fp[3], fp[2] + ((i >> 2 & 1) * 2 - 1) * fp[3]});
        }
        for (var v : corners) {
            minX = Math.min(minX, dot(v, right));
            maxX = Math.max(maxX, dot(v, right));
            minY = Math.min(minY, dot(v, up));
            maxY = Math.max(maxY, dot(v, up));
        }
        double scale = Math.max(10, Math.min(Double.parseDouble(System.getProperty("cap", "64")), 1200 / Math.max(maxX - minX, maxY - minY)));
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
        for (int x = gx0; x < gx1 && !Boolean.getBoolean("hideGround"); x++)
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
            int tint = channel < 0 ? 0xFFFFFF : channel == Paint.BODY && System.getProperty("paint") != null ? Integer.parseInt(System.getProperty("paint"), 16) : Paint.DEFAULT.colour(channel);
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





