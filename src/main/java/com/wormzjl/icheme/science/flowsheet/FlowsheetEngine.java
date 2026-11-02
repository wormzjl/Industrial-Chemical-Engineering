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

    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint) {
        return run(sheet, catalog, checkpoint, null);
    }

    /**
     * {@code last}, this sheet's last run if it has one, starts its loops; see {@link FlowsheetCompiler#estimates}. A stream flashes
     * on the world's own package, so a sheet drawn on another, or on one since removed, asks for it. Amounts go by component id, so
     * a sheet given that package keeps every one it has a place for.
     */
    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, @Nullable FlowsheetRun last) {
        return run(sheet, catalog, checkpoint, last, FlowsheetSolver.Progress.NONE);
    }

    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, @Nullable FlowsheetRun last, FlowsheetSolver.Progress progress) {
        return solving(sheet, catalog, thermo -> {
            var calculations = FlowsheetCompiler.compile(sheet, thermo);
            var estimates = FlowsheetCompiler.estimates(sheet, thermo, last);
            return FlowsheetSolver.run(calculations, thermo.axis(), thermo, sheet.settings(), checkpoint, estimates, progress, Map.of());
        }, checkpoint);
    }

    /**
     * Solves {@code unit} alone on the streams it has now, {@code streams}, as Solve alone asks (plan section 3.3). Nothing else
     * runs: a stream that starts on the sheet's own feed reaches it, and a heat side runs only if its line has a stream.
     */
    public static FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress) {
        return solving(sheet, catalog, thermo -> {
            var all = FlowsheetCompiler.compile(sheet, thermo);
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
        FlowsheetRun on(FlowThermo thermo);
    }

    private static FlowsheetRun solving(Flowsheet sheet, MaterialCatalog catalog, Solving solving, Runnable checkpoint) {
        String network = sheet.basis().packageId();
        String world = FluidMaterialCatalog.networkPackage(catalog);
        if (!network.equals(world)) return FlowsheetRun.refused(Note.error("sheet", "basis.package", network, world));
        return MaterialRuntime.with(catalog, network, () -> {
            try {
                return solving.on(new FlowThermo(FluidThermodynamics.forNetwork(catalog, network), catalog, network, checkpoint));
            } catch (CancellationException stop) {
                return FlowsheetRun.stopped();
            }
        });
    }
}
