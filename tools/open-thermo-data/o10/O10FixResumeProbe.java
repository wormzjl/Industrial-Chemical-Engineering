package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E30 landing probe (batch 2026-11-04-open-thermo-data): is the full-budget steam resume still reached? The wet literature preset
 * (Ledezma-Martinez, 40 trays, three draws, sump steam, three pumparounds) and the three perturbations the resume was built for
 * (steam x0.5, steam x1.5, top 200 kPa, condenser +5 K). Prints "fix resume" lines; the label is read from tmp/o10-fix/label.txt.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class O10FixResumeProbe {
    static final String LABEL = label();
    static String label() {
        try { return Files.readString(Path.of("D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa/tmp/o10-fix/label.txt")).trim(); }
        catch (Exception e) { return "?"; }
    }

    static V3ColumnInput preset(double steamScale, double topPressure, double condenser) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl19_dwsim");
        V3CrudeFeed crude = thermo.crudeFeed("icheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 737.6996333000835;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 40, 37, topPressure, 0.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenser),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(0.0)),
                List.of(new V3SideDrawSpec(10, 491.0 / 3.6), new V3SideDrawSpec(18, 515.0 / 3.6),
                        new V3SideDrawSpec(28, 165.0 / 3.6)),
                List.of(new V3SteamFeedSpec(41, steamScale * 1_200.0 / 3.6, 533.15)),
                List.of(new V3PumparoundSpec(8, 10, -12.84e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(16, 18, -17.89e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(26, 28, -11.20e6, V3PumparoundSpec.Split.UNIFORM)), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    @Test void resumes() {
        for (double[] c : new double[][] {{1.0, 250_000.0, 332.15}, {0.5, 250_000.0, 332.15}, {1.5, 250_000.0, 332.15}, {1.0, 200_000.0, 332.15}, {1.0, 250_000.0, 337.15}}) {
            var polls = new java.util.concurrent.atomic.AtomicLong();
            long started = System.nanoTime();
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(preset(c[0], c[1], c[2]), polls::incrementAndGet, 0, 0,
                    V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
            long resumes = outcome.diagnostics().events().stream().filter(e -> e.contains("resuming the steam schedule")).count();
            StringBuilder sb = new StringBuilder(String.format("fix resume %s steam=%.1f top=%.0f Tc=%.2f: %s polls=%dk resumes=%d %.1f s path=%s",
                    LABEL, c[0], c[1], c[2], outcome instanceof V3ColumnOutcome.Success ? "ok" : "FAIL", polls.get() / 1000, resumes,
                    (System.nanoTime() - started) / 1e9, outcome.diagnostics().solvePath()));
            if (outcome instanceof V3ColumnOutcome.Success s) {
                for (var st : s.result().streams()) sb.append(String.format(" %s=%.4f", st.streamId(), st.massFlowKgPerSecond()));
                sb.append(String.format(" Qc=%.4f MW", s.result().dutyLedger().orElseThrow().condenserWatts() / 1e6));
            }
            System.out.println(sb);
        }
    }
}
