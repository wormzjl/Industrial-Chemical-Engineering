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
 * Offline look at a storage vessel (batch 2026-10-21-floating-roof-tanks, WP3; from tools/new-units/UnitRender) as the
 * chunk renderer receives it: every shell cell's polygons from {@link ShapeRasterizer#surface}, textured with the mod's
 * sprites (painted ones tinted with {@link Paint#DEFAULT}), shaded by the game's directional shade, back faces culled as
 * the chunk renderer culls them, on a grass ground with a block grid; a floating-roof tank's client-drawn roof and
 * rolling ladder ({@link ShapeRasterizer#floating}) placed for a liquid {@code level} m deep. Orthographic views, PNGs.
 *
 * <p>Args: {@code kind size length support level outPrefix [views]}; views from front, back, left, right, top, iso (front
 * left, above), iso2 (front right), iso3 (back left), low (front left, at eye height); default {@code iso,front,right,top}.
 * The vessel faces north: its front (the constructor's side) is -z. Run from the worktree root (it reads the sprites from
 * src/main/resources).
 */
public class GaugeEnclosureRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";

    private record Triangle(double[][] p, double[][] uv, double[] normal, Mesh.Material material, int colour) {
    }

    private record Sprite(int width, int height, int[] argb) {
        int sample(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * width), width), y = Math.floorMod((int) Math.floor(v * height), height);
            return argb[y * width + x];
        }
    }

    public static void main(String[] args) throws Exception {
        var kind = Kind.valueOf(args[0]);
        double size = Double.parseDouble(args[1]);
        int length = Integer.parseInt(args[2]), support = Integer.parseInt(args[3]);
        double level = Double.parseDouble(args[4]);
        String out = args[5];
        var views = args.length > 6 ? args[6].split(",") : new String[]{"iso", "front", "right", "top"};
        var shape = EquipmentShape.storage(kind, size, length, Heading.NORTH, support);
        var sprites = sprites();
        var triangles = new ArrayList<Triangle>();
        for (var cell : ShapeRasterizer.layout(shape).shell())
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                add(triangles, polygon, cell.x(), cell.y(), cell.z());
        if (!Boolean.getBoolean("bare")) for (var polygon : ShapeRasterizer.tankGauge(shape)) add(triangles, polygon, 0, 0, 0);
        var floating = ShapeRasterizer.floating(shape);
        if (floating != null) {
            // The roof lifted onto the liquid, the ladder turned down about its hinge onto the roof's deck.
            double lift = floating.underside(level) - floating.landed(), pitch = floating.pitch(level);
            for (var polygon : floating.roof()) add(triangles, polygon, 0, lift, 0);
            double[] axis = floating.axis(), pivot = floating.pivot();
            double sign = rotate(floating.along(), axis, pitch)[1] <= 0 ? 1 : -1;
            for (var polygon : floating.ladder()) {
                var p = polygon.positions().clone();
                var n = polygon.normals().clone();
                for (int i = 0; i < p.length; i += 3) {
                    var q = rotate(new double[]{p[i], p[i + 1], p[i + 2]}, axis, sign * pitch);
                    var m = rotate(new double[]{n[i], n[i + 1], n[i + 2]}, axis, sign * pitch);
                    for (int a = 0; a < 3; a++) {
                        p[i + a] = q[a] + pivot[a];
                        n[i + a] = m[a];
                    }
                }
                add(triangles, new Mesh.Polygon(p, polygon.uvs(), n, polygon.material()), 0, 0, 0);
            }
            if (!Boolean.getBoolean("bare")) {
            double low=floating.glassLow(), high=floating.glassHigh(), water=Math.max(low,Math.min(high,level*.35)), top=Math.max(low,Math.min(high,level));
            boolean baseline=Boolean.getBoolean("baselineBands");double half=baseline?.1:TankGaugeGeometry.BAND_HALF, markerHalf=baseline?.105:TankGaugeGeometry.MARKER_HALF, markerHeight=baseline?.1:TankGaugeGeometry.MARKER_HEIGHT;
            var colours=new int[]{0xFF835024,0xCC4A93B2}; // Illustrative liquids on the native gauge faces.
            band(triangles,floating,low,water,colours[1],half,baseline);
            band(triangles,floating,water,top,colours[0],half,baseline);
            if(baseline)band(triangles,floating,top,high,0xffd8d8d8,half,true);
            collar(triangles,floating,Math.max(low,top-markerHeight/2),Math.min(high,top+markerHeight/2),0xffb84432,markerHalf);
            }
            System.out.println("roof underside " + floating.underside(level) + " m, ladder pitch " + Math.toDegrees(pitch) + " deg");
        }
        new File(out).getAbsoluteFile().getParentFile().mkdirs();
        for (var view : views) {
            double[] depth = switch (view) {
                case "front" -> new double[]{0, 0, 1};
                case "back" -> new double[]{0, 0, -1};
                case "left" -> new double[]{-1, 0, 0};
                case "right" -> new double[]{1, 0, 0};
                case "top" -> new double[]{0, -1, 0};
                case "iso" -> new double[]{-1, -.75, 1.15};
                case "iso2" -> new double[]{1, -.75, 1.15};
                case "iso3" -> new double[]{-1, -.75, -1.15};
                case "low" -> new double[]{-1, -.12, 1.3};
                case "side" -> new double[]{-1, -.12, .18};
                case "under" -> new double[]{-.4, .45, 1};
                default -> throw new IllegalArgumentException("No view " + view);
            };
            render(triangles, shape, unit(depth), sprites, out + "-" + view + ".png");
            System.out.println("wrote " + out + "-" + view + ".png");
        }
    }

    /** Same cached product box faces/scale used by TankRenderer; baseline reproduces its former winding verbatim. */
    private static void band(List<Triangle> out,ShapeRasterizer.Floating f,double lo,double hi,int colour,double half,boolean baseline) {
        if(hi<=lo)return;colour=Math.max(200,colour>>>24)<<24|colour&0xFFFFFF;var faces=new ArrayList<Mesh.Polygon>();
        if(!baseline)faces.addAll(TankGaugeGeometry.bandFaces());
        else {
            double[][] sides={{0,0,1,0},{1,0,1,1},{1,1,0,1},{0,1,0,0}};
            for(var q:sides)faces.add(new Mesh.Polygon(new double[]{q[0],0,q[1],q[2],0,q[3],q[2],1,q[3],q[0],1,q[1]},new double[8],new double[12],Mesh.Material.OPENING));
            faces.add(new Mesh.Polygon(new double[]{0,1,0,0,1,1,1,1,1,1,1,0},new double[8],new double[12],Mesh.Material.OPENING));
        }
        for(var face:faces){var p=face.positions().clone();for(int i=0;i<p.length;i+=3){p[i]=f.glass()[0]+half*(2*p[i]-1);p[i+1]=lo+(hi-lo)*p[i+1];p[i+2]=f.glass()[1]+half*(2*p[i+2]-1);}add(out,new Mesh.Polygon(p,face.uvs(),face.normals(),face.material()),0,0,0,colour);}
    }

    private static void collar(List<Triangle> out,ShapeRasterizer.Floating f,double lo,double hi,int colour,double half){
        for(var face:TankGaugeGeometry.markerFaces()){
            var p=face.positions().clone();
            for(int i=0;i<p.length;i+=3){p[i]=f.glass()[0]+half*(2*p[i]-1);p[i+1]=lo+(hi-lo)*p[i+1];p[i+2]=f.glass()[1]+half*(2*p[i+2]-1);}
            add(out,new Mesh.Polygon(p,face.uvs(),face.normals(),face.material()),0,0,0,colour);
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
        out.put(Mesh.Material.GAUGE_SCALE,new Sprite(1,1,new int[]{0xffd5d6d1}));
        if(!out.containsKey(Mesh.Material.GLASS))throw new IllegalStateException("Equipment model must provide the custom glass sprite");

        return out;
    }

    /** {@code v} turned by {@code angle} about the unit {@code axis} (Rodrigues). */
    private static double[] rotate(double[] v, double[] axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), d = dot(axis, v);
        var k = cross(axis, v);
        return new double[]{v[0] * c + k[0] * s + axis[0] * d * (1 - c), v[1] * c + k[1] * s + axis[1] * d * (1 - c), v[2] * c + k[2] * s + axis[2] * d * (1 - c)};
    }

    private static void add(List<Triangle> out, Mesh.Polygon polygon, double x, double y, double z) {
        add(out, polygon, x, y, z, 0);
    }
    private static void add(List<Triangle> out, Mesh.Polygon polygon, double x, double y, double z, int colour) {
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
            out.add(new Triangle(pts, uvs, new double[]{normal[0] / l, normal[1] / l, normal[2] / l}, polygon.material(), colour));
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
        String region=System.getProperty("gaugeRegion","full");
        var f=ShapeRasterizer.floating(shape);double cropLow,cropHigh;
        switch(region){
            case "full"->{cropLow=-.05;cropHigh=f.glassHigh()+.75;}
            case "top"->{cropLow=f.glassHigh()-.2;cropHigh=f.glassHigh()+.8;}
            case "bottom"->{cropLow=-.05;cropHigh=.9;}
            case "body"->{double mid=.6*shape.fillHeight();cropLow=mid-.7;cropHigh=mid+.7;}
            default->throw new IllegalArgumentException("No region "+region);
        }
        corners.clear();
        for(double x:new double[]{f.glass()[0]-.65,f.glass()[0]+.65})for(double y:new double[]{cropLow,cropHigh})for(double z:new double[]{f.glass()[1]-.65,f.glass()[1]+.8})corners.add(new double[]{x,y,z});
        for (var v : corners) {
            minX = Math.min(minX, dot(v, right));
            maxX = Math.max(maxX, dot(v, right));
            minY = Math.min(minY, dot(v, up));
            maxY = Math.max(maxY, dot(v, up));
        }
        double scale = region.equals("full")?Math.min(260,1000/(maxY-minY)):360;
        int w = (int) Math.ceil((maxX - minX) * scale) + 20, h = (int) Math.ceil((maxY - minY) * scale) + 20;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var zbuf = new double[w * h];
        Arrays.fill(zbuf, Double.MAX_VALUE);
        int sky = 0x9CC3F5;
        for (int i = 0; i < w * h; i++) image.setRGB(i % w, i / w, sky);
        double fx = minX, fy = minY;
        int[] pass={0};
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
                    int rgb = colour.at(w0, w1, w2),alpha=rgb>>>24;
                    if (alpha==0) continue;
                    if(pass[0]==0){zbuf[y*w+x]=z;image.setRGB(x,y,rgb);}
                    else{
                        int under=image.getRGB(x,y),inverse=255-alpha;
                        int red=((rgb>>16&255)*alpha+(under>>16&255)*inverse+127)/255;
                        int green=((rgb>>8&255)*alpha+(under>>8&255)*inverse+127)/255;
                        int blue=((rgb&255)*alpha+(under&255)*inverse+127)/255;
                        image.setRGB(x,y,red<<16|green<<8|blue);
                    }
                }
        };
        // Grass with the block grid (a line every block) under the unit.
        for (int x = gx0; x < gx1; x++)
            for (int z = gz0; z < gz1; z++) {
                double[] a = {x, -.001, z}, b = {x + 1, -.001, z}, c = {x + 1, -.001, z + 1}, d = {x, -.001, z + 1};
                int fx0 = x, fz0 = z;
                Colour grass = (w0, w1, w2) -> 0xFF6A9A3C;
                raster.fill(a, b, c, grass);
                raster.fill(a, c, d, grass);
                double t = .03;
                for (double[][] line : new double[][][]{{{fx0, -.0005, fz0}, {fx0 + 1, -.0005, fz0}, {fx0 + 1, -.0005, fz0 + t}, {fx0, -.0005, fz0 + t}},
                        {{fx0, -.0005, fz0}, {fx0 + t, -.0005, fz0}, {fx0 + t, -.0005, fz0 + 1}, {fx0, -.0005, fz0 + 1}}}) {
                    Colour dark = (w0, w1, w2) -> 0xFF557D30;
                    raster.fill(line[0], line[1], line[2], dark);
                    raster.fill(line[0], line[2], line[3], dark);
                }
            }
        var ordered=new ArrayList<Triangle>(triangles);
        ordered.sort(java.util.Comparator.comparingDouble((Triangle t)->(dot(t.p[0],depth)+dot(t.p[1],depth)+dot(t.p[2],depth))/3).reversed());
        for(int phase=0;phase<2;phase++)for (var t : ordered) {
            boolean transparent=t.material==Mesh.Material.GLASS||(t.colour!=0&&(t.colour>>>24)<255);
            if(transparent!=(phase==1))continue;pass[0]=phase;
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
                int texel = t.colour != 0 ? t.colour : 0xFFFF00FF;
                if (t.colour == 0 && sprite != null) {
                    double u = w0 * t.uv[0][0] + w1 * t.uv[1][0] + w2 * t.uv[2][0], v = w0 * t.uv[0][1] + w1 * t.uv[1][1] + w2 * t.uv[2][1];
                    texel = sprite.sample(u, v);
                    if ((texel >>> 24) ==0) return 0;
                }
                int r = (int) Math.min(255, (texel >> 16 & 255) * ftr * light), g = (int) Math.min(255, (texel >> 8 & 255) * ftg * light),
                        b = (int) Math.min(255, (texel & 255) * ftb * light);
                return texel&0xFF000000 | r << 16 | g << 8 | b;
            });
        }
        ImageIO.write(image, "png", new File(file));
    }

    private interface Colour {
        /** ARGB at barycentric weights; zero alpha is discarded. */
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

