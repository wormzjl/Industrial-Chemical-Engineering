package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConduitGeometryTest {
    @Test
    void branchingAnOffsetLineKeepsEveryBuiltFaceConnectedToItsBody() {
        for (int missing = 0; missing < 6; missing++)
            for (int shifted = 0; shifted < 6; shifted++)
                if (shifted != missing)
                    for (int u : new int[] {-12, 0, 12})
                        for (int v : new int[] {-12, 12}) {
                            int mask = 63 & ~(1 << missing) & ~(1 << (missing ^ 1));
                            if ((mask & 1 << shifted) == 0) continue;
                            var key =
                                    new ConduitGeometry.Key(
                                            mask, LineGeometry.Key.offsets(shifted, u, v));
                            var polygons = ConduitGeometry.polygons(key, false);
                            var point = key.point(shifted);
                            int axis = LineGeometry.axis(shifted);
                            for (int corner = 0; corner < PipeSizes.sides(.125); corner++) {
                                var expected =
                                        new Mesh.Frame(
                                                        point,
                                                        LineGeometry.step(shifted),
                                                        LineGeometry.ref(shifted))
                                                .at(
                                                        2
                                                                * Math.PI
                                                                * corner
                                                                / PipeSizes.sides(.125),
                                                        .0625,
                                                        0);
                                boolean found = false;
                                for (var p : polygons)
                                    if (p.material() == Mesh.Material.PIPE)
                                        for (int k = 0; k < p.positions().length; k += 3)
                                            if (Math.abs(p.positions()[k] - expected[0])
                                                            + Math.abs(
                                                                    p.positions()[k + 1]
                                                                            - expected[1])
                                                            + Math.abs(
                                                                    p.positions()[k + 2]
                                                                            - expected[2])
                                                    < 1e-8) found = true;
                                assertTrue(
                                        found,
                                        "Junction body stops short of offset connector: "
                                                + key
                                                + " / axis="
                                                + axis
                                                + " corner="
                                                + corner);
                            }
                        }
    }

    @Test
    void drumTerminalPadsTouchTheShellAndPreferFaceCentres() {
        for (var heading : EquipmentShape.Heading.values())
            for (var orientation :
                    new EquipmentShape.Orientation[] {
                        EquipmentShape.Orientation.VERTICAL, EquipmentShape.Orientation.HORIZONTAL
                    })
                for (double diameter : new double[] {.5, 2, 10})
                    for (int length : new int[] {1, 4, 12}) {
                        var s =
                                EquipmentShape.drum(
                                        diameter,
                                        length,
                                        orientation,
                                        heading,
                                        EquipmentShape.defaultSupport(diameter, orientation));
                        var socket = SocketGeometry.data(s);
                        assertNotNull(socket);
                        var n = LineGeometry.step(socket.face());
                        var back =
                                new double[] {
                                    socket.x() - .04 * n[0],
                                    socket.y() - .04 * n[1],
                                    socket.z() - .04 * n[2]
                                };
                        assertTrue(
                                ShapeRasterizer.inside(
                                        s,
                                        back[0] - .02 * n[0],
                                        back[1] - .02 * n[1],
                                        back[2] - .02 * n[2]),
                                s.toString());
                        assertFalse(
                                ShapeRasterizer.inside(
                                        s,
                                        back[0] + .06 * n[0],
                                        back[1] + .06 * n[1],
                                        back[2] + .06 * n[2]),
                                s.toString());
                        assertTrue(Double.isFinite(socket.x() + socket.y() + socket.z()));
                        int axis = LineGeometry.axis(socket.face());
                        double[] p = {socket.x(), socket.y(), socket.z()};
                        var housing = ShapeRasterizer.services(s).enclosures().getFirst().centre();
                        for (int k = 0; k < 3; k++)
                            if (k != axis)
                                assertEquals(
                                        housing[k],
                                        p[k],
                                        1e-10,
                                        "Gland is centred on the actual enclosure face");
                    }
    }

    @Test
    void motorAndGeneratorConnectorsFaceOppositeSidesAtEveryHeading() {
        for (var kind :
                new EquipmentShape.Kind[] {
                    EquipmentShape.Kind.MOTOR, EquipmentShape.Kind.GENERATOR
                })
            for (var heading : EquipmentShape.Heading.values())
                for (double size : new double[] {.75, 22, 400, 500, 1000, 20000}) {
                    var shape = EquipmentShape.unit(kind, size, 1, heading, 0);
                    var data = SocketGeometry.data(shape);
                    var power = SocketGeometry.power(shape);
                    assertNotNull(data, shape.toString());
                    assertNotNull(power, shape.toString());
                    assertEquals(data.face() ^ 1, power.face(), shape.toString());
                    assertEquals(data.y(), power.y(), 1e-12, shape.toString());
                    var transverse = LineGeometry.step(data.face());
                    if (transverse[0] != 0)
                        assertEquals(data.z(), power.z(), 1e-12, shape.toString());
                    else assertEquals(data.x(), power.x(), 1e-12, shape.toString());
                    var n = LineGeometry.step(power.face());
                    assertTrue(
                            (power.x() - data.x()) * n[0] + (power.z() - data.z()) * n[2] > .25,
                            shape.toString());
                }
    }

    @Test
    void everyMediumBuildsWellFormedStraightElbowAndTeeMeshes() {
        for (var medium : CableMedium.values())
            for (int joints : new int[] {48, 36, 52})
                for (var p : ConduitGeometry.polygons(new ConduitGeometry.Key(joints, 0, medium))) {
                    for (double v : p.positions()) assertTrue(Double.isFinite(v));
                    assertEquals(p.size() * 2, p.uvs().length);
                    assertEquals(p.size() * 3, p.normals().length);
                }
    }

    @Test
    void insulatedEndsAreClosedAndBothKindsCarryDifferentSymbolsOnEveryStraightHeading() {
        for (int axis = 0; axis < 3; axis++) {
            int face = 2 * axis, mask = 3 << face;
            var data = ConduitGeometry.polygons(new ConduitGeometry.Key(mask, 0, CableMedium.DATA));
            var power =
                    ConduitGeometry.polygons(new ConduitGeometry.Key(mask, 0, CableMedium.POWER));
            for (var medium : CableMedium.values()) {
                var polygons = medium == CableMedium.DATA ? data : power;
                var ends =
                        polygons.stream()
                                .filter(p -> p.material() == Mesh.Material.OPENING && p.size() == 8)
                                .toList();
                assertEquals(
                        2,
                        ends.size(),
                        "Both insulated cut ends must block the view through the conduit");
                assertTrue(
                        polygons.stream()
                                        .filter(
                                                p ->
                                                        p.material() == Mesh.Material.OPENING
                                                                && p.size() != 8)
                                        .count()
                                >= 12,
                        "Repeating symbols must be visible from all four sides");
                for (var end : ends)
                    for (int v = 0; v < end.size(); v++) {
                        int dim = LineGeometry.axis(face);
                        double at = end.positions()[3 * v + dim];
                        assertTrue(
                                Math.abs(at - .002) < 1e-9 || Math.abs(at - .998) < 1e-9,
                                "Caps are recessed to avoid shared coplanar faces");
                        assertEquals(0, Math.abs(end.normals()[3 * v + dim]) - 1, 1e-12);
                    }
            }
            assertNotEquals(
                    data.stream()
                            .filter(p -> p.material() == Mesh.Material.OPENING)
                            .map(p -> java.util.Arrays.toString(p.positions()))
                            .toList(),
                    power.stream()
                            .filter(p -> p.material() == Mesh.Material.OPENING)
                            .map(p -> java.util.Arrays.toString(p.positions()))
                            .toList(),
                    "Power and data symbols differ geometrically");
        }
    }

    @Test
    void wideningSavedRackCablesKeepsTheirEndCoordinates() {
        for (int facing = 2; facing < 6; facing++)
            for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++) {
                var rack = new RackGeometry.Rack(facing, corner);
                int arms = RackGeometry.runs(rack);
                if (Integer.bitCount(arms) != 2) continue;
                var old = new RackGeometry.Lane(0, 1, arms, arms, PipeSizes.code(.0625));
                var current =
                        new RackGeometry.Lane(
                                0, 1, arms, arms, PipeSizes.code(CableMedium.DATA.outside()));
                var saved = new ConduitGeometry.Key(arms, 0, CableMedium.DATA);
                for (int face = 0; face < 6; face++)
                    if ((arms & 1 << face) != 0) {
                        int point =
                                RackGeometry.point(
                                        rack, old.option(), old.width(), old.diameter(), face);
                        saved = saved.connect(face, signed(point & 31), signed(point >> 5 & 31));
                    }
                var caps =
                        ConduitGeometry.lane(rack, current, saved).stream()
                                .filter(p -> p.material() == Mesh.Material.OPENING && p.size() == 8)
                                .toList();
                assertEquals(2, caps.size());
                for (int face = 0; face < 6; face++)
                    if ((arms & 1 << face) != 0) {
                        var expected = saved.point(face);
                        var normal = LineGeometry.step(face);
                        var matching =
                                caps.stream()
                                        .filter(
                                                p ->
                                                        p.normals()[0] * normal[0]
                                                                        + p.normals()[1] * normal[1]
                                                                        + p.normals()[2] * normal[2]
                                                                > .99)
                                        .findFirst()
                                        .orElseThrow();
                        var centre = new double[3];
                        for (int v = 0; v < matching.size(); v++)
                            for (int k = 0; k < 3; k++)
                                centre[k] += matching.positions()[3 * v + k] / matching.size();
                        for (int k = 0; k < 3; k++)
                            assertEquals(
                                    expected[k] - .002 * normal[k],
                                    centre[k],
                                    1e-10,
                                    "Saved cable end moved with its diameter");
                    }
            }
    }

    private static int signed(int value) {
        return value >= 16 ? value - 32 : value;
    }

    @Test
    void serviceSocketsClearTheCastingsAcrossEveryEquipmentFamily() {
        var kinds = java.util.EnumSet.noneOf(EquipmentShape.Kind.class);
        var buried = new java.util.ArrayList<String>();
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            kinds.add(shape.kind());
            var data = SocketGeometry.data(shape);
            var power = SocketGeometry.power(shape);
            if (data != null) {
                checkClear(shape, data, buried);
            }
            if (power != null) {
                checkClear(shape, power, buried);
            }
            var node = ShapeRasterizer.fluidCell(shape);
            for (var socket : InstrumentGeometry.sockets(shape, node)) {
                assertTrue(Double.isFinite(socket.x() + socket.y() + socket.z()), shape.toString());
            }
        }
        assertEquals(java.util.EnumSet.allOf(EquipmentShape.Kind.class), kinds);
        assertTrue(buried.isEmpty(), "Sockets buried in castings:\n" + String.join("\n", buried));
    }

    @Test
    void largeMotorPortsUseMatchedHousingsOnTheSolidCentreStrip() {
        for (var kind :
                new EquipmentShape.Kind[] {
                    EquipmentShape.Kind.MOTOR, EquipmentShape.Kind.GENERATOR
                })
            for (double kw : new double[] {500, 1000, 20000}) {
                var shape = EquipmentShape.unit(kind, kw, 1, EquipmentShape.Heading.NORTH, 0);
                var service = ShapeRasterizer.services(shape);
                var data = service.data();
                var power = service.power();
                assertEquals(data.face() ^ 1, power.face());
                assertFalse(data.junctionBox());
                assertFalse(power.junctionBox());
                assertEquals(2, service.enclosures().size());
                var a = service.enclosures().getFirst();
                var b = service.enclosures().getLast();
                var lo = a.low();
                var hi = a.high();
                var low = b.low();
                var high = b.high();
                for (int k = 0; k < 3; k++)
                    assertEquals(
                            hi[k] - lo[k],
                            high[k] - low[k],
                            1e-10,
                            "Matched DATA and FE housing sizes");
                assertEquals(data.y(), power.y(), 1e-10);
                assertEquals(data.z(), power.z(), 1e-10);
            }
    }

    @Test
    void shortConnectorLeadsDoNotFoldBackAndOverlappingGlandsNeedNoExtraLead() {
        for (var medium : CableMedium.values()) {
            var n = new double[] {1, 0, 0};
            assertTrue(ConduitGeometry.lead(n, new double[] {-.02, 0, 0}, n, medium).isEmpty());
            var end = new double[] {.2, 0, 0};
            var expected =
                    LineGeometry.conduit(java.util.List.of(new double[3], end), medium.outside());
            var actual = ConduitGeometry.lead(n, end, n, medium);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < actual.size(); i++)
                assertArrayEquals(expected.get(i).positions(), actual.get(i).positions());
        }
    }

    private static void checkClear(
            EquipmentShape shape, SocketGeometry.Socket socket, java.util.List<String> buried) {
        assertTrue(Double.isFinite(socket.x() + socket.y() + socket.z()), shape.toString());
        var n = LineGeometry.step(socket.face());
        if (ShapeRasterizer.inside(
                shape, socket.x() + .06 * n[0], socket.y() + .06 * n[1], socket.z() + .06 * n[2]))
            buried.add(shape + " / " + socket);
    }
}
