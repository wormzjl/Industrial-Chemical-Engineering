package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.ThermoRefusal;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Diagnostics;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.FailureKind;
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
    /** Largest step of an unbracketed pressure search, and of one UV Newton step, in {@code ln P}. */
    static final double PRESSURE_STEP_CAP = 2.0;
    /**
     * TP equilibria of a warm-started UV Newton phase before the cold search takes over. 16, not 8: a vessel's crystal
     * beside a vapour and a liquid of three species moves between substeps by more than two Newton iterations can close
     * (warm answers 180 of 211 per interval at 16 against 2 of 211 at 8 on the pilot world's liquid methane flush).
     */
    public static final int WARM_UV_EVALUATIONS = 16;

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
    /** The PH and UV searches (S01), built once at construction. */
    private final SpecificationSearch specificationSearch;

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
        specificationSearch = new SpecificationSearch(this, balanceSteps, contract, evaluator, kernel, settings, count, translations,
                crystalSet, criticalBand);
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
        // The failed core's detail and its kind, written together.
        String failure;
        FailureKind failureKind;
        double residual, productDistance;
        // The request's material, set before any calculation.
        double hydrocarbonTotal;
        double total;
        // PH and UV: the search window, the searches, counters over the whole search, the Jacobian of the UV Newton phase.
        double minimumT, maximumT, minimumP, maximumP;
        /** Where a PH or UV search starts: inside the data of the models the material uses (see {@link SpecificationSearch#searchWindow}). */
        double startMinimumT, startMaximumT;
        final BracketSearch search = new BracketSearch();
        final BracketSearch inner = new BracketSearch();
        int outerEvaluations, outerKernel, outerDerivative;
        final PhaseDerivatives slope;
        final double[] jacobian = new double[4];
        boolean jacobianValid;
        double coexistenceX, coexistenceResidual, bestT, bestResidual, innerPressure;
        EquilibriumResult innerResult;
        boolean innerCoexistence;
        String innerFailure;
        FailureKind innerFailureKind;
        // The nested UV search's pressure solves: accepted residual, whether the last was inexact, the last secant (of one
        // classification), whether the last answer was one phase.
        double innerLooseness, innerSlope;
        boolean innerLoose, innerSingle;
        // The last two pressure solves at different temperatures, (T, ln P): the next solve's start.
        double innerT1, innerY1, innerT2, innerY2;
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
        final PhaseDerivatives solidSlope = new PhaseDerivatives(1);
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
        specificationSearch.searchWindow(w);
        return graded(specificationSearch.solvePh(p, request.enthalpy(), competition, w), null, w);
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
        specificationSearch.searchWindow(w);
        return graded(specificationSearch.solveUv(request.internalEnergy(), request.volume(), hintTemperature, hintPressure, competition, w), null, w);
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
        return EquilibriumResult.notConverged(specification, contract.identity(), competition, FailureKind.OUTSIDE_BOX, false, "the state " + t + " K, " + p
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
        } catch (ThermoRefusal | PengRobinsonKernel.NoPhysicalRoot | PengRobinsonKernel.RootCoalescence refusedByModel) {
            // The equation of state or a property model refused a state (no physical root, a root too close to coalescence
            // to differentiate, a mechanically unstable root, a state outside a model's range): no answer.
            return refusedEvaluation(FailureKind.REFUSED, refusedByModel, competition, w);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException internal) {
            // Not a refusal (an argument, layout or invariant failure inside the solve): still answered, with the same
            // text, so that a defect does not take down the caller's solve; typed INTERNAL so that it is not read as a
            // refusal.
            return refusedEvaluation(FailureKind.INTERNAL, internal, competition, w);
        }
    }

    /** The answer of an evaluation an exception ended: not converged, of {@code kind}, the detail as before the kind existed. */
    private EquilibriumResult refusedEvaluation(FailureKind kind, RuntimeException refused, PhaseCompetition competition, Workspace w) {
        return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, kind, false,
                "evaluation refused: " + refused.getMessage(), diagnostics(w, w.feedResult, false));
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

    // The answer inspectors the crystal solves and the onsets share (P4 stage 2a, generalised in P5). The searches' own are
    // BracketSearch's and SpecificationSearch's, the steps from the balances BalanceSteps', the equilibrium of a competing
    // crystal with the fluid it leaves CrystalEquilibrium's, the onset searches CrystalOnsets'.

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

    double[] single(int slot, double amount) {
        double[] amounts = new double[basis];
        amounts[slot] = amount;
        return amounts;
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
