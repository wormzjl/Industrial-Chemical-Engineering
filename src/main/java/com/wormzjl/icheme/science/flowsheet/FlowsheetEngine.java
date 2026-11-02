package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
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
        String network = sheet.basis().packageId();
        String world = FluidMaterialCatalog.networkPackage(catalog);
        if (!network.equals(world)) return FlowsheetRun.refused(Note.error("sheet", "basis.package", network, world));
        return MaterialRuntime.with(catalog, network, () -> {
            var thermo = new FlowThermo(FluidThermodynamics.forNetwork(catalog, network), catalog, network, checkpoint);
            return FlowsheetSolver.run(FlowsheetCompiler.compile(sheet, thermo), thermo.axis(), thermo, sheet.settings(), checkpoint, FlowsheetCompiler.estimates(sheet, thermo, last));
        });
    }
}
