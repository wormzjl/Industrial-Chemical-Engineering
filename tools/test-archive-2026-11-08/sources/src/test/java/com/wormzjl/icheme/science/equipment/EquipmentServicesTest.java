package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class EquipmentServicesTest {
    @Test
    void supportedSizeExtremesHaveNativeServiceCarriersAndUnchangedDetailDatums() {
        var failures = new ArrayList<String>();
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            try {
                var services = ShapeRasterizer.services(shape);
                if (services.data() == null) continue;
                assertFalse(services.enclosures().isEmpty(), shape.toString());
                for (var heading : EquipmentShape.Heading.values()) {
                    var s =
                            new EquipmentShape(
                                    shape.kind(),
                                    shape.diameter(),
                                    shape.tangentLength(),
                                    shape.orientation(),
                                    heading,
                                    shape.support(),
                                    shape.trays(),
                                    shape.connections(),
                                    shape.bank(),
                                    shape.boot(),
                                    shape.outlet(),
                                    shape.towerBank(),
                                    shape.duty(),
                                    shape.exchangerType());
                    var current = ShapeRasterizer.services(s);
                    assertNotNull(current.data(), s.toString());
                    var normal = ShapeRasterizer.renderBody(s, RenderDetail.NORMAL);
                    var low = ShapeRasterizer.renderBody(s, RenderDetail.LOW);
                    assertFalse(normal.isEmpty());
                    assertFalse(low.isEmpty());
                    for (var socket :
                            current.power() == null
                                    ? List.of(current.data())
                                    : List.of(current.data(), current.power())) {
                        var point = new double[] {socket.x(), socket.y(), socket.z()};
                        for (double v : point) assertTrue(Double.isFinite(v), s.toString());
                        var n = LineGeometry.step(socket.face());
                        var inside =
                                new double[] {
                                    point[0] - n[0] * .06, point[1], point[2] - n[2] * .06
                                };
                        assertTrue(
                                current.inside(inside), "Gland has no enclosure behind it: " + s);
                    }
                    if (current.power() != null)
                        assertEquals(
                                current.data().face() ^ 1,
                                current.power().face(),
                                "FE and DATA use opposite service faces");
                }
            } catch (Throwable e) {
                failures.add(
                        shape.kind()
                                + " d="
                                + shape.diameter()
                                + " l="
                                + shape.tangentLength()
                                + ": "
                                + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void passiveVesselsKeepManualTopInstrumentsAndAutomaticUnitsHaveNone() {
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            var probes = EquipmentInstruments.of(shape).probes();
            if (EquipmentInstruments.automatic(shape.kind()))
                assertTrue(
                        probes.stream().noneMatch(EquipmentInstruments.Probe::manual),
                        shape.toString());
            if (shape.kind() == EquipmentShape.Kind.DRUM) {
                assertEquals(
                        Set.of("pressure_top", "temperature_top", "level_body"),
                        probes.stream()
                                .map(EquipmentInstruments.Probe::id)
                                .collect(java.util.stream.Collectors.toSet()));
                assertTrue(probes.stream().allMatch(EquipmentInstruments.Probe::top));
                if (!shape.orientation().horizontal())
                    assertEquals(2, EquipmentInstruments.of(shape).reserved().size());
            }
            if (shape.kind() == EquipmentShape.Kind.SPHERE) {
                assertEquals(3, probes.size());
                for (var p : probes)
                    assertTrue(
                            p.centre()[1] > shape.support() + shape.diameter(),
                            "Head is not at the top platform");
            }
        }
    }

    @Test
    void fittedProbeRoutesQualifyAtConstructorSizeExtremesAndBothDetails() {
        var failures = new ArrayList<String>();
        int checked = 0;
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            var probes = EquipmentInstruments.of(shape).probes();
            if (probes.isEmpty()) continue;
            var ids =
                    probes.stream()
                            .map(EquipmentInstruments.Probe::id)
                            .collect(java.util.stream.Collectors.toSet());
            for (var detail : RenderDetail.values()) {
                checked++;
                try {
                    var drawing = ShapeRasterizer.instrumentWiring(shape, ids, detail);
                    if (!drawing.unresolved().isEmpty())
                        failures.add(
                                shape.kind()
                                        + " "
                                        + shape.diameter()
                                        + " "
                                        + shape.tangentLength()
                                        + " "
                                        + detail
                                        + " "
                                        + drawing.unresolved());
                } catch (RuntimeException e) {
                    failures.add(
                            shape.kind()
                                    + " "
                                    + shape.diameter()
                                    + " "
                                    + detail
                                    + " "
                                    + e.getMessage());
                }
            }
        }
        System.out.println("native_instrument_finish_profiles=" + checked);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void orthogonalSurfaceRunsStayContinuousOnBothMeshDetails() {
        var shapes =
                List.of(
                        EquipmentShape.drum(
                                4,
                                8,
                                EquipmentShape.Orientation.VERTICAL,
                                EquipmentShape.Heading.NORTH,
                                3),
                        EquipmentShape.drum(
                                4,
                                8,
                                EquipmentShape.Orientation.HORIZONTAL,
                                EquipmentShape.Heading.NORTH,
                                3),
                        EquipmentShape.storage(
                                EquipmentShape.Kind.SPHERE, 6, 1, EquipmentShape.Heading.NORTH, 2),
                        EquipmentShape.unit(
                                EquipmentShape.Kind.EXCHANGER,
                                1.5,
                                8,
                                EquipmentShape.Heading.NORTH,
                                2));
        for (var shape : shapes)
            for (var detail : RenderDetail.values()) {
                var all =
                        EquipmentInstruments.of(shape).probes().stream()
                                .map(EquipmentInstruments.Probe::id)
                                .collect(java.util.stream.Collectors.toSet());
                var drawing = ShapeRasterizer.instrumentWiring(shape, all, detail);
                assertTrue(
                        drawing.unresolved().isEmpty(),
                        shape + " " + detail + " " + drawing.unresolved());
                assertFalse(drawing.runs().isEmpty());
                assertEquals(
                        1,
                        drawing.runs().stream().filter(r -> r.source().equals("baseline")).count());
                assertTrue(
                        drawing.polygons().stream()
                                .anyMatch(p -> p.material() == Mesh.Material.INSTRUMENT_CONDUIT));
                for (var p : drawing.polygons()) {
                    for (double v : p.positions()) assertTrue(Double.isFinite(v));
                    for (double v : p.uvs())
                        assertTrue(
                                v >= -1e-8 && v <= 1 + 1e-8,
                                "Instrument finish escapes its atlas sprite");
                }
            }
    }

    @Test
    void surfaceFinishCoversTheRibbonWidthAtCurvedFacetJoints() {
        var shape =
                EquipmentShape.drum(
                        4, 8, EquipmentShape.Orientation.VERTICAL, EquipmentShape.Heading.NORTH, 3);
        var all =
                EquipmentInstruments.of(shape).probes().stream()
                        .map(EquipmentInstruments.Probe::id)
                        .collect(java.util.stream.Collectors.toSet());
        for (var detail : RenderDetail.values()) {
            var drawing = ShapeRasterizer.instrumentWiring(shape, all, detail);
            var finish = new ServiceSurface(drawing.polygons());
            var baseline =
                    drawing.runs().stream()
                            .filter(r -> r.source().equals("baseline"))
                            .findFirst()
                            .orElseThrow();
            int checked = 0;
            for (int i = 1; i + 1 < baseline.points().size(); i++)
                for (double dx : new double[] {-.0001, .0001})
                    for (double dz : new double[] {-.3, .3}) {
                        var point = baseline.points().get(i).clone();
                        point[0] += dx;
                        point[2] += dz * baseline.width();
                        assertDoesNotThrow(
                                () ->
                                        finish.ray(
                                                1,
                                                point,
                                                1,
                                                Set.of(Mesh.Material.INSTRUMENT_CONDUIT)),
                                detail + " has a ribbon-width gap at " + Arrays.toString(point));
                        checked++;
                    }
            assertTrue(checked >= 4, "The test must sample real native facet joints");
        }
    }

    @Test
    void constructorPreviewIncludesTheFactoryProbeCapsAndLevelDisplays() {
        var shapes =
                List.of(
                        EquipmentShape.unit(
                                EquipmentShape.Kind.EXCHANGER,
                                1.5,
                                8,
                                EquipmentShape.Heading.NORTH,
                                2),
                        EquipmentShape.unit(
                                EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,
                                8,
                                2,
                                EquipmentShape.Heading.NORTH,
                                0));
        for (var shape : shapes)
            for (var detail : RenderDetail.values()) {
                var preview = new ServiceSurface(ShapeRasterizer.renderPreview(shape, detail));
                for (var probe : EquipmentInstruments.of(shape).probes())
                    if (!probe.manual()) {
                        var n = probe.top() ? new double[] {0, 1, 0} : probe.normal();
                        int axis = Math.abs(n[0]) > .5 ? 0 : Math.abs(n[1]) > .5 ? 1 : 2;
                        int sign = n[axis] > 0 ? 1 : -1;
                        var face =
                                preview.ray(
                                        axis,
                                        probe.centre(),
                                        sign,
                                        Set.of(
                                                probe.display()
                                                        ? Mesh.Material.OPENING
                                                        : Mesh.Material.INSTRUMENT_STEEL));
                        assertEquals(
                                probe.centre()[axis] + n[axis] * (probe.display() ? .072 : .045),
                                face[axis],
                                1e-6,
                                shape.kind()
                                        + " "
                                        + detail
                                        + " "
                                        + probe.id()
                                        + " is missing from the constructor preview");
                    }
            }
    }
}
