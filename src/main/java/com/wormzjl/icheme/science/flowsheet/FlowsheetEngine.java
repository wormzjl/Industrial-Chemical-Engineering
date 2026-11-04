package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a sheet (batch 2026-11-02-process-simulator, plan section 3.4): a client tool outside the engine schedule. It reads and
 * writes no process state, and {@code checkpoint} is how Stop reaches it. Minecraft-free.
 */
public final class FlowsheetEngine {
    private FlowsheetEngine() {
    }

    /**
     * {@code last}, this sheet's last run if it has one, starts its loops (see {@link FlowsheetCompiler#estimates}) and, where it
     * stood steady, its columns where their solves there ended ({@link Report.Warm}). A stream flashes on the world's own package's
     * view of the sheet's compounds ({@link SheetCompounds}), so a sheet drawn on another runs where that package gives them the same
     * physics, and one drawn on a package since removed asks for it. Amounts go by component id, so a sheet given that package keeps
     * every one it has a place for. A stream of {@code last} that holds a compound the sheet no longer feeds starts nothing.
     */
    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, @Nullable FlowsheetRun last, FlowsheetSolver.Progress progress) {
        return solving(sheet, catalog, view -> {
            var thermo = view.thermo();
            var before = view.down(last);
            var calculations = FlowsheetCompiler.compile(sheet, thermo, before);
            var estimates = FlowsheetCompiler.estimates(sheet, thermo, before);
            return FlowsheetSolver.run(calculations, thermo.axis(), thermo, sheet.settings(), checkpoint, estimates, progress, Map.of());
        }, checkpoint);
    }

    /**
     * Solves {@code unit} alone on the streams it has now, {@code streams}, as Solve alone asks (plan section 3.3). Nothing else
     * runs: a stream that starts on the sheet's own feed reaches it, and a heat side runs only if its line has a stream. A column
     * starts from the learned start. A stream that holds a compound the sheet no longer feeds is not one it has.
     */
    public static FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> shown, FlowsheetSolver.Progress progress) {
        return solving(sheet, catalog, view -> {
            var thermo = view.thermo();
            var streams = view.down(shown);
            var all = FlowsheetCompiler.compile(sheet, thermo, null);
            var known = new HashSet<>(streams.keySet());
            var reads = new HashSet<String>();
            for (var c : all) {
                if (!c.unit().equals(unit)) continue;
                reads.addAll(c.reads());
                if (c.kind() == Calculation.Kind.BLOCK) known.addAll(c.writes());
            }
            var calculations = new ArrayList<Calculation>();
            for (var c : all) {
                if (c.id().startsWith("feed ") && c.writes().stream().anyMatch(reads::contains)) {
                    calculations.add(c);
                    known.addAll(c.writes());
                }
            }
            for (var c : all) {
                if (!c.unit().equals(unit) || c.kind() != Calculation.Kind.BLOCK && !known.containsAll(c.reads())) continue;
                calculations.add(c);
                known.addAll(c.writes());
            }
            return FlowsheetSolver.run(calculations, thermo.axis(), thermo, sheet.settings(), checkpoint, Map.of(), progress, streams);
        }, checkpoint);
    }

    private interface Solving {
        FlowsheetRun on(SheetCompounds view);
    }

    /** {@code solving} on the network package's view of the sheet's compounds ({@link SheetCompounds}); its run comes back on the network's axis. */
    private static FlowsheetRun solving(Flowsheet sheet, MaterialCatalog catalog, Solving solving, Runnable checkpoint) {
        var compounds = SheetCompounds.of(sheet, catalog);
        var refused = SheetCompounds.refusal(sheet, catalog, compounds);
        if (refused != null) return FlowsheetRun.refused(refused);
        String network = FluidMaterialCatalog.networkPackage(catalog);
        var view = catalog.restrict(network, compounds);
        var whole = view == catalog ? null : MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
        var axes = MaterialRuntime.with(view, network, () -> SheetCompounds.of(new FlowThermo(FluidThermodynamics.forNetwork(view, network), view, network, checkpoint), whole));
        var run = MaterialRuntime.with(view, network, () -> {
            try {
                return solving.on(axes);
            } catch (CancellationException stop) {
                return null;
            }
        });
        return run == null ? FlowsheetRun.stopped() : MaterialRuntime.with(catalog, network, () -> axes.up(run));
    }
}
