package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Clash;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Connection;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.wormzjl.icheme.science.equipment.EquipmentShape.drum;
import static org.junit.jupiter.api.Assertions.*;

class ShapeRasterizerTest {
    @Test
    void aDedicatedServerLaysOutKeysAndCollidesWithoutFacets() {
        // ShapeRasterizer.serverOnly (2026-10-16): a dedicated server drops a geometry's facets once it is laid out. Its
        // layout, cell keys, ports and solids must be the drawing side's, and it must refuse to draw a cell.
        var shapes = List.of(drum(3, 9, Orientation.VERTICAL, Heading.NORTH, 4), drum(2, 6, Orientation.HORIZONTAL, Heading.EAST, 1),
                EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.NORTH, 4), EquipmentShape.unit(Kind.AIR_COOLER, 3, 6, Heading.NORTH, 4));
        var drawn = new java.util.ArrayList<Object>();
        for (var shape : shapes) drawn.add(serverView(shape));
        try {
            ShapeRasterizer.drawing(false);
            for (int k = 0; k < shapes.size(); k++) assertEquals(drawn.get(k), serverView(shapes.get(k)), shapes.get(k).toString());
            var cell = ShapeRasterizer.layout(shapes.getFirst()).shell().getFirst();
            assertThrows(IllegalStateException.class, () -> ShapeRasterizer.surface(ShapeRasterizer.key(shapes.getFirst(), cell.x(), cell.y(), cell.z())));
        } finally {
            ShapeRasterizer.drawing(true);
        }
    }

    /** What a server reads of a vessel: its layout, each shell cell's key and solid with a 0.1 m pipe on every nozzle, and its ports. */
    private static List<Object> serverView(EquipmentShape shape) {
        var layout = ShapeRasterizer.layout(shape);
        var keys = new java.util.ArrayList<Object>();
        var piped = new double[shape.nozzles().size()];
        java.util.Arrays.fill(piped, .1);
        for (var cell : layout.shell()) {
            var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z(), ShapeRasterizer.cellCodes(shape, cell, piped));
            keys.add(List.of(key, ShapeRasterizer.solid(key)));
        }
        return List.of(layout, keys, ShapeRasterizer.ports(shape));
    }

    /**
     * Bare drums (small, odd, half-metre and largest), drums on skirts, lying drums on saddles facing each way, columns
     * (a crude column with two pumparounds on its least skirt, and the smallest diameter with four pumparounds, two draws
     * and steam), and units at their smallest, odd and largest sizes. (0.5, 5) lying puts a saddle face exactly on a cell
     * plane; (10, 60) is the report's largest vessel.
     */
    private static final List<EquipmentShape> SHAPES = List.of(new EquipmentShape(.5, 1), new EquipmentShape(2, 3),
            new EquipmentShape(2.5, 2), new EquipmentShape(3, 9), new EquipmentShape(4, 5), new EquipmentShape(10, 60),
            drum(2, 3, Orientation.VERTICAL, Heading.NORTH, 4), drum(6, 10, Orientation.VERTICAL, Heading.EAST, 5),
            drum(2.5, 2, Orientation.HORIZONTAL, Heading.WEST, 1), drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2),
            drum(.5, 5, Orientation.HORIZONTAL, Heading.SOUTH, 1), drum(4, 12, Orientation.HORIZONTAL, Heading.EAST, 1),
            drum(3, 9, Orientation.HORIZONTAL, Heading.EAST, EquipmentShape.minimumBootSupport(3, 9),
                    EquipmentShape.defaultConnections(3, 9, Orientation.HORIZONTAL, EquipmentShape.minimumBootSupport(3, 9), true), true),
            // Boot drums (owner decision 2026-10-25: the outlets at the front end, the oil between the boot and the front head):
            // a 1 m drum 6 m long (owner request: a boot from 6 m; its oil leaves the front head) and an 8 m drum 7 m long.
            drum(1, 6, Orientation.HORIZONTAL, Heading.SOUTH, EquipmentShape.minimumBootSupport(1, 6), List.of(), true),
            drum(8, 7, Orientation.HORIZONTAL, Heading.WEST, EquipmentShape.minimumBootSupport(8, 7),
                    EquipmentShape.defaultConnections(8, 7, Orientation.HORIZONTAL, EquipmentShape.minimumBootSupport(8, 7), true), true),
            // Bottom outlets turned (owner request 2026-10-25) to the left, the back and the right.
            drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4, EquipmentShape.defaultConnections(3, 4, Orientation.VERTICAL, 4, false, 1), false, 1),
            drum(6, 10, Orientation.VERTICAL, Heading.EAST, 5, EquipmentShape.defaultConnections(6, 10, Orientation.VERTICAL, 5, false, 2), false, 2),
            drum(.5, 4, Orientation.VERTICAL, Heading.SOUTH, EquipmentShape.minimumSkirt(.5),
                    EquipmentShape.defaultConnections(.5, 4, Orientation.VERTICAL, EquipmentShape.minimumSkirt(.5), false, 3), false, 3),
            EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.SOUTH, 4),
            EquipmentShape.column(1.5, new ColumnTrays(25, 13, List.of(8), List.of(26), List.of(new ColumnTrays.Pumparound(6, 4), new ColumnTrays.Pumparound(15, 13))),
                    Heading.EAST, EquipmentShape.minimumSkirt(1.5)),
            EquipmentShape.column(.5, new ColumnTrays(30, 15, List.of(8, 20), List.of(31), List.of(new ColumnTrays.Pumparound(6, 4),
                    new ColumnTrays.Pumparound(12, 10), new ColumnTrays.Pumparound(18, 16), new ColumnTrays.Pumparound(26, 24))), Heading.NORTH, EquipmentShape.minimumSkirt(.5)),
            EquipmentShape.unit(Kind.EXCHANGER, .5, 2, Heading.NORTH, 2), EquipmentShape.unit(Kind.EXCHANGER, .75, 3, Heading.EAST, 2),
            EquipmentShape.unit(Kind.EXCHANGER, 2.5, 12, Heading.WEST, 3), EquipmentShape.unit(Kind.KETTLE, .5, 2, Heading.SOUTH, 2),
            EquipmentShape.unit(Kind.KETTLE, 2, 10, Heading.EAST, 2), EquipmentShape.unit(Kind.AIR_COOLER, 3, 6, Heading.NORTH, 5),
            EquipmentShape.unit(Kind.EJECTOR, .1, 1, Heading.NORTH, 1), EquipmentShape.unit(Kind.EJECTOR, .3, 1, Heading.EAST, 5),
            EquipmentShape.unit(Kind.EJECTOR, PipeSizes.MAX_OUTSIDE, 1, Heading.WEST, 2), EquipmentShape.unit(Kind.PUMP, .05, 1, Heading.NORTH, 0),
            EquipmentShape.unit(Kind.PUMP, .4, 1, Heading.EAST, 0), EquipmentShape.unit(Kind.COMPRESSOR, .15, 2, Heading.SOUTH, ProcessUnit.minSupport(Kind.COMPRESSOR, .15, 2)),
            EquipmentShape.unit(Kind.COMPRESSOR, .8, 6, Heading.NORTH, ProcessUnit.minSupport(Kind.COMPRESSOR, .8, 6)),
            EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 8, Heading.NORTH, 3), EquipmentShape.unit(Kind.CABIN_HEATER, 6, 12, Heading.WEST, 3),
            // Storage (owner request 2026-10-21): the least tank, a mid one turned, the least sphere on its highest legs, the concept sheet's sphere.
            EquipmentShape.storage(Kind.TANK, 4, 4, Heading.NORTH, 0), EquipmentShape.storage(Kind.TANK, 20, 12, Heading.EAST, 0),
            EquipmentShape.storage(Kind.SPHERE, 4, 1, Heading.SOUTH, 8), EquipmentShape.storage(Kind.SPHERE, 14, 1, Heading.NORTH, 2),
            // The ladder forms with their rest platforms (owner change 2026-10-21): two rests up a tall small tank, one beside a small sphere.
            EquipmentShape.storage(Kind.TANK, 5, 22, Heading.WEST, 0), EquipmentShape.storage(Kind.SPHERE, 9, 1, Heading.EAST, 8));

    /** The drums, columns, units and storage sampled here. */
    static List<EquipmentShape> shapes() {
        return SHAPES;
    }

    @Test
    void rejectsShapesOffTheGrid() {
        for (double[] bad : new double[][]{{.25, 3}, {2.25, 3}, {10.5, 3}, {Double.NaN, 3}, {2, 0}, {2, 1.5}, {2, 65}, {2, Double.POSITIVE_INFINITY}})
            assertThrows(IllegalArgumentException.class, () -> new EquipmentShape(bad[0], bad[1]), bad[0] + " x " + bad[1]);
        // A skirt shorter than the bottom head, saddles on the ground, supports above 16 m.
        for (var bad : List.of(new Object[]{6., Orientation.VERTICAL, 1}, new Object[]{2., Orientation.VERTICAL, -1}, new Object[]{2., Orientation.VERTICAL, 17},
                new Object[]{2., Orientation.HORIZONTAL, 0}, new Object[]{2., Orientation.HORIZONTAL, 17}))
            assertThrows(IllegalArgumentException.class, () -> drum((double) bad[0], 3, (Orientation) bad[1], Heading.NORTH, (int) bad[2]), List.of(bad).toString());
        // Columns reach 15 m, drums 10 m; a column stands on a skirt and takes its length from its trays.
        assertDoesNotThrow(() -> EquipmentShape.column(12, ColumnTrays.simple(20, 10), Heading.NORTH, 6));
        assertThrows(IllegalArgumentException.class, () -> drum(12, 5, Orientation.VERTICAL, Heading.NORTH, 3));
        assertThrows(IllegalArgumentException.class, () -> EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.NORTH, 0));
        assertThrows(IllegalArgumentException.class, () -> new EquipmentShape(EquipmentShape.Kind.COLUMN, 3, 4, Orientation.VERTICAL, Heading.NORTH, 2, ColumnTrays.simple(12, 6), List.of(),
                ProcessUnit.Bank.ONE, false, 0));
        // Owner decision 2026-10-25: a lower skirt, the bottom line on the lowest row: ceil(0.5 + 0.8 + 0.05 + D/4).
        assertEquals(2, EquipmentShape.minimumSkirt(2.5));
        assertEquals(3, EquipmentShape.minimumSkirt(6));
        assertEquals(6, EquipmentShape.minimumSkirt(15));
        assertEquals(3, EquipmentShape.defaultSupport(6, Orientation.VERTICAL));
        assertEquals(4, EquipmentShape.defaultSupport(10, Orientation.VERTICAL));
        assertEquals(1, EquipmentShape.defaultSupport(10, Orientation.HORIZONTAL));
    }

    @Test
    void surfacePiecesAddUpToTheFacetedArea() {
        for (var shape : SHAPES) {
            double area = 0;
            for (var cell : ShapeRasterizer.layout(shape).shell())
                for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) area += area(polygon);
            // Every facet is drawn once, whole: clipping to cells and splitting at texture tiles neither lose nor double area,
            // but for a few slivers under Mesh.MIN_AREA, which are not drawn.
            double facets = ShapeRasterizer.facetArea(shape);
            assertEquals(facets, area, 1e-9 * facets + 20 * Mesh.MIN_AREA, shape.toString());
            if (shape.drum() && !shape.skirt() && !shape.saddles())
                assertEquals(vesselArea(shape), facets - ShapeRasterizer.nozzleArea(shape) - manwayArea(shape), 1e-9 * facets, shape.toString());
        }
    }

    @Test
    void partsCarryTheirMaterials() {
        // Skirt row 0 (base ring, chairs, floor plate, the bottom outlet with its flange and bolts leaving through its port
        // on the lowest row: owner decision 2026-10-25, a lower skirt), the skirt row under the head seam (the head inside
        // it, the band over the seam, the vents' dark bores), a shell row, the top head with its nozzle.
        var standing = drum(6, 10, Orientation.VERTICAL, Heading.NORTH, 3);
        assertEquals(EnumSet.of(Material.SUPPORT, Material.PIPE, Material.FLANGE, Material.BOLT), materials(standing, 0, 1));
        assertEquals(EnumSet.of(Material.SUPPORT, Material.HEAD, Material.FLANGE, Material.OPENING), materials(standing, 2, 3));
        assertEquals(Set.of(Material.SHELL), materials(standing, 5, 6));
        assertTrue(materials(standing, standing.sizeY() - 1, standing.sizeY()).containsAll(Set.of(Material.HEAD, Material.PIPE, Material.FLANGE)));
        // A lying drum's lowest row holds only its saddles.
        assertEquals(Set.of(Material.SUPPORT), materials(drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2), 0, 1));
    }

    /**
     * A vessel whose skirt shows its inside lets skylight through its interior cells (owner request 2026-10-27: "Remove
     * the shadow in drum/vessel skirt", the floor inside a skirt dark, worse the wider the drum); every other vessel keeps
     * its interior opaque and its shadow on the ground.
     */
    @Test
    void aSkirtShowingItsInsideLetsSkylightThrough() {
        for (var shape : List.of(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 3), drum(10, 12, Orientation.VERTICAL, Heading.EAST, 4),
                EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.NORTH, 4), leastUnit(Kind.TRICKLE_BED_REACTOR), leastUnit(Kind.EBULLATED_BED_REACTOR)))
            assertTrue(ShapeRasterizer.skylight(shape), shape.toString());
        for (var shape : List.of(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 0), drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2),
                leastUnit(Kind.SPHERE), leastUnit(Kind.TANK), leastUnit(Kind.ADSORBER), leastUnit(Kind.DELAYED_COKER), leastUnit(Kind.PUMP), leastUnit(Kind.AIR_COOLER)))
            assertFalse(ShapeRasterizer.skylight(shape), shape.toString());
    }

    private static EquipmentShape leastUnit(Kind kind) {
        double d = ProcessUnit.minDiameter(kind);
        int length = ProcessUnit.minLength(kind, d);
        return EquipmentShape.designed(kind, d, length, Heading.NORTH, ProcessUnit.minSupport(kind, d, length), ProcessUnit.Bank.ONE);
    }

    @Test
    void everyPieceStaysInsideItsCellAndFacesOutwards() {
        for (var shape : SHAPES)
            for (var cell : ShapeRasterizer.layout(shape).shell()) {
                var polygons = ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()));
                // A cell only a nozzle's largest flange reaches is empty while the nozzle is drawn at its default size; a
                // drum's fluid node inside its skirt is empty too, and so is a cell only a ladder's climb zone reaches.
                assertTrue(!polygons.isEmpty() || reachedByNozzle(shape, cell) || cell.equals(ShapeRasterizer.fluidCell(shape)) || reachedByClimb(shape, cell), shape + " " + cell);
                for (var polygon : polygons) {
                    assertTrue(polygon.size() >= 3);
                    for (double v : polygon.positions()) assertTrue(v >= 0 && v <= 1, shape + " " + cell + " position " + v);
                    for (double v : polygon.uvs()) assertTrue(v >= 0 && v <= 1, shape + " " + cell + " uv " + v);
                    double[] face = newell(polygon), smooth = new double[3];
                    for (int i = 0; i < polygon.size(); i++) {
                        double nx = polygon.normals()[3 * i], ny = polygon.normals()[3 * i + 1], nz = polygon.normals()[3 * i + 2];
                        assertEquals(1, nx * nx + ny * ny + nz * nz, 1e-9);
                        smooth[0] += nx;
                        smooth[1] += ny;
                        smooth[2] += nz;
                    }
                    assertTrue(face[0] * smooth[0] + face[1] * smooth[1] + face[2] * smooth[2] > 0, shape + " " + cell + " winding " + polygon.material());
                }
            }
    }

    @Test
    void bodyKeyGeometryRepeatsAlongTheAxis() {
        for (var shape : SHAPES) {
            int bodies = 0;
            for (var cell : ShapeRasterizer.layout(shape).shell()) {
                var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z());
                if (axial(key) > ShapeRasterizer.CellKey.BODY) continue;
                bodies++;
                var shared = ShapeRasterizer.surface(key);
                var actual = ShapeRasterizer.surface(shape, cell.x(), cell.y(), cell.z(), 0);
                assertEquals(actual.size(), shared.size(), shape + " " + cell);
                // Clipping may start a polygon at another vertex on another row: equal up to a cyclic shift.
                for (int i = 0; i < actual.size(); i++)
                    assertTrue(sameUpToRotation(actual.get(i), shared.get(i)), shape + " " + cell + " polygon " + i);
            }
            // A long drum has plain rows; a column only where its platforms and nozzles leave some, a unit none. A boot drum's
            // boot and its liquid outlet behind it (owner request 2026-10-25) take its middle rows up to 9 m.
            if (shape.drum() && shape.tangentLength() >= (shape.boot() ? 10 : 9) || shape.kind() == Kind.TANK && shape.tangentLength() >= 8) assertTrue(bodies > 0, shape + " has no body rows");
        }
    }

    @Test
    void interiorIsSealedByTheShell() {
        for (var shape : SHAPES) {
            var layout = ShapeRasterizer.layout(shape);
            var occupied = new HashSet<ShapeRasterizer.Cell>(layout.shell());
            occupied.addAll(layout.interior());
            for (var cell : layout.interior())
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    // A floating-roof tank is open at its top and stands on its foundation.
                    if (shape.kind() == Kind.TANK && d[1] != 0) continue;
                    assertTrue(occupied.contains(new ShapeRasterizer.Cell(cell.x() + d[0], cell.y() + d[1], cell.z() + d[2])),
                            shape + " interior " + cell + " touches the outside");
                }
        }
    }

    @Test
    void solidMatchesTheFacetedVolume() {
        int s = ShapeRasterizer.SOLID_RESOLUTION;
        for (var shape : SHAPES) {
            var layout = ShapeRasterizer.layout(shape);
            double volume = layout.interior().stream().filter(c -> ShapeRasterizer.insideVessel(shape, c.x() + .5, c.y() + .5, c.z() + .5)).count();
            for (var cell : layout.shell()) {
                int parts = 0, sampled = 0;
                for (var box : ShapeRasterizer.solid(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) {
                    assertTrue(box.x0() < box.x1() && box.x1() <= s && box.y0() < box.y1() && box.y1() <= s && box.z0() < box.z1() && box.z1() <= s);
                    parts += (box.x1() - box.x0()) * (box.y1() - box.y0()) * (box.z1() - box.z0());
                }
                for (int a = 0; a < s; a++) for (int b = 0; b < s; b++) for (int c = 0; c < s; c++)
                    if (ShapeRasterizer.inside(shape, cell.x() + (a + .5) / s, cell.y() + (b + .5) / s, cell.z() + (c + .5) / s)) sampled++;
                assertEquals(sampled, parts, shape + " " + cell + ": boxes overlap or miss samples");
                // The vessel's own solid: a column's platforms, ladders and manways are checked by ColumnStructureTest.
                int vessel = 0;
                for (int a = 0; a < s; a++) for (int b = 0; b < s; b++) for (int c = 0; c < s; c++)
                    if (ShapeRasterizer.insideVessel(shape, cell.x() + (a + .5) / s, cell.y() + (b + .5) / s, cell.z() + (c + .5) / s)) vessel++;
                volume += vessel / (double) (s * s * s);
            }
            // A unit has no vessel formula: its boxes match its samples above, its parts are checked by ProcessUnitTest.
            if (shape.designed()) continue;
            // A misclassified sample lies within half a sample diagonal of the surface.
            double exact = facetedVolume(shape) + nozzleVolume(shape), band = ShapeRasterizer.facetArea(shape) * Math.sqrt(3) / (2 * s);
            assertEquals(exact, volume, band, shape.toString());
            // Lattice alignment alone moves a 2 m cross-section by 2 % (16 facets centred on a cell midline sampled +2.1 %).
            if (shape.diameter() >= 2.5) assertEquals(exact, volume, .02 * exact, shape.toString());
        }
    }

    /** A drum's fluid node carries its identity (a block entity), so it is a shell cell even inside a skirt, where nothing is drawn. */
    @Test
    void aDrumsFluidNodeIsAShellCell() {
        for (var shape : SHAPES)
            if (shape.reservoir()) assertTrue(ShapeRasterizer.layout(shape).shell().contains(ShapeRasterizer.fluidCell(shape)), shape.toString());
            // A mover's node is its suction nozzle's port cell (batch 2026-10-23-functional-pumps-compressors).
            else if (shape.kind().mover()) assertTrue(ShapeRasterizer.ports(shape).stream().anyMatch(p -> p.cell().equals(ShapeRasterizer.fluidCell(shape))), shape.toString());
            // A heat-exchanging unit has a node per side, at its first inlet's port cell (batch 2026-10-27-heat-exchanger-design).
            else if (HeatSides.unit(shape.kind()) != null) {
                var sides = HeatSides.of(shape);var cells = ShapeRasterizer.fluidCells(shape);
                // A kettle's shell registers after its sides, at a shell cell (W7).
                assertEquals(sides.size() + (HeatSides.pool(shape.kind()) ? 1 : 0), cells.size(), shape.toString());
                if (HeatSides.pool(shape.kind())) assertTrue(ShapeRasterizer.layout(shape).shell().contains(cells.getLast()), shape.toString());
                for (int i = 0; i < sides.size(); i++) {
                    var inlet = sides.get(i).inlet();
                    var first = ShapeRasterizer.ports(shape).stream().filter(p -> shape.nozzles().get(p.nozzle()).role() == inlet).findFirst().orElseThrow();
                    assertEquals(first.cell(), cells.get(i), shape.toString());
                }
            }
            else assertNull(ShapeRasterizer.fluidCell(shape), shape.toString());
    }

    @Test
    void controllerIsTheLowestShellCellThenWestThenNorth() {
        for (var shape : SHAPES) {
            var layout = ShapeRasterizer.layout(shape);
            var controller = layout.controller();
            assertTrue(layout.shell().contains(controller), shape.toString());
            for (var cell : layout.shell()) {
                assertTrue(cell.y() >= controller.y(), shape + " " + cell);
                if (cell.y() == controller.y()) assertTrue(cell.x() > controller.x() || cell.x() == controller.x() && cell.z() >= controller.z(), shape + " " + cell);
            }
        }
    }

    @Test
    void vesselStandsOnTheGroundAndFillsItsBox() {
        for (var shape : SHAPES) {
            // An odd width puts the axis, and every nozzle's centre line, through cell centres.
            assertEquals(1, shape.width() % 2, shape.toString());
            int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {-1, -1, -1};
            for (var cell : ShapeRasterizer.layout(shape).shell()) {
                int[] c = {cell.x(), cell.y(), cell.z()};
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], c[a]);
                    max[a] = Math.max(max[a], c[a]);
                }
            }
            assertArrayEquals(new int[]{0, 0, 0}, min, shape.toString());
            assertArrayEquals(new int[]{shape.sizeX() - 1, shape.sizeY() - 1, shape.sizeZ() - 1}, max, shape.toString());
        }
    }

    @Test
    void turningTheFrontTurnsTheVessel() {
        // Quarter turns are exact: every heading gives the same cells, sizes and area, with x and z swapped.
        for (var orientation : Orientation.values()) {
            double[] first = null;
            for (var heading : Heading.values()) {
                var shape = drum(2.5, 5, orientation, heading, orientation.horizontal() ? 1 : 4);
                var layout = ShapeRasterizer.layout(shape);
                boolean alongX = heading.dx != 0;
                double[] seen = {layout.shell().size(), layout.interior().size(), shape.sizeY(),
                        alongX ? shape.sizeX() : shape.sizeZ(), alongX ? shape.sizeZ() : shape.sizeX(), ShapeRasterizer.facetArea(shape)};
                if (first == null) first = seen;
                else assertArrayEquals(first, seen, 1e-9, orientation + " " + heading);
            }
        }
    }

    @Test
    void nozzlesEndOnBlockFacesWithRoomForAPipe() {
        for (var shape : SHAPES) {
            ProcessUnitTest.pipesApart(shape);
            var layout = ShapeRasterizer.layout(shape);
            var occupied = new HashSet<ShapeRasterizer.Cell>(layout.shell());
            occupied.addAll(layout.interior());
            var transform = shape.transform();
            for (var port : ShapeRasterizer.ports(shape)) {
                var nozzle = shape.nozzles().get(port.nozzle());
                double[] face = transform.point(nozzle.face()), out = transform.direction(nozzle.direction());
                int[] cell = {port.cell().x(), port.cell().y(), port.cell().z()}, step = {port.dx(), port.dy(), port.dz()};
                for (int a = 0; a < 3; a++) {
                    assertEquals(out[a], step[a], 0, shape + " " + nozzle.role());
                    // On the plane between the port cell and its neighbour, centred on that cell face.
                    double expected = step[a] == 0 ? cell[a] + .5 : cell[a] + (step[a] > 0 ? 1 : 0);
                    assertEquals(expected, face[a], 1e-9, shape + " " + nozzle.role() + " axis " + a);
                }
                assertTrue(occupied.contains(port.cell()), shape + " " + nozzle.role());
                var front = new ShapeRasterizer.Cell(cell[0] + step[0], cell[1] + step[1], cell[2] + step[2]);
                assertFalse(occupied.contains(front), shape + " " + nozzle.role() + ": no room for a pipe at " + front);
                // The flange stands clear of the vessel: just behind the flange, off the neck, is outside.
                double reach = PipeSizes.flange(PipeSizes.MAX_OUTSIDE) / 2 + .02, behind = PipeSizes.flangeThickness(PipeSizes.MAX_OUTSIDE) + .02;
                var beside = nozzle.face().clone();
                beside[nozzle.axis()] -= nozzle.sign() * behind;
                beside[(nozzle.axis() + 1) % 3] += reach;
                var p = transform.point(beside);
                assertFalse(ShapeRasterizer.inside(shape, p[0], p[1], p[2]), shape + " " + nozzle.role() + " flange against the vessel");
            }
            assertEquals(shape.nozzles().size(), ShapeRasterizer.ports(shape).size(), shape.toString());
        }
    }

    @Test
    void drumNozzlesArePortsByPosition() {
        // Owner decision 2026-10-16: the top nozzle is a vapour port, the lowest a liquid port, the rest bulk ports.
        for (var heading : Heading.values()) {
            var standing = drum(3, 4, Orientation.VERTICAL, heading, 4);
            var lying = drum(3, 6, Orientation.HORIZONTAL, heading, 1);
            for (var shape : List.of(standing, lying)) {
                var ports = ShapeRasterizer.ports(shape);
                assertEquals(Set.of(EquipmentShape.PortClass.VAPOUR, EquipmentShape.PortClass.LIQUID, EquipmentShape.PortClass.BULK), Set.copyOf(ports.stream().map(ShapeRasterizer.Port::port).toList()), shape.toString());
                int lowest = Integer.MAX_VALUE, liquid = 0;
                for (var port : ports) {
                    int[] d = {port.dx(), port.dy(), port.dz()};
                    switch (port.port()) {
                        case VAPOUR -> assertArrayEquals(new int[]{0, 1, 0}, d, shape.toString());
                        // A standing drum's bottom outlet leaves its skirt through the access opening on the front (owner
                        // report 2026-10-16); a lying drum's leaves its front head.
                        case LIQUID -> {
                            assertArrayEquals(new int[]{heading.dx, 0, heading.dz}, d, shape.toString());
                            liquid = port.cell().y();
                        }
                        case BULK -> assertArrayEquals(shape.saddles() ? new int[]{0, 1, 0}
                                : new int[]{heading.counterClockwise().dx, 0, heading.counterClockwise().dz}, d, shape.toString());
                    }
                    lowest = Math.min(lowest, port.cell().y());
                }
                assertEquals(lowest, liquid, shape.toString());
            }
        }
    }

    @Test
    void nozzleDrawsAtTheConnectedPipeSize() {
        var standing = drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4);
        int k = 0;
        while (standing.nozzles().get(k).role() != EquipmentShape.Role.LIQUID_OUTLET) k++;
        var nozzle = standing.nozzles().get(k);
        double[][] outside = new double[3][standing.nozzles().size()];
        outside[0][k] = .1;
        outside[1][k] = .3;
        outside[2][k] = 2;
        double[] area = new double[3];
        int[] solid = new int[3];
        long large = 0;
        for (var cell : ShapeRasterizer.nozzleCells(standing, k)) {
            // Unconnected nozzles carry no code.
            assertEquals(0, ShapeRasterizer.cellCodes(standing, cell, new double[standing.nozzles().size()]), cell.toString());
            for (int i = 0; i < 3; i++) {
                long codes = ShapeRasterizer.cellCodes(standing, cell, outside[i]);
                assertNotEquals(0, codes, cell.toString());
                if (i == 2) large = codes;
                var key = ShapeRasterizer.key(standing, cell.x(), cell.y(), cell.z(), codes);
                assertEquals(codes, key.nozzles(), cell.toString());
                for (var polygon : ShapeRasterizer.surface(key)) area[i] += area(polygon);
                for (var box : ShapeRasterizer.solid(key)) solid[i] += (box.x1() - box.x0()) * (box.y1() - box.y0()) * (box.z1() - box.z0());
            }
        }
        // Bigger pipes draw bigger nozzles: the neck and the flange at the pipe's size.
        assertTrue(area[0] < area[1] && area[1] < area[2], List.of(area[0], area[1], area[2]).toString());
        assertTrue(solid[0] < solid[1] && solid[1] < solid[2]);
        // A cell no nozzle reaches drops any codes, so body rows still share one key.
        int unreached = 0;
        for (var cell : ShapeRasterizer.layout(standing).shell())
            if (!reachedByNozzle(standing, cell)) {
                unreached++;
                assertEquals(ShapeRasterizer.key(standing, cell.x(), cell.y(), cell.z()), ShapeRasterizer.key(standing, cell.x(), cell.y(), cell.z(), large), cell.toString());
            }
        assertTrue(unreached > 0);
    }

    /**
     * Owner request 2026-10-16: every nozzle takes the size of its pipe, also where several reach one cell. A cell's key
     * keeps one code per reaching nozzle in nozzle order, so two nozzles in one cell draw at their own pipes' sizes; a
     * column's nozzles scale too, though only a drum is a reservoir.
     */
    @Test
    void nozzlesSharingACellEachKeepTheirPipeSize() {
        for (var shape : SHAPES) {
            int n = shape.nozzles().size();
            var shared = new java.util.HashMap<ShapeRasterizer.Cell, List<Integer>>();
            for (int k = 0; k < n; k++)
                for (var cell : ShapeRasterizer.nozzleCells(shape, k)) shared.computeIfAbsent(cell, c -> new java.util.ArrayList<>()).add(k);
            for (var entry : shared.entrySet()) {
                var cell = entry.getKey();
                var reaching = entry.getValue();
                assertTrue(reaching.size() <= 9, shape + " " + cell + " reached by " + reaching.size());
                // Each nozzle alone at a pipe of 0.3 m sets its own seven bits only.
                for (int j = 0; j < reaching.size(); j++) {
                    double[] outside = new double[n];
                    outside[reaching.get(j)] = .3;
                    long codes = ShapeRasterizer.cellCodes(shape, cell, outside);
                    assertEquals(codes, codes & (0x7FL << (7 * j)), shape + " " + cell + " nozzle " + reaching.get(j));
                    assertNotEquals(0, codes);
                }
            }
            // A column's nozzles are ports that scale, but only a drum is a reservoir.
            if (shape.column()) assertTrue(n > 0 && ShapeRasterizer.ports(shape).size() == n, shape.toString());
        }
    }

    @Test
    void aPipeOfAnySizeHasANeckOfItsOwnSize() {
        // Owner decision 2026-10-16: resizing starts where the port meets the vessel, never at the flange; 2026-10-21: "if I
        // have a 1m pipe then the root needs to be 1m"; 2026-10-25: pipes at most 0.8 m. A line of a pipe's largest on the
        // bottom outlet: the flange is the line's, and so is the neck from its root to the flange's hub, with no reducer.
        var standing = drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4);
        int k = 0;
        while (standing.nozzles().get(k).role() != EquipmentShape.Role.LIQUID_OUTLET) k++;
        var nozzle = standing.nozzles().get(k);
        double[] line = new double[standing.nozzles().size()];
        // Drawn in 1/64 m steps: 0.8 m is drawn at 51/64.
        double max = PipeSizes.outsideOf(PipeSizes.code(PipeSizes.MAX_OUTSIDE));
        line[k] = max;
        double flange = 0, neck = 0, hub = 0, taper = nozzle.length() - PipeSizes.flangeThickness(max) - PipeSizes.hub(max);
        for (var cell : ShapeRasterizer.nozzleCells(standing, k))
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(standing, cell.x(), cell.y(), cell.z(), ShapeRasterizer.cellCodes(standing, cell, line))))
                for (int i = 0; i < polygon.size(); i++) {
                    var pos = polygon.positions();
                    var f = standing.transform().local(new double[]{cell.x() + pos[3 * i], cell.y() + pos[3 * i + 1], cell.z() + pos[3 * i + 2]});
                    var base = nozzle.base();
                    double s = (f[nozzle.axis()] - base[nozzle.axis()]) * nozzle.sign(), rho = 0;
                    for (int a = 0; a < 3; a++) if (a != nozzle.axis()) rho += (f[a] - base[a]) * (f[a] - base[a]);
                    rho = Math.sqrt(rho);
                    if (polygon.material() == Material.FLANGE && s > taper) flange = Math.max(flange, rho);
                    else if (polygon.material() == Material.PIPE && s >= 0)
                        if (s <= taper + 1e-9) neck = Math.max(neck, rho);
                        else hub = Math.max(hub, rho);
                }
        assertEquals(PipeSizes.flange(max) / 2, flange, 1e-6);
        assertEquals(max / 2, neck, 1e-6);
        assertEquals(PipeSizes.hubOutside(max) / 2, hub, 1e-6);
    }

    @Test
    void bottomLineCarriesAPipesLargestOnEverySkirt() {
        // Owner requests 2026-10-16 ("1m pipe on the bottom of drum & column still looks weird", decided: the line rises
        // with the skirt), 2026-10-21 ("Skirt hole needs to adapt to the size of the pipe to max 1m"; the skirt gives way)
        // and 2026-10-25 (pipes at most 0.8 m, a lower skirt): every skirted drum and column carries a line of a pipe's
        // largest on SKIRT_OUTLET, its flange clear of grade, the line and its hole clear of the base plate, the hole and
        // collar under the head seam, its elbow (bend 1 D) under the head's pole, its riser up from the elbow at the axis,
        // and its neck's hub before its flange face. One metre less skirt is refused.
        double max = PipeSizes.MAX_OUTSIDE, port = EquipmentShape.portRadius(max);
        for (boolean column : new boolean[]{false, true})
            for (double d = EquipmentShape.MIN_DIAMETER; d <= (column ? EquipmentShape.MAX_COLUMN_DIAMETER : EquipmentShape.MAX_DIAMETER); d += .5) {
                double size = d;
                int least = EquipmentShape.minimumSkirt(d);
                assertThrows(IllegalArgumentException.class, () -> {
                        if (column) EquipmentShape.column(size, ColumnTrays.simple(12, 6), Heading.NORTH, least - 1);
                        else drum(size, 4, Orientation.VERTICAL, Heading.NORTH, least - 1);
                        }, d + " on " + (least - 1));
                for (int s = least; s <= EquipmentShape.MAX_SUPPORT; s++) {
                    var shape = column ? EquipmentShape.column(d, ColumnTrays.simple(12, 6), Heading.NORTH, s) : drum(d, 4, Orientation.VERTICAL, Heading.NORTH, s);
                    var line = shape.nozzles().stream().filter(n -> n.riser() > 0).findFirst().orElseThrow(() -> new AssertionError(shape + " has no bottom line"));
                    double c = line.base()[1], pole = shape.bottomTangent() - shape.headDepth();
                    String at = shape + " line at " + c;
                    assertEquals(EquipmentShape.SKIRT_OUTLET, c, 0, at);
                    assertEquals(c, shape.openingHeight(), 0, at);
                    assertEquals(port, shape.openingRadius(), 1e-12, at);
                    assertTrue(c - PipeSizes.flange(max) / 2 >= -1e-12, at);
                    assertTrue(c - port >= EquipmentShape.PLATE, at);
                    assertTrue(port >= max / 2 + .03, at);
                    assertTrue(c + port + EquipmentShape.PORT_COLLAR <= shape.bottomTangent() - .1, at);
                    assertTrue(c + max + EquipmentShape.ELBOW_CLEARANCE <= pole + 1e-9, at);
                    assertTrue(line.riser() > c + max, at);
                    assertEquals(shape.axis(), line.base()[2] + max, 1e-12, at);
                    assertTrue(line.length() >= PipeSizes.flangeThickness(max) + PipeSizes.hub(max), at);
                }
            }
    }

    @Test
    void skirtHoleTakesTheLinesSize() {
        // Owner decision 2026-10-21: "Skirt hole needs to adapt to the size of the pipe to max 1m". At every line size the
        // hole in the skirt plate is the line's outside and the port's clearance: sight lines along the line inside it
        // cross no plate, and sight lines just outside the hole's collar cross the plate's two faces. Lines up to the
        // skirt's own radius, which turn up inside it.
        for (var shape : List.of(drum(.5, 4, Orientation.VERTICAL, Heading.NORTH, EquipmentShape.minimumSkirt(.5)), drum(2, 4, Orientation.VERTICAL, Heading.EAST, 4),
                drum(3, 4, Orientation.VERTICAL, Heading.SOUTH, 4), drum(6, 10, Orientation.VERTICAL, Heading.WEST, 5), EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.NORTH, 4))) {
            int k = 0;
            while (shape.nozzles().get(k).riser() <= 0) k++;
            double a = shape.axis(), c = EquipmentShape.SKIRT_OUTLET, inner = shape.radius() * DeterministicMath.cos(Math.PI / shape.facets()) - EquipmentShape.SKIRT_WALL;
            double outside = a - shape.radius() - .07;
            int checked = 0;
            for (double wanted : new double[]{.1, .3, .6, PipeSizes.MAX_OUTSIDE}) {
                double od = PipeSizes.outsideOf(PipeSizes.code(wanted)), r = od / 2;
                if (od > inner - .02) continue;
                checked++;
                var sizes = new double[shape.nozzles().size()];
                sizes[k] = od;
                String at = shape + " line " + od;
                double from = a - od - .02;
                assertEquals(0, crossings(shape, new double[]{a, c, from}, new double[]{a, c, outside}, sizes), at + " centre");
                // Inside the line from in front of the elbow, which turns up at the axis; just outside the collar from just
                // inside the plate's inner face there (the elbow, the line's width, lies nearer the middle), where the plate
                // stands (a line near a pipe's largest on the lowest row has its collar over the base ring), to just past its
                // outer face (an anchor chair may stand beside the collar).
                double rim = EquipmentShape.portRadius(od) + EquipmentShape.PORT_COLLAR + .02;
                for (int j = 0; j < 16; j++) {
                    double t = 2 * Math.PI * j / 16, x = Math.cos(t), y = Math.sin(t);
                    assertEquals(0, crossings(shape, new double[]{a + .95 * r * x, c + .95 * r * y, from}, new double[]{a + .95 * r * x, c + .95 * r * y, outside}, sizes),
                            at + " inside at " + j);
                    // Just outside the collar, where the skirt's front faces stand, the plate's two faces.
                    double behind = a - Math.sqrt(inner * inner - rim * x * rim * x) + .02, past = a - Math.sqrt(shape.radius() * shape.radius() - rim * x * rim * x) - .02;
                    if (Math.abs(rim * x) < .5 * shape.radius() && c + rim * y > EquipmentShape.PLATE + .01)
                        assertEquals(2, crossings(shape, new double[]{a + rim * x, c + rim * y, behind}, new double[]{a + rim * x, c + rim * y, past}, sizes),
                                at + " collar at " + j);
                }
            }
            // A 0.5 m skirt (inside radius 0.18 m) takes only the smallest line.
            assertTrue(checked >= (shape.diameter() < 1 ? 1 : 2), shape.toString());
        }
    }

    @Test
    void skirtShowsItsInsideThroughTheOpeningAndSlots() {
        // Owner reports 2026-10-16: the bottom outlet leaves through its skirt port on the front, centred on the outlet
        // line, the port 5 cm wider than the line drawn, and slots on the left, back and right show the skirt's inside.
        // Sight lines (frame points) between the default line (r 0.15 m) and its port (r 0.2 m), and through each slot's
        // middle, cross nothing; one between the slots meets both faces of the plate.
        double between = Math.toRadians(302.5);
        for (var heading : Heading.values()) {
            var standing = drum(3, 4, Orientation.VERTICAL, heading, 4);
            double a = standing.axis(), out = standing.radius() + .05, slot = 1.1, port = EquipmentShape.SKIRT_OUTLET;
            assertEquals(EquipmentShape.SKIRT_OUTLET, standing.openingHeight(), 0);
            assertTrue(standing.openingRadius() > .25, heading.toString());
            assertEquals(0, crossings(standing, new double[]{a + .175, port, a}, new double[]{a + .175, port, a - out}), heading + " port");
            for (double[] d : new double[][]{{1, 0}, {0, 1}, {-1, 0}})
                assertEquals(0, crossings(standing, new double[]{a + d[0], slot, a + d[1]}, new double[]{a + out * d[0], slot, a + out * d[1]}),
                        heading + " slot " + d[0] + "," + d[1]);
            double c = Math.cos(between), s = Math.sin(between);
            assertEquals(2, crossings(standing, new double[]{a + c, slot, a + s}, new double[]{a + out * c, slot, a + out * s}), heading + " plate");
            // The outlet line inside the skirt is drawn: its cells are shell cells.
            int k = 0;
            while (standing.nozzles().get(k).role() != EquipmentShape.Role.LIQUID_OUTLET) k++;
            assertTrue(ShapeRasterizer.layout(standing).shell().containsAll(ShapeRasterizer.nozzleCells(standing, k)), heading.toString());
            // Owner request 2026-10-16: a column's bottoms leave through its skirt's port on the front, like the drum's
            // outlet, and a sight line between the default line and the port crosses nothing.
            var column = EquipmentShape.column(3, ColumnTrays.simple(12, 6), heading, 4);
            var bottoms = column.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.BOTTOMS).findFirst().orElseThrow();
            assertEquals(EquipmentShape.SKIRT_OUTLET, bottoms.base()[1], 0, heading.toString());
            assertEquals(2, bottoms.axis());
            assertEquals(-1, bottoms.sign());
            assertTrue(column.openingRadius() > bottoms.defaultOd() / 2, heading.toString());
            double c0 = column.axis(), wall = column.radius() + .05, gap = bottoms.defaultOd() / 2 + EquipmentShape.PORT_CLEARANCE / 2;
            assertEquals(0, crossings(column, new double[]{c0 + gap, port, c0}, new double[]{c0 + gap, port, c0 - wall}), heading + " column port");
            assertTrue(openingCentres(column).isEmpty(), heading + " the column's access opening gave way to its port");
        }
        // A port only where a bottom outlet line leaves a skirt: none on a bare or lying drum; at its largest a pipe's
        // largest and its clearance, on a small drum too (owner decision 2026-10-21: the hole takes the pipe's size).
        assertEquals(0, new EquipmentShape(3, 4).openingRadius());
        assertEquals(0, drum(3, 4, Orientation.HORIZONTAL, Heading.NORTH, 2).openingRadius());
        assertEquals(EquipmentShape.portRadius(PipeSizes.MAX_OUTSIDE), drum(.5, 4, Orientation.VERTICAL, Heading.NORTH, EquipmentShape.minimumSkirt(.5)).openingRadius(), 1e-12);
    }

    @Test
    void aTurnedBottomOutletTurnsItsPortAndSlots() {
        // Owner request 2026-10-25 ("make drum/reactor bottom outlet rotatable"): a skirted drum's bottom outlet turns a
        // quarter at a time from the front. Its line, its port and its collar face that side, and its face sits on a block
        // face there; sight lines inside the line at 0.3 m and at a pipe's largest cross no plate, and just outside its
        // collar the plate's two faces; the slots keep to the other three sides (the chairs, in fours between them, map onto
        // themselves); the default connections meet none of it.
        for (int q = 0; q < EquipmentShape.CONNECTION_SIDES; q++)
            for (var heading : List.of(Heading.NORTH, Heading.EAST)) {
                var standing = drum(3, 4, Orientation.VERTICAL, heading, 4, EquipmentShape.defaultConnections(3, 4, Orientation.VERTICAL, 4, false, q), false, q);
                String at = standing + " turned " + q;
                assertTrue(standing.outletTurns(), at);
                assertEquals(List.of(), standing.clashes(), at);
                int[] side = EquipmentShape.side(q);
                int k = 0;
                while (standing.nozzles().get(k).role() != EquipmentShape.Role.LIQUID_OUTLET) k++;
                var line = standing.nozzles().get(k);
                assertEquals(side[0], line.axis(), at);
                assertEquals(side[1], line.sign(), at);
                assertEquals(Math.rint(line.face()[side[0]]), line.face()[side[0]], 1e-9, at);
                double[] out = new double[3];
                out[side[0]] = side[1];
                var world = standing.transform().direction(out);
                final int nozzle = k;
                var port = ShapeRasterizer.ports(standing).stream().filter(p -> p.nozzle() == nozzle).findFirst().orElseThrow();
                assertArrayEquals(new int[]{(int) Math.rint(world[0]), (int) Math.rint(world[1]), (int) Math.rint(world[2])}, new int[]{port.dx(), port.dy(), port.dz()}, at);
                double c = EquipmentShape.SKIRT_OUTLET, r0 = standing.radius(), inner = r0 * DeterministicMath.cos(Math.PI / standing.facets()) - EquipmentShape.SKIRT_WALL;
                for (double wanted : new double[]{.3, PipeSizes.MAX_OUTSIDE}) {
                    double od = PipeSizes.outsideOf(PipeSizes.code(wanted)), r = od / 2, rim = EquipmentShape.portRadius(od) + EquipmentShape.PORT_COLLAR + .02;
                    var sizes = new double[standing.nozzles().size()];
                    sizes[k] = od;
                    for (int j = 0; j < 16; j++) {
                        double t = 2 * Math.PI * j / 16, x = Math.cos(t), y = Math.sin(t);
                        assertEquals(0, crossings(standing, toward(standing, q, od + .02, .95 * r * x, c + .95 * r * y), toward(standing, q, r0 + .07, .95 * r * x, c + .95 * r * y), sizes),
                                at + " line " + od + " inside at " + j);
                        if (Math.abs(rim * x) < .5 * r0 && c + rim * y > EquipmentShape.PLATE + .01)
                            assertEquals(2, crossings(standing, toward(standing, q, Math.sqrt(inner * inner - rim * x * rim * x) - .02, rim * x, c + rim * y),
                                    toward(standing, q, Math.sqrt(r0 * r0 - rim * x * rim * x) + .02, rim * x, c + rim * y), sizes), at + " line " + od + " collar at " + j);
                    }
                }
                // Through each slot's middle nothing; on the port's side above the port, the plate's two faces.
                for (int other = 0; other < EquipmentShape.CONNECTION_SIDES; other++)
                    assertEquals(other == q ? 2 : 0, crossings(standing, toward(standing, other, 1, 0, 1.1), toward(standing, other, r0 + .05, 0, 1.1)), at + " side " + other);
            }
        // Every skirted drum at every turn: its defaults meet nothing.
        for (double d = EquipmentShape.MIN_DIAMETER; d <= EquipmentShape.MAX_DIAMETER; d += .5)
            for (int length : new int[]{1, 2, 4, 12})
                for (int q = 0; q < EquipmentShape.CONNECTION_SIDES; q++) {
                    int skirt = EquipmentShape.minimumSkirt(d);
                    var drum = drum(d, length, Orientation.VERTICAL, Heading.NORTH, skirt, EquipmentShape.defaultConnections(d, length, Orientation.VERTICAL, skirt, false, q), false, q);
                    assertEquals(List.of(), drum.clashes(), drum + " turned " + q);
                }
        // Only a skirt's outlet turns: not a bare or lying drum's, nor a column's (the owner leaves columns alone), nor past
        // three quarter turns.
        var trays = ColumnTrays.simple(12, 6);
        for (Runnable bad : List.<Runnable>of(() -> drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4, List.of(), false, 4),
                () -> drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4, List.of(), false, -1), () -> drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 0, List.of(), false, 1),
                () -> drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2, List.of(), false, 2),
                () -> new EquipmentShape(Kind.COLUMN, 3, trays.tangentLength(3), Orientation.VERTICAL, Heading.NORTH, 4, trays, List.of(), ProcessUnit.Bank.ONE, false, 1)))
            assertThrows(IllegalArgumentException.class, bad::run);
        assertFalse(EquipmentShape.column(3, trays, Heading.NORTH, 4).outletTurns());
        assertFalse(drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2).outletTurns());
        assertFalse(new EquipmentShape(3, 4).outletTurns());
    }

    /** A frame point {@code along} out from a standing vessel's axis towards {@link EquipmentShape#SIDES}[{@code side}], {@code across} beside that line, at height {@code y}. */
    private static double[] toward(EquipmentShape shape, int side, double along, double across, double y) {
        int[] s = EquipmentShape.side(side);
        var p = new double[]{shape.axis(), y, shape.axis()};
        p[s[0]] += s[1] * along;
        p[2 - s[0]] += across;
        return p;
    }

    @Test
    void sideConnectionsAreValidatedAndClash() {
        // A new drum's side connection is its former inlet: 0.4 D below the top tangent line, facing frame -x.
        assertEquals(List.of(new Connection(3, 6, 0)), drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4).connections());
        var placed = List.of(new Connection(3, 6, 0), new Connection(1, 6, 0), new Connection(2, 6, 1));
        assertEquals(placed, Connection.decode(Connection.encode(placed)));
        assertThrows(IllegalArgumentException.class, () -> Connection.decode(new int[]{0, 3}));
        assertThrows(IllegalArgumentException.class, () -> Connection.decode(new int[3 * (EquipmentShape.MAX_CONNECTIONS + 1)]));
        assertThrows(IllegalArgumentException.class, () -> new Connection(EquipmentShape.CONNECTION_SIDES, 3, 0));
        // D 3 m on a 4 m skirt, T/T 4 m: centre lines 0.1 m inside the tangent lines at 4 and 8 m, offsets within 0.85 R.
        assertArrayEquals(new int[]{4, 7}, EquipmentShape.stations(3, 4, Orientation.VERTICAL, 4));
        assertEquals(1, EquipmentShape.maximumOffset(3));
        for (var bad : List.of(new Connection(0, 3, 0), new Connection(0, 8, 0), new Connection(0, 5, 2)))
            assertThrows(IllegalArgumentException.class, () -> drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4, List.of(bad)), bad.toString());
        assertThrows(IllegalArgumentException.class, () -> drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4,
                java.util.Collections.nCopies(EquipmentShape.MAX_CONNECTIONS + 1, new Connection(0, 5, 0))));
        var trays = ColumnTrays.simple(12, 6);
        assertThrows(IllegalArgumentException.class, () -> new EquipmentShape(EquipmentShape.Kind.COLUMN, 3, trays.tangentLength(3), Orientation.VERTICAL,
                Heading.NORTH, 4, trays, List.of(new Connection(0, 5, 0)), ProcessUnit.Bank.ONE, false, 0));
        // Right, left and a hillside nozzle at the back over the manway: clear of each other, of the outlets and of the
        // manway, the hillside one off centre.
        var drum = drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4, placed);
        assertEquals(List.of(), drum.clashes());
        var nozzles = drum.nozzles();
        assertEquals(5, nozzles.size());
        assertEquals(5, ShapeRasterizer.ports(drum).size());
        var hillside = nozzles.get(4);
        assertEquals(EquipmentShape.Role.SIDE, hillside.role());
        assertEquals(drum.axis() + 1, hillside.base()[0], 0);
        assertEquals(6.5, hillside.base()[1], 0);
        // Two on one place; two on neighbouring rows, their pipes touching (owner rule 2026-10-17); one beside the bare
        // drum's liquid outlet; one on a saddle; one facing a ground too near.
        assertEquals(List.of(new Clash(1, 0)), drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4, List.of(new Connection(1, 5, 0), new Connection(1, 5, 0))).clashes());
        assertEquals(List.of(new Clash(1, 0)), drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4, List.of(new Connection(1, 5, 0), new Connection(1, 6, 0))).clashes());
        assertEquals(List.of(), drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4, List.of(new Connection(1, 5, 0), new Connection(1, 7, 0))).clashes());
        assertEquals(List.of(new Clash(0, Clash.LIQUID)), drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 0, List.of(new Connection(0, 1, 0))).clashes());
        assertTrue(drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2, List.of(new Connection(3, 2, 0))).clashes().contains(new Clash(0, Clash.SADDLE)));
        assertEquals(List.of(new Clash(0, Clash.GROUND)), drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 1, List.of(new Connection(3, 5, 0))).clashes());
        assertEquals(List.of(), drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2, List.of(new Connection(3, 5, 0))).clashes());
    }

    @Test
    void connectionsFitEveryDrumDesign() {
        // Owner report 2026-10-16: the default lying drum's connection met a saddle. Every new drum's connection meets
        // nothing, and a connection put where a saddle stands moves to the nearest free station on its own side.
        for (var orientation : List.of(Orientation.VERTICAL, Orientation.HORIZONTAL))
            for (double d = .5; d <= EquipmentShape.MAX_DIAMETER; d += .5)
                for (int length = 1; length <= 24; length += length < 8 ? 1 : 4)
                    for (int support : orientation.horizontal() ? new int[]{1, 2, 3} : new int[]{0, EquipmentShape.minimumSkirt(d), EquipmentShape.defaultSupport(d, orientation)}) {
                        var drum = drum(d, length, orientation, Heading.NORTH, support);
                        assertEquals(List.of(), drum.clashes(), drum.toString());
                    }
        var onSaddle = List.of(new Connection(3, 2, 0));
        assertTrue(drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2, onSaddle).clashes().contains(new Clash(0, Clash.SADDLE)));
        var fitted = EquipmentShape.fit(onSaddle, drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2));
        assertEquals(List.of(), drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2, fitted).clashes());
        assertEquals(3, fitted.getFirst().side());
        // A connection that meets nothing stays where it is; the standing drum's own default fits as it is.
        var clear = List.of(new Connection(3, 6, 0), new Connection(1, 6, 0), new Connection(2, 6, 1));
        assertEquals(clear, EquipmentShape.fit(clear, drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4)));
        // One put on the standing drum's manway moves up its own side, clear of the cover and the davit.
        assertEquals(List.of(new Connection(2, 6, 0)), EquipmentShape.fit(List.of(new Connection(2, 4, 1)), drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4)));
    }

    @Test
    void bootsAndManwaysStandClear() {
        // Owner request 2026-10-16 (photo survey A, D): a boot hangs inboard of the front saddle and 0.05 m clear of it,
        // its drain facing down with a block for its pipe above the ground; every drum from 0.9 m carries a manway; the
        // default connections meet neither.
        int boots = 0;
        for (var orientation : List.of(Orientation.VERTICAL, Orientation.HORIZONTAL))
            for (double d = .5; d <= EquipmentShape.MAX_DIAMETER; d += .5)
                for (int length = 1; length <= 24; length += length < 8 ? 1 : 4) {
                    var plain = drum(d, length, orientation, Heading.NORTH, EquipmentShape.defaultSupport(d, orientation));
                    assertEquals(d >= .9 && (orientation.horizontal() || length >= 2), plain.manway() != null, plain.toString());
                    var boot = EquipmentShape.bootFor(d, length);
                    if (!orientation.horizontal() || boot == null) continue;
                    boots++;
                    int support = EquipmentShape.minimumBootSupport(d, length);
                    var drum = drum(d, length, orientation, Heading.NORTH, support, EquipmentShape.defaultConnections(d, length, orientation, support, true), true);
                    assertEquals(List.of(), drum.clashes(), drum.toString());
                    for (double saddle : drum.saddleCentres())
                        assertTrue(Math.abs(boot.station() - saddle) >= EquipmentShape.SADDLE_WIDTH / 2 + .05 + boot.diameter() / 2 - 1e-9, drum.toString());
                    assertEquals(1, drum.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.BOOT_DRAIN).count(), drum.toString());
                    // Its drain's face a unit's neck beyond where a pipe's largest leaves the boot's head, its root 0.03 m
                    // inside it (owner decision 2026-10-21: a 0.8 m pipe has a 0.8 m root).
                    var drain = drum.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.BOOT_DRAIN).findFirst().orElseThrow();
                    assertTrue(drain.length() >= ProcessUnit.NECK + .03 - 1e-9, drum + " drain neck " + drain.length());
                    if (support > 1) assertThrows(IllegalArgumentException.class, () -> drum(drum.diameter(), drum.tangentLength(), orientation, Heading.NORTH, support - 1, List.of(), true));
                }
        assertTrue(boots > 50, "boots checked: " + boots);
        assertThrows(IllegalArgumentException.class, () -> drum(3, 9, Orientation.VERTICAL, Heading.NORTH, 2, List.of(), true));
        assertNull(EquipmentShape.bootFor(3, 1));
        // The boot, its drain's port facing down, and the manways' covers are solid.
        var lying = drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, EquipmentShape.minimumBootSupport(3, 9), List.of(), true);
        var boot = lying.bootPlace();
        assertTrue(in(lying, lying.axis() - 1.5 - boot.length() / 2, boot.station(), lying.axis()));
        int drain = 0;
        while (lying.nozzles().get(drain).role() != EquipmentShape.Role.BOOT_DRAIN) drain++;
        final int k = drain;
        assertTrue(ShapeRasterizer.ports(lying).stream().anyMatch(p -> p.nozzle() == k && p.dy() == -1 && p.port() == EquipmentShape.PortClass.LIQUID));
        for (var shape : List.of(lying, drum(3, 4, Orientation.VERTICAL, Heading.NORTH, 4), drum(1, 3, Orientation.VERTICAL, Heading.EAST, 3))) {
            var m = shape.manway();
            var p = m.at(m.face() + .03, 0, 0);
            assertTrue(in(shape, p[0], p[1], p[2]), "manway cover of " + shape);
        }
    }

    @Test
    void aBootDrumsOilLeavesBetweenItsBootAndTheFrontHead() {
        // Owner decision 2026-10-25 (a three-phase drum: Arnold and Stewart's separator, a reflux drum's general
        // arrangement): the outlets leave at the front end, the feed enters at the far end. The boot hangs inboard of the
        // front saddle; the liquid outlet faces down from the bottom centre line beyond that saddle, on the shell or on the
        // head's bottom within 0.6 of its depth from the tangent line, its root inside the drum, its face on a block face
        // under the shell with a block for its pipe above the ground. Its largest flange (0.8 m, at most D / 2) stands
        // 0.15 m clear of both saddles; the boot stands whole cells behind it, at least two (one free block between their
        // pipes), its shell clear of that flange. The vapour outlet stands on top nearer the front head than the default
        // inlet. Owner request 2026-10-25: every drum from 6 m long takes a boot; where the outlet has no room beyond the
        // front saddle it stays low on the front head (a 0.5 m drum has room on neither). The boot's and the outlet's pipes
        // drop through block columns clear of the saddles' blocks. A drum whose saddles cannot keep the boot clear takes none.
        double saddle = EquipmentShape.SADDLE_WIDTH / 2 + .15;
        int boots = 0;
        for (double d = EquipmentShape.MIN_DIAMETER; d <= EquipmentShape.MAX_DIAMETER; d += .5)
            for (int length = 1; length <= 40; length++) {
                var b = EquipmentShape.bootFor(d, length);
                if (length >= 6 && d >= 1) assertNotNull(b, d + " x " + length);
                double size = d, flange = PipeSizes.flange(Math.min(PipeSizes.MAX_OUTSIDE, d / 2)) / 2;
                int tangent = length;
                if (b == null) {
                    assertThrows(IllegalArgumentException.class, () -> drum(size, tangent, Orientation.HORIZONTAL, Heading.NORTH, EquipmentShape.MAX_SUPPORT, List.of(), true),
                            d + " x " + length);
                    continue;
                }
                boots++;
                int support = EquipmentShape.minimumBootSupport(d, length);
                var drum = drum(d, length, Orientation.HORIZONTAL, Heading.NORTH, support, EquipmentShape.defaultConnections(d, length, Orientation.HORIZONTAL, support, true), true);
                String at = drum + " boot " + b.station() + " outlet " + b.outlet();
                assertEquals(List.of(), drum.clashes(), at);
                var outlet = drum.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.LIQUID_OUTLET).findFirst().orElseThrow();
                assertEquals(1, drum.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.LIQUID_OUTLET).count(), at);
                if (Double.isNaN(b.outlet())) {
                    assertArrayEquals(new double[]{1, -1}, new double[]{outlet.axis(), outlet.sign()}, 0, at + " on the front head");
                    continue;
                }
                assertArrayEquals(new double[]{0, -1, b.outlet(), drum.axis()}, new double[]{outlet.axis(), outlet.sign(), outlet.base()[1], outlet.base()[2]}, 0, at);
                double face = outlet.face()[0];
                assertEquals(Math.rint(face), face, 1e-9, at);
                assertTrue(face <= drum.axis() - drum.radius() - EquipmentShape.STAND_OUT + 1e-9 && face - 1 >= -support - 1e-9, at + " face at " + face);
                assertTrue(drum.bottomTangent() - b.outlet() <= .6 * drum.headDepth() + 1e-9 && outlet.base()[0] > drum.axis() - drum.radius(), at);
                // Its depth within a block of the preferred, less up to 0.07 m for its drain's neck.
                double preferred = Math.max(.9, .6 * d);
                assertTrue(b.length() >= .4 - 1e-9 && b.length() < preferred && b.length() > preferred - 1.07 - 1e-5, at + " boot depth " + b.length());
                double gap = b.station() - b.outlet();
                assertEquals(Math.rint(gap), gap, 1e-9, at);
                assertTrue(gap >= 2 && gap >= b.diameter() / 2 + flange - 1e-9, at);
                double[] saddles = drum.saddleCentres();
                for (double c : saddles)
                    for (double pipe : new double[]{b.station(), b.outlet()}) {
                        int column = (int) Math.floor(pipe);
                        assertTrue(column < Math.floor(c - EquipmentShape.SADDLE_WIDTH / 2) || column > Math.floor(c + EquipmentShape.SADDLE_WIDTH / 2 - 1e-9), at + " pipe at " + pipe + " over the saddle at " + c);
                    }
                double front = Math.min(saddles[0], saddles[1]);
                assertTrue(b.outlet() < front && front < b.station(), at);
                for (double c : saddles) assertTrue(Math.abs(b.outlet() - c) >= saddle + flange - 1e-9, at + " saddle at " + c);
                var vapour = drum.nozzles().stream().filter(n -> n.role() == EquipmentShape.Role.VAPOUR_OUTLET).findFirst().orElseThrow();
                for (var c : drum.connections()) assertTrue(vapour.base()[1] < c.station() + .5, at + " connection at " + c.station());
            }
        assertTrue(boots > 700, boots + " boots");
        // Owner decision 2026-10-25 (a reflux drum's drawing: saddles 500 mm inside the tangent lines of a 4000 mm drum, the
        // boot leaning to the outlet end, its centre about a quarter of T/T from the tangent line): the 3 x 9 drum's boot
        // hangs 2.75 m from its front tangent line, its oil outlet on its head's bottom beyond the saddle at 1.875 m; the
        // 2 x 7 drum's 2 m (0.29 T/T), its outlet on the tangent line; the 2 x 6 drum's outlet stays on the front head; a
        // 3 x 2 drum has no room for a boot between its saddles.
        assertArrayEquals(new double[]{3.5, .5}, new double[]{EquipmentShape.bootFor(3, 9).station(), EquipmentShape.bootFor(3, 9).outlet()}, 1e-12);
        assertArrayEquals(new double[]{2.5, .5}, new double[]{EquipmentShape.bootFor(2, 7).station(), EquipmentShape.bootFor(2, 7).outlet()}, 1e-12);
        assertEquals(2.5, EquipmentShape.bootFor(2, 6).station(), 1e-12);
        assertTrue(Double.isNaN(EquipmentShape.bootFor(2, 6).outlet()));
        assertNull(EquipmentShape.bootFor(3, 2));
        // Owner request 2026-10-25: a boot stops short of the block line its preferred depth (0.9 m or 0.6 D) would reach,
        // so its drum stands a block lower: 3 x 9 on 3 m saddles (were 4), 2 x 6 on 2 m (were 3).
        assertArrayEquals(new int[]{3, 2}, new int[]{EquipmentShape.minimumBootSupport(3, 9), EquipmentShape.minimumBootSupport(2, 6)});
    }

    @Test
    void outlineFollowsTheViewer() {
        // Owner report 2026-10-16: a fixed meridian down the drum's front cut it in half. The silhouette's two meridians
        // stand where the sight lines touch the shell, within a facet of φ ± acos(R / d), and turn with the viewer.
        var standing = drum(3, 4, Orientation.VERTICAL, Heading.EAST, 4);
        double a = standing.axis(), facet = 360.0 / standing.facets();
        for (double[] eye : new double[][]{{0, -20}, {20, 0}, {-14, 14}}) {
            double d = Math.hypot(eye[0], eye[1]), towards = Math.toDegrees(Math.atan2(eye[1], eye[0])), spread = Math.toDegrees(Math.acos(standing.outerRadius() / d));
            var p = standing.transform().point(new double[]{a + eye[0], 3, a + eye[1]});
            var meridians = new java.util.ArrayList<Double>();
            for (var s : ShapeRasterizer.outline(standing, p[0], p[1], p[2]))
                if (Math.abs(s[4] - s[1]) >= standing.tangentLength()) {
                    var f = standing.transform().local(new double[]{s[0], s[1], s[2]});
                    meridians.add(Math.toDegrees(Math.atan2(f[2] - a, f[0] - a)));
                }
            assertEquals(2, meridians.size(), List.of(eye[0], eye[1]).toString());
            for (double side : new double[]{-1, 1}) {
                double expected = towards + side * spread;
                assertTrue(meridians.stream().anyMatch(m -> Math.abs(Math.IEEEremainder(m - expected, 360)) <= facet / 2 + 1e-9), meridians + " for " + expected);
            }
        }
    }

    @Test
    void supportsCarryTheVessel() {
        // A skirt continues the shell to the ground on a base ring 1/8 m wider; a bare vessel touches it only at its lower apex.
        var skirted = drum(6, 10, Orientation.VERTICAL, Heading.NORTH, 5);
        double a = skirted.axis();
        assertEquals(7, skirted.width());
        assertEquals(5 + 10 + 1.5, skirted.topElevation(), 0);
        assertEquals(12, skirted.anchorChairs());
        // A drum's skirt is drawn hollow but stays solid to collision, so nothing spawns or is placed inside it.
        assertTrue(in(skirted, a - 2.8, .5, a));
        assertFalse(in(skirted, a - 3.05, .5, a));
        assertTrue(in(skirted, a + 3.1, .03, a));
        assertFalse(in(skirted, a + 3.2, .03, a));
        var bare = new EquipmentShape(6, 10);
        assertFalse(in(bare, bare.axis() + 2.8, .5, bare.axis()));
        // Saddles an eighth of T/T in from each tangent line (owner decision 2026-10-25), under the axis; nothing under the
        // vessel between them.
        for (var lying : List.of(drum(3, 9, Orientation.HORIZONTAL, Heading.NORTH, 2), drum(4, 12, Orientation.HORIZONTAL, Heading.EAST, 1))) {
            double bottom = lying.bottomTangent(), length = lying.tangentLength(), under = -lying.support() / 2.0;
            assertEquals(lying.support() + lying.width() / 2.0 + lying.radius(), lying.topElevation(), 0);
            double at = EquipmentShape.SADDLE_POSITION * length;
            for (double along : new double[]{bottom + at, bottom + length - at, bottom + .5 * length}) {
                boolean saddle = along != bottom + .5 * length;
                assertEquals(saddle, in(lying, under, along, lying.axis()), lying + " at " + along);
            }
        }
    }

    @Test
    void datasheetVolumeAndAreaBoundTheFacetedVessel() {
        // D 2 m, T/T 3 m: π·1·3 + π·8/12 = 11π/3 m³; 6π + 8 × 1.08398 m².
        var drum = new EquipmentShape(2, 3);
        assertEquals(11 * Math.PI / 3, drum.volume(), 1e-12);
        assertEquals(1.08398, EquipmentShape.HEAD_AREA_FACTOR, 1e-5);
        assertEquals(6 * Math.PI + 8 * EquipmentShape.HEAD_AREA_FACTOR, drum.surfaceArea(), 1e-12);
        assertEquals(4, drum.overallLength(), 0);
        assertEquals(4, drum.topElevation(), 0);
        // The liquid head stands over the apex-to-apex height, or a lying drum's diameter.
        assertEquals(4, drum.levelHeight(), 0);
        assertEquals(2, drum(2, 3, Orientation.HORIZONTAL, Heading.NORTH, 1).levelHeight(), 0);
        // The faceted vessel is inscribed in the smooth one: smaller, and closer for finer facets. Supports are not vessel.
        for (var shape : SHAPES) {
            if (shape.designed()) continue;
            double volume = vesselVolume(shape), area = vesselArea(shape);
            assertTrue(volume < shape.volume() && volume > .88 * shape.volume(), shape + " volume " + volume + " of " + shape.volume());
            assertTrue(area < shape.surfaceArea() && area > .9 * shape.surfaceArea(), shape + " area " + area + " of " + shape.surfaceArea());
        }
    }

    // ---- Storage vessels (owner request 2026-10-21, batch 2026-10-21-floating-roof-tanks, plan sections 3.3 and 3.5) ---

    /** Every floating-roof tank (D 4 to 40 m by 1 m, shell 4 to 24 m) and every sphere (D 4 to 24 m by 1 m, 2 to 8 m under it). */
    static List<EquipmentShape> everyStorageSize() {
        var out = new java.util.ArrayList<EquipmentShape>();
        for (int d = 4; d <= 40; d++) for (int shell = 4; shell <= 24; shell++) out.add(EquipmentShape.storage(Kind.TANK, d, shell, Heading.NORTH, 0));
        for (int d = 4; d <= 24; d++) for (int s = 2; s <= 8; s++) out.add(EquipmentShape.storage(Kind.SPHERE, d, 1, Heading.NORTH, s));
        return out;
    }

    @Test
    void storageGridsRefuseWhatThePlanLeavesOut() {
        for (double[] bad : new double[][]{{3, 8}, {41, 8}, {12.5, 8}, {12, 3}, {12, 25}, {Double.NaN, 8}})
            assertThrows(IllegalArgumentException.class, () -> EquipmentShape.storage(Kind.TANK, bad[0], bad[1], Heading.NORTH, 0), "tank " + bad[0] + " x " + bad[1]);
        assertThrows(IllegalArgumentException.class, () -> EquipmentShape.storage(Kind.TANK, 12, 8, Heading.NORTH, 1), "a tank stands at grade");
        for (double[] bad : new double[][]{{3, 2}, {25, 2}, {8.5, 2}, {8, 1}, {8, 9}})
            assertThrows(IllegalArgumentException.class, () -> EquipmentShape.storage(Kind.SPHERE, bad[0], 1, Heading.NORTH, (int) bad[1]), "sphere " + bad[0] + " on " + bad[1]);
        assertThrows(IllegalArgumentException.class, () -> EquipmentShape.storage(Kind.SPHERE, 8, 2, Heading.NORTH, 2), "a sphere has no second size");
        assertThrows(IllegalArgumentException.class, () -> EquipmentShape.storage(Kind.DRUM, 2, 3, Heading.NORTH, 0));
        // Neither takes side connections or trays: their ports are fixed.
        assertThrows(IllegalArgumentException.class, () -> new EquipmentShape(Kind.TANK, 12, 8, Orientation.VERTICAL, Heading.NORTH, 0, null,
                List.of(new Connection(0, 1, 0)), ProcessUnit.Bank.ONE, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new EquipmentShape(Kind.SPHERE, 8, 1, Orientation.VERTICAL, Heading.NORTH, 2, ColumnTrays.simple(12, 6),
                List.of(), ProcessUnit.Bank.ONE, false, 0));
        assertEquals(37 * 21 + 21 * 7, everyStorageSize().size());
    }

    @Test
    void storageHoldsWhatThePlanSays() {
        // A tank holds liquid up to its fill height, the shell less the roof's 1 m; a sphere as a drum, its head over its diameter.
        var tank = EquipmentShape.storage(Kind.TANK, 20, 12, Heading.NORTH, 0);
        assertEquals(11, tank.fillHeight(), 0);
        assertEquals(Math.PI * 100 * 11, tank.volume(), 1e-9);
        assertEquals(11, tank.levelHeight(), 0);
        assertEquals(37.7, EquipmentShape.storage(Kind.TANK, 4, 4, Heading.NORTH, 0).volume(), .05);
        assertEquals(28_903, EquipmentShape.storage(Kind.TANK, 40, 24, Heading.NORTH, 0).volume(), 1);
        var sphere = EquipmentShape.storage(Kind.SPHERE, 14, 1, Heading.NORTH, 2);
        assertEquals(Math.PI * 14 * 14 * 14 / 6, sphere.volume(), 1e-9);
        assertEquals(14, sphere.levelHeight(), 0);
        assertEquals(33.5, EquipmentShape.storage(Kind.SPHERE, 4, 1, Heading.NORTH, 2).volume(), .05);
        assertEquals(7238.2, EquipmentShape.storage(Kind.SPHERE, 24, 1, Heading.NORTH, 2).volume(), .05);
        // Its interior cells fill its inside but the cells its shell passes through.
        for (var shape : List.of(tank, sphere)) {
            double inside = shape.kind() == Kind.TANK ? Math.PI * 100 * 12 : shape.volume(), cells = ShapeRasterizer.layout(shape).interior().size();
            assertTrue(cells < inside && cells > .65 * inside, shape + ": " + cells + " interior cells for " + inside + " m3 inside");
        }
    }

    @Test
    void storagePortsStandWhereThePlanPutsThem() {
        for (var heading : Heading.values()) for (var dimensions : new int[][]{{4, 4}, {20, 12}}) {
            // A tank: centered front/back bottom nozzles on the lowest row; front overflow above the fill height.
            var tank = EquipmentShape.storage(Kind.TANK, dimensions[0], dimensions[1], heading, 0);
            var ports = ShapeRasterizer.ports(tank);
            assertEquals(List.of(EquipmentShape.Role.BOTTOM, EquipmentShape.Role.BOTTOM, EquipmentShape.Role.OVERFLOW), tank.nozzles().stream().map(EquipmentShape.Nozzle::role).toList());
            assertEquals(List.of(EquipmentShape.PortClass.LIQUID, EquipmentShape.PortClass.LIQUID, EquipmentShape.PortClass.OVERFLOW),
                    ports.stream().map(ShapeRasterizer.Port::port).toList(), tank.toString());
            for (int i = 0; i < ports.size(); i++) {
                var port = ports.get(i);int sign = i == 1 ? -1 : 1;
                assertArrayEquals(new int[]{sign * heading.dx, 0, sign * heading.dz}, new int[]{port.dx(), port.dy(), port.dz()}, tank.toString());
            }
            var a = ports.get(0).cell();
            var b = ports.get(1).cell();
            var overflow = ports.get(2).cell();
            assertEquals(List.of(0, 0, dimensions[1] - 1), List.of(a.y(), b.y(), overflow.y()), tank.toString());
            // Both bottom ports share the overflow's centerline; the second ends at the opposite side of the tank.
            assertEquals(heading.dx != 0 ? overflow.z() : overflow.x(), heading.dx != 0 ? a.z() : a.x(), tank.toString());
            assertEquals(heading.dx != 0 ? a.z() : a.x(), heading.dx != 0 ? b.z() : b.x(), tank.toString());
            assertEquals(heading.dx != 0 ? overflow.x() : overflow.z(), heading.dx != 0 ? a.x() : a.z(), tank.toString());
            var frontFace = tank.transform().point(tank.nozzles().get(0).face());
            var backFace = tank.transform().point(tank.nozzles().get(1).face());
            assertTrue((frontFace[0] - backFace[0]) * heading.dx + (frontFace[2] - backFace[2]) * heading.dz > tank.diameter(), tank.toString());
            var gauge = ShapeRasterizer.floating(tank);
            assertArrayEquals(new double[]{heading.dx, 0, heading.dz}, gauge.facing(), 0, "gauge stays on the front: " + tank);
            double side = (gauge.glass()[0] - frontFace[0]) * heading.dz - (gauge.glass()[1] - frontFace[2]) * heading.dx;
            assertEquals(.85, Math.abs(side), 1e-9, "gauge stays beside the ports: " + tank);
            // A sphere: the vapour outlet up at its top, the liquid outlet on the lowest row at the box's front edge.
            var sphere = EquipmentShape.storage(Kind.SPHERE, 14, 1, heading, 2);
            var sp = ShapeRasterizer.ports(sphere);
            assertEquals(List.of(EquipmentShape.PortClass.VAPOUR, EquipmentShape.PortClass.LIQUID), sp.stream().map(ShapeRasterizer.Port::port).toList(), sphere.toString());
            assertArrayEquals(new int[]{0, 1, 0}, new int[]{sp.get(0).dx(), sp.get(0).dy(), sp.get(0).dz()}, sphere.toString());
            assertTrue(sp.get(0).cell().y() >= sphere.support() + sphere.diameter(), sphere.toString());
            assertArrayEquals(new int[]{heading.dx, 0, heading.dz}, new int[]{sp.get(1).dx(), sp.get(1).dy(), sp.get(1).dz()}, sphere.toString());
            var front = sp.get(1).cell();
            int fx = front.x() + heading.dx, fz = front.z() + heading.dz;
            assertEquals(0, front.y(), sphere.toString());
            assertTrue(fx < 0 || fz < 0 || fx >= sphere.sizeX() || fz >= sphere.sizeZ(), sphere + ": the liquid outlet ends at the front edge");
        }
    }

    @Test
    void tankGaugeFacesPointOutwardsIncludingTheUnderside() {
        var faces = TankGaugeGeometry.bandFaces();assertEquals(6, faces.size());
        var normals = new java.util.HashSet<String>();
        for (var face : faces) {
            var p = face.positions();var n = face.normals();
            double[] a = {p[3] - p[0], p[4] - p[1], p[5] - p[2]}, b = {p[6] - p[0], p[7] - p[1], p[8] - p[2]};
            double[] cross = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
            assertTrue(cross[0] * (p[0] - .5) + cross[1] * (p[1] - .5) + cross[2] * (p[2] - .5) > 0, "inward face is culled from outside");
            assertTrue(cross[0] * n[0] + cross[1] * n[1] + cross[2] * n[2] > 0, "winding and light normal disagree");
            normals.add(n[0] + "," + n[1] + "," + n[2]);
        }
        assertEquals(6, normals.size());
    }

    @Test
    void tankGaugeHasAClearGlassSleeveAndConnectedEndPipesInEveryHeading() {
        assertTrue(TankGaugeGeometry.GLASS_HALF > TankGaugeGeometry.BAND_HALF, "liquid clears the glass");
        assertTrue(TankGaugeGeometry.MARKER_INNER_HALF > TankGaugeGeometry.GLASS_HALF, "the hollow float clears the glass");
        for (int[] dimensions : new int[][]{{4, 4}, {40, 24}}) for (var heading : Heading.values()) {
            var shape = EquipmentShape.storage(Kind.TANK, dimensions[0], dimensions[1], heading, 0);
            var gauge = ShapeRasterizer.floating(shape);
            // The dynamic liquid stops at fill height; the fixed sleeve extends .3 m above it into the vent space.
            double low = gauge.glassLow(), high = gauge.glassHigh() + .3, glassArea = 0, lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
            var normals = new HashSet<String>();
            for (var cell : ShapeRasterizer.layout(shape).shell()) {
                if (Math.abs(cell.x() + .5 - gauge.glass()[0]) > 1 || Math.abs(cell.z() + .5 - gauge.glass()[1]) > 1) continue;
                for (var face : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) if (face.material() == Material.GLASS) {
                    double[] positions = face.positions(), normal = newell(face), light = face.normals();
                    double dx = positions[0] + cell.x() - gauge.glass()[0], dz = positions[2] + cell.z() - gauge.glass()[1];
                    assertTrue(normal[0] * dx + normal[2] * dz > 0, "clipped glass faces outwards: " + shape);
                    assertTrue(normal[0] * light[0] + normal[1] * light[1] + normal[2] * light[2] > 0, "winding agrees with lighting");
                    assertEquals(0, normal[1], 1e-12, "glass end faces stay buried in opaque caps");
                    normals.add(light[0] + "," + light[1] + "," + light[2]);
                    for (int v = 0; v < positions.length; v += 3) {
                        double x = Math.abs(positions[v] + cell.x() - gauge.glass()[0]), z = Math.abs(positions[v + 2] + cell.z() - gauge.glass()[1]);
                        assertEquals(TankGaugeGeometry.GLASS_HALF, Math.max(x, z), 1e-9, "the sleeve encloses the liquid without crossing its collar");
                        lowest = Math.min(lowest, positions[v + 1] + cell.y());highest = Math.max(highest, positions[v + 1] + cell.y());
                    }
                    glassArea += area(face);
                }
            }
            assertEquals(4, normals.size(), "all four lateral views have glass");
            assertEquals(low, lowest, 1e-9);assertEquals(high, highest, 1e-9);
            assertEquals(8 * TankGaugeGeometry.GLASS_HALF * (high - low), glassArea, 1e-8, "cell clipping leaves no missing or overlapping glass");
            var plan = TankDesign.Plan.of(dimensions[0], dimensions[1]);var local = plan.gauge();
            double shellZ = plan.a() - Math.sqrt(plan.r() * plan.r() - Math.pow(local.x() - plan.a(), 2));
            // Cross the exposed neck at three stations between shell and cap, below/above the glass and scale board.
            for (double y : new double[]{low - TankGaugeGeometry.END_HEIGHT / 2, high + TankGaugeGeometry.END_HEIGHT / 2})
                for (double fraction : new double[]{.2, .5, .8}) {
                    double capBack = local.z() + TankGaugeGeometry.CHAMBER_RADIUS + .03, z = shellZ + fraction * (capBack - shellZ);
                    assertTrue(crossings(shape, new double[]{local.x() - .09, y, z}, new double[]{local.x() + .09, y, z}) >= 2,
                            "a continuous round equalizing pipe joins each end to the shell: " + shape + " y=" + y + " fraction=" + fraction);
                }
        }
    }

    /**
     * Every size: its decks and its wound stair or ladders are walked (at frame level), its pipes kept one block apart. The
     * wound stair from {@link TankDesign#STAIR_SIZE} and {@link SphereDesign#STAIR_SIZE} across, caged ladders under (owner
     * change 2026-10-21); every tank's roof and rolling ladder drawn apart.
     */
    @Test
    void everyStorageSizeIsWalkedAndKeepsItsPipesApart() {
        for (var shape : everyStorageSize()) {
            var unit = ProcessUnit.of(shape);
            ProcessUnitTest.walkedInFrame(unit);
            boolean tank = shape.kind() == Kind.TANK, stair = shape.diameter() >= (tank ? TankDesign.STAIR_SIZE : SphereDesign.STAIR_SIZE);
            assertEquals(stair ? 1 : 0, unit.woundStairs().size(), shape.toString());
            assertEquals(stair, unit.ladders().isEmpty(), shape.toString());
            for (var l : unit.ladders()) assertTrue(l.top() - Access.LANDING - l.base() <= Access.MAX_CLIMB + Access.DECK + 1e-9, shape + " climbs from " + l.base());
            if (tank) {
                assertEquals(shape.fillHeight(), unit.floating.length(), 0, shape.toString());
                innerRailFlanksTheLadder(shape, unit.floating);
            }
            var nozzles = shape.nozzles();
            assertEquals(shape.kind() == Kind.TANK ? 3 : 2, nozzles.size(), shape.toString());
            for (int i = 0; i < nozzles.size(); i++)
                for (int j = i + 1; j < nozzles.size(); j++) assertFalse(nozzles.get(i).crowds(nozzles.get(j)), shape + ": " + nozzles.get(i).role() + " and " + nozzles.get(j).role());
        }
    }

    /**
     * The platform's inner rail stands in its band either side of the rolling ladder's opening, a rail band beyond it
     * (owner report 2026-10-25: on a 7 m tank the opening took the whole inner edge, its rail and posts were left out).
     */
    private static void innerRailFlanksTheLadder(EquipmentShape shape, ProcessUnit.Floating f) {
        double[] pivot = f.pivot(), along = f.along();
        double inner = shape.diameter() / 2 - TankDesign.OVERHANG, cx = pivot[0] + inner * along[0], cz = pivot[2] + inner * along[2];
        double hinge = Math.atan2(-along[2], -along[0]), rho = inner + Access.RAIL_BAND / 2;
        for (int side : new int[]{-1, 1}) {
            double t = hinge + side * (TankDesign.LADDER_OPENING + Access.RAIL_BAND) / 2 / inner;
            assertTrue(in(shape, cx + rho * Math.cos(t), pivot[1] + .7, cz + rho * Math.sin(t)), shape + ": no inner rail on side " + side);
        }
    }

    /** The sampled sizes walked in the box: decks, closed ends stopping and railed, and the wound stair at the game's collision. */
    @Test
    void storageDecksAndStairsAreWalkedInTheBox() {
        for (var shape : SHAPES) if (shape.storage()) ProcessUnitTest.walkedAndClimbed(shape);
    }

    @Test
    void aTanksRoofAndLadderAreDrawnApart() {
        var tank = EquipmentShape.storage(Kind.TANK, 20, 12, Heading.NORTH, 0);
        var f = ShapeRasterizer.floating(tank);
        assertNotNull(f);
        assertFalse(f.roof().isEmpty());
        assertFalse(f.ladder().isEmpty());
        // The roof floats on the liquid between its legs' 1.8 m and the fill height; the ladder turns down as it sinks.
        assertEquals(1.8, f.underside(0), 0);
        assertEquals(6, f.underside(6), 0);
        assertEquals(11, f.underside(50), 0);
        assertTrue(f.pitch(11) < f.pitch(6) && f.pitch(6) < f.pitch(0), f.pitch(11) + " " + f.pitch(6) + " " + f.pitch(0));
        assertEquals(tank.fillHeight(), f.length(), 0);
        double roof = 0;
        for (var p : f.roof()) roof += area(p);
        assertTrue(roof > Math.PI * 100, "the roof's area " + roof);
        assertNull(ShapeRasterizer.floating(EquipmentShape.storage(Kind.SPHERE, 14, 1, Heading.NORTH, 2)));
    }

    /** A timing note (plan section 3.3): the largest tank's layout, about 30 000 interior cells; not a gate. */
    @Test
    void theLargestTankLaysOut() {
        var shape = EquipmentShape.storage(Kind.TANK, 40, 24, Heading.NORTH, 0);
        long start = System.nanoTime();
        var layout = ShapeRasterizer.layout(shape);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("TIMING largest tank " + shape.sizeX() + " x " + shape.sizeY() + " x " + shape.sizeZ() + ": " + layout.shell().size() + " shell, "
                + layout.interior().size() + " interior cells laid out in " + ms + " ms");
        assertTrue(layout.interior().size() > 25_000, layout.interior().size() + " interior cells");
    }

    /** A frame point inside the solid. */
    private static boolean in(EquipmentShape shape, double x, double y, double z) {
        var p = shape.transform().point(new double[]{x, y, z});
        return ShapeRasterizer.inside(shape, p[0], p[1], p[2]);
    }

    private static int axial(ShapeRasterizer.CellKey key) {
        var along = key.shape().transform().direction(new double[]{0, 1, 0});
        return along[0] != 0 ? key.x() : along[1] != 0 ? key.y() : key.z();
    }

    /** Whether a point of the cell, on a 0.02 m grid, is where a ladder is climbed. */
    private static boolean reachedByClimb(EquipmentShape shape, ShapeRasterizer.Cell cell) {
        for (int i = 0; i <= 50; i++)
            for (int j = 0; j <= 50; j++)
                for (double y : new double[]{.02, .5, .98})
                    if (ShapeRasterizer.climbable(shape, cell.x() + i / 50.0, cell.y() + y, cell.z() + j / 50.0)) return true;
        return false;
    }

    private static boolean reachedByNozzle(EquipmentShape shape, ShapeRasterizer.Cell cell) {
        for (int k = 0; k < shape.nozzles().size(); k++) if (ShapeRasterizer.nozzleCells(shape, k).contains(cell)) return true;
        return false;
    }

    /** Surface pieces the segment between frame points {@code from} and {@code to} crosses. */
    private static int crossings(EquipmentShape shape, double[] from, double[] to) {
        return crossings(shape, from, to, new double[shape.nozzles().size()]);
    }

    /** As {@link #crossings(EquipmentShape, double[], double[])}, with each nozzle drawn at the pipe of outside {@code outside} (0: unconnected). */
    private static int crossings(EquipmentShape shape, double[] from, double[] to, double[] outside) {
        double[] p = shape.transform().point(from), q = shape.transform().point(to), d = {q[0] - p[0], q[1] - p[1], q[2] - p[2]};
        int count = 0;
        for (var cell : ShapeRasterizer.layout(shape).shell())
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z(), ShapeRasterizer.cellCodes(shape, cell, outside)))) {
                // Newell's normal, the plane through the first vertex, and the crossing point in cell coordinates.
                double[] pos = polygon.positions(), n = new double[3];
                int size = polygon.size();
                for (int i = 0; i < size; i++) {
                    int j = (i + 1) % size;
                    n[0] += (pos[3 * i + 1] - pos[3 * j + 1]) * (pos[3 * i + 2] + pos[3 * j + 2]);
                    n[1] += (pos[3 * i + 2] - pos[3 * j + 2]) * (pos[3 * i] + pos[3 * j]);
                    n[2] += (pos[3 * i] - pos[3 * j]) * (pos[3 * i + 1] + pos[3 * j + 1]);
                }
                double[] o = {p[0] - cell.x(), p[1] - cell.y(), p[2] - cell.z()};
                double denominator = n[0] * d[0] + n[1] * d[1] + n[2] * d[2];
                if (Math.abs(denominator) < 1e-12) continue;
                double t = (n[0] * (pos[0] - o[0]) + n[1] * (pos[1] - o[1]) + n[2] * (pos[2] - o[2])) / denominator;
                if (t < 0 || t > 1) continue;
                double[] x = {o[0] + t * d[0], o[1] + t * d[1], o[2] + t * d[2]};
                // Even-odd test in the plane of the normal's two smaller components.
                int drop = Math.abs(n[0]) >= Math.abs(n[1]) && Math.abs(n[0]) >= Math.abs(n[2]) ? 0 : Math.abs(n[1]) >= Math.abs(n[2]) ? 1 : 2, u = (drop + 1) % 3, v = (drop + 2) % 3;
                boolean in = false;
                for (int i = 0, j = size - 1; i < size; j = i++) {
                    double ui = pos[3 * i + u], vi = pos[3 * i + v], uj = pos[3 * j + u], vj = pos[3 * j + v];
                    if ((vi > x[v]) != (vj > x[v]) && x[u] < (uj - ui) * (x[v] - vi) / (vj - vi) + ui) in = !in;
                }
                if (in) count++;
            }
        return count;
    }

    /** Box positions of the access opening's dark back, one per polygon piece. */
    private static List<double[]> openingCentres(EquipmentShape shape) {
        var out = new java.util.ArrayList<double[]>();
        for (var cell : ShapeRasterizer.layout(shape).shell())
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z())))
                if (polygon.material() == Material.OPENING) {
                    var c = new double[]{cell.x(), cell.y(), cell.z()};
                    for (int i = 0; i < polygon.size(); i++) for (int a = 0; a < 3; a++) c[a] += polygon.positions()[3 * i + a] / polygon.size();
                    out.add(c);
                }
        return out;
    }

    /** Area of the faceted pressure vessel: the N-gon prism over T/T and two banded heads. */
    /** The area of a drum's manway, which stands on its shell or head. */
    private static double manwayArea(EquipmentShape shape) {
        var manway = shape.manway();
        if (manway == null) return 0;
        var mesh = new Mesh();
        manway.draw(mesh);
        return mesh.area();
    }

    private static double vesselArea(EquipmentShape shape) {
        return shellArea(shape, shape.tangentLength()) + 2 * headArea(shape);
    }

    private static double vesselVolume(EquipmentShape shape) {
        return prismVolume(shape, shape.tangentLength()) + 2 * headVolume(shape);
    }

    /**
     * Volume of the faceted solid without nozzles: a skirt's prism to the ground, its base ring (an annular N-gon prism
     * 1/8 m wider) and chairs; or the vessel and two saddles, each a top plate around the contact sector, a web and two
     * ribs under the top plate's outer edge, and a base plate, and its boot. The skirt's access opening is drawn but not
     * cut from it.
     */
    private static double facetedVolume(EquipmentShape shape) {
        int n = shape.facets();
        double sin = DeterministicMath.sin(2 * Math.PI / n), cos = DeterministicMath.cos(Math.PI / n), r = shape.radius();
        if (shape.skirt()) {
            double ring = r + EquipmentShape.BASE_RING / cos;
            // A column's shell and top head are clad: the skirt stands at the shell radius, the cladding over it.
            return prismVolume(shape, shape.support(), r) + prismVolume(shape, shape.tangentLength(), shape.outerRadius())
                    + headVolume(shape, rings(shape, shape.outerRadius(), shape.outerHeadDepth()))
                    + EquipmentShape.PLATE * n / 2.0 * sin * (ring * ring - r * r)
                    + shape.anchorChairs() * EquipmentShape.BASE_RING * EquipmentShape.CHAIR * EquipmentShape.CHAIR;
        }
        if (!shape.saddles()) return vesselVolume(shape);
        int m = (int) Math.round(n * EquipmentShape.SADDLE_CONTACT_DEGREES / 720);
        double outer = r + EquipmentShape.PLATE / cos, lateral = outer * DeterministicMath.sin(2 * Math.PI * m / n), web = lateral - EquipmentShape.SADDLE_RIB;
        double width = EquipmentShape.SADDLE_WIDTH;
        double saddle = width * m * sin * (outer * outer - r * r)
                + EquipmentShape.SADDLE_WEB * underChain(shape, -web, web) + 2 * width * underChain(shape, web, lateral)
                + width * 2 * lateral * EquipmentShape.PLATE;
        return vesselVolume(shape) + 2 * saddle + bootVolume(shape);
    }

    /**
     * A lying drum's boot under the shell's bottom line: its faceted prism down to its tangent line and its 2:1 head, two
     * thirds of a prism its depth (the sliver over the line, where the shell curves up, is left out).
     */
    private static double bootVolume(EquipmentShape shape) {
        var b = shape.bootPlace();
        if (b == null) return 0;
        int sides = Math.max(12, 4 * (int) Math.ceil(.75 * Math.PI * b.diameter()));
        return sides / 2.0 * DeterministicMath.sin(2 * Math.PI / sides) * b.diameter() * b.diameter() / 4 * (b.length() + b.diameter() / 6);
    }

    /**
     * The unconnected nozzles outside the vessel: each neck from the vessel's surface on its centre line to the back of
     * its flange, and the flange. The surface across the neck differs from that on its centre line by a sliver.
     */
    private static double nozzleVolume(EquipmentShape shape) {
        double total = 0, a = shape.axis();
        for (var nozzle : shape.nozzles()) {
            double od = nozzle.defaultOd(), t = PipeSizes.flangeThickness(od), flange = PipeSizes.flange(od) / 2;
            var base = nozzle.base();
            double surface;
            if (nozzle.role() == EquipmentShape.Role.BOOT_DRAIN) surface = a - shape.radius() - shape.bootPlace().length() - shape.bootPlace().diameter() / 4; // the boot's pole
            else if (nozzle.axis() != 1) surface = a + nozzle.sign() * shape.outerRadius(); // a shell (cladding) vertex ridge
            else {
                double off = headOffset(shape, Math.hypot(base[0] - a, base[2] - a)); // the head, on a vertex meridian
                surface = nozzle.sign() > 0 ? shape.topTangent() + off : shape.bottomTangent() - off;
            }
            double depth = (surface - base[nozzle.axis()]) * nozzle.sign();
            total += Math.PI * od * od / 4 * (nozzle.length() - t - depth) + Math.PI * flange * flange * t;
        }
        return total;
    }

    /** Distance from the tangent line at which the faceted head's circumradius falls to {@code e}. */
    private static double headOffset(EquipmentShape shape, double e) {
        var rings = rings(shape, shape.outerRadius(), shape.outerHeadDepth());
        for (int k = 0; k + 1 < rings.length; k++)
            if (e >= rings[k + 1][1]) return rings[k][0] + (rings[k + 1][0] - rings[k][0]) * (rings[k][1] - e) / (rings[k][1] - rings[k + 1][1]);
        return shape.headDepth();
    }

    /** Area between a saddle's deck and its top plate's outer edge, over lateral offsets [low, high] from the axis. */
    private static double underChain(EquipmentShape shape, double low, double high) {
        int n = shape.facets(), m = (int) Math.round(n * EquipmentShape.SADDLE_CONTACT_DEGREES / 720);
        double outer = shape.radius() + EquipmentShape.PLATE / DeterministicMath.cos(Math.PI / n), area = 0;
        for (int j = n / 2 - m; j < n / 2 + m; j++) {
            double z0 = outer * DeterministicMath.sin(2 * Math.PI * j / n), z1 = outer * DeterministicMath.sin(2 * Math.PI * (j + 1) / n);
            double h0 = shape.support() + shape.width() / 2.0 + outer * DeterministicMath.cos(2 * Math.PI * j / n) - EquipmentShape.PLATE;
            double h1 = shape.support() + shape.width() / 2.0 + outer * DeterministicMath.cos(2 * Math.PI * (j + 1) / n) - EquipmentShape.PLATE;
            // The edge runs from z0 down to z1 < z0; take its part within [low, high].
            double a = Math.max(z1, low), b = Math.min(z0, high);
            if (b <= a) continue;
            double ha = h1 + (h0 - h1) * (a - z1) / (z0 - z1), hb = h1 + (h0 - h1) * (b - z1) / (z0 - z1);
            area += (b - a) * (ha + hb) / 2;
        }
        return area;
    }

    private static Set<Material> materials(EquipmentShape shape, int low, int high) {
        var found = EnumSet.noneOf(Material.class);
        for (var cell : ShapeRasterizer.layout(shape).shell())
            if (cell.y() >= low && cell.y() < high)
                for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) found.add(polygon.material());
        return found;
    }

    private static double shellArea(EquipmentShape shape, double length) {
        int n = shape.facets();
        return n * 2 * shape.radius() * DeterministicMath.sin(Math.PI / n) * length;
    }

    private static double prismVolume(EquipmentShape shape, double length) {
        return prismVolume(shape, length, shape.radius());
    }

    private static double prismVolume(EquipmentShape shape, double length, double r) {
        int n = shape.facets();
        return n / 2.0 * DeterministicMath.sin(2 * Math.PI / n) * r * r * length;
    }

    private static double headArea(EquipmentShape shape) {
        int n = shape.facets();
        double sin = DeterministicMath.sin(Math.PI / n), cos = DeterministicMath.cos(Math.PI / n), area = 0;
        double[][] rings = rings(shape);
        for (int k = 0; k + 1 < rings.length; k++) {
            double dh = rings[k + 1][0] - rings[k][0], dr = (rings[k][1] - rings[k + 1][1]) * cos;
            area += n * (rings[k][1] + rings[k + 1][1]) * sin * Math.sqrt(dh * dh + dr * dr);
        }
        return area;
    }

    private static double headVolume(EquipmentShape shape) {
        return headVolume(shape, rings(shape));
    }

    private static double headVolume(EquipmentShape shape, double[][] rings) {
        int n = shape.facets();
        double polygon = n / 2.0 * DeterministicMath.sin(2 * Math.PI / n), volume = 0; // area of the N-gon of unit circumradius
        for (int k = 0; k + 1 < rings.length; k++) {
            double a = polygon * rings[k][1] * rings[k][1], b = polygon * rings[k + 1][1] * rings[k + 1][1];
            volume += (rings[k + 1][0] - rings[k][0]) / 3 * (a + b + Math.sqrt(a * b));
        }
        return volume;
    }

    /** The steel head's rings. */
    private static double[][] rings(EquipmentShape shape) {
        return rings(shape, shape.radius(), shape.headDepth());
    }

    /** Rings of a head of radius {@code r} and depth {@code depth}: a column's clad head has the outer ones. */
    private static double[][] rings(EquipmentShape shape, double r, double depth) {
        int bands = shape.bands();
        var rings = new double[bands + 1][];
        for (int k = 0; k <= bands; k++) {
            double phi = Math.PI / 2 * k / bands;
            rings[k] = new double[]{depth * DeterministicMath.sin(phi), k == bands ? 0 : r * DeterministicMath.cos(phi)};
        }
        return rings;
    }

    private static boolean sameUpToRotation(Mesh.Polygon a, Mesh.Polygon b) {
        int m = a.size();
        if (m != b.size() || a.material() != b.material()) return false;
        rotation:
        for (int r = 0; r < m; r++) {
            for (int i = 0; i < m; i++) {
                int j = (i + r) % m;
                for (int c = 0; c < 3; c++)
                    if (Math.abs(a.positions()[3 * i + c] - b.positions()[3 * j + c]) > 1e-12
                            || Math.abs(a.normals()[3 * i + c] - b.normals()[3 * j + c]) > 1e-12) continue rotation;
                for (int c = 0; c < 2; c++)
                    if (Math.abs(a.uvs()[2 * i + c] - b.uvs()[2 * j + c]) > 1e-12) continue rotation;
            }
            return true;
        }
        return false;
    }

    private static double area(Mesh.Polygon polygon) {
        var n = newell(polygon);
        return .5 * Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    }

    private static double[] newell(Mesh.Polygon polygon) {
        double[] p = polygon.positions(), n = new double[3];
        int m = polygon.size();
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            n[0] += (p[3 * i + 1] - p[3 * j + 1]) * (p[3 * i + 2] + p[3 * j + 2]);
            n[1] += (p[3 * i + 2] - p[3 * j + 2]) * (p[3 * i] + p[3 * j]);
            n[2] += (p[3 * i] - p[3 * j]) * (p[3 * i + 1] + p[3 * j + 1]);
        }
        return n;
    }
}
