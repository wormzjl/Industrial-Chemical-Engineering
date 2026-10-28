package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.assembly.AssemblyStore.Entry;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * A shell-and-tube exchanger's control lines as an assembly (batch 2026-10-27-heat-exchanger-design, plan section 7, W8):
 * its design read from its sides' registered controls, and its report published to the fluid world as each side's lines,
 * which decide whether the side's control works a real valve ({@link FluidWorldAuthority#lines}).
 */
final class ExchangerLines {
    private ExchangerLines() {
    }

    /** The lines {@code vessel}'s sides' controls ask for; {@link ExchangerBlueprint.Design#NONE} for anything but an exchanger. */
    static ExchangerBlueprint.Design design(ServerLevel level, FluidWorldAuthority world, Vessel vessel) {
        if (vessel.shape().kind() != EquipmentShape.Kind.EXCHANGER) return ExchangerBlueprint.Design.NONE;
        var ids = EquipmentStructure.sideIdentities(level, vessel);
        var sides = HeatSides.of(vessel.shape());
        var tube = ExchangerBlueprint.Lines.NONE;
        var shell = ExchangerBlueprint.Lines.NONE;
        for (int i = 0; i < sides.size() && i < ids.length; i++) {
            var r = world.registrations().get(ids[i]);
            var heat = r == null ? null : r.device().heat();
            if (heat == null) continue;
            if (sides.get(i).side() == HeatSides.Side.TUBE) tube = heat.lines();
            else if (sides.get(i).side() == HeatSides.Side.SHELL) shell = heat.lines();
        }
        return new ExchangerBlueprint.Design(tube, shell);
    }

    /**
     * Publishes {@code entry} (null: none) as the lines of each of {@code vessel}'s sides whose control asks for lines: not
     * validated, checked with the parts not in place counted, or complete with the valve its control works (the
     * registration at the position the operated slot holds).
     */
    static void publish(ServerLevel level, FluidWorldAuthority world, Vessel vessel, @Nullable Entry entry) {
        if (vessel.shape().kind() != EquipmentShape.Kind.EXCHANGER) return;
        var ids = EquipmentStructure.sideIdentities(level, vessel);
        var sides = HeatSides.of(vessel.shape());
        for (int i = 0; i < sides.size() && i < ids.length; i++) {
            if (ids[i] == 0) continue;
            var r = world.registrations().get(ids[i]);
            var heat = r == null ? null : r.device().heat();
            if (heat == null) continue;
            var asked = heat.lines();
            if (asked == ExchangerBlueprint.Lines.NONE || entry == null) {
                world.lines(ids[i], null);
                continue;
            }
            var report = entry.report();
            // The report's slot states go with the lines: the side's screen draws each part in its colour (the heat GUI).
            var slots = report == null ? java.util.List.<AssemblyReport.SlotReport>of() : report.slots();
            world.lines(ids[i], switch (entry.status()) {
                case NOT_VALIDATED -> new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.NOT_VALIDATED, 0, 0);
                case CHECKED -> new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.CHECKED, report.missing() + report.misplaced(), 0, slots);
                case COMPLETE -> {
                    long valve = valve(level, world, report, ExchangerBlueprint.operated(sides.get(i).side(), asked));
                    // A control valve regulates by its sensor: a bare one stands open, whatever its target.
                    var found = valve == 0 ? null : world.registrations().get(valve);
                    boolean metered = found != null && found.device().control() instanceof FlowControl.ControlValve law && !law.bare();
                    yield valve == 0 ? new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.CHECKED, 1, 0, slots)
                            : new FluidWorldAuthority.Lines(metered ? FluidWorldAuthority.Lines.Status.COMPLETE : FluidWorldAuthority.Lines.Status.UNMETERED, 0, valve, slots);
                }
            });
        }
    }

    /** The registration at the position {@code report} binds to {@code slot}, or 0 where it binds none (a design the report predates). */
    private static long valve(ServerLevel level, FluidWorldAuthority world, @Nullable AssemblyReport report, @Nullable com.wormzjl.icheme.science.assembly.Blueprint.Slot slot) {
        if (report == null || slot == null) return 0;
        for (var s : report.slots()) {
            if (!s.slot().equals(slot) || s.member() == AssemblyReport.NONE) continue;
            var at = BlockPos.of(s.member());
            var found = world.at(new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ()));
            return found.map(r -> r.device().id()).orElse(0L);
        }
        return 0;
    }
}
