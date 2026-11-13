package com.wormzjl.icheme.client.model;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one polygon bake (batch 2026-11-13-codebase-review, C15) gives, bit for bit, the vertices of the two loops it
 * replaced: {@code EquipmentBodyRenderer.body} and {@code SceneView.upload}, kept here as the references with a stand-in
 * sprite (an atlas window) and a stand-in shade, the parts the game supplies.
 */
class PolygonBakeTest {
    /** A sprite's window on the atlas, as {@code TextureAtlasSprite.getU} and {@code getV} map into it. */
    private record Sprite(float u0, float u1, float v0, float v1) {
        float getU(float u) {return u0 + (u1 - u0) * u;}
        float getV(float v) {return v0 + (v1 - v0) * v;}
    }

    private static Sprite sprite(Mesh.Material material) {
        if (material == Mesh.Material.GLASS) return null;
        int k = material.ordinal();
        return new Sprite(k / 64F, (k + 1) / 64F, (k % 7) / 16F, (k % 7 + 1) / 16F);
    }

    private static final PolygonBake.Atlas<Sprite> ATLAS = new PolygonBake.Atlas<>() {
        @Override public Sprite sprite(Mesh.Material material) {return PolygonBakeTest.sprite(material);}
        @Override public float u(Sprite sprite, float u) {return sprite.getU(u);}
        @Override public float v(Sprite sprite, float v) {return sprite.getV(v);}
    };

    /** A smooth-normal shade, 0 to 255, as {@code EquipmentShellModel.shade} gives one. */
    private static int shade(Mesh.Polygon polygon, int corner) {
        double[] n = polygon.normals();
        return Math.round(255 * (float) Math.min(1, .5 + .3 * n[3 * corner + 1] + .2 * Math.abs(n[3 * corner + 2])));
    }

    /** A weathering factor of a point, as {@code ShapeRasterizer.towerStain} gives a natural tower's (up to 1.05). */
    private static double stain(double[] p) {
        return 1 + .05 * Math.sin(3 * p[0] + p[2]) - .1 * Math.max(0, p[1] - 2) / 4;
    }

    private static List<Mesh.Polygon> drum() {
        var shape = EquipmentShape.drum(2, 4, Orientation.VERTICAL, Heading.NORTH, EquipmentShape.defaultSupport(2, Orientation.VERTICAL));
        var polygons = new ArrayList<>(ShapeRasterizer.renderBody(shape, RenderDetail.LOW));
        polygons.removeIf(p -> p.material() == Mesh.Material.GLASS);
        assertFalse(polygons.isEmpty());
        // An unpainted pentagon with curved normals, so that a fan of three and the white tint are exercised too.
        double[] positions = new double[15], uvs = new double[10], normals = new double[15];
        for (int i = 0; i < 5; i++) {
            double a = 2 * Math.PI * i / 5;
            positions[3 * i] = .5 + .4 * Math.cos(a);
            positions[3 * i + 1] = .3;
            positions[3 * i + 2] = .5 + .4 * Math.sin(a);
            uvs[2 * i] = .5 + .5 * Math.cos(a);
            uvs[2 * i + 1] = .5 + .5 * Math.sin(a);
            normals[3 * i] = .6 * Math.cos(a);
            normals[3 * i + 1] = .8;
            normals[3 * i + 2] = .6 * Math.sin(a);
        }
        polygons.add(new Mesh.Polygon(positions, uvs, normals, Mesh.Material.PIPE));
        assertTrue(polygons.stream().anyMatch(p -> p.material().channel() >= 0), "a painted material is exercised");
        return polygons;
    }

    private static final int[] TINTS = {Paint.tint(0xC0302A), Paint.tint(0x2A6FC0)};

    /** The reference: {@code EquipmentBodyRenderer.body} before C15, with the stand-ins. */
    private static PolygonBake.Triangles oldBody(List<Mesh.Polygon> polygons, int[] tints) {
        int vertices=0;
        for(var polygon:polygons)vertices+=3*(polygon.size()-2);
        float[] positions=new float[3*vertices],uvs=new float[2*vertices],normals=new float[3*vertices];int[] colours=new int[vertices];int v=0;
        for(var polygon:polygons){
            var sprite=sprite(polygon.material());if(sprite==null)return null;
            int channel=polygon.material().channel(),tint=channel<0||channel>=tints.length?0xFFFFFF:tints[channel];
            var p=polygon.positions();var n=polygon.normals();var uv=polygon.uvs();
            for(int corner=1;corner+1<polygon.size();corner++)for(int index:new int[]{0,corner,corner+1}){
                int shade=(int)Math.min(255,shade(polygon,index)*stain(new double[]{p[3*index],p[3*index+1],p[3*index+2]}));
                for(int a=0;a<3;a++){positions[3*v+a]=(float)p[3*index+a];normals[3*v+a]=(float)n[3*index+a];}
                uvs[2*v]=sprite.getU((float)uv[2*index]);uvs[2*v+1]=sprite.getV((float)uv[2*index+1]);
                colours[v]=0xFF000000|shade*(tint>>16&255)/255<<16|shade*(tint>>8&255)/255<<8|shade*(tint&255)/255;
                v++;
            }
        }
        return new PolygonBake.Triangles(positions,colours,uvs,normals);
    }

    /** One vertex as {@code SceneView.upload} handed it to its buffer: position, UV, colour. */
    private record SceneVertex(float x, float y, float z, float u, float v, int colour) {}

    /** The reference: {@code SceneView.upload} before C15, its buffer calls recorded, with the stand-ins. */
    private static List<SceneVertex> oldScene(List<Mesh.Polygon> polygons, int[] tints) {
        var out = new ArrayList<SceneVertex>();
        for (var polygon : polygons) {
            var sprite = sprite(polygon.material());
            if (sprite == null) return null;
            int channel = polygon.material().channel(), tint = channel < 0 || channel >= tints.length ? 0xFFFFFF : tints[channel];
            var p = polygon.positions();
            var uv = polygon.uvs();
            for (int corner = 1; corner + 1 < polygon.size(); corner++)
                for (int index : new int[]{0, corner, corner + 1}) {
                    int shade = shade(polygon, index);
                    int colour = 0xFF000000 | shade * (tint >> 16 & 255) / 255 << 16 | shade * (tint >> 8 & 255) / 255 << 8 | shade * (tint & 255) / 255;
                    out.add(new SceneVertex((float) p[3 * index], (float) p[3 * index + 1], (float) p[3 * index + 2],
                            sprite.getU((float) uv[2 * index]), sprite.getV((float) uv[2 * index + 1]), colour));
                }
        }
        return out;
    }

    private static int[] bits(float[] values) {
        var out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = Float.floatToRawIntBits(values[i]);
        return out;
    }

    @Test
    void aBodyBakesBitForBitAsBefore() {
        var polygons = drum();
        var expected = oldBody(polygons, TINTS);
        var actual = PolygonBake.triangles(polygons, TINTS, ATLAS, (polygon, index) -> {
            var p = polygon.positions();
            return (int) Math.min(255, shade(polygon, index) * stain(new double[]{p[3 * index], p[3 * index + 1], p[3 * index + 2]}));
        });
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(PolygonBake.vertices(polygons), actual.vertices());
        assertArrayEquals(bits(expected.positions()), bits(actual.positions()));
        assertArrayEquals(bits(expected.uvs()), bits(actual.uvs()));
        assertArrayEquals(bits(expected.normals()), bits(actual.normals()));
        assertArrayEquals(expected.colours(), actual.colours());
    }

    @Test
    void aFilmActorUploadsBitForBitAsBefore() {
        var polygons = drum();
        int[] tints = {Paint.tint(Paint.DEFAULT.body()), Paint.tint(Paint.DEFAULT.structure())};
        var expected = oldScene(polygons, tints);
        var triangles = PolygonBake.triangles(polygons, tints, ATLAS, PolygonBakeTest::shade);
        assertNotNull(expected);
        assertNotNull(triangles);
        // As SceneView.upload reads the bake into its buffer now.
        var p = triangles.positions();
        var uv = triangles.uvs();
        var colours = triangles.colours();
        var actual = new ArrayList<SceneVertex>();
        for (int v = 0; v < colours.length; v++) actual.add(new SceneVertex(p[3 * v], p[3 * v + 1], p[3 * v + 2], uv[2 * v], uv[2 * v + 1], colours[v]));
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            var e = expected.get(i);
            var a = actual.get(i);
            assertArrayEquals(bits(new float[]{e.x(), e.y(), e.z(), e.u(), e.v()}), bits(new float[]{a.x(), a.y(), a.z(), a.u(), a.v()}), "vertex " + i);
            assertEquals(e.colour(), a.colour(), "vertex " + i);
        }
    }

    @Test
    void aMissingSpriteStopsTheBakeAsBefore() {
        var polygons = new ArrayList<>(drum());
        var glass = polygons.getFirst();
        polygons.add(1, new Mesh.Polygon(glass.positions(), glass.uvs(), glass.normals(), Mesh.Material.GLASS));
        assertNull(oldBody(polygons, TINTS));
        assertNull(oldScene(polygons, TINTS));
        assertNull(PolygonBake.triangles(polygons, TINTS, ATLAS, PolygonBakeTest::shade));
    }
}
