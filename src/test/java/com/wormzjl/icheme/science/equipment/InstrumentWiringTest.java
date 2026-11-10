package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The instrument wiring's finish (batch 2026-11-10-custom-shader-system, CUSTOM_SHADER_SYSTEM_PLAN.md
 * section 4.4, W2 gate): v in metres, tray cable counts, the ATEX entry at the head, no entry on a top
 * face.
 */
class InstrumentWiringTest {
    /** One flat 20 m by 1 m carrier facing up, at y = 0. */
    private static List<Mesh.Polygon> deck() {
        double[] p = {0, 0, 0, 0, 0, 1, 20, 0, 1, 20, 0, 0};
        double[] n = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        return List.of(new Mesh.Polygon(p, new double[8], n, Mesh.Material.SHELL));
    }

    private static double[] range(List<Mesh.Polygon> polygons, int component) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (var p : polygons)
            for (int k = component; k < p.uvs().length; k += 2) {
                low = Math.min(low, p.uvs()[k]);
                high = Math.max(high, p.uvs()[k]);
            }
        return new double[] {low, high};
    }

    @Test
    void vGrowsByRunLengthSoAFifteenMetreRunSpansThirtyPeriods() {
        var a = new double[] {1, 0, .5};
        var b = new double[] {16, 0, .5};
        var tray = InstrumentWiring.paint(deck(), a, b, InstrumentWiring.TRAY, 0, true);
        assertFalse(tray.isEmpty());
        var v = range(tray, 1);
        // The ribbon overlaps half a width past each end, so the next run's corner closes.
        double periods = (v[1] - v[0] - InstrumentWiring.TRAY) / InstrumentWiring.PERIOD;
        assertEquals(30, periods, 1e-3, "15 m of run must carry 30 texture periods");
        assertEquals(-InstrumentWiring.TRAY / 2, v[0], 2e-4);
        var u = range(tray, 0);
        assertEquals(0, u[0], 1e-9);
        assertEquals(1, u[1], 1e-9);
        // A later segment continues the run's metres; a branch ribbon takes the texture's left half.
        var branch = InstrumentWiring.paint(deck(), a, b, InstrumentWiring.BRANCH, 2.5, false);
        assertEquals(2.5 - InstrumentWiring.BRANCH / 2, range(branch, 1)[0], 2e-4);
        assertEquals(.5, range(branch, 0)[1], 1e-9);
        // Folded onto an atlas sprite, every polygon keeps its v inside one period.
        for (var p : InstrumentWiring.atlas(tray))
            for (double x : p.uvs()) assertTrue(x >= -1e-9 && x <= 1 + 1e-9);
    }

    @Test
    void aBaselineSegmentCarriesTheCablesJoinedBeyondIt() {
        // Joins at 1, 2, 3, 7 and 9 m; the exit at 4.5 m.
        double[] joins = {1, 2, 3, 7, 9};
        var run =
                InstrumentWiring.tray(
                        "baseline",
                        List.of(new double[] {1, 0, 0}, new double[] {9, 0, 0}),
                        0,
                        4.5,
                        joins);
        assertTrue(run.tray());
        var stations = run.points().stream().map(p -> p[0]).toList();
        assertEquals(List.of(1.0, 2.0, 3.0, 4.5, 7.0, 9.0), stations);
        assertEquals(List.of(1, 2, 3, 2, 1), run.cables());
        assertEquals(3, InstrumentWiring.carried(4, 4.5, joins));
        assertEquals(2, InstrumentWiring.carried(5, 4.5, joins));
        assertEquals("tray_6", new InstrumentWiring.Finish(9, true).texture());
        assertEquals("tray_2", new InstrumentWiring.Finish(2, true).texture());
        assertEquals("branch", InstrumentWiring.Finish.BRANCH.texture());
        assertNull(InstrumentWiring.Finish.HARDWARE.texture());
    }

    @Test
    void aDrumsRunsCarryOneCablePerBranchAndEveryCableToTheEntry() {
        var shapes =
                List.of(
                        EquipmentShape.drum(4, 8, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 3),
                        EquipmentShape.drum(4, 8, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 3));
        for (var shape : shapes)
            for (var detail : RenderDetail.values()) {
                var ids =
                        EquipmentInstruments.of(shape).probes().stream()
                                .map(EquipmentInstruments.Probe::id)
                                .collect(Collectors.toSet());
                var drawing = ShapeRasterizer.instrumentWiring(shape, ids, detail);
                assertEquals(drawing.polygons().size(), drawing.finishes().size());
                int cables = ids.size();
                assertTrue(cables >= 2, shape + " needs two probes for this check");
                var baseline =
                        drawing.runs().stream()
                                .filter(r -> r.source().equals("baseline"))
                                .findFirst()
                                .orElseThrow();
                for (int n : baseline.cables()) assertTrue(n >= 1 && n <= cables, shape + " " + baseline.cables());
                for (var run : drawing.runs()) {
                    if (run.source().startsWith("controller"))
                        assertEquals(Set.of(cables), Set.copyOf(run.cables()), run.source());
                    if (ids.contains(run.source())) {
                        assertFalse(run.tray());
                        assertEquals(Set.of(1), Set.copyOf(run.cables()));
                    }
                }
                // Every finish polygon names a texture that exists; tubes and branches use the branch texture.
                var names = new HashSet<String>();
                for (var f : drawing.finishes()) if (f.texture() != null) names.add(f.texture());
                assertTrue(names.contains("branch"), shape + " " + names);
                assertTrue(names.stream().anyMatch(t -> t.startsWith("tray_")), shape + " " + names);
                for (var t : names)
                    assertTrue(t.equals("branch") || t.matches("tray_[1-6]"), t);
            }
    }

    @Test
    void theCableLeavesThroughAGlandUnderTheHeadAndDripsBelowIt() {
        var shape =
                EquipmentShape.drum(4, 8, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 3);
        for (var p : EquipmentInstruments.of(shape).probes())
            assertEquals(p.centre()[1] - .115 - .025 * p.normal()[1], InstrumentWiring.seat(p)[1], 1e-9, p.id() + " seat is on the head's bottom face");
        // The drip loop: from the gland's exit to the skin, its lowest point below both ends.
        double[] exit = {0, 1, 0}, foot = {0, .97, -.15}, out = {0, 0, 1};
        var loop = InstrumentWiring.loop(exit, foot, out);
        assertArrayEquals(exit, loop.getFirst(), 1e-12);
        assertArrayEquals(foot, loop.getLast(), 1e-12);
        double lowest = loop.stream().mapToDouble(q -> q[1]).min().orElseThrow();
        assertTrue(lowest < foot[1] - .03, "The loop must hang below where it meets the skin: " + lowest);
        var tube = InstrumentWiring.tube(loop, RenderDetail.NORMAL);
        assertEquals(8 * (loop.size() - 1), tube.size());
        var v = range(tube, 1);
        double length = 0;
        for (int i = 1; i < loop.size(); i++) length += SurfaceSection.distance(loop.get(i - 1), loop.get(i));
        assertEquals(0, v[0], 1e-12);
        assertEquals(length, v[1], 1e-9, "The tube's v is metres along the cable");
        var u = range(tube, 0);
        assertTrue(u[0] >= .5 - 1e-12 && u[1] <= 1 + 1e-12, "The tube takes the sheath half of the branch texture");
    }

    @Test
    void noInstrumentEntryIsOnATopFace() {
        var failures = new ArrayList<String>();
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            var plan = ShapeRasterizer.services(shape);
            var entry = plan.instrumentEntry();
            if (entry == null) continue;
            boolean onSide = false, onTop = false;
            for (var c : plan.enclosures()) {
                double[] lo = c.low(), hi = c.high();
                boolean inside = true;
                for (int a = 0; a < 3; a++) inside &= entry[a] >= lo[a] - 1e-6 && entry[a] <= hi[a] + 1e-6;
                if (!inside) continue;
                if (Math.abs(entry[1] - hi[1]) < 1e-6) onTop = true;
                for (int a : new int[] {0, 2})
                    if (Math.abs(entry[a] - lo[a]) < 1e-6 || Math.abs(entry[a] - hi[a]) < 1e-6) onSide = true;
                if (Math.abs(entry[1] - lo[1]) < 1e-6) onSide = true;
            }
            if (onTop || !onSide)
                failures.add(shape.kind() + " " + shape.diameter() + " " + Arrays.toString(entry) + (onTop ? " on top" : " not on a face"));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
