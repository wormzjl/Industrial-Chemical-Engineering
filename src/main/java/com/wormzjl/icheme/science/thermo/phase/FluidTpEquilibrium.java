package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.material.CrystalReference;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Diagnostics;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import com.wormzjl.icheme.science.thermo.phase.CrystalSet.Crystal;
import com.wormzjl.icheme.science.thermo.phase.ThreeSlotFlash.Slots;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import java.util.List;
import java.util.Objects;

/**
 * TP equilibrium of fluid phases on a {@link CubicPhaseEvaluator}: the fluid phases by stability test and flash, water a
 * component of the cubic where the contract carries it ({@link WaterParticipation#IN_EQUATION_OF_STATE}), pure crystals
 * competing with the fluid phases; PH and UV search over the TP answers.
 *
 * <p>Hydrocarbon phases: contract checks ({@link #contractChecks}; a request asking for crystals on a service without
 * their evaluators is {@link UnsupportedReason#NOT_IMPLEMENTED}); tangent-plane stability of the feed
 * ({@link TangentPlaneStability} on the evaluator's kernel, where the constant translation cancels at one pressure).
 * Stable: one phase on the feed's lower-Gibbs root, classified from the root situation; a pure component with three
 * roots whose fugacities agree within {@link Settings#coexistenceTolerance()} is
 * {@link Classification#PURE_COEXISTENCE_UNDERDETERMINED}; a one-root phase is labelled by its phase identification
 * parameter ({@link PhaseIdentification}), except a pure component above its critical point
 * ({@link Classification#SUPERCRITICAL_FLUID}, {@link PhaseKind#FLUID}). Unstable: Rachford-Rice with successive
 * substitution on {@code ln K_i = ln phi_i^L - ln phi_i^V} from the stability test's stationary composition, each phase
 * on its own lower-Gibbs root, a dominant-eigenvalue extrapolation every {@link Settings#accelerationCycle()} steps,
 * solved in the minor phase's fraction so a trace phase keeps its precision; converged when a step moves no
 * {@code ln K_i} by {@link Settings#lnKTolerance()}. When substitution is slow ({@link #SLOW_SUBSTITUTION_STEPS} steps
 * with the eigenvalue above {@link #SLOW_EIGENVALUE}), past {@link Settings#substitutionLimit()}, collapsing onto the
 * feed or losing the split, a Newton minimises the Gibbs energy over the vapour mole numbers ({@code l_i = z_i - v_i}):
 * gradient {@code ln f_i^V - ln f_i^L}, Hessian {@code sum over both phases (delta_ij/n_i - 1/N + Phi_ij/N)} with the
 * kernel's {@code Phi_ij = d ln phi_i/d n_j}, scaled by {@code sqrt(v_i l_i / z_i)} and shifted by {@code mu I} until
 * Cholesky succeeds, the step cut so no amount shrinks below a tenth of itself, an Armijo line search on
 * {@code Delta G/RT} from the feed, which stays negative so the finish never reaches the trivial solution; converged at
 * {@code max |g_i| < }{@link #FUGACITY_TOLERANCE}. Amounts are split per component, the smaller share directly and the
 * larger by difference, so each component conserves to rounding. A converged split with
 * {@code sum z_i (ln K_i)^2 <} {@link Settings#mergeTieLine()} whose molar volumes differ by less than
 * {@link Settings#mergeVolumeGap()} is one fluid ({@link Classification#SINGLE_FLUID}, inside the critical band);
 * otherwise both products are re-tested, and an unstable product is {@link EquilibriumResult.Status#NOT_CONVERGED} with
 * the detail {@link EquilibriumResult#UNSTABLE_PRODUCT} or {@link EquilibriumResult#UNRESOLVED_PRODUCT}. The denser
 * phase is {@link PhaseKind#LIQUID} and the lighter {@link PhaseKind#VAPOR}; two liquid-like products denser than the
 * cubic's critical volume ({@link PhaseIdentification#denseLiquidLike}, {@code v < 3.9514 b}) outside the critical band
 * are {@link Classification#LIQUID_LIQUID}, the lighter first. An unresolved feed stability test is repeated once with
 * {@link #UNRESOLVED_STABILITY_BUDGET_FACTOR} times the per-trial budget.</p>
 *
 * <p>Declared critical band: a pure fluid (one component at least 0.95 of the amount) in the reduced boxes of
 * {@link #pureBandBox}; a split with {@code sum z_i (ln K_i)^2 <} {@link #BAND_TIE_LINE}; a single mixture phase whose
 * stability test met a stationary point other than the feed within {@link #BAND_STATIONARY_DISTANCE}
 * ({@link TangentPlaneStability.Result#nearestStationaryDistance()}); a failed split whose last iterate or stability
 * seed was that short. Inside it a converged answer is graded {@link EquilibriumResult.CoverageGrade#RESEARCH_ONLY} and
 * a failure is typed {@link EquilibriumResult#CRITICAL_BAND}. No split is fabricated and no single phase is reported
 * stable when the test said unstable.</p>
 *
 * <p>PH and UV are outer searches over TP answers inside the numerical box ({@link NumericalBox}; a specification
 * beyond it is {@code NOT_CONVERGED}, a numerical failure). PH: a bracketed search in {@code T} at fixed pressure
 * ({@code H(T)} increases for stable equilibria) from the ideal-gas temperature of the enthalpy; Newton with the phase's
 * {@code c_p} for one dry phase, a secant between answers with the same phases otherwise, bisection when two steps fail
 * to halve the bracket's smaller residual. UV: Newton on {@code (ln T, ln P)} for
 * {@code ((U - U_spec)/(N R T), ln(V/V_spec))} (the analytic Jacobian of one dry phase, else differences and Broyden's
 * update, a halving line search), started where the feed as one phase of the cubic has the specified energy at the
 * specified volume; when Newton stalls, a nested search in {@code T} at the specified volume, each temperature solving
 * {@code V(P) = V_spec} in {@code ln P}; a bracket that closes at the box's pressure bound with the energy still short is
 * {@code NOT_CONVERGED} naming that bound. Steps the TP answer leaves undetermined (a pure component at its
 * equal-fugacity point) are determined from the balance once a bracket straddles the jump,
 * {@code target = (1 - beta) x_1 + beta x_2}, at the kernel's equal-fugacity temperature or pressure:
 * {@link Classification#VAPOR_LIQUID} with {@link Diagnostics#coexistenceFromBalance()}. A target inside any other step is {@code NOT_CONVERGED}. Every search is
 * bounded ({@link Settings#maximumOuterIterations()}); near a critical point the searches take secants and brackets.</p>
 *
 * <p>Crystals: a service built with crystal evaluators ({@link PackageBinding#equilibrium}) answers the competitions its contract
 * qualifies ({@link PhaseCompetition#FLUID_AND_CRYSTALS} resolves to the package's set). Each crystal is anchored once,
 * at construction, to this evaluator's pure liquid at its triple point ({@link CrystalPhaseEvaluator.Anchored}). A
 * crystal's record continues past its data range; the lowest record ceiling of a competition ({@link #crystalCeiling},
 * 300 K for CO2-I) is where its crystals cease to exist; with water in the cubic ice is one of them. TP: the fluid answer of the feed, then each crystal against it by fugacity, {@code D = (mu_i - mu_s)/(R T)};
 * above {@link #DEPOSITION_TOLERANCE} the solid amount is solved so that the fluid the crystal leaves, re-flashed with
 * the stability test, has the crystal's chemical potential to {@code 1e-9}: the vapour-solid Newton on the reduced
 * feed's vapour root first ({@link CrystalEquilibrium#depositFromVapour}), else the general solve whose every iterate is a re-flash
 * ({@link CrystalEquilibrium#depositFromFluid}; {@link Classification#VAPOR_SOLID}, {@link Classification#LIQUID_SOLID},
 * {@link Classification#VAPOR_LIQUID_SOLID}, and, with water in the equation of state, beside two liquids
 * {@link Classification#LIQUID_LIQUID_SOLID} or {@link Classification#VAPOR_LIQUID_LIQUID_SOLID}, W3b step 1). A pure feed freezes or deposits completely
 * ({@link Classification#SOLID_PRESENT}), as does a species below {@link #DEPOSITION_TRACE} in the fluid. Two crystals
 * alternate their single solves; on a contract without water in the equation of state two liquids or three fluid phases beside the crystal, a
 * fluid with a product proved unstable beside it and three crystals are the typed {@link EquilibriumResult#CRYSTAL_PHASES} hold.
 * PH and UV answer the pure species' sublimation and melting steps, its triple point (UV) and a binary's three-phase
 * step from the balances; a start in a held region restarts from the competition's ceiling. Onsets
 * ({@link #depositionTemperature}, {@link #freezingTemperature}, {@link #depositionPressure}) are bracketed searches on
 * {@code D} of the fluid answer of the whole feed.</p>
 *
 * <p>Carried crystals ({@link PhaseCompetition#FLUID_CARRYING_CRYSTALS}, a step's decision: only a vessel's refresh books
 * a crystal, D23): no crystal competes, and the phase a crystal would take is no fluid phase of its own. A stationary
 * dense phase rich in a crystal's species is no phase of a dense fluid or of a split's product where that crystal would
 * form ({@link CarriedCrystals#carriedTrial}), nor is a dense phase with that species taken out; a vapour feed is never filtered, and a
 * split that ends with a crystal-rich product is restated ({@link CarriedCrystals#carriedSplit}): one liquid where both products are
 * dense, otherwise that product as a stand-in of crystal species only. Below the triple point the equation of state
 * continues vapour, liquid and frost as three fluid phases, a crystal-rich liquid and a lean one beside the vapour (3 % of
 * CO2 at 101 kPa, 113 to 129 K; the lean liquid saturates near 15 %), so no vapour-liquid family runs from one liquid to the
 * other there: the stand-in is held out and the rest split alone ({@link CarriedCrystals#beyondStandIn}).</p>
 *
 * <p>Deterministic (fixed arithmetic order; a reused {@link Workspace} carries nothing between calls but prepared
 * temperatures, recomputed bit-identically) and allocation-light: the iterations allocate nothing.</p>
 */
public final class FluidTpEquilibrium {
    static final String CONVERGED = "converged";
    static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** Fraction of the predicted decrease an accepted Newton step must achieve. */
    static final double ARMIJO = 1.0e-4;
    static final int NEWTON_HALVINGS = 40;
    /**
     * The diagonal shifts a shifted factorization tries, per attempt: zero, then {@code 10^(attempt - 9)}
     * ({@code 1e-8 .. 1e4}) or {@code 10^(attempt - 14)} ({@code 1e-13 .. 1e-1}), each the same {@code pow} call the
     * attempt loops made, formed once.
     */
    static final double[] SHIFTS_FROM_1E8 = shifts(9);
    static final double[] SHIFTS_FROM_1E13 = shifts(14);
    /** One Newton step leaves every mole number above {@code 1 - FRACTION_TO_BOUNDARY} of itself. */
    static final double FRACTION_TO_BOUNDARY = 0.9;
    /** Roundoff allowance on the Gibbs-energy decrease, relative to the magnitude of its summands. */
    static final double GIBBS_ROUNDOFF = 1.0e-15;

    /**
     * A feed whose stability test ends unresolved (a trial out of its budget without proving instability) is tested once
     * more with this many times the per-trial budget: next to a mixture critical point a trial can crawl towards the
     * trivial solution on a root whose Hessian is indefinite, where only substitution moves it.
     */
    public static final int UNRESOLVED_STABILITY_BUDGET_FACTOR = 10;

    // The PH and UV searches (P3 WP6b).
    /**
     * A converged PH or UV answer meets its specification to this many {@code N R T} in energy and in {@code ln V}; a
     * bracket that closed on a larger jump of the residual found a step of the function, not its root.
     */
    public static final double SPECIFICATION_TOLERANCE = 1.0e-6;
    /** Consecutive halvings back toward the last evaluated state when a trial state has no TP answer. */
    private static final int MAXIMUM_RETREATS = 8;
    /** Largest step of an unbracketed pressure search, and of one UV Newton step, in {@code ln P}; in {@code ln T}. */
    static final double PRESSURE_STEP_CAP = 2.0;
    private static final double TEMPERATURE_STEP_CAP = 0.25;
    /** The UV Newton phase's finite-difference step in {@code ln T} and {@code ln P}. */
    private static final double DIFFERENCE_STEP = 1.0e-7;
    private static final int NEWTON_UV_EVALUATIONS = 30;
    /**
     * TP equilibria of a warm-started UV Newton phase before the cold search takes over. 16, not 8: a vessel's crystal
     * beside a vapour and a liquid of three species moves between substeps by more than two Newton iterations can close
     * (warm answers 180 of 211 per interval at 16 against 2 of 211 at 8 on the pilot world's liquid methane flush).
     */
    public static final int WARM_UV_EVALUATIONS = 16;
    private static final int LINE_SEARCH_HALVINGS = 4;
    /** The nested UV search's loosest volume solve, in {@code ln V}, while the energy is still far off. */
    private static final double INNER_LOOSENESS = 1.0e-3;
    // Outcomes of the UV pressure search at one temperature.
    private static final int INNER_FOUND = 0;
    private static final int INNER_ABOVE = 1;
    private static final int INNER_BELOW = 2;
    private static final int INNER_FAILED = 3;

    /** The end of a fluid-only answer's coverage evidence; a crystal competition's answer replaces it (P4 stage 2a). */
    static final String FLUID_ONLY_NOTE = "; fluid-only: solid phases not assessed";
    /**
     * P4 stage 2a: a crystal forms when {@code (mu_i - mu_s)/(R T)} of its species in the fluid answer exceeds this, and
     * the gas-solid solve converges when {@code |mu_i - mu_s|/(R T)} is below it.
     */
    public static final double DEPOSITION_TOLERANCE = 1.0e-9;
    /**
     * P4 stage 2a: the vapour mole fraction of a crystal's species below which its deposition counts as complete (the
     * solid takes the whole species, the vapour none).
     */
    public static final double DEPOSITION_TRACE = 1.0e-15;
    /** A PH (relative in T) or UV (in ln P) bracket this narrow that straddles a binary's three-phase step is solved there (P5). */
    private static final double THREE_PHASE_BRACKET = 0.02;

    // Outcomes of the hydrocarbon core.
    static final int SINGLE = 0;
    static final int SPLIT = 1;
    static final int MERGED = 2;
    static final int FAILED = 3;
    /** W3a D3: three fluid phases present in the three-slot system. */
    static final int THREE = 4;

    /**
     * @param lnKTolerance a plain substitution step moving no {@code ln K_i} by more than this is converged
     * @param maximumIterations substitution iterations before {@link EquilibriumResult.Status#NOT_CONVERGED} (not reached
     *        while {@code substitutionLimit} is smaller: Newton takes over first)
     * @param accelerationCycle plain steps between two dominant-eigenvalue extrapolations
     * @param coexistenceTolerance relative pressure distance to a pure component's equal-fugacity pressure within
     *        which the state is pure coexistence
     * @param trivialDistance {@code sum ln K_i^2} below which substitution has collapsed onto the feed (Newton takes over)
     * @param stability settings of the stability tests
     * @param substitutionLimit substitution steps after which Newton takes over in any case
     * @param maximumNewtonIterations accepted Newton steps before {@link EquilibriumResult.Status#NOT_CONVERGED}
     * @param mergeTieLine a split with {@code sum z_i (ln K_i)^2} below this ...
     * @param mergeVolumeGap ... and molar volumes within this relative gap is merged into one fluid
     * @param maximumOuterIterations PH and UV: TP equilibria one search may evaluate (the PH temperature search and the
     *        UV Newton phase count TP equilibria, the UV Newton phase stopping after 30 unless its last step cut the
     *        residual fourfold; the nested UV temperature search counts its steps and each of its pressure searches its
     *        TP equilibria) before {@link EquilibriumResult.Status#NOT_CONVERGED}
     * @param outerTolerance PH and UV: converged when the next step moves {@code T} (relative) and {@code ln P} by
     *        at most this, or a bracket is this narrow (relative), with the specification met to
     *        {@link FluidTpEquilibrium#SPECIFICATION_TOLERANCE} of {@code N R T} (energy) and in {@code ln V}
     */
    public record Settings(double lnKTolerance, int maximumIterations, int accelerationCycle,
                           double coexistenceTolerance, double trivialDistance,
                           TangentPlaneStability.Settings stability,
                           int substitutionLimit, int maximumNewtonIterations,
                           double mergeTieLine, double mergeVolumeGap,
                           int maximumOuterIterations, double outerTolerance) {
        public static final Settings DEFAULT = new Settings(1.0e-10, 1000, 5, 1.0e-9, 1.0e-8,
                TangentPlaneStability.Settings.DEFAULT);

        public Settings {
            Objects.requireNonNull(stability, "stability");
            if (!(lnKTolerance > 0.0) || !Double.isFinite(lnKTolerance) || maximumIterations < 1 || accelerationCycle < 2
                    || !(coexistenceTolerance >= 0.0) || !Double.isFinite(coexistenceTolerance)
                    || !(trivialDistance > 0.0) || !Double.isFinite(trivialDistance) || substitutionLimit < 1
                    || maximumNewtonIterations < 1 || !(mergeTieLine >= 0.0)
                    || !(mergeVolumeGap >= 0.0) || maximumOuterIterations < 2 || !(outerTolerance > 0.0)
                    || !(outerTolerance < 1.0e-3)) {
                throw new IllegalArgumentException("Invalid equilibrium settings");
            }
        }

        /** The core settings with the defaults of the Newton finish, merging and the PH and UV searches. */
        public Settings(double lnKTolerance, int maximumIterations, int accelerationCycle, double coexistenceTolerance,
                        double trivialDistance, TangentPlaneStability.Settings stability) {
            this(lnKTolerance, maximumIterations, accelerationCycle, coexistenceTolerance, trivialDistance, stability,
                    50, 50, 1.0e-8, 1.0e-2, 100, 1.0e-11);
        }
    }

    /** Substitution steps after which a slow iteration (dominant eigenvalue estimate above {@link #SLOW_EIGENVALUE}) hands over to Newton. */
    public static final int SLOW_SUBSTITUTION_STEPS = 10;
    public static final double SLOW_EIGENVALUE = 0.9;
    /** Newton is converged at {@code max |ln f_i^V - ln f_i^L|} below this. */
    public static final double FUGACITY_TOLERANCE = 1.0e-10;
    /** A split with {@code sum z_i (ln K_i)^2} below this is in the critical band. */
    public static final double BAND_TIE_LINE = 0.1;
    /** A single mixture phase whose stability test met a stationary point within this {@code sum (ln W_i - ln z_i)^2} of the feed is in the critical band. */
    public static final double BAND_STATIONARY_DISTANCE = 0.1;

    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;
    private final PengRobinsonKernel kernel;
    private final TangentPlaneStability stability;
    private final Settings settings;
    private final int count;
    private final int basis;
    private final int[] basisOfComponent;
    /** W3a D1: water is a component of the cubic ({@link WaterParticipation#IN_EQUATION_OF_STATE}). */
    private final boolean inEquationOfState;
    /** The cubic's water component, -1 without one. */
    private final int cubicWater;
    private final int[] waterChemistry;
    private final double[] translations;
    private final EquilibriumResult.Coverage convergedCoverage;
    /** The crystals this service evaluates, each anchored once to this fluid family (S01), built once at construction. */
    private final CrystalSet crystalSet;
    /** The declared critical band (S01), built once at construction. */
    private final CriticalBand criticalBand;
    /** The dry core (S01): the feed's stability test, both flashes, the carried crystals and the dry answers, built once at construction. */
    private final DryCore dryCore;
    /** The crystals at TP (S01): the crystal solves, their coverage and holds, built once at construction. */
    private final CrystalEquilibrium crystalEquilibrium;
    /** The steps TP leaves undetermined, answered from the balances (S01), built once at construction. */
    private final BalanceSteps balanceSteps;
    /** The onset searches (S01), built once at construction. */
    private final CrystalOnsets crystalOnsets;

    /** @param evaluator its basis must be the contract's basis, in the same order, and its family the contract identity's */
    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator, Settings settings) {
        this(contract, evaluator, settings, List.of());
    }

    /**
     * @param crystalEvaluators the crystals this service evaluates (P4 stage 2a): each made of a species of the
     *        evaluator's basis, named by a qualified competition of the contract, with the same ideal-gas function as
     *        the evaluator's for its species (so the chemical potentials compare); each is anchored here, once, to this
     *        evaluator's pure liquid at its triple point. A qualified crystal without an evaluator here leaves the
     *        competitions naming it {@code NOT_IMPLEMENTED}.
     */
    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator, Settings settings,
                              List<CrystalPhaseEvaluator> crystalEvaluators) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!contract.identity().evaluatorFamily().equals(evaluator.family())) {
            throw new IllegalArgumentException("Contract identity names family " + contract.identity().evaluatorFamily()
                    + ", evaluator is " + evaluator.family());
        }
        inEquationOfState = contract.water() == WaterParticipation.IN_EQUATION_OF_STATE;
        if (!contract.components().equals(evaluator.components())) {
            throw new IllegalArgumentException("The evaluator basis must be the contract basis, in order");
        }
        count = evaluator.componentCount();
        basis = contract.components().size();
        basisOfComponent = new int[count];
        for (int i = 0; i < count; i++) basisOfComponent[i] = contract.index(evaluator.components().get(i));
        cubicWater = evaluator.waterComponent();
        waterChemistry = contract.waterChemistrySpecies().stream().mapToInt(contract::index).sorted().toArray();
        kernel = evaluator.kernel();
        translations = evaluator.translations();
        // W3a D3: a list with water in the cubic adds the water-rich trial; every other list keeps today's test.
        stability = new TangentPlaneStability(kernel, settings.stability(), cubicWater);
        convergedCoverage = new EquilibriumResult.Coverage(contract.fluidCoverage().grade(),
                contract.fluidCoverage().evidence() + FLUID_ONLY_NOTE);
        crystalSet = new CrystalSet(contract, evaluator, count, cubicWater, convergedCoverage, crystalEvaluators);
        criticalBand = new CriticalBand(evaluator);
        dryCore = new DryCore(this, contract, kernel, evaluator, settings, stability, count, basis, inEquationOfState,
                cubicWater, crystalSet, criticalBand);
        crystalEquilibrium = new CrystalEquilibrium(this, dryCore, contract, evaluator, settings, count, basis, convergedCoverage,
                crystalSet);
        balanceSteps = new BalanceSteps(this, crystalEquilibrium, contract, evaluator, kernel, criticalBand, crystalSet, count,
                basis, basisOfComponent, inEquationOfState, convergedCoverage);
        crystalOnsets = new CrystalOnsets(this, crystalEquilibrium, balanceSteps, contract, evaluator);
    }

    /**
     * The anchored crystal {@code id} of this service (its integration constants solved once at construction), or
     * {@code null} when the service has no evaluator for it: the one view of the crystal a consumer compares chemical
     * potentials with, so its answers and this service's agree (the fluid network's vessel refresh, D23).
     */
    public CrystalPhaseEvaluator.Anchored anchoredCrystal(String id) {
        Crystal c = crystalSet.crystalById(id);
        return c == null ? null : c.anchored();
    }

    public PhaseContract contract() { return contract; }
    public CubicPhaseEvaluator evaluator() { return evaluator; }
    public Settings settings() { return settings; }

    /** Caller-owned scratch for one thread; reusing it makes a call allocate little more than its result, and results do
     * not depend on what the workspace computed before. */
    public Workspace newWorkspace() { return new Workspace(this); }

    public EquilibriumResult tp(EquilibriumRequest request) { return tp(request, newWorkspace()); }
    public EquilibriumResult ph(EquilibriumRequest request) { return ph(request, newWorkspace()); }
    public EquilibriumResult uv(EquilibriumRequest request) { return uv(request, newWorkspace()); }

    /**
     * A feed the dry core solves (S02): the equation of state's amounts and the contract basis's, and the composition and
     * present list the core derives from them at its entry ({@link DryCore#hydrocarbon}). The core reads the feed it is handed
     * and no other: the workspace's {@code request}, or a crystal solve's reduced feed in a scratch feed of its own.
     */
    static final class Feed {
        final double[] amounts;
        final double[] basisAmounts;
        final double[] z;
        final int[] present;
        int presentCount;

        private Feed(int count, int basis) {
            amounts = new double[count];
            basisAmounts = new double[basis];
            z = new double[count];
            present = new int[count];
        }

        /** The amounts of {@code from} (both bases); the composition and present list are left to the core. */
        void takeAmounts(Feed from) {
            System.arraycopy(from.amounts, 0, amounts, 0, amounts.length);
            System.arraycopy(from.basisAmounts, 0, basisAmounts, 0, basisAmounts.length);
        }
    }

    /** Scratch for one thread. */
    public static final class Workspace {
        private final FluidTpEquilibrium owner;
        /** The request's material, set before any calculation and never overwritten by a solve. */
        final Feed request;
        /**
         * Scratch feeds of the crystal solves and the carried path: the fluid a crystal leaves (the base feed with the
         * crystal's species at its step's amount), the request less the partner crystal's solid ({@link CrystalEquilibrium#twoCrystals}),
         * and a feed less its crystal's stand-in ({@link CarriedCrystals#beyondStandIn}).
         */
        final Feed reducedFeed, pairFeed, standInFeed;
        final double[] logK, k, x, y, newLogK;
        double[] delta, previousDelta;
        final double[] liquidLogPhi, vaporLogPhi;
        final double[] liquidAmounts, vaporAmounts;
        final PengRobinsonKernel.Workspace mixture;
        final PengRobinsonKernel.Evaluation first, second;
        final TangentPlaneStability.Workspace stability;
        final CubicPhaseEvaluator.Workspace phases;
        /** The split's products: written only by a split's finish and its restatements, read as the dry core's outcome. */
        final PhaseState vaporState, liquidState;
        /**
         * Whether {@link #liquidState} and {@link #vaporState} hold the split of the dry solve now running or last run: cleared
         * at the entry of {@code hydrocarbon} (and by the stand-in's failure, whose states may be the rest's), set once a
         * split's own finish has written both ({@code finishSplit}, {@code slotOutcome}, the stand-in's restated split;
         * {@code carriedSplit} restates the split {@code finishSplit} wrote). {@code splitChemicalPotentialsUsable} judges
         * the pair only then.
         */
        boolean ownSplit;
        /**
         * Scratch states of the stages that evaluate a phase and copy it into their answer or read one value of it: the pure
         * coexistence, the pure crystal step and its fluid, the onsets' one-phase drive, and the single-phase and merged
         * builders. Never the split's products.
         */
        final PhaseState vaporScratch, liquidScratch;
        int kernelEvaluations, derivativeEvaluations, rachfordRiceSteps, iterations, extrapolations,
                newtonIterations;
        double lastChange;
        // The pressure of the hydrocarbon core.
        double pressure;
        // The Newton finish.
        final double[] vaporMoles, liquidMoles, trialVapor, trialLiquid, step, gradient, scale, reference, trial;
        final double[] hessian, factor, direction;
        final PengRobinsonKernel.Derivatives vaporDerivatives, liquidDerivatives;
        double lambda, tieLine, seedDistance, gibbsScale;
        boolean trialIsVapour;
        // The split Rachford-Rice found last (D24): each phase's fraction of the feed, the minor one solved for and the
        // major one its complement; liquidMinor when the liquid is the minor phase.
        double vapourFraction, liquidFraction;
        boolean liquidMinor;
        // The kernel evaluation lowerGibbsRoot chose last; whether each product of a dry split is liquid-like and denser than
        // the cubic's critical volume (P3 WP7d, WP11).
        PengRobinsonKernel.Evaluation chosen;
        boolean liquidProductDenseLiquidLike, vapourProductDenseLiquidLike;
        // Outcome of the hydrocarbon core.
        TangentPlaneStability.Result feedResult;
        Root singleRoot;
        String failure;
        double residual, productDistance;
        // The request's material, set before any calculation.
        double hydrocarbonTotal;
        private double total;
        // PH and UV: the search window, the searches, counters over the whole search, the Jacobian of the UV Newton phase.
        private double minimumT, maximumT, minimumP, maximumP;
        /** Where a PH or UV search starts: inside the data of the models the material uses (see {@link #searchWindow}). */
        private double startMinimumT, startMaximumT;
        final Search search = new Search();
        final Search inner = new Search();
        int outerEvaluations, outerKernel, outerDerivative;
        private final PhaseDerivatives slope;
        private final double[] jacobian = new double[4];
        private boolean jacobianValid;
        double coexistenceX, coexistenceResidual, bestT, bestResidual, innerPressure;
        private EquilibriumResult innerResult;
        private boolean innerCoexistence;
        private String innerFailure;
        // The nested UV search's pressure solves: accepted residual, whether the last was inexact, the last secant (of one
        // classification), whether the last answer was one phase.
        private double innerLooseness, innerSlope;
        private boolean innerLoose, innerSingle;
        // The last two pressure solves at different temperatures, (T, ln P): the next solve's start.
        private double innerT1, innerY1, innerT2, innerY2;
        // Crystals (P4 stage 2a): the request's resolved competition, the competing crystals, the solid and pure-species
        // scratch, fluid equilibria of the last crystal answer.
        PhaseCompetition competition;
        final Crystal[] active;
        int activeCount;
        // Carried crystals: the request's mode, the filter the stability tests read, its temperature and whether a product
        // is being tested, the tested phase's state (never liquidState or vaporState, which finishSplit reads after the tests).
        boolean carrying, carriedProduct, standInHeld;
        boolean beyondStandIn;
        double carriedTemperature;
        final double[] standIn;
        final TangentPlaneStability.TrialFilter carriedFilter;
        final PhaseState carriedState;
        final double[] pure;
        /** D21: the fluid a near-pure crystal step leaves, the traces with it. */
        final double[] stepFluid;
        final PhaseState solidState = new PhaseState(1);
        final PhaseState pureState;
        final PhaseDerivatives reduced;
        private final PhaseDerivatives solidSlope = new PhaseDerivatives(1);
        int fluidEquilibria;
        /** Two crystals at once (W3a D5): the partner held at its solid amount, and a second crystal met by a single solve. */
        Crystal paired, secondForming;
        double reducedSlope;
        // P5: the phases of a binary's three-phase point and of a pure species' triple point.
        final PhaseState threeLiquid, threeVapour;
        /** W3a D3: the three-slot system's scratch; null unless water is in the equation of state. */
        final Slots slots;

        private Workspace(FluidTpEquilibrium owner) {
            this.owner = owner;
            int n = owner.count;
            request = new Feed(n, owner.basis);
            reducedFeed = new Feed(n, owner.basis);
            pairFeed = new Feed(n, owner.basis);
            standInFeed = new Feed(n, owner.basis);
            logK = new double[n];
            k = new double[n];
            x = new double[n];
            y = new double[n];
            newLogK = new double[n];
            delta = new double[n];
            previousDelta = new double[n];
            liquidLogPhi = new double[n];
            vaporLogPhi = new double[n];
            liquidAmounts = new double[n];
            vaporAmounts = new double[n];
            mixture = owner.kernel.newWorkspace();
            first = owner.kernel.newEvaluation();
            second = owner.kernel.newEvaluation();
            stability = owner.stability.newWorkspace();
            phases = owner.evaluator.newWorkspace();
            vaporState = owner.evaluator.newState();
            liquidState = owner.evaluator.newState();
            vaporScratch = owner.evaluator.newState();
            liquidScratch = owner.evaluator.newState();
            vaporMoles = new double[n];
            liquidMoles = new double[n];
            trialVapor = new double[n];
            trialLiquid = new double[n];
            step = new double[n];
            gradient = new double[n];
            scale = new double[n];
            reference = new double[n];
            trial = new double[n];
            hessian = new double[n * n];
            factor = new double[n * n];
            direction = new double[n];
            vaporDerivatives = owner.kernel.newDerivativesWithoutHeatCapacity();
            liquidDerivatives = owner.kernel.newDerivativesWithoutHeatCapacity();
            slope = owner.evaluator.newDerivatives();
            active = new Crystal[owner.crystalSet.crystals.size()];
            carriedFilter = (tested, testedRoot, trial, trialRoot) -> owner.dryCore.carried.carriedTrial(tested, testedRoot, trial, trialRoot, this);
            carriedState = owner.evaluator.newState();
            standIn = new double[n];
            pure = new double[n];
            stepFluid = new double[n];
            pureState = owner.evaluator.newState();
            reduced = owner.evaluator.newDerivatives();
            threeLiquid = owner.evaluator.newState();
            threeVapour = owner.evaluator.newState();
            slots = owner.inEquationOfState ? new Slots(owner.dryCore.threeSlotFlash) : null;
        }
    }

    /**
     * A bracketed search for the root of an increasing function of one variable (P3 WP6b): the latest point with a
     * negative and with a positive residual, and the TP answers there. Its next point is the proposed Newton or secant
     * step while that stays strictly inside the bracket and the residual keeps shrinking (below half the last one at
     * least every second step), otherwise the bracket's midpoint; before a bracket exists, the step capped and kept
     * inside the window.
     */
    static final class Search {
        private double minimum, maximum;
        double lower, upper, lowerResidual, upperResidual;
        EquilibriumResult lowerResult, upperResult;
        private boolean lowerCoexistence, upperCoexistence;
        private double lastMagnitude;
        private int stalls;
        private boolean bisect;
        /** Attempts at a binary's three-phase point from this search's bracket (P5). */
        private int threePhaseAttempts;

        void reset(double minimum, double maximum) {
            threePhaseAttempts = 0;
            this.minimum = minimum;
            this.maximum = maximum;
            lower = upper = lowerResidual = upperResidual = Double.NaN;
            lowerResult = upperResult = null;
            lowerCoexistence = upperCoexistence = false;
            lastMagnitude = Double.NaN;
            stalls = 0;
            bisect = false;
        }

        /** {@code coexistence}: the answer at {@code x} is a coexistence from a balance (P3 WP6b), not a TP answer. */
        void record(double x, double residual, EquilibriumResult result, boolean coexistence) {
            double best = Math.min(Math.abs(lowerResidual), Math.abs(upperResidual));
            if (Double.isNaN(lowerResidual)) best = Math.abs(upperResidual);
            if (Double.isNaN(upperResidual)) best = Math.abs(lowerResidual);
            lastMagnitude = best;
            if (residual < 0.0) {
                lower = x;
                lowerResidual = residual;
                lowerResult = result;
                lowerCoexistence = coexistence;
            } else {
                upper = x;
                upperResidual = residual;
                upperResult = result;
                upperCoexistence = coexistence;
            }
            track(Math.abs(residual));
        }

        /** The bracket end with a result and the smaller residual, or null. */
        EquilibriumResult best() {
            if (lowerResult == null) return upperResult;
            if (upperResult == null) return lowerResult;
            return Math.abs(lowerResidual) <= Math.abs(upperResidual) ? lowerResult : upperResult;
        }

        boolean bestCoexistence() {
            EquilibriumResult best = best();
            return best != null && (best == lowerResult ? lowerCoexistence : upperCoexistence);
        }

        /**
         * Whether the ends hold one fluid phase each, labelled liquid at one end and vapour at the other (by the root of
         * three, or by the phase identification parameter of one), and every other phase the same: for a pure component
         * below its critical pressure, the jump of its coexistence lies between them. A dry feed's ends are the fluid
         * alone.
         */
        boolean straddlesPureCoexistence() {
            if (lowerResult == null || upperResult == null) return false;
            PhaseKind a = oneFluid(lowerResult);
            PhaseKind b = oneFluid(upperResult);
            return (a == PhaseKind.LIQUID && b == PhaseKind.VAPOR || a == PhaseKind.VAPOR && b == PhaseKind.LIQUID)
                    && sameBesideFluid(lowerResult, upperResult);
        }

        /**
         * Whether one end holds a crystal and the other is fluid alone (P4 stage 2a, D21): for the one major species of a
         * competing crystal, the jump of its crystal step lies between them. A pure feed's ends are each one phase (the
         * crystal alone against one fluid phase); a near-pure feed's crystal end also holds the fluid with the traces.
         */
        boolean straddlesCrystalStep() {
            if (lowerResult == null || upperResult == null) return false;
            return holdsSolid(lowerResult) != holdsSolid(upperResult);
        }

        /** Whether an end's answer holds a crystal (P4 stage 2a). */
        boolean endHoldsSolid() { return holdsSolid(lowerResult) || holdsSolid(upperResult); }

        /** A side known without a residual: the root lies below {@code x} ({@code above}) or above it. */
        void recordSide(double x, boolean above) {
            if (above) {
                upper = x;
                upperResidual = Double.NaN;
                upperResult = null;
                upperCoexistence = false;
            } else {
                lower = x;
                lowerResidual = Double.NaN;
                lowerResult = null;
                lowerCoexistence = false;
            }
            track(Double.NaN);
        }

        /**
         * Bisection after two bracketed steps in a row that did not halve the smaller residual of the bracket's ends as it
         * stood before the step (a side without a residual counts as no progress).
         */
        private void track(double magnitude) {
            if (bracketed()) {
                stalls = magnitude <= 0.5 * lastMagnitude ? 0 : stalls + 1;
                bisect = stalls >= 2;
            }
        }

        boolean bracketed() { return !Double.isNaN(lower) && !Double.isNaN(upper); }

        /** The secant through the bracket's ends (regula falsi), NaN without two residuals. */
        double bracketSlope() { return (upperResidual - lowerResidual) / (upper - lower); }

        double width() { return Math.abs(upper - lower); }

        double next(double x, double step, double cap) {
            if (bracketed()) {
                double a = Math.min(lower, upper);
                double b = Math.max(lower, upper);
                double candidate = x + step;
                if (bisect || !(candidate > a && candidate < b)) candidate = 0.5 * (a + b);
                return candidate;
            }
            if (!Double.isFinite(step)) return x;
            return Math.clamp(x + Math.clamp(step, -cap, cap), minimum, maximum);
        }
    }

    public EquilibriumResult tp(EquilibriumRequest request, Workspace workspace) {
        Workspace w = own(workspace);
        requireSpecification(request, Specification.TP);
        PhaseCompetition competition = request.competition();
        EquilibriumResult refused = contractChecks(request, w);
        if (refused != null) return refused;
        double t = request.temperature();
        double p = request.pressure();
        competition = w.competition;
        refused = implementationChecks(Specification.TP, competition, w);
        if (refused != null) return refused;
        refused = outsideBox(Specification.TP, t, p, competition);
        if (refused != null) return refused;
        return graded(evaluateTp(t, p, competition, w), null, w);
    }

    /**
     * PH: the temperature at the request's pressure whose TP equilibrium has the request's enthalpy (J, over all phases),
     * by the bracketed search of the class comment: Newton with the phase's {@code c_p} for one dry phase, secant
     * otherwise, bisection whenever a step leaves the bracket or the residual stops halving; a step of {@code H(T)} the
     * bracket closes on (a pure component's coexistence) is answered from the energy balance, any
     * other step is typed not converged.
     */
    public EquilibriumResult ph(EquilibriumRequest request, Workspace workspace) {
        Workspace w = own(workspace);
        requireSpecification(request, Specification.PH);
        PhaseCompetition competition = request.competition();
        EquilibriumResult refused = contractChecks(request, w);
        if (refused != null) return refused;
        double p = request.pressure();
        competition = w.competition;
        refused = implementationChecks(Specification.PH, competition, w);
        if (refused != null) return refused;
        refused = outsideBox(Specification.PH, NumericalBox.MINIMUM_TEMPERATURE, p, competition);
        if (refused != null) return refused;
        searchWindow(w);
        return graded(solvePh(p, request.enthalpy(), competition, w), null, w);
    }

    /**
     * UV: the temperature and pressure whose TP equilibrium has the request's internal energy (J) and volume (m3): the
     * Newton on {@code (ln T, ln P)} of the class comment and, when it does not converge, the nested bracketed search
     * ({@code U(T)} increasing at the fixed volume, {@code V(P)} decreasing at each temperature); a pure component
     * inside its two-phase dome closes the pressure bracket on the step of {@code V(P)} and the
     * phase fraction comes from the volume balance at the saturation pressure.
     */
    public EquilibriumResult uv(EquilibriumRequest request, Workspace workspace) {
        return uv(request, workspace, Double.NaN, Double.NaN);
    }

    /**
     * {@link #uv} warm-started from a previous answer's temperature and pressure (the fluid network's crystal
     * equilibration of a vessel already holding crystals): the UV Newton phase starts at
     * {@code (hintTemperature, hintPressure)} with at most {@link #WARM_UV_EVALUATIONS} TP equilibria; when it does not
     * converge there, the cold search of {@link #uv} runs with its own budget. The answer meets the same specification
     * and tolerance; the diagnostics' kernel counts include the warm attempt. A hint that is not finite is the cold search.
     */
    public EquilibriumResult uv(EquilibriumRequest request, Workspace workspace, double hintTemperature,
                                double hintPressure) {
        Workspace w = own(workspace);
        requireSpecification(request, Specification.UV);
        PhaseCompetition competition = request.competition();
        EquilibriumResult refused = contractChecks(request, w);
        if (refused != null) return refused;
        competition = w.competition;
        refused = implementationChecks(Specification.UV, competition, w);
        if (refused != null) return refused;
        searchWindow(w);
        return graded(solveUv(request.internalEnergy(), request.volume(), hintTemperature, hintPressure, competition, w), null, w);
    }

    /**
     * What this implementation does not do (crystals; water without a water model), then the request's material into
     * the workspace: the EOS feed, the hydrocarbon and water totals.
     */
    EquilibriumResult implementationChecks(Specification specification, PhaseCompetition competition, Workspace w) {
        w.activeCount = 0;
        w.carrying = competition.carried();
        for (String id : competition.crystals()) {
            Crystal c = crystalSet.crystalById(id);
            if (c == null) {
                return unsupported(specification, competition, UnsupportedReason.NOT_IMPLEMENTED,
                        "solid phases compete from P4 on; this service was built without the evaluator of crystal " + id);
            }
            w.active[w.activeCount++] = c;
        }
        double hydrocarbons = 0.0;
        for (int i = 0; i < count; i++) {
            w.request.amounts[i] = w.request.basisAmounts[basisOfComponent[i]];
            hydrocarbons += w.request.amounts[i];
        }
        w.hydrocarbonTotal = hydrocarbons;
        w.total = hydrocarbons;
        // The kernel carries the request's compounds at every entry: every phase and trial of this solve lives on them
        // (a crystal's reduced feed only lowers their amounts).
        w.mixture.carry(w.request.amounts);
        w.phases.carry(w.request.amounts);
        return null;
    }

    /**
     * A request state outside the numerical box ({@link NumericalBox}): not converged, a numerical failure (owner rule of
     * 2026-10-16: the box is where the mathematics stops being trusted, never a data range); null inside.
     */
    private EquilibriumResult outsideBox(Specification specification, double t, double p, PhaseCompetition competition) {
        if (NumericalBox.contains(t, p)) return null;
        return EquilibriumResult.notConverged(specification, contract.identity(), competition, "the state " + t + " K, " + p
                + " Pa lies outside the numerical box " + NumericalBox.MINIMUM_TEMPERATURE + " .. " + NumericalBox.MAXIMUM_TEMPERATURE
                + " K, " + NumericalBox.MINIMUM_PRESSURE + " .. " + NumericalBox.MAXIMUM_PRESSURE + " Pa", Diagnostics.NONE);
    }

    /** One TP equilibrium at (t, p) of the workspace's material, the checks done; its own counters. */
    EquilibriumResult evaluateTp(double t, double p, PhaseCompetition competition, Workspace w) {
        w.kernelEvaluations = 0;
        w.derivativeEvaluations = 0;
        w.rachfordRiceSteps = 0;
        w.iterations = 0;
        w.extrapolations = 0;
        w.newtonIterations = 0;
        w.lastChange = Double.NaN;
        w.tieLine = Double.NaN;
        w.feedResult = null;
        w.residual = Double.NaN;
        w.productDistance = Double.NaN;
        w.fluidEquilibria = 0;
        try {
            return w.activeCount > 0 ? crystalEquilibrium.solveWithCrystals(t, p, competition, w) : dryCore.solveDry(t, p, competition, w.request, w);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refusedByModel) {
            // The equation of state refused a state (no physical root, a mechanically unstable root): no answer.
            return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                    "evaluation refused: " + refusedByModel.getMessage(), diagnostics(w, w.feedResult, false));
        }
    }

    // Data ranges (owner rule of 2026-10-16).

    /**
     * {@code answer} graded {@link EquilibriumResult.CoverageGrade#EXTRAPOLATED} when it converged outside a data range of
     * the models it used ({@link #outsideData}), the range named in its evidence; any other answer as it is. Leaving a
     * data range never refuses a state: every model continues past its data, and the grade says so.
     *
     * @param onset the crystal of an onset answer (its record is used even where no crystal is among the phases), or null
     */
    EquilibriumResult graded(EquilibriumResult answer, Crystal onset, Workspace w) {
        if (!answer.converged()) return answer;
        PhaseState at = answer.phases().isEmpty() ? answer.coexisting().get(0) : answer.phases().get(0).state();
        String outside = outsideData(at.temperature(), at.pressure(), answer, onset, w);
        if (outside == null) return answer;
        return answer.withCoverage(new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.EXTRAPOLATED,
                answer.coverage().evidence() + "; extrapolated past " + outside));
    }

    /**
     * The data ranges a converged answer at {@code (t, p)} of the workspace's material lies outside, in words, or null
     * inside all of them: the package envelope ({@link PhaseDomain#covers}), the ideal-gas function of each carried
     * species, and the record of each crystal among the phases and of an
     * onset's crystal. Allocates only outside a range.
     */
    private String outsideData(double t, double p, EquilibriumResult answer, Crystal onset, Workspace w) {
        StringBuilder out = null;
        if (!contract.domain().covers(t, p)) {
            double[] envelope = contract.domain().envelope();
            out = note(out, "the package envelope " + envelope[0] + " .. " + envelope[1] + " K, " + envelope[2] + " .. "
                    + envelope[3] + " Pa");
        }
        for (int i = 0; i < count; i++) {
            if (!(w.request.amounts[i] > 0.0)) continue;
            IdealGasFunction gas = evaluator.idealGas(i);
            if (t < gas.minimumTemperature() || t > gas.maximumTemperature()) {
                out = note(out, "the ideal gas of " + evaluator.components().get(i) + " " + gas.minimumTemperature() + " .. "
                        + gas.maximumTemperature() + " K");
            }
        }
        for (PhaseAmounts phase : answer.phases()) {
            if (phase.kind() != PhaseKind.SOLID) continue;
            Crystal c = crystalSet.crystalById(phase.crystal());
            if (c != null && c != onset) out = crystalNote(out, c, t, p);
        }
        if (onset != null) out = crystalNote(out, onset, t, p);
        return out == null ? null : out.toString();
    }

    /** {@code out} with the crystal's record range appended when {@code (t, p)} lies outside it. */
    private static StringBuilder crystalNote(StringBuilder out, Crystal c, double t, double p) {
        var record = c.anchored().record();
        if (t < record.minimumTemperature() || t > record.maximumTemperature() || p < record.minimumPressure()
                || p > record.maximumPressure()) {
            out = note(out, "the record of " + c.id() + " " + record.minimumTemperature() + " .. " + record.maximumTemperature()
                    + " K, " + record.minimumPressure() + " .. " + record.maximumPressure() + " Pa");
        }
        return out;
    }

    private static StringBuilder note(StringBuilder out, String range) {
        return out == null ? new StringBuilder(range) : out.append(", ").append(range);
    }

    /**
     * The ceiling of the request's crystal competition: the lowest record maximum temperature of its crystals (300 K for
     * CO2-I), above which they cease to exist (P4 stage 2a). Not a data range: the onset searches stay below it, and a PH
     * or UV start that fails restarts there, where no crystal forms. {@code +infinity} without crystals.
     */
    static double crystalCeiling(Workspace w) {
        double ceiling = Double.POSITIVE_INFINITY;
        for (int a = 0; a < w.activeCount; a++) ceiling = Math.min(ceiling, w.active[a].anchored().record().maximumTemperature());
        return ceiling;
    }

    // PH and UV (P3 WP6b).

    /**
     * The search window: the numerical box ({@link NumericalBox}, where the mathematics stops; no data range bounds it);
     * resets the search's counters and state. A search starts inside the data of every model the material uses, where
     * they overlap (the package envelope, the carried species' ideal-gas functions and the steam's range), since the
     * first temperature is a lower bound from the ideal gas, which a latent heat can put far below the answer (35 K for a
     * frost answer at 150 K); from inside the data the search moves out only as far as the specification asks.
     */
    private void searchWindow(Workspace w) {
        w.minimumT = NumericalBox.MINIMUM_TEMPERATURE;
        w.maximumT = NumericalBox.MAXIMUM_TEMPERATURE;
        w.minimumP = NumericalBox.MINIMUM_PRESSURE;
        w.maximumP = NumericalBox.MAXIMUM_PRESSURE;
        w.outerEvaluations = 0;
        w.outerKernel = 0;
        w.outerDerivative = 0;
        w.jacobianValid = false;
        w.bestT = Double.NaN;
        w.bestResidual = Double.POSITIVE_INFINITY;
        w.innerPressure = Double.NaN;
        w.innerResult = null;
        w.innerCoexistence = false;
        w.innerFailure = null;
        w.innerLooseness = 0.0;
        w.innerSlope = 1.0;
        w.innerLoose = false;
        w.innerSingle = true;
        w.innerT1 = w.innerY1 = w.innerT2 = w.innerY2 = Double.NaN;
        // The composition and present list as the hydrocarbon core sets them (the band and the coexistence solves).
        w.request.presentCount = 0;
        for (int i = 0; i < count; i++) {
            w.request.z[i] = w.hydrocarbonTotal > 0.0 ? w.request.amounts[i] / w.hydrocarbonTotal : 0.0;
            if (w.request.amounts[i] > 0.0) w.request.present[w.request.presentCount++] = i;
        }
        double[] envelope = contract.domain().envelope();
        double low = envelope[0];
        double high = envelope[1];
        for (int a = 0; a < w.request.presentCount; a++) {
            IdealGasFunction gas = evaluator.idealGas(w.request.present[a]);
            low = Math.max(low, gas.minimumTemperature());
            high = Math.min(high, gas.maximumTemperature());
        }
        if (!(low <= high)) {
            low = envelope[0];
            high = envelope[1];
        }
        w.startMinimumT = Math.clamp(low, w.minimumT, w.maximumT);
        w.startMaximumT = Math.clamp(high, w.minimumT, w.maximumT);
    }

    /**
     * One TP equilibrium of a search, counted. A failure of a pure fluid inside its critical band (the kernel refusing a
     * root or a derivative at coalescence, where the plan promises no finite smooth derivatives) is typed
     * {@code CRITICAL_BAND}, so a search that ends on it says so.
     */
    private EquilibriumResult outerTp(double t, double p, PhaseCompetition competition, Workspace w) {
        EquilibriumResult result = evaluateTp(t, p, competition, w);
        w.outerEvaluations++;
        w.outerKernel += w.kernelEvaluations;
        w.outerDerivative += w.derivativeEvaluations;
        if (result.status() == EquilibriumResult.Status.NOT_CONVERGED && !result.diagnostics().criticalBand()
                && w.hydrocarbonTotal > 0.0 && criticalBand.inBand(t, p, w.request)) {
            Diagnostics d = result.diagnostics();
            result = EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                    EquilibriumResult.CRITICAL_BAND + " (critical band: pure fluid near its critical point): " + result.detail(),
                    new Diagnostics(d.feedVerdict(), d.feedTangentPlaneDistance(), d.flashIterations(), d.extrapolations(),
                            d.rachfordRiceIterations(), d.lnKChange(), d.fugacityResidual(),
                            d.productTangentPlaneDistance(), d.kernelEvaluations(), d.derivativeEvaluations(),
                            d.newtonIterations(), d.tieLine(), true));
        }
        return result;
    }

    private static boolean usable(EquilibriumResult result) {
        return result.converged() && result.classification() != Classification.PURE_COEXISTENCE_UNDERDETERMINED;
    }

    /**
     * {@code T} of {@code sum_i n_i h_ig,i(T) = target} (or of the internal energy {@code h_ig - R T}), water as ideal
     * steam, inside the data where the search starts ({@link #searchWindow}): the search's first temperature. Newton on the
     * ideal-gas functions only.
     */
    private double idealTemperature(double target, boolean internal, Workspace w) {
        double t = Math.clamp(298.15, w.startMinimumT, w.startMaximumT);
        for (int k = 0; k < 60; k++) {
            double f = -target;
            double slope = 0.0;
            for (int a = 0; a < w.request.presentCount; a++) {
                int i = w.request.present[a];
                IdealGasFunction gas = evaluator.idealGas(i);
                f += w.request.amounts[i] * gas.enthalpy(t);
                slope += w.request.amounts[i] * gas.heatCapacity(t);
            }
            if (internal) {
                f -= w.total * R * t;
                slope -= w.total * R;
            }
            if (!(slope > 0.0) || !Double.isFinite(f)) break;
            double next = Math.clamp(t - f / slope, w.startMinimumT, w.startMaximumT);
            boolean done = Math.abs(next - t) <= 1.0e-9 * t;
            t = next;
            if (done) break;
        }
        return t;
    }

    /** {@code sum_i n_i c_p,ig,i(T)}, water as steam; minus {@code N R} for the isochoric one: a positive slope scale. */
    private double idealHeatCapacity(double t, boolean isochoric, Workspace w) {
        double sum = 0.0;
        for (int a = 0; a < w.request.presentCount; a++) {
            int i = w.request.present[a];
            sum += w.request.amounts[i] * evaluator.idealGas(i).heatCapacity(t);
        }
        return isochoric ? sum - w.total * R : sum;
    }

    /** The derivatives of a dry answer with one phase (its own root), or null (several phases, water, a refusal). */
    private PhaseDerivatives dryDerivatives(EquilibriumResult result, double t, double p, Workspace w) {
        if (result.phases().size() != 1) return null;
        PhaseAmounts only = result.phases().get(0);
        if (only.kind() == PhaseKind.SOLID) {
            // A crystal alone (P4 stage 2a): its own c_p, expansion and compressibility.
            Crystal c = crystalSet.crystalById(only.crystal());
            if (c == null) return null;
            w.outerDerivative++;
            return c.anchored().derivatives(t, p, w.solidSlope);
        }
        w.outerKernel++;
        w.outerDerivative++;
        try {
            return evaluator.derivatives(t, p, w.request.amounts, result.phases().get(0).state().root(), w.phases, w.slope);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            // At root coalescence (a pure critical point) the kernel promises no derivative: the search takes a secant.
            return null;
        }
    }

    /** {@code (dH/dT)_P} of one dry phase, {@code NaN} otherwise. */
    private double dryHeatCapacity(EquilibriumResult result, double t, double p, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        return d == null ? Double.NaN : w.total * d.balanceHeatCapacity();
    }

    /** {@code (dU/dT)_V} of one dry phase, {@code NaN} otherwise. */
    private double dryIsochoricHeatCapacity(EquilibriumResult result, double t, Workspace w) {
        double p = result.phases().get(0).pressure();
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        if (d == null) return Double.NaN;
        double dvdt = d.balanceVolumeTemperatureDerivative();
        double dvdp = d.balanceVolumePressureDerivative();
        double dudt = d.balanceHeatCapacity() - p * dvdt;
        double dudp = d.balanceEnthalpyPressureDerivative() - d.state().balanceVolume() - p * dvdp;
        return w.total * (dudt - dudp * dvdt / dvdp);
    }

    /** {@code -(d ln V/d ln P)_T} of one dry phase, {@code NaN} otherwise. */
    private double dryCompressibility(EquilibriumResult result, double t, double p, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        return d == null ? Double.NaN : -p * d.balanceVolumePressureDerivative() / d.state().balanceVolume();
    }

    private double energyResidual(EquilibriumResult result, double target, double t, Workspace w) {
        return (result.internalEnergy() - target) / (w.total * R * t);
    }

    // PH.

    private EquilibriumResult solvePh(double p, double target, PhaseCompetition competition, Workspace w) {
        Search s = w.search;
        s.reset(w.minimumT, w.maximumT);
        double tolerance = settings.outerTolerance();
        double t = idealTemperature(target, false, w);
        double previousT = Double.NaN;
        double previousResidual = Double.NaN;
        int previousPhases = -1;
        double lastT = Double.NaN;
        EquilibriumResult last = null;
        int retreats = 0;
        int warmer = 0;
        while (true) {
            if (w.outerEvaluations >= settings.maximumOuterIterations()) {
                return searchFailure(Specification.PH, competition, "no convergence of the PH temperature search in "
                        + w.outerEvaluations + " TP equilibria (bracket " + s.lower + " .. " + s.upper + " K)", last, w);
            }
            EquilibriumResult result = outerTp(t, p, competition, w);
            if (!result.converged()) {
                if (Double.isNaN(lastT) && w.activeCount > 0 && t < crystalCeiling(w)) {
                    // P4 stage 2a: the ideal-gas start ignores a crystal's enthalpy and can land where a crystal meets a
                    // liquid (a typed hold); the search starts again from the competition's ceiling, where no crystal forms.
                    t = crystalCeiling(w);
                    continue;
                }
                if (Double.isNaN(lastT) && t < w.maximumT && ++warmer <= MAXIMUM_RETREATS) {
                    // The start is a lower bound of the answer's temperature (the ideal gas's, which a latent heat puts far
                    // below it): a start the equilibrium does not answer (a phase set it does not carry) lies below the
                    // answer, so the search starts again warmer.
                    t = Math.min(1.25 * t, w.maximumT);
                    continue;
                }
                if (Double.isNaN(lastT) || ++retreats > MAXIMUM_RETREATS) {
                    return outerAnswer(Specification.PH, result, target, Double.NaN, false,
                            "PH search: the TP equilibrium at " + t + " K", w);
                }
                t = 0.5 * (t + lastT);
                continue;
            }
            retreats = 0;
            double residual;
            boolean coexistence = false;
            if (result.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
                EquilibriumResult answer = holdsSolid(result) ? balanceSteps.pureCrystalStep(Specification.PH, t, p, target, false, competition, w)
                        : balanceSteps.pureCoexistence(Specification.PH, t, p, target, false, competition, w);
                if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
                if (Double.isNaN(w.coexistenceX)) {
                    return searchFailure(Specification.PH, competition, "no equal-fugacity temperature found near " + t
                            + " K at " + p + " Pa", result, w);
                }
                t = w.coexistenceX;
                residual = w.coexistenceResidual;
                result = null;
                coexistence = true;
            } else {
                residual = result.enthalpy() - target;
                last = result;
            }
            lastT = t;
            s.record(t, residual, result, coexistence);
            double slope = result == null ? Double.NaN : dryHeatCapacity(result, t, p, w);
            int phases = result == null ? -1 : result.phases().size();
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                // A secant only between answers with the same phases (across a bubble or dew point H(T) has a kink);
                // otherwise the bracket's own secant, before a bracket the ideal-gas heat capacity.
                slope = Double.isNaN(previousT) || previousT == t || phases != previousPhases ? Double.NaN
                        : (residual - previousResidual) / (t - previousT);
                if (!(slope > 0.0) || !Double.isFinite(slope)) slope = s.bracketSlope();
            }
            if (!(slope > 0.0) || !Double.isFinite(slope)) slope = idealHeatCapacity(t, false, w);
            previousPhases = phases;
            double step = -residual / slope;
            if (result != null && Math.abs(residual) <= SPECIFICATION_TOLERANCE * w.total * R * t
                    && Math.abs(step) <= tolerance * t) {
                return outerAnswer(Specification.PH, result, target, Double.NaN, false, null, w);
            }
            if (s.bracketed() && (s.width() <= tolerance * t || w.request.presentCount == 1 && s.straddlesPureCoexistence()
                    || balanceSteps.pureCrystal(w) != null && s.straddlesCrystalStep()
                    || balanceSteps.binaryCrystal(w) != null && threePhaseDue(s, s.width() / t)
                    || balanceSteps.binaryFluid(w) >= 0 && fluidThreeDue(s, s.width() / t))) {
                EquilibriumResult closed = closedPh(p, target, competition, w);
                if (closed != null) return closed;
                // The coexistence lies beyond the jump (its side is recorded), or was not met yet: the search goes on.
                if (!Double.isNaN(w.coexistenceX)) {
                    t = w.coexistenceX;
                    s.record(t, w.coexistenceResidual, null, true);
                }
                previousT = Double.NaN;
                t = s.next(t, Double.NaN, Double.POSITIVE_INFINITY);
                continue;
            }
            if (!s.bracketed()) {
                if (t <= s.minimum && residual > 0.0) {
                    return outsideWindow(Specification.PH, true, true,
                            "the specified enthalpy " + target + " J", competition, last, w);
                }
                if (t >= s.maximum && residual < 0.0) {
                    return outsideWindow(Specification.PH, true, false,
                            "the specified enthalpy " + target + " J", competition, last, w);
                }
            }
            previousT = t;
            previousResidual = residual;
            // With crystals (P4 stage 2a) an unbracketed step is capped: a vapour's c_p says nothing of the heat of deposition.
            t = s.next(t, step, w.activeCount > 0 ? TEMPERATURE_STEP_CAP * t : Double.POSITIVE_INFINITY);
        }
    }

    /**
     * The PH bracket closed (or straddles a pure component's liquid and vapour): the root, or a step of {@code H(T)}.
     * Returns the answer, or null when the search goes on: a pure coexistence lies beyond the jump (its side in
     * {@code coexistenceX} and {@code coexistenceResidual}), or the bracket is not closed yet and no equal-fugacity
     * temperature was met ({@code coexistenceX} NaN).
     */
    private EquilibriumResult closedPh(double p, double target, PhaseCompetition competition, Workspace w) {
        Search s = w.search;
        double t = 0.5 * (s.lower + s.upper);
        double jump = s.upperResidual - s.lowerResidual;
        boolean closed = s.width() <= settings.outerTolerance() * t;
        w.coexistenceX = Double.NaN;
        if (closed && jump <= SPECIFICATION_TOLERANCE * w.total * R * t && s.best() != null) {
            return outerAnswer(Specification.PH, s.best(), target, Double.NaN, s.bestCoexistence(), null, w);
        }
        boolean crystalStep = balanceSteps.pureCrystal(w) != null && s.endHoldsSolid();
        if (crystalStep || w.request.presentCount == 1) {
            EquilibriumResult answer = crystalStep ? balanceSteps.pureCrystalStep(Specification.PH, t, p, target, closed, competition, w)
                    : balanceSteps.pureCoexistence(Specification.PH, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        } else if (balanceSteps.binaryCrystal(w) != null && BalanceSteps.straddlesThreePhase(s)) {
            // P5: a binary's three-phase step (vapour, liquid and crystal at one temperature for the fixed pressure).
            EquilibriumResult answer = balanceSteps.binaryThreePhase(Specification.PH, s, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        } else if (balanceSteps.binaryFluid(w) >= 0 && BalanceSteps.straddlesFluidThree(s)) {
            // W3a D4: a binary's vapour-liquid-liquid step (at the fixed pressure, one temperature).
            EquilibriumResult answer = balanceSteps.binaryFluidThreePhase(Specification.PH, s, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        }
        // Not closed yet: the search goes on (the step is not where the enthalpy lies).
        if (!closed) return null;
        EquilibriumResult lever = balanceSteps.leverStep(Specification.PH, s, target, competition, w);
        if (lever != null) return outerAnswer(Specification.PH, lever, target, Double.NaN, true, null, w);
        return searchFailure(Specification.PH, competition, crystalStepType(crystalStep) + "the specified enthalpy " + target
                + " J lies in a step of H(T) at " + p + " Pa between " + s.lower + " and " + s.upper + " K (from "
                + (target + s.lowerResidual) + " to " + (target + s.upperResidual) + " J): no state of the model has it",
                s.lowerResult, w);
    }

    // UV.

    private EquilibriumResult solveUv(double u, double v, double hintT, double hintP, PhaseCompetition competition, Workspace w) {
        // P5, D21: inside the triple point's triangle of the feed's one major species the answer is that triple point (or,
        // near-pure with too little fluid for its traces, the typed hold), whatever a search would meet on its way there.
        Crystal major = balanceSteps.pureCrystal(w);
        if (major != null && BalanceSteps.nearTriangle(u, v, w.hydrocarbonTotal, major)) {
            EquilibriumResult triple = balanceSteps.pureTriplePoint(u, v, competition, w);
            if (triple != null) return triple;
        }
        if (hintT >= w.minimumT && hintT <= w.maximumT && hintP >= w.minimumP && hintP <= w.maximumP) {
            // P6 warm start: the Newton phase from the previous answer, on a short budget; the cold search otherwise.
            EquilibriumResult warm = newtonUv(hintT, hintP, null, u, v, competition, w, WARM_UV_EVALUATIONS);
            if (warm != null) return warm;
            w.outerEvaluations = 0;
            w.jacobianValid = false;
            w.bestT = Double.NaN;
            w.bestResidual = Double.POSITIVE_INFINITY;
        }
        // Both are lower bounds of a two-phase answer's temperature (the residual energy is negative, and a split lies
        // below the single phase's energy at the same temperature and volume); for one phase the first is the answer.
        double single = singlePhaseTemperature(u, v, w);
        double ideal = idealTemperature(u, true, w);
        double t = Math.clamp(Double.isNaN(single) ? ideal : Math.max(single, ideal), w.startMinimumT, w.startMaximumT);
        double p = cubicPressure(t, v, w);
        EquilibriumResult first = null;
        if (Double.isNaN(p)) {
            // No stable single phase at the start (two phases): an inexact volume solve there first, so the
            // Newton phase starts on the specified volume (a liquid's volume barely moves with pressure).
            w.innerLooseness = INNER_LOOSENESS;
            int outcome = innerUv(t, v, competition, w);
            if (outcome == INNER_FOUND && w.innerCoexistence) {
                w.bestT = t;
                return nestedUv(u, v, competition, w);
            }
            if (outcome == INNER_FOUND) first = w.innerResult;
            p = outcome == INNER_FOUND ? w.innerPressure : w.total * R * t / v;
        }
        p = Math.clamp(p, w.minimumP, w.maximumP);
        EquilibriumResult answer = newtonUv(t, p, first, u, v, competition, w, settings.maximumOuterIterations());
        return answer != null ? answer : nestedUv(u, v, competition, w);
    }

    /**
     * The mixture's {@code a}, {@code b} and {@code da/dT} at {@code t} into {@code w.first} (one kernel call at the
     * ideal-gas pressure of the specified volume; the three do not depend on pressure). False when the kernel refuses.
     */
    private boolean mixtureParameters(double t, double v, Workspace w) {
        try {
            kernel.evaluate(t, w.hydrocarbonTotal * R * t / v, w.request.amounts, Root.VAPOR, w.mixture, w.first);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return false;
        }
        w.outerKernel++;
        return true;
    }

    /** The untranslated molar volume of the dry feed at total volume {@code v}: the constant translation removed. */
    private double untranslatedVolume(double v, Workspace w) {
        double shift = 0.0;
        for (int a = 0; a < w.request.presentCount; a++) shift += w.request.z[w.request.present[a]] * translations[w.request.present[a]];
        return v / w.hydrocarbonTotal - shift;
    }

    /**
     * The UV start of dry material (P3 WP6b): the temperature at which the feed as one phase of the cubic, at the
     * specified molar volume, has the specified internal energy. Kernel calls only, no equilibrium: on the untranslated
     * volume {@code v_u} (the constant translation cancels from {@code u = h - P v}) the PR departure is closed-form,
     * {@code u^R = (a - T da/dT) / (2 sqrt2 b) ln[(v_u + (1 - sqrt2) b) / (v_u + (1 + sqrt2) b)]}, and
     * {@code u(T) = sum z_i h_ig,i(T) - R T + u^R} increases with {@code T} at fixed volume; a safeguarded secant on
     * the window. NaN when the volume is not above the covolume or the solve fails. For a single-phase answer this is
     * the answer's temperature; inside a two-phase region it is a start of the right size.
     */
    private double singlePhaseTemperature(double u, double v, Workspace w) {
        if (!(w.hydrocarbonTotal > 0.0)) return Double.NaN;
        double target = u / w.hydrocarbonTotal;
        double volume = untranslatedVolume(v, w);
        double low = w.minimumT;
        double high = w.maximumT;
        double t = Math.clamp(idealTemperature(u, true, w), low, high);
        double previousT = Double.NaN;
        double previousValue = Double.NaN;
        final double root2 = Math.sqrt(2.0);
        for (int k = 0; k < 100; k++) {
            if (!mixtureParameters(t, v, w)) return Double.NaN;
            double a = w.first.aMix();
            double b = w.first.bMix();
            if (!(volume > b)) return Double.NaN;
            double departure = (a - t * w.first.daMixDt()) / (2.0 * root2 * b)
                    * DeterministicMath.log((volume + (1.0 - root2) * b) / (volume + (1.0 + root2) * b));
            double value = departure - R * t - target;
            for (int s = 0; s < w.request.presentCount; s++) {
                int i = w.request.present[s];
                value += w.request.z[i] * evaluator.idealGas(i).enthalpy(t);
            }
            if (!Double.isFinite(value)) return Double.NaN;
            if (value < 0.0) low = t; else high = t;
            double next;
            if (!Double.isNaN(previousT) && value != previousValue) {
                next = t - value * (t - previousT) / (value - previousValue);
            } else {
                next = t - value / (idealHeatCapacity(t, true, w) / w.total);
            }
            if (!(next > low && next < high)) {
                // The root lies beyond an end of the window: that end is the start.
                if (value > 0.0 && t <= w.minimumT || value < 0.0 && t >= w.maximumT) return t;
                next = 0.5 * (low + high);
            }
            if (Math.abs(next - t) <= 1.0e-12 * t) return next;
            previousT = t;
            previousValue = value;
            t = next;
        }
        return Double.NaN;
    }

    /**
     * The cubic's pressure {@code R T/(v_u - b) - a/(v_u^2 + 2 b v_u - b^2)} of the dry feed as one phase at temperature
     * {@code t} and total volume {@code v}; NaN unless positive and mechanically stable ({@code dP/dv < 0}): the start
     * of a pressure search, exact for a stable single phase.
     */
    private double cubicPressure(double t, double v, Workspace w) {
        if (!(w.hydrocarbonTotal > 0.0) || !mixtureParameters(t, v, w)) return Double.NaN;
        double volume = untranslatedVolume(v, w);
        double a = w.first.aMix();
        double b = w.first.bMix();
        if (!(volume > b)) return Double.NaN;
        double pressure = PengRobinsonKernel.cubicPressure(t, volume, a, b);
        double slope = PengRobinsonKernel.pressureVolumeDerivative(t, volume, a, b);
        return pressure > 0.0 && slope < 0.0 && Double.isFinite(pressure) ? pressure : Double.NaN;
    }

    /**
     * Newton on {@code x = (ln T, ln P)} for {@code F = ((U - U_spec)/(N R T), ln(V/V_spec))}: the analytic Jacobian of one
     * dry phase, otherwise finite differences once and Broyden's update after each step (refreshed by differences when
     * a step fails), the step capped in {@code ln T} and {@code ln P} and kept in the window, halvings until
     * {@code |F|^2} decreases. Null when it does not converge within its budget: the nested search takes over from its
     * best temperature.
     */
    private EquilibriumResult newtonUv(double t, double p, EquilibriumResult start, double u, double v,
                                       PhaseCompetition competition, Workspace w, int budget) {
        double tolerance = settings.outerTolerance();
        EquilibriumResult current = start != null ? start : outerTp(t, p, competition, w);
        if (!usable(current)) return null;
        double f0 = energyResidual(current, u, t, w);
        double f1 = DeterministicMath.log(current.volume() / v);
        noteBest(t, f0, f1, w);
        w.jacobianValid = false;
        boolean refresh = false;
        double[] j = w.jacobian;
        double lastRatio = 0.0;
        // Beyond NEWTON_UV_EVALUATIONS TP equilibria only while the last accepted step cut |F|^2 at least fourfold.
        while (w.outerEvaluations < budget && (w.outerEvaluations < NEWTON_UV_EVALUATIONS || lastRatio <= 0.25)) {
            boolean fresh = true;
            if (!analyticJacobian(current, t, p, f0, w)) {
                if (!w.jacobianValid || refresh) {
                    if (!differenceJacobian(t, p, u, v, f0, f1, competition, w)) return null;
                } else {
                    fresh = false;
                }
            }
            refresh = false;
            double determinant = j[0] * j[3] - j[1] * j[2];
            double d0 = -(j[3] * f0 - j[1] * f1) / determinant;
            double d1 = -(j[0] * f1 - j[2] * f0) / determinant;
            if (!Double.isFinite(d0) || !Double.isFinite(d1)) return null;
            double size = Math.max(Math.abs(d0), Math.abs(d1));
            boolean met = Math.abs(f0) <= SPECIFICATION_TOLERANCE && Math.abs(f1) <= SPECIFICATION_TOLERANCE;
            if (met && size <= tolerance) return outerAnswer(Specification.UV, current, u, v, false, null, w);
            double x0 = DeterministicMath.log(t);
            double x1 = DeterministicMath.log(p);
            double alpha = Math.min(1.0, Math.min(TEMPERATURE_STEP_CAP / Math.abs(d0), PRESSURE_STEP_CAP / Math.abs(d1)));
            alpha = Math.min(alpha, windowFraction(x0, d0, DeterministicMath.log(w.minimumT), DeterministicMath.log(w.maximumT)));
            alpha = Math.min(alpha, windowFraction(x1, d1, DeterministicMath.log(w.minimumP), DeterministicMath.log(w.maximumP)));
            if (!(alpha > 0.0)) return null;
            double norm = f0 * f0 + f1 * f1;
            boolean accepted = false;
            double tt = t;
            double pt = p;
            double g0 = Double.NaN;
            double g1 = Double.NaN;
            EquilibriumResult trial = null;
            for (int halving = 0; halving <= LINE_SEARCH_HALVINGS && w.outerEvaluations < budget; halving++, alpha *= 0.5) {
                tt = DeterministicMath.exp(x0 + alpha * d0);
                pt = DeterministicMath.exp(x1 + alpha * d1);
                trial = outerTp(tt, pt, competition, w);
                if (!usable(trial)) continue;
                g0 = energyResidual(trial, u, tt, w);
                g1 = DeterministicMath.log(trial.volume() / v);
                noteBest(tt, g0, g1, w);
                if (g0 * g0 + g1 * g1 <= (1.0 - 1.0e-4 * alpha) * norm) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                // At the noise floor of the TP answers a converged point cannot be improved.
                if (met && size <= 10.0 * tolerance) return outerAnswer(Specification.UV, current, u, v, false, null, w);
                if (!fresh) {
                    refresh = true;
                    continue;
                }
                return null;
            }
            // Broyden's update for the next point when it is not one dry phase.
            double s0 = DeterministicMath.log(tt) - x0;
            double s1 = DeterministicMath.log(pt) - x1;
            double squared = s0 * s0 + s1 * s1;
            double r0 = g0 - f0 - (j[0] * s0 + j[1] * s1);
            double r1 = g1 - f1 - (j[2] * s0 + j[3] * s1);
            j[0] += r0 * s0 / squared;
            j[1] += r0 * s1 / squared;
            j[2] += r1 * s0 / squared;
            j[3] += r1 * s1 / squared;
            w.jacobianValid = true;
            lastRatio = (g0 * g0 + g1 * g1) / norm;
            current = trial;
            t = tt;
            p = pt;
            f0 = g0;
            f1 = g1;
        }
        return null;
    }

    private static void noteBest(double t, double f0, double f1, Workspace w) {
        double norm = f0 * f0 + f1 * f1;
        if (norm < w.bestResidual) {
            w.bestResidual = norm;
            w.bestT = t;
        }
    }

    /** The largest fraction of the step {@code d} from {@code x} that stays in {@code [low, high]}. */
    private static double windowFraction(double x, double d, double low, double high) {
        if (d > 0.0) return (high - x) / d;
        if (d < 0.0) return (low - x) / d;
        return Double.POSITIVE_INFINITY;
    }

    /** The UV Jacobian of one dry phase from its derivatives; false otherwise. */
    private boolean analyticJacobian(EquilibriumResult result, double t, double p, double f0, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        if (d == null) return false;
        double volume = d.state().balanceVolume();
        double dvdt = d.balanceVolumeTemperatureDerivative();
        double dvdp = d.balanceVolumePressureDerivative();
        double rt = R * t;
        w.jacobian[0] = t * (d.balanceHeatCapacity() - p * dvdt) / rt - f0;
        w.jacobian[1] = p * (d.balanceEnthalpyPressureDerivative() - volume - p * dvdp) / rt;
        w.jacobian[2] = t * dvdt / volume;
        w.jacobian[3] = p * dvdp / volume;
        w.jacobianValid = true;
        return true;
    }

    /** The UV Jacobian by forward differences of TP answers in {@code ln T} and {@code ln P}; false when one fails. */
    private boolean differenceJacobian(double t, double p, double u, double v, double f0, double f1,
                                       PhaseCompetition competition, Workspace w) {
        double ht = t * DeterministicMath.exp(DIFFERENCE_STEP) <= w.maximumT ? DIFFERENCE_STEP : -DIFFERENCE_STEP;
        double tt = t * DeterministicMath.exp(ht);
        EquilibriumResult a = outerTp(tt, p, competition, w);
        if (!usable(a)) return false;
        double hp = p * DeterministicMath.exp(DIFFERENCE_STEP) <= w.maximumP ? DIFFERENCE_STEP : -DIFFERENCE_STEP;
        double pp = p * DeterministicMath.exp(hp);
        EquilibriumResult b = outerTp(t, pp, competition, w);
        if (!usable(b)) return false;
        w.jacobian[0] = (energyResidual(a, u, tt, w) - f0) / ht;
        w.jacobian[2] = (DeterministicMath.log(a.volume() / v) - f1) / ht;
        w.jacobian[1] = (energyResidual(b, u, t, w) - f0) / hp;
        w.jacobian[3] = (DeterministicMath.log(b.volume() / v) - f1) / hp;
        w.jacobianValid = true;
        return true;
    }

    /**
     * The nested UV search: {@code U(T)} at the specified volume, increasing, bracketed in temperature; each temperature
     * solves the volume by {@link #innerUv}. A pressure outside the window at a temperature moves the bracket (at fixed
     * volume the pressure rises with temperature); a closed bracket there is out of domain in pressure.
     */
    private EquilibriumResult nestedUv(double u, double v, PhaseCompetition competition, Workspace w) {
        Search s = w.search;
        s.reset(w.minimumT, w.maximumT);
        double tolerance = settings.outerTolerance();
        double t = Double.isNaN(w.bestT) ? idealTemperature(u, true, w) : w.bestT;
        double previousT = Double.NaN;
        double previousResidual = Double.NaN;
        double lastT = Double.NaN;
        EquilibriumResult last = null;
        int retreats = 0;
        boolean strict = false;
        // Whether each bracket end was recorded from an inexact volume solve, and whether a closed jump was re-solved.
        boolean lowerLoose = false, upperLoose = false, resolved = false;
        int warmer = 0;
        for (int steps = 0; ; steps++) {
            if (steps >= settings.maximumOuterIterations()) {
                return searchFailure(Specification.UV, competition, "no convergence of the UV temperature search in "
                        + steps + " steps (bracket " + s.lower + " .. " + s.upper + " K)", last, w);
            }
            // An inexact volume solve while the energy is far off: a hundredth of the last relative energy residual. Not
            // under a crystal competition (P5): a crystal beside a liquid makes the answer nearly incompressible, so an
            // inexact volume moves its pressure by megapascals and its energy by more than the residual that set the
            // tolerance (dU/d ln V at fixed T is T alpha/kappa, ten times N R T), which put ends of the bracket on the wrong
            // side and closed it on a false step.
            w.innerLooseness = strict || w.activeCount > 0 ? 0.0 : Double.isNaN(previousResidual) ? INNER_LOOSENESS
                    : Math.min(INNER_LOOSENESS, 0.01 * Math.abs(previousResidual) / (w.total * R * t));
            int outcome = innerUv(t, v, competition, w);
            if (outcome == INNER_FAILED) {
                if (Double.isNaN(lastT) && w.activeCount > 0 && t < crystalCeiling(w)) {
                    // P4 stage 2a: as in PH, a start in a held region starts again from the competition's ceiling.
                    t = crystalCeiling(w);
                    continue;
                }
                if (Double.isNaN(lastT) && t < w.maximumT && ++warmer <= MAXIMUM_RETREATS) {
                    // As in PH: a start the equilibrium does not answer lies below the answer; the search starts warmer.
                    t = Math.min(1.25 * t, w.maximumT);
                    continue;
                }
                if (Double.isNaN(lastT) || ++retreats > MAXIMUM_RETREATS) {
                    return w.innerResult != null
                            ? outerAnswer(Specification.UV, w.innerResult, u, v, false, w.innerFailure, w)
                            : searchFailure(Specification.UV, competition, w.innerFailure, last, w);
                }
                t = 0.5 * (t + lastT);
                continue;
            }
            retreats = 0;
            if (outcome != INNER_FOUND) {
                boolean above = outcome == INNER_ABOVE;
                s.recordSide(t, above);
                lastT = t;
                previousT = Double.NaN;
                boolean closed = s.bracketed() && s.width() <= tolerance * t;
                if (closed || !s.bracketed() && (above ? t <= s.minimum : t >= s.maximum)) {
                    return outsideWindow(Specification.UV, false, !above,
                            "the specified volume " + v + " m3", competition, last, w);
                }
                t = s.bracketed() ? s.next(t, Double.NaN, 0.0) : Math.clamp(above ? 0.8 * t : 1.25 * t, s.minimum, s.maximum);
                continue;
            }
            EquilibriumResult result = w.innerResult;
            boolean coexistence = w.innerCoexistence;
            last = result;
            lastT = t;
            double residual = result.internalEnergy() - u;
            double scale = SPECIFICATION_TOLERANCE * w.total * R * t;
            if (w.innerLoose && Math.abs(residual) <= 100.0 * scale) {
                // Close enough to need the volume exactly: solve it again at this temperature (nothing recorded).
                strict = true;
                continue;
            }
            s.record(t, residual, result, coexistence);
            if (residual < 0.0) lowerLoose = w.innerLoose; else upperLoose = w.innerLoose;
            double slope = coexistence ? Double.NaN : dryIsochoricHeatCapacity(result, t, w);
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                slope = Double.isNaN(previousT) || previousT == t ? Double.NaN : (residual - previousResidual) / (t - previousT);
            }
            if (!(slope > 0.0) || !Double.isFinite(slope)) slope = idealHeatCapacity(t, true, w);
            double step = -residual / slope;
            if (Math.abs(residual) <= scale && Math.abs(step) <= tolerance * t) {
                return outerAnswer(Specification.UV, result, u, v, coexistence, null, w);
            }
            if (s.bracketed() && s.width() <= tolerance * t) {
                // U(T) at fixed volume is continuous (through a coexistence too): a jump here is no state of the model.
                if (s.upperResidual - s.lowerResidual <= scale && s.best() != null) {
                    return outerAnswer(Specification.UV, s.best(), u, v, s.bestCoexistence(), null, w);
                }
                // An end known only by its pressure leaving the window (P3 WP7d): the bracket closed against the box, on
                // the temperature where the isochore meets the box's pressure bound, with the energy still short of the
                // specification at the answered end. The answer lies beyond that bound: not converged.
                boolean aboveWindow = Double.isNaN(s.upperResidual) && s.upperResult == null;
                boolean belowWindow = Double.isNaN(s.lowerResidual) && s.lowerResult == null;
                if (aboveWindow || belowWindow) {
                    if (Math.abs(residual) <= scale) return outerAnswer(Specification.UV, result, u, v, coexistence, null, w);
                    return outsideWindow(Specification.UV, false, belowWindow,
                            "the specified volume " + v + " m3 at the specified internal energy " + u + " J", competition, last, w);
                }
                if ((lowerLoose || upperLoose) && !resolved) {
                    // An end from an inexact volume solve may lie on the wrong side: within a split dU/d ln V at fixed T is
                    // about the vapour's share of the latent heat, far above the N R T the looseness assumes (W3a: ethanol
                    // and water at 360 K, 7 Pa and 5 J off). The search starts again from its best end with exact solves.
                    resolved = true;
                    strict = true;
                    lowerLoose = upperLoose = false;
                    t = Math.abs(s.lowerResidual) <= Math.abs(s.upperResidual) ? s.lower : s.upper;
                    s.reset(s.minimum, s.maximum);
                    previousT = Double.NaN;
                    previousResidual = Double.NaN;
                    continue;
                }
                EquilibriumResult lever = balanceSteps.leverStep(Specification.UV, s, u, competition, w);
                if (lever != null) return outerAnswer(Specification.UV, lever, u, v, true, null, w);
                // A target inside a one-major feed's triple-point triangle was answered before the search (solveUv).
                boolean crystalStep = balanceSteps.pureCrystal(w) != null && s.endHoldsSolid();
                return searchFailure(Specification.UV, competition, crystalStepType(crystalStep) + "the specified internal energy "
                        + u + " J lies in a step of U(T) at the specified volume between " + s.lower + " and " + s.upper + " K",
                        last, w);
            }
            if (!s.bracketed()) {
                if (t <= s.minimum && residual > 0.0) {
                    return outsideWindow(Specification.UV, true, true,
                            "the specified internal energy " + u + " J", competition, last, w);
                }
                if (t >= s.maximum && residual < 0.0) {
                    return outsideWindow(Specification.UV, true, false,
                            "the specified internal energy " + u + " J", competition, last, w);
                }
            }
            previousT = t;
            previousResidual = residual;
            // Before a bracket the step is capped: the ideal-gas slope of a two-phase start is far too small.
            t = s.next(t, step, TEMPERATURE_STEP_CAP * t);
        }
    }

    /**
     * The pressure at temperature {@code t} whose TP answer has volume {@code v}: {@code ln(v/V(P))} increases with
     * {@code ln P}, bracketed like PH. Started from the cubic's own pressure of the dry feed as one phase when the last
     * answer was one phase, otherwise from the pressures found at earlier temperatures extrapolated linearly in
     * {@code (1/T, ln P)} (one earlier answer: Trouton's slope {@code -10 T}); the first slope without a compressibility
     * is the last secant measured between two answers on one piece of {@code V(P)} ({@link #samePhases}). Accepted within
     * {@code innerLooseness} in {@code ln V} (the nested search's inexact solves far from the answer) or at the
     * specification. A step of {@code V(P)} is the volume jump between the bracket's ends (owner decision
     * D40 A): a pure coexistence, a crystal's step, a binary's three-phase pressure,
     * each answered from the volume balance, or a typed failure.
     */
    private int innerUv(double t, double v, PhaseCompetition competition, Workspace w) {
        Search s = w.inner;
        double tolerance = settings.outerTolerance();
        s.reset(DeterministicMath.log(w.minimumP), DeterministicMath.log(w.maximumP));
        double cubic = !w.innerSingle ? Double.NaN : cubicPressure(t, v, w);
        double y;
        if (!Double.isNaN(cubic)) {
            y = DeterministicMath.log(cubic);
        } else if (Double.isNaN(w.innerY1)) {
            y = DeterministicMath.log(w.total * R * t / v);
        } else {
            double slope = !Double.isNaN(w.innerY2) && w.innerT2 != w.innerT1
                    ? (w.innerY1 - w.innerY2) / (1.0 / w.innerT1 - 1.0 / w.innerT2) : -10.0 * w.innerT1;
            if (!(slope < 0.0) || !Double.isFinite(slope)) slope = -10.0 * w.innerT1;
            y = w.innerY1 + Math.clamp(slope * (1.0 / t - 1.0 / w.innerT1), -2.0 * PRESSURE_STEP_CAP, 2.0 * PRESSURE_STEP_CAP);
        }
        y = Math.clamp(y, s.minimum, s.maximum);
        w.innerLoose = false;
        double previousY = Double.NaN;
        double previousResidual = Double.NaN;
        EquilibriumResult previous = null;
        double lastY = Double.NaN;
        int retreats = 0;
        w.innerResult = null;
        w.innerCoexistence = false;
        w.innerFailure = null;
        for (int steps = 0; ; steps++) {
            if (steps >= settings.maximumOuterIterations()) {
                w.innerFailure = "no convergence of the UV pressure search at " + t + " K in " + steps + " TP equilibria";
                return INNER_FAILED;
            }
            double p = DeterministicMath.exp(y);
            EquilibriumResult result = outerTp(t, p, competition, w);
            if (!result.converged()) {
                if (Double.isNaN(lastY) || ++retreats > MAXIMUM_RETREATS) {
                    w.innerResult = result;
                    w.innerFailure = "UV search: the TP equilibrium at " + t + " K, " + p + " Pa";
                    return INNER_FAILED;
                }
                y = 0.5 * (y + lastY);
                continue;
            }
            retreats = 0;
            double residual;
            boolean coexistence = false;
            if (result.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
                EquilibriumResult answer = holdsSolid(result) ? balanceSteps.pureCrystalStep(Specification.UV, t, p, v, false, competition, w)
                        : balanceSteps.pureCoexistence(Specification.UV, t, p, v, false, competition, w);
                if (answer != null) return foundOrHeld(answer, w);
                if (Double.isNaN(w.coexistenceX)) {
                    w.innerFailure = "no equal-fugacity pressure found near " + p + " Pa at " + t + " K";
                    return INNER_FAILED;
                }
                y = w.coexistenceX;
                residual = w.coexistenceResidual;
                result = null;
                coexistence = true;
            } else {
                residual = DeterministicMath.log(v / result.volume());
            }
            lastY = y;
            s.record(y, residual, result, coexistence);
            double slope = result == null ? Double.NaN : dryCompressibility(result, t, p, w);
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                // A secant only between answers on one piece of V(P), the same phases (owner decision D40 A): across a bubble
                // or dew point V(P) has a kink, across a step a jump whose secant is no slope; stored, such a secant started
                // the next temperature's search with steps below the spacing of ln P (100 TP equilibria at one point).
                slope = Double.isNaN(previousY) || previousY == y || !samePhases(result, previous) ? Double.NaN
                        : (residual - previousResidual) / (y - previousY);
                if (slope > 0.0 && Double.isFinite(slope)) w.innerSlope = slope; else slope = w.innerSlope;
            }
            previous = result;
            double step = -residual / slope;
            boolean exact = Math.abs(residual) <= SPECIFICATION_TOLERANCE && Math.abs(step) <= tolerance;
            if (result != null && (exact || Math.abs(residual) <= w.innerLooseness)) {
                w.innerLoose = !exact;
                return found(result, false, w);
            }
            if (s.bracketed() && (s.width() <= tolerance || w.request.presentCount == 1 && s.straddlesPureCoexistence()
                    || balanceSteps.pureCrystal(w) != null && s.straddlesCrystalStep()
                    || balanceSteps.binaryCrystal(w) != null && threePhaseDue(s, s.width())
                    || balanceSteps.binaryFluid(w) >= 0 && fluidThreeDue(s, s.width()))) {
                int closed = closedInner(t, v, competition, w);
                if (closed != INNER_BELOW) return closed;
                // The coexistence lies beyond the jump (its side is recorded), or was not met yet: the search goes on.
                if (!Double.isNaN(w.coexistenceX)) {
                    y = w.coexistenceX;
                    s.record(y, w.coexistenceResidual, null, true);
                }
                previousY = Double.NaN;
                y = s.next(y, Double.NaN, PRESSURE_STEP_CAP);
                continue;
            }
            if (!s.bracketed()) {
                if (y <= s.minimum && residual > 0.0) return INNER_BELOW;
                if (y >= s.maximum && residual < 0.0) return INNER_ABOVE;
            }
            previousY = y;
            previousResidual = residual;
            y = s.next(y, step, PRESSURE_STEP_CAP);
        }
    }

    private static int found(EquilibriumResult result, boolean coexistence, Workspace w) {
        w.innerResult = result;
        w.innerCoexistence = coexistence;
        w.innerPressure = result.phases().get(0).pressure();
        w.innerSingle = !coexistence && result.phases().size() == 1;
        double t = result.phases().get(0).temperature();
        if (t != w.innerT1) {
            w.innerT2 = w.innerT1;
            w.innerY2 = w.innerY1;
        }
        w.innerT1 = t;
        w.innerY1 = DeterministicMath.log(w.innerPressure);
        return INNER_FOUND;
    }

    /**
     * The inner bracket closed (or straddles a pure component's vapour and liquid). Returns {@code INNER_FOUND},
     * {@code INNER_FAILED}, or {@code INNER_BELOW} when the search goes on: the coexistence lies beyond the jump (its
     * side in {@code coexistenceX} and {@code coexistenceResidual}), or the bracket is not closed yet and no
     * equal-fugacity pressure was met ({@code coexistenceX} NaN).
     */
    private int closedInner(double t, double v, PhaseCompetition competition, Workspace w) {
        Search s = w.inner;
        boolean closed = s.width() <= settings.outerTolerance();
        w.coexistenceX = Double.NaN;
        if (closed && s.upperResidual - s.lowerResidual <= SPECIFICATION_TOLERANCE && s.best() != null) {
            if (!s.bestCoexistence()) w.innerPressure = s.best().phases().get(0).pressure();
            return found(s.best(), s.bestCoexistence(), w);
        }
        double p = DeterministicMath.exp(0.5 * (s.lower + s.upper));
        boolean crystalStep = balanceSteps.pureCrystal(w) != null && s.endHoldsSolid();
        // The one component's coexistence: any jump of a pure feed.
        if (crystalStep || w.request.presentCount == 1) {
            EquilibriumResult answer = crystalStep ? balanceSteps.pureCrystalStep(Specification.UV, t, p, v, closed, competition, w)
                    : balanceSteps.pureCoexistence(Specification.UV, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        } else if (balanceSteps.binaryCrystal(w) != null && BalanceSteps.straddlesThreePhase(s)) {
            // P5: a binary's three-phase step (vapour, liquid and crystal at one pressure for the fixed temperature).
            EquilibriumResult answer = balanceSteps.binaryThreePhase(Specification.UV, s, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        } else if (balanceSteps.binaryFluid(w) >= 0 && BalanceSteps.straddlesFluidThree(s)) {
            // W3a D4: a binary's vapour-liquid-liquid step (at the fixed temperature, one pressure).
            EquilibriumResult answer = balanceSteps.binaryFluidThreePhase(Specification.UV, s, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        }
        // Not closed yet: the search goes on (the step is not where the volume lies).
        if (!closed) return INNER_BELOW;
        w.innerFailure = crystalStepType(crystalStep) + "the specified volume " + v + " m3 lies in a step of V(P) at " + t
                + " K between " + DeterministicMath.exp(s.lower) + " and " + DeterministicMath.exp(s.upper)
                + " Pa: no state of the model has it";
        return INNER_FAILED;
    }

    /**
     * The {@link EquilibriumResult#CRYSTAL_STEP} prefix of a closed search's failure when the step it closed on is the
     * crystal step of the feed's one major species ({@code crystalStep}: a pure or near-pure feed, an end holding the
     * crystal; D21 option C), otherwise nothing.
     */
    private static String crystalStepType(boolean crystalStep) {
        return crystalStep ? EquilibriumResult.CRYSTAL_STEP + ": " : "";
    }

    // Results of a search.

    /**
     * A search's answer under its specification: the TP answer (or a coexistence from a balance) with the search's
     * counters and residual; a note is appended to the detail (never prepended: a {@code CRITICAL_BAND} prefix stays).
     */
    static EquilibriumResult outerAnswer(Specification specification, EquilibriumResult answer, double first, double second,
                                         boolean coexistence, String note, Workspace w) {
        double residual = Double.NaN;
        if (answer.converged() && !answer.phases().isEmpty()) {
            double t = answer.phases().get(0).temperature();
            double scale = answer.totalAmount() * R * t;
            residual = specification == Specification.PH ? Math.abs(answer.enthalpy() - first) / scale
                    : Math.max(Math.abs(answer.internalEnergy() - first) / scale, Math.abs(DeterministicMath.log(answer.volume() / second)));
        }
        Diagnostics d = answer.diagnostics();
        Diagnostics outer = new Diagnostics(d.feedVerdict(), d.feedTangentPlaneDistance(), d.flashIterations(),
                d.extrapolations(), d.rachfordRiceIterations(), d.lnKChange(), d.fugacityResidual(),
                d.productTangentPlaneDistance(), w.outerKernel, w.outerDerivative, d.newtonIterations(), d.tieLine(),
                d.criticalBand(), w.outerEvaluations, residual, coexistence && answer.converged());
        String detail = note == null ? answer.detail() : answer.detail() + "; " + note;
        EquilibriumResult.Coverage coverage = answer.converged() || note == null ? answer.coverage()
                : new EquilibriumResult.Coverage(answer.coverage().grade(), detail);
        return new EquilibriumResult(specification, answer.status(), answer.reason(), detail,
                answer.identity(), answer.competition(), answer.classification(), answer.phases(), answer.coexisting(),
                coverage, outer, answer.deposition());
    }

    /** A search that ended without an answer; inside the critical band (by its last TP answer) typed {@code CRITICAL_BAND}. */
    private EquilibriumResult searchFailure(Specification specification, PhaseCompetition competition, String why,
                                            EquilibriumResult last, Workspace w) {
        Diagnostics d = last == null ? Diagnostics.NONE : last.diagnostics();
        boolean band = d.criticalBand();
        String detail = band ? EquilibriumResult.CRITICAL_BAND + " (the search's last TP equilibrium lies in the critical band): "
                + why : why;
        Diagnostics outer = new Diagnostics(d.feedVerdict(), d.feedTangentPlaneDistance(), d.flashIterations(),
                d.extrapolations(), d.rachfordRiceIterations(), d.lnKChange(), d.fugacityResidual(),
                d.productTangentPlaneDistance(), w.outerKernel, w.outerDerivative, d.newtonIterations(), d.tieLine(), band,
                w.outerEvaluations, Double.NaN, false);
        return EquilibriumResult.notConverged(specification, contract.identity(), competition, detail, outer);
    }

    /**
     * The specification lies beyond an end of the search window, the numerical box: not converged, a numerical failure
     * (owner rule of 2026-10-16: no data range bounds a search).
     */
    private EquilibriumResult outsideWindow(Specification specification, boolean temperature, boolean below, String what,
                                            PhaseCompetition competition, EquilibriumResult last, Workspace w) {
        double minimum = temperature ? w.minimumT : w.minimumP;
        double maximum = temperature ? w.maximumT : w.maximumP;
        return searchFailure(specification, competition, what + " lies " + (below ? "below" : "above") + " the "
                + (temperature ? "temperature" : "pressure") + " window " + minimum + " .. " + maximum + (temperature ? " K" : " Pa")
                + " of the " + specification + " search (the numerical box)", last, w);
    }

    // The answer inspectors the searches and the crystal stages share (P4 stage 2a, generalised in P5). The pure species'
    // sublimation and melting steps and the three-phase steps of PH and UV are BalanceSteps', the equilibrium of a competing
    // crystal with the fluid it leaves is CrystalEquilibrium's, the onset searches are CrystalOnsets'.

    /** {@code mu_i} of EOS component {@code i} in a fluid answer: its first phase's (equal in every phase), or a coexisting state's. */
    static double fluidChemicalPotential(EquilibriumResult fluid, int component) {
        PhaseState state = fluid.phases().isEmpty() ? fluid.coexisting().get(0) : fluid.phases().get(0).state();
        return state.chemicalPotential(component);
    }

    /** Whether a fluid answer holds a liquid: a phase labelled liquid, or a pure fluid's liquid-vapour coexistence. */
    static boolean holdsLiquid(EquilibriumResult fluid) {
        if (fluid.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
            for (PhaseState state : fluid.coexisting()) if (state.kind() == PhaseKind.LIQUID) return true;
            return false;
        }
        for (PhaseAmounts phase : fluid.phases()) if (phase.kind() == PhaseKind.LIQUID) return true;
        return false;
    }

    private static boolean holdsSolid(EquilibriumResult result) {
        if (result == null || !result.converged()) return false;
        for (PhaseAmounts phase : result.phases()) if (phase.kind() == PhaseKind.SOLID) return true;
        for (PhaseState state : result.coexisting()) if (state.kind() == PhaseKind.SOLID) return true;
        return false;
    }

    /** Whether a phase is a hydrocarbon fluid: labelled liquid, vapour or fluid (not a crystal). */
    private static boolean hydrocarbonFluid(PhaseKind kind) {
        return kind == PhaseKind.LIQUID || kind == PhaseKind.VAPOR || kind == PhaseKind.FLUID;
    }

    /** The label of an answer's one hydrocarbon fluid phase, or null where it has none or several. */
    private static PhaseKind oneFluid(EquilibriumResult result) {
        PhaseKind found = null;
        for (PhaseAmounts phase : result.phases()) {
            if (!hydrocarbonFluid(phase.kind())) continue;
            if (found != null) return null;
            found = phase.kind();
        }
        return found;
    }

    /** Whether two answers hold the same phases beside their hydrocarbon fluid ones: by kind and crystal, in order. */
    private static boolean sameBesideFluid(EquilibriumResult a, EquilibriumResult b) {
        List<PhaseAmounts> x = a.phases();
        List<PhaseAmounts> y = b.phases();
        int i = 0;
        int j = 0;
        while (true) {
            while (i < x.size() && hydrocarbonFluid(x.get(i).kind())) i++;
            while (j < y.size() && hydrocarbonFluid(y.get(j).kind())) j++;
            if (i == x.size() || j == y.size()) return i == x.size() && j == y.size();
            if (x.get(i).kind() != y.get(j).kind() || !Objects.equals(x.get(i).crystal(), y.get(j).crystal())) return false;
            i++;
            j++;
        }
    }

    /**
     * Whether two answers lie on one piece of {@code V(P)} (or {@code H(T)}): both answered, with the same phases by kind
     * and crystal, in order (owner decision D40 A). A step of the function changes the phases at its ends (a pure
     * component's coexistence, a crystal's step, a three-phase pressure), and so
     * does a kink (a bubble or dew point).
     */
    private static boolean samePhases(EquilibriumResult a, EquilibriumResult b) {
        if (a == null || b == null || a.phases().size() != b.phases().size()) return false;
        for (int k = 0; k < a.phases().size(); k++) {
            PhaseAmounts x = a.phases().get(k);
            PhaseAmounts y = b.phases().get(k);
            if (x.kind() != y.kind() || !Objects.equals(x.crystal(), y.crystal())) return false;
        }
        return true;
    }

    /** An inner UV answer from a balance: a converged one is found, a typed hold ends the pressure search with it. */
    private static int foundOrHeld(EquilibriumResult answer, Workspace w) {
        if (answer.converged()) return found(answer, true, w);
        w.innerResult = answer;
        w.innerFailure = answer.detail();
        return INNER_FAILED;
    }

    double[] single(int slot, double amount) {
        double[] amounts = new double[basis];
        amounts[slot] = amount;
        return amounts;
    }

    /**
     * Whether a binary's three-phase point is due from bracket {@code s} (P5): it straddles the step, and this is the
     * search's first attempt, or one of three more once the bracket is narrower than {@link #THREE_PHASE_BRACKET}
     * ({@code width}: relative in T for PH, in ln P for UV); a closed bracket is always tried by the closing branch.
     */
    private static boolean threePhaseDue(Search s, double width) {
        if (!BalanceSteps.straddlesThreePhase(s)) return false;
        if (s.threePhaseAttempts == 0 || width <= THREE_PHASE_BRACKET && s.threePhaseAttempts < 4) {
            s.threePhaseAttempts++;
            return true;
        }
        return false;
    }

    /** {@link #threePhaseDue} for the vapour-liquid-liquid step of a {@link BalanceSteps#binaryFluid} feed. */
    private static boolean fluidThreeDue(Search s, double width) {
        if (!BalanceSteps.straddlesFluidThree(s)) return false;
        if (s.threePhaseAttempts == 0 || width <= THREE_PHASE_BRACKET && s.threePhaseAttempts < 4) {
            s.threePhaseAttempts++;
            return true;
        }
        return false;
    }

    // Onset functions (P4 stage 2a; P5 beside a liquid).

    /**
     * The onset temperature of crystal {@code crystal} ({@code null}: the one competing crystal whose species is carried)
     * at {@code pressure}: the frost point of a gas, the liquidus of a liquid, a three-phase point of a split, whatever
     * the fluid answer of the whole feed is at the onset. A bracketed search in {@code x = 1/T} ({@code D} increases with
     * {@code x}; {@code mu_i} is continuous across the fluid's own phase boundaries) from the partial-pressure
     * approximation (or the window's warm end), Newton with the ideal-gas sublimation enthalpy as the first slope and
     * secants after, steps capped at a fifth of {@code x} before a bracket, bisection when a step leaves it; converged at
     * {@code |D| <= }{@link #DEPOSITION_TOLERANCE}. The window is the numerical box below the competition's ceiling
     * ({@link #crystalCeiling}); an onset beyond it is {@code NOT_CONVERGED}, one outside a data range is graded
     * extrapolated, one whose fluid is two liquids the typed {@link EquilibriumResult#CRYSTAL_PHASES} hold.
     *
     * @throws IllegalArgumentException when no competing crystal (or {@code crystal}) is made of a carried species, or
     *         several are and none is named
     */
    public DepositionOnset depositionTemperature(String crystal, double pressure, List<String> species, double[] amounts,
                                                 Workspace workspace) {
        return crystalOnsets.onset(true, null, crystal, pressure, species, amounts, own(workspace));
    }

    /**
     * The frost point of a gas of composition {@code amounts} as one vapour phase: the temperature at {@code pressure}
     * where crystal {@code crystal} and the feed on the cubic's vapour root have equal chemical potentials of the
     * crystal's species, the standard solid-vapour temperature at a measured vapour composition, whether or not the gas
     * would condense at that pressure. The mirror of {@link #freezingTemperature(String, double, List, double[], Workspace)};
     * {@link DepositionOnset#fluid()} is the fluid answer of the whole feed at the onset.
     */
    public DepositionOnset frostTemperature(String crystal, double pressure, List<String> species, double[] amounts,
                                            Workspace workspace) {
        return crystalOnsets.onset(true, Root.VAPOR, crystal, pressure, species, amounts, own(workspace));
    }

    /**
     * The freezing point: the liquidus of a liquid of composition {@code amounts} at {@code pressure}, the temperature
     * where crystal {@code crystal} ({@code null}: the one competing crystal whose species is carried) and the feed as one
     * liquid phase (the cubic's liquid root) have equal chemical potentials of the crystal's species; for a pure feed its
     * melting temperature. The standard solid-liquid solubility temperature at the liquid's own composition, whether or
     * not a vapour would form from it. The same bracketed search in {@code x = 1/T} as
     * {@link #depositionTemperature(String, double, List, double[], Workspace)}, started at the ideal-solubility estimate
     * {@code 1/T = 1/T_t - R ln x_i / Delta h_fus}; no fluid flash per step. {@link DepositionOnset#fluid()} is the
     * fluid answer of the whole feed at the onset; the partial-pressure approximation is {@code NaN}.
     */
    public DepositionOnset freezingTemperature(String crystal, double pressure, List<String> species, double[] amounts,
                                               Workspace workspace) {
        return crystalOnsets.onset(true, Root.LIQUID, crystal, pressure, species, amounts, own(workspace));
    }

    /**
     * The onset pressure of crystal {@code crystal}: as {@link #depositionTemperature(String, double, List, double[],
     * Workspace)} with the search in {@code ln P} ({@code D} increases with it; first slope one, the
     * ideal gas's), steps capped at 2 in {@code ln P} before a bracket.
     */
    public DepositionOnset depositionPressure(String crystal, double temperature, List<String> species, double[] amounts,
                                              Workspace workspace) {
        return crystalOnsets.onset(false, null, crystal, temperature, species, amounts, own(workspace));
    }

    private String species(int component) { return evaluator.components().get(component); }

    // Contract checks.

    /** Takes the request's amounts over the contract basis (owner decision O2: a request names the basis, in order) and
     * applies the water-chemistry and competition rules. */
    EquilibriumResult contractChecks(EquilibriumRequest request, Workspace w) {
        Specification specification = request.specification();
        PhaseCompetition competition = request.competition();
        if (!request.species().equals(contract.components())) {
            throw new IllegalArgumentException("A request names the contract basis " + contract.components() + ", got " + request.species());
        }
        System.arraycopy(request.amountsView(), 0, w.request.basisAmounts, 0, basis);
        int water = contract.waterIndex();
        if (water >= 0 && w.request.basisAmounts[water] > 0.0) {
            for (int i : waterChemistry) {
                if (w.request.basisAmounts[i] > 0.0) {
                    return unsupported(specification, competition, UnsupportedReason.WATER_CHEMISTRY_NOT_MODELLED,
                            contract.components().get(i) + " together with water needs aqueous chemistry; the "
                                    + contract.water() + " approximation cannot describe its dissolution");
                }
            }
        }
        competition = contract.resolve(competition);
        if (!contract.qualifies(competition)) {
            return unsupported(specification, competition, UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED,
                    "package " + contract.packageId() + " is not qualified for the " + competition + " competition");
        }
        w.competition = competition;
        return null;
    }

    /** The 14 shifts of an attempt loop: zero, then {@code 10^(attempt - offset)}. */
    private static double[] shifts(int offset) {
        double[] shifts = new double[14];
        for (int attempt = 1; attempt < shifts.length; attempt++) shifts[attempt] = DeterministicMath.pow(10.0, attempt - offset);
        return shifts;
    }

    // The critical band.

    /**
     * The declared critical band of a pure fluid at reduced temperature {@code tr} and pressure {@code pr} (closed
     * intervals): the box it lies in, 0 outside. Box 1 {@code Tr 0.95-1.1 x Pr 0.8-1.5}; box 2 {@code Tr 0.90-0.95 x Pr
     * 0.58-0.74} widened by D15 (P3 WP7d) with {@code Tr 0.95-0.97 x Pr 0.58-0.8}, the liquid between boxes 1 and 2 below
     * Pr 0.8; box 3 {@code Tr 1.05-1.2 x Pr 2-3}; box 4, D14's corner {@code Tr 1.0-1.1 x Pr 1.5-2} (P3 WP7d). Where boxes
     * share an edge the lower number is reported. Inside the band an answer is research-only.
     */
    public static int pureBandBox(double tr, double pr) {
        if (tr >= 0.95 && tr <= 1.1 && pr >= 0.8 && pr <= 1.5) return 1;
        if (tr >= 0.90 && tr <= 0.95 && pr >= 0.58 && pr <= 0.74 || tr >= 0.95 && tr <= 0.97 && pr >= 0.58 && pr <= 0.8) return 2;
        if (tr >= 1.05 && tr <= 1.2 && pr >= 2.0 && pr <= 3.0) return 3;
        if (tr >= 1.0 && tr <= 1.1 && pr >= 1.5 && pr <= 2.0) return 4;
        return 0;
    }

    // Results.

    /** EOS-basis amounts over the contract basis (water, if any, zero). */
    double[] expand(double[] amounts) {
        double[] expanded = new double[basis];
        for (int i = 0; i < count; i++) expanded[basisOfComponent[i]] = amounts[i];
        return expanded;
    }

    EquilibriumResult converged(PhaseCompetition competition, Classification classification,
                                List<PhaseAmounts> phases, List<PhaseState> coexisting, Diagnostics diagnostics,
                                String band) {
        return converged(competition, classification, phases, coexisting, diagnostics, band, null);
    }

    /** @param note appended to the detail (a coexistence solved from a balance, P3 WP6b), or null */
    EquilibriumResult converged(PhaseCompetition competition, Classification classification,
                                List<PhaseAmounts> phases, List<PhaseState> coexisting, Diagnostics diagnostics,
                                String band, String note) {
        EquilibriumResult.Coverage coverage = band == null ? convergedCoverage
                : new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY,
                        contract.fluidCoverage().evidence() + "; " + band + ": research-only"
                                + FLUID_ONLY_NOTE);
        String detail = band == null ? CONVERGED : CONVERGED + " (" + band + ")";
        if (note != null) detail += "; " + note;
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, detail,
                contract.identity(), competition, classification, phases, coexisting, coverage, diagnostics);
    }

    EquilibriumResult unsupported(Specification specification, PhaseCompetition competition,
                                  UnsupportedReason reason, String detail) {
        return EquilibriumResult.unsupported(specification, contract.identity(), competition, reason, detail);
    }

    static Diagnostics diagnostics(Workspace w, TangentPlaneStability.Result feed, boolean band) {
        return new Diagnostics(feed == null ? null : feed.verdict(),
                feed == null ? Double.NaN : feed.minimumTangentPlaneDistance(), w.iterations, w.extrapolations,
                w.rachfordRiceSteps, w.lastChange, w.residual, w.productDistance, w.kernelEvaluations,
                w.derivativeEvaluations, w.newtonIterations, w.tieLine, band);
    }

    private Workspace own(Workspace workspace) {
        if (!(Objects.requireNonNull(workspace, "workspace") instanceof Workspace w) || w.owner != this) {
            throw new IllegalArgumentException("Workspace belongs to another service");
        }
        return w;
    }

    private static void requireSpecification(EquilibriumRequest request, Specification expected) {
        if (Objects.requireNonNull(request, "request").specification() != expected) {
            throw new IllegalArgumentException("Expected a " + expected + " request, got " + request.specification());
        }
    }
}
