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

/** A placed vessel refreshed to its shape's current layout (owner request 2026-10-18): the stamp and the plan. */
class EquipmentRefreshTest {
    @Test
    void theStampNamesALayoutOnBothSides() {
        // A cell saves its layout's stamp; an equal shape must give it again (on a dedicated server too), another layout another.
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
            long stamp = ShapeRasterizer.stamp(shape);
            assertNotEquals(0, stamp, shape.toString());
            stamps.add(stamp);
        }
        assertEquals(shapes.size(), new HashSet<>(stamps).size(), "distinct layouts share a stamp");
        assertEquals(stamps.getFirst(), ShapeRasterizer.stamp(drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4)));
        try {
            ShapeRasterizer.drawing(false);
            for (int k = 0; k < shapes.size(); k++) assertEquals(stamps.get(k), ShapeRasterizer.stamp(shapes.get(k)), shapes.get(k).toString());
        } finally {
            ShapeRasterizer.drawing(true);
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
