package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;

/**
 * Runs a sheet (batch 2026-11-02-process-simulator, plan section 3.4): a client tool outside the engine schedule. It reads and
 * writes no process state, and {@code checkpoint} is how Stop reaches it. Minecraft-free.
 */
public final class FlowsheetEngine {
    private FlowsheetEngine() {
    }

    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint) {
        String network = sheet.basis().packageId();
        return MaterialRuntime.with(catalog, network, () -> {
            var thermo = new FlowThermo(FluidThermodynamics.forNetwork(catalog, network), checkpoint);
            return FlowsheetSolver.run(FlowsheetCompiler.compile(sheet, thermo), thermo.axis(), thermo, sheet.settings(), checkpoint);
        });
    }
}
