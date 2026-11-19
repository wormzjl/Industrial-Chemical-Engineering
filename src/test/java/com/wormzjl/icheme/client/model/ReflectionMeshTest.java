package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the reflection pass draws (batch 2026-11-18-equipment-reflections): a drum's and a pump's shiny facets by
 * material against what the chunk mesh draws of them, every facet inside the structure, nothing the pass has no class
 * for; and its draw calls and triangles over the review plant's structures ({@code CrudeUnitPlant.Layout.REVIEW}),
 * counted as the renderer merges them, with the pass on and off. Prints the counts (REFLECTION_PASS_REVIEW.md section 5).
 */
class ReflectionMeshTest {
    private static EquipmentShape drum() {
        // CrudeUnitPlant.receiver(1.5, 4, RECEIVER_SUPPORT): the review plant's reflux drum.
        return EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 5,
                EquipmentShape.defaultConnections(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, 5, true), true);
    }

    private static EquipmentShape unit(EquipmentShape.Kind kind) {
        // PlantBuilder.unit: the smallest size at its default length and support.
        var d = UnitDesign.of(kind);
        double size = d.minSize();
        int length = d.defaultLength(size);
        return EquipmentShape.designed(kind, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    private static EquipmentShape exchanger(double size) {
        var d = UnitDesign.of(EquipmentShape.Kind.EXCHANGER);
        int length = d.defaultLength(size);
        return EquipmentShape.designed(EquipmentShape.Kind.EXCHANGER, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    private static EquipmentShape column() {
        return EquipmentShape.column(2, ColumnTrays.simple(20, 10), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(2));
    }

    /** The chunk mesh's triangles of {@code shape} by material: every shell cell's surface, as the shell model bakes it. */
    private static Map<Mesh.Material, Integer> chunk(EquipmentShape shape) {
        var out = new EnumMap<Mesh.Material, Integer>(Mesh.Material.class);
        for (var cell : ShapeRasterizer.layout(shape).shell())
            for (var polygon : ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                out.merge(polygon.material(), polygon.size() - 2, Integer::sum);
        return out;
    }

    private static String table(String name, Map<Mesh.Material, Integer> chunk, Map<Mesh.Material, Integer> pass) {
        var b = new StringBuilder(name + ": material, chunk triangles, pass triangles\n");
        int c = 0, p = 0;
        var all = new java.util.TreeSet<Mesh.Material>(chunk.keySet());
        all.addAll(pass.keySet());
        for (var m : all) {
            int ci = chunk.getOrDefault(m, 0), pi = pass.getOrDefault(m, 0);
            c += ci;
            p += pi;
            b.append(String.format("  %-14s %6d %6d%s%n", m, ci, pi, ReflectionClass.of(m) == null ? "" : "  " + ReflectionClass.of(m)));
        }
        return b.append(String.format("  %-14s %6d %6d%n", "total", c, p)).toString();
    }

    private static void checkInside(EquipmentShape shape, List<Mesh.Polygon> polygons) {
        for (var polygon : polygons) {
            assertNotNull(ReflectionClass.of(polygon.material()), polygon.material() + " has no class");
            var p = polygon.positions();
            for (int i = 0; i < polygon.size(); i++) {
                assertTrue(p[3 * i] > -.01 && p[3 * i] < shape.sizeX() + .01, "x inside");
                assertTrue(p[3 * i + 1] > -.01 && p[3 * i + 1] < shape.sizeY() + .01, "y inside");
                assertTrue(p[3 * i + 2] > -.01 && p[3 * i + 2] < shape.sizeZ() + .01, "z inside");
            }
        }
    }

    @Test
    void aDrumMirrorsItsPaintedShellAndHeadsAndItsSteel() {
        var shape = drum();
        var polygons = ReflectionMesh.polygons(shape, RenderDetail.NORMAL);
        var pass = ReflectionMesh.triangles(polygons);
        var chunk = chunk(shape);
        System.out.print(table("drum 1.5 x 4 m", chunk, pass));
        checkInside(shape, polygons);
        assertTrue(pass.containsKey(Mesh.Material.SHELL) && pass.containsKey(Mesh.Material.HEAD), "the painted shell and heads");
        assertFalse(pass.containsKey(Mesh.Material.CONCRETE));
        for (var e : pass.entrySet())
            assertTrue(e.getValue() <= chunk.getOrDefault(e.getKey(), 0), e.getKey() + ": whole facets, never more triangles than the chunk's cut pieces");
        var mesh = ReflectionMesh.of(shape, RenderDetail.NORMAL);
        assertEquals(3 * pass.values().stream().mapToInt(Integer::intValue).sum(), mesh.vertices());
    }

    @Test
    void aPumpMirrorsItsCastingButNotItsTurningShaftOrItsNozzles() {
        var shape = unit(EquipmentShape.Kind.PUMP);
        var polygons = ReflectionMesh.polygons(shape, RenderDetail.NORMAL);
        var pass = ReflectionMesh.triangles(polygons);
        var chunk = chunk(shape);
        System.out.print(table("pump " + shape.diameter() + " m", chunk, pass));
        checkInside(shape, polygons);
        assertTrue(pass.containsKey(Mesh.Material.PUMP_CASTING), "the cast casing");
        assertFalse(pass.containsKey(Mesh.Material.SHAFT), "the turning shaft is MachineAnimation's");
        assertFalse(pass.containsKey(Mesh.Material.BOLT), "nozzle flanges' bolts: nozzles are not in the first build");
        for (var e : pass.entrySet())
            assertTrue(e.getValue() <= chunk.getOrDefault(e.getKey(), 0), e.getKey() + ": only what the chunk mesh draws (moving parts are MachineAnimation's)");
    }

    @Test
    void theReviewPlantCostsOneDrawPerRegion() {
        // CrudeUnitPlant.Layout.REVIEW: the column at its origin, the condenser, the drum, the reflux pump, three pumps, three coolers.
        record Placed(String name, EquipmentShape shape, int x, int z) {}
        var cooler = unit(EquipmentShape.Kind.EXCHANGER);
        var pump = unit(EquipmentShape.Kind.PUMP);
        var plant = List.of(new Placed("column", column(), 0, 0), new Placed("condenser", exchanger(1.5), -14, -20), new Placed("drum", drum(), -24, -6),
                new Placed("reflux pump", pump, -10, -8), new Placed("pump 1", pump, -7, 9), new Placed("pump 2", pump, -7, 17), new Placed("pump 3", pump, -7, 25),
                new Placed("cooler 1", cooler, -16, 9), new Placed("cooler 2", cooler, -16, 17), new Placed("cooler 3", cooler, -16, 25));
        var regions = new HashSet<Long>();
        int triangles = 0;
        var b = new StringBuilder("review plant: structure, pass triangles\n");
        for (var p : plant) {
            int t = ReflectionMesh.of(p.shape(), RenderDetail.NORMAL).triangles();
            triangles += t;
            regions.add(ReflectionMesh.region(p.x(), p.z()));
            b.append(String.format("  %-12s %6d%n", p.name(), t));
        }
        b.append(String.format("  pass on: %d draw calls, %d triangles; off: 0 draw calls, 0 triangles (the chunk mesh's triangles are the same either way)%n", regions.size(), triangles));
        System.out.print(b);
        assertEquals(3, regions.size(), "ten structures in three 32-block squares: three calls, not ten");
        assertTrue(triangles > 0);
    }
}
