package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import java.util.List;
import java.util.Objects;

/**
 * The answer of a {@link FluidTpEquilibrium}: a status, the phases with their amounts over the shared basis, a
 * classification, a coverage grade with its evidence, and diagnostics.
 *
 * <p>Status rules (the constructor enforces them):</p>
 * <ul>
 *   <li>{@link Status#CONVERGED}: a classification other than {@link Classification#NONE}; at least one phase, except
 *   {@link Classification#PURE_COEXISTENCE_UNDERDETERMINED}, which has no phases and exactly two
 *   {@link #coexisting()} states (the split of a pure component at its own coexistence pressure is not determined by
 *   T and P, and none is invented); a coverage grade an answer can have.</li>
 *   <li>{@link Status#UNSUPPORTED}: a typed {@link UnsupportedReason}, no phases, grade {@link CoverageGrade#UNAVAILABLE}.</li>
 *   <li>{@link Status#NOT_CONVERGED}: no phases, grade {@link CoverageGrade#UNAVAILABLE}, diagnostics of the attempt.</li>
 * </ul>
 * <p>A solid phase must name a crystal of {@link #competition()}; a {@link PhaseCompetition#FLUID_ONLY} result therefore holds no competing crystal and {@link #solidsAssessed()} is false:
 * it is a constrained fluid answer, not a complete equilibrium.</p>
 */
public final class EquilibriumResult {
    public enum Status { CONVERGED, UNSUPPORTED, NOT_CONVERGED }

    public enum UnsupportedReason {
        /** The request needs a phase competition the package is not qualified for. */
        PHASE_COMPETITION_NOT_QUALIFIED,
        /** The request needs water chemistry (dissolution, aqueous non-ideality) no participation models. */
        WATER_CHEMISTRY_NOT_MODELLED,
        /** The contract admits the request but this service does not implement it yet (named milestone in the detail). */
        NOT_IMPLEMENTED
    }

    public enum Classification {
        /** One phase on the vapour root of a three-root cubic. */
        SINGLE_VAPOR,
        /** One phase on the liquid root of a three-root cubic. */
        SINGLE_LIQUID,
        /**
         * One phase with one physical root that is not a pure supercritical fluid; from P3 its phase label says whether
         * it is vapour-like or liquid-like (phase identification parameter), or {@link PhaseKind#FLUID} for a
         * near-critical split merged into one fluid.
         */
        SINGLE_FLUID,
        /** One pure component above its critical temperature and pressure. */
        SUPERCRITICAL_FLUID,
        /** Two fluid phases; the denser is labelled liquid, the lighter vapour. */
        VAPOR_LIQUID,
        /**
         * Two fluid phases both liquid-like by the phase identification parameter on their own roots
         * ({@code PIP > max(1, Z)}, P3 WP7c) and both denser than the cubic's critical volume
         * ({@link PhaseIdentification#denseLiquidLike}, P3 WP11), outside the declared critical band: both labelled
         * {@link PhaseKind#LIQUID}, the lighter first (P3 WP7d). A consumer with one hydrocarbon liquid slot (the fluid
         * network) holds on it with a typed reason.
         */
        LIQUID_LIQUID,
        /**
         * Three fluid phases of a list with water in the equation of state (batch 2026-11-11-aqueous-solvents, W3a D3): the
         * lightest (largest molar volume) labelled {@link PhaseKind#VAPOR}, then the two liquids labelled
         * {@link PhaseKind#LIQUID}, the lighter first; neither is named aqueous.
         */
        VAPOR_LIQUID_LIQUID,
        /** A pure component at its own coexistence pressure: two phases, split undetermined by T and P. */
        PURE_COEXISTENCE_UNDERDETERMINED,
        /**
         * At least one crystal among the phases and no vapour beside it (P4 stage 2a: a pure species deposited
         * completely is its crystal alone, as is the solid end of a pure sublimation balance).
         */
        SOLID_PRESENT,
        /**
         * One fluid phase that is not liquid-like (labelled {@link PhaseKind#VAPOR}, or {@link PhaseKind#FLUID} for a
         * one-root fluid) and one crystal (P4 stage 2a), in that order: gas-solid equilibrium, the crystal's chemical
         * potential equal to its species' in the vapour; {@link #deposition()} carries the solve.
         */
        VAPOR_SOLID,
        /**
         * One liquid-like fluid phase (labelled {@link PhaseKind#LIQUID}: a liquid on the liquid root of three, or a
         * one-root fluid that is liquid-like by its phase identification parameter, such as dense supercritical nitrogen
         * carrying the crystal's species) and one crystal (P5), in that order: solid-liquid equilibrium, the crystal's
         * chemical potential equal to its species' in the liquid; {@link #deposition()} carries the solve (the freezing
         * of the species out of the liquid, or a pure species' melting coexistence determined by a balance).
         */
        LIQUID_SOLID,
        /**
         * A vapour, a liquid and one crystal (P5), in that order: the fluid the crystal leaves is a stable vapour-liquid
         * split whose chemical potential of the crystal's species equals the crystal's. At fixed T and P a binary has
         * it only on its three-phase line (within the tolerance), a mixture of three or more species over a region;
         * PH and UV determine a binary's (and a pure species' triple point) split from their balances
         * ({@link Diagnostics#coexistenceFromBalance()}).
         */
        VAPOR_LIQUID_SOLID,
        /**
         * Two liquids and one crystal (W3b step 1, with water in the equation of state), the liquids lighter first, then the crystal: the fluid the crystal
         * leaves is a stable {@link #LIQUID_LIQUID} split, each liquid at the crystal's chemical potential of its species
         * (ice beside a water-rich and an oil-rich liquid).
         */
        LIQUID_LIQUID_SOLID,
        /**
         * A vapour, two liquids and one crystal (W3b step 1, with water in the equation of state), in that order: the fluid the crystal leaves is a stable
         * {@link #VAPOR_LIQUID_LIQUID} split at the crystal's chemical potential of its species.
         */
        VAPOR_LIQUID_LIQUID_SOLID,
        /** No answer. */
        NONE;

        /** Whether an answer of this classification holds a crystal among its phases (P4 stage 2a, P5). */
        public boolean holdsCrystal() {
            return this == SOLID_PRESENT || this == VAPOR_SOLID || this == LIQUID_SOLID || this == VAPOR_LIQUID_SOLID
                    || this == LIQUID_LIQUID_SOLID || this == VAPOR_LIQUID_LIQUID_SOLID;
        }
    }

    /**
     * How the crystal of a result with a solid phase was found (P4 stage 2a; P5 with a liquid): the solve's numbers.
     *
     * @param crystal the crystal id
     * @param component the species it is made of
     * @param feedDrivingForce {@code (mu_i - mu_s)/(R T)} of the species in the fluid answer of the whole feed, before
     *        any deposition: positive, the reason the crystal formed
     * @param residual {@code max |mu_i^fluid - mu_s|/(R T)} over the returned fluid phases; {@code NaN} for a complete
     *        deposition (the species is absent from the fluid) and for a coexistence from a balance
     * @param solidAmount moles in the crystal
     * @param complete whether the whole species deposited: the vapour holds none of it, because its equilibrium mole
     *        fraction lies below {@link FluidTpEquilibrium#DEPOSITION_TRACE} or no other species is carried (the vapour vanishes)
     * @param iterations Newton steps of the solve for the solid amount (zero for a complete deposition without one)
     * @param fluidEquilibria fluid TP equilibria the answer evaluated (the feed's, and the re-flashes of the reduced feed)
     */
    public record Deposition(String crystal, String component, double feedDrivingForce, double residual, double solidAmount,
                             boolean complete, int iterations, int fluidEquilibria) {
        public Deposition {
            Objects.requireNonNull(crystal, "crystal");
            Objects.requireNonNull(component, "component");
            if (!(solidAmount > 0.0) || !Double.isFinite(solidAmount)) throw new IllegalArgumentException("A deposition holds solid");
        }
    }

    /**
     * The plan's coverage vocabulary (section 3, decision D7), with {@link #EXTRAPOLATED} (owner rule of 2026-10-16): a
     * converged answer whose state lies outside a data range of the models it used (the package envelope, an ideal-gas
     * function's, the steam's or a crystal record's), on their continuations.
     */
    public enum CoverageGrade {
        QUALIFIED, ESTIMATED_DECLARED_ERROR, RESEARCH_ONLY, EXTRAPOLATED, UNAVAILABLE;

        /** Whether a converged answer may carry this grade. */
        public boolean answer() { return this == QUALIFIED || this == ESTIMATED_DECLARED_ERROR || this == RESEARCH_ONLY || this == EXTRAPOLATED; }
    }

    public record Coverage(CoverageGrade grade, String evidence) {
        public Coverage {
            Objects.requireNonNull(grade, "grade");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /**
     * What the calculation did.
     *
     * @param feedVerdict the stability verdict of the feed, {@code null} if no test ran
     * @param feedTangentPlaneDistance its most negative {@code tm}
     * @param flashIterations successive-substitution iterations of the two-phase flash
     * @param extrapolations accepted dominant-eigenvalue extrapolations among them
     * @param rachfordRiceIterations Newton/bisection steps over every Rachford-Rice solve
     * @param lnKChange {@code max |ln K_new - ln K_old|} of the last substitution iteration, or the last
     *        {@code max |ln f_i^V - ln f_i^L|} of the Newton finish when it ran
     * @param fugacityResidual {@code max |ln f_i^L - ln f_i^V|} of the returned phases, present components
     * @param productTangentPlaneDistance the most negative {@code tm} of the stability re-check of the product phases
     * @param kernelEvaluations every equation-of-state call: stability tests, flash, phase states
     * @param derivativeEvaluations the derivative calls among them
     * @param newtonIterations accepted steps of the second-order finish (P3), zero when substitution converged alone
     * @param tieLine {@code sum_i z_i (ln K_i)^2} of the last split (converged or not), {@code NaN} without one
     * @param criticalBand whether the state lies in the declared critical band (P3): a converged answer there is graded
     *        research-only, and a failure there is the typed {@code CRITICAL_BAND} not-converged result
     * @param outerIterations PH and UV (P3 WP6b): the TP equilibria the outer search evaluated (finite-difference
     *        evaluations included); zero for TP. The flash fields above are then those of the last TP equilibrium, and
     *        {@code kernelEvaluations}/{@code derivativeEvaluations} count the whole search.
     * @param specificationResidual PH: {@code |H - H_spec| / (N R T)}; UV: the larger of {@code |U - U_spec| / (N R T)}
     *        and {@code |ln(V / V_spec)|}, of the returned phases; {@code NaN} for TP and without phases
     * @param coexistenceFromBalance whether the answer is a coexistence that TP leaves undetermined (a pure component at
     *        its equal-fugacity point, or free water boiling at the state pressure) whose phase fraction came from the
     *        energy (PH) or energy and volume (UV) balance
     */
    public record Diagnostics(TangentPlaneStability.Verdict feedVerdict, double feedTangentPlaneDistance,
                              int flashIterations, int extrapolations, int rachfordRiceIterations, double lnKChange,
                              double fugacityResidual, double productTangentPlaneDistance, int kernelEvaluations,
                              int derivativeEvaluations, int newtonIterations, double tieLine, boolean criticalBand,
                              int outerIterations, double specificationResidual, boolean coexistenceFromBalance) {
        public static final Diagnostics NONE = new Diagnostics(null, Double.NaN, 0, 0, 0, Double.NaN, Double.NaN,
                Double.NaN, 0, 0, 0, Double.NaN, false);

        /** A TP calculation's diagnostics (no outer search). */
        public Diagnostics(TangentPlaneStability.Verdict feedVerdict, double feedTangentPlaneDistance,
                           int flashIterations, int extrapolations, int rachfordRiceIterations, double lnKChange,
                           double fugacityResidual, double productTangentPlaneDistance, int kernelEvaluations,
                           int derivativeEvaluations, int newtonIterations, double tieLine, boolean criticalBand) {
            this(feedVerdict, feedTangentPlaneDistance, flashIterations, extrapolations, rachfordRiceIterations, lnKChange,
                    fugacityResidual, productTangentPlaneDistance, kernelEvaluations, derivativeEvaluations,
                    newtonIterations, tieLine, criticalBand, 0, Double.NaN, false);
        }
    }

    /**
     * Why an answer is not one, typed: set where the answer is built, beside its detail, whose text it does not change.
     * The detail's typed prefixes ({@link #UNSTABLE_PRODUCT}, {@link #UNRESOLVED_PRODUCT}, {@link #CRYSTAL_PHASES},
     * {@link #CRYSTAL_STEP}) stay for the readers outside the engine; inside it the kind is read, never the text. A failure
     * inside the declared critical band keeps its own kind; the band is the flag {@link #failedInCriticalBand()} (the
     * detail then starts with {@link #CRITICAL_BAND} before the failure's own text).
     */
    public enum FailureKind {
        /** A converged answer. */
        NONE,
        /** An unsupported request ({@link #reason()} says which), other than {@link #CRYSTAL_PHASES}. */
        UNSUPPORTED,
        /** The equation of state or a property model refused a trial state of the solve (a {@code ThermoRefusal}). */
        REFUSED,
        /**
         * Another exception inside the solve: an argument, layout or invariant failure of the engine itself. Answered (the
         * detail reads as a refused evaluation, as before this kind existed) so that a defect does not take down the
         * caller's solve, and typed so that it is not read as a refusal.
         */
        INTERNAL,
        /** The request's state lies outside the numerical box. */
        OUTSIDE_BOX,
        /** The TP calculation did not converge: the feed's stability test, the two-phase flash or its Newton finish, the three-slot system. */
        FLASH,
        /** {@link #UNSTABLE_PRODUCT}: a converged split whose product the stability re-check proved unstable. */
        UNSTABLE_PRODUCT,
        /** {@link #UNRESOLVED_PRODUCT}: a converged split whose product's stability the re-check left unresolved. */
        UNRESOLVED_PRODUCT,
        /** A crystal solve (one crystal or two) did not converge. */
        CRYSTAL_SOLVE,
        /** {@link #CRYSTAL_PHASES}: a crystal beside a phase set the engine does not implement (an unsupported answer). */
        CRYSTAL_PHASES,
        /** {@link #CRYSTAL_STEP}: a crystal step of one major species that the pure species' step does not answer. */
        CRYSTAL_STEP,
        /** A PH or UV search ended without an answer (its budget, a step no state of the model has, the window). */
        SEARCH
    }

    /** The prefix of the detail of a not-converged result inside the declared critical band. */
    public static final String CRITICAL_BAND = "CRITICAL_BAND";

    /**
     * The prefix of the detail of a not-converged TP result (outside the band; inside it the detail starts with
     * {@link #CRITICAL_BAND} first) whose converged two-phase split has a product the stability re-check proved unstable:
     * a third fluid phase (or another split) is indicated, and P3 has no three-fluid-phase answer (P3 WP11).
     */
    public static final String UNSTABLE_PRODUCT = "UNSTABLE_PRODUCT";

    /**
     * The prefix of the detail of a not-converged TP result (outside the band; inside it the detail starts with
     * {@link #CRITICAL_BAND} first) whose converged two-phase split has a product whose stability the re-check left
     * unresolved, none proved unstable: a third fluid phase (or another split) is not excluded, so the split is no answer
     * either. The same failure as {@link #UNSTABLE_PRODUCT}, short of the proof.
     */
    public static final String UNRESOLVED_PRODUCT = "UNRESOLVED_PRODUCT";

    /**
     * The prefix of the detail of the typed hold of a crystal competition whose answer needs a phase set the engine does
     * not implement: on the free-water contract two liquids or three fluid phases beside a crystal (a split with a
     * product proved unstable included; with water in the equation of state both are answers, W3b step 1), three
     * crystals forming, or two crystals whose fluid is not answered. The result is {@link Status#UNSUPPORTED} with
     * {@link UnsupportedReason#NOT_IMPLEMENTED}, the fluid answer's diagnostics, and no phases.
     */
    public static final String CRYSTAL_PHASES = "CRYSTAL_PHASES";

    /**
     * The prefix of the detail of a not-converged PH or UV result (D21 option C) at a crystal step of the feed's one major
     * species (a pure feed, or a near-pure one) that the pure species' step does not answer: the search closed on that
     * step, or a near-pure feed's triple point leaves too little fluid for its traces. TP resolves no state there, so
     * another attempt at the same material meets the same step. The network holds on it at once instead of retrying.
     */
    public static final String CRYSTAL_STEP = "CRYSTAL_STEP";

    private final EquilibriumRequest.Specification specification;
    private final Status status;
    private final UnsupportedReason reason;
    private final String detail;
    private final ThermoIdentity identity;
    private final PhaseCompetition competition;
    private final Classification classification;
    private final List<PhaseAmounts> phases;
    private final List<PhaseState> coexisting;
    private final Coverage coverage;
    private final Diagnostics diagnostics;
    private final Deposition deposition;
    private final FailureKind failureKind;
    private final boolean failedInCriticalBand;

    public EquilibriumResult(EquilibriumRequest.Specification specification, Status status, UnsupportedReason reason,
                             String detail, ThermoIdentity identity,
                             PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                             List<PhaseState> coexisting, Coverage coverage, Diagnostics diagnostics) {
        this(specification, status, reason, detail, identity, competition, classification, phases, coexisting,
                coverage, diagnostics, null);
    }

    /**
     * A converged or unsupported result: its failure kind is {@link FailureKind#NONE} or {@link FailureKind#UNSUPPORTED}
     * by its status. A not-converged result names its kind ({@link #notConverged}); this constructor refuses one.
     *
     * @param deposition the gas-solid solve's numbers of a converged result with a crystal among its phases (P4 stage 2a;
     *        the engine attaches one to every such answer), otherwise {@code null}
     */
    public EquilibriumResult(EquilibriumRequest.Specification specification, Status status, UnsupportedReason reason,
                             String detail, ThermoIdentity identity,
                             PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                             List<PhaseState> coexisting, Coverage coverage, Diagnostics diagnostics,
                             Deposition deposition) {
        this(specification, status, reason, detail, identity, competition, classification, phases, coexisting, coverage,
                diagnostics, deposition, status == Status.CONVERGED ? FailureKind.NONE
                        : status == Status.UNSUPPORTED ? FailureKind.UNSUPPORTED : null, false);
    }

    /**
     * The engine's builders: a result with its failure kind, and whether its failure lies in the declared critical band.
     *
     * @param failureKind {@link FailureKind#NONE} exactly for a converged result; {@link FailureKind#UNSUPPORTED} or
     *        {@link FailureKind#CRYSTAL_PHASES} for an unsupported one
     * @param failedInCriticalBand whether a not-converged result's detail is typed {@link #CRITICAL_BAND}
     */
    EquilibriumResult(EquilibriumRequest.Specification specification, Status status, UnsupportedReason reason,
                      String detail, ThermoIdentity identity,
                      PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                      List<PhaseState> coexisting, Coverage coverage, Diagnostics diagnostics,
                      Deposition deposition, FailureKind failureKind, boolean failedInCriticalBand) {
        this.deposition = deposition;
        this.specification = Objects.requireNonNull(specification, "specification");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = reason;
        this.detail = Objects.requireNonNull(detail, "detail");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.competition = Objects.requireNonNull(competition, "competition");
        this.classification = Objects.requireNonNull(classification, "classification");
        this.phases = List.copyOf(phases);
        this.coexisting = List.copyOf(coexisting);
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if ((status == Status.UNSUPPORTED) != (reason != null)) throw new IllegalArgumentException("Exactly an unsupported result has a reason");
        for (PhaseState state : this.coexisting) {
            if (!state.frozen()) throw new IllegalArgumentException("A result holds frozen phase states");
        }
        if (status == Status.CONVERGED) {
            if (classification == Classification.NONE || !coverage.grade().answer()) {
                throw new IllegalArgumentException("A converged result is classified and graded as an answer");
            }
            boolean underdetermined = classification == Classification.PURE_COEXISTENCE_UNDERDETERMINED;
            if (underdetermined ? !this.phases.isEmpty() || this.coexisting.size() != 2
                    : this.phases.isEmpty() || !this.coexisting.isEmpty()) {
                throw new IllegalArgumentException("Pure coexistence has two coexisting states and no split; any other answer has phases");
            }
            int basis = -1;
            boolean solid = false;
            for (PhaseAmounts phase : this.phases) {
                if (basis >= 0 && phase.amountsView().length != basis) throw new IllegalArgumentException("Phases on different bases");
                basis = phase.amountsView().length;
                if (phase.kind() == PhaseKind.SOLID) {
                    if (!competition.crystals().contains(phase.crystal())) {
                        throw new IllegalArgumentException("Crystal " + phase.crystal() + " is outside the competition " + competition);
                    }
                    solid = true;
                }
            }
            if (solid != classification.holdsCrystal()) {
                throw new IllegalArgumentException("A crystal classification exactly when a crystal is among the phases");
            }
            if (deposition != null && !solid) throw new IllegalArgumentException("A deposition needs a crystal among the phases");
        } else {
            if (!this.phases.isEmpty() || !this.coexisting.isEmpty() || classification != Classification.NONE || deposition != null) {
                throw new IllegalArgumentException("Only a converged result has phases and a classification");
            }
            if (coverage.grade() != CoverageGrade.UNAVAILABLE) throw new IllegalArgumentException(status + " is graded " + CoverageGrade.UNAVAILABLE);
        }
        if (failureKind == null) throw new IllegalArgumentException("A not-converged result names its failure kind");
        if ((status == Status.CONVERGED) != (failureKind == FailureKind.NONE)
                || status == Status.UNSUPPORTED && failureKind != FailureKind.UNSUPPORTED && failureKind != FailureKind.CRYSTAL_PHASES
                || status == Status.NOT_CONVERGED && (failureKind == FailureKind.UNSUPPORTED || failureKind == FailureKind.CRYSTAL_PHASES)) {
            throw new IllegalArgumentException("A " + status + " result has no failure kind " + failureKind);
        }
        if (failedInCriticalBand && status != Status.NOT_CONVERGED) throw new IllegalArgumentException("Only a failure lies in the critical band");
        this.failureKind = failureKind;
        this.failedInCriticalBand = failedInCriticalBand;
    }

    public static EquilibriumResult unsupported(EquilibriumRequest.Specification specification, ThermoIdentity identity,
                                                PhaseCompetition competition, UnsupportedReason reason, String detail) {
        return new EquilibriumResult(specification, Status.UNSUPPORTED, Objects.requireNonNull(reason), detail,
                identity, competition, Classification.NONE, List.of(), List.of(),
                new Coverage(CoverageGrade.UNAVAILABLE, reason + ": " + detail), Diagnostics.NONE);
    }

    /**
     * A not-converged result of failure kind {@code failureKind}; {@code failedInCriticalBand} when its detail is typed
     * {@link #CRITICAL_BAND}.
     */
    public static EquilibriumResult notConverged(EquilibriumRequest.Specification specification, ThermoIdentity identity,
                                                 PhaseCompetition competition, FailureKind failureKind,
                                                 boolean failedInCriticalBand, String detail, Diagnostics diagnostics) {
        return new EquilibriumResult(specification, Status.NOT_CONVERGED, null, detail, identity, competition,
                Classification.NONE, List.of(), List.of(), new Coverage(CoverageGrade.UNAVAILABLE, detail), diagnostics, null,
                failureKind, failedInCriticalBand);
    }

    /** This result with another coverage (the engine grades an answer outside a data range, owner rule of 2026-10-16). */
    EquilibriumResult withCoverage(Coverage coverage) {
        return new EquilibriumResult(specification, status, reason, detail, identity, competition, classification, phases,
                coexisting, coverage, diagnostics, deposition, failureKind, failedInCriticalBand);
    }

    public EquilibriumRequest.Specification specification() { return specification; }
    public Status status() { return status; }
    public boolean converged() { return status == Status.CONVERGED; }
    /** The typed reason of an {@link Status#UNSUPPORTED} result, {@code null} otherwise. */
    public UnsupportedReason reason() { return reason; }
    public String detail() { return detail; }
    /** Why this result is no answer ({@link FailureKind#NONE} for a converged one): read this, not the detail's text. */
    public FailureKind failureKind() { return failureKind; }
    /** Whether a not-converged result failed inside the declared critical band (its detail starts with {@link #CRITICAL_BAND}). */
    public boolean failedInCriticalBand() { return failedInCriticalBand; }
    public ThermoIdentity identity() { return identity; }
    /** The competition the answer covers; a fluid-only answer says nothing about solids. */
    public PhaseCompetition competition() { return competition; }
    /** Whether solid phases took part: false for every {@link PhaseCompetition#FLUID_ONLY} answer. */
    public boolean solidsAssessed() { return status == Status.CONVERGED && !competition.crystals().isEmpty(); }
    public Classification classification() { return classification; }
    public List<PhaseAmounts> phases() { return phases; }
    /** The two coexisting states of {@link Classification#PURE_COEXISTENCE_UNDERDETERMINED}, empty otherwise. */
    public List<PhaseState> coexisting() { return coexisting; }
    public Coverage coverage() { return coverage; }
    public Diagnostics diagnostics() { return diagnostics; }
    /** The gas-solid solve of a result with a crystal among its phases (P4 stage 2a), {@code null} otherwise. */
    public Deposition deposition() { return deposition; }

    /**
     * {@code max_i |sum_p n_(p,i) - overall_i| / overall_i} over the basis, with the total overall amount as the scale
     * of a component the overall lacks (so any phase amount of it counts).
     *
     * @throws IllegalStateException without a phase split (not converged, or pure coexistence)
     */
    public double conservationDefect(double[] overall) {
        requireSplit();
        if (overall.length != phases.get(0).amountsView().length) throw new IllegalArgumentException("Overall basis mismatch");
        double total = 0.0;
        for (double amount : overall) total += amount;
        double worst = 0.0;
        for (int i = 0; i < overall.length; i++) {
            double sum = 0.0;
            for (PhaseAmounts phase : phases) sum += phase.amount(i);
            double scale = overall[i] > 0.0 ? overall[i] : total;
            worst = Math.max(worst, Math.abs(sum - overall[i]) / scale);
        }
        return worst;
    }

    /** Moles over every phase. */
    public double totalAmount() {
        requireSplit();
        double total = 0.0;
        for (PhaseAmounts phase : phases) total += phase.total();
        return total;
    }
    // The aggregates read each phase's balance book (W3a D2): the equilibrium book plus water's share, which is zero
    // unless water is a component of the cubic, so these equal the equilibrium book's sums bit for bit then.
    /** {@code sum_p n_p h_p}, J, on the balance book. */
    public double enthalpy() { return sum(Property.ENTHALPY); }
    /** {@code sum_p n_p u_p}, J, on the balance book. */
    public double internalEnergy() { return sum(Property.INTERNAL_ENERGY); }
    /** {@code sum_p n_p g_p}, J: the equilibrium book (no share enters the Gibbs energy). */
    public double gibbsEnergy() { return sum(Property.GIBBS); }
    /** {@code sum_p n_p v_p}, m3, on the balance book. */
    public double volume() { return sum(Property.VOLUME); }
    /** {@code sum_p n_p s_p}, J/K, on the balance book. */
    public double entropy() { return sum(Property.ENTROPY); }
    public double molarEnthalpy() { return enthalpy() / totalAmount(); }

    private enum Property { ENTHALPY, INTERNAL_ENERGY, GIBBS, VOLUME, ENTROPY }

    private double sum(Property property) {
        requireSplit();
        double sum = 0.0;
        for (PhaseAmounts phase : phases) {
            PhaseState state = phase.state();
            double molar = switch (property) {
                case ENTHALPY -> state.balanceEnthalpy();
                case INTERNAL_ENERGY -> state.balanceInternalEnergy();
                case GIBBS -> state.molarGibbsEnergy();
                case VOLUME -> state.balanceVolume();
                case ENTROPY -> state.balanceEntropy();
            };
            sum += phase.total() * molar;
        }
        return sum;
    }

    private void requireSplit() {
        if (status != Status.CONVERGED) throw new IllegalStateException("No phases: " + status + " (" + detail + ")");
        if (phases.isEmpty()) throw new IllegalStateException("Pure coexistence: the split is undetermined by T and P");
    }

    @Override
    public String toString() {
        return "EquilibriumResult[" + specification + " " + status + (reason == null ? "" : " " + reason) + ", "
                + classification + ", " + phases.size() + " phases, " + coverage.grade() + ", " + detail + ']';
    }
}
