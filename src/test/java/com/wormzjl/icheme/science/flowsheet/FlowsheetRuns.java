package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** The short forms the tests call: a run from no last run, a solve with no estimates, Size all with no progress, a block's calculation. */
public final class FlowsheetRuns {
    private FlowsheetRuns() {
    }

    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint) {
        return run(sheet, catalog, checkpoint, null);
    }

    public static FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, @Nullable FlowsheetRun last) {
        return FlowsheetEngine.run(sheet, catalog, checkpoint, last, FlowsheetSolver.Progress.NONE);
    }

    public static FlowsheetRun solve(List<Calculation> calculations, List<String> axis, StreamThermo thermo, FlowsheetSolver.Settings settings, Runnable checkpoint) {
        return FlowsheetSolver.run(calculations, axis, thermo, settings, checkpoint, Map.of(), FlowsheetSolver.Progress.NONE, Map.of());
    }

    public static SheetSizing.SizeAll sizeAll(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo) {
        return SheetSizing.sizeAll(sheet, run, thermo, FlowsheetSolver.Progress.NONE);
    }

    /** {@code calculation} as a block's own solve. */
    public static Calculation block(Calculation calculation) {
        return new Calculation(calculation.id(), calculation.unit(), calculation.reads(), calculation.writes(), calculation.needs(), calculation.gives(), Calculation.Kind.BLOCK, calculation.law());
    }
}
