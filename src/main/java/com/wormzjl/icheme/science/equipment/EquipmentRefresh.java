package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.ShapeRasterizer.Cell;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer.Layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * A placed vessel brought back to its kind's current layout (owner request 2026-10-18: "when loading an old save,
 * updated models are replaced by new ones", an exception to the no-compatibility rule recorded in AGENTS.md). A cell
 * draws from the code at every load, so only the set of cells can be stale: the cells a new layout adds are missing,
 * those it drops still stand, and the box may have grown or shrunk. The world notices a stale vessel by its cells'
 * {@link ShapeRasterizer#stamp}. A refit stays joined and on its foundation: the new layout stands where it keeps the
 * most of its nozzles on their pipes, then where it covers the most of
 * what the vessel already holds, so its body stays and only the parts the layout redrew change, whichever side of the
 * box they grew or shrank on. The plan then clears the vessel's cells the layout no longer has, places the cells it
 * lacks where the world is free, and keeps the rest. A layout cell another block stands in is left out (the vessel
 * works without it), never overwritten; the cells it did write keep no stamp, so the next load tries again (batch
 * 2026-10-26-backward-compat).
 */
public final class EquipmentRefresh {
    /** How far along x and z the new layout's box may stand from the old one's, blocks: more than any layout change moves a body. */
    public static final int REACH = 3;

    /** What stands in a cell of the region the vessel spans: one of its shell or interior cells, a pipe (a fluid device block,
     * which a nozzle meets: {@link #anchor}), or another block. Free cells are absent. */
    public enum Holding { SHELL, INTERIOR, PIPE, OTHER }

    /**
     * Where the new box stands from the old origin ({@code shift}, along x and z only: the vessel stays at its grade), the
     * vessel's cells to clear, the shell and interior cells to place, and the layout's cells another block stands in;
     * every list from the new origin (old origin + shift), in x, y, z order.
     */
    public record Plan(Cell shift, List<Cell> clear, List<Cell> shell, List<Cell> interior, List<Cell> blocked) {
        public boolean changes() {
            return shift.x() != 0 || shift.z() != 0 || !clear.isEmpty() || !shell.isEmpty() || !interior.isEmpty();
        }
    }

    private static final Comparator<Cell> ORDER = Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z);

    private EquipmentRefresh() {
    }

    /** The plan that brings {@code world} (what stands in each cell of the region, from the old origin, free cells absent) to {@code layout}. */
    public static Plan plan(Layout layout, Map<Cell, Holding> world) {
        return plan(layout, world, anchor(layout, world));
    }

    /**
     * The plan with the layout's box at {@code shift} from the old origin. A restore from the registry's record keeps the
     * origin the record holds (shift 0, batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md 4.4); only a layout
     * refresh looks for one ({@link #anchor}).
     */
    public static Plan plan(Layout layout, Map<Cell, Holding> world, Cell shift) {
        var shell = new HashSet<>(layout.shell());
        var interior = new HashSet<>(layout.interior());
        var clear = new ArrayList<Cell>();
        for (var entry : world.entrySet()) {
            var cell = minus(entry.getKey(), shift);
            if (entry.getValue() == Holding.SHELL && !shell.contains(cell) || entry.getValue() == Holding.INTERIOR && !interior.contains(cell)) clear.add(cell);
        }
        var placeShell = new ArrayList<Cell>();
        var placeInterior = new ArrayList<Cell>();
        var blocked = new ArrayList<Cell>();
        for (var cell : layout.shell()) {
            var held = world.get(plus(cell, shift));
            if (held == Holding.OTHER || held == Holding.PIPE) blocked.add(cell);
            else if (held != Holding.SHELL) placeShell.add(cell);
        }
        for (var cell : layout.interior()) {
            var held = world.get(plus(cell, shift));
            if (held == Holding.OTHER || held == Holding.PIPE) blocked.add(cell);
            else if (held != Holding.INTERIOR) placeInterior.add(cell);
        }
        for (var list : List.of(clear, placeShell, placeInterior, blocked)) list.sort(ORDER);
        return new Plan(shift, List.copyOf(clear), List.copyOf(placeShell), List.copyOf(placeInterior), List.copyOf(blocked));
    }

    /**
     * The shift, within {@link #REACH} along x and z, that keeps the most of the layout's nozzles on a pipe (ports first,
     * batch 2026-10-26-backward-compat: a pipe stays joined wherever its nozzle can stay), then puts the most of the
     * layout's cells on cells the vessel holds as the same kind; ties go to the smaller shift, then to the lower x and z.
     * None when the layout fits where it stands.
     */
    static Cell anchor(Layout layout, Map<Cell, Holding> world) {
        var nozzles = ShapeRasterizer.ports(layout.shape());
        var best = new Cell(0, 0, 0);
        int most = matches(layout, world, best), kept = joined(nozzles, world, best);
        if (most == layout.shell().size() + layout.interior().size()) return best;
        for (int dx = -REACH; dx <= REACH; dx++)
            for (int dz = -REACH; dz <= REACH; dz++) {
                var shift = new Cell(dx, 0, dz);
                int joined = joined(nozzles, world, shift), found = matches(layout, world, shift);
                if (joined > kept || joined == kept && (found > most || found == most && Math.abs(dx) + Math.abs(dz) < Math.abs(best.x()) + Math.abs(best.z()))) {
                    best = shift;
                    kept = joined;
                    most = found;
                }
            }
        return best;
    }

    /** The nozzles whose pipe block, the cell each faces out to, holds a pipe at {@code shift}. */
    private static int joined(List<ShapeRasterizer.Port> nozzles, Map<Cell, Holding> world, Cell shift) {
        int found = 0;
        for (var nozzle : nozzles)
            if (world.get(new Cell(nozzle.cell().x() + nozzle.dx() + shift.x(), nozzle.cell().y() + nozzle.dy(), nozzle.cell().z() + nozzle.dz() + shift.z())) == Holding.PIPE) found++;
        return found;
    }

    private static int matches(Layout layout, Map<Cell, Holding> world, Cell shift) {
        int found = 0;
        for (var cell : layout.shell()) if (world.get(plus(cell, shift)) == Holding.SHELL) found++;
        for (var cell : layout.interior()) if (world.get(plus(cell, shift)) == Holding.INTERIOR) found++;
        return found;
    }

    private static Cell plus(Cell a, Cell b) {
        return new Cell(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    private static Cell minus(Cell a, Cell b) {
        return new Cell(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }
}
