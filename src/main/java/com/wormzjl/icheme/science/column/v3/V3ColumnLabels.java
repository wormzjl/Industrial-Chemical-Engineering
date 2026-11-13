package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The column's labels: the formulation and assumptions revisions an accepted result carries and its digest reads.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C1). Every string here
 * is part of a saved or cached identity: a change of one character is a revision change, never a refactor. Nothing
 * here reads a solve's state; each label is a function of the authored input, the cutoff and the closure.</p>
 */
public final class V3ColumnLabels {
    /** Cutoff-enabled publication identity, including physical phase admission and dry upper sections. */
    public static final String FORMULATION_REVISION = "v3-dry-mesh-r24-flash-trace-phase-r1-derived-pairs-r1";
    /**
     * Every label's derived-pairs suffix (batch 2026-11-11-aqueous-solvents, W5 D1): the kernel takes the network's
     * pairs ({@code DerivedPairs}) instead of the package's authored matrix, so an accepted state with a gas beside a
     * cut moves. The learned starts are matched on the label without it: they are seeds, and their physics binding
     * reads the authored pairs ({@code TrainedPhysics.matches}).
     */
    static final String DERIVED_PAIRS_SUFFIX = "-derived-pairs-r1";
    private static final String LEGACY_FORMULATION_REVISION = "v3-dry-mesh-r23";
    public static final String ASSUMPTIONS_REVISION = "v3-dry-assumptions-r4";
    /**
     * r2: free water is allowed on a tray, is immiscible with the hydrocarbon liquid, and is not side-drawn.
     * r3 (E31): under a drum that decants free water, tray one's free water is carried over to the drum as entrained
     * liquid instead of falling to tray two; with a column diameter the carried water is read against an
     * entrainment limit of flood fraction times vapour mass.
     */
    public static final String WET_ASSUMPTIONS_REVISION = "v3-wet-assumptions-r3";
    /**
     * Prescribed stage heat: a signed duty with positive adding heat, no circulating pumparound stream,
     * no return-temperature specification, and the {@link V3PumparoundSpec.Split} tray placement rule.
     */
    public static final String HEAT_ASSUMPTIONS_REVISION = "v3-heat-assumptions-r1";
    private static final String HEAT_FORMULATION_SUFFIX = "-stage-heat";

    private V3ColumnLabels() {}

    private static String legacyFormulationRevision(double requestedCutoff) {
        V3TruncationSupport.requireCutoff(requestedCutoff);
        return requestedCutoff == 0.0 ? LEGACY_FORMULATION_REVISION : "v3-dry-mesh-r24-flash-trace";
    }

    /**
     * Formulation label of one authored input.
     *
     * <p>r9 to r15 replaced r2 to r8 across every family: component material balances are scaled by their own
     * local throughput instead of by the component's feed flow, and every stage point whose flow is below
     * {@link V3TruncationSupport#TRACE_FLOOR_FRACTION} of that component's feed is removed from the unknowns
     * and equations. r16 to r22 made that presence per phase. r23 to r29 retire the forced product-path
     * band: the trays between the feed tray and a side draw are decided by the flow like any other tray, and
     * a draw tray keeps the liquid it receives from above. Accepted trace profiles differ at each step, so
     * the digest must differ.</p>
     */
    static String formulationRevision(V3ColumnInput input, double requestedCutoff) {
        return formulationRevision(input, requestedCutoff, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE);
    }

    /**
     * Formulation label of one authored input solved at {@code closureTolerance}.
     *
     * <p>A closure above the frozen default appends {@code -closure<mantissa>e<exponent>} (for example
     * {@code -closure1e-3}). Accepted states differ between closures — the whole point of the knob is that a
     * looser one accepts a state the default rejects — so the label, and therefore the digest, must differ.
     * The physical phase revision precedes this optional closure suffix.</p>
     */
    static String formulationRevision(V3ColumnInput input, double requestedCutoff, double closureTolerance) {
        return formulationRevisionWithoutClosure(input, requestedCutoff) + "-phase-r1" + DERIVED_PAIRS_SUFFIX
                + closureSuffix(closureTolerance);
    }

    /** The trained wet-stage seed equations are unchanged; dry-stage layouts need a newly qualified model. */
    static String initializationFormulationRevision(V3ColumnInput input) {
        return V3ColumnCalculator.specValue(input, V3ColumnSpecification.OrganicRefluxRatio.class) > 0.0
                ? formulationRevisionWithoutClosure(input, 0.0) : seedFormulationRevision(input);
    }

    /** The label a learned start was trained on: {@link #formulationRevision} at no cutoff, without the derived-pairs suffix. */
    static String seedFormulationRevision(V3ColumnInput input) {
        return formulationRevisionWithoutClosure(input, 0.0) + "-phase-r1";
    }

    /** Canonical {@code -closureMeN} suffix, or the empty string at the frozen default closure. */
    static String closureSuffix(double closureTolerance) {
        V3ConvergenceEvidence.requireClosure(closureTolerance);
        if (closureTolerance <= V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE) return "";
        int exponent = (int) Math.floor(DeterministicMath.log10(closureTolerance));
        double mantissa = closureTolerance / DeterministicMath.pow(10.0, exponent);
        String digits = new java.math.BigDecimal(mantissa)
                .round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
        return "-closure" + digits + "e" + exponent;
    }

    private static String formulationRevisionWithoutClosure(V3ColumnInput input, double requestedCutoff) {
        V3TruncationSupport.requireCutoff(requestedCutoff);
        boolean heat = !input.pumparounds().isEmpty();
        String trace = requestedCutoff > 0.0 ? "-flash-trace" : "";
        if (!input.steamFeeds().isEmpty()) {
            // r30/r31: a tray below the water dew point carries a free-water phase. Water vapour is a
            // function of the state rather than a parameter, wet trays gain a free-water unknown and a
            // saturation row, and the tray energy rows carry the aqueous liquid in and out.
            // r32/r33 (E30 of batch 2026-11-04-open-thermo-data): the water is the fluid network's, the steam its
            // second-virial gas at the steam's partial pressure and the liquid IF97 Region 1 at the tray's
            // pressure, so accepted wet states move by the steam's residual and the liquid's enthalpy difference.
            // r34/r35 (E31 of the same batch): condenser carry-over. Under a drum that decants free water, tray
            // one's free water leaves upward to the drum as entrained liquid, its vapour carries the steam less
            // that water, and its saturation row has a lever, so a top tray below its water dew point is solved
            // wet instead of published dry with the WATER_DEW_POINT warning; the condenser duty falls by the
            // carried water's latent heat.
            return (heat ? "v3-wet-mesh-r35-steam" : "v3-wet-mesh-r34-steam")
                    + (!input.sideDraws().isEmpty() ? "-side-draws" : "") + trace
                    + (heat ? HEAT_FORMULATION_SUFFIX : "");
        }
        if (!input.sideDraws().isEmpty()) {
            return (heat ? "v3-dry-mesh-r26-side-draws" : "v3-dry-mesh-r25-side-draws") + trace
                    + (heat ? HEAT_FORMULATION_SUFFIX : "");
        }
        if (!heat) return legacyFormulationRevision(requestedCutoff);
        return "v3-dry-mesh-r27" + trace + HEAT_FORMULATION_SUFFIX;
    }

    static String assumptionsRevision(V3ColumnInput input) {
        String base = input.steamFeeds().isEmpty() ? ASSUMPTIONS_REVISION : WET_ASSUMPTIONS_REVISION;
        return input.pumparounds().isEmpty() ? base : base + "+" + HEAT_ASSUMPTIONS_REVISION;
    }
}
