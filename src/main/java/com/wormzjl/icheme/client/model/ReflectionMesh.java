package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One structure's mesh for the reflection pass (batch 2026-11-18-equipment-reflections): the fixed facets of its drawn
 * body that take a {@link ReflectionClass} ({@link ShapeRasterizer#renderBody}: no glass, no moving parts, no nozzles; each
 * on the plane of the chunk mesh's own pieces of it, tiled by its sprite's period, not cut per cell), as triangles from its origin
 * with their smooth normals, each vertex's class and reflection colour. No texture: the sky is the image. Built once
 * per shape, paint and detail; {@code EquipmentReflectionRenderer} packs it into the GPU format. No Minecraft class.
 *
 * @param positions x, y, z per vertex, m from the structure's origin
 * @param normals   unit x, y, z per vertex
 * @param classes   the class's ordinal per vertex
 * @param tints     the reflection colour per vertex, 0xRRGGBB ({@link ReflectionClass#tint})
 */
public record ReflectionMesh(float[] positions, float[] normals, byte[] classes, int[] tints) {
    public static final ReflectionMesh EMPTY = new ReflectionMesh(new float[0], new float[0], new byte[0], new int[0]);
    /** The side of the square whose structures' meshes merge into one draw, blocks (as {@code EquipmentBodyRenderer}'s). */
    public static final int REGION = 32;

    /** The square a structure with its origin at block (x, z) merges into. */
    public static long region(int x, int z) {
        return (long) Math.floorDiv(x, REGION) << 32 ^ Math.floorDiv(z, REGION) & 0xffffffffL;
    }

    public int vertices() {
        return classes.length;
    }

    public int triangles() {
        return classes.length / 3;
    }

    /** The mesh of {@code shape} at {@code detail} with body paint {@code paintBody} (0xRRGGBB). */
    public static ReflectionMesh of(EquipmentShape shape, RenderDetail detail, int paintBody) {
        return of(polygons(shape, detail), paintBody);
    }

    /** The polygons the pass draws on {@code shape}. */
    public static List<Mesh.Polygon> polygons(EquipmentShape shape, RenderDetail detail) {
        return ShapeRasterizer.renderBody(shape, detail).stream().filter(p -> ReflectionClass.of(p.material()) != null).toList();
    }

    /** Fans of {@code polygons} (convex) as triangles. */
    public static ReflectionMesh of(List<Mesh.Polygon> polygons, int paintBody) {
        int vertices = 0;
        for (var polygon : polygons) vertices += 3 * Math.max(0, polygon.size() - 2);
        if (vertices == 0) return EMPTY;
        var positions = new float[3 * vertices];
        var normals = new float[3 * vertices];
        var classes = new byte[vertices];
        var tints = new int[vertices];
        int v = 0;
        for (var polygon : polygons) {
            var type = ReflectionClass.of(polygon.material());
            if (type == null) continue;
            int tint = ReflectionClass.tint(polygon.material(), paintBody);
            double[] p = polygon.positions(), n = polygon.normals();
            for (int k = 1; k + 1 < polygon.size(); k++)
                for (int c : new int[]{0, k, k + 1}) {
                    for (int i = 0; i < 3; i++) {
                        positions[3 * v + i] = (float) p[3 * c + i];
                        normals[3 * v + i] = (float) n[3 * c + i];
                    }
                    classes[v] = (byte) type.ordinal();
                    tints[v] = tint;
                    v++;
                }
        }
        return new ReflectionMesh(positions, normals, classes, tints);
    }

    /** Triangles by material: what the pass draws of {@code polygons} (the review's counts). */
    public static Map<Mesh.Material, Integer> triangles(List<Mesh.Polygon> polygons) {
        var out = new EnumMap<Mesh.Material, Integer>(Mesh.Material.class);
        for (var polygon : polygons) out.merge(polygon.material(), Math.max(0, polygon.size() - 2), Integer::sum);
        return out;
    }
}
