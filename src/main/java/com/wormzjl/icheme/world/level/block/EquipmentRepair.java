package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.fluid.ColumnUnit;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.Integrity;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.integrity.ColumnWear;
import com.wormzjl.icheme.science.integrity.WallState;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * The Equipment Constructor's repair of the structure on its line (batch 2026-11-16-fouling-corrosion, WP4, plan section
 * 10) and the state its removal group shows. The repair is the engine's queued event ({@link FluidWorldAuthority#queueStructureRepair}):
 * its answer comes at the engine's tick drain. The state is a read of what the walls and the machine health hold: nothing
 * solves, nothing is created.
 */
public final class EquipmentRepair {
    private EquipmentRepair() {}

    /** The used allowance a structure with no booked wall answers: the line then shows no wall share. */
    public static final float NO_WALL = -1;

    /**
     * The repair of {@code vessel}: one queued event for every device registered at its nodes; a column's, one queued event on
     * its unit (WP8, plan section 13.1), accepted while it is stopped. Another structure that registers no fluid device (a
     * motor) answers {@link Integrity.Outcome#NOTHING_TO_REPAIR} at once.
     */
    public static void repair(FluidWorldAuthority world, ServerLevel level, EquipmentStructure.Vessel vessel, Consumer<Integrity.Outcome> reply) {
        if (vessel.shape().column()) {
            world.queueColumnRepair(master(level, vessel), reply);
            return;
        }
        if (!vessel.shape().fluidDevice()) {
            reply.accept(Integrity.Outcome.NOTHING_TO_REPAIR);
            return;
        }
        world.queueStructureRepair(EquipmentStructure.heldAtNodes(world, level, vessel), reply);
    }

    /** The answer's text key of {@code outcome}: {@code message.icheme.equipment.repair.<outcome>}. */
    public static String key(Integrity.Outcome outcome) {
        var name = outcome == Integrity.Outcome.CLEANED ? Integrity.Outcome.REPAIRED : outcome;
        return "message.icheme.equipment.repair." + name.name().toLowerCase(Locale.ROOT);
    }

    /** What the removal group's line shows of a structure: its worst used allowance, 0 to 1 ({@link #NO_WALL} for none), and its words. */
    public record State(float used, String words) {}

    /** {@code vessel}'s state, read from the walls and the machine health as last booked. */
    public static State state(FluidWorldAuthority world, ServerLevel level, EquipmentStructure.Vessel vessel) {
        if (vessel.shape().column()) return column(world.column(master(level, vessel)));
        if (!vessel.shape().fluidDevice()) return new State(NO_WALL, "");
        return state(world, EquipmentStructure.heldAtNodes(world, level, vessel));
    }

    /** A column's origin as its unit names it ({@code ColumnUnit.master}). */
    private static PhysicalFluidTopology.Position master(ServerLevel level, EquipmentStructure.Vessel vessel) {
        var o = vessel.origin();
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), o.getX(), o.getY(), o.getZ());
    }

    /**
     * A column's state, read from its unit's facts (WP8): the allowance its wall used and its words, LEAKING (through),
     * WEEPING or THIN WALL, then TRAYS FOULED. A column that never ran shows no wall share.
     */
    public static State column(@Nullable ColumnUnit unit) {
        if (unit == null) return new State(NO_WALL, "");
        double allowance = unit.material().allowance();
        float used = allowance > 0 ? (float) Math.clamp(unit.wallLoss() / allowance, 0, 1) : 0;
        var stage = WallState.stage(unit.wallLoss(), allowance);
        String words = words(false, stage == WallState.Stage.THROUGH, false, stage == WallState.Stage.THROUGH ? WallState.Stage.SOUND : stage);
        if (ColumnWear.fouled(unit.trayFouling())) words = words.isEmpty() ? "TRAYS FOULED" : words + " · TRAYS FOULED";
        return new State(used, words);
    }

    /** The state of the structure that holds {@code devices}. */
    public static State state(FluidWorldAuthority world, List<Long> devices) {
        var integrity = world.integrity();
        var health = world.health();
        float used = NO_WALL;
        var worst = WallState.Stage.SOUND;
        boolean wrecked = false, leaking = false, failed = false;
        for (long id : devices) {
            if (integrity.wrecked(id)) wrecked = true;
            if (health.failed(id)) failed = true;
            if (world.isBreached(id)) leaking = true;
            var wall = integrity.wall(id);
            if (wall == null) continue;
            float share = wall.allowance() > 0 ? (float) Math.clamp(wall.loss() / wall.allowance(), 0, 1) : 0;
            used = Math.max(used, share);
            if (wall.stage() == WallState.Stage.THROUGH && integrity.kindOf(id) != Integrity.Kind.MACHINE) leaking = true;
            else if (wall.stage().ordinal() > worst.ordinal() && wall.stage() != WallState.Stage.THROUGH) worst = wall.stage();
        }
        return new State(used, words(wrecked, leaking, failed, worst));
    }

    /** The words, most severe first, joined by " · ": WRECKED, LEAKING, FAILED, then WEEPING or THIN WALL. */
    public static String words(boolean wrecked, boolean leaking, boolean failed, WallState.Stage worst) {
        if (wrecked) return "WRECKED";
        var out = new StringBuilder();
        if (leaking) out.append("LEAKING");
        if (failed) out.append(out.isEmpty() ? "" : " · ").append("FAILED");
        String wall = worst == WallState.Stage.WEEPING ? "WEEPING" : worst == WallState.Stage.THIN ? "THIN WALL" : "";
        if (!wall.isEmpty() && !leaking) out.append(out.isEmpty() ? "" : " · ").append(wall);
        return out.toString();
    }
}
