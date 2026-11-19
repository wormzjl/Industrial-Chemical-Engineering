package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.assembly.AssemblyStore.Entry;
import com.wormzjl.icheme.runtime.control.ValveSignal;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.SavedEquipment;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger.Registration;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A shell-and-tube exchanger's control lines as an assembly (batch 2026-10-27-heat-exchanger-design, plan section 7, W8):
 * its design read from its sides' registered controls, and its report published to the fluid world as each side's lines,
 * which decide whether the side's control works a real valve ({@link FluidWorldAuthority#lines}).
 */
final class ExchangerLines {
    private ExchangerLines() {
    }

    /** A kettle's tubes can also have control lines. */
    static boolean lined(@Nullable EquipmentShape shape) {
        return shape != null && (shape.kind() == EquipmentShape.Kind.EXCHANGER || shape.kind() == EquipmentShape.Kind.KETTLE);
    }

    /** The lines the built exchanger's registered sides ask for. */
    static ExchangerBlueprint.Design design(FluidWorldAuthority world, PhysicalFluidTopology.Position origin) {
        var shape = SavedEquipment.at(world, origin);
        if (!lined(shape)) return ExchangerBlueprint.Design.NONE;
        var tube = ExchangerBlueprint.Lines.NONE;
        var shell = ExchangerBlueprint.Lines.NONE;
        for (long id : EquipmentStructure.heatSides(world, origin, shape)) {
            var heat = world.registrations().get(id).device().heat();
            if (heat.side() == HeatSides.Side.TUBE) tube = heat.lines();
            else if (heat.side() == HeatSides.Side.SHELL) shell = heat.lines();
        }
        return new ExchangerBlueprint.Design(tube, shell);
    }

    /**
     * Publishes {@code entry} (null: none) as the lines of each registered heat side whose control asks for lines: not
     * validated, checked with the parts not in place counted, or complete with the valve its control works (the
     * registration at the position the operated slot holds). Returns whether any valve needs an external signal.
     */
    static boolean publish(FluidWorldAuthority world, PhysicalFluidTopology.Position origin, @Nullable Entry entry) {
        var shape = SavedEquipment.at(world, origin);
        if (!lined(shape)) return false;
        boolean externalSignal = false;
        for (long id : EquipmentStructure.heatSides(world, origin, shape)) {
            var heat = world.registrations().get(id).device().heat();
            var asked = heat.lines();
            if (asked == ExchangerBlueprint.Lines.NONE || entry == null) {
                world.lines(id, null);
                continue;
            }
            var report = entry.report();
            // The report's slot states go with the lines: the side's screen draws each part in its colour (the heat GUI).
            var slots = report == null ? List.<AssemblyReport.SlotReport>of() : report.slots();
            world.lines(id, switch (entry.status()) {
                case NOT_VALIDATED -> new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.NOT_VALIDATED, 0, 0);
                case CHECKED -> new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.CHECKED, report.missing() + report.misplaced(), 0, slots);
                case COMPLETE -> {
                    var found = valve(origin.dimension(), world, report, ExchangerBlueprint.operated(heat.side(), asked));
                    if (found == null || !(found.device().control() instanceof FlowControl.ControlValve law))
                        yield new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.CHECKED, 1, 0, slots);
                    // A control valve regulates by its sensor: a bare one stands open, whatever its target.
                    long valve = found.device().id();
                    var signal = ValveSignal.link(world, valve, law);
                    externalSignal |= signal != ValveSignal.Link.LOCAL;
                    boolean metered = !law.bare() && signal != ValveSignal.Link.MISSING;
                    yield new FluidWorldAuthority.Lines(metered ? FluidWorldAuthority.Lines.Status.COMPLETE : FluidWorldAuthority.Lines.Status.UNMETERED, 0, valve, slots);
                }
            });
        }
        return externalSignal;
    }

    /** The unheld valve at the reported fitting's position, if the report still names it. */
    private static @Nullable Registration valve(String dimension, FluidWorldAuthority world, AssemblyReport report, @Nullable Slot slot) {
        if (slot == null) return null;
        for (var s : report.slots()) {
            if (!s.slot().equals(slot) || s.member() == AssemblyReport.NONE) continue;
            var at = BlockPos.of(s.member());
            var position = new PhysicalFluidTopology.Position(dimension, at.getX(), at.getY(), at.getZ());
            return world.at(position).filter(r -> !r.device().held()).orElse(null);
        }
        return null;
    }
}
