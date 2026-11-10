package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.assembly.AssemblyStore.Status;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.PortConditions;
import com.wormzjl.icheme.science.column.operation.Refusal;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A column's Start from its assembly (batch 2026-10-28-column-operation, plan 4.1 and 4.2): the request the fluid world
 * runs, built from the column's design and its complete report, or why it cannot start (the status line's NOT READY).
 */
public final class ColumnStarts {
    private ColumnStarts() {
    }

    /** A request, or the reason there is none. */
    public record Built(FluidWorldAuthority.ColumnStart request, String refusal) {
    }

    /** The port a boundary valve's slot stands for, or null for a slot that is no boundary valve. */
    static Port port(Slot slot) {
        return switch (slot.part()) {
            case "feed_valve" -> Port.FEED;
            case "steam_valve" -> Port.STEAM;
            case "offgas_valve" -> Port.OFF_GAS;
            case "water_valve" -> Port.SOUR_WATER;
            case "distillate_valve" -> Port.DISTILLATE;
            case "draw_valve" -> Port.DRAW;
            case "bottoms_valve" -> Port.BOTTOMS;
            default -> null;
        };
    }

    /** The Start of the column at {@code origin}. */
    public static Built build(ServerLevel level, FluidWorldAuthority world, BlockPos origin) {
        var vessel = EquipmentStructure.at(level, origin);
        if (vessel == null || !AssemblyService.designed(vessel.shape())) return refused(Refusal.NO_DESIGN.text());
        var trays = vessel.shape().trays();
        var point = trays.operating();
        if (point == null) return refused(Refusal.NO_SAVED_POINT.text());
        if (trays.components().ejector()) return refused(Refusal.VACUUM.text());
        var entry = AssemblyStore.of(level).entry(vessel.origin().asLong());
        if (entry == null || entry.status() != Status.COMPLETE || entry.report() == null) return refused(Refusal.INCOMPLETE.text());
        String dimension = level.dimension().location().toString();
        var o = vessel.origin();
        var nozzles = new ArrayList<PhysicalFluidTopology.Position>();
        for (var p : ShapeRasterizer.ports(vessel.shape()))
            nozzles.add(new PhysicalFluidTopology.Position(dimension, o.getX() + p.cell().x() + p.dx(), o.getY() + p.cell().y() + p.dy(), o.getZ() + p.cell().z() + p.dz()));
        var spots = new ArrayList<FluidWorldAuthority.PortSpot>();
        for (var s : entry.report().slots()) {
            var port = port(s.slot());
            if (port == null) continue;
            int index = s.slot().index() == 0 ? 0 : s.slot().index() - 1;
            // The fitting that fills the slot (its member; the slot report's at is where a fault lies).
            var at = BlockPos.of(s.member());
            var valve = world.at(new PhysicalFluidTopology.Position(dimension, at.getX(), at.getY(), at.getZ())).orElse(null);
            if (valve == null) return refused(Refusal.VALVE_UNREGISTERED.text());
            // The valve's arrow points along the stream: an inlet's column side is ahead of it, an outlet's behind it.
            var facing = valve.device().facing();
            var toward = port.inlet() ? opposite(facing) : facing;
            var back = net.minecraft.core.Direction.valueOf(toward.name()).getOpposite();
            var cell = new PhysicalFluidTopology.Position(dimension, at.getX() + back.getStepX(), at.getY() + back.getStepY(), at.getZ() + back.getStepZ());
            var c = PortConditions.estimate(point, trays, port, index);
            spots.add(new FluidWorldAuthority.PortSpot(port, index, valve.device().id(), cell, toward, c.pressure(), c.temperature()));
        }
        var master = new PhysicalFluidTopology.Position(dimension, o.getX(), o.getY(), o.getZ());
        // A water-cooled condenser's sides: the column holds its process side, its water side stays in the network (C6b);
        // every shell in parallel the same (S3b); an air-then-water condenser's from its trim (COOLER_MODEL_PLAN.md section 6).
        var cooled = ColumnBlueprint.waterCooled(trays.components().condenser());
        var condenser = cooled == null ? new ArrayList<Long>() : heatSides(level, world, entry, cooled);
        // The reboiler's and each pumparound cooler's sides, every unit's, by the condenser's rule (W4): the column holds the
        // process side, the utility side stays in the network and is owed the duty.
        var reboiler = heatSides(level, world, entry, ColumnBlueprint.REBOILER);
        var pumparounds = new ArrayList<java.util.List<Long>>();
        for (int i = 1; i <= trays.pumparounds().size(); i++) pumparounds.add(heatSides(level, world, entry, ColumnBlueprint.pumparoundCooler(i)));
        // An air condenser's banks, every one's bare tube: what its fans hold (U2, COOLER_MODEL_PLAN.md section 8).
        double air = 0;
        if (!ColumnBlueprint.CONDENSER.equals(cooled))
            for (var s : entry.report().slots())
                if (s.slot().equals(ColumnBlueprint.CONDENSER))
                    for (long unit : s.units()) {
                        var member = EquipmentStructure.at(level, BlockPos.of(unit));
                        if (member != null && member.shape().kind() == EquipmentShape.Kind.AIR_COOLER)
                            for (var side : HeatSides.of(member.shape())) air += side.area();
                    }
        return new Built(new FluidWorldAuthority.ColumnStart(master, Setpoints.of(point), nozzles, spots, condenser, air, reboiler, pumparounds), null);
    }

    /** The registered heat sides of every unit the report places in {@code slot}, one unit after another (S3b: units in parallel). */
    private static ArrayList<Long> heatSides(ServerLevel level, FluidWorldAuthority world, AssemblyStore.Entry entry, Slot slot) {
        var sides = new ArrayList<Long>();
        for (var s : entry.report().slots())
            if (s.slot().equals(slot))
                for (long unit : s.units()) {
                    var member = EquipmentStructure.at(level, BlockPos.of(unit));
                    if (member != null) sides.addAll(EquipmentStructure.heatSides(world, level, member));
                }
        return sides;
    }

    private static PhysicalFluidTopology.Direction opposite(PhysicalFluidTopology.Direction d) {
        return PhysicalFluidTopology.Direction.valueOf(net.minecraft.core.Direction.valueOf(d.name()).getOpposite().name());
    }

    private static Built refused(String why) {
        return new Built(null, why);
    }
}
