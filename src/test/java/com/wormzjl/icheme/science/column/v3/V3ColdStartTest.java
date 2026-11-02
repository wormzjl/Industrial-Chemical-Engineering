package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * Cold-start QA: every case gets a brand-new immutable input and invokes only {@link V3ColumnCalculator#calculate};
 * no accepted state, cache, or prior attempt seeds a later point. Numerical failure is recorded as a typed outcome
 * rather than pretending every in-range combination is feasible.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3ColdStartTest {
    private static final String CDU17 = "icheme:cdu17_tjl_acs2018";
    private static final long REAL_CRUDE_CASE_BUDGET_NANOS = 5_000_000_000L;
    private static final long LOW_PRESSURE_CASE_BUDGET_NANOS = 45_000_000_000L;

    /**
     * The registered two-stage PR pilot: topology and PC03/PC10 composition fixed while covering practical setup
     * edits exposed by the V3 screen.
     */
    @Test
    void reasonableSetupMatrixHasBoundedTypedColdOutcomes() {
        List<Case> matrix = reasonableMatrix();
        Map<String, V3ColumnOutcome> outcomes = new LinkedHashMap<>();
        StringBuilder report = new StringBuilder("V3 cold-start sweep\n");
        for (Case sample : matrix) {
            V3ColumnOutcome outcome = V3TestInputs.calculate(sample.input());
            outcomes.put(sample.name(), outcome);
            report.append(sample.name()).append(" | ").append(summary(outcome)).append('\n');
            if (outcome instanceof V3ColumnOutcome.Success success) {
                assertTrue(success.result().acceptanceAudit().accepted(), sample.name());
                assertTrue(success.result().convergenceEvidence().satisfiesGates(), sample.name());
            } else if (outcome instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, () -> sample.name() + ": " + failure);
                assertFalse(failure.code() == V3SolverFailureCode.INVALID_INPUT, () -> sample.name() + ": " + failure);
            }
        }
        long accepted = outcomes.values().stream().filter(V3ColumnOutcome::isSuccess).count();
        report.append("accepted=").append(accepted).append(" / ").append(matrix.size()).append('\n');
        System.out.println(report);
        assertTrue(outcomes.get("nominal") instanceof V3ColumnOutcome.Success, report::toString);
        assertTrue(accepted >= 5L, report::toString);
        assertEquals(matrix.size(), outcomes.size(), "Every cold-sweep case must have a unique stable name");
    }

    /** This old accepted root closes MESH by labelling a dense liquid as both phases. */
    @Test
    void nearby551KelvinCollapsedRootFailsTheFreshPhaseIdentityGate() {
        V3ColumnInput input = binaryPilot(100.0, 551.0, 250.0, 750.0, 300.0, 2.0, 0.0);

        V3ColumnOutcome outcome = V3TestInputs.calculate(input);
        var failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE, failure.code());
        assertTrue(failure.diagnostics().maximumScaledResidual() < 1e-8);
        assertTrue(failure.diagnostics().acceptanceAudit().checks().stream().anyMatch(c ->
                c.family().equals("INTERIOR_PHASE_IDENTITY") && !c.passed()));
    }

    /**
     * The V1-scale Tia Juana crude case: a per-case cooperative budget keeps the cold diagnostic bounded while the
     * report shows the initial residuals and the outcome of every tray count up to the 30-tray default.
     */
    @Test
    void v1ScaleRealCrudeColdMatrixReportsInitialResidualsAndBoundedOutcomes() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(CDU17);
        V3CrudeFeed crude = thermo.crudeFeed("icheme:tia_juana_light");
        List<RealCrudeCase> cases = List.of(
                new RealCrudeCase("two_stage", 2, 1),
                new RealCrudeCase("four_stage", 4, 3),
                new RealCrudeCase("eight_stage", 8, 6),
                new RealCrudeCase("fifteen_stage", 15, 12),
                new RealCrudeCase("v1_default_thirty_stage", 30, 24));
        List<String> report = new ArrayList<>();

        for (RealCrudeCase sample : cases) {
            V3ColumnInput input = v1ScaleInput(crude, sample.stageCount(), sample.feedStage());
            InitialResidual initial = initialResidual(thermo, input);
            Probe probe = runColdWithBudget(input);
            report.add(sample.name() + " stages=" + sample.stageCount()
                    + " initial(material=" + initial.material()
                    + ",equilibrium=" + initial.equilibrium()
                    + ",energy=" + initial.energy() + ") " + probe.summary());
            if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, report::toString);
            }
        }

        System.out.println("V3 real-crude cold matrix\n" + String.join("\n", report));
        assertTrue(report.size() == cases.size());
    }

    /** The client-reported low-top-pressure condenser operating region. */
    @Test
    void onePointFiveBarTotalCondenserConvergesWithoutOutletGas() {
        assertTotalCondenser(lowPressure(150_000.0));
    }

    @Test
    void onePointOneBarTotalCondenserConvergesWithoutOutletGas() {
        assertTotalCondenser(lowPressure(110_000.0));
    }

    private static void assertTotalCondenser(V3ColumnOutcome.Success success) {
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().solvePath().contains("continuation"));
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, success.result().problem().topology().condenserPhaseBranch());
        assertFalse(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")));
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("distillate_liquid")));
    }

    private static V3ColumnOutcome.Success lowPressure(double topPressurePascal) {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(lowPressureInput(topPressurePascal), () -> {
            if (System.nanoTime() - started >= LOW_PRESSURE_CASE_BUDGET_NANOS) {
                throw new AssertionError("low-pressure partial-condenser cold solve exceeded its budget");
            }
        }, 0, 0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(System.nanoTime() - started < LOW_PRESSURE_CASE_BUDGET_NANOS);
        return success;
    }

    private static V3ColumnInput lowPressureInput(double topPressurePascal) {
        V3CrudeFeed crude = V3PengRobinsonThermo.fromRegisteredPackage(CDU17).crudeFeed("icheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_000.0 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlowMolPerSecond;
        return V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, topPressurePascal, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static List<Case> reasonableMatrix() {
        List<Case> cases = new ArrayList<>();
        add(cases, "nominal", 100.0, 550.0, 250.0, 0.75, 300.0, 2.0, 0.0);

        // One-factor cold edits centred on the accepted pilot. Values reflect the exposed UI units.
        for (double flow : List.of(75.0, 125.0)) add(cases, "flow_" + flow, flow, 550.0, 250.0, 0.75, 300.0, 2.0, 0.0);
        for (double temperature : List.of(545.0, 548.0, 551.0, 552.0, 555.0)) {
            add(cases, "feed_k_" + temperature, 100.0, temperature, 250.0, 0.75, 300.0, 2.0, 0.0);
        }
        for (double pressure : List.of(225.0, 275.0)) add(cases, "top_kpa_" + pressure, 100.0, 550.0, pressure, 0.75, 300.0, 2.0, 0.0);
        for (double drop : List.of(0.0, 1.5)) add(cases, "drop_kpa_" + drop, 100.0, 550.0, 250.0, drop, 300.0, 2.0, 0.0);
        for (double condenser : List.of(295.0, 305.0)) add(cases, "condenser_k_" + condenser, 100.0, 550.0, 250.0, 0.75, condenser, 2.0, 0.0);
        for (double reflux : List.of(1.5, 2.5)) add(cases, "reflux_" + reflux, 100.0, 550.0, 250.0, 0.75, 300.0, reflux, 0.0);
        for (double duty : List.of(0.02, 0.05)) add(cases, "reboiler_mw_" + duty, 100.0, 550.0, 250.0, 0.75, 300.0, 2.0, duty);

        // Combined corners distinguish coupled thermal/pressure sensitivity from individual one-factor effects.
        add(cases, "low_corner", 75.0, 545.0, 225.0, 0.0, 295.0, 1.5, 0.0);
        add(cases, "high_corner", 125.0, 555.0, 275.0, 1.5, 305.0, 2.5, 0.0);
        add(cases, "cool_high_reflux", 125.0, 545.0, 250.0, 0.75, 295.0, 2.5, 0.0);
        add(cases, "hot_low_reflux", 75.0, 555.0, 250.0, 0.75, 305.0, 1.5, 0.0);
        return List.copyOf(cases);
    }

    private static void add(List<Case> cases, String name, double feedFlow, double feedTemperature,
            double topPressureKpa, double dropKpaPerStage, double condenserTemperature, double reflux,
            double reboilerDutyMegawatt) {
        cases.add(new Case(name, binaryPilot(feedFlow, feedTemperature, topPressureKpa, dropKpaPerStage * 1_000.0,
                condenserTemperature, reflux, reboilerDutyMegawatt * 1_000_000.0)));
    }

    /** The registered two-tray PC03/PC10 binary at the given conditions. */
    private static V3ColumnInput binaryPilot(double feedFlow, double feedTemperature, double topPressureKpa,
            double dropPascal, double condenserTemperature, double reflux, double reboilerDutyWatts) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(CDU17);
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = feedFlow / 2.0;
        feedFlows[13] = feedFlow / 2.0;
        return V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, feedTemperature, 2, 1, topPressureKpa * 1_000.0, dropPascal,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                        new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(reboilerDutyWatts)));
    }

    private static String summary(V3ColumnOutcome outcome) {
        return switch (outcome) {
            case V3ColumnOutcome.Success success -> "SUCCESS residual="
                    + success.diagnostics().maximumScaledResidual() + " iterations=" + success.diagnostics().newtonIterations();
            case V3ColumnOutcome.Failure failure -> failure.code() + " residual="
                    + failure.diagnostics().maximumScaledResidual() + " iterations=" + failure.diagnostics().newtonIterations();
        };
    }

    private static Probe runColdWithBudget(V3ColumnInput input) {
        long started = System.nanoTime();
        try {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
                if (System.nanoTime() - started >= REAL_CRUDE_CASE_BUDGET_NANOS) {
                    throw new CancellationException("cold diagnostic budget exceeded");
                }
            }, 0, 0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
            return new Probe(outcome, System.nanoTime() - started, false);
        } catch (CancellationException expectedBudgetExpiry) {
            return new Probe(null, System.nanoTime() - started, true);
        }
    }

    private static InitialResidual initialResidual(V3PengRobinsonThermo thermo, V3ColumnInput input) {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3DryMeshState state = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state();
        V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .evaluate(state, thermo.newWorkspace());
        return new InitialResidual(
                maximum(residual, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE),
                maximum(residual, V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM),
                maximum(residual, V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE));
    }

    private static double maximum(V3MeshResidual residual, V3DegreeOfFreedomLedger.EquationFamily family) {
        return residual.rows().stream()
                .filter(row -> row.equation().family() == family)
                .mapToDouble(row -> Math.abs(row.scaledValue()))
                .max().orElseThrow();
    }

    private static V3ColumnInput v1ScaleInput(V3CrudeFeed crude, int stages, int feedStage) {
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 365.0 + 273.15, stages, feedStage, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private record Case(String name, V3ColumnInput input) {}

    private record RealCrudeCase(String name, int stageCount, int feedStage) {}

    private record InitialResidual(double material, double equilibrium, double energy) {}

    private record Probe(V3ColumnOutcome outcome, long elapsedNanos, boolean budgetExceeded) {
        private String summary() {
            if (budgetExceeded) return "BUDGET_EXCEEDED elapsed_ms=" + elapsedNanos / 1_000_000L;
            return switch (outcome) {
                case V3ColumnOutcome.Success success -> "SUCCESS iter=" + success.diagnostics().newtonIterations()
                        + " residual=" + success.diagnostics().maximumScaledResidual()
                        + " elapsed_ms=" + elapsedNanos / 1_000_000L;
                case V3ColumnOutcome.Failure failure -> failure.code() + " iter="
                        + failure.diagnostics().newtonIterations() + " residual="
                        + failure.diagnostics().maximumScaledResidual() + " elapsed_ms=" + elapsedNanos / 1_000_000L;
            };
        }
    }
}
