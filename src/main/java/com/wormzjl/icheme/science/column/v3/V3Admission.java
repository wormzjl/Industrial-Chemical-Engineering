package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import java.util.List;
import java.util.Objects;

import static com.wormzjl.icheme.science.column.v3.V3SolveReport.terminalFailure;

/**
 * The checks a request passes before any solve: the request-only gates that type it
 * {@code INFEASIBLE_SPECIFICATION} from the specification alone, and the typed refusals they build.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C1). Nothing here
 * reads a solve's state: the gates read the request, and the cooling bound one feed flash of it. The calculator
 * calls {@link #requestOnlyAdmission} on the classical route, and the liquid-supply screen and the cooling bound
 * directly on the learned route; its {@code requestOnlyAdmission} is an alias for the offline tools.</p>
 */
final class V3Admission {
    private V3Admission() {}

    /**
     * The verdict of the request-only admission, naming the gate that typed the request.
     *
     * <p>{@code gate} is {@code null} exactly when {@code failure} is, i.e. when the request is admitted.</p>
     */
    record RequestAdmission(String gate, V3ColumnOutcome.Failure failure) {
        /** The gate names published by {@link #requestOnlyAdmission}, in the order the solver applies them. */
        static final String TOTAL_DRAW_AT_LEAST_TOTAL_FEED = "totalDrawAtLeastTotalFeed";
        static final String LIQUID_SUPPLY_SCREEN = "liquidSupplyScreen";
        static final String STATIC_COOLING_ADMISSION = "staticCoolingAdmission";

        static final RequestAdmission ADMITTED = new RequestAdmission(null, null);

        /** Whether the request is typed {@code INFEASIBLE_SPECIFICATION} from the specification alone. */
        boolean typed() {
            return failure != null;
        }
    }

    /**
     * Every gate that types a request {@code INFEASIBLE_SPECIFICATION} from the specification alone.
     *
     * <p>This is the whole of the solver's request-only admission and the only place it is evaluated: the
     * ordinary {@code calculate} path calls exactly this method, so an offline population screen that calls it
     * reproduces production's verdict by construction rather than by a second implementation that can drift.
     * The gates run in the order a request meets them:</p>
     *
     * <ol>
     *   <li>{@code totalDraw >= totalFeed}, the column's overall material balance;</li>
     *   <li>{@link V3LiquidSupplyScreen}, the same balance per tray, at the authored calibrated ratio;</li>
     *   <li>{@link #staticCoolingAdmission}, the necessary bound on authored pumparound cooling.</li>
     * </ol>
     *
     * <p>No solve, continuation or Newton step is performed. The first two gates read nothing but the request;
     * the third evaluates one feed flash, and answers {@code ADMITTED} when the property package is
     * unavailable, so a caller never gets a cooling verdict it could not compute. The state-dependent heat
     * bounds are deliberately absent: they are measured on a continuation state and publish
     * {@code NONCONVERGENCE}. See {@code PathDependentHeatBound}.</p>
     *
     * @throws IllegalArgumentException if the screen ratio is nonfinite or outside [0, 1]
     */
    static RequestAdmission requestOnlyAdmission(V3ColumnInput input, double liquidSupplyScreenRatio) {
        Objects.requireNonNull(input, "input");
        V3LiquidSupplyScreen.requireRatio(liquidSupplyScreenRatio);
        double totalDraw = input.sideDraws().stream().mapToDouble(V3SideDrawSpec::molarFlowMolPerSecond).sum();
        double totalFeed = java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        if (totalDraw >= totalFeed) {
            return new RequestAdmission(RequestAdmission.TOTAL_DRAW_AT_LEAST_TOTAL_FEED,
                    terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION,
                            "V3 total side draw rate must be less than the feed rate",
                            "input/draws-" + input.sideDraws().size(), List.of()));
        }
        // Generalises the gate above from the column's total balance to each tray's own liquid supply, on the
        // same request-only terms and still before any flash or property evaluation.
        V3ColumnOutcome.Failure unsuppliedDraws = liquidSupplyScreen(input, liquidSupplyScreenRatio);
        if (unsuppliedDraws != null) return new RequestAdmission(RequestAdmission.LIQUID_SUPPLY_SCREEN, unsuppliedDraws);
        V3ColumnOutcome.Failure inadmissibleCooling = staticCoolingAdmission(input);
        if (inadmissibleCooling != null) {
            return new RequestAdmission(RequestAdmission.STATIC_COOLING_ADMISSION, inadmissibleCooling);
        }
        return RequestAdmission.ADMITTED;
    }

    /**
     * Request-only liquid-supply screen, evaluated before any flash or property evaluation.
     *
     * <p>Returns {@code null} when the request is admitted. A published failure reports zero Newton
     * iterations on the admission path, because none were spent: the statistic is a few dozen floating-point
     * operations on the authored draws, feed, steam, reflux and pumparound duties. See
     * {@link V3LiquidSupplyScreen} for the two tiers and for what "calibrated" does and does not claim.</p>
     */
    static V3ColumnOutcome.Failure liquidSupplyScreen(V3ColumnInput input, double liquidSupplyScreenRatio) {
        V3LiquidSupplyScreen.Verdict verdict = V3LiquidSupplyScreen.evaluate(input);
        // The measured wet-tray startup envelope does not cover the dry upper section of a zero-reflux
        // column. Its exact liquid-supply bound still applies; the newly supported topology is solved.
        if (V3ColumnCalculator.specValue(input, V3ColumnSpecification.OrganicRefluxRatio.class) == 0.0) liquidSupplyScreenRatio = 0.0;
        if (!verdict.fires(liquidSupplyScreenRatio)) return null;
        return terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION,
                V3LiquidSupplyScreen.detail(verdict, liquidSupplyScreenRatio),
                "input/liquid-supply-" + input.sideDraws().size(), List.of());
    }

    /**
     * Necessary static bound on the authored cooling, evaluated with one feed flash before any solve.
     *
     * <p>Returns {@code null} when the input is admissible or when the property package is unavailable; in the
     * latter case the ordinary admission path publishes the typed property failure instead.</p>
     *
     * <p>The bound is on the <em>net</em> authored heat: an authored stage heater adds enthalpy the coolers
     * below it can remove again, so its duty is credited to the heat-free column's budget. Without that
     * credit a cooler paired with an equal heater — no net stage heat anywhere — is rejected here.</p>
     */
    static V3ColumnOutcome.Failure staticCoolingAdmission(V3ColumnInput input) {
        if (input.pumparounds().isEmpty()) return null;
        double cooling = -V3PumparoundSpec.totalCoolingWatts(input);
        if (!(cooling > 0.0)) return null;
        double heating = V3HeatFeasibility.heatingCreditWatts(input);
        double available;
        try {
            available = V3HeatFeasibility.availableCoolingWatts(
                    input, V3PengRobinsonThermo.fromRegisteredPackage(input.packageId()));
        } catch (V3ThermoException | IllegalArgumentException unavailableProperties) {
            return null;
        }
        if (!Double.isFinite(available) || cooling <= available + heating) return null;
        return terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION,
                V3HeatFeasibility.staticAdmissionDetail(cooling, available, heating),
                V3ColumnOutcome.STATIC_COOLING_PATH + input.pumparounds().size(), List.of());
    }
}
