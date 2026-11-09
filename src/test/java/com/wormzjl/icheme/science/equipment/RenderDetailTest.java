package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RenderDetailTest {
    @Test
    void aCoarseClientKeepsEveryServersLayoutPortsAndCollision() {
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            String before = EquipmentLayoutGoldenTest.line(shape);
            var layout = ShapeRasterizer.layout(shape);
            var cell = layout.controller();
            var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z());
            var collision = ShapeRasterizer.solid(key);
            var low = ShapeRasterizer.renderBody(shape, RenderDetail.LOW);
            assertFalse(low.isEmpty(), shape.toString());
            for (var polygon : low) finite(polygon);
            ShapeRasterizer.chunkSurface(key, RenderDetail.LOW);
            ShapeRasterizer.moving(shape, RenderDetail.LOW);
            var floating = ShapeRasterizer.floating(shape, RenderDetail.NORMAL);
            var coarse = ShapeRasterizer.floating(shape, RenderDetail.LOW);
            if (floating != null) {
                assertNotNull(coarse);
                assertArrayEquals(floating.pivot(), coarse.pivot(), 0);
                assertArrayEquals(floating.along(), coarse.along(), 0);
                assertArrayEquals(floating.axis(), coarse.axis(), 0);
                assertEquals(floating.length(), coarse.length());
                assertEquals(floating.landed(), coarse.landed());
                assertEquals(floating.top(), coarse.top());
            }
            assertEquals(
                    before,
                    EquipmentLayoutGoldenTest.line(shape),
                    "Low client changed server layout: " + shape.kind());
            assertEquals(
                    collision,
                    ShapeRasterizer.solid(key),
                    "Low client changed collision: " + shape.kind());
        }
    }

    @Test
    void lowFlangeBoltsAreFlushTexturedFacesWithTheSameCount() {
        var frame = Frame.of(new double[3], 1, 1);
        var normal = new Mesh();
        var low = new Mesh(RenderDetail.LOW);
        normal.bolts(frame, .8, .4, .6, 0, 1, 12, Material.BOLT);
        low.bolts(frame, .8, .4, .6, 0, 1, 12, Material.BOLT);
        assertEquals(12, low.facets().size());
        assertTrue(low.facets().size() * 4 < normal.facets().size());
        for (var facet : low.facets()) {
            assertEquals(Material.BOLT_FACE, facet.material());
            assertTrue(facet.max()[1] < .001);
            assertEquals(facet.min()[1], facet.max()[1]);
        }
    }

    @Test
    void normalCasingHasRaisedNutsInsideThePreviousCoverEnvelope() {
        var frame = Frame.of(new double[3], 2, 1);
        for (int sign : new int[] {-1, 1}) {
            var normal = new Mesh();
            normal.disc(frame, 1, 0, 32, sign, Material.PUMP_DISC);
            assertTrue(normal.facets().stream().anyMatch(f -> f.material() == Material.BOLT));
            assertTrue(normal.facets().stream().anyMatch(f -> f.material() == Material.PUMP_COVER));
            for (var facet : normal.facets())
                for (var point : facet.positions()) {
                    assertTrue(sign * point[2] <= 1e-9, "Bolt expanded the old cover envelope");
                    assertTrue(Math.hypot(point[0], point[1]) <= 1 + 1e-9);
                }
            var low = new Mesh(RenderDetail.LOW);
            low.disc(frame, 1, 0, 32, sign, Material.PUMP_DISC);
            assertEquals(1, low.facets().size());
            assertEquals(Material.PUMP_DISC, low.facets().getFirst().material());
        }
    }

    @Test
    void roundEquipmentUsesFewerDrawingTriangles() {
        var shapes =
                List.of(
                        EquipmentShape.column(
                                8, ColumnTrays.simple(30, 15), EquipmentShape.Heading.NORTH, 6),
                        EquipmentShape.drum(
                                8,
                                12,
                                EquipmentShape.Orientation.HORIZONTAL,
                                EquipmentShape.Heading.NORTH,
                                3),
                        EquipmentShape.unit(
                                EquipmentShape.Kind.EXCHANGER,
                                2,
                                8,
                                EquipmentShape.Heading.NORTH,
                                2),
                        EquipmentShape.unit(
                                EquipmentShape.Kind.PUMP, .5, 1, EquipmentShape.Heading.NORTH, 0));
        for (var shape : shapes) {
            long normal = triangles(ShapeRasterizer.renderBody(shape, RenderDetail.NORMAL));
            long low = triangles(ShapeRasterizer.renderBody(shape, RenderDetail.LOW));
            assertTrue(low < normal, shape.kind() + " has no polygon saving");
        }
    }

    @Test
    void concurrentModelBuildsDoNotLeakDetailBetweenClientsOrIntoPhysicalGeometry()
            throws Exception {
        var shape =
                EquipmentShape.drum(
                        4, 6, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.EAST, 3);
        String server = EquipmentLayoutGoldenTest.line(shape);
        long normal = triangles(ShapeRasterizer.renderBody(shape, RenderDetail.NORMAL));
        long low = triangles(ShapeRasterizer.renderBody(shape, RenderDetail.LOW));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 12; i++) {
                var detail = i % 2 == 0 ? RenderDetail.LOW : RenderDetail.NORMAL;
                jobs.add(pool.submit(() -> triangles(ShapeRasterizer.renderBody(shape, detail))));
            }
            for (int i = 0; i < jobs.size(); i++)
                assertEquals(i % 2 == 0 ? low : normal, jobs.get(i).get(20, TimeUnit.SECONDS));
        }
        assertEquals(server, EquipmentLayoutGoldenTest.line(shape));
    }

    @Test
    void detailedAndCoarsePipeEndsShareTheirConnectionProfiles() {
        for (double diameter : new double[] {.05, .2, .5, .8})
            for (int axis = 0; axis < 3; axis++) {
                var key =
                        new LineGeometry.Key(
                                com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind
                                        .PIPE,
                                2 * axis,
                                3 << (2 * axis),
                                PipeSizes.code(PipeSizes.outside(diameter)),
                                0,
                                3 << (2 * axis),
                                true);
                int coordinate = new int[] {1, 2, 0}[axis];
                var normal = LineGeometry.polygons(key, RenderDetail.NORMAL);
                var low = LineGeometry.polygons(key, RenderDetail.LOW);
                for (int side = 0; side < 2; side++) {
                    double plane = side;
                    long full =
                            normal.stream()
                                    .filter(p -> p.material() == Material.FLANGE)
                                    .flatMapToDouble(
                                            p ->
                                                    java.util.stream.IntStream.range(0, p.size())
                                                            .mapToDouble(
                                                                    v ->
                                                                            p.positions()[
                                                                                    3 * v
                                                                                            + coordinate]))
                                    .filter(x -> Math.abs(x - plane) < 1e-8)
                                    .count();
                    long coarse =
                            low.stream()
                                    .filter(p -> p.material() == Material.FLANGE)
                                    .flatMapToDouble(
                                            p ->
                                                    java.util.stream.IntStream.range(0, p.size())
                                                            .mapToDouble(
                                                                    v ->
                                                                            p.positions()[
                                                                                    3 * v
                                                                                            + coordinate]))
                                    .filter(x -> Math.abs(x - plane) < 1e-8)
                                    .count();
                    assertTrue(full > 0, "Missing connection profile");
                    assertEquals(full, coarse);
                }
                assertTrue(low.stream().anyMatch(p -> p.material() == Material.BOLT_FACE));
                assertTrue(triangles(low) < triangles(normal));
            }
    }

    @Test
    void basinWaterUsesTheSameCoarseBoundaryAsItsShell() {
        var shape =
                EquipmentShape.unit(
                        EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,
                        80,
                        104,
                        EquipmentShape.Heading.NORTH,
                        0);
        var water = CoolingTowerBasin.surface(shape, RenderDetail.LOW);
        int n = RenderDetail.LOW.sides(CoolingTowerDesign.naturalFacets(80));
        var centre = shape.transform().point(CoolingTowerDesign.naturalFrame(80).origin());
        assertEquals(n, water.size());
        for (var triangle : water)
            for (var p : List.of(triangle.a(), triangle.b(), triangle.c()))
                for (int i = 0; i < n; i++) {
                    double angle = 2 * Math.PI * (i + .5) / n;
                    var normal =
                            shape.transform()
                                    .direction(new double[] {Math.cos(angle), 0, Math.sin(angle)});
                    assertTrue(
                            (p.x() - centre[0]) * normal[0] + (p.z() - centre[2]) * normal[2]
                                    <= 40 * Math.cos(Math.PI / n) + 1e-8);
                }
    }

    private static long triangles(List<Polygon> polygons) {
        return polygons.stream().mapToLong(p -> p.size() - 2L).sum();
    }

    private static void finite(Polygon polygon) {
        for (double value : polygon.positions()) assertTrue(Double.isFinite(value));
        for (double value : polygon.normals()) assertTrue(Double.isFinite(value));
        for (double value : polygon.uvs()) assertTrue(value >= -1e-8 && value <= 1 + 1e-8);
    }
}
