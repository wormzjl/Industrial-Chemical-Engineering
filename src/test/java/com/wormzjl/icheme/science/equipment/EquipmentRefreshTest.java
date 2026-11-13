package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentRefresh.Holding;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer.Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.wormzjl.icheme.science.equipment.EquipmentShape.drum;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A placed vessel refreshed to its shape's current layout (owner request 2026-10-18): which saved cells are current (the
 * declared layout revision, an old save's legacy stamp; batch 2026-11-13-codebase-review, E20) and the plan.
 */
class EquipmentRefreshTest {
    @Test
    void theLegacyStampNamesALayoutOnBothSides() {
        // An old save's cell holds its layout's stamp; an equal shape must give it again (on a dedicated server too), another layout another.
        var shapes = List.of(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4), drum(3, 7, Orientation.VERTICAL, Heading.NORTH, 4),
                drum(3, 6, Orientation.VERTICAL, Heading.EAST, 4), EquipmentShape.unit(Kind.CYCLONE, 2.0, 1, Heading.NORTH, 8),
                // The storage kinds (owner request 2026-10-21) refresh as every layout does.
                EquipmentShape.storage(Kind.TANK, 12, 8, Heading.NORTH, 0), EquipmentShape.storage(Kind.SPHERE, 8, 1, Heading.NORTH, 2),
                // Either side of the stair sizes (owner change 2026-10-21): a small one placed with a stair refreshes to its ladders.
                EquipmentShape.storage(Kind.TANK, 5, 8, Heading.NORTH, 0), EquipmentShape.storage(Kind.SPHERE, 12, 1, Heading.NORTH, 2),
                // A bottom outlet turned to the back (owner request 2026-10-25) is a layout of its own, which the shell keeps.
                drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4, EquipmentShape.defaultConnections(3, 6, Orientation.VERTICAL, 4, false, 2), false, 2));
        var stamps = new ArrayList<Long>();
        for (var shape : shapes) {
            long stamp = ShapeRasterizer.legacyStamp(shape);
            assertNotEquals(0, stamp, shape.toString());
            stamps.add(stamp);
        }
        assertEquals(shapes.size(), new HashSet<>(stamps).size(), "distinct layouts share a stamp");
        assertEquals(stamps.getFirst(), ShapeRasterizer.legacyStamp(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4)));
        try {
            ShapeRasterizer.drawing(false);
            for (int k = 0; k < shapes.size(); k++) assertEquals(stamps.get(k), ShapeRasterizer.legacyStamp(shapes.get(k)), shapes.get(k).toString());
        } finally {
            ShapeRasterizer.drawing(true);
        }
    }

    @Test
    void everyKindDeclaresOneLayoutRevision() {
        // One revision per design, read through the kind, 1 or more: 0 is the mark of a cell to refresh, no design's.
        var revisions = new HashMap<Kind, Integer>();
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            int revision = ShapeRasterizer.layoutRevision(shape);
            assertTrue(revision >= 1 && revision != EquipmentRefresh.STALE, shape + ": " + revision);
            if (shape.kind().designed()) assertEquals(UnitDesign.of(shape.kind()).layoutRevision(), revision, shape.toString());
            int first = revisions.computeIfAbsent(shape.kind(), k -> revision);
            assertEquals(first, revision, "one revision for every shape of " + shape.kind());
        }
        assertEquals(Kind.values().length, revisions.size(), "a kind the table does not lay out: " + revisions.keySet());
        assertThrows(IllegalArgumentException.class, () -> new Sizes(1, 2, 1, 1, 1, 0, 0, Orientation.VERTICAL, "row", null, null, false, EquipmentRefresh.STALE));
    }

    @Test
    void aSavedRevisionIsCurrentOnlyWhenItIsItsDesignsAndItsShapeNeededNoRepair() {
        for (var shape : List.of(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4), EquipmentShape.unit(Kind.CYCLONE, 2.0, 1, Heading.NORTH, 8),
                EquipmentShape.storage(Kind.TANK, 12, 8, Heading.NORTH, 0))) {
            int current = ShapeRasterizer.layoutRevision(shape);
            assertEquals(current, EquipmentRefresh.placedRevision(shape, false, current), shape.toString());
            // A cell a blocked refresh wrote, one an older or a newer layout placed, a cell whose shape was repaired on read.
            assertEquals(EquipmentRefresh.STALE, EquipmentRefresh.placedRevision(shape, false, EquipmentRefresh.STALE), shape.toString());
            assertEquals(EquipmentRefresh.STALE, EquipmentRefresh.placedRevision(shape, false, current - 1), shape.toString());
            assertEquals(EquipmentRefresh.STALE, EquipmentRefresh.placedRevision(shape, false, current + 1), shape.toString());
            assertEquals(EquipmentRefresh.STALE, EquipmentRefresh.placedRevision(shape, true, current), shape.toString());
        }
    }

    @Test
    void anOldSavesStampIsCurrentWhileItsDesignIsAtRevisionOne() {
        // A cell saved before layout revisions holds only its stamp: current, revision 1, while its design's revision is 1
        // and the stamp is its shape's; so no placed structure refreshes at the switch, and one an older layout placed does.
        var shapes = List.of(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4), drum(3, 7, Orientation.VERTICAL, Heading.NORTH, 4),
                EquipmentShape.unit(Kind.CYCLONE, 2.0, 1, Heading.NORTH, 8), EquipmentShape.storage(Kind.TANK, 12, 8, Heading.NORTH, 0),
                EquipmentShape.column(1.5, ColumnTrays.simple(20, 10), Heading.NORTH, EquipmentShape.minimumSkirt(1.5)));
        for (int k = 0; k < shapes.size(); k++) {
            var shape = shapes.get(k);var other = shapes.get((k + 1) % shapes.size());
            int current = ShapeRasterizer.layoutRevision(shape) == 1 ? 1 : EquipmentRefresh.STALE;
            assertEquals(current, EquipmentRefresh.legacyRevision(shape, ShapeRasterizer.legacyStamp(shape)), shape.toString());
            assertEquals(EquipmentRefresh.STALE, EquipmentRefresh.legacyRevision(shape, ShapeRasterizer.legacyStamp(other)), shape + " placed as " + other);
            // A cell saved before stamps: no stamp.
            assertEquals(EquipmentRefresh.STALE, EquipmentRefresh.legacyRevision(shape, 0), shape.toString());
        }
    }

    @Test
    void aCurrentVesselIsLeftAsItIs() {
        var layout = ShapeRasterizer.layout(EquipmentShape.unit(Kind.CYCLONE, 2.0, 1, Heading.NORTH, 8));
        var plan = EquipmentRefresh.plan(layout, held(layout));
        assertFalse(plan.changes(), plan.toString());
        assertTrue(plan.blocked().isEmpty());
    }

    @Test
    void anOlderLayoutIsBroughtToTheCurrentOne() {
        // The old layout lacked some shell cells, had stray ones outside the new layout (a larger old box) and held one of
        // the new interior cells as shell: the plan clears the strays and the misused cell and places what is missing.
        var layout = ShapeRasterizer.layout(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4));
        var world = held(layout);
        var missing = List.of(layout.shell().get(0), layout.shell().get(layout.shell().size() / 2));
        for (var cell : missing) world.remove(cell);
        var strays = List.of(new Cell(-1, 0, 0), new Cell(0, layout.shape().sizeY(), 1));
        for (var cell : strays) world.put(cell, Holding.SHELL);
        var misused = layout.interior().get(layout.interior().size() / 2);
        world.put(misused, Holding.SHELL);
        var strayInterior = new Cell(layout.shape().sizeX(), 2, 2);
        world.put(strayInterior, Holding.INTERIOR);

        var plan = EquipmentRefresh.plan(layout, world);
        assertEquals(new Cell(0, 0, 0), plan.shift(), "the body stands where it stood");
        assertEquals(sorted(List.of(strays.get(0), strays.get(1), misused, strayInterior)), plan.clear());
        assertEquals(sorted(missing), plan.shell());
        assertEquals(List.of(misused), plan.interior());
        assertTrue(plan.blocked().isEmpty());
        assertTrue(plan.changes());
        assertWorldAfter(layout, world, plan);
    }

    @Test
    void theBodyStaysOnItsFoundationWhenTheBoxShrinksAtTheFront() {
        // The 2.0 m cyclone of 2026-10-18: its old inlet stood one row further to the front (-z), so its box was one row
        // deeper and its origin one row further north. The new layout stands on the old body (shift +1 along z), and only
        // the old inlet's cells, in front of the new box, are cleared.
        var layout = ShapeRasterizer.layout(EquipmentShape.unit(Kind.CYCLONE, 2.0, 1, Heading.NORTH, 8));
        var world = new HashMap<Cell, Holding>();
        for (var entry : held(layout).entrySet()) world.put(new Cell(entry.getKey().x(), entry.getKey().y(), entry.getKey().z() + 1), entry.getValue());
        var inlet = List.of(new Cell(2, 5, 0), new Cell(2, 6, 0), new Cell(3, 6, 0));
        for (var cell : inlet) world.put(cell, Holding.SHELL);
        var plan = EquipmentRefresh.plan(layout, world);
        assertEquals(new Cell(0, 0, 1), plan.shift());
        assertEquals(sorted(List.of(new Cell(2, 5, -1), new Cell(2, 6, -1), new Cell(3, 6, -1))), plan.clear());
        assertTrue(plan.shell().isEmpty() && plan.interior().isEmpty() && plan.blocked().isEmpty(), plan.toString());
        assertTrue(plan.changes());
    }

    @Test
    void anotherBlockInANewCellIsNeverOverwritten() {
        // A cell the new layout adds holds a player's block: it stays, and the cell is left out of the vessel.
        var layout = ShapeRasterizer.layout(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4));
        var world = held(layout);
        var shellCell = layout.shell().getLast();
        var interiorCell = layout.interior().getFirst();
        world.put(shellCell, Holding.OTHER);
        world.put(interiorCell, Holding.OTHER);
        world.put(new Cell(-1, 0, 0), Holding.OTHER);
        var plan = EquipmentRefresh.plan(layout, world);
        assertEquals(sorted(List.of(shellCell, interiorCell)), plan.blocked());
        assertTrue(plan.clear().isEmpty(), "another block is cleared");
        assertFalse(plan.shell().contains(shellCell));
        assertFalse(plan.interior().contains(interiorCell));
    }

    @Test
    void theNewLayoutStandsWhereItsNozzlesKeepTheirPipes() {
        // Ports first (batch 2026-10-26-backward-compat): the old body, one block east of where the new layout's nozzles
        // meet the pipes, matches more cells shifted one east; the plan keeps the nozzles on their pipes instead.
        var layout = ShapeRasterizer.layout(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4));
        var world = new HashMap<Cell, Holding>();
        for (var entry : held(layout).entrySet()) world.put(new Cell(entry.getKey().x() + 1, entry.getKey().y(), entry.getKey().z()), entry.getValue());
        world.remove(new Cell(layout.shell().getFirst().x() + 1, layout.shell().getFirst().y(), layout.shell().getFirst().z()));
        var pipes = new ArrayList<Cell>();
        for (var nozzle : ShapeRasterizer.ports(layout.shape())) {
            var pipe = new Cell(nozzle.cell().x() + nozzle.dx(), nozzle.cell().y() + nozzle.dy(), nozzle.cell().z() + nozzle.dz());
            if (!world.containsKey(pipe)) {
                world.put(pipe, Holding.PIPE);
                pipes.add(pipe);
            }
        }
        assertFalse(pipes.isEmpty(), "a nozzle's pipe block lies outside the old body");
        var plan = EquipmentRefresh.plan(layout, world);
        assertEquals(new Cell(0, 0, 0), plan.shift(), "the nozzles stay on their pipes: " + plan);
        for (var pipe : pipes) assertFalse(plan.shell().contains(pipe) || plan.interior().contains(pipe) || plan.clear().contains(pipe), "a pipe is left as it is");
        // With no pipe to keep, the cells decide: the body stays where it stood.
        world.values().removeIf(held -> held == Holding.PIPE);
        assertEquals(new Cell(1, 0, 0), EquipmentRefresh.plan(layout, world).shift());
    }

    @Test
    void aRestoreKeepsTheOriginItIsGiven() {
        // Registry first (batch 2026-11-03-save-corruption): the record holds the origin. The body one block east, which a
        // layout refresh follows, is cleared, and the layout is placed where the record stands.
        var layout = ShapeRasterizer.layout(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4));
        var world = new HashMap<Cell, Holding>();
        for (var entry : held(layout).entrySet()) world.put(new Cell(entry.getKey().x() + 1, entry.getKey().y(), entry.getKey().z()), entry.getValue());
        assertEquals(new Cell(1, 0, 0), EquipmentRefresh.plan(layout, world).shift());
        var plan = EquipmentRefresh.plan(layout, world, new Cell(0, 0, 0));
        assertEquals(new Cell(0, 0, 0), plan.shift());
        assertTrue(plan.blocked().isEmpty(), plan.toString());
        assertWorldAfter(layout, world, plan);
    }

    @Test
    void aPipeInANewCellIsLeftOutAsAnotherBlockIs() {
        var layout = ShapeRasterizer.layout(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4));
        var world = held(layout);
        var cell = layout.shell().getLast();
        world.put(cell, Holding.PIPE);
        assertEquals(List.of(cell), EquipmentRefresh.plan(layout, world).blocked());
    }

    /** The world as the layout places it. */
    private static Map<Cell, Holding> held(ShapeRasterizer.Layout layout) {
        var world = new HashMap<Cell, Holding>();
        for (var cell : layout.shell()) world.put(cell, Holding.SHELL);
        for (var cell : layout.interior()) world.put(cell, Holding.INTERIOR);
        return world;
    }

    /** Applying the plan leaves exactly the layout's cells, each of its type. */
    private static void assertWorldAfter(ShapeRasterizer.Layout layout, Map<Cell, Holding> world, EquipmentRefresh.Plan plan) {
        var after = new HashMap<>(world);
        for (var cell : plan.clear()) after.remove(cell);
        for (var cell : plan.shell()) after.put(cell, Holding.SHELL);
        for (var cell : plan.interior()) after.put(cell, Holding.INTERIOR);
        assertEquals(held(layout), after);
    }

    private static List<Cell> sorted(List<Cell> cells) {
        var list = new ArrayList<>(cells);
        list.sort(java.util.Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z));
        return list;
    }
}
