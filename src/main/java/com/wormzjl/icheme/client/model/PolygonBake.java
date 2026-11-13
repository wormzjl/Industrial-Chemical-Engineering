package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.Mesh;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Polygons to coloured triangles, without Minecraft classes (batch 2026-11-13-codebase-review, C15): each convex polygon
 * as a fan of triangles (corners 0, k, k + 1), its corners' positions and normals as floats, its material's sprite UVs
 * ({@link Atlas}), and a colour of the corner's shade ({@link Shade}, 0 to 255) times the paint tint of the material's
 * channel. A coarse body ({@code EquipmentBodyRenderer}) and a film's actor ({@code SceneView}) bake through it; the
 * chunk model's quads ({@link EquipmentShellModel#bake}) and the constructor ghost's quads are fans of quads and keep
 * their own loops.
 */
public final class PolygonBake {
    /** A material's sprite, and a sprite's atlas coordinates of a polygon's (u, v); a null sprite stops the bake. */
    public interface Atlas<T> {
        @Nullable T sprite(Mesh.Material material);
        float u(T sprite, float u);
        float v(T sprite, float v);
    }

    /** A corner's shade, 0 to 255. */
    @FunctionalInterface public interface Shade {int at(Mesh.Polygon polygon, int corner);}

    /** Triangle vertices: positions (3 a vertex), colours (ARGB), atlas UVs (2) and normals (3). */
    public record Triangles(float[] positions, int[] colours, float[] uvs, float[] normals) {
        public int vertices() {return colours.length;}
    }

    private PolygonBake() {}

    /** The vertices of {@code polygons} as triangle fans. */
    public static int vertices(List<Mesh.Polygon> polygons) {
        int vertices=0;
        for(var polygon:polygons)vertices+=3*(polygon.size()-2);
        return vertices;
    }

    /**
     * {@code polygons} as triangles, tinted by {@code tints} (the paint's colours by channel; a material with no channel,
     * or a channel past the array, takes white) and shaded by {@code shades}; null while a sprite is missing.
     */
    @Nullable public static <T> Triangles triangles(List<Mesh.Polygon> polygons, int[] tints, Atlas<T> atlas, Shade shades) {
        int vertices=vertices(polygons);
        float[] positions=new float[3*vertices],uvs=new float[2*vertices],normals=new float[3*vertices];int[] colours=new int[vertices];int v=0;
        for(var polygon:polygons){
            var sprite=atlas.sprite(polygon.material());if(sprite==null)return null;
            int channel=polygon.material().channel(),tint=channel<0||channel>=tints.length?0xFFFFFF:tints[channel];
            var p=polygon.positions();var n=polygon.normals();var uv=polygon.uvs();
            for(int corner=1;corner+1<polygon.size();corner++)for(int index:new int[]{0,corner,corner+1}){
                int shade=shades.at(polygon,index);
                for(int a=0;a<3;a++){positions[3*v+a]=(float)p[3*index+a];normals[3*v+a]=(float)n[3*index+a];}
                uvs[2*v]=atlas.u(sprite,(float)uv[2*index]);uvs[2*v+1]=atlas.v(sprite,(float)uv[2*index+1]);
                colours[v]=0xFF000000|shade*(tint>>16&255)/255<<16|shade*(tint>>8&255)/255<<8|shade*(tint&255)/255;
                v++;
            }
        }
        return new Triangles(positions,colours,uvs,normals);
    }
}
