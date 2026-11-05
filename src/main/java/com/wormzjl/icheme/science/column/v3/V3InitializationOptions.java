package com.wormzjl.icheme.science.column.v3;

import java.util.Objects;

/**
 * Immutable admission snapshot. Neural budgets never replace the caller's overall deadline.
 *
 * <p>{@code budgetPolls} bounds the learned route (binding, inference, anchor and correction) in control polls, the counter
 * every solve already checks, so whether a learned seed is used never depends on the machine's speed (owner decision D2 of
 * batch 2026-11-05-neural-retrain; it replaced a 2,000 ms allowance and a 100 ms probe for large wet columns).</p>
 */
public record V3InitializationOptions(Mode mode, WetStart wetStart, int maximumIterations, int budgetPolls,
        Correction correction) {
    public enum Mode { LNN_FIRST, LNN_ONLY, CURRENT_ONLY }
    public enum WetStart { AUTO, DRY_START, PREDICTED_WET }

    /**
     * How one learned correction attempt is allowed to spend Newton iterations.
     *
     * <p>A progress rule keeps the same base cap and, when an attempt reaches it, extends it in blocks
     * while the maximum scaled residual is still falling by {@code contractionFactor} over
     * {@code contractionWindow} iterations, never past {@code extensionMaximumIterations} and never past
     * the unchanged wall. {@code stallWindow}, {@code stallFactor} and {@code stallResidualFloor} are the
     * mirror image: an attempt whose residual has not fallen by that factor over that window stops there
     * instead of spending the rest of its cap. Extending and stopping are one rule, not two — the time the
     * stop returns is what pays for the time the extension spends.</p>
     *
     * @param extensionBlock iterations added per granted block
     * @param extensionMaximumIterations the cap an extended attempt may never exceed
     * @param contractionWindow iterations the extension's contraction test looks back over
     * @param contractionFactor the fraction of its earlier value the residual must have reached
     * @param stallWindow iterations the early stop looks back over
     * @param stallFactor the fraction an attempt must beat to continue
     * @param stallResidualFloor residual below which the early stop never fires
     */
    public record Correction(int extensionBlock, int extensionMaximumIterations, int contractionWindow,
            double contractionFactor, int stallWindow, double stallFactor, double stallResidualFloor) {
        /**
         * The qualified progress rule, and the learned default since the Transformer promotion.
         *
         * <p>Extension blocks of eight iterations up to a cap of forty-eight, and an early stop when the
         * maximum scaled residual has not fallen below nine tenths of its value eight iterations ago while
         * still above 1e-6. The base cap of sixteen is unchanged; the extension spends only what the poll budget allows.</p>
         *
         * <p>The contraction factor is <b>1.0</b>: an attempt that reaches its cap is extended unless its
         * residual actually rose over the window. The neural-budget study registered 0.5 — the residual had
         * to be still halving — and the LNN-gap campaign measured what that cost. Of the twenty traced
         * crawling trajectories the 0.5 gate admitted four; on the cleaned 330-input validation population
         * raising it to 1.0 is +2 strict LNN_ONLY and +2 strict LNN_FIRST in both blocks, three stable
         * gains against one stable loss, for 0.6 ms on the pooled all-case LNN_FIRST mean against a 119 ms
         * between-block noise band. The extension can still only spend time the attempt was already allowed
         * to spend, because the route's whole allowance (two seconds then, the poll budget now) did not move.</p>
         */
        public static final Correction PROGRESS = new Correction(8, 48, 8, 1.0, 8, 0.9, 1e-6);

        public Correction {
            if (extensionBlock < 1 || extensionMaximumIterations < 1 || contractionWindow < 1 || stallWindow < 1
                    || extensionMaximumIterations > V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS
                    || !Double.isFinite(contractionFactor) || contractionFactor < 0.0 || contractionFactor > 1.0
                    || !Double.isFinite(stallFactor) || stallFactor < 0.0 || stallFactor > 1.0
                    || !Double.isFinite(stallResidualFloor) || stallResidualFloor < 0.0) {
                throw new IllegalArgumentException("Invalid neural correction progress rule");
            }
        }
    }

    /**
     * The learned route's default budget: the anchor and inference plus sixteen Newton iterations of the 40-tray medium-grade preset
     * (about 8,000 polls each, O8), with a margin. Re-measured by counters in batch 2026-11-05-neural-retrain (N5).
     */
    public static final int DEFAULT_BUDGET_POLLS = 150_000;
    /** Ceiling of any authored budget: 20 M polls, about two minutes of solving. */
    public static final int MAXIMUM_BUDGET_POLLS = 20_000_000;

    /** The classical route. It runs no learned correction, so its progress rule is never consulted. */
    public static final V3InitializationOptions CURRENT =
            new V3InitializationOptions(Mode.CURRENT_ONLY, WetStart.DRY_START, 16, DEFAULT_BUDGET_POLLS, Correction.PROGRESS);

    public V3InitializationOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(wetStart, "wetStart");
        Objects.requireNonNull(correction, "correction");
        if (maximumIterations < 1 || maximumIterations > V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS
                || budgetPolls < 1 || budgetPolls > MAXIMUM_BUDGET_POLLS) {
            throw new IllegalArgumentException("Invalid neural initialization budget");
        }
        if (correction.extensionMaximumIterations() < maximumIterations) {
            throw new IllegalArgumentException("A neural correction extension cannot lower the base cap");
        }
    }
}
