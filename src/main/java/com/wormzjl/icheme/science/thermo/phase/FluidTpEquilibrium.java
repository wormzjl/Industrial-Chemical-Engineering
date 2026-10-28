package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.material.CrystalReference;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCoverage;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Diagnostics;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TP equilibrium of fluid phases on a {@link CubicPhaseEvaluator}: the hydrocarbon phases by stability test and flash,
 * water as the fluid network's separate free water, pure crystals competing with the fluid phases; PH and UV search over
 * the TP answers.
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
 * <p>Free water ({@link WaterParticipation#SEPARATE_FREE_WATER} with a {@link FreeWaterModel}), the fluid network's
 * rule: the hydrocarbon liquid at the state pressure {@code P}, the hydrocarbon vapour at its partial pressure
 * {@code pc = P - p_w}, the second-virial steam ({@link SteamVirial}) sharing the gas volume, liquid water from the water
 * model at {@code P}. Without hydrocarbons the water is all liquid when {@code P >= p_sat} and all steam otherwise.
 * With both, when
 * {@code P > p_sat + 1e-6 Pa} the hydrocarbons are split at {@code (P, P - p_sat)} and the steam that saturates the gas,
 * {@code p_sat V_gas/(R T + p_sat B)}, is taken from the water if there is that much; otherwise all water is steam and {@code pc}
 * solves {@code pc + n_w R T / (V_gas(pc) - n_w B) = P}: up to {@link #PARTIAL_PRESSURE_FIXED_POINT_STEPS} fixed-point steps, each
 * accepted only on the bisection's stop test, else a bisection on {@code [1e-6 Pa, P]}
 * ({@link #PARTIAL_PRESSURE_BISECTIONS} halvings, stop at {@link #PARTIAL_PRESSURE_TOLERANCE}, accepted within
 * {@link #PARTIAL_PRESSURE_ACCEPTANCE}). Steam above the model's qualified partial pressure is answered and graded
 * {@link EquilibriumResult.CoverageGrade#EXTRAPOLATED}. The split with branch pressures uses the stability test's
 * per-branch form and the vapour branch's shift {@code ln(pc/P) + (pc - P) c_i/(R T)} in the flash (the translation does
 * not cancel between two pressures). Ice ({@link FreeWaterIce}): below the melting line {@code T_m(P)} the condensed
 * water is ice, a solid phase naming the water model's ice record, and the rule runs with the ice's fugacity in place of
 * {@code p_sat}; ice is water's own rule, never a crystal of a competition, and forms beside a competing crystal too.</p>
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
 * {@code NOT_CONVERGED} naming that bound. Steps the TP answer leaves undetermined (a pure hydrocarbon at its
 * equal-fugacity point, free water boiling or subliming at the state pressure, free water freezing at its melting line)
 * are determined from the balance once a bracket straddles the jump, {@code target = (1 - beta) x_1 + beta x_2}, at the
 * kernel's equal-fugacity temperature or pressure, the water model's saturation or the melting line
 * ({@link #freezingAtVolume} at a fixed volume and energy; pure water inside its triple point's triangle at that point,
 * {@link #waterTriplePoint}): {@link Classification#VAPOR_LIQUID} or {@link Classification#FREE_WATER} with
 * {@link Diagnostics#coexistenceFromBalance()}. A target inside any other step is {@code NOT_CONVERGED}. Every search is
 * bounded ({@link Settings#maximumOuterIterations()}); near a critical point the searches take secants and brackets.</p>
 *
 * <p>Crystals: a service built with crystal evaluators ({@link #forPackage}) answers the competitions its contract
 * qualifies ({@link PhaseCompetition#FLUID_AND_CRYSTALS} resolves to the package's set). Each crystal is anchored once,
 * at construction, to this evaluator's pure liquid at its triple point ({@link CrystalPhaseEvaluator.Anchored}). A
 * crystal's record continues past its data range; the lowest record ceiling of a competition ({@link #crystalCeiling},
 * 300 K for CO2-I) is where its crystals cease to exist. With free water the crystals compete against the free-water
 * rule's hydrocarbon phases at the hydrocarbon partial pressure ({@link #solveWetWithCrystals}; ice and CO2 frost
 * coexist). TP: the fluid answer of the feed, then each crystal against it by fugacity, {@code D = (mu_i - mu_s)/(R T)};
 * above {@link #DEPOSITION_TOLERANCE} the solid amount is solved so that the fluid the crystal leaves, re-flashed with
 * the stability test, has the crystal's chemical potential to {@code 1e-9}: the vapour-solid Newton on the reduced
 * feed's vapour root first ({@link #depositFromVapour}), else the general solve whose every iterate is a re-flash
 * ({@link #depositFromFluid}; {@link Classification#VAPOR_SOLID}, {@link Classification#LIQUID_SOLID} or
 * {@link Classification#VAPOR_LIQUID_SOLID}). A pure feed freezes or deposits completely
 * ({@link Classification#SOLID_PRESENT}), as does a species below {@link #DEPOSITION_TRACE} in the fluid. Two liquids
 * beside the crystal, three fluid phases and two crystals are the typed {@link EquilibriumResult#CRYSTAL_PHASES} hold.
 * PH and UV answer the pure species' sublimation and melting steps, its triple point (UV) and a binary's three-phase
 * step from the balances; a start in a held region restarts from the competition's ceiling. Onsets
 * ({@link #depositionTemperature}, {@link #freezingTemperature}, {@link #depositionPressure}) are bracketed searches on
 * {@code D} of the fluid answer of the whole feed.</p>
 *
 * <p>Deterministic (fixed arithmetic order; a reused {@link Workspace} carries nothing between calls but prepared
 * temperatures, recomputed bit-identically) and allocation-light: the iterations allocate nothing.</p>
 */
public final class FluidTpEquilibrium {
    /** Largest {@code ln K} change one extrapolation may add. */
    private static final double MAXIMUM_EXTRAPOLATION = 20.0;
    /** Keeps {@code exp(ln K)} a finite, positive normal double. */
    private static final double LOG_K_BOUND = 700.0;
    private static final int MAXIMUM_RACHFORD_RICE_STEPS = 200;
    private static final String CONVERGED = "converged";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** Fraction of the predicted decrease an accepted Newton step must achieve. */
    private static final double ARMIJO = 1.0e-4;
    private static final int NEWTON_HALVINGS = 40;
    /** One Newton step leaves every mole number above {@code 1 - FRACTION_TO_BOUNDARY} of itself. */
    private static final double FRACTION_TO_BOUNDARY = 0.9;
    /** Roundoff allowance on the Gibbs-energy decrease, relative to the magnitude of its summands. */
    private static final double GIBBS_ROUNDOFF = 1.0e-15;
    /** Halvings of the amount of the stationary phase when a Newton start is built from the stability test. */
    private static final int START_HALVINGS = 60;

    // The fluid network's free-water rule (FluidThermodynamics.flashTP and HydrocarbonModel at commit 5100233).
    /** Liquid water with hydrocarbons needs {@code P > p_sat + SATURATION_MARGIN}. */
    public static final double SATURATION_MARGIN = 1.0e-6;
    /** Lower end of the hydrocarbon partial-pressure bisection: the box's floor of a partial pressure. */
    public static final double PARTIAL_PRESSURE_FLOOR = NumericalBox.MINIMUM_PARTIAL_PRESSURE;
    public static final int PARTIAL_PRESSURE_BISECTIONS = 70;
    /** The bisection stops at {@code |pc + p_w - P| < PARTIAL_PRESSURE_TOLERANCE P}. */
    public static final double PARTIAL_PRESSURE_TOLERANCE = 1.0e-8;
    /** And its result is accepted within {@code PARTIAL_PRESSURE_ACCEPTANCE P}. */
    public static final double PARTIAL_PRESSURE_ACCEPTANCE = 1.0e-6;
    /** Fixed-point steps {@code pc = P - n_w R T / V_gas(pc)} tried before the bisection (P3 WP7b). */
    public static final int PARTIAL_PRESSURE_FIXED_POINT_STEPS = 3;
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
    private static final double PRESSURE_STEP_CAP = 2.0;
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
    private static final int COEXISTENCE_ITERATIONS = 100;
    /** The nested UV search's loosest volume solve, in {@code ln V}, while the energy is still far off. */
    private static final double INNER_LOOSENESS = 1.0e-3;
    /**
     * The free water's freeze at a fixed volume ({@link #freezingAtVolume}): TP equilibria of one solve on the melting line;
     * solves of one UV search (each step of the search that crosses the line may try one, until one finds that the line
     * holds no answer); the condensed phases' compressibility of its first step, 1/Pa (liquid water's at 0 °C, 5.1e-10;
     * ice's 1.2e-10).
     */
    private static final int FREEZE_EVALUATIONS = 12;
    private static final int FREEZE_ATTEMPTS = 2;
    private static final double CONDENSED_COMPRESSIBILITY = 5.0e-10;
    // Outcomes of the UV pressure search at one temperature.
    private static final int INNER_FOUND = 0;
    private static final int INNER_ABOVE = 1;
    private static final int INNER_BELOW = 2;
    private static final int INNER_FAILED = 3;

    /** The end of a fluid-only answer's coverage evidence; a crystal competition's answer replaces it (P4 stage 2a). */
    private static final String FLUID_ONLY_NOTE = "; fluid-only: solid phases not assessed";
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
    /** Newton steps of the gas-solid solve, and fluid TP equilibria of an onset search, before NOT_CONVERGED. */
    private static final int DEPOSITION_ITERATIONS = 60;
    private static final int ONSET_EVALUATIONS = 100;
    /** Re-flashes of the general crystal solve (P5) before NOT_CONVERGED. */
    private static final int CRYSTAL_ITERATIONS = 100;
    /** Newton steps of a binary's three-phase point (P5), and its tolerance on {@code |mu - mu_s|/(R T)}. */
    private static final int THREE_PHASE_ITERATIONS = 50;
    private static final double THREE_PHASE_TOLERANCE = 1.0e-11;
    /** A species below this fraction of the feed is a trace of a pseudo-binary's three-phase step (P5). */
    private static final double TRACE_FRACTION = 1.0e-8;
    /**
     * D21: a dry feed whose species other than its largest total at most this fraction of it is near-pure. When that
     * major species has a competing crystal, PH and UV answer its crystal steps (sublimation, melting, triple point) as
     * a pure feed's, the other species carried as traces in the fluid phase the step leaves ({@link #STEP_FLUID_TRACES});
     * the pure species' pressure or temperature is then about 1.3 times the trace fraction off (0.1 mK at 101 kPa at
     * this bound). TP cannot resolve the feed's own vapour-solid region, about the trace fraction wide in pressure, up
     * to about 1e-6 and not reliably just above (a PH target at 2e-6 closed on a 0.57 J step of H(T) over 6e-10 K).
     * Takes precedence over the pseudo-binary rule of {@link #TRACE_FRACTION}.
     */
    private static final double NEAR_PURE_TRACES = 1.0e-5;
    /**
     * D21: the largest share of a near-pure step's fluid its traces may take. The step at the pure species' point leaves
     * out the traces' dilution of that fluid, which moves the real step by about this share in ln P; a step needing a
     * smaller fluid (deep deposition) is the general solve's, whose vapour-solid region is then at least this share
     * wide in drive. At 10 times {@link #NEAR_PURE_TRACES} every near-pure feed keeps at least 90 % of its step on the
     * rule, and the handover moves the answer by at most 1e-4 in ln P; unbounded, the rule and the general solve
     * alternate along {@code H(T)} where the traces make most of the fluid.
     */
    private static final double STEP_FLUID_TRACES = 1.0e-4;
    /**
     * The margin of the triple-point screen, a fraction of the feed: the feed's own phases (a near-pure feed's traces in
     * one fluid) move the triangle's balances by about the trace fraction, far inside it.
     */
    private static final double TRIANGLE_SCREEN = 1.0e-3;
    /** Evaluations of the fluid at its own composition while a near-pure step's split converges (D21). */
    private static final int NEAR_PURE_ITERATIONS = 8;
    /** A PH (relative in T) or UV (in ln P) bracket this narrow that straddles a binary's three-phase step is solved there (P5). */
    private static final double THREE_PHASE_BRACKET = 0.02;

    // Outcomes of the hydrocarbon core.
    private static final int SINGLE = 0;
    private static final int SPLIT = 1;
    private static final int MERGED = 2;
    private static final int FAILED = 3;

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
    /** The same test with {@link #UNRESOLVED_STABILITY_BUDGET_FACTOR} times the per-trial budget (P3 WP7d). */
    private final TangentPlaneStability patientStability;
    private final Settings settings;
    private final FreeWaterModel freeWater;
    private final int count;
    private final int basis;
    private final int[] basisOfComponent;
    private final int waterIndex;
    private final int[] waterChemistry;
    private final double[] translations;
    private final EquilibriumResult.Coverage convergedCoverage;
    /** The crystals this service evaluates (P4 stage 2a), each anchored once to this fluid family. */
    private final List<Crystal> crystals;
    /**
     * The coverage of the answers outside the critical band of each qualified crystal competition whose crystals all have
     * their evaluator here, built once.
     */
    private final Map<PhaseCompetition, EquilibriumResult.Coverage> crystalCoverages;
    /**
     * The corners of pure water's triple-point triangle in UV, {@code (u_V, v_V, u_L, v_L, u_s, v_s)} of its steam, liquid
     * and ice ({@link #waterTriangle}), evaluated once; null without free water.
     */
    private final double[] waterTriangle;

    /**
     * @param evaluator its basis must be the contract's basis without water, in the same order, and its family the
     *        contract identity's
     * @param freeWater the water model of a {@link WaterParticipation#SEPARATE_FREE_WATER} contract, whose revision the
     *        contract identity must name; {@code null} leaves requests with water {@code NOT_IMPLEMENTED}
     */
    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator, Settings settings,
                              FreeWaterModel freeWater) {
        this(contract, evaluator, settings, freeWater, List.of());
    }

    /**
     * The service of a package with its crystals (P4 stage 2a): {@link PhaseContract#forPackage}, the package's cubic
     * evaluator, the default settings, no water model (a request with water is {@code NOT_IMPLEMENTED}, with crystals or
     * without, unless the contract refuses it first as {@code WATER_CHEMISTRY_NOT_MODELLED}, when it also holds a species that
     * needs aqueous chemistry), and an evaluator of every crystal the contract admits.
     */
    public static FluidTpEquilibrium forPackage(MaterialCatalog catalog, String packageId) {
        PhaseContract contract = PhaseContract.forPackage(catalog, packageId);
        List<CrystalPhaseEvaluator> solids = new ArrayList<>();
        PhaseCompetition competition = contract.crystalCompetition();
        if (competition != null) {
            for (String crystal : competition.crystals()) solids.add(CrystalPhaseEvaluator.forPackage(catalog, packageId, crystal));
        }
        return new FluidTpEquilibrium(contract, CubicPhaseEvaluator.forPackage(catalog, packageId), Settings.DEFAULT, null, solids);
    }

    /**
     * @param crystalEvaluators the crystals this service evaluates (P4 stage 2a): each made of a species of the
     *        evaluator's basis, named by a qualified competition of the contract, with the same ideal-gas function as
     *        the evaluator's for its species (so the chemical potentials compare); each is anchored here, once, to this
     *        evaluator's pure liquid at its triple point. A qualified crystal without an evaluator here leaves the
     *        competitions naming it {@code NOT_IMPLEMENTED}.
     */
    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator, Settings settings,
                              FreeWaterModel freeWater, List<CrystalPhaseEvaluator> crystalEvaluators) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.freeWater = freeWater;
        if (!contract.identity().evaluatorFamily().equals(evaluator.family())) {
            throw new IllegalArgumentException("Contract identity names family " + contract.identity().evaluatorFamily()
                    + ", evaluator is " + evaluator.family());
        }
        List<String> withoutWater = new ArrayList<>(contract.components());
        if (contract.waterComponent() != null) withoutWater.remove(contract.waterComponent());
        if (!withoutWater.equals(evaluator.components())) {
            throw new IllegalArgumentException("The evaluator basis must be the contract basis without water, in order");
        }
        if (freeWater != null) {
            if (contract.water() != WaterParticipation.SEPARATE_FREE_WATER) {
                throw new IllegalArgumentException("A free-water model needs a separate-free-water contract");
            }
            String expected = ThermoIdentity.SEPARATE_FREE_WATER_FORMULATION + ":" + freeWater.revision();
            if (!contract.identity().waterModelRevision().equals(expected)) {
                throw new IllegalArgumentException("The contract identity names water model "
                        + contract.identity().waterModelRevision() + ", the free-water model is " + expected);
            }
            Objects.requireNonNull(freeWater.ice(), "the free-water rule needs its ice below the melting line (WP4)");
        }
        count = evaluator.componentCount();
        basis = contract.components().size();
        basisOfComponent = new int[count];
        for (int i = 0; i < count; i++) basisOfComponent[i] = contract.index(evaluator.components().get(i));
        waterIndex = contract.waterIndex();
        waterChemistry = contract.waterChemistrySpecies().stream().mapToInt(contract::index).sorted().toArray();
        kernel = evaluator.kernel();
        translations = evaluator.translations();
        stability = new TangentPlaneStability(kernel, settings.stability());
        var s = settings.stability();
        patientStability = new TangentPlaneStability(kernel, new TangentPlaneStability.Settings(s.instabilityTolerance(),
                s.convergenceTolerance(), s.newtonSwitch(), s.maximumSuccessiveSubstitutions(), s.accelerationCycle(),
                Math.multiplyExact(s.maximumIterations(), UNRESOLVED_STABILITY_BUDGET_FACTOR), s.trivialDistance(),
                s.pureComponentTrialLimit(), s.exhaustive()));
        convergedCoverage = new EquilibriumResult.Coverage(contract.fluidCoverage().grade(),
                contract.fluidCoverage().evidence() + FLUID_ONLY_NOTE);
        List<Crystal> built = new ArrayList<>();
        for (CrystalPhaseEvaluator solid : List.copyOf(crystalEvaluators)) {
            for (Crystal other : built) {
                if (other.id().equals(solid.crystal())) throw new IllegalArgumentException("Crystal " + solid.crystal() + " given twice");
            }
            built.add(crystal(solid));
        }
        crystals = List.copyOf(built);
        Map<PhaseCompetition, EquilibriumResult.Coverage> coverages = new HashMap<>();
        for (PhaseCompetition competition : contract.qualifiedCompetitions()) {
            if (competition.crystals().isEmpty()) continue;
            List<Crystal> set = new ArrayList<>();
            for (String id : competition.crystals()) {
                Crystal c = crystalById(id);
                if (c == null) {
                    set = null;
                    break;
                }
                set.add(c);
            }
            if (set != null) coverages.put(competition, solidCoverage(convergedCoverage, competition, set));
        }
        crystalCoverages = Map.copyOf(coverages);
        waterTriangle = freeWater == null ? null : waterTriangle(freeWater);
    }

    /** The crystal of {@code solid} on this service: checked against the evaluator and the contract, anchored once. */
    private Crystal crystal(CrystalPhaseEvaluator solid) {
        int component = evaluator.components().indexOf(solid.component());
        if (component < 0) throw new IllegalArgumentException("Crystal " + solid.crystal() + " is made of " + solid.component()
                + ", which the evaluator does not carry");
        boolean named = false;
        for (PhaseCompetition competition : contract.qualifiedCompetitions()) named |= competition.crystals().contains(solid.crystal());
        if (!named) throw new IllegalArgumentException("Crystal " + solid.crystal() + " is in no competition the contract qualifies");
        IdealGasFunction fluidGas = evaluator.idealGas(component);
        double t = solid.record().anchor().temperature();
        double p = solid.record().anchor().pressure();
        if (fluidGas.enthalpy(t) != solid.idealGas().enthalpy(t) || fluidGas.entropy(t, p) != solid.idealGas().entropy(t, p)) {
            throw new IllegalArgumentException("Crystal " + solid.crystal() + " is split against another ideal gas than the evaluator's "
                    + solid.component() + ": the chemical potentials would not compare");
        }
        var anchored = solid.anchored(solid.anchor(evaluator));
        var coverage = solid.record().coverage();
        int slot = contract.index(solid.component());
        String evidence = solid.crystal() + " (" + solid.record().revision() + ", " + coverage.grade().key() + ": " + coverage.evidence() + ")";
        double[] triple = familyTriplePoint(anchored, component);
        return new Crystal(anchored, solid.crystal(), component, slot, gradeOf(coverage.grade()), evidence,
                triple[0], triple[1], tripleTriangle(anchored, component, triple[0], triple[1]));
    }

    /**
     * The molar internal energies and volumes of the pure species' vapour, liquid and crystal at its family's triple
     * point, {@code (u_V, v_V, u_L, v_L, u_s, v_s)}: the corners of the triple point's triangle in UV (P5), evaluated once;
     * null when the family refuses that point (no triple-point answers then).
     */
    private double[] tripleTriangle(CrystalPhaseEvaluator.Anchored crystal, int component, double t, double p) {
        double[] pure = new double[count];
        pure[component] = 1.0;
        var scratch = evaluator.newWorkspace();
        try {
            PhaseState vapour = evaluator.evaluate(t, p, pure, Root.VAPOR, scratch, evaluator.newState());
            PhaseState liquid = evaluator.evaluate(t, p, pure, Root.LIQUID, scratch, evaluator.newState());
            PhaseState solid = crystal.evaluate(t, p, new PhaseState(1));
            return new double[] {vapour.molarInternalEnergy(), vapour.molarVolume(), liquid.molarInternalEnergy(), liquid.molarVolume(),
                    solid.molarInternalEnergy(), solid.molarVolume()};
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return null;
        }
    }

    /**
     * The corners of pure water's triple-point triangle in UV, as {@link #tripleTriangle} gives a crystal's species: the
     * molar internal energies and volumes {@code (u_V, v_V, u_L, v_L, u_s, v_s)} of the free-water rule's steam, the
     * water model's liquid and the ice at the ice record's triple point ({@link FreeWaterIce#tripleTemperature()},
     * {@link FreeWaterIce#triplePressure()}), each as its phase states it ({@link #gasPhase}, {@link #freeWaterPhase},
     * {@link #icePhase}); null when the model refuses that point.
     */
    private static double[] waterTriangle(FreeWaterModel water) {
        FreeWaterIce ice = water.ice();
        double t = ice.tripleTemperature();
        double p = ice.triplePressure();
        try {
            double[] liquid = new double[2];
            water.liquid(t, p, liquid);
            CrystalReference.State solid = ice.properties(t, p);
            // The virial steam: u = h_ig - R T - p T dB/dT, v = R T / p + B.
            double[] corners = {water.vapor().enthalpy(t) - R * t - p * t * SteamVirial.temperatureDerivative(t),
                    SteamVirial.molarVolume(t, p), liquid[1] - p * liquid[0], liquid[0],
                    solid.enthalpy() - p * solid.volume(), solid.volume()};
            for (double corner : corners) if (!Double.isFinite(corner)) return null;
            return corners;
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return null;
        }
    }

    /**
     * The family's own triple point of a crystal's pure species (P5): {@code mu_V = mu_L = mu_s}, Newton on
     * {@code (1/T, ln P)} from the anchor's triple point with {@code d(mu/RT)/d(1/T) = h/R} and
     * {@code d(mu/RT)/d ln P = Z} of each phase, until a step moves {@code 1/T} by at most {@code 1e-13} of itself and
     * {@code ln P} by at most {@code 1e-12} (the chemical potentials, some {@code 200 R T} on the formation datum, carry a
     * rounding of about {@code 1e-13 R T}); the anchor's point when it does not converge (it lies within a fraction of a
     * percent of it on a family fitted to the triple point).
     */
    private double[] familyTriplePoint(CrystalPhaseEvaluator.Anchored crystal, int component) {
        double t = crystal.anchor().temperature();
        double p = crystal.anchor().pressure();
        double[] pure = new double[count];
        pure[component] = 1.0;
        var scratch = evaluator.newWorkspace();
        PhaseState vapour = evaluator.newState();
        PhaseState liquid = evaluator.newState();
        PhaseState solid = new PhaseState(1);
        double x = 1.0 / t;
        double y = DeterministicMath.log(p);
        try {
            for (int k = 0; k < 50; k++) {
                double tk = 1.0 / x;
                double pk = DeterministicMath.exp(y);
                evaluator.evaluate(tk, pk, pure, Root.VAPOR, scratch, vapour);
                evaluator.evaluate(tk, pk, pure, Root.LIQUID, scratch, liquid);
                crystal.evaluate(tk, pk, solid);
                double rt = R * tk;
                double g1 = (vapour.chemicalPotential(component) - liquid.chemicalPotential(component)) / rt;
                double g2 = (liquid.chemicalPotential(component) - solid.molarGibbsEnergy()) / rt;
                double a = (vapour.molarEnthalpy() - liquid.molarEnthalpy()) / R;
                double b = vapour.compressibilityFactor() - liquid.compressibilityFactor();
                double c = (liquid.molarEnthalpy() - solid.molarEnthalpy()) / R;
                double d = liquid.compressibilityFactor() - pk * solid.molarVolume() / rt;
                double det = a * d - b * c;
                double dx = -(g1 * d - b * g2) / det;
                double dy = -(a * g2 - c * g1) / det;
                if (!Double.isFinite(dx) || !Double.isFinite(dy)) break;
                x += dx;
                y += dy;
                if (Math.abs(dx) <= 1.0e-13 * x && Math.abs(dy) <= 1.0e-12) return new double[] {1.0 / x, DeterministicMath.exp(y)};
            }
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            // The anchor's point below.
        }
        return new double[] {t, p};
    }

    private Crystal crystalById(String id) {
        for (Crystal c : crystals) if (c.id().equals(id)) return c;
        return null;
    }

    /**
     * The anchored crystal {@code id} of this service (its integration constants solved once at construction), or
     * {@code null} when the service has no evaluator for it: the one view of the crystal a consumer compares chemical
     * potentials with, so its answers and this service's agree (the fluid network's vessel refresh, D23).
     */
    public CrystalPhaseEvaluator.Anchored anchoredCrystal(String id) {
        Crystal c = crystalById(id);
        return c == null ? null : c.anchored();
    }

    private static EquilibriumResult.CoverageGrade gradeOf(MaterialCoverage.Grade grade) {
        return switch (grade) {
            case QUALIFIED -> EquilibriumResult.CoverageGrade.QUALIFIED;
            case ESTIMATED_DECLARED_ERROR -> EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR;
            case RESEARCH_ONLY -> EquilibriumResult.CoverageGrade.RESEARCH_ONLY;
            case UNAVAILABLE -> EquilibriumResult.CoverageGrade.UNAVAILABLE;
        };
    }

    /**
     * A crystal on this service (P4 stage 2a): its anchored view (the integration constants solved once at
     * construction), the species' EOS index and contract slot, the record's grade
     * and its evidence line, and the family's own triple point (P5: where the crystal and the family's vapour and liquid
     * of the pure species have equal chemical potentials; the anchor's triple point pins the crystal to the liquid only,
     * and the cubic's saturation pressure there differs slightly), with the corners of its triangle in UV
     * ({@link #tripleTriangle}).
     */
    private record Crystal(CrystalPhaseEvaluator.Anchored anchored, String id, int component, int slot,
                           EquilibriumResult.CoverageGrade grade, String evidence,
                           double tripleTemperature, double triplePressure, double[] triangle) {
        /** The anchor's fusion enthalpy, J/mol (the record's effective value on this family, D16). */
        double fusionEnthalpy() { return anchored.record().anchor().fusionEnthalpy(); }
    }

    public PhaseContract contract() { return contract; }
    public CubicPhaseEvaluator evaluator() { return evaluator; }
    public Settings settings() { return settings; }
    /** The water model, {@code null} when requests with water are not implemented by this service. */
    public FreeWaterModel freeWater() { return freeWater; }

    /** Caller-owned scratch for one thread; reusing it makes a call allocate little more than its result, and results do
     * not depend on what the workspace computed before. */
    public Workspace newWorkspace() { return new Workspace(this); }

    public EquilibriumResult tp(EquilibriumRequest request) { return tp(request, newWorkspace()); }
    public EquilibriumResult ph(EquilibriumRequest request) { return ph(request, newWorkspace()); }
    public EquilibriumResult uv(EquilibriumRequest request) { return uv(request, newWorkspace()); }

    /** Scratch for one thread. */
    public static final class Workspace {
        private final FluidTpEquilibrium owner;
        private final double[] basisAmounts;
        private final double[] feed;
        private final double[] z;
        private final int[] present;
        private int presentCount;
        private final double[] logK, k, x, y, newLogK;
        private double[] delta, previousDelta;
        private final double[] liquidLogPhi, vaporLogPhi;
        private final double[] liquidAmounts, vaporAmounts;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation first, second;
        private final TangentPlaneStability.Workspace stability;
        private final CubicPhaseEvaluator.Workspace phases;
        private final PhaseState vaporState, liquidState;
        private int kernelEvaluations, derivativeEvaluations, rachfordRiceSteps, iterations, extrapolations,
                newtonIterations;
        private double lastChange;
        // Branch pressures of the hydrocarbon core: equal on the dry path.
        private double liquidPressure, vaporPressure;
        private boolean branched;
        private final double[] vaporShifts;
        // The Newton finish.
        private final double[] vaporMoles, liquidMoles, trialVapor, trialLiquid, step, gradient, scale, reference, trial;
        private final double[] hessian, factor, direction;
        private final PengRobinsonKernel.Derivatives vaporDerivatives, liquidDerivatives;
        private double lambda, tieLine, seedDistance, gibbsScale;
        private boolean trialIsVapour;
        // The split Rachford-Rice found last (D24): each phase's fraction of the feed, the minor one solved for and the
        // major one its complement; liquidMinor when the liquid is the minor phase.
        private double vapourFraction, liquidFraction;
        private boolean liquidMinor;
        // The kernel evaluation lowerGibbsRoot chose last; whether each product of a dry split is liquid-like and denser than
        // the cubic's critical volume (P3 WP7d, WP11).
        private PengRobinsonKernel.Evaluation chosen;
        private boolean liquidProductDenseLiquidLike, vapourProductDenseLiquidLike;
        // Outcome of the hydrocarbon core.
        private TangentPlaneStability.Result feedResult;
        private Root singleRoot;
        private String failure;
        private double residual, productDistance;
        // Free water: the gas, the liquid water and the ice (WP4) of a wet answer.
        private final PhaseState gasState, waterState, iceState = new PhaseState(1);
        private final double[] gasComposition, gasLogPhi, gasIdealGibbs, waterProperties;
        private final double[] one = {1.0}, oneLogPhi = new double[1], oneIdealGibbs = new double[1];
        // The request's material, set before any calculation.
        private boolean wet;
        private double hydrocarbonTotal, waterTotal, total;
        // PH and UV: the search window, the searches, counters over the whole search, the Jacobian of the UV Newton phase.
        private double minimumT, maximumT, minimumP, maximumP;
        /** Where a PH or UV search starts: inside the data of the models the material uses (see {@link #searchWindow}). */
        private double startMinimumT, startMaximumT;
        private final Search search = new Search();
        private final Search inner = new Search();
        private int outerEvaluations, outerKernel, outerDerivative;
        private final PhaseDerivatives slope;
        private final double[] jacobian = new double[4];
        private boolean jacobianValid;
        private double coexistenceX, coexistenceResidual, bestT, bestResidual, innerPressure;
        /** Solves of the free water's freeze at a fixed volume in this UV search ({@link #FREEZE_ATTEMPTS}). */
        private int freezeAttempts;
        private EquilibriumResult innerResult;
        private boolean innerCoexistence;
        private String innerFailure;
        // The nested UV search's pressure solves: accepted residual, whether the last was inexact, the last secant (of one
        // classification), whether the last answer was one phase.
        private double innerLooseness, innerSlope;
        private boolean innerLoose, innerSingle;
        // The last two pressure solves at different temperatures, (T, ln P): the next solve's start.
        private double innerT1, innerY1, innerT2, innerY2;
        // Crystals (P4 stage 2a): the request's resolved competition, the competing crystals, the feed saved while a reduced
        // feed is flashed, the solid and pure-species scratch, fluid equilibria of the last crystal answer.
        private PhaseCompetition competition;
        private final Crystal[] active;
        private int activeCount;
        private final double[] savedFeed, savedBasis, pure;
        /** D21: the fluid a near-pure crystal step leaves, the traces with it. */
        private final double[] stepFluid;
        private final PhaseState solidState = new PhaseState(1);
        private final PhaseState pureState;
        private final PhaseDerivatives reduced;
        private final PhaseDerivatives solidSlope = new PhaseDerivatives(1);
        private int fluidEquilibria;
        private double reducedSlope;
        // P5: the phases of a binary's three-phase point and of a pure species' triple point.
        private final PhaseState threeLiquid, threeVapour;

        private Workspace(FluidTpEquilibrium owner) {
            this.owner = owner;
            int n = owner.count;
            basisAmounts = new double[owner.basis];
            feed = new double[n];
            z = new double[n];
            present = new int[n];
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
            vaporShifts = new double[n];
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
            vaporDerivatives = owner.kernel.newDerivatives();
            liquidDerivatives = owner.kernel.newDerivatives();
            gasState = new PhaseState(owner.basis);
            waterState = new PhaseState(1);
            gasComposition = new double[owner.basis];
            gasLogPhi = new double[owner.basis];
            gasIdealGibbs = new double[owner.basis];
            waterProperties = new double[2];
            slope = owner.evaluator.newDerivatives();
            active = new Crystal[owner.crystals.size()];
            savedFeed = new double[n];
            savedBasis = new double[owner.basis];
            pure = new double[n];
            stepFluid = new double[n];
            pureState = owner.evaluator.newState();
            reduced = owner.evaluator.newDerivatives();
            threeLiquid = owner.evaluator.newState();
            threeVapour = owner.evaluator.newState();
        }

        /** The pressure a root's branch is evaluated at. */
        private double pressureOf(Root root) { return root == Root.VAPOR ? vaporPressure : liquidPressure; }
    }

    /**
     * A bracketed search for the root of an increasing function of one variable (P3 WP6b): the latest point with a
     * negative and with a positive residual, and the TP answers there. Its next point is the proposed Newton or secant
     * step while that stays strictly inside the bracket and the residual keeps shrinking (below half the last one at
     * least every second step), otherwise the bracket's midpoint; before a bracket exists, the step capped and kept
     * inside the window.
     */
    private static final class Search {
        private double minimum, maximum;
        private double lower, upper, lowerResidual, upperResidual;
        private EquilibriumResult lowerResult, upperResult;
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
         * alone; a wet feed's hold the free water's condensed phase beside it, the same at both ends (D40 H4).
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
     * bracket closes on (a pure component's coexistence, free water boiling) is answered from the energy balance, any
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
     * inside its two-phase dome, or free water boiling, closes the pressure bracket on the step of {@code V(P)} and the
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
    private EquilibriumResult implementationChecks(Specification specification, PhaseCompetition competition, Workspace w) {
        w.activeCount = 0;
        for (String id : competition.crystals()) {
            Crystal c = crystalById(id);
            if (c == null) {
                return unsupported(specification, competition, UnsupportedReason.NOT_IMPLEMENTED,
                        "solid phases compete from P4 on; this service was built without the evaluator of crystal " + id);
            }
            w.active[w.activeCount++] = c;
        }
        w.wet = waterIndex >= 0 && w.basisAmounts[waterIndex] > 0.0;
        if (w.wet && freeWater == null) {
            return unsupported(specification, competition, UnsupportedReason.NOT_IMPLEMENTED,
                    "the separate free-water phase needs a FreeWaterModel (P3); this service was built without one");
        }
        double hydrocarbons = 0.0;
        for (int i = 0; i < count; i++) {
            w.feed[i] = w.basisAmounts[basisOfComponent[i]];
            hydrocarbons += w.feed[i];
        }
        w.hydrocarbonTotal = hydrocarbons;
        w.waterTotal = w.wet ? w.basisAmounts[waterIndex] : 0.0;
        w.total = hydrocarbons + w.waterTotal;
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
    private EquilibriumResult evaluateTp(double t, double p, PhaseCompetition competition, Workspace w) {
        return evaluateTp(t, p, competition, w.wet && frozen(t, p), w);
    }

    /**
     * Whether free water at {@code (t, p)} is ice: below its melting line {@code T_m(P)} (WP4). On the line the liquid
     * is the condensed phase; the freeze there is a step a PH or UV balance splits ({@link #waterFreezing}).
     */
    private boolean frozen(double t, double p) {
        return t < freeWater.ice().meltingTemperature(p);
    }

    /**
     * The fugacity of the condensed free water at {@code (t, p)}, Pa: the ice's below the melting line, the liquid's
     * saturation pressure on and above it. Continuous across the line and increasing with {@code t}.
     */
    private double condensedPressure(double t, double p) {
        return frozen(t, p) ? freeWater.ice().fugacity(t, p) : freeWater.saturationPressure(t);
    }

    /**
     * {@link #evaluateTp(double, double, PhaseCompetition, Workspace)} with the free water's condensed phase given:
     * {@code frozen} ice, else liquid (a freeze step evaluates its liquid side on the melting line itself).
     */
    private EquilibriumResult evaluateTp(double t, double p, PhaseCompetition competition, boolean frozen, Workspace w) {
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
            if (w.wet) {
                return w.activeCount > 0 ? solveWetWithCrystals(t, p, frozen, competition, w)
                        : solveWet(t, p, w.hydrocarbonTotal, w.waterTotal, frozen, competition, w);
            }
            return w.activeCount > 0 ? solveWithCrystals(t, p, competition, w) : solveDry(t, p, competition, w);
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
    private EquilibriumResult graded(EquilibriumResult answer, Crystal onset, Workspace w) {
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
     * species, with water the steam's range and its partial-pressure qualification
     * ({@link FreeWaterModel#vaporPartialPressureLimit}), and the record of each crystal among the phases and of an
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
            if (!(w.feed[i] > 0.0)) continue;
            IdealGasFunction gas = evaluator.idealGas(i);
            if (t < gas.minimumTemperature() || t > gas.maximumTemperature()) {
                out = note(out, "the ideal gas of " + evaluator.components().get(i) + " " + gas.minimumTemperature() + " .. "
                        + gas.maximumTemperature() + " K");
            }
        }
        if (w.wet) {
            IdealGasFunction steam = freeWater.vapor();
            if (t < steam.minimumTemperature() || t > steam.maximumTemperature()) {
                out = note(out, "the water model " + steam.minimumTemperature() + " .. " + steam.maximumTemperature() + " K");
            }
            EquilibriumResult.FreeWater split = answer.freeWater();
            if (split != null && split.waterVapor() > 0.0) {
                double limit = freeWater.vaporPartialPressureLimit(t);
                if (split.waterPartialPressure() > limit) {
                    out = note(out, "the steam's qualified partial pressure " + limit + " Pa (" + split.waterPartialPressure() + " Pa)");
                }
            }
        }
        for (PhaseAmounts phase : answer.phases()) {
            if (phase.kind() != PhaseKind.SOLID) continue;
            Crystal c = crystalById(phase.crystal());
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
    private static double crystalCeiling(Workspace w) {
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
        w.presentCount = 0;
        for (int i = 0; i < count; i++) {
            w.z[i] = w.hydrocarbonTotal > 0.0 ? w.feed[i] / w.hydrocarbonTotal : 0.0;
            if (w.feed[i] > 0.0) w.present[w.presentCount++] = i;
        }
        double[] envelope = contract.domain().envelope();
        double low = envelope[0];
        double high = envelope[1];
        for (int a = 0; a < w.presentCount; a++) {
            IdealGasFunction gas = evaluator.idealGas(w.present[a]);
            low = Math.max(low, gas.minimumTemperature());
            high = Math.min(high, gas.maximumTemperature());
        }
        if (w.wet) {
            low = Math.max(low, freeWater.vapor().minimumTemperature());
            high = Math.min(high, freeWater.vapor().maximumTemperature());
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
                && w.hydrocarbonTotal > 0.0 && inBand(t, p, w)) {
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
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                IdealGasFunction gas = evaluator.idealGas(i);
                f += w.feed[i] * gas.enthalpy(t);
                slope += w.feed[i] * gas.heatCapacity(t);
            }
            if (w.wet) {
                f += w.waterTotal * freeWater.vapor().enthalpy(t);
                slope += w.waterTotal * freeWater.vapor().heatCapacity(t);
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
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            sum += w.feed[i] * evaluator.idealGas(i).heatCapacity(t);
        }
        if (w.wet) sum += w.waterTotal * freeWater.vapor().heatCapacity(t);
        return isochoric ? sum - w.total * R : sum;
    }

    /** The derivatives of a dry answer with one phase (its own root), or null (several phases, water, a refusal). */
    private PhaseDerivatives dryDerivatives(EquilibriumResult result, double t, double p, Workspace w) {
        if (w.wet || result.phases().size() != 1) return null;
        PhaseAmounts only = result.phases().get(0);
        if (only.kind() == PhaseKind.SOLID) {
            // A crystal alone (P4 stage 2a): its own c_p, expansion and compressibility.
            Crystal c = crystalById(only.crystal());
            if (c == null) return null;
            w.outerDerivative++;
            return c.anchored().derivatives(t, p, w.solidSlope);
        }
        w.outerKernel++;
        w.outerDerivative++;
        try {
            return evaluator.derivatives(t, p, w.feed, result.phases().get(0).state().root(), w.phases, w.slope);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            // At root coalescence (a pure critical point) the kernel promises no derivative: the search takes a secant.
            return null;
        }
    }

    /** {@code (dH/dT)_P} of one dry phase, {@code NaN} otherwise. */
    private double dryHeatCapacity(EquilibriumResult result, double t, double p, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        return d == null ? Double.NaN : w.total * d.enthalpyTemperatureDerivative();
    }

    /** {@code (dU/dT)_V} of one dry phase, {@code NaN} otherwise. */
    private double dryIsochoricHeatCapacity(EquilibriumResult result, double t, Workspace w) {
        double p = result.phases().get(0).pressure();
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        if (d == null) return Double.NaN;
        double dvdt = d.volumeTemperatureDerivative();
        double dvdp = d.volumePressureDerivative();
        double dudt = d.enthalpyTemperatureDerivative() - p * dvdt;
        double dudp = d.enthalpyPressureDerivative() - d.state().molarVolume() - p * dvdp;
        return w.total * (dudt - dudp * dvdt / dvdp);
    }

    /** {@code -(d ln V/d ln P)_T} of one dry phase, {@code NaN} otherwise. */
    private double dryCompressibility(EquilibriumResult result, double t, double p, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        return d == null ? Double.NaN : -p * d.volumePressureDerivative() / d.state().molarVolume();
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
                EquilibriumResult answer = holdsSolid(result) ? pureCrystalStep(Specification.PH, t, p, target, false, competition, w)
                        : pureCoexistence(Specification.PH, t, p, target, false, competition, w);
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
            if (s.bracketed() && (s.width() <= tolerance * t || !w.wet && (w.presentCount == 1 && s.straddlesPureCoexistence()
                    || pureCrystal(w) != null && s.straddlesCrystalStep())
                    || w.wet && (condensedStep(s.lowerResult, s.upperResult) || freezeStep(s.lowerResult, s.upperResult))
                    || binaryCrystal(w) != null && threePhaseDue(s, s.width() / t))) {
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
        boolean crystalStep = !w.wet && pureCrystal(w) != null && s.endHoldsSolid();
        if (crystalStep || !w.wet && w.presentCount == 1) {
            EquilibriumResult answer = crystalStep ? pureCrystalStep(Specification.PH, t, p, target, closed, competition, w)
                    : pureCoexistence(Specification.PH, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        } else if (binaryCrystal(w) != null && straddlesThreePhase(s)) {
            // P5: a binary's three-phase step (vapour, liquid and crystal at one temperature for the fixed pressure).
            EquilibriumResult answer = binaryThreePhase(Specification.PH, s, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        } else if (w.wet && condensedStep(s.lowerResult, s.upperResult)) {
            double saturation = saturationTemperature(p, s.lower, s.upper, w);
            if (!Double.isNaN(saturation)) {
                EquilibriumResult answer = waterBoiling(Specification.PH, saturation, p, target, s.lowerResult, competition, w);
                if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            }
        } else if (w.wet && freezeStep(s.lowerResult, s.upperResult)) {
            // WP4: the free water freezes at its melting temperature at this pressure, on the line itself.
            double melting = freeWater.ice().meltingTemperature(p);
            double margin = 1.0e-6 * s.upper;
            if (melting >= s.lower - margin && melting <= s.upper + margin) {
                EquilibriumResult answer = waterFreezing(Specification.PH, melting, p, target, competition, w);
                if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            }
        }
        // Not closed yet: the search goes on (the step is not where the enthalpy lies).
        if (!closed) return null;
        return searchFailure(Specification.PH, competition, crystalStepType(crystalStep) + "the specified enthalpy " + target
                + " J lies in a step of H(T) at " + p + " Pa between " + s.lower + " and " + s.upper + " K (from "
                + (target + s.lowerResidual) + " to " + (target + s.upperResidual) + " J): no state of the model has it",
                s.lowerResult, w);
    }

    // UV.

    private EquilibriumResult solveUv(double u, double v, double hintT, double hintP, PhaseCompetition competition, Workspace w) {
        w.freezeAttempts = 0;
        // P5, D21: inside the triple point's triangle of the feed's one major species the answer is that triple point (or,
        // near-pure with too little fluid for its traces, the typed hold), whatever a search would meet on its way there.
        Crystal major = w.wet ? null : pureCrystal(w);
        if (major != null && nearTriangle(u, v, w.hydrocarbonTotal, major)) {
            EquilibriumResult triple = pureTriplePoint(u, v, competition, w);
            if (triple != null) return triple;
        }
        if (w.wet && !(w.hydrocarbonTotal > 0.0)) {
            // Pure water with only its own steam likewise: inside its triple point's triangle the answer is that triple point.
            EquilibriumResult triple = waterTriplePoint(u, v, competition, w);
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
        double single = w.wet ? Double.NaN : singlePhaseTemperature(u, v, w);
        double ideal = idealTemperature(u, true, w);
        double t = Math.clamp(Double.isNaN(single) ? ideal : Math.max(single, ideal), w.startMinimumT, w.startMaximumT);
        double p = w.wet ? Double.NaN : cubicPressure(t, v, w);
        EquilibriumResult first = null;
        if (Double.isNaN(p)) {
            // No stable single phase at the start (two phases, or water): an inexact volume solve there first, so the
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
            kernel.evaluate(t, w.hydrocarbonTotal * R * t / v, w.feed, Root.VAPOR, w.mixture, w.first);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return false;
        }
        w.outerKernel++;
        return true;
    }

    /** The untranslated molar volume of the dry feed at total volume {@code v}: the constant translation removed. */
    private double untranslatedVolume(double v, Workspace w) {
        double shift = 0.0;
        for (int a = 0; a < w.presentCount; a++) shift += w.z[w.present[a]] * translations[w.present[a]];
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
            for (int s = 0; s < w.presentCount; s++) {
                int i = w.present[s];
                value += w.z[i] * evaluator.idealGas(i).enthalpy(t);
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
        double free = volume - b;
        double denominator = volume * volume + 2.0 * b * volume - b * b;
        double pressure = R * t / free - a / denominator;
        double slope = -R * t / (free * free) + 2.0 * a * (volume + b) / (denominator * denominator);
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
                if (w.wet && f0 * g0 <= 0.0 && (straddlesFreeze(trial, current) || straddlesFreeze(current, trial))) {
                    // WP4: the step crosses the free water's melting line with the specified energy between its ends, as a
                    // PH bracket does: U(T) at the fixed volume rises by the latent heat over a fraction of a microkelvin
                    // there, so the target is answered on the line itself.
                    EquilibriumResult frozen = freezingAtVolume(u, v, p, competition, w);
                    if (frozen != null) return frozen.converged() ? outerAnswer(Specification.UV, frozen, u, v, true, null, w) : frozen;
                }
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
        double volume = d.state().molarVolume();
        double dvdt = d.volumeTemperatureDerivative();
        double dvdp = d.volumePressureDerivative();
        double rt = R * t;
        w.jacobian[0] = t * (d.enthalpyTemperatureDerivative() - p * dvdt) / rt - f0;
        w.jacobian[1] = p * (d.enthalpyPressureDerivative() - volume - p * dvdp) / rt;
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
            if (w.wet && last != null && crossesMelting(last, t, last.phases().get(0).pressure())) {
                // WP4: the step crosses the free water's melting line, where U(T) at the fixed volume rises by the latent
                // heat over a fraction of a microkelvin (no temperature resolves it): answered on the line itself, before a
                // volume solve on its far side.
                EquilibriumResult frozen = freezingAtVolume(u, v, last.phases().get(0).pressure(), competition, w);
                if (frozen != null) return frozen.converged() ? outerAnswer(Specification.UV, frozen, u, v, true, null, w) : frozen;
            }
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
                // A target inside a one-major feed's triple-point triangle was answered before the search (solveUv).
                boolean crystalStep = !w.wet && pureCrystal(w) != null && s.endHoldsSolid();
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
     * specification. A step of {@code V(P)} is the volume jump between the bracket's ends, wet or dry (owner decision
     * D40 A): a pure coexistence, a crystal's step, a binary's three-phase pressure, the free water's boiling or
     * freezing, each answered from the volume balance, or a typed failure.
     */
    private int innerUv(double t, double v, PhaseCompetition competition, Workspace w) {
        Search s = w.inner;
        double tolerance = settings.outerTolerance();
        s.reset(DeterministicMath.log(w.minimumP), DeterministicMath.log(w.maximumP));
        double cubic = w.wet || !w.innerSingle ? Double.NaN : cubicPressure(t, v, w);
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
                EquilibriumResult answer = holdsSolid(result) ? pureCrystalStep(Specification.UV, t, p, v, false, competition, w)
                        : pureCoexistence(Specification.UV, t, p, v, false, competition, w);
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
            if (s.bracketed() && (s.width() <= tolerance || w.presentCount == 1 && s.straddlesPureCoexistence()
                    || !w.wet && pureCrystal(w) != null && s.straddlesCrystalStep()
                    || w.wet && (condensedStep(s.upperResult, s.lowerResult) || freezeStep(s.lowerResult, s.upperResult))
                    || binaryCrystal(w) != null && threePhaseDue(s, s.width()))) {
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
        boolean crystalStep = !w.wet && pureCrystal(w) != null && s.endHoldsSolid();
        // The one hydrocarbon's coexistence: any jump of a dry pure feed, a wet one's between its liquid and its vapour
        // beside the same free water (D40 H4; the water's own boiling and freezing are the cases below).
        if (crystalStep || w.presentCount == 1 && (!w.wet || s.straddlesPureCoexistence())) {
            EquilibriumResult answer = crystalStep ? pureCrystalStep(Specification.UV, t, p, v, closed, competition, w)
                    : w.wet ? wetCoexistence(t, p, v, closed, competition, w)
                    : pureCoexistence(Specification.UV, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        } else if (binaryCrystal(w) != null && straddlesThreePhase(s)) {
            // P5: a binary's three-phase step (vapour, liquid and crystal at one pressure for the fixed temperature).
            EquilibriumResult answer = binaryThreePhase(Specification.UV, s, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        } else if (w.wet && condensedStep(s.upperResult, s.lowerResult)) {
            // At fixed temperature the condensed water lies at the high-pressure end, the steam at the low one.
            double saturation = condensedSaturationPressure(t);
            double y = DeterministicMath.log(saturation);
            if (saturation >= w.minimumP && saturation <= w.maximumP
                    && y >= Math.min(s.lower, s.upper) - 1.0e-6 && y <= Math.max(s.lower, s.upper) + 1.0e-6) {
                EquilibriumResult answer = waterBoiling(Specification.UV, t, saturation, v, s.upperResult, competition, w);
                if (answer != null) return found(answer, true, w);
            }
        } else if (w.wet && freezeStep(s.lowerResult, s.upperResult)) {
            // WP4: at fixed temperature below the triple point the ice lies at the low-pressure end, the liquid at the high
            // one; the free water freezes at the melting line's pressure for this temperature.
            double melting = freeWater.ice().meltingPressure(t);
            if (melting >= w.minimumP && melting <= w.maximumP) {
                double y = DeterministicMath.log(melting);
                if (y >= Math.min(s.lower, s.upper) - 1.0e-6 && y <= Math.max(s.lower, s.upper) + 1.0e-6) {
                    EquilibriumResult answer = waterFreezing(Specification.UV, t, melting, v, competition, w);
                    if (answer != null) return found(answer, true, w);
                }
            }
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

    // Coexistences TP leaves undetermined.

    /**
     * A pure hydrocarbon at its equal-fugacity point: TP leaves its split undetermined, PH (at the fixed pressure, the
     * energy balance) and UV (at the fixed temperature, the volume balance) determine it:
     * {@code target = N [(1 - beta) x_L + beta x_V]} with {@code x} the saturated states' molar enthalpy or volume. The
     * equal-fugacity temperature (PH) or pressure (UV) is solved on the kernel from {@code t} or {@code p}. Returns the
     * answer when {@code beta} lies in {@code [0, 1]} (within the specification tolerance, or always when
     * {@code clamp}); otherwise null with the jump's end on the target's side in {@code coexistenceX} (T for PH,
     * {@code ln P} for UV) and {@code coexistenceResidual}; {@code coexistenceX} is NaN when no equal-fugacity point
     * was found.
     */
    private EquilibriumResult pureCoexistence(Specification specification, double t, double p, double target, boolean clamp,
                                              PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        boolean energy = specification == Specification.PH;
        if (energy) t = coexistenceTemperature(t, p, w); else p = coexistencePressure(t, p, w);
        if (Double.isNaN(t) || Double.isNaN(p)) return null;
        PhaseState liquid = evaluator.evaluate(t, p, w.feed, Root.LIQUID, w.phases, w.liquidState);
        PhaseState vapour = evaluator.evaluate(t, p, w.feed, Root.VAPOR, w.phases, w.vaporState);
        w.outerKernel += 2;
        double n = w.hydrocarbonTotal;
        double liquidValue = energy ? liquid.molarEnthalpy() : liquid.molarVolume();
        double vapourValue = energy ? vapour.molarEnthalpy() : vapour.molarVolume();
        double beta = (target / n - liquidValue) / (vapourValue - liquidValue);
        // The specification tolerance as a phase fraction.
        double gap = vapourValue - liquidValue;
        double belowAllowance = SPECIFICATION_TOLERANCE * (energy ? R * t : liquidValue) / gap;
        double aboveAllowance = SPECIFICATION_TOLERANCE * (energy ? R * t : vapourValue) / gap;
        if (!clamp && (beta < -belowAllowance || beta > 1.0 + aboveAllowance) || !Double.isFinite(beta)) {
            boolean liquidEnd = beta < 0.0;
            double value = n * (liquidEnd ? liquidValue : vapourValue);
            w.coexistenceX = energy ? t : DeterministicMath.log(p);
            w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
            return null;
        }
        beta = Math.clamp(beta, 0.0, 1.0);
        boolean band = inBand(t, p, w);
        String why = band ? bandReason(t, p, w, Double.NaN) : null;
        String note = "pure coexistence at the equal-fugacity point (" + t + " K, " + p + " Pa): vapour fraction " + beta
                + " from the " + (energy ? "energy" : "volume") + " balance";
        List<PhaseAmounts> phases;
        Classification classification;
        if (beta == 0.0 || beta == 1.0) {
            boolean vapourOnly = beta == 1.0;
            classification = vapourOnly ? Classification.SINGLE_VAPOR : Classification.SINGLE_LIQUID;
            phases = List.of(new PhaseAmounts(vapourOnly ? PhaseKind.VAPOR : PhaseKind.LIQUID, null, t, p, w.basisAmounts,
                    (vapourOnly ? vapour : liquid).copy()));
        } else {
            int slot = basisOfComponent[w.present[0]];
            double amount = w.feed[w.present[0]];
            double[] vapourAmounts = new double[basis];
            double[] liquidAmounts = new double[basis];
            // The smaller share by its own formula, the larger by difference: conserved to rounding.
            if (beta <= 0.5) {
                vapourAmounts[slot] = beta * amount;
                liquidAmounts[slot] = amount - vapourAmounts[slot];
            } else {
                liquidAmounts[slot] = (1.0 - beta) * amount;
                vapourAmounts[slot] = amount - liquidAmounts[slot];
            }
            classification = Classification.VAPOR_LIQUID;
            phases = List.of(new PhaseAmounts(PhaseKind.VAPOR, null, t, p, vapourAmounts, vapour.copy()),
                    new PhaseAmounts(PhaseKind.LIQUID, null, t, p, liquidAmounts, liquid.copy()));
        }
        return converged(competition, classification, phases, List.of(), diagnostics(w, null, band), why, null, note);
    }

    /**
     * The one hydrocarbon of a wet feed at its equal-fugacity pressure at temperature {@code t} (UV, owner decision
     * D40 A): the dry pure coexistence ({@link #pureCoexistence}) beside the free water as the rule's TP answers on both
     * sides of the jump hold it, condensed at the state pressure (ice below the melting line), its steam in the gas at
     * the condensed water's fugacity {@code p_w}, the hydrocarbon's vapour at {@code P - p_w}
     * ({@link #coexistencePressure}). The vapour fraction from the volume balance
     * {@code V = n [(1 - beta) v_L + beta v_V] + (n_w - beta n s) v_c}, {@code s = p_w v_V/(R T)}. Returns the answer when
     * {@code beta} lies in [0, 1] (within the tolerance, or always when {@code clamp}); otherwise null with the jump's end
     * on the target's side in {@code coexistenceX} ({@code ln P}) and {@code coexistenceResidual}, NaN when no
     * equal-fugacity pressure was found or the gas would take all of the water as steam.
     */
    private EquilibriumResult wetCoexistence(double t, double p, double v, boolean clamp, PhaseCompetition competition,
                                             Workspace w) {
        w.coexistenceX = Double.NaN;
        p = coexistencePressure(t, p, w);
        if (Double.isNaN(p)) return null;
        boolean frozen = frozen(t, p);
        double saturation = condensedPressure(t, p);
        double pc = p - saturation;
        PhaseState liquid = evaluator.evaluate(t, p, w.feed, Root.LIQUID, w.phases, w.liquidState);
        PhaseState vapour = evaluator.evaluate(t, pc, w.feed, Root.VAPOR, w.phases, w.vaporState);
        w.outerKernel += 2;
        double condensedVolume;
        if (frozen) {
            condensedVolume = freeWater.ice().properties(t, p).volume();
        } else {
            freeWater.liquid(t, p, w.waterProperties);
            condensedVolume = w.waterProperties[0];
        }
        double n = w.hydrocarbonTotal;
        double water = w.waterTotal;
        double steamPerMole = SteamVirial.moles(saturation, t, vapour.molarVolume());
        double liquidEnd = n * liquid.molarVolume() + water * condensedVolume;
        double vapourEnd = n * vapour.molarVolume() + (water - n * steamPerMole) * condensedVolume;
        double gap = vapourEnd - liquidEnd;
        double beta = (v - liquidEnd) / gap;
        // The specification tolerance as a phase fraction.
        double belowAllowance = SPECIFICATION_TOLERANCE * liquidEnd / gap;
        double aboveAllowance = SPECIFICATION_TOLERANCE * vapourEnd / gap;
        if (!clamp && (beta < -belowAllowance || beta > 1.0 + aboveAllowance) || !Double.isFinite(beta)) {
            w.coexistenceX = DeterministicMath.log(p);
            w.coexistenceResidual = DeterministicMath.log(v / (beta < 0.0 ? liquidEnd : vapourEnd));
            return null;
        }
        beta = Math.clamp(beta, 0.0, 1.0);
        int only = w.present[0];
        double amount = w.feed[only];
        double vapourMoles;
        double liquidMoles;
        // The smaller share by its own formula, the larger by difference: conserved to rounding.
        if (beta <= 0.5) {
            vapourMoles = beta * amount;
            liquidMoles = amount - vapourMoles;
        } else {
            liquidMoles = (1.0 - beta) * amount;
            vapourMoles = amount - liquidMoles;
        }
        double steam = vapourMoles * steamPerMole;
        if (!(steam < water)) return null;
        double condensed = water - steam;
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (vapourMoles > 0.0) {
            Arrays.fill(w.vaporAmounts, 0.0);
            w.vaporAmounts[only] = vapourMoles;
            phases.add(gasPhase(t, p, pc, vapourMoles, steam, vapour, w));
        }
        if (liquidMoles > 0.0) {
            double[] amounts = new double[basis];
            amounts[basisOfComponent[only]] = liquidMoles;
            phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t, p, amounts, liquid.copy()));
        }
        phases.add(condensedPhase(t, p, saturation, condensed, frozen, w));
        Classification hydrocarbon = liquidMoles == 0.0 ? Classification.SINGLE_VAPOR
                : vapourMoles == 0.0 ? Classification.SINGLE_LIQUID : Classification.VAPOR_LIQUID;
        var split = freeWaterSplit(hydrocarbon, saturation, pc, steam > 0.0 ? saturation : 0.0, condensed, steam, frozen);
        boolean band = inBand(t, p, w);
        String why = band ? bandReason(t, p, w, Double.NaN) : null;
        String note = "pure coexistence beside free water at the equal-fugacity point (" + t + " K, " + p + " Pa, the "
                + (frozen ? "ice's" : "water's") + " fugacity " + saturation + " Pa): vapour fraction " + beta
                + " from the volume balance";
        return converged(competition, Classification.FREE_WATER, phases, List.of(), diagnostics(w, null, band), why, split, note);
    }

    /**
     * The pure component's equal-fugacity temperature at {@code p}, from {@code t}: Newton on
     * {@code g = ln phi_L - ln phi_V}, {@code dg/dT = (h^R_V - h^R_L)/(R T^2)} (the translation cancels), bisection once
     * both signs are seen; NaN when no liquid-vapour root pair is met or it does not converge.
     */
    private double coexistenceTemperature(double t, double p, Workspace w) {
        int only = w.present[0];
        double below = Double.NaN;
        double above = Double.NaN;
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            kernel.evaluate(t, p, w.feed, Root.LIQUID, w.mixture, w.first);
            kernel.evaluate(t, p, w.feed, Root.VAPOR, w.mixture, w.second);
            w.outerKernel += 2;
            double next;
            if (w.first.physicalRootCount() < 2) {
                // One root: the liquid-like one lies below the equal-fugacity temperature.
                if (PhaseIdentification.liquidLike(w.first, t, p)) below = t; else above = t;
                if (Double.isNaN(below) || Double.isNaN(above)) return Double.NaN;
                next = 0.5 * (below + above);
            } else {
                double g = w.first.logFugacityCoefficient(only) - w.second.logFugacityCoefficient(only);
                if (g == 0.0) return t;
                if (g < 0.0) below = t; else above = t;
                double slope = (w.second.residualEnthalpyJoulesPerMol() - w.first.residualEnthalpyJoulesPerMol()) / (R * t * t);
                next = t - g / slope;
                boolean bracketed = !Double.isNaN(below) && !Double.isNaN(above);
                if (!(slope > 0.0) || !Double.isFinite(next) || bracketed && !(next > below && next < above)) {
                    if (!bracketed) return Double.NaN;
                    next = 0.5 * (below + above);
                }
            }
            if (Math.abs(next - t) <= 1.0e-14 * t) return next;
            t = next;
        }
        return Double.NaN;
    }

    /**
     * The pure component's equal-fugacity pressure at {@code t}, from {@code p}: Newton in {@code ln P} on
     * {@code g = ln phi_L - ln phi_V}, {@code dg/d ln P = Z_L - Z_V}, bisection once both signs are seen; NaN when no
     * root pair is met or it does not converge. Beside free water (D40 H4) on the free-water rule's own condition, where
     * its TP answer turns from vapour to liquid: the liquid at {@code P}, the vapour at its partial pressure
     * {@code P_c = P - p_w} beside the condensed water's fugacity {@code p_w} ({@link #condensedPressure}), so
     * {@code g = ln phi_L(P) - ln phi_V(P_c) - ln(P_c/P) + p_w c/(R T)} (the volume translation {@code c} no longer
     * cancels: {@link TangentPlaneStability}) and {@code dg/d ln P = Z_L - Z_V P/P_c}, each branch with its own root.
     */
    private double coexistencePressure(double t, double p, Workspace w) {
        int only = w.present[0];
        double low = Double.NaN;
        double high = Double.NaN;
        double y = DeterministicMath.log(p);
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            double pressure = DeterministicMath.exp(y);
            double water = w.wet ? condensedPressure(t, pressure) : 0.0;
            double partial = pressure - water;
            if (!(partial > 0.0)) return Double.NaN;
            kernel.evaluate(t, pressure, w.feed, Root.LIQUID, w.mixture, w.first);
            kernel.evaluate(t, partial, w.feed, Root.VAPOR, w.mixture, w.second);
            w.outerKernel += 2;
            double next;
            boolean liquidRoot = w.first.physicalRootCount() > 1 || PhaseIdentification.liquidLike(w.first, t, pressure);
            boolean vapourRoot = w.second.physicalRootCount() > 1 || !PhaseIdentification.liquidLike(w.second, t, partial);
            if (!liquidRoot || !vapourRoot) {
                // One root: the liquid-like one lies above the equal-fugacity pressure, the vapour-like one below.
                if (liquidRoot) high = y; else low = y;
                if (Double.isNaN(low) || Double.isNaN(high)) return Double.NaN;
                next = 0.5 * (low + high);
            } else {
                double g = w.first.logFugacityCoefficient(only) - w.second.logFugacityCoefficient(only);
                if (w.wet) g += water * translations[only] / (R * t) - DeterministicMath.log(partial / pressure);
                if (g == 0.0) return pressure;
                if (g > 0.0) low = y; else high = y;
                double slope = w.first.compressibility()
                        - (w.wet ? w.second.compressibility() * pressure / partial : w.second.compressibility());
                next = y - g / slope;
                boolean bracketed = !Double.isNaN(low) && !Double.isNaN(high);
                if (!(slope < 0.0) || !Double.isFinite(next) || bracketed && !(next > low && next < high)) {
                    if (!bracketed) return Double.NaN;
                    next = 0.5 * (low + high);
                }
            }
            if (Math.abs(next - y) <= 1.0e-14) return DeterministicMath.exp(next);
            y = next;
        }
        return Double.NaN;
    }

    /**
     * A step of the free-water rule where the water boils (or, below its melting line, sublimes) at the state pressure:
     * one side has all water condensed (liquid or ice) and no gas at all (no hydrocarbons, or a hydrocarbon liquid
     * without vapour), the other all water as steam.
     */
    private static boolean condensedStep(EquilibriumResult condensedSide, EquilibriumResult steamSide) {
        if (condensedSide == null || steamSide == null) return false;
        EquilibriumResult.FreeWater condensed = condensedSide.freeWater();
        EquilibriumResult.FreeWater steam = steamSide.freeWater();
        if (condensed == null || steam == null) return false;
        if (!(condensed.condensed() > 0.0) || condensed.waterVapor() != 0.0 || steam.condensed() != 0.0) return false;
        for (PhaseAmounts phase : condensedSide.phases()) if (phase.kind() == PhaseKind.VAPOR) return false;
        return true;
    }

    /**
     * A step of the free-water rule where the water freezes at its melting temperature (WP4): the colder side holds the
     * condensed water as ice, the warmer as liquid; the gas and the hydrocarbons are continuous across the line (the
     * ice's fugacity meets the liquid's on it, {@link FreeWaterIce}), so the step is the condensed water's alone.
     */
    private static boolean freezeStep(EquilibriumResult iceSide, EquilibriumResult liquidSide) {
        if (iceSide == null || liquidSide == null) return false;
        EquilibriumResult.FreeWater ice = iceSide.freeWater();
        EquilibriumResult.FreeWater liquid = liquidSide.freeWater();
        if (ice == null || liquid == null) return false;
        return ice.ice() > 0.0 && ice.liquidWater() == 0.0 && liquid.liquidWater() > 0.0 && liquid.ice() == 0.0;
    }

    /**
     * Water's saturation temperature at {@code p} near the closed bracket {@code [low, high]}, by bisection on the
     * condensed water's vapour pressure ({@link #condensedPressure}: the ice's below the melting line, the liquid's above);
     * NaN unless it lies within 1e-6 (relative) of the bracket, where the step is then water boiling (or subliming) at the
     * state pressure.
     */
    private double saturationTemperature(double p, double low, double high, Workspace w) {
        double margin = 1.0e-6 * high;
        double a = Math.max(w.minimumT, low - margin);
        double b = Math.min(w.maximumT, high + margin);
        if (!(condensedPressure(a, p) < p) || !(condensedPressure(b, p) >= p)) return Double.NaN;
        for (int k = 0; k < 200 && Math.nextUp(a) < b; k++) {
            double middle = 0.5 * (a + b);
            if (condensedPressure(middle, p) < p) a = middle; else b = middle;
        }
        return 0.5 * (a + b);
    }

    /**
     * The pressure at which free water at {@code t} boils or sublimes, Pa: the liquid's saturation pressure, or below the
     * melting line the ice's fugacity at that pressure itself (two fixed-point steps; the ice's fugacity moves by 7e-10 per
     * pascal, so the second leaves it to rounding).
     */
    private double condensedSaturationPressure(double t) {
        double p = freeWater.saturationPressure(t);
        if (!frozen(t, p)) return p;
        p = freeWater.ice().fugacity(t, p);
        return freeWater.ice().fugacity(t, p);
    }

    /**
     * Free water boiling (or subliming) at the state pressure {@code p}, the condensed water's vapour pressure at
     * {@code t}: the hydrocarbons as on the condensed side (their liquid at {@code (t, p)}, no vapour), the water split
     * into steam at {@code p_w = p} and condensed water (liquid, or ice below the melting line), the steam fraction from
     * the energy (PH) or volume (UV) balance. Null when the balance puts the fraction outside [0, 1] by more than the
     * specification tolerance (the target lies in a part of the step no state of the rule spans, which the caller types
     * as not converged), and beside a competing crystal, whose hydrocarbons are not one liquid.
     */
    private EquilibriumResult waterBoiling(Specification specification, double t, double p, double target,
                                           EquilibriumResult condensedSide, PhaseCompetition competition, Workspace w) {
        if (condensedSide.freeWater().hydrocarbon().holdsCrystal()) return null;
        boolean energy = specification == Specification.PH;
        boolean frozen = frozen(t, p);
        double saturation = frozen ? freeWater.ice().fugacity(t, p) : freeWater.saturationPressure(t);
        PhaseState liquid = null;
        double hydrocarbonValue = 0.0;
        if (w.hydrocarbonTotal > 0.0) {
            liquid = evaluator.evaluate(t, p, w.feed, Root.LIQUID, w.phases, w.liquidState);
            w.outerKernel++;
            hydrocarbonValue = w.hydrocarbonTotal * (energy ? liquid.molarEnthalpy() : liquid.molarVolume());
        }
        double waterValue;
        if (frozen) {
            CrystalReference.State ice = freeWater.ice().properties(t, p);
            waterValue = energy ? ice.enthalpy() : ice.volume();
        } else {
            freeWater.liquid(t, p, w.waterProperties);
            waterValue = energy ? w.waterProperties[1] : w.waterProperties[0];
        }
        double water = w.waterTotal;
        double steamValue = energy ? freeWater.vapor().enthalpy(t) + SteamVirial.residualEnthalpy(t, p) : SteamVirial.molarVolume(t, p);
        double span = water * (steamValue - waterValue);
        double beta = (target - hydrocarbonValue - water * waterValue) / span;
        double allowance = SPECIFICATION_TOLERANCE * (energy ? w.total * R * t : Math.abs(target)) / Math.abs(span);
        if (!(beta >= -allowance && beta <= 1.0 + allowance)) return null;
        beta = Math.clamp(beta, 0.0, 1.0);
        double steam;
        double condensed;
        if (beta <= 0.5) {
            steam = beta * water;
            condensed = water - steam;
        } else {
            condensed = (1.0 - beta) * water;
            steam = water - condensed;
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (steam > 0.0) phases.add(gasPhase(t, p, p, 0.0, steam, null, w));
        if (liquid != null) phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t, p, expand(w.feed), liquid.copy()));
        if (condensed > 0.0) phases.add(condensedPhase(t, p, saturation, condensed, frozen, w));
        var split = freeWaterSplit(condensedSide.freeWater().hydrocarbon(), saturation, p, steam > 0.0 ? p : 0.0, condensed,
                steam, frozen);
        String note = "free water " + (frozen ? "subliming" : "boiling") + " at the state pressure (" + t + " K, "
                + (frozen ? "f_ice " : "p_sat ") + saturation + " Pa): steam fraction " + beta + " from the "
                + (energy ? "energy" : "volume") + " balance";
        return converged(competition, Classification.FREE_WATER, phases, List.of(), diagnostics(w, null, false), null, split, note);
    }

    /**
     * Free water freezing at its melting line (WP4), {@code t = T_m(p)}: the TP answer there with its condensed water
     * liquid (the gas, the hydrocarbons and the steam are the same on both sides of the line), the condensed water split
     * into ice and liquid by the energy (PH) or volume (UV) balance, {@code target = X_L - beta n_c (x_L - x_s)}: the ice
     * fraction {@code beta}. Null where the liquid side has no condensed liquid, or the balance puts {@code beta} outside
     * [0, 1] by more than the specification tolerance (the search goes on).
     */
    private EquilibriumResult waterFreezing(Specification specification, double t, double p, double target,
                                            PhaseCompetition competition, Workspace w) {
        boolean energy = specification == Specification.PH;
        EquilibriumResult warm = meltingLine(t, p, competition, w);
        if (warm == null) return null;
        double condensed = warm.freeWater().liquidWater();
        freeWater.liquid(t, p, w.waterProperties);
        CrystalReference.State solid = freeWater.ice().properties(t, p);
        double liquidValue = energy ? w.waterProperties[1] : w.waterProperties[0];
        double iceValue = energy ? solid.enthalpy() : solid.volume();
        double value = energy ? warm.enthalpy() : warm.volume();
        double span = condensed * (iceValue - liquidValue);
        double beta = (target - value) / span;
        double allowance = SPECIFICATION_TOLERANCE * (energy ? w.total * R * t : Math.abs(target)) / Math.abs(span);
        if (!(beta >= -allowance && beta <= 1.0 + allowance)) return null;
        return frozenAt(warm, t, p, Math.clamp(beta, 0.0, 1.0), energy ? "energy balance" : "volume balance", w);
    }

    /**
     * The warm side of the free water's melting line at {@code (t, p)}, {@code t = T_m(p)}: the TP answer with the
     * condensed water liquid, counted as one TP equilibrium of the search. Null unless it converged with condensed liquid
     * water and no ice.
     */
    private EquilibriumResult meltingLine(double t, double p, PhaseCompetition competition, Workspace w) {
        EquilibriumResult warm = evaluateTp(t, p, competition, false, w);
        w.outerEvaluations++;
        w.outerKernel += w.kernelEvaluations;
        w.outerDerivative += w.derivativeEvaluations;
        if (!warm.converged() || warm.freeWater() == null) return null;
        EquilibriumResult.FreeWater split = warm.freeWater();
        return split.liquidWater() > 0.0 && split.ice() == 0.0 ? warm : null;
    }

    /**
     * {@code warm}, the melting line's warm side at {@code (t, p)}, with the fraction {@code beta} of its condensed water
     * ice (the gas, the hydrocarbons and the steam are the same on both sides of the line), {@code balance} naming what
     * gave {@code beta} in the detail.
     */
    private EquilibriumResult frozenAt(EquilibriumResult warm, double t, double p, double beta, String balance, Workspace w) {
        EquilibriumResult.FreeWater split = warm.freeWater();
        double condensed = split.liquidWater();
        double ice;
        double liquid;
        if (beta <= 0.5) {
            ice = beta * condensed;
            liquid = condensed - ice;
        } else {
            liquid = (1.0 - beta) * condensed;
            ice = condensed - liquid;
        }
        double saturation = split.saturationPressure();
        List<PhaseAmounts> phases = new ArrayList<>(warm.phases().size() + 1);
        for (PhaseAmounts phase : warm.phases()) if (phase.kind() != PhaseKind.FREE_WATER) phases.add(phase);
        if (liquid > 0.0) phases.add(freeWaterPhase(t, p, saturation, liquid, w));
        if (ice > 0.0) phases.add(icePhase(t, p, saturation, ice, w));
        var frozen = new EquilibriumResult.FreeWater(split.hydrocarbon(), saturation, split.hydrocarbonPressure(),
                split.waterPartialPressure(), liquid, split.waterVapor(), ice);
        String note = "free water freezing at its melting temperature (" + t + " K at " + p + " Pa): ice fraction " + beta
                + " from the " + balance;
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, warm.detail() + "; " + note,
                warm.identity(), warm.competition(), Classification.FREE_WATER, phases, List.of(), warm.coverage(),
                warm.diagnostics(), frozen, warm.deposition());
    }

    /**
     * The free water's freeze at a fixed volume and energy: a rigid vessel of gas over water cooled below its melting
     * line freezes on the line, ice and liquid water at {@code T_m(P)}. At each pressure {@code P} of the line the warm
     * side's TP answer at {@code (T_m(P), P)} ({@link #meltingLine}) gives the ice fraction from the energy balance,
     * {@code beta = (U - U_L) / (n_c (u_s - u_L))}, and with it the volume {@code V = V_L + beta n_c (v_s - v_L)} (ice is
     * 9 % less dense than the water and compresses the gas cushion; {@code T_m} moves at -7.42e-8 K/Pa). The volume
     * balance sets {@code P}: a secant in {@code P} from {@code p0}, its first slope from the phases' compressibility and
     * bisection once bracketed, closes it in two to six TP equilibria. A {@code beta} beyond [0, 1] on the side the root
     * lies ends the solve: the line holds no answer. Returns the answer; null where the line holds none or its warm side
     * has no liquid water at {@code p0}; {@code NOT_CONVERGED} where the root lies beyond the pressure window.
     */
    private EquilibriumResult freezingAtVolume(double u, double v, double p0, PhaseCompetition competition, Workspace w) {
        if (w.freezeAttempts >= FREEZE_ATTEMPTS) return null;
        w.freezeAttempts++;
        double tolerance = settings.outerTolerance();
        double floor = DeterministicMath.exp(-PRESSURE_STEP_CAP);
        double p = Math.clamp(p0, w.minimumP, w.maximumP);
        double answered = Double.NaN;
        double previousP = Double.NaN;
        double previousGap = Double.NaN;
        double low = Double.NaN;
        double high = Double.NaN;
        int retreats = 0;
        EquilibriumResult last = null;
        for (int k = 0; k < FREEZE_EVALUATIONS; k++) {
            double t = freeWater.ice().meltingTemperature(p);
            EquilibriumResult warm = meltingLine(t, p, competition, w);
            if (warm == null) {
                // No liquid water on the warm side (or no TP answer) at this pressure: a rejected step, halved back.
                if (Double.isNaN(answered) || ++retreats > MAXIMUM_RETREATS) return null;
                p = Math.sqrt(p * answered);
                continue;
            }
            retreats = 0;
            answered = p;
            last = warm;
            double condensed = warm.freeWater().liquidWater();
            freeWater.liquid(t, p, w.waterProperties);
            CrystalReference.State solid = freeWater.ice().properties(t, p);
            double span = condensed * (solid.enthalpy() - p * solid.volume() - (w.waterProperties[1] - p * w.waterProperties[0]));
            double beta = (u - warm.internalEnergy()) / span;
            double volume = warm.volume() + beta * condensed * (solid.volume() - w.waterProperties[0]);
            double gap = v / volume - 1.0;
            if (beta < 0.0 && gap < 0.0 || beta > 1.0 && gap > 0.0) {
                w.freezeAttempts = FREEZE_ATTEMPTS;
                return null;
            }
            if (gap < 0.0) low = p; else high = p;
            double slope = Double.isNaN(previousP) || gap == previousGap ? Double.NaN : (gap - previousGap) / (p - previousP);
            if (!(slope > 0.0) || !Double.isFinite(slope)) slope = (v / volume) * lineCompressibility(warm, p, volume) / p;
            double next = p - gap / slope;
            // Converged when the next step moves P by at most the tolerance, or where the volume balance closes to the
            // rounding of a TP answer's volume: a liquid-full vessel (d ln V / d ln P 4e-5 at 1 bar) resolves P no
            // further, to about 1e-5 Pa.
            if (Math.abs(DeterministicMath.log(v / volume)) <= SPECIFICATION_TOLERANCE
                    && (Math.abs(next - p) <= tolerance * p || Math.abs(gap) <= 16.0 * Math.ulp(1.0))) {
                double allowance = SPECIFICATION_TOLERANCE * w.total * R * t / Math.abs(span);
                if (!(beta >= -allowance && beta <= 1.0 + allowance)) {
                    w.freezeAttempts = FREEZE_ATTEMPTS;
                    return null;
                }
                return frozenAt(warm, t, p, Math.clamp(beta, 0.0, 1.0), "energy balance, its pressure from the volume balance", w);
            }
            next = Math.max(next, floor * p);
            if (!Double.isNaN(low) && !Double.isNaN(high) && !(next > Math.min(low, high) && next < Math.max(low, high))) {
                next = Math.sqrt(low * high);
            }
            next = Math.clamp(next, w.minimumP, w.maximumP);
            if (next == p) {
                // The root lies beyond the window's bound the solve stands on; elsewhere the solve is stuck.
                if (p != w.minimumP && p != w.maximumP) return null;
                return outsideWindow(Specification.UV, false, p == w.minimumP, "the free water's freeze at the specified volume "
                        + v + " m3 and internal energy " + u + " J", competition, last, w);
            }
            previousP = p;
            previousGap = gap;
            p = next;
        }
        return null;
    }

    /**
     * {@code -(d ln V / d ln P)} along the melting line at the warm side {@code warm} whose volume with its ice is
     * {@code volume}: its gas phases as ideal, its condensed phases at {@link #CONDENSED_COMPRESSIBILITY}; the first step
     * of {@link #freezingAtVolume}, which secants take over.
     */
    private static double lineCompressibility(EquilibriumResult warm, double p, double volume) {
        double gas = 0.0;
        for (PhaseAmounts phase : warm.phases()) if (phase.kind() == PhaseKind.VAPOR) gas += phase.total() * phase.state().molarVolume();
        return (gas + p * CONDENSED_COMPRESSIBILITY * Math.max(0.0, volume - gas)) / volume;
    }

    /**
     * Whether the free water's freeze lies between {@code colder}, which holds ice, and {@code warmer}, which holds liquid
     * water (either may hold both): a UV Newton step between two TP answers that crosses the line.
     */
    private static boolean straddlesFreeze(EquilibriumResult colder, EquilibriumResult warmer) {
        EquilibriumResult.FreeWater ice = colder.freeWater();
        EquilibriumResult.FreeWater liquid = warmer.freeWater();
        return ice != null && liquid != null && ice.ice() > 0.0 && liquid.liquidWater() > 0.0;
    }

    /**
     * Whether a step of the nested UV search from the answer {@code from} to temperature {@code t} crosses the free
     * water's melting line at {@code from}'s pressure {@code p}: from liquid water to below the line, or from ice to above
     * it. Decided before the volume solve at {@code t}, which on the far side of a liquid-full vessel's line costs a dozen
     * TP equilibria the line itself does not need.
     */
    private boolean crossesMelting(EquilibriumResult from, double t, double p) {
        EquilibriumResult.FreeWater split = from.freeWater();
        if (split == null) return false;
        double melting = freeWater.ice().meltingTemperature(p);
        return split.liquidWater() > 0.0 && t < melting || split.ice() > 0.0 && t > melting;
    }

    // Results of a search.

    /**
     * A search's answer under its specification: the TP answer (or a coexistence from a balance) with the search's
     * counters and residual; a note is appended to the detail (never prepended: a {@code CRITICAL_BAND} prefix stays).
     */
    private EquilibriumResult outerAnswer(Specification specification, EquilibriumResult answer, double first, double second,
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
                coverage, outer, answer.freeWater(), answer.deposition());
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

    // Crystals (P4 stage 2a, generalised in P5): the equilibrium of a competing crystal with the fluid it leaves (a vapour,
    // a liquid or a vapour-liquid split), the pure species' sublimation and melting steps and the three-phase steps of PH
    // and UV, and the onset functions.

    /**
     * TP with competing crystals (dry material; with free water {@link #solveWetWithCrystals} runs this on the
     * hydrocarbons at their partial pressure). The fluid answer of the whole feed first; each competing crystal whose
     * species is carried is tested against it, {@code D = (mu_i - mu_s)/(R T)}, the species' chemical potential in the
     * fluid answer (fugacity, equal in every phase) against the crystal's; a fluid answer whose split has a product
     * proved unstable gives its split's chemical potential as the test (below the crystal's triple point that third
     * phase is typically a subcooled liquid of the species, which the crystal replaces). No {@code D} above
     * {@link #DEPOSITION_TOLERANCE}: the fluid answer, the solids assessed. Otherwise a pure feed of the species is its
     * crystal alone (except within the coexistence tolerance of its sublimation or melting point, undetermined as a
     * pure fluid's coexistence); a mixture's solid amount is solved so that the fluid the crystal leaves, re-flashed
     * with the stability test, has the crystal's chemical potential: {@link #depositFromVapour} first, and where that
     * does not leave one stable fluid phase on its root, {@link #depositFromFluid}; two liquids beside the crystal,
     * three fluid phases and two crystals forming are the typed {@link EquilibriumResult#CRYSTAL_PHASES} hold.
     */
    private EquilibriumResult solveWithCrystals(double t, double p, PhaseCompetition competition, Workspace w) {
        EquilibriumResult fluid = solveDry(t, p, competition, w);
        w.fluidEquilibria = 1;
        boolean threeFluid = !fluid.converged() && splitChemicalPotentialsUsable(fluid, w);
        if (!fluid.converged() && !threeFluid) return fluid;
        double rt = R * t;
        Crystal forming = null;
        int formingCount = 0;
        double drive = Double.NEGATIVE_INFINITY;
        double solidMu = Double.NaN;
        StringBuilder forces = new StringBuilder();
        for (int a = 0; a < w.activeCount; a++) {
            Crystal c = w.active[a];
            if (!(w.feed[c.component()] > 0.0)) continue;
            double mu = c.anchored().chemicalPotential(t, p);
            double fluidMu = threeFluid ? w.liquidState.chemicalPotential(c.component()) : fluidChemicalPotential(fluid, c.component());
            double d = (fluidMu - mu) / rt;
            forces.append(forces.isEmpty() ? "" : ", ").append(c.id()).append(": (mu - mu_s)/RT = ").append(d);
            if (d > DEPOSITION_TOLERANCE) formingCount++;
            if (d > drive) {
                drive = d;
                forming = c;
                solidMu = mu;
            }
        }
        if (forming == null) return threeFluid ? fluid : assessed(fluid, "solids assessed: no competing crystal's species is carried", w);
        if (w.presentCount == 1) {
            // A pure feed of the crystal's species (never a three-phase fluid answer).
            if (fluid.phases().size() == 1) {
                PhaseState state = fluid.phases().get(0).state();
                PhaseState solid = forming.anchored().evaluate(t, p, w.solidState);
                double gap = Math.abs(state.compressibilityFactor() - p * solid.molarVolume() / rt);
                if (Math.abs(drive) <= settings.coexistenceTolerance() * gap) {
                    String point = fluid.phases().get(0).kind() == PhaseKind.LIQUID ? "melting" : "sublimation";
                    return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, fluid.detail()
                            + "; pure " + point + " point of " + forming.id() + " (" + forces + "): the split is undetermined by T and P",
                            contract.identity(), competition, Classification.PURE_COEXISTENCE_UNDERDETERMINED, List.of(),
                            List.of(state, solid.copy()), solidCoverage(fluid.coverage(), w), fluid.diagnostics(), null, null);
                }
            }
            if (!(drive > DEPOSITION_TOLERANCE)) return assessed(fluid, "solids assessed: no crystal forms (" + forces + ")", w);
            boolean liquid = holdsLiquid(fluid);
            PhaseAmounts solid = new PhaseAmounts(PhaseKind.SOLID, forming.id(), t, p, w.basisAmounts,
                    forming.anchored().evaluate(t, p, w.solidState).copy());
            var deposition = new EquilibriumResult.Deposition(forming.id(), evaluator.components().get(forming.component()), drive,
                    Double.NaN, w.feed[forming.component()], true, 0, w.fluidEquilibria);
            return solidAnswer(competition, Classification.SOLID_PRESENT, List.of(solid), fluid, deposition,
                    (liquid ? "complete freezing: the pure " : "complete deposition: the pure ") + evaluator.components().get(forming.component())
                            + " is its crystal " + forming.id() + " (" + forces + "), the fluid vanishes", w);
        }
        if (!(drive > DEPOSITION_TOLERANCE)) {
            return threeFluid ? fluid : assessed(fluid, "solids assessed: no crystal forms (" + forces + ")", w);
        }
        if (formingCount > 1) return crystalPhasesHold(fluid, "two crystals form (" + forces + ")", competition, w);
        System.arraycopy(w.feed, 0, w.savedFeed, 0, count);
        System.arraycopy(w.basisAmounts, 0, w.savedBasis, 0, basis);
        try {
            if (!threeFluid) {
                EquilibriumResult answer = depositFromVapour(fluid, forming, drive, solidMu, forces, t, p, competition, w);
                if (answer != null) return answer;
                restoreFeed(w);
            }
            return depositFromFluid(fluid, threeFluid, forming, drive, solidMu, forces, t, p, competition, w);
        } finally {
            restoreFeed(w);
        }
    }

    /**
     * TP with competing crystals and free water (ice and a competing crystal coexist): the free-water rule's answer of
     * the whole feed first ({@link #solveWet}), each competing crystal tested against its hydrocarbon phases as in
     * {@link #solveWithCrystals}. No crystal forms: that answer, the solids assessed. Otherwise the hydrocarbons and their
     * crystal are solved as a dry feed at the hydrocarbon partial pressure {@code pc = P - p_w}, where their vapour lies,
     * and the water is laid around them by its rule (the steam in their vapour at the condensed water's vapour pressure
     * and the rest condensed, or all steam where that leaves the gas unsaturated); {@code pc} then follows the new gas
     * (the fixed point of {@link #solveWet}), started at the rule's own {@code pc}. The liquid and the crystal are
     * restated at the state pressure: solving them at {@code pc} moves their chemical potentials by
     * {@code v p_w / (R T)}, below 1e-7 where a crystal forms beside ice, 2e-5 for a liquid beside liquid water at 280 K.
     * A wet pure hydrocarbon at its crystal's own coexistence is the typed {@link EquilibriumResult#CRYSTAL_STEP} failure.
     */
    private EquilibriumResult solveWetWithCrystals(double t, double p, boolean frozen, PhaseCompetition competition, Workspace w) {
        EquilibriumResult fluid = solveWet(t, p, w.hydrocarbonTotal, w.waterTotal, frozen, competition, w);
        w.fluidEquilibria = 1;
        double rt = R * t;
        StringBuilder forces = new StringBuilder();
        EquilibriumResult.FreeWater wet = fluid.converged() ? fluid.freeWater() : null;
        if (wet != null) {
            boolean forms = false;
            for (int a = 0; a < w.activeCount; a++) {
                Crystal c = w.active[a];
                if (!(w.feed[c.component()] > 0.0)) continue;
                double d = (wetChemicalPotential(fluid, c.component()) - c.anchored().chemicalPotential(t, p)) / rt;
                forces.append(forces.isEmpty() ? "" : ", ").append(c.id()).append(": (mu - mu_s)/RT = ").append(d);
                if (d > DEPOSITION_TOLERANCE) forms = true;
            }
            if (!forms) {
                return assessed(fluid, forces.isEmpty() ? "solids assessed: no competing crystal's species is carried"
                        : "solids assessed: no crystal forms (" + forces + ")", w);
            }
        }
        double saturation = wet != null ? wet.saturationPressure() : frozen ? freeWater.ice().fugacity(t, p) : freeWater.saturationPressure(t);
        double water = w.waterTotal;
        double pc = wet != null ? wet.hydrocarbonPressure() : p > saturation + SATURATION_MARGIN ? p - saturation : p;
        for (int step = 0; ; step++) {
            EquilibriumResult dry = dryWithCrystals(t, pc, competition, w);
            if (!dry.converged()) return dry;
            if (dry.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
                return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, EquilibriumResult.CRYSTAL_STEP
                        + ": the pure hydrocarbon beside free water lies at its crystal's own coexistence, undetermined by T and P ("
                        + dry.detail() + ")", dry.diagnostics());
            }
            if (wet != null && !dry.classification().holdsCrystal()) {
                return assessed(fluid, "solids assessed: no crystal forms at the hydrocarbon partial pressure " + pc + " Pa (" + forces + ")", w);
            }
            PhaseAmounts vapour = null;
            for (PhaseAmounts phase : dry.phases()) if (phase.kind() == PhaseKind.VAPOR || phase.kind() == PhaseKind.FLUID) vapour = phase;
            double gasVolume = vapour == null ? 0.0 : vapour.total() * vapour.state().molarVolume();
            double steam;
            double partial;
            double next;
            if (!(gasVolume > 0.0)) {
                // No hydrocarbon gas: the water by the pure water's rule, condensed at or above its vapour pressure.
                steam = p >= saturation ? 0.0 : water;
                partial = steam > 0.0 ? p : 0.0;
                next = pc;
            } else {
                double required = SteamVirial.moles(saturation, t, gasVolume);
                steam = required <= water ? required : water;
                partial = SteamVirial.partialPressure(steam, t, gasVolume);
                next = p - partial;
            }
            if (Math.abs(next - pc) > PARTIAL_PRESSURE_TOLERANCE * p) {
                if (step >= PARTIAL_PRESSURE_FIXED_POINT_STEPS || !(next > PARTIAL_PRESSURE_FLOOR)) {
                    w.failure = "the hydrocarbon partial pressure beside the crystal and the steam was not found (pc " + pc + " Pa, next "
                            + next + " Pa at " + p + " Pa)";
                    return failed(t, p, competition, w);
                }
                pc = next;
                continue;
            }
            return wetAround(dry, vapour, t, p, pc, saturation, partial, steam, water - steam, frozen, competition, w);
        }
    }

    /**
     * The dry crystal solve of {@link #solveWetWithCrystals} at the single pressure {@code pc}: the workspace's hydrocarbons
     * with the water held out, put back after.
     */
    private EquilibriumResult dryWithCrystals(double t, double pc, PhaseCompetition competition, Workspace w) {
        double water = w.basisAmounts[waterIndex];
        w.basisAmounts[waterIndex] = 0.0;
        w.wet = false;
        try {
            return solveWithCrystals(t, pc, competition, w);
        } finally {
            w.wet = true;
            w.basisAmounts[waterIndex] = water;
        }
    }

    /**
     * The answer of {@link #solveWetWithCrystals}: the dry answer's phases restated at the state pressure (its vapour
     * {@code vapour}, null without one, as the gas with {@code steam} moles of steam at {@code pc} and {@code partial}),
     * {@code condensed} moles of condensed water (ice where {@code frozen}) at the vapour pressure {@code saturation}.
     */
    private EquilibriumResult wetAround(EquilibriumResult dry, PhaseAmounts vapour, double t, double p, double pc, double saturation,
                                        double partial, double steam, double condensed, boolean frozen, PhaseCompetition competition,
                                        Workspace w) {
        List<PhaseAmounts> phases = new ArrayList<>(dry.phases().size() + 2);
        double vapourAmount = 0.0;
        Arrays.fill(w.vaporAmounts, 0.0);
        if (vapour != null) {
            for (int i = 0; i < count; i++) w.vaporAmounts[i] = vapour.amount(basisOfComponent[i]);
            vapourAmount = vapour.total();
        }
        if (vapourAmount > 0.0 || steam > 0.0) {
            phases.add(gasPhase(t, p, vapourAmount > 0.0 ? pc : p, vapourAmount, steam, vapourAmount > 0.0 ? vapour.state() : null, w));
        }
        for (PhaseAmounts phase : dry.phases()) {
            if (phase == vapour) continue;
            if (phase.kind() == PhaseKind.SOLID) {
                Crystal c = crystalById(phase.crystal());
                phases.add(new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, phase.amountsView(), c.anchored().evaluate(t, p, w.solidState).copy()));
                continue;
            }
            for (int i = 0; i < count; i++) w.liquidAmounts[i] = phase.amount(basisOfComponent[i]);
            PhaseState state = evaluator.evaluate(t, p, w.liquidAmounts, phase.state().root(), w.phases, w.liquidState);
            w.kernelEvaluations++;
            phases.add(new PhaseAmounts(phase.kind(), null, t, p, phase.amountsView(), state.copy()));
        }
        if (condensed > 0.0) phases.add(condensedPhase(t, p, saturation, condensed, frozen, w));
        var split = freeWaterSplit(dry.classification(), saturation, vapourAmount > 0.0 || !(steam > 0.0) ? pc : p, partial,
                condensed, steam, frozen);
        String note = "free water beside the crystal answer at the hydrocarbon partial pressure " + pc + " Pa: " + condensed
                + " mol " + (frozen ? "ice" : "liquid water") + " and " + steam + " mol steam at " + partial + " Pa";
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, dry.detail() + "; " + note,
                contract.identity(), competition, Classification.FREE_WATER, phases, List.of(), dry.coverage(), dry.diagnostics(),
                split, dry.deposition());
    }

    /**
     * {@code mu_i} of EOS component {@code i} in a free-water answer: in its hydrocarbon liquid where that holds it, else in
     * its gas, whose state is on the contract basis (equal in both phases of a converged split).
     */
    private double wetChemicalPotential(EquilibriumResult fluid, int i) {
        PhaseState gas = null;
        for (PhaseAmounts phase : fluid.phases()) {
            if (phase.kind() == PhaseKind.LIQUID && phase.amount(basisOfComponent[i]) > 0.0) return phase.state().chemicalPotential(i);
            if (phase.kind() == PhaseKind.VAPOR) gas = phase.state();
        }
        return gas == null ? Double.NEGATIVE_INFINITY : gas.chemicalPotential(basisOfComponent[i]);
    }

    /**
     * Whether a fluid answer that is not converged is a split whose product the stability re-check proved unstable (a
     * third fluid phase indicated, {@link EquilibriumResult#UNSTABLE_PRODUCT}; also where a short tie line types it
     * {@link EquilibriumResult#CRITICAL_BAND}), whose two phases are still in the workspace with equal chemical potentials
     * (to 1e-6 RT): the split's chemical potentials then test and steer the crystals (P5). They are never an answer: an
     * answer needs a converged, stability-tested fluid the crystal leaves.
     */
    private boolean splitChemicalPotentialsUsable(EquilibriumResult fluid, Workspace w) {
        if (fluid.status() != EquilibriumResult.Status.NOT_CONVERGED || w.branched) return false;
        String detail = fluid.detail();
        boolean unstableProduct = detail.startsWith(EquilibriumResult.UNSTABLE_PRODUCT)
                || detail.startsWith(EquilibriumResult.CRITICAL_BAND) && detail.contains("): " + EquilibriumResult.UNSTABLE_PRODUCT);
        if (!unstableProduct) return false;
        if (!w.liquidState.filled() || !w.vaporState.filled()) return false;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            if (!(w.liquidState.moleFraction(i) > 0.0) || !(w.vaporState.moleFraction(i) > 0.0)) return false;
            double difference = (w.liquidState.chemicalPotential(i) - w.vaporState.chemicalPotential(i)) / (R * w.liquidState.temperature());
            if (!(Math.abs(difference) <= 1.0e-6)) return false;
        }
        return true;
    }

    /**
     * The vapour-solid solve, the first attempt of a mixture: the solid amount is the one unknown. With {@code r} the
     * species' amount left in the fluid and {@code s = ln(r/z_i)} in {@code (-infinity, 0]}, the fluid holds
     * {@code z - (z_i - r) e_i} and {@code f(s) = (mu_i^V - mu_s)/(R T)} on the reduced feed's vapour root increases with
     * {@code s}. Newton on {@code s} with {@code f' = 1 - y_i + y_i Phi_ii}, bracketed (bisection when a step leaves the
     * bracket), to {@code |f| <= }{@link #DEPOSITION_TOLERANCE}; below the trace floor ({@code y_i = }{@link #DEPOSITION_TRACE})
     * with {@code f} still positive the deposition is complete. The reduced feed is then re-flashed with the stability
     * test; the answer is returned only when that fluid is one phase meeting the tolerance on its own root
     * ({@link Classification#LIQUID_SOLID} for a liquid-like one-root fluid, else {@link Classification#VAPOR_SOLID});
     * every other outcome returns {@code null} and {@link #depositFromFluid} decides. A second crystal forming is the
     * typed hold. The smaller of the solid and the fluid's share is computed directly ({@code -z_i expm1(s)} or
     * {@code z_i exp(s)}), the larger by difference, so the species is conserved to rounding.
     */
    private EquilibriumResult depositFromVapour(EquilibriumResult fluid, Crystal c, double drive, double solidMu, CharSequence forces,
                                                double t, double p, PhaseCompetition competition, Workspace w) {
        int i = c.component();
        int slot = c.slot();
        String species = evaluator.components().get(i);
        double z = w.feed[i];
        double others = 0.0;
        for (int j = 0; j < count; j++) if (j != i) others += w.feed[j];
        Root root = Root.VAPOR;
        double floor = DeterministicMath.log(others * (DEPOSITION_TRACE / (1.0 - DEPOSITION_TRACE)) / z);
        double s = 0.0;
        double f = depositionResidual(t, p, c, z, s, solidMu, root, w);
        boolean liquid = holdsLiquid(fluid) || fluid.phases().size() != 1;
        // The feed as one vapour is not supersaturated: the crystal forms out of the answer's liquid (the general solve).
        if (!(f > DEPOSITION_TOLERANCE)) return null;
        double slope = w.reducedSlope;
        double high = 0.0;
        double low = Double.NEGATIVE_INFINITY;
        int iterations = 0;
        boolean complete = false;
        while (true) {
            if (iterations > 0 && Math.abs(f) <= DEPOSITION_TOLERANCE) break;
            if (iterations >= DEPOSITION_ITERATIONS) return null;
            if (f > 0.0) high = s; else low = s;
            double next = s - f / slope;
            boolean bracketed = low > Double.NEGATIVE_INFINITY;
            if (!(slope > 0.0) || !Double.isFinite(next) || next >= high || bracketed && next <= low) {
                next = bracketed ? 0.5 * (low + high) : s - Math.max(1.0, Math.abs(f));
            }
            if (next < floor) {
                if (s == floor) {
                    complete = true;
                    break;
                }
                next = floor;
            }
            s = next;
            f = depositionResidual(t, p, c, z, s, solidMu, root, w);
            slope = w.reducedSlope;
            iterations++;
            if (s == floor && f > 0.0) {
                complete = true;
                break;
            }
        }
        double solid;
        double vapour;
        if (complete) {
            solid = z;
            vapour = 0.0;
        } else {
            double deposit = -z * DeterministicMath.expm1(s);
            double left = z * DeterministicMath.exp(s);
            if (deposit <= left) {
                solid = deposit;
                vapour = z - solid;
            } else {
                vapour = left;
                solid = z - vapour;
            }
            if (!(solid > 0.0)) {
                if (liquid) return null;
                return assessed(fluid, "solids assessed: " + c.id() + " at its onset (the deposit rounds to zero)", w);
            }
        }
        // The re-flash of the reduced feed: the fluid the crystal leaves, by the stability test.
        w.feed[i] = vapour;
        w.basisAmounts[slot] = vapour;
        EquilibriumResult reduced = solveDry(t, p, competition, w);
        w.fluidEquilibria++;
        if (!reduced.converged() || reduced.phases().size() != 1) return null;
        double residual = Double.NaN;
        double rt = R * t;
        if (!complete) {
            residual = Math.abs((reduced.phases().get(0).state().chemicalPotential(i) - solidMu) / rt);
            // A liquid on the liquid root of three (the vapour root was solved), or a vapour that disagrees: the general solve.
            if (!(residual <= DEPOSITION_TOLERANCE)) return null;
        } else if (holdsLiquid(reduced)) {
            return null;
        }
        EquilibriumResult held = secondCrystal(reduced, c, t, p, competition, w);
        if (held != null) return held;
        PhaseAmounts fluidPhase = reduced.phases().get(0);
        boolean liquidLike = fluidPhase.kind() == PhaseKind.LIQUID;
        double[] solidAmounts = new double[basis];
        solidAmounts[slot] = solid;
        PhaseAmounts crystal = new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, solidAmounts, c.anchored().evaluate(t, p, w.solidState).copy());
        var deposition = new EquilibriumResult.Deposition(c.id(), species, drive, residual, solid, complete,
                iterations, w.fluidEquilibria);
        String note = complete
                ? "complete deposition of " + species + " as " + c.id() + ": at the trace limit y = " + DEPOSITION_TRACE
                        + " the vapour is still supersaturated; the fluid is the feed without " + species
                : c.id() + (liquidLike ? " freezes " + solid + " mol of " + species + " out of the dense one-root fluid"
                        : " deposits " + solid + " mol of " + species + " from the vapour") + " (feed (mu - mu_s)/RT = " + drive
                        + ", |mu_i - mu_s|/RT = " + residual + " after " + iterations + " Newton steps)";
        if (liquidLike) {
            note += "; the fluid left is one " + reduced.classification() + " liquid-like by its phase identification parameter"
                    + " (a dense one-root fluid carrying the crystal's species, P5): its crystal is answered beside it";
        }
        if (liquid) {
            note += "; the fluid-only answer of the feed was " + fluid.classification() + ": its liquid is metastable against "
                    + c.id() + " (the fluid left by the deposition is a stable single phase)";
        }
        return solidAnswer(competition, liquidLike ? Classification.LIQUID_SOLID : Classification.VAPOR_SOLID,
                List.of(fluidPhase, crystal), reduced, deposition, note, w);
    }

    /**
     * The general crystal solve: the solid amount of crystal {@code c} out of a mixture whose fluid the crystal leaves
     * may be a vapour, a liquid or a vapour-liquid split. The unknown is {@code s = ln(r/z_i)} in {@code (-infinity, 0]};
     * every iterate re-flashes the reduced feed {@code z - (z_i - r) e_i} with the stability test, and
     * {@code f(s) = (mu_i - mu_s)/(R T)} of that fluid answer is nondecreasing in {@code s} (a binary's vapour-liquid
     * split holds it constant, where the step goes on), with {@code f(0) = D > 0}. Steps: Newton with
     * {@code f' = 1 - y_i + y_i Phi_ii} on a one-phase fluid's root, a secant otherwise, {@code s - max(1, |f|)} before a
     * bracket exists, bisection when a step leaves the bracket or two steps fail to halve {@code |f|}; at most
     * {@link #CRYSTAL_ITERATIONS} re-flashes. Converged on a converged fluid answer each of whose phases has
     * {@code |mu_i - mu_s|/(R T) <= }{@link #DEPOSITION_TOLERANCE} (a split's phases agree on {@code mu_i} only to the
     * flash's fugacity tolerance: stopped on its first phase alone, the search left the other outside and the answer was
     * refused, LNG at 140.9 K and 620 kPa freezing CO2). A reduced fluid whose split has a product proved unstable steers
     * the search but is never an answer. Below the trace floor with {@code f} still positive the deposition is complete.
     * The answer: the fluid's phases and the crystal ({@link Classification#VAPOR_SOLID},
     * {@link Classification#LIQUID_SOLID} or {@link Classification#VAPOR_LIQUID_SOLID}); two liquids beside the crystal
     * are the typed {@link EquilibriumResult#CRYSTAL_PHASES} hold, a search that does not converge {@code NOT_CONVERGED}.
     * The solid is {@code -z_i expm1(s)} and the fluid carries {@code z_i e^s}, so the species is conserved to rounding.
     */
    private EquilibriumResult depositFromFluid(EquilibriumResult fluid, boolean threeFluid, Crystal c, double drive, double solidMu,
                                               CharSequence forces, double t, double p, PhaseCompetition competition, Workspace w) {
        int i = c.component();
        int slot = c.slot();
        String species = evaluator.components().get(i);
        double z = w.feed[i];
        double others = 0.0;
        for (int j = 0; j < count; j++) if (j != i) others += w.feed[j];
        double rt = R * t;
        double floor = DeterministicMath.log(others * (DEPOSITION_TRACE / (1.0 - DEPOSITION_TRACE)) / z);
        double s = 0.0;
        double f = drive;
        EquilibriumResult reduced = threeFluid ? null : fluid;
        double slope = reduced != null && reduced.phases().size() == 1 ? singlePhaseSlope(t, p, reduced, i, w) : Double.NaN;
        double high = 0.0;
        double low = Double.NEGATIVE_INFINITY;
        double previousS = Double.NaN;
        double previousF = Double.NaN;
        double best = Math.abs(f);
        // The largest |mu_i - mu_s|/RT over the phases of the last converged re-flash: the convergence test.
        double worst = Double.NaN;
        int stalls = 0;
        int iterations = 0;
        boolean complete = false;
        while (true) {
            if (reduced != null && iterations > 0 && worst <= DEPOSITION_TOLERANCE) break;
            if (iterations >= CRYSTAL_ITERATIONS) {
                return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the crystal solve of " + c.id()
                        + " did not converge in " + iterations + " re-flashes (|mu_i - mu_s|/RT = " + Math.abs(f) + ", the fluid "
                        + (reduced == null ? "a split with a product proved unstable" : reduced.classification()) + ")",
                        diagnostics(w, w.feedResult, false));
            }
            boolean bracketed = low > Double.NEGATIVE_INFINITY;
            if (bracketed && high - low <= 1.0e-14 * Math.max(1.0, Math.abs(low))) {
                // The bracket closed on a jump of f: no fluid the engine answers takes the crystal's chemical potential here.
                if (reduced == null) {
                    return crystalPhasesHold(fluid, c.id() + " forms and the fluid it leaves has three fluid phases (" + forces + ")", competition, w);
                }
                return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the crystal solve of " + c.id()
                        + " closed its bracket on a jump of the chemical potential (|mu_i - mu_s|/RT = " + Math.abs(f) + ", the fluid "
                        + reduced.classification() + ")", diagnostics(w, w.feedResult, false));
            }
            double proposal = Double.NaN;
            if (slope > 0.0 && Double.isFinite(slope)) proposal = s - f / slope;
            if (!Double.isFinite(proposal) && Double.isFinite(previousS) && previousS != s) {
                double secant = (f - previousF) / (s - previousS);
                if (secant > 0.0 && Double.isFinite(secant)) proposal = s - f / secant;
            }
            double next;
            if (bracketed) {
                boolean bisect = stalls >= 2 || !(proposal > low && proposal < high);
                next = bisect ? 0.5 * (low + high) : proposal;
                if (bisect) stalls = 0;
            } else {
                next = proposal < s && Double.isFinite(proposal) ? proposal : s - Math.max(1.0, Math.abs(f));
            }
            if (next < floor) {
                if (s == floor) {
                    complete = true;
                    break;
                }
                next = floor;
            }
            previousS = s;
            previousF = f;
            s = next;
            double left = z * DeterministicMath.exp(s);
            w.feed[i] = left;
            w.basisAmounts[slot] = left;
            EquilibriumResult answer = solveDry(t, p, competition, w);
            w.fluidEquilibria++;
            iterations++;
            if (answer.converged()) {
                reduced = answer;
                f = (fluidChemicalPotential(answer, i) - solidMu) / rt;
                worst = Math.abs(f);
                for (PhaseAmounts phase : answer.phases()) {
                    worst = Math.max(worst, Math.abs((phase.state().chemicalPotential(i) - solidMu) / rt));
                }
                slope = answer.phases().size() == 1 ? singlePhaseSlope(t, p, answer, i, w) : Double.NaN;
            } else if (splitChemicalPotentialsUsable(answer, w)) {
                reduced = null;
                f = (w.liquidState.chemicalPotential(i) - solidMu) / rt;
                worst = Double.NaN;
                slope = Double.NaN;
            } else {
                return answer;
            }
            if (Math.abs(f) <= 0.5 * best) stalls = 0; else stalls++;
            best = Math.min(best, Math.abs(f));
            if (f > 0.0) high = s; else low = s;
            if (s == floor && f > 0.0) {
                complete = true;
                break;
            }
        }
        double solid;
        if (complete) {
            solid = z;
            w.feed[i] = 0.0;
            w.basisAmounts[slot] = 0.0;
            reduced = solveDry(t, p, competition, w);
            w.fluidEquilibria++;
            if (!reduced.converged()) return reduced;
        } else {
            solid = -z * DeterministicMath.expm1(s);
            if (!(solid > 0.0)) {
                EquilibriumResult base = fluid.converged() ? fluid : reduced;
                if (base == null) return fluid;
                return assessed(base, "solids assessed: " + c.id() + " at its onset (the deposit rounds to zero)", w);
            }
        }
        Classification classification = switch (reduced.classification()) {
            case VAPOR_LIQUID -> Classification.VAPOR_LIQUID_SOLID;
            case SINGLE_VAPOR, SINGLE_LIQUID, SINGLE_FLUID, SUPERCRITICAL_FLUID ->
                    reduced.phases().get(0).kind() == PhaseKind.LIQUID ? Classification.LIQUID_SOLID : Classification.VAPOR_SOLID;
            default -> null;
        };
        if (reduced.classification() == Classification.LIQUID_LIQUID) {
            return crystalPhasesHold(reduced, "the fluid left by the crystal " + c.id() + " is two liquids (" + forces + ")", competition, w);
        }
        if (classification == null) {
            return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the fluid left by the crystal "
                    + c.id() + " is " + reduced.classification() + ", which a crystal answer does not carry", diagnostics(w, w.feedResult,
                    reduced.diagnostics().criticalBand()));
        }
        // Every fluid phase meets the crystal: the convergence test read them all.
        double residual = complete ? Double.NaN : worst;
        EquilibriumResult held = secondCrystal(reduced, c, t, p, competition, w);
        if (held != null) return held;
        double[] solidAmounts = new double[basis];
        solidAmounts[slot] = solid;
        PhaseAmounts crystal = new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, solidAmounts, c.anchored().evaluate(t, p, w.solidState).copy());
        List<PhaseAmounts> phases = new ArrayList<>(reduced.phases());
        phases.add(crystal);
        var deposition = new EquilibriumResult.Deposition(c.id(), species, drive, residual, solid, complete,
                iterations, w.fluidEquilibria);
        String out = switch (classification) {
            case LIQUID_SOLID -> "freezes " + solid + " mol of " + species + " out of the liquid";
            case VAPOR_LIQUID_SOLID -> "forms " + solid + " mol of " + species + " beside a vapour and a liquid";
            default -> "deposits " + solid + " mol of " + species + " from the vapour";
        };
        String note = complete
                ? "complete " + (classification == Classification.VAPOR_SOLID ? "deposition" : "freezing") + " of " + species + " as "
                        + c.id() + ": at the trace limit y = " + DEPOSITION_TRACE + " the fluid is still supersaturated; the fluid is the"
                        + " feed without " + species
                : c.id() + " " + out + " (feed (mu - mu_s)/RT = " + drive + ", |mu_i - mu_s|/RT = " + residual + " after "
                        + iterations + " re-flashes of the reduced feed)";
        if (!fluid.converged()) {
            note += "; the fluid-only answer of the feed had a product proved unstable (a third fluid phase): the crystal replaces it";
        } else if (fluid.classification() != reduced.classification()) {
            note += "; the fluid-only answer of the feed was " + fluid.classification();
        }
        return solidAnswer(competition, classification, phases, reduced, deposition, note, w);
    }

    /** {@code df/ds} of the crystal solve on a one-phase fluid answer of the workspace's feed: {@code 1 - y_i + y_i Phi_ii}. */
    private double singlePhaseSlope(double t, double p, EquilibriumResult reduced, int i, Workspace w) {
        try {
            PhaseDerivatives d = evaluator.derivatives(t, p, w.feed, reduced.phases().get(0).state().root(), w.phases, w.reduced);
            w.kernelEvaluations++;
            w.derivativeEvaluations++;
            double y = d.state().moleFraction(i);
            return 1.0 - y + y * d.logFugacityCompositionDerivative(i, i);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return Double.NaN;
        }
    }

    /** A second competing crystal that forms against the fluid a crystal leaves: the typed hold, or null. */
    private EquilibriumResult secondCrystal(EquilibriumResult reduced, Crystal c, double t, double p, PhaseCompetition competition,
                                            Workspace w) {
        double rt = R * t;
        for (int a = 0; a < w.activeCount; a++) {
            Crystal other = w.active[a];
            if (other == c || !(w.feed[other.component()] > 0.0)) continue;
            double d = (fluidChemicalPotential(reduced, other.component()) - other.anchored().chemicalPotential(t, p)) / rt;
            if (d > DEPOSITION_TOLERANCE) {
                return crystalPhasesHold(reduced, "a second crystal " + other.id() + " forms beside " + c.id(), competition, w);
            }
        }
        return null;
    }

    /**
     * {@code f(s)} of the vapour-solid solve at {@code r = z e^s} (the reduced feed set into the workspace), from the
     * fluid evaluator's derivatives on {@code root}; the slope {@code 1 - y_i + y_i Phi_ii} into {@code w.reducedSlope}.
     */
    private double depositionResidual(double t, double p, Crystal c, double z, double s, double solidMu, Root root,
                                      Workspace w) {
        int i = c.component();
        double r = s == 0.0 ? z : z * DeterministicMath.exp(s);
        w.feed[i] = r;
        PhaseDerivatives d = evaluator.derivatives(t, p, w.feed, root, w.phases, w.reduced);
        w.kernelEvaluations++;
        w.derivativeEvaluations++;
        PhaseState state = d.state();
        double y = state.moleFraction(i);
        w.reducedSlope = 1.0 - y + y * d.logFugacityCompositionDerivative(i, i);
        return (state.chemicalPotential(i) - solidMu) / (R * t);
    }

    /** Puts the saved feed back, with its composition and present list as the hydrocarbon core computes them. */
    private void restoreFeed(Workspace w) {
        System.arraycopy(w.savedFeed, 0, w.feed, 0, count);
        System.arraycopy(w.savedBasis, 0, w.basisAmounts, 0, basis);
        double total = 0.0;
        for (int i = 0; i < count; i++) total += w.feed[i];
        w.presentCount = 0;
        for (int i = 0; i < count; i++) {
            w.z[i] = w.feed[i] / total;
            if (w.feed[i] > 0.0) w.present[w.presentCount++] = i;
        }
    }

    /** {@code mu_i} of EOS component {@code i} in a fluid answer: its first phase's (equal in every phase), or a coexisting state's. */
    private static double fluidChemicalPotential(EquilibriumResult fluid, int component) {
        PhaseState state = fluid.phases().isEmpty() ? fluid.coexisting().get(0) : fluid.phases().get(0).state();
        return state.chemicalPotential(component);
    }

    /** Whether a fluid answer holds a liquid: a phase labelled liquid, or a pure fluid's liquid-vapour coexistence. */
    private static boolean holdsLiquid(EquilibriumResult fluid) {
        if (fluid.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
            for (PhaseState state : fluid.coexisting()) if (state.kind() == PhaseKind.LIQUID) return true;
            return false;
        }
        for (PhaseAmounts phase : fluid.phases()) if (phase.kind() == PhaseKind.LIQUID) return true;
        return false;
    }

    /** Whether a converged answer holds a crystal, among its phases or its two coexisting states. */
    private static boolean holdsSolid(EquilibriumResult result) {
        if (result == null || !result.converged()) return false;
        for (PhaseAmounts phase : result.phases()) if (phase.kind() == PhaseKind.SOLID) return true;
        for (PhaseState state : result.coexisting()) if (state.kind() == PhaseKind.SOLID) return true;
        return false;
    }

    /** Whether a phase is a hydrocarbon fluid: labelled liquid, vapour or fluid (not the free water, not a crystal). */
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
     * component's coexistence, a crystal's step, a three-phase pressure, the free water's boiling or freezing), and so
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

    /**
     * The competing crystal of the feed's one major species, or null: the only species present (a pure feed), or the
     * largest of a dry near-pure feed whose other species total at most {@link #NEAR_PURE_TRACES} of it (D21).
     */
    private Crystal pureCrystal(Workspace w) {
        if (w.activeCount == 0) return null;
        int major = majorSpecies(w);
        if (major < 0) return null;
        for (int a = 0; a < w.activeCount; a++) if (w.active[a].component() == major) return w.active[a];
        return null;
    }

    /** The feed's one major species (see {@link #pureCrystal}), or -1. */
    private static int majorSpecies(Workspace w) {
        if (w.presentCount == 1) return w.present[0];
        if (w.wet || w.presentCount == 0) return -1;
        int major = w.present[0];
        for (int a = 1; a < w.presentCount; a++) if (w.feed[w.present[a]] > w.feed[major]) major = w.present[a];
        return traces(major, w) <= NEAR_PURE_TRACES * w.hydrocarbonTotal ? major : -1;
    }

    /** The feed's amount of every species but {@code major}, mol: the traces of a near-pure feed (D21), 0 for a pure one. */
    private static double traces(int major, Workspace w) {
        double others = 0.0;
        for (int a = 0; a < w.presentCount; a++) if (w.present[a] != major) others += w.feed[w.present[a]];
        return others;
    }

    /**
     * The competing crystal of a dry binary that carries its species (and no other crystal's), or null (P5). A
     * pseudo-binary counts: two species above {@link #TRACE_FRACTION} of the feed, the others traces (a network vessel
     * carries the nitrogen trace of an injection), whose three-phase region at fixed T and P is as thin as their share. A
     * near-pure feed whose major species has the crystal is not one (D21: its pure species' steps take precedence).
     */
    private Crystal binaryCrystal(Workspace w) {
        if (w.wet || w.activeCount == 0 || w.presentCount < 2 || pureCrystal(w) != null) return null;
        int majors = 0;
        for (int a = 0; a < w.presentCount; a++) if (w.feed[w.present[a]] > TRACE_FRACTION * w.hydrocarbonTotal) majors++;
        if (majors != 2) return null;
        Crystal found = null;
        for (int a = 0; a < w.activeCount; a++) {
            Crystal c = w.active[a];
            if (!(w.feed[c.component()] > 0.0)) continue;
            if (found != null) return null;
            found = c;
        }
        if (found == null || !(w.feed[found.component()] > TRACE_FRACTION * w.hydrocarbonTotal)) return null;
        return found;
    }

    /** The other major species of a pseudo-binary with crystal species {@code i} (see {@link #binaryCrystal}). */
    private int binaryPartner(int i, Workspace w) {
        for (int a = 0; a < w.presentCount; a++) {
            int k = w.present[a];
            if (k != i && w.feed[k] > TRACE_FRACTION * w.hydrocarbonTotal) return k;
        }
        throw new IllegalStateException("no partner");
    }

    /** An inner UV answer from a balance: a converged one is found, a typed hold ends the pressure search with it. */
    private static int foundOrHeld(EquilibriumResult answer, Workspace w) {
        if (answer.converged()) return found(answer, true, w);
        w.innerResult = answer;
        w.innerFailure = answer.detail();
        return INNER_FAILED;
    }

    /**
     * The coverage of a crystal competition's answer: the fluid answer's grade folded with each competing crystal's
     * (the worse of them), the fluid evidence without the fluid-only note, and each crystal's record line (its revision,
     * grade and evidence, which names the declared errors of D16 for the CO2-I crystal).
     */
    private EquilibriumResult.Coverage solidCoverage(EquilibriumResult.Coverage fluid, Workspace w) {
        if (fluid == convergedCoverage) {
            EquilibriumResult.Coverage cached = crystalCoverages.get(w.competition);
            if (cached != null) return cached;
        }
        List<Crystal> set = new ArrayList<>(w.activeCount);
        for (int a = 0; a < w.activeCount; a++) set.add(w.active[a]);
        return solidCoverage(fluid, w.competition, set);
    }

    private static EquilibriumResult.Coverage solidCoverage(EquilibriumResult.Coverage fluid, PhaseCompetition competition,
                                                            List<Crystal> set) {
        String base = fluid.evidence();
        if (base.endsWith(FLUID_ONLY_NOTE)) base = base.substring(0, base.length() - FLUID_ONLY_NOTE.length());
        EquilibriumResult.CoverageGrade grade = fluid.grade();
        StringBuilder evidence = new StringBuilder(base).append("; solids assessed (").append(competition).append(')');
        for (int a = 0; a < set.size(); a++) {
            Crystal c = set.get(a);
            if (c.grade().ordinal() > grade.ordinal()) grade = c.grade();
            evidence.append(a == 0 ? ": " : "; ").append(c.evidence());
        }
        return new EquilibriumResult.Coverage(grade, evidence.toString());
    }

    /** The fluid answer under a crystal competition, no crystal among its phases: the solids assessed. */
    private EquilibriumResult assessed(EquilibriumResult fluid, String note, Workspace w) {
        return new EquilibriumResult(fluid.specification(), EquilibriumResult.Status.CONVERGED, null, fluid.detail() + "; " + note,
                fluid.identity(), fluid.competition(), fluid.classification(), fluid.phases(), fluid.coexisting(),
                solidCoverage(fluid.coverage(), w), fluid.diagnostics(), fluid.freeWater(), null);
    }

    /** An answer with a crystal among its phases: the fluid answer's grade and band folded with the crystals'. */
    private EquilibriumResult solidAnswer(PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                                          EquilibriumResult fluid, EquilibriumResult.Deposition deposition, String note, Workspace w) {
        boolean band = fluid.diagnostics().criticalBand();
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, fluid.detail() + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), solidCoverage(fluid.coverage(), w),
                diagnostics(w, w.feedResult, band), null, deposition);
    }

    /**
     * The typed hold of a crystal competition whose answer needs a phase set the engine does not implement (P5: two
     * liquids beside a crystal, three fluid phases beside it, two crystals); see {@link EquilibriumResult#CRYSTAL_PHASES}.
     */
    private EquilibriumResult crystalPhasesHold(EquilibriumResult fluid, String why, PhaseCompetition competition, Workspace w) {
        String detail = EquilibriumResult.CRYSTAL_PHASES + ": " + why + "; that phase set beside a crystal is not implemented";
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.UNSUPPORTED, UnsupportedReason.NOT_IMPLEMENTED, detail,
                contract.identity(), competition, Classification.NONE, List.of(), List.of(),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.UNAVAILABLE, detail),
                diagnostics(w, w.feedResult, fluid.diagnostics().criticalBand()), null, null);
    }

    /**
     * The pure species of a competing crystal at its equal-chemical-potential point with its own fluid (PH at the fixed
     * pressure, UV at the fixed temperature): below the family's triple point the sublimation step with the vapour, above
     * it the melting step with the liquid. The energy or volume balance determines the split,
     * {@code target = N [(1 - beta) x_s + beta x_F]}; as {@link #pureCoexistence}, the answer when {@code beta} lies in
     * {@code [0, 1]}, otherwise null with the jump's end in {@code coexistenceX} and {@code coexistenceResidual}. A
     * near-pure feed (D21) takes the same step at the same point, its traces in the fluid ({@code beta} the fluid's share
     * of all the moles, the fluid evaluated at its own composition, a few re-evaluations converging the split), while the
     * traces are at most {@link #STEP_FLUID_TRACES} of that fluid; a target beyond that end is the general solve's, and a
     * closed search there is the typed {@link EquilibriumResult#CRYSTAL_STEP} hold.
     */
    private EquilibriumResult pureCrystalStep(Specification specification, double t, double p, double target, boolean clamp,
                                              PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        Crystal c = pureCrystal(w);
        if (c == null) return null;
        boolean energy = specification == Specification.PH;
        boolean melting = energy ? p > c.triplePressure() : t > c.tripleTemperature();
        Root root = melting ? Root.LIQUID : Root.VAPOR;
        if (energy) t = crystalStepTemperature(p, t, c, root, w); else p = crystalStepPressure(t, p, c, root, w);
        if (Double.isNaN(t) || Double.isNaN(p)) return null;
        int i = c.component();
        String species = evaluator.components().get(i);
        PhaseState fluid = evaluator.evaluate(t, p, w.feed, root, w.phases, w.vaporState);
        PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
        w.outerKernel++;
        double n = w.hydrocarbonTotal;
        double traces = traces(i, w);
        double solidValue = energy ? solid.molarEnthalpy() : solid.molarVolume();
        double fluidValue = energy ? fluid.molarEnthalpy() : fluid.molarVolume();
        double gap = fluidValue - solidValue;
        double beta = (target / n - solidValue) / gap;
        // D21: the smallest fluid share that keeps the traces within STEP_FLUID_TRACES of it (0 for a pure feed).
        double lowest = traces / (STEP_FLUID_TRACES * n);
        if (traces > 0.0) {
            for (int k = 0; k < NEAR_PURE_ITERATIONS && beta >= lowest && beta <= 1.0; k++) {
                fluid = stepFluid(t, p, i, beta * n - traces, root, w);
                fluidValue = energy ? fluid.molarEnthalpy() : fluid.molarVolume();
                gap = fluidValue - solidValue;
                double next = (target / n - solidValue) / gap;
                boolean converged = Math.abs(next - beta) <= 4.0 * Math.ulp(beta);
                beta = next;
                if (converged) break;
            }
        }
        double belowAllowance = Math.abs(SPECIFICATION_TOLERANCE * (energy ? R * t : solidValue) / gap);
        double aboveAllowance = Math.abs(SPECIFICATION_TOLERANCE * (energy ? R * t : fluidValue) / gap);
        if (!clamp && (beta < lowest - belowAllowance || beta > 1.0 + aboveAllowance) || !Double.isFinite(beta)) {
            boolean solidEnd = beta < lowest;
            double value = !solidEnd ? n * fluidValue
                    : traces > 0.0 ? n * ((1.0 - lowest) * solidValue + lowest * fluidValue) : n * solidValue;
            w.coexistenceX = energy ? t : DeterministicMath.log(p);
            w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
            return null;
        }
        if (traces > 0.0 && beta < lowest - belowAllowance) {
            // A closed search on the crystal step with the target beyond the traces' end of it (D21 option C).
            return crystalStepHold("the specified " + (energy ? "enthalpy " + target + " J" : "volume " + target + " m3")
                    + " needs a fluid of more than " + STEP_FLUID_TRACES + " traces beside the crystal at the pure " + species
                    + " step (" + t + " K, " + p + " Pa; fluid share " + beta + ", the traces' end " + lowest + ")", competition, w);
        }
        beta = Math.clamp(beta, lowest, 1.0);
        String note = "pure " + (melting ? "melting" : "sublimation") + " of " + species + " at the equal-chemical-potential point ("
                + t + " K, " + p + " Pa): " + (melting ? "liquid" : "vapour") + " fraction " + beta + " from the "
                + (energy ? "energy" : "volume") + " balance"
                + (traces > 0.0 ? "; near-pure (D21): " + traces + " mol of other species in the " + (melting ? "liquid" : "vapour") : "");
        int slot = c.slot();
        double amount = w.feed[i];
        PhaseKind fluidKind = melting ? PhaseKind.LIQUID : PhaseKind.VAPOR;
        List<PhaseAmounts> phases;
        Classification classification;
        EquilibriumResult.Deposition deposition = null;
        if (beta == 1.0) {
            if (traces > 0.0) fluid = stepFluid(t, p, i, amount, root, w);
            classification = !melting ? Classification.SINGLE_VAPOR
                    : fluid.physicalRootCount() > 1 ? Classification.SINGLE_LIQUID : Classification.SINGLE_FLUID;
            phases = List.of(new PhaseAmounts(fluidKind, null, t, p, w.basisAmounts, fluid.copy()));
        } else if (beta == 0.0) {
            classification = Classification.SOLID_PRESENT;
            phases = List.of(new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, w.basisAmounts, solid.copy()));
            deposition = new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, amount, true, 0, 0);
        } else {
            double[] fluidAmounts = new double[basis];
            double[] solidAmounts = new double[basis];
            // The traces stay in the fluid (D21; none for a pure feed, whose n is the species' amount).
            for (int a = 0; a < w.presentCount; a++) {
                int j = w.present[a];
                if (j != i) fluidAmounts[basisOfComponent[j]] = w.feed[j];
            }
            // The smaller share by its own formula, the larger by difference: conserved to rounding.
            if (beta <= 0.5) {
                fluidAmounts[slot] = beta * n - traces;
                solidAmounts[slot] = amount - fluidAmounts[slot];
            } else {
                solidAmounts[slot] = (1.0 - beta) * n;
                fluidAmounts[slot] = amount - solidAmounts[slot];
            }
            classification = melting ? Classification.LIQUID_SOLID : Classification.VAPOR_SOLID;
            phases = List.of(new PhaseAmounts(fluidKind, null, t, p, fluidAmounts, fluid.copy()),
                    new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, solidAmounts, solid.copy()));
            deposition = new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, solidAmounts[slot], false, 0, 0);
        }
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), null, deposition);
    }

    /**
     * The fluid a near-pure crystal step leaves (D21): the feed's traces with {@code amount} of species {@code i},
     * evaluated at (t, p) on {@code root} into the workspace's vapour state.
     */
    private PhaseState stepFluid(double t, double p, int i, double amount, Root root, Workspace w) {
        System.arraycopy(w.feed, 0, w.stepFluid, 0, count);
        w.stepFluid[i] = amount;
        w.outerKernel++;
        return evaluator.evaluate(t, p, w.stepFluid, root, w.phases, w.vaporState);
    }

    /** The typed hold of a crystal step the near-pure rule does not answer (D21 option C, {@link EquilibriumResult#CRYSTAL_STEP}). */
    private EquilibriumResult crystalStepHold(String why, PhaseCompetition competition, Workspace w) {
        return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                EquilibriumResult.CRYSTAL_STEP + ": " + why, diagnostics(w, null, false));
    }

    /**
     * The temperature at {@code p} where the crystal's pure species and its fluid on {@code root} (the vapour for the
     * sublimation line, the liquid for the melting line, P5) have equal chemical potentials on this family: Newton in
     * {@code x = 1/T} on {@code g = (mu_F - mu_s)/(R T)} with {@code dg/dx = (h_F - h_s)/R}, bisection once both signs are
     * seen, within the numerical box's floor and the crystal's ceiling; converged at {@code |dx| <= 1e-14 x}. {@code NaN}
     * when it does not converge. The kernel calls count into {@code outerKernel}.
     */
    private double crystalStepTemperature(double p, double start, Crystal c, Root root, Workspace w) {
        int i = c.component();
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = 1.0;
        // The numerical box's floor to the crystal's ceiling, where it ceases to exist: the record and the ideal gas continue
        // past their data ranges (owner rule of 2026-10-16).
        double low = NumericalBox.MINIMUM_TEMPERATURE;
        double high = c.anchored().record().maximumTemperature();
        double t = Math.clamp(Double.isFinite(start) ? start : cold(p, c), low, high);
        double warm = Double.NaN;
        double cold = Double.NaN;
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            PhaseState fluid = evaluator.evaluate(t, p, w.pure, root, w.phases, w.pureState);
            PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
            w.outerKernel++;
            double x = 1.0 / t;
            double g = (fluid.chemicalPotential(i) - solid.molarGibbsEnergy()) / (R * t);
            if (g == 0.0) return t;
            if (g > 0.0) cold = x; else warm = x;
            double next = x - g * R / (fluid.molarEnthalpy() - solid.molarEnthalpy());
            boolean bracketed = !Double.isNaN(cold) && !Double.isNaN(warm);
            if (!Double.isFinite(next) || bracketed && !(next > warm && next < cold)) {
                if (!bracketed) return Double.NaN;
                next = 0.5 * (warm + cold);
            }
            next = Math.clamp(next, 1.0 / high, 1.0 / low);
            if (Math.abs(next - x) <= 1.0e-14 * x) return 1.0 / next;
            if (next == x) return Double.NaN;
            t = 1.0 / next;
        }
        return Double.NaN;
    }

    /**
     * The pressure at {@code t} where the crystal's pure species and its fluid on {@code root} have equal chemical
     * potentials on this family (the sublimation pressure on the vapour root, the melting pressure on the liquid root,
     * P5): Newton in {@code ln P} on {@code g = (mu_F - mu_s)/(R T)} with {@code dg/d ln P = P (v_F - v_s)/(R T)},
     * bisection once both signs are seen; converged at {@code |d ln P| <= 1e-14}; {@code NaN} when it does not converge.
     */
    private double crystalStepPressure(double t, double start, Crystal c, Root root, Workspace w) {
        int i = c.component();
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = 1.0;
        var anchor = c.anchored().anchor();
        double y;
        if (Double.isFinite(start) && start > 0.0) {
            y = DeterministicMath.log(start);
        } else {
            // Clausius-Clapeyron from the triple point with the ideal-gas sublimation enthalpy there.
            double sublimation = evaluator.idealGas(i).enthalpy(anchor.temperature())
                    - (anchor.liquidEnthalpy() - c.anchored().record().anchor().fusionEnthalpy());
            y = DeterministicMath.log(anchor.pressure()) - sublimation / R * (1.0 / t - 1.0 / anchor.temperature());
        }
        double maximum = DeterministicMath.log(NumericalBox.MAXIMUM_PRESSURE);
        double low = Double.NaN;
        double high = Double.NaN;
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            double p = DeterministicMath.exp(y);
            PhaseState fluid = evaluator.evaluate(t, p, w.pure, root, w.phases, w.pureState);
            PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
            w.outerKernel++;
            double g = (fluid.chemicalPotential(i) - solid.molarGibbsEnergy()) / (R * t);
            if (g == 0.0) return p;
            if (g > 0.0) high = y; else low = y;
            double slope = p * (fluid.molarVolume() - solid.molarVolume()) / (R * t);
            double next = y - g / slope;
            boolean bracketed = !Double.isNaN(low) && !Double.isNaN(high);
            if (!(slope > 0.0) || !Double.isFinite(next) || bracketed && !(next > low && next < high)) {
                if (!bracketed) return Double.NaN;
                next = 0.5 * (low + high);
            }
            next = Math.min(next, maximum);
            if (Math.abs(next - y) <= 1.0e-14) return DeterministicMath.exp(next);
            if (next == y) return Double.NaN;
            y = next;
        }
        return Double.NaN;
    }

    /** A start of the sublimation-temperature solve at {@code p}: Clausius-Clapeyron from the crystal's triple point. */
    private double cold(double p, Crystal c) {
        var anchor = c.anchored().anchor();
        double sublimation = evaluator.idealGas(c.component()).enthalpy(anchor.temperature())
                - (anchor.liquidEnthalpy() - c.anchored().record().anchor().fusionEnthalpy());
        return 1.0 / (1.0 / anchor.temperature() - R * DeterministicMath.log(p / anchor.pressure()) / sublimation);
    }

    /**
     * The pure species of a competing crystal at its family's triple point in UV: the vapour, the liquid and the crystal
     * at the family's triple point ({@link Crystal#tripleTemperature()}, {@link Crystal#triplePressure()}), with the
     * amounts from the three balances (moles, energy, volume). A target inside the triple point's triangle (every amount
     * nonnegative within the specification tolerance) has this answer, {@link Classification#VAPOR_LIQUID_SOLID}, before
     * any search (at that volume {@code U(T)} steps at the triple temperature); otherwise null. A near-pure feed (D21)
     * carries its traces in the larger fluid phase of the balances, that fluid evaluated at its own composition and the
     * balances solved again until the amounts settle; with more than {@link #STEP_FLUID_TRACES} of traces in it the
     * answer is the typed {@link EquilibriumResult#CRYSTAL_STEP} hold.
     */
    private EquilibriumResult pureTriplePoint(double u, double v, PhaseCompetition competition, Workspace w) {
        Crystal c = pureCrystal(w);
        if (c == null || w.wet) return null;
        double t = c.tripleTemperature();
        double p = c.triplePressure();
        int i = c.component();
        PhaseState vapour = evaluator.evaluate(t, p, w.feed, Root.VAPOR, w.phases, w.threeVapour);
        PhaseState liquid = evaluator.evaluate(t, p, w.feed, Root.LIQUID, w.phases, w.threeLiquid);
        PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
        w.outerKernel += 2;
        double n = w.hydrocarbonTotal;
        double traces = traces(i, w);
        // n_V + n_L + n_S = N; n_V u_V + n_L u_L + n_S u_s = U; n_V v_V + n_L v_L + n_S v_s = V.
        double[] b = {n, u, v};
        double[] amounts = insideTriangle(tripleBalances(vapour, liquid, solid), b, t);
        if (amounts == null) return null;
        boolean inVapour = amounts[0] >= amounts[1];
        String species = evaluator.components().get(i);
        if (traces > 0.0) {
            // D21: the traces in the larger fluid at its own composition, the other fluid the pure species.
            if (!((inVapour ? amounts[0] : amounts[1]) * STEP_FLUID_TRACES >= traces)) return tripleHold(u, v, amounts, traces, species, competition, w);
            Arrays.fill(w.pure, 0.0);
            w.pure[i] = 1.0;
            for (int k = 0; k < NEAR_PURE_ITERATIONS; k++) {
                System.arraycopy(w.feed, 0, w.stepFluid, 0, count);
                w.stepFluid[i] = (inVapour ? amounts[0] : amounts[1]) - traces;
                vapour = evaluator.evaluate(t, p, inVapour ? w.stepFluid : w.pure, Root.VAPOR, w.phases, w.threeVapour);
                liquid = evaluator.evaluate(t, p, inVapour ? w.pure : w.stepFluid, Root.LIQUID, w.phases, w.threeLiquid);
                w.outerKernel += 2;
                double[] next = insideTriangle(tripleBalances(vapour, liquid, solid), b, t);
                if (next == null) return null;
                boolean settled = Math.abs(next[0] - amounts[0]) <= 4.0 * Math.ulp(n) && Math.abs(next[2] - amounts[2]) <= 4.0 * Math.ulp(n);
                amounts = next;
                if (settled) break;
            }
            if (!((inVapour ? amounts[0] : amounts[1]) * STEP_FLUID_TRACES >= traces)) return tripleHold(u, v, amounts, traces, species, competition, w);
        }
        double nv = amounts[0];
        double nl = amounts[1];
        double ns = amounts[2];
        int slot = c.slot();
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (nv > 0.0) phases.add(new PhaseAmounts(PhaseKind.VAPOR, null, t, p, tripleAmounts(slot, nv, traces > 0.0 && inVapour, i, w), vapour.copy()));
        if (nl > 0.0) phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t, p, tripleAmounts(slot, nl, traces > 0.0 && !inVapour, i, w), liquid.copy()));
        if (ns > 0.0) phases.add(new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, single(slot, ns), solid.copy()));
        Classification classification = crystalClassification(nv > 0.0, nl > 0.0, ns > 0.0);
        if (classification == null) return null;
        String note = "pure " + species + " at its family's triple point (" + t + " K, " + p + " Pa): vapour " + nv + ", liquid " + nl
                + ", " + c.id() + " " + ns + " mol from the mole, energy and volume balances"
                + (traces > 0.0 ? "; near-pure (D21): " + traces + " mol of other species in the " + (inVapour ? "vapour" : "liquid") : "");
        var deposition = ns > 0.0 ? new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, ns, false, 0, 0) : null;
        EquilibriumResult answer = new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), null, deposition);
        return outerAnswer(Specification.UV, answer, u, v, true, null, w);
    }

    /**
     * Pure water with only its own steam at its triple point, the end of the free water's melting line where no gas holds
     * the pressure above the steam's: steam, liquid water and ice at the ice record's triple point (273.16 K and
     * 611.657 Pa), with the amounts from the mole, energy and volume balances over the corners {@link #waterTriangle}, as
     * {@link #pureTriplePoint} answers a crystal's species. A target inside the triangle ({@link #insideTriangle}) has
     * this answer before any search (at its volume {@code U(T)} rises by the latent heats within 2e-9 K of the triple
     * temperature, a step no search converges on); a rigid vessel of water and its steam cooled through 0 °C stands here
     * until its liquid is gone. Otherwise null.
     */
    private EquilibriumResult waterTriplePoint(double u, double v, PhaseCompetition competition, Workspace w) {
        if (waterTriangle == null) return null;
        FreeWaterIce ice = freeWater.ice();
        double t = ice.tripleTemperature();
        double p = ice.triplePressure();
        double[] amounts = insideTriangle(tripleBalances(waterTriangle), new double[] {w.waterTotal, u, v}, t);
        if (amounts == null) return null;
        double saturation = freeWater.saturationPressure(t);
        double nv = amounts[0];
        double nl = amounts[1];
        double ns = amounts[2];
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (nv > 0.0) phases.add(gasPhase(t, p, p, 0.0, nv, null, w));
        if (nl > 0.0) phases.add(freeWaterPhase(t, p, saturation, nl, w));
        if (ns > 0.0) phases.add(icePhase(t, p, ice.fugacity(t, p), ns, w));
        var split = new EquilibriumResult.FreeWater(Classification.NONE, saturation, p, nv > 0.0 ? p : 0.0, nl, nv, ns);
        String note = "pure water at its triple point (" + t + " K, " + p + " Pa): steam " + nv + ", liquid " + nl + ", ice " + ns
                + " mol from the mole, energy and volume balances";
        EquilibriumResult answer = converged(competition, Classification.FREE_WATER, phases, List.of(), diagnostics(w, null, false),
                null, split, note);
        return outerAnswer(Specification.UV, answer, u, v, true, null, w);
    }

    /**
     * The triple point's amounts (vapour, liquid, crystal) at {@code b = (N, U, V)} over the balances {@code a}
     * ({@link #tripleBalances}) at the triple temperature {@code t}: {@link #solve3}'s vapour and crystal with the liquid
     * from the mole balance, when every one is nonnegative within the specification tolerance. An amount below zero is set
     * to 0, the others keeping the moles, where the answer then still meets the specification: the energy to
     * {@link #SPECIFICATION_TOLERANCE} of {@code N R T} and the volume in {@code ln V}. A tolerance in moles would not bound
     * the volume, the vapour's molar volume being the condensed phases' times 1e2 for CO2 and 2e5 for water at 611.657 Pa.
     * Otherwise null.
     */
    private static double[] insideTriangle(double[] a, double[] b, double t) {
        double[] solved = solve3(a, b);
        if (solved == null) return null;
        double n = b[0];
        double nv = Math.max(solved[0], 0.0);
        double ns = Math.max(solved[2], 0.0);
        double nl = n - nv - ns;
        if (solved[0] >= 0.0 && solved[2] >= 0.0 && nl >= 0.0) return new double[] {nv, nl, ns};
        if (nl < 0.0) {
            double scale = n / (nv + ns);
            nv *= scale;
            ns *= scale;
            nl = 0.0;
        }
        double energy = nv * a[3] + nl * a[4] + ns * a[5];
        double volume = nv * a[6] + nl * a[7] + ns * a[8];
        if (!(Math.abs(energy - b[1]) <= SPECIFICATION_TOLERANCE * n * R * t)
                || !(Math.abs(DeterministicMath.log(volume / b[2])) <= SPECIFICATION_TOLERANCE)) return null;
        return new double[] {nv, nl, ns};
    }

    /** The UV hold of a near-pure triple point whose fluids are too small for its traces (D21 option C), typed. */
    private EquilibriumResult tripleHold(double u, double v, double[] amounts, double traces, String species, PhaseCompetition competition,
                                         Workspace w) {
        return outerAnswer(Specification.UV, crystalStepHold("the specified energy and volume put near-pure " + species + " at its family's triple point with vapour "
                + amounts[0] + ", liquid " + amounts[1] + " and crystal " + amounts[2] + " mol, too little fluid for the " + traces
                + " mol of other species within " + STEP_FLUID_TRACES + " of it", competition, w), u, v, false, null, w);
    }

    /**
     * Whether {@code (N, U, V)} may lie inside crystal {@code c}'s triple-point triangle: the balances over its pure
     * corners ({@link Crystal#triangle()}) leave no amount below {@link #TRIANGLE_SCREEN} of {@code N} (a screen that
     * spares a search the triple point's evaluations; {@link #pureTriplePoint} decides on the feed's own states).
     */
    private static boolean nearTriangle(double u, double v, double n, Crystal c) {
        double[] m = c.triangle();
        if (m == null) return false;
        double[] amounts = solve3(tripleBalances(m), new double[] {n, u, v});
        if (amounts == null) return false;
        for (double amount : amounts) if (!(amount >= -TRIANGLE_SCREEN * n)) return false;
        return true;
    }

    /** The triple point's mole, energy and volume balances over (vapour, liquid, crystal), row-major for {@link #solve3}. */
    private static double[] tripleBalances(PhaseState vapour, PhaseState liquid, PhaseState solid) {
        return new double[] {1.0, 1.0, 1.0, vapour.molarInternalEnergy(), liquid.molarInternalEnergy(), solid.molarInternalEnergy(),
                vapour.molarVolume(), liquid.molarVolume(), solid.molarVolume()};
    }

    /** The same balances over a triangle's corners, {@code (u_V, v_V, u_L, v_L, u_s, v_s)}. */
    private static double[] tripleBalances(double[] corners) {
        return new double[] {1.0, 1.0, 1.0, corners[0], corners[2], corners[4], corners[1], corners[3], corners[5]};
    }

    /**
     * A triple-point fluid phase's amounts: {@code amount} of the species alone, or (D21, {@code carrier}) the feed's
     * traces with the rest of {@code amount} of species {@code i}.
     */
    private double[] tripleAmounts(int slot, double amount, boolean carrier, int i, Workspace w) {
        if (!carrier) return single(slot, amount);
        double[] amounts = new double[basis];
        double traces = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int j = w.present[a];
            if (j == i) continue;
            amounts[basisOfComponent[j]] = w.feed[j];
            traces += w.feed[j];
        }
        amounts[slot] = amount - traces;
        return amounts;
    }

    /** The classification of a crystal answer by its phases (vapour, liquid, crystal), or null for none of the known sets. */
    private static Classification crystalClassification(boolean vapour, boolean liquid, boolean solid) {
        if (vapour && liquid && solid) return Classification.VAPOR_LIQUID_SOLID;
        if (vapour && solid) return Classification.VAPOR_SOLID;
        if (liquid && solid) return Classification.LIQUID_SOLID;
        if (vapour && liquid) return Classification.VAPOR_LIQUID;
        if (solid) return Classification.SOLID_PRESENT;
        if (vapour) return Classification.SINGLE_VAPOR;
        if (liquid) return Classification.SINGLE_LIQUID;
        return null;
    }

    private double[] single(int slot, double amount) {
        double[] amounts = new double[basis];
        amounts[slot] = amount;
        return amounts;
    }

    /** Solves the 3x3 system {@code a x = b} ({@code a} row-major) by Cramer's rule; null when it is singular. */
    private static double[] solve3(double[] a, double[] b) {
        double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        if (!(Math.abs(det) > 0.0) || !Double.isFinite(det)) return null;
        double x0 = (b[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (b[1] * a[8] - a[5] * b[2]) + a[2] * (b[1] * a[7] - a[4] * b[2])) / det;
        double x1 = (a[0] * (b[1] * a[8] - a[5] * b[2]) - b[0] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * b[2] - b[1] * a[6])) / det;
        double x2 = (a[0] * (a[4] * b[2] - b[1] * a[7]) - a[1] * (a[3] * b[2] - b[1] * a[6]) + b[0] * (a[3] * a[7] - a[4] * a[6])) / det;
        if (!Double.isFinite(x0) || !Double.isFinite(x1) || !Double.isFinite(x2)) return null;
        return new double[] {x0, x1, x2};
    }

    /**
     * Whether a binary's three-phase point is due from bracket {@code s} (P5): it straddles the step, and this is the
     * search's first attempt, or one of three more once the bracket is narrower than {@link #THREE_PHASE_BRACKET}
     * ({@code width}: relative in T for PH, in ln P for UV); a closed bracket is always tried by the closing branch.
     */
    private static boolean threePhaseDue(Search s, double width) {
        if (!straddlesThreePhase(s)) return false;
        if (s.threePhaseAttempts == 0 || width <= THREE_PHASE_BRACKET && s.threePhaseAttempts < 4) {
            s.threePhaseAttempts++;
            return true;
        }
        return false;
    }

    /**
     * Whether a closed or narrow PH or UV bracket of a binary with a competing crystal straddles its three-phase
     * (vapour-liquid-crystal) step (P5): both ends converged, together holding a vapour, a liquid and the crystal, at
     * least one end the crystal. At fixed T and P a binary has the three phases only on its three-phase line, so
     * {@code H(T)} at fixed pressure and {@code V(P)} at fixed temperature step there, like a pure fluid's coexistence.
     */
    private static boolean straddlesThreePhase(Search s) {
        if (s.lowerResult == null || s.upperResult == null) return false;
        boolean vapour = false, liquid = false, solid = false;
        for (EquilibriumResult end : new EquilibriumResult[] {s.lowerResult, s.upperResult}) {
            if (!end.converged()) return false;
            for (PhaseAmounts phase : end.phases()) {
                switch (phase.kind()) {
                    case SOLID -> solid = true;
                    case LIQUID -> liquid = true;
                    default -> vapour = true;
                }
            }
        }
        return vapour && liquid && solid;
    }

    /**
     * The three-phase step of a dry binary with a competing crystal: at the fixed pressure (PH) the temperature, at the
     * fixed temperature (UV) the pressure where the vapour, a liquid and the crystal coexist, and the three amounts from
     * the two species' balances and the energy or volume balance. The three-phase point is solved from the bracket's two
     * ends: Newton on {@code (X, ln x_i, ln y_i)} ({@code X = 1/T} for PH, {@code ln P} for UV) for
     * {@code (mu_i^L - mu_s, mu_i^V - mu_s, mu_j^L - mu_j^V)/(R T) = 0}, a finite-difference Jacobian, steps capped, to
     * {@code 1e-11}; it must lie inside the bracket with two distinct fluid phases. One free amount along
     * {@code d = (1 - x_i, -(1 - y_i), x_i - y_i)} keeps both species' balances and the third balance fixes it. As
     * {@link #pureCoexistence}: the answer when every amount is nonnegative within the tolerance (or clamped), otherwise
     * null with the step's end in {@code coexistenceX} and {@code coexistenceResidual}, NaN when no three-phase point
     * lies in the bracket. The liquid's other species and the crystal are taken by difference, so both species conserve.
     */
    private EquilibriumResult binaryThreePhase(Specification specification, Search s, double t, double p, double target,
                                               boolean clamp, PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        Crystal c = binaryCrystal(w);
        if (c == null || !straddlesThreePhase(s)) return null;
        boolean energy = specification == Specification.PH;
        int i = c.component();
        int j = binaryPartner(i, w);
        double x = Double.NaN;
        double y = Double.NaN;
        for (EquilibriumResult end : new EquilibriumResult[] {s.lowerResult, s.upperResult}) {
            for (PhaseAmounts phase : end.phases()) {
                if (phase.kind() == PhaseKind.LIQUID && Double.isNaN(x)) x = phase.state().moleFraction(i);
                else if (phase.kind() != PhaseKind.SOLID && phase.kind() != PhaseKind.LIQUID && Double.isNaN(y)) y = phase.state().moleFraction(i);
            }
        }
        if (!(x > 0.0 && x < 1.0 && y > 0.0 && y < 1.0)) return null;
        double lower = energy ? Math.min(s.lower, s.upper) : DeterministicMath.exp(Math.min(s.lower, s.upper));
        double upper = energy ? Math.max(s.lower, s.upper) : DeterministicMath.exp(Math.max(s.lower, s.upper));
        double[] u = {energy ? 1.0 / t : DeterministicMath.log(p), DeterministicMath.log(x), DeterministicMath.log(y)};
        double[] r = new double[3];
        double[] trial = new double[3];
        double[] shifted = new double[3];
        double[] jacobian = new double[9];
        boolean converged = false;
        for (int k = 0; k < THREE_PHASE_ITERATIONS; k++) {
            if (!threePhaseResidual(energy, energy ? p : t, u, c, i, j, r, w)) return null;
            double norm = Math.max(Math.abs(r[0]), Math.max(Math.abs(r[1]), Math.abs(r[2])));
            if (norm <= THREE_PHASE_TOLERANCE) {
                converged = true;
                break;
            }
            for (int col = 0; col < 3; col++) {
                double h = col == 0 ? 1.0e-7 * Math.abs(u[0]) + (energy ? 0.0 : 1.0e-7) : 1.0e-7;
                System.arraycopy(u, 0, shifted, 0, 3);
                shifted[col] += h;
                if (!threePhaseResidual(energy, energy ? p : t, shifted, c, i, j, trial, w)) return null;
                for (int row = 0; row < 3; row++) jacobian[3 * row + col] = (trial[row] - r[row]) / h;
            }
            double[] step = solve3(jacobian, new double[] {-r[0], -r[1], -r[2]});
            if (step == null) return null;
            double scale = 1.0;
            scale = Math.min(scale, (energy ? 0.02 * Math.abs(u[0]) : 0.2) / Math.max(Math.abs(step[0]), 1.0e-300));
            scale = Math.min(scale, 1.0 / Math.max(Math.abs(step[1]), 1.0e-300));
            scale = Math.min(scale, 1.0 / Math.max(Math.abs(step[2]), 1.0e-300));
            for (int a = 0; a < 3; a++) u[a] += scale * step[a];
            u[1] = Math.min(u[1], -1.0e-12);
            u[2] = Math.min(u[2], -1.0e-12);
        }
        if (!converged) return null;
        double t3 = energy ? 1.0 / u[0] : t;
        double p3 = energy ? p : DeterministicMath.exp(u[0]);
        double x3 = DeterministicMath.exp(u[1]);
        double y3 = DeterministicMath.exp(u[2]);
        double variable = energy ? t3 : p3;
        double slack = 1.0e-9 * variable;
        if (!(variable >= lower - slack && variable <= upper + slack)) return null;
        PhaseState vapour = evaluateBinary(t3, p3, y3, i, j, Root.VAPOR, w.threeVapour, w);
        PhaseState liquid = evaluateBinary(t3, p3, x3, i, j, Root.LIQUID, w.threeLiquid, w);
        PhaseState solid = c.anchored().evaluate(t3, p3, w.solidState);
        w.outerKernel += 2;
        // Two distinct fluid phases: not the trivial solution of one fluid at its onset.
        if (!(Math.abs(liquid.molarVolume() - vapour.molarVolume()) > 1.0e-3 * liquid.molarVolume()) || !(Math.abs(x3 - y3) > 1.0e-9)) return null;
        double ni = w.feed[i];
        double nj = w.feed[j];
        double vapourValue = energy ? vapour.molarEnthalpy() : vapour.molarVolume();
        double liquidValue = energy ? liquid.molarEnthalpy() : liquid.molarVolume();
        double solidValue = energy ? solid.molarEnthalpy() : solid.molarVolume();
        // Rows: species i, species j, the third balance.
        double[] a = {y3, x3, 1.0, 1.0 - y3, 1.0 - x3, 0.0, vapourValue, liquidValue, solidValue};
        double[] amounts = solve3(a, new double[] {ni, nj, target});
        if (amounts == null) return null;
        double n = ni + nj;
        double allowance = SPECIFICATION_TOLERANCE * n;
        boolean inside = amounts[0] >= -allowance && amounts[1] >= -allowance && amounts[2] >= -allowance;
        if (!inside && !clamp) {
            // The end of the step on the target's side: move along d (both species' balances kept) until a phase vanishes.
            double[] d = {1.0 - x3, -(1.0 - y3), x3 - y3};
            double third = d[0] * vapourValue + d[1] * liquidValue + d[2] * solidValue;
            double lambdaLow = Double.NEGATIVE_INFINITY, lambdaHigh = Double.POSITIVE_INFINITY;
            for (int a2 = 0; a2 < 3; a2++) {
                if (d[a2] > 0.0) lambdaLow = Math.max(lambdaLow, -amounts[a2] / d[a2]);
                else if (d[a2] < 0.0) lambdaHigh = Math.min(lambdaHigh, -amounts[a2] / d[a2]);
            }
            if (!(lambdaLow <= lambdaHigh)) return null;
            // amounts is at lambda = 0 (the target); the end nearer it.
            double lambda = 0.0 < lambdaLow ? lambdaLow : lambdaHigh;
            double value = target + lambda * third;
            w.coexistenceX = energy ? t3 : DeterministicMath.log(p3);
            w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
            return null;
        }
        if (!inside) {
            double[] d = {1.0 - x3, -(1.0 - y3), x3 - y3};
            double lambdaLow = Double.NEGATIVE_INFINITY, lambdaHigh = Double.POSITIVE_INFINITY;
            for (int a2 = 0; a2 < 3; a2++) {
                if (d[a2] > 0.0) lambdaLow = Math.max(lambdaLow, -amounts[a2] / d[a2]);
                else if (d[a2] < 0.0) lambdaHigh = Math.min(lambdaHigh, -amounts[a2] / d[a2]);
            }
            if (!(lambdaLow <= lambdaHigh)) return null;
            double lambda = Math.clamp(0.0, lambdaLow, lambdaHigh);
            for (int a2 = 0; a2 < 3; a2++) amounts[a2] += lambda * d[a2];
        }
        double nv = Math.max(amounts[0], 0.0);
        double nl = Math.max(amounts[1], 0.0);
        double ns = Math.max(amounts[2], 0.0);
        // Per species: the vapour's and the liquid's own amounts, the rest by difference (both species conserved to rounding).
        double vi, vj, li, lj;
        if (nv == 0.0) {
            vi = 0.0;
            vj = 0.0;
            lj = nj;
            li = ns == 0.0 ? ni : Math.min(nl * x3, ni);
        } else if (nl == 0.0) {
            li = 0.0;
            lj = 0.0;
            vj = nj;
            vi = ns == 0.0 ? ni : Math.min(nv * y3, ni);
        } else {
            vi = Math.min(nv * y3, ni);
            vj = Math.min(nv * (1.0 - y3), nj);
            lj = nj - vj;
            li = ns == 0.0 ? ni - vi : Math.min(nl * x3, ni - vi);
        }
        double si = ns == 0.0 ? 0.0 : ni - vi - li;
        if (si < 0.0) {
            li += si;
            si = 0.0;
        }
        int slotI = basisOfComponent[i];
        int slotJ = basisOfComponent[j];
        double[] vapourAmounts = pair(slotI, vi, slotJ, vj);
        double[] liquidAmounts = pair(slotI, li, slotJ, lj);
        // Traces of a pseudo-binary: each split between the vapour and the liquid by its infinite-dilution K on the three-phase
        // states (equal fugacities to first order in the trace), the vapour's share directly and the liquid's by difference.
        for (int b = 0; b < w.presentCount; b++) {
            int k = w.present[b];
            if (k == i || k == j) continue;
            double amount = w.feed[k];
            double vapourShare;
            if (vi + vj > 0.0 && li + lj > 0.0) {
                double kValue = DeterministicMath.exp(liquid.logFugacityCoefficient(k) - vapour.logFugacityCoefficient(k));
                double nv3 = vi + vj;
                double nl3 = li + lj;
                vapourShare = nv3 * kValue / (nv3 * kValue + nl3);
            } else {
                vapourShare = vi + vj > 0.0 ? 1.0 : 0.0;
            }
            vapourAmounts[basisOfComponent[k]] = amount * vapourShare;
            liquidAmounts[basisOfComponent[k]] = amount - vapourAmounts[basisOfComponent[k]];
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (vi + vj > 0.0) phases.add(new PhaseAmounts(PhaseKind.VAPOR, null, t3, p3, vapourAmounts, vapour.copy()));
        if (li + lj > 0.0) phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t3, p3, liquidAmounts, liquid.copy()));
        if (si > 0.0) phases.add(new PhaseAmounts(PhaseKind.SOLID, c.id(), t3, p3, single(c.slot(), si), solid.copy()));
        Classification classification = crystalClassification(vi + vj > 0.0, li + lj > 0.0, si > 0.0);
        if (classification == null) return null;
        String species = evaluator.components().get(i);
        String note = "three-phase point of the " + species + " binary with " + c.id() + " at (" + t3 + " K, " + p3 + " Pa): x = " + x3
                + ", y = " + y3 + "; vapour " + nv + ", liquid " + nl + ", crystal " + si + " mol from the species and "
                + (energy ? "energy" : "volume") + " balances";
        var deposition = si > 0.0 ? new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, si, false, 0, 0) : null;
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), null, deposition);
    }

    private double[] pair(int first, double a, int second, double b) {
        double[] amounts = new double[basis];
        amounts[first] = a;
        amounts[second] = b;
        return amounts;
    }

    /** The binary's phase of mole fraction {@code fraction} of species {@code i} (the rest {@code j}) on {@code root}. */
    private PhaseState evaluateBinary(double t, double p, double fraction, int i, int j, Root root, PhaseState out, Workspace w) {
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = fraction;
        w.pure[j] = 1.0 - fraction;
        return evaluator.evaluate(t, p, w.pure, root, w.phases, out);
    }

    /**
     * The residuals of the binary three-phase point at {@code u = (X, ln x_i, ln y_i)} ({@code X = 1/T} with the pressure
     * fixed, or {@code ln P} with the temperature fixed): {@code (mu_i^L - mu_s)/(R T)}, {@code (mu_i^V - mu_s)/(R T)},
     * {@code (mu_j^L - mu_j^V)/(R T)}; false when a state is refused.
     */
    private boolean threePhaseResidual(boolean energy, double fixed, double[] u, Crystal c, int i, int j, double[] r, Workspace w) {
        double t = energy ? 1.0 / u[0] : fixed;
        double p = energy ? fixed : DeterministicMath.exp(u[0]);
        double x = DeterministicMath.exp(u[1]);
        double y = DeterministicMath.exp(u[2]);
        if (!(x < 1.0 && y < 1.0) || !Double.isFinite(t) || !Double.isFinite(p) || !(t > 0.0) || !(p > 0.0)) return false;
        try {
            PhaseState liquid = evaluateBinary(t, p, x, i, j, Root.LIQUID, w.threeLiquid, w);
            PhaseState vapour = evaluateBinary(t, p, y, i, j, Root.VAPOR, w.threeVapour, w);
            double mu = c.anchored().chemicalPotential(t, p);
            w.outerKernel += 2;
            double rt = R * t;
            r[0] = (liquid.chemicalPotential(i) - mu) / rt;
            r[1] = (vapour.chemicalPotential(i) - mu) / rt;
            r[2] = (liquid.chemicalPotential(j) - vapour.chemicalPotential(j)) / rt;
            return Double.isFinite(r[0]) && Double.isFinite(r[1]) && Double.isFinite(r[2]);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return false;
        }
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
        return onset(true, null, crystal, pressure, species, amounts, own(workspace));
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
        return onset(true, Root.VAPOR, crystal, pressure, species, amounts, own(workspace));
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
        return onset(true, Root.LIQUID, crystal, pressure, species, amounts, own(workspace));
    }

    /**
     * The onset pressure of crystal {@code crystal}: as {@link #depositionTemperature(String, double, List, double[],
     * Workspace)} with the search in {@code ln P} ({@code D} increases with it; first slope one, the
     * ideal gas's), steps capped at 2 in {@code ln P} before a bracket.
     */
    public DepositionOnset depositionPressure(String crystal, double temperature, List<String> species, double[] amounts,
                                              Workspace workspace) {
        return onset(false, null, crystal, temperature, species, amounts, own(workspace));
    }

    /**
     * @param single the root of the feed as one phase whose chemical potential the search uses ({@link Root#LIQUID}:
     *        the freezing point, {@link Root#VAPOR}: the frost point of the gas as one phase), or {@code null} for the
     *        fluid answer of the whole feed (the onset functions of P4)
     */
    private DepositionOnset onset(boolean temperatureSearch, Root single, String crystalId, double fixed, List<String> species,
                                  double[] amounts, Workspace w) {
        boolean freezing = single == Root.LIQUID;
        if (!(fixed > 0.0) || !Double.isFinite(fixed)) throw new IllegalArgumentException("The fixed temperature or pressure must be positive");
        var request = EquilibriumRequest.tp(temperatureSearch ? 200.0 : fixed, temperatureSearch ? fixed : 1.0e5, species, amounts,
                PhaseCompetition.FLUID_AND_CRYSTALS);
        EquilibriumResult refused = contractChecks(request, w);
        if (refused == null) refused = implementationChecks(Specification.TP, w.competition, w);
        if (refused == null && w.activeCount == 0) {
            refused = unsupported(Specification.TP, w.competition, UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED,
                    "package " + contract.packageId() + " admits no crystal");
        }
        if (refused != null) {
            return DepositionOnset.without(refused.status(), refused.reason(), refused.detail(), crystalId,
                    Double.NaN, Double.NaN, Double.NaN, 0);
        }
        PhaseCompetition competition = w.competition;
        Crystal c = null;
        for (int a = 0; a < w.activeCount; a++) {
            Crystal k = w.active[a];
            if (!(w.feed[k.component()] > 0.0) || crystalId != null && !k.id().equals(crystalId)) continue;
            if (c != null) throw new IllegalArgumentException("Several competing crystals are made of carried species; name one");
            c = k;
        }
        if (c == null) {
            throw new IllegalArgumentException("No competing crystal" + (crystalId == null ? "" : " " + crystalId)
                    + " is made of a carried species");
        }
        int i = c.component();
        double y = w.feed[i] / w.hydrocarbonTotal;
        // The window: the numerical box below the competition's ceiling, where its crystals cease to exist (owner rule of
        // 2026-10-16: the models continue past their data ranges).
        double tLow = NumericalBox.MINIMUM_TEMPERATURE;
        double tHigh = Math.min(NumericalBox.MAXIMUM_TEMPERATURE, crystalCeiling(w));
        double pLow = NumericalBox.MINIMUM_PRESSURE;
        double pHigh = NumericalBox.MAXIMUM_PRESSURE;
        var anchor = c.anchored().anchor();
        double temperature = temperatureSearch ? Double.NaN : fixed;
        double pressure = temperatureSearch ? fixed : Double.NaN;
        // The fixed variable inside the window.
        if (temperatureSearch ? pressure < pLow || pressure > pHigh : temperature < tLow || temperature > tHigh) {
            String detail = temperatureSearch ? "the pressure " + pressure + " Pa lies outside the numerical box " + pLow + " .. " + pHigh + " Pa"
                    : "the temperature " + temperature + " K lies outside the window " + tLow + " .. " + tHigh
                    + " K (the numerical box below the ceiling of " + competition + ", where its crystals cease to exist)";
            return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, detail, c.id(), temperature, pressure, Double.NaN, 0);
        }
        // The partial-pressure approximation, a diagnostic: the pure sublimation line at y_i P, or p_sub(T)/y_i.
        double approximation = Double.NaN;
        if (temperatureSearch) {
            if (y * pressure <= anchor.pressure()) {
                double estimate = crystalStepTemperature(y * pressure, Double.NaN, c, Root.VAPOR, w);
                if (estimate <= anchor.temperature()) approximation = estimate;
            }
        } else if (temperature <= anchor.temperature()) {
            double sublimation = crystalStepPressure(temperature, Double.NaN, c, Root.VAPOR, w);
            if (sublimation > 0.0) approximation = sublimation / y;
        }
        double xLow = temperatureSearch ? 1.0 / tHigh : DeterministicMath.log(pLow);
        double xHigh = temperatureSearch ? 1.0 / tLow : DeterministicMath.log(pHigh);
        // A pressure search without an approximation starts at the top of the package's data, inside the box.
        double xStart = temperatureSearch ? xLow : Math.min(xHigh, DeterministicMath.log(contract.domain().envelope()[3]));
        double x;
        if (freezing) {
            // The ideal-solubility estimate of the liquidus (a start only).
            approximation = Double.NaN;
            double fusion = c.fusionEnthalpy();
            double start = 1.0 / (1.0 / c.tripleTemperature() - R * DeterministicMath.log(Math.min(y, 1.0)) / fusion);
            x = Double.isFinite(start) && start > 0.0 ? Math.clamp(1.0 / start, xLow, xHigh) : xLow;
        } else if (Double.isFinite(approximation) && (temperatureSearch ? approximation >= tLow && approximation <= tHigh
                : approximation >= pLow && approximation <= pHigh)) {
            x = temperatureSearch ? 1.0 / approximation : DeterministicMath.log(approximation);
        } else {
            x = xStart;
        }
        double negative = Double.NaN;
        double positive = Double.NaN;
        double negativeValue = Double.NaN;
        double positiveValue = Double.NaN;
        double previousX = Double.NaN;
        double previousD = Double.NaN;
        int evaluations = 0;
        EquilibriumResult fluid;
        double d;
        while (true) {
            if (evaluations >= ONSET_EVALUATIONS) {
                return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the onset search of " + c.id()
                        + " did not converge in " + evaluations + " fluid equilibria", c.id(), temperature, pressure,
                        approximation, evaluations);
            }
            double t = temperatureSearch ? 1.0 / x : temperature;
            double p = temperatureSearch ? pressure : DeterministicMath.exp(x);
            if (single != null) {
                fluid = null;
                d = singlePhaseDrive(t, p, c, single, w);
                evaluations++;
                if (Double.isNaN(d)) {
                    return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the liquid at " + t + " K, " + p
                            + " Pa of the freezing search of " + c.id() + " was refused by the equation of state", c.id(),
                            temperature, pressure, approximation, evaluations);
                }
            } else {
                fluid = fluidAnswer(t, p, competition, w);
                evaluations++;
                if (!fluid.converged()) {
                    return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the fluid equilibrium at " + t + " K, "
                            + p + " Pa of the onset search of " + c.id() + ": " + fluid.detail(), c.id(), temperature, pressure,
                            approximation, evaluations);
                }
                d = (fluidChemicalPotential(fluid, i) - c.anchored().chemicalPotential(t, p)) / (R * t);
            }
            if (Math.abs(d) <= DEPOSITION_TOLERANCE) break;
            if (d > 0.0) {
                positive = x;
                positiveValue = d;
            } else {
                negative = x;
                negativeValue = d;
            }
            boolean bracketed = !Double.isNaN(positive) && !Double.isNaN(negative);
            if (bracketed && Math.abs(positive - negative) <= (temperatureSearch ? 1.0e-13 * x : 1.0e-13)) {
                if (Math.abs(negativeValue) < Math.abs(positiveValue)) x = negative; else x = positive;
                t = temperatureSearch ? 1.0 / x : temperature;
                p = temperatureSearch ? pressure : DeterministicMath.exp(x);
                if (single != null) {
                    d = singlePhaseDrive(t, p, c, single, w);
                    evaluations++;
                    break;
                }
                fluid = fluidAnswer(t, p, competition, w);
                evaluations++;
                if (!fluid.converged()) continue;
                d = (fluidChemicalPotential(fluid, i) - c.anchored().chemicalPotential(t, p)) / (R * t);
                break;
            }
            double slope = Double.NaN;
            if (!Double.isNaN(previousX) && previousX != x) slope = (d - previousD) / (x - previousX);
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                slope = temperatureSearch
                        ? (evaluator.idealGas(i).enthalpy(t) - c.anchored().properties(t, p).enthalpy()) / R : 1.0;
            }
            previousX = x;
            previousD = d;
            double next = x - d / slope;
            if (bracketed) {
                double a = Math.min(positive, negative);
                double b = Math.max(positive, negative);
                if (!(next > a && next < b)) next = 0.5 * (a + b);
            } else {
                double cap = temperatureSearch ? 0.2 * x : PRESSURE_STEP_CAP;
                next = x + Math.clamp(next - x, -cap, cap);
                if (!Double.isFinite(next)) next = x;
                if (d < 0.0 && x >= xHigh || d > 0.0 && x <= xLow) {
                    // The onset lies beyond the window: colder (or denser) than its end without a crystal, or warmer (or
                    // more dilute) than its other end with one.
                    boolean beyondHigh = d < 0.0;
                    // x = 1/T: beyond xHigh is below tLow; x = ln P: beyond xHigh is above pHigh.
                    boolean below = temperatureSearch == beyondHigh;
                    String detail = "the onset of " + c.id() + " lies " + (below ? "below" : "above") + " the "
                            + (temperatureSearch ? "temperature window " + tLow + " .. " + tHigh + " K" : "pressure window " + pLow + " .. "
                            + pHigh + " Pa") + " (the numerical box below the ceiling of " + competition + ")";
                    return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, detail, c.id(),
                            temperature, pressure, approximation, evaluations);
                }
                next = Math.clamp(next, xLow, xHigh);
            }
            x = next;
        }
        double t = temperatureSearch ? 1.0 / x : temperature;
        double p = temperatureSearch ? pressure : DeterministicMath.exp(x);
        if (single != null) {
            fluid = fluidAnswer(t, p, competition, w);
            evaluations++;
            if (!fluid.converged()) {
                return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the fluid equilibrium at the one-phase onset "
                        + t + " K, " + p + " Pa of " + c.id() + ": " + fluid.detail(), c.id(), t, p, approximation, evaluations);
            }
        }
        if (fluid.classification() == Classification.LIQUID_LIQUID) {
            String detail = EquilibriumResult.CRYSTAL_PHASES + ": the onset of " + c.id() + " at " + t + " K, " + p + " Pa lies where the"
                    + " fluid is two liquids; that phase set beside a crystal is not implemented";
            return DepositionOnset.without(EquilibriumResult.Status.UNSUPPORTED, UnsupportedReason.NOT_IMPLEMENTED, detail, c.id(),
                    t, p, approximation, evaluations);
        }
        EquilibriumResult atOnset = graded(assessed(fluid, "onset of " + c.id() + ": |mu_i - mu_s|/RT = " + Math.abs(d), w), c, w);
        String kind = freezing
                ? holdsLiquid(fluid) && fluid.phases().size() == 1 ? "a liquidus point (the liquid is the stable fluid, " + fluid.classification() + ")"
                        : "a liquidus point of the liquid as one phase (the stable fluid of the feed there is " + fluid.classification() + ")"
                : single != null ? !holdsLiquid(fluid) ? "a frost point (the gas is the stable fluid, " + fluid.classification() + ")"
                        : "a frost point of the gas as one phase (the stable fluid of the feed there is " + fluid.classification() + ")"
                : holdsLiquid(fluid) ? fluid.phases().size() > 1 ? "a three-phase point (the fluid is " + fluid.classification() + ")"
                        : "a liquidus point (the fluid is " + fluid.classification() + ")" : "a frost point (the fluid is " + fluid.classification() + ")";
        String detail = "onset of " + c.id() + " at " + t + " K, " + p + " Pa by fugacity (" + evaluations + " fluid equilibria), "
                + kind + (freezing ? "" : "; the partial-pressure approximation " + approximation + (temperatureSearch ? " K" : " Pa")
                + " is a diagnostic, not the answer");
        var coverage = new EquilibriumResult.Coverage(atOnset.coverage().grade(), atOnset.coverage().evidence()
                + "; onset by fugacity" + (freezing ? "" : ", the partial-pressure approximation a diagnostic"));
        return new DepositionOnset(EquilibriumResult.Status.CONVERGED, null, detail, c.id(), t, p, approximation, Math.abs(d),
                evaluations, atOnset, coverage);
    }

    /**
     * {@code (mu_i - mu_s)/(R T)} of the feed as one phase at (t, p) on {@code root} (its only root where it has one), the
     * function of the freezing and one-phase frost searches (P5); {@code NaN} when the equation of state refuses the state.
     */
    private double singlePhaseDrive(double t, double p, Crystal c, Root root, Workspace w) {
        try {
            PhaseState phase = evaluator.evaluate(t, p, w.feed, root, w.phases, w.liquidState);
            w.kernelEvaluations++;
            return (phase.chemicalPotential(c.component()) - c.anchored().chemicalPotential(t, p)) / (R * t);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return Double.NaN;
        }
    }

    /** The fluid answer of the workspace's material at (t, p) with the crystals left out (onset searches). */
    private EquilibriumResult fluidAnswer(double t, double p, PhaseCompetition competition, Workspace w) {
        int active = w.activeCount;
        w.activeCount = 0;
        try {
            return evaluateTp(t, p, competition, w);
        } finally {
            w.activeCount = active;
        }
    }

    private String species(int component) { return evaluator.components().get(component); }

    // Contract checks.

    /** Takes the request's amounts over the contract basis (owner decision O2: a request names the basis, in order) and
     * applies the water-chemistry and competition rules. */
    private EquilibriumResult contractChecks(EquilibriumRequest request, Workspace w) {
        Specification specification = request.specification();
        PhaseCompetition competition = request.competition();
        if (!request.species().equals(contract.components())) {
            throw new IllegalArgumentException("A request names the contract basis " + contract.components() + ", got " + request.species());
        }
        System.arraycopy(request.amountsView(), 0, w.basisAmounts, 0, basis);
        if (waterIndex >= 0 && w.basisAmounts[waterIndex] > 0.0) {
            for (int i : waterChemistry) {
                if (w.basisAmounts[i] > 0.0) {
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

    // The dry calculation: hydrocarbon phases at one pressure.

    private EquilibriumResult solveDry(double t, double p, PhaseCompetition competition, Workspace w) {
        setBranches(t, p, p, w);
        int outcome = hydrocarbon(t, w);
        return switch (outcome) {
            case SINGLE -> singlePhase(t, p, competition, w);
            case MERGED -> merged(t, p, competition, w);
            case SPLIT -> twoPhaseResult(t, p, competition, w);
            default -> failed(t, p, competition, w);
        };
    }

    private void setBranches(double t, double liquidPressure, double vaporPressure, Workspace w) {
        w.liquidPressure = liquidPressure;
        w.vaporPressure = vaporPressure;
        w.branched = liquidPressure != vaporPressure;
        if (w.branched) {
            double logRatio = DeterministicMath.log(vaporPressure / liquidPressure);
            double scale = (vaporPressure - liquidPressure) / (R * t);
            for (int i = 0; i < count; i++) w.vaporShifts[i] = logRatio + scale * translations[i];
        }
    }

    private EquilibriumResult singlePhase(double t, double p, PhaseCompetition competition, Workspace w) {
        TangentPlaneStability.Result feed = w.feedResult;
        boolean band = inBand(t, p, w) || feed.nearestStationaryDistance() < BAND_STATIONARY_DISTANCE && w.presentCount > 1;
        String why = band ? bandReason(t, p, w, feed.nearestStationaryDistance()) : null;
        if (w.presentCount == 1) {
            int only = w.present[0];
            kernel.evaluate(t, p, w.feed, Root.VAPOR, w.mixture, w.first);
            w.kernelEvaluations++;
            if (w.first.physicalRootCount() > 1) {
                kernel.evaluate(t, p, w.feed, Root.LIQUID, w.mixture, w.second);
                w.kernelEvaluations++;
                // d(ln phi_L - ln phi_V)/d ln P = Z_L - Z_V: the gap over the Z gap is the relative distance in
                // pressure to the equal-fugacity pressure, to first order.
                double gap = w.second.logFugacityCoefficient(only) - w.first.logFugacityCoefficient(only);
                double compressibilityGap = w.first.compressibility() - w.second.compressibility();
                if (Math.abs(gap) <= settings.coexistenceTolerance() * compressibilityGap) {
                    PhaseState vapour = evaluator.evaluate(t, p, w.feed, Root.VAPOR, w.phases, w.vaporState).copy();
                    PhaseState liquid = evaluator.evaluate(t, p, w.feed, Root.LIQUID, w.phases, w.liquidState).copy();
                    w.kernelEvaluations += 2;
                    return converged(competition, Classification.PURE_COEXISTENCE_UNDERDETERMINED, List.of(),
                            List.of(vapour, liquid), diagnostics(w, feed, band), why, null);
                }
            }
        }
        Root root = w.singleRoot == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
        PhaseState state = evaluator.evaluate(t, p, w.feed, root, w.phases, w.vaporState);
        w.kernelEvaluations++;
        Classification classification = singleClassification(state, t, p, w);
        PhaseKind label = singleLabel(state, classification, t, p, w);
        var phase = new PhaseAmounts(label, null, t, p, w.basisAmounts, state.copy());
        return converged(competition, classification, List.of(phase), List.of(), diagnostics(w, feed, band), why, null);
    }

    /** The classification of one hydrocarbon phase from its root situation (P2 rule). */
    private Classification singleClassification(PhaseState state, double t, double p, Workspace w) {
        return switch (state.kind()) {
            case VAPOR -> Classification.SINGLE_VAPOR;
            case LIQUID -> Classification.SINGLE_LIQUID;
            default -> w.presentCount == 1 && supercritical(w.present[0], t, p)
                    ? Classification.SUPERCRITICAL_FLUID : Classification.SINGLE_FLUID;
        };
    }

    /**
     * The label of a single phase: the root for three roots; {@link PhaseKind#FLUID} for a pure supercritical fluid;
     * otherwise the phase identification parameter of its one root: liquid-like above one and above the root's
     * compressibility factor ({@link PhaseIdentification#liquidLike(double, double)}, P3 WP7c: a hot dilute gas has a
     * parameter just above one and its {@code Z} above that).
     */
    private PhaseKind singleLabel(PhaseState state, Classification classification, double t, double p, Workspace w) {
        return switch (classification) {
            case SINGLE_VAPOR -> PhaseKind.VAPOR;
            case SINGLE_LIQUID -> PhaseKind.LIQUID;
            case SUPERCRITICAL_FLUID -> PhaseKind.FLUID;
            default -> {
                Root root = state.root() == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
                kernel.evaluate(t, p, w.feed, root, w.mixture, w.first);
                w.kernelEvaluations++;
                double parameter = PhaseIdentification.parameter(w.first, t, p);
                yield Double.isNaN(parameter) ? PhaseKind.FLUID
                        : PhaseIdentification.liquidLike(parameter, w.first.compressibility()) ? PhaseKind.LIQUID : PhaseKind.VAPOR;
            }
        };
    }

    private boolean supercritical(int component, double t, double p) {
        var constants = evaluator.component(component);
        return t > constants.criticalTemperatureKelvin() && p > constants.criticalPressurePascal();
    }

    /** A near-critical split below the model's resolution: one fluid, the feed on its stability root, in the band. */
    private EquilibriumResult merged(double t, double p, PhaseCompetition competition, Workspace w) {
        Root root = w.feedResult.feedRoot() == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
        PhaseState state = evaluator.evaluate(t, p, w.feed, root, w.phases, w.vaporState);
        w.kernelEvaluations++;
        var phase = new PhaseAmounts(PhaseKind.FLUID, null, t, p, w.basisAmounts, state.copy());
        return converged(competition, Classification.SINGLE_FLUID, List.of(phase), List.of(), diagnostics(w, w.feedResult, true),
                mergedDetail(w), null);
    }

    private String mergedDetail(Workspace w) {
        return "merged near-critical split (sum z ln K^2 = " + w.tieLine + " below " + settings.mergeTieLine()
                + "): the stability test found the feed unstable, but its two phases coincide within the model's resolution";
    }

    /**
     * A dry split. The denser phase is the liquid and the lighter the vapour, unless both are liquid-like
     * ({@code PIP > max(1, Z)} on each product's own root) and denser than the cubic's critical volume
     * ({@link PhaseIdentification#denseLiquidLike}: a dense gas beside a liquid at 8 to 10 MPa has a parameter just above
     * one while less dense than {@code 3.9514 b}) and the split lies outside the declared critical band: then it is
     * {@link Classification#LIQUID_LIQUID} with both phases {@link PhaseKind#LIQUID}, the lighter first. Inside the band
     * the parameter is no label to trust (next to a critical point it runs to plus or minus infinity), so a split there
     * stays vapour-liquid.
     */
    private EquilibriumResult twoPhaseResult(double t, double p, PhaseCompetition competition, Workspace w) {
        PhaseState liquid = w.liquidState;
        PhaseState vapour = w.vaporState;
        boolean swapped = vapour.molarVolume() < liquid.molarVolume();
        boolean band = inBand(t, p, w) || w.tieLine < BAND_TIE_LINE;
        boolean liquidLiquid = !band && w.liquidProductDenseLiquidLike && w.vapourProductDenseLiquidLike;
        PhaseAmounts lighter = new PhaseAmounts(liquidLiquid ? PhaseKind.LIQUID : PhaseKind.VAPOR, null, t, p,
                expand(swapped ? w.liquidAmounts : w.vaporAmounts), (swapped ? liquid : vapour).copy());
        PhaseAmounts denser = new PhaseAmounts(PhaseKind.LIQUID, null, t, p,
                expand(swapped ? w.vaporAmounts : w.liquidAmounts), (swapped ? vapour : liquid).copy());
        return converged(competition, liquidLiquid ? Classification.LIQUID_LIQUID : Classification.VAPOR_LIQUID,
                List.of(lighter, denser), List.of(), diagnostics(w, w.feedResult, band), band ? bandReason(t, p, w, Double.NaN) : null, null);
    }

    private EquilibriumResult failed(double t, double p, PhaseCompetition competition, Workspace w) {
        double seed = w.feedResult == null ? Double.NaN : w.seedDistance;
        boolean band = inBand(t, p, w) || w.tieLine < BAND_TIE_LINE || seed < BAND_STATIONARY_DISTANCE;
        String detail = band ? EquilibriumResult.CRITICAL_BAND + " (" + bandReason(t, p, w, seed) + "): " + w.failure : w.failure;
        return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                detail, diagnostics(w, w.feedResult, band));
    }

    // The hydrocarbon core, at the workspace's branch pressures.

    /** Stability test and, for an unstable feed, the split. Returns SINGLE, SPLIT, MERGED or FAILED. */
    private int hydrocarbon(double t, Workspace w) {
        double total = 0.0;
        for (int i = 0; i < count; i++) total += w.feed[i];
        w.presentCount = 0;
        for (int i = 0; i < count; i++) {
            w.z[i] = w.feed[i] / total;
            if (w.feed[i] > 0.0) w.present[w.presentCount++] = i;
        }
        w.tieLine = Double.NaN;
        w.seedDistance = Double.NaN;
        w.residual = Double.NaN;
        w.productDistance = Double.NaN;
        TangentPlaneStability.Result feed = feedStability(stability, t, w);
        if (feed.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) {
            // A trial ran out of its budget without proving anything (P3 WP7d: next to a mixture critical point a trial can
            // crawl towards the trivial solution on a root whose own Hessian is indefinite): once more on the longer budget.
            feed = feedStability(patientStability, t, w);
        }
        w.feedResult = feed;
        switch (feed.verdict()) {
            case UNRESOLVED -> {
                w.failure = "the feed's stability test is unresolved (also with " + patientStability.settings().maximumIterations()
                        + " iterations per trial)";
                return FAILED;
            }
            case STABLE -> {
                w.singleRoot = feed.feedRoot();
                return SINGLE;
            }
            default -> {
                return twoPhase(t, feed, w);
            }
        }
    }

    /** The feed's stability test at the workspace's branch pressures on {@code test}, counted. */
    private TangentPlaneStability.Result feedStability(TangentPlaneStability test, double t, Workspace w) {
        TangentPlaneStability.Result feed = w.branched
                ? test.test(t, w.liquidPressure, w.vaporPressure, translations, w.feed, w.stability)
                : test.test(t, w.liquidPressure, w.feed, w.stability);
        w.kernelEvaluations += feed.kernelEvaluations();
        w.derivativeEvaluations += feed.derivativeEvaluations();
        return feed;
    }

    /**
     * The split of an unstable feed: a first attempt seeded from the stability test's stationary phase, and, when that
     * fails, one more from Wilson's K. With branch pressures a stationary phase on the feed's own branch (a second
     * vapour, or a second liquid) cannot seed the network's split of a liquid at {@code P} and a vapour at {@code pc},
     * so Wilson's K (Raoult with the vapour at {@code pc}, the network's own seed) is the first attempt there.
     */
    private int twoPhase(double t, TangentPlaneStability.Result feed, Workspace w) {
        boolean sameBranch = w.branched && feed.trialRoot() == feed.feedRoot();
        int outcome = attempt(t, feed, sameBranch, w);
        if (outcome == FAILED && !sameBranch) {
            String first = w.failure;
            double seed = w.seedDistance;
            outcome = attempt(t, feed, true, w);
            if (outcome == FAILED) w.failure = first + "; again from Wilson's K: " + w.failure;
            w.seedDistance = seed;
        }
        return outcome;
    }

    private int attempt(double t, TangentPlaneStability.Result feed, boolean wilson, Workspace w) {
        // The stability test's stationary point seeds one side of the split: the lighter one the vapour.
        double[] trial = feed.trialComposition();
        System.arraycopy(trial, 0, w.trial, 0, count);
        kernel.evaluate(t, w.pressureOf(feed.trialRoot()), trial, feed.trialRoot(), w.mixture, w.first);
        kernel.evaluate(t, w.pressureOf(feed.feedRoot()), w.feed, feed.feedRoot(), w.mixture, w.second);
        w.kernelEvaluations += 2;
        w.trialIsVapour = w.branched && feed.trialRoot() != feed.feedRoot() ? feed.trialRoot() == Root.VAPOR
                : w.first.compressibility() > w.second.compressibility();
        Arrays.fill(w.logK, 0.0);
        double seedDistance = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double ratio = DeterministicMath.log(trial[i]) - DeterministicMath.log(w.z[i]);
            w.logK[i] = w.trialIsVapour ? ratio : -ratio;
            seedDistance += w.logK[i] * w.logK[i];
        }
        w.seedDistance = seedDistance;
        if (wilson || !(seedDistance >= settings.trivialDistance())) {
            // Instability proven by the feed's own curvature with no distinct stationary point: Wilson's K instead.
            kernel.wilsonK(t, w.vaporPressure, w.k);
            for (int a = 0; a < w.presentCount; a++) w.logK[w.present[a]] = DeterministicMath.log(w.k[w.present[a]]);
        }

        // Successive substitution; Newton takes over when it is slow, collapses or loses the split.
        boolean newton = false;
        boolean fromSubstitution = true;
        boolean hasPrevious = false;
        int plainSteps = 0;
        w.lambda = 0.0;
        int steps = 0;
        while (true) {
            if (!split(w)) {
                newton = true;
                fromSubstitution = false;
                break;
            }
            phaseLogPhi(t, w.x, false, w.liquidLogPhi, w);
            phaseLogPhi(t, w.y, true, w.vaporLogPhi, w);
            double change = 0.0;
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                w.newLogK[i] = w.liquidLogPhi[i] - w.vaporLogPhi[i];
                w.delta[i] = w.newLogK[i] - w.logK[i];
                change = Math.max(change, Math.abs(w.delta[i]));
            }
            w.iterations++;
            steps++;
            w.lastChange = change;
            if (change < settings.lnKTolerance()) {
                for (int a = 0; a < w.presentCount; a++) w.logK[w.present[a]] = w.newLogK[w.present[a]];
                break;
            }
            if (!Double.isFinite(change)) {
                newton = true;
                fromSubstitution = false;
                break;
            }
            if (steps >= settings.maximumIterations()) {
                w.failure = "no convergence in " + steps + " substitution iterations (max |d ln K| " + change + ")";
                return FAILED;
            }
            boolean extrapolated = false;
            plainSteps++;
            if (hasPrevious && plainSteps >= settings.accelerationCycle()) {
                double numerator = 0.0;
                double denominator = 0.0;
                for (int a = 0; a < w.presentCount; a++) {
                    int i = w.present[a];
                    numerator += w.delta[i] * w.delta[i];
                    denominator += w.previousDelta[i] * w.delta[i];
                }
                double lambda = numerator / denominator;
                w.lambda = lambda;
                if (lambda > 0.0 && lambda < 1.0) {
                    double factor = lambda / (1.0 - lambda);
                    double largest = change * factor;
                    double scale = largest > MAXIMUM_EXTRAPOLATION ? MAXIMUM_EXTRAPOLATION / largest : 1.0;
                    for (int a = 0; a < w.presentCount; a++) {
                        int i = w.present[a];
                        w.logK[i] = w.newLogK[i] + w.delta[i] * factor * scale;
                    }
                    extrapolated = true;
                    w.extrapolations++;
                }
                plainSteps = 0;
            }
            if (extrapolated) {
                hasPrevious = false;
            } else {
                for (int a = 0; a < w.presentCount; a++) w.logK[w.present[a]] = w.newLogK[w.present[a]];
                double[] swap = w.previousDelta;
                w.previousDelta = w.delta;
                w.delta = swap;
                hasPrevious = true;
            }
            double distance = 0.0;
            for (int a = 0; a < w.presentCount; a++) distance += w.logK[w.present[a]] * w.logK[w.present[a]];
            if (distance < settings.trivialDistance()) {
                // Collapsing onto the feed: start the finish from the stability test's own phase instead.
                newton = true;
                fromSubstitution = false;
                break;
            }
            if (steps >= settings.substitutionLimit()
                    || steps >= SLOW_SUBSTITUTION_STEPS && w.lambda > SLOW_EIGENVALUE) {
                newton = true;
                break;
            }
        }

        if (newton) {
            if (!newtonFinish(t, feed, fromSubstitution, w)) return FAILED;
        } else {
            if (!twoPhaseSplit(w)) {
                // A converged ratio set with no admissible split: the finish decides from the stability test's phase.
                if (!newtonFinish(t, feed, false, w)) return FAILED;
            } else {
                double tie = 0.0;
                Arrays.fill(w.vaporAmounts, 0.0);
                Arrays.fill(w.liquidAmounts, 0.0);
                for (int a = 0; a < w.presentCount; a++) {
                    int i = w.present[a];
                    tie += w.z[i] * w.logK[i] * w.logK[i];
                    // The smaller share by its own formula, the larger by difference: conserved to rounding, and a
                    // component almost entirely in one phase keeps its trace in the other to full relative precision.
                    double denominator = splitDenominator(w, i);
                    double vapourShare = w.vapourFraction * w.k[i] / denominator;
                    double liquidShare = w.liquidFraction / denominator;
                    if (vapourShare <= liquidShare) {
                        w.vaporAmounts[i] = Math.min(w.feed[i], w.feed[i] * vapourShare);
                        w.liquidAmounts[i] = w.feed[i] - w.vaporAmounts[i];
                    } else {
                        w.liquidAmounts[i] = Math.min(w.feed[i], w.feed[i] * liquidShare);
                        w.vaporAmounts[i] = w.feed[i] - w.liquidAmounts[i];
                    }
                }
                w.tieLine = tie;
            }
        }
        return finishSplit(t, w);
    }

    /** States of both products, merging, the product stability re-check and the fugacity residual. */
    private int finishSplit(double t, Workspace w) {
        PhaseState liquid;
        PhaseState vapour;
        if (w.branched) {
            // The network's slots: the vapour branch must be vapour-like (checked below), so no liquid-liquid split here.
            w.liquidProductDenseLiquidLike = false;
            w.vapourProductDenseLiquidLike = false;
            liquid = evaluator.evaluate(t, w.liquidPressure, w.liquidAmounts, Root.LIQUID, w.phases, w.liquidState);
            vapour = evaluator.evaluate(t, w.vaporPressure, w.vaporAmounts, Root.VAPOR, w.phases, w.vaporState);
        } else {
            // Each product's WP7c label from the root lowerGibbsRoot chose (no further kernel call): both liquid-like and
            // denser than the cubic's critical volume is a liquid-liquid split (P3 WP7d; the volume guard P3 WP11).
            Root liquidRoot = lowerGibbsRoot(t, w.liquidAmounts, Root.LIQUID, w.liquidLogPhi, w);
            w.liquidProductDenseLiquidLike = PhaseIdentification.denseLiquidLike(w.chosen, t, w.liquidPressure);
            liquid = evaluator.evaluate(t, w.liquidPressure, w.liquidAmounts, liquidRoot, w.phases, w.liquidState);
            Root vapourRoot = lowerGibbsRoot(t, w.vaporAmounts, Root.VAPOR, w.vaporLogPhi, w);
            w.vapourProductDenseLiquidLike = PhaseIdentification.denseLiquidLike(w.chosen, t, w.liquidPressure);
            vapour = evaluator.evaluate(t, w.vaporPressure, w.vaporAmounts, vapourRoot, w.phases, w.vaporState);
        }
        w.kernelEvaluations += 2;
        double gap = Math.abs(vapour.molarVolume() - liquid.molarVolume()) / (0.5 * (vapour.molarVolume() + liquid.molarVolume()));
        if (w.tieLine < settings.mergeTieLine() && gap < settings.mergeVolumeGap()) return MERGED;
        if (w.branched && vapour.physicalRootCount() == 1) {
            kernel.evaluate(t, w.vaporPressure, w.vaporAmounts, Root.VAPOR, w.mixture, w.first);
            w.kernelEvaluations++;
            if (!PhaseIdentification.vapourLike(w.first, t, w.vaporPressure)) {
                w.failure = "the vapour-branch phase is liquid-like at the hydrocarbon partial pressure";
                return FAILED;
            }
        }
        // Stability of both products: a third phase or another split is not an answer.
        TangentPlaneStability.Result liquidCheck = w.branched
                ? stability.test(t, w.liquidPressure, w.vaporPressure, translations, w.liquidAmounts, w.stability)
                : stability.test(t, w.liquidPressure, w.liquidAmounts, w.stability);
        w.kernelEvaluations += liquidCheck.kernelEvaluations();
        w.derivativeEvaluations += liquidCheck.derivativeEvaluations();
        TangentPlaneStability.Result vapourCheck = w.branched
                ? stability.test(t, w.liquidPressure, w.vaporPressure, translations, w.vaporAmounts, w.stability)
                : stability.test(t, w.liquidPressure, w.vaporAmounts, w.stability);
        w.kernelEvaluations += vapourCheck.kernelEvaluations();
        w.derivativeEvaluations += vapourCheck.derivativeEvaluations();
        w.productDistance = Math.min(liquidCheck.minimumTangentPlaneDistance(), vapourCheck.minimumTangentPlaneDistance());
        if (liquidCheck.verdict() != TangentPlaneStability.Verdict.STABLE
                || vapourCheck.verdict() != TangentPlaneStability.Verdict.STABLE) {
            // A product proved unstable is the typed indication of a third phase (P3 WP11): the split's common tangent
            // plane is crossed, so it is not the global minimum; a consumer holds on it with that reason. A product the
            // re-check left unresolved is the same failure short of the proof, typed as such.
            boolean unstable = liquidCheck.verdict() == TangentPlaneStability.Verdict.UNSTABLE
                    || vapourCheck.verdict() == TangentPlaneStability.Verdict.UNSTABLE;
            w.failure = (unstable ? EquilibriumResult.UNSTABLE_PRODUCT : EquilibriumResult.UNRESOLVED_PRODUCT) + ": a product phase is not stable (liquid "
                    + liquidCheck.verdict() + ", vapour " + vapourCheck.verdict()
                    + "): a third phase or another split; three-phase equilibrium is not implemented";
            return FAILED;
        }
        double logPressureRatio = w.branched ? DeterministicMath.log(w.liquidPressure / w.vaporPressure) : 0.0;
        double residual = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double xi = liquid.moleFraction(i);
            double yi = vapour.moleFraction(i);
            if (!(xi > 0.0) || !(yi > 0.0)) continue;
            double difference = DeterministicMath.log(xi) + liquid.logFugacityCoefficient(i) - DeterministicMath.log(yi) - vapour.logFugacityCoefficient(i);
            if (w.branched) difference += logPressureRatio;
            residual = Math.max(residual, Math.abs(difference));
        }
        w.residual = residual;
        return SPLIT;
    }

    // The second-order finish.

    /**
     * Newton on the vapour mole numbers per mole of feed, minimising the Gibbs energy. Leaves the amounts, the tie line
     * and the ratios in the workspace; false (with {@code failure}) when it cannot start or does not converge.
     */
    private boolean newtonFinish(double t, TangentPlaneStability.Result feed, boolean fromSubstitution, Workspace w) {
        // The mole numbers go to the kernel over the whole basis: a component absent from this feed must be zero, not the
        // amount an earlier request on this workspace left there (P3 WP7d: a reused workspace failed or changed a split).
        Arrays.fill(w.vaporMoles, 0.0);
        Arrays.fill(w.liquidMoles, 0.0);
        Arrays.fill(w.trialVapor, 0.0);
        Arrays.fill(w.trialLiquid, 0.0);
        // d_i = ln f_i of the feed on its stability root, over the liquid pressure.
        kernel.evaluate(t, w.pressureOf(feed.feedRoot()), w.z, feed.feedRoot(), w.mixture, w.first);
        w.kernelEvaluations++;
        double[] feedLogPhi = w.first.logFugacityCoefficientsView();
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.reference[i] = DeterministicMath.log(w.z[i]) + feedLogPhi[i] + shift(w, feed.feedRoot() == Root.VAPOR, i);
        }
        double gibbs = Double.NaN;
        if (fromSubstitution) {
            if (twoPhaseSplit(w)) {
                for (int a = 0; a < w.presentCount; a++) {
                    int i = w.present[a];
                    double denominator = splitDenominator(w, i);
                    w.vaporMoles[i] = w.z[i] * (w.vapourFraction * w.k[i] / denominator);
                    w.liquidMoles[i] = w.z[i] * (w.liquidFraction / denominator);
                }
                gibbs = gibbs(t, w.vaporMoles, w.liquidMoles, w);
                if (!(gibbs < -GIBBS_ROUNDOFF * w.gibbsScale)) gibbs = Double.NaN;
            }
        }
        if (Double.isNaN(gibbs)) gibbs = startFromStationaryPhase(t, feed, w);
        if (Double.isNaN(gibbs)) {
            w.failure = "no two-phase start below the feed's Gibbs energy for the Newton finish (stationary tm "
                    + feed.minimumTangentPlaneDistance() + ")";
            return false;
        }
        int m = w.presentCount;
        for (int iteration = 0; ; iteration++) {
            // Both phases with their composition derivatives at the current point.
            double vapourTotal = 0.0;
            double liquidTotal = 0.0;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                vapourTotal += w.vaporMoles[i];
                liquidTotal += w.liquidMoles[i];
            }
            PengRobinsonKernel.Evaluation vapour;
            PengRobinsonKernel.Evaluation liquid;
            try {
                vapour = differentiatePhase(t, w.vaporMoles, true, w.vaporDerivatives, w);
                liquid = differentiatePhase(t, w.liquidMoles, false, w.liquidDerivatives, w);
            } catch (IllegalStateException coalescing) {
                w.failure = "the Newton finish met a phase at root coalescence: " + coalescing.getMessage();
                return false;
            }
            double largest = 0.0;
            double tie = 0.0;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double logY = DeterministicMath.log(w.vaporMoles[i] / vapourTotal);
                double logX = DeterministicMath.log(w.liquidMoles[i] / liquidTotal);
                w.gradient[i] = logY + vapour.logFugacityCoefficient(i) + shift(w, true, i)
                        - logX - liquid.logFugacityCoefficient(i);
                largest = Math.max(largest, Math.abs(w.gradient[i]));
                tie += w.z[i] * (logY - logX) * (logY - logX);
            }
            w.lastChange = largest;
            w.tieLine = tie;
            if (largest < FUGACITY_TOLERANCE) break;
            if (!Double.isFinite(largest) || iteration >= settings.maximumNewtonIterations()) {
                w.failure = "no convergence in " + w.newtonIterations + " Newton steps (max |ln f_V - ln f_L| " + largest + ")";
                return false;
            }
            // Scaled Hessian: s_i H_ij s_j with s_i = sqrt(v_i l_i / z_i), so the ideal part is the identity.
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                w.scale[i] = Math.sqrt(w.vaporMoles[i] * w.liquidMoles[i] / (w.vaporMoles[i] + w.liquidMoles[i]));
            }
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double[] vapourRow = w.vaporDerivatives.dLogPhiDnRowView(i);
                double[] liquidRow = w.liquidDerivatives.dLogPhiDnRowView(i);
                for (int b = 0; b <= a; b++) {
                    int j = w.present[b];
                    double coupling = (0.5 * (vapourRow[j] + w.vaporDerivatives.dLogPhiDnRowView(j)[i]) - 1.0) / vapourTotal
                            + (0.5 * (liquidRow[j] + w.liquidDerivatives.dLogPhiDnRowView(j)[i]) - 1.0) / liquidTotal;
                    double entry = coupling * w.scale[i] * w.scale[j];
                    if (a == b) entry += 1.0;
                    w.hessian[a * m + b] = entry;
                }
            }
            if (!shiftedSolve(w, m)) {
                w.failure = "the Newton finish found no positive definite shift of the Gibbs Hessian";
                return false;
            }
            // Step in mole numbers, cut so that no amount shrinks below a tenth of itself.
            double slope = 0.0;
            double length = 1.0;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double dv = w.scale[i] * w.step[a];
                w.direction[i] = dv;
                slope += w.gradient[i] * dv;
                if (dv < 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * w.vaporMoles[i] / -dv);
                else if (dv > 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * w.liquidMoles[i] / dv);
            }
            boolean accepted = false;
            double allowance = GIBBS_ROUNDOFF * w.gibbsScale;
            for (int halving = 0; halving <= NEWTON_HALVINGS; halving++, length *= 0.5) {
                for (int a = 0; a < m; a++) {
                    int i = w.present[a];
                    w.trialVapor[i] = w.vaporMoles[i] + length * w.direction[i];
                    w.trialLiquid[i] = w.liquidMoles[i] - length * w.direction[i];
                }
                double next;
                try {
                    next = gibbs(t, w.trialVapor, w.trialLiquid, w);
                } catch (IllegalArgumentException refused) {
                    continue;
                }
                if (next <= gibbs + ARMIJO * length * slope + allowance) {
                    gibbs = next;
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                w.failure = "the Newton finish found no Gibbs-energy decrease (max |ln f_V - ln f_L| " + largest + ")";
                return false;
            }
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                w.vaporMoles[i] = w.trialVapor[i];
                w.liquidMoles[i] = w.trialLiquid[i];
            }
            w.newtonIterations++;
        }
        // The amounts: each component's smaller share by its own mole number, the larger by difference.
        Arrays.fill(w.vaporAmounts, 0.0);
        Arrays.fill(w.liquidAmounts, 0.0);
        for (int a = 0; a < m; a++) {
            int i = w.present[a];
            double v = w.vaporMoles[i];
            double l = w.liquidMoles[i];
            if (v <= l) {
                w.vaporAmounts[i] = Math.min(w.feed[i], w.feed[i] * (v / (v + l)));
                w.liquidAmounts[i] = w.feed[i] - w.vaporAmounts[i];
            } else {
                w.liquidAmounts[i] = Math.min(w.feed[i], w.feed[i] * (l / (v + l)));
                w.vaporAmounts[i] = w.feed[i] - w.liquidAmounts[i];
            }
        }
        return true;
    }

    /**
     * A split below the feed's Gibbs energy from the stability test's stationary composition {@code w}: that phase in the
     * amount {@code eps w}, the rest {@code z - eps w}, with {@code eps} halved from half the largest admissible value
     * until {@code Delta G < 0} (to first order {@code Delta G = eps D(w) < 0}). NaN when the test found no negative
     * stationary point or no amount works.
     */
    private double startFromStationaryPhase(double t, TangentPlaneStability.Result feed, Workspace w) {
        if (!(feed.minimumTangentPlaneDistance() < 0.0)) return Double.NaN;
        // With branch pressures a stationary phase on the feed's own branch cannot be one side of the network's split.
        if (w.branched && feed.trialRoot() == feed.feedRoot()) return Double.NaN;
        double largest = Double.POSITIVE_INFINITY;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            if (w.trial[i] > 0.0) largest = Math.min(largest, w.z[i] / w.trial[i]);
        }
        if (!(largest > 0.0) || !Double.isFinite(largest)) return Double.NaN;
        double amount = 0.5 * Math.min(largest, 1.0);
        for (int halving = 0; halving < START_HALVINGS; halving++, amount *= 0.5) {
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                double part = amount * w.trial[i];
                double rest = w.z[i] - part;
                if (w.trialIsVapour) {
                    w.vaporMoles[i] = part;
                    w.liquidMoles[i] = rest;
                } else {
                    w.liquidMoles[i] = part;
                    w.vaporMoles[i] = rest;
                }
            }
            double gibbs;
            try {
                gibbs = gibbs(t, w.vaporMoles, w.liquidMoles, w);
            } catch (IllegalArgumentException refused) {
                continue;
            }
            if (gibbs < -GIBBS_ROUNDOFF * w.gibbsScale) return gibbs;
        }
        return Double.NaN;
    }

    /**
     * {@code Delta G/RT} per mole of feed of the split {@code (v, l)} against the feed:
     * {@code sum_i v_i (ln y_i + ln phi_i^V + s_i - d_i) + l_i (ln x_i + ln phi_i^L - d_i)}; leaves the magnitude of its
     * summands in {@code gibbsScale} for the roundoff allowance.
     */
    private double gibbs(double t, double[] v, double[] l, Workspace w) {
        double vapourTotal = 0.0;
        double liquidTotal = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            vapourTotal += v[i];
            liquidTotal += l[i];
        }
        Arrays.fill(w.x, 0.0);
        Arrays.fill(w.y, 0.0);
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.y[i] = v[i] / vapourTotal;
            w.x[i] = l[i] / liquidTotal;
        }
        phaseLogPhi(t, w.y, true, w.vaporLogPhi, w);
        phaseLogPhi(t, w.x, false, w.liquidLogPhi, w);
        double sum = 0.0;
        double magnitude = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double vapourTerm = DeterministicMath.log(w.y[i]) + w.vaporLogPhi[i];
            double liquidTerm = DeterministicMath.log(w.x[i]) + w.liquidLogPhi[i];
            sum += v[i] * (vapourTerm - w.reference[i]) + l[i] * (liquidTerm - w.reference[i]);
            magnitude += v[i] * (Math.abs(vapourTerm) + Math.abs(w.reference[i]))
                    + l[i] * (Math.abs(liquidTerm) + Math.abs(w.reference[i]));
        }
        w.gibbsScale = magnitude;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Non-finite Gibbs energy of a trial split");
        return sum;
    }

    /**
     * Solves {@code (H + mu I) s = -s g} in place into {@code step} for the scaled Hessian in {@code hessian}, with
     * {@code mu} zero or the first of {@code 1e-8, 1e-7, ..., 1e4} that makes it positive definite.
     */
    private static boolean shiftedSolve(Workspace w, int m) {
        double[] h = w.hessian;
        for (int attempt = 0; attempt < 14; attempt++) {
            double mu = attempt == 0 ? 0.0 : DeterministicMath.pow(10.0, attempt - 9);
            if (factorShifted(h, m, mu, w.factor)) {
                for (int a = 0; a < m; a++) {
                    int i = w.present[a];
                    w.step[a] = -w.scale[i] * w.gradient[i];
                }
                TangentPlaneStability.solveCholesky(w.factor, m, w.step);
                return true;
            }
        }
        return false;
    }

    // Phase evaluations at the branch pressures.

    /** {@code ln phi_i} of a composition on its branch (the vapour's shift added with branch pressures) into {@code out}. */
    private Root phaseLogPhi(double t, double[] composition, boolean vapourSide, double[] out, Workspace w) {
        if (!w.branched) {
            return lowerGibbsRoot(t, composition, vapourSide ? Root.VAPOR : Root.LIQUID, out, w);
        }
        Root root = vapourSide ? Root.VAPOR : Root.LIQUID;
        kernel.evaluate(t, w.pressureOf(root), composition, root, w.mixture, w.first);
        w.kernelEvaluations++;
        double[] logPhi = w.first.logFugacityCoefficientsView();
        for (int i = 0; i < count; i++) out[i] = vapourSide ? logPhi[i] + w.vaporShifts[i] : logPhi[i];
        return root;
    }

    /** Derivatives of one side's phase on its root (the lower-Gibbs root at one pressure, the fixed branch otherwise). */
    private PengRobinsonKernel.Evaluation differentiatePhase(double t, double[] amounts, boolean vapourSide,
                                                             PengRobinsonKernel.Derivatives out, Workspace w) {
        Root preferred = vapourSide ? Root.VAPOR : Root.LIQUID;
        kernel.evaluateDerivatives(t, w.pressureOf(preferred), amounts, preferred, w.mixture, out);
        w.kernelEvaluations++;
        w.derivativeEvaluations++;
        if (!w.branched && out.evaluation().physicalRootCount() > 1) {
            Root other = vapourSide ? Root.LIQUID : Root.VAPOR;
            kernel.evaluate(t, w.liquidPressure, amounts, other, w.mixture, w.second);
            w.kernelEvaluations++;
            if (residualGibbs(w.second, amounts) < residualGibbs(out.evaluation(), amounts)) {
                kernel.evaluateDerivatives(t, w.liquidPressure, amounts, other, w.mixture, out);
                w.kernelEvaluations++;
                w.derivativeEvaluations++;
            }
        }
        return out.evaluation();
    }

    private double shift(Workspace w, boolean vapourSide, int i) {
        return w.branched && vapourSide ? w.vaporShifts[i] : 0.0;
    }

    /**
     * Rachford-Rice for the current {@code ln K}, solved in the minor phase's fraction: the vapour fraction {@code beta}
     * of {@code sum z_i (K_i - 1)/(1 + beta (K_i - 1)) = 0} when the root lies at or below one half, otherwise the liquid
     * fraction {@code delta = 1 - beta} of the same equation ({@code 1 + beta (K_i - 1) = K_i - delta (K_i - 1)}), so the
     * minor fraction carries full relative precision (a liquid of 1e-14 of the feed is {@code delta = 1e-14}, a split
     * while {@code delta > 0}). The first evaluation, at one half, tells which phase is minor; safeguarded Newton with
     * bisection between that fraction's pole and one half, stopped when a step moves the fraction by at most 1e-15 of
     * itself. Fills {@code k}, the normalised {@code x} and {@code y}, and both fractions; false when the K-values
     * straddle no split.
     */
    private boolean split(Workspace w) {
        double smallest = Double.POSITIVE_INFINITY;
        double largest = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.k[i] = DeterministicMath.exp(Math.clamp(w.logK[i], -LOG_K_BOUND, LOG_K_BOUND));
            smallest = Math.min(smallest, w.k[i]);
            largest = Math.max(largest, w.k[i]);
        }
        if (!(smallest < 1.0 && largest > 1.0)) return false;
        boolean liquidMinor = false;
        double lower = 1.0 / (1.0 - largest);
        double upper = 1.0 / (1.0 - smallest);
        double fraction = 0.5;
        for (int step = 0; step < MAXIMUM_RACHFORD_RICE_STEPS; step++) {
            double f = 0.0;
            double slope = 0.0;
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                double d = w.k[i] - 1.0;
                double denominator = liquidMinor ? w.k[i] - fraction * d : 1.0 + fraction * d;
                f += w.z[i] * d / denominator;
                slope -= w.z[i] * d * d / (denominator * denominator);
            }
            w.rachfordRiceSteps++;
            if (f == 0.0) break;
            if (liquidMinor) {
                // The sum increases in delta; its negative decreases like the vapour form's, with the slope above.
                f = -f;
            } else if (step == 0 && f > 0.0) {
                // The root lies above one half: the liquid is the minor phase. At one half the delta form's negated sum
                // is -f with this slope; its window runs from the pole of the smallest K.
                liquidMinor = true;
                f = -f;
                lower = smallest / (smallest - 1.0);
                upper = largest / (largest - 1.0);
            }
            // f decreases in the fraction: a positive value puts the root above it.
            if (f > 0.0) lower = fraction; else upper = fraction;
            double next = fraction - f / slope;
            if (!(next > lower && next < upper)) next = 0.5 * (lower + upper);
            if (next == fraction || Math.abs(next - fraction) <= 1.0e-15 * Math.abs(fraction)) {
                fraction = next;
                break;
            }
            fraction = next;
        }
        w.liquidMinor = liquidMinor;
        w.vapourFraction = liquidMinor ? 1.0 - fraction : fraction;
        w.liquidFraction = liquidMinor ? fraction : 1.0 - fraction;
        double sumX = 0.0;
        double sumY = 0.0;
        Arrays.fill(w.x, 0.0);
        Arrays.fill(w.y, 0.0);
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double xi = w.z[i] / splitDenominator(w, i);
            w.x[i] = xi;
            w.y[i] = w.k[i] * xi;
            sumX += w.x[i];
            sumY += w.y[i];
        }
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.x[i] /= sumX;
            w.y[i] /= sumY;
        }
        return true;
    }

    /** {@link #split} with both fractions positive: the K-values divide the feed into two populated phases. */
    private boolean twoPhaseSplit(Workspace w) {
        return split(w) && w.vapourFraction > 0.0 && w.liquidFraction > 0.0;
    }

    /**
     * Component {@code i}'s {@code 1 + beta (K_i - 1)} of the last {@link #split}, written in the minor phase's fraction
     * ({@code K_i - delta (K_i - 1)} when the liquid is minor), so that it keeps that fraction's precision: component
     * {@code i}'s liquid share is {@code delta} over it and its vapour share {@code beta K_i} over it.
     */
    private static double splitDenominator(Workspace w, int i) {
        double d = w.k[i] - 1.0;
        return w.liquidMinor ? w.k[i] - w.liquidFraction * d : 1.0 + w.vapourFraction * d;
    }

    /** Evaluates {@code composition} on its lower-Gibbs root (the preferred one at a tie), copying its {@code ln phi}. */
    private Root lowerGibbsRoot(double t, double[] composition, Root preferred, double[] logPhi, Workspace w) {
        double p = w.liquidPressure;
        kernel.evaluate(t, p, composition, preferred, w.mixture, w.first);
        w.kernelEvaluations++;
        PengRobinsonKernel.Evaluation chosen = w.first;
        Root root = preferred;
        if (w.first.physicalRootCount() > 1) {
            Root other = preferred == Root.LIQUID ? Root.VAPOR : Root.LIQUID;
            kernel.evaluate(t, p, composition, other, w.mixture, w.second);
            w.kernelEvaluations++;
            if (residualGibbs(w.second, composition) < residualGibbs(w.first, composition)) {
                chosen = w.second;
                root = other;
            }
        }
        System.arraycopy(chosen.logFugacityCoefficientsView(), 0, logPhi, 0, count);
        w.chosen = chosen;
        return root;
    }

    /** {@code sum n_i ln phi_i}: at one composition the root with the smaller value has the lower Gibbs energy. */
    private double residualGibbs(PengRobinsonKernel.Evaluation evaluation, double[] composition) {
        double[] logPhi = evaluation.logFugacityCoefficientsView();
        double sum = 0.0;
        for (int i = 0; i < count; i++) if (composition[i] > 0.0) sum += composition[i] * logPhi[i];
        return sum;
    }

    // The critical band.

    /**
     * The pure-fluid band: one component at least 0.95 of the amount, at reduced temperature and pressure (its own
     * critical constants) inside one of the declared boxes, closed intervals ({@link #pureBandBox}).
     */
    private boolean inBand(double t, double p, Workspace w) {
        int dominant = -1;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            if (w.z[i] >= 0.95) dominant = i;
        }
        if (dominant < 0) return false;
        var constants = evaluator.component(dominant);
        return pureBandBox(t / constants.criticalTemperatureKelvin(), p / constants.criticalPressurePascal()) != 0;
    }

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

    private String bandReason(double t, double p, Workspace w, double stationary) {
        if (inBand(t, p, w)) return "critical band: pure fluid near its critical point";
        if (w.tieLine < BAND_TIE_LINE) return "critical band: short tie line (sum z ln K^2 = " + w.tieLine + ")";
        return "critical band: incipient phase next to the feed (sum (ln w - ln z)^2 = " + stationary + ")";
    }

    // Free water.

    /**
     * The free-water rule at {@code (t, p)}: the water's condensed phase is its ice where {@code frozen} (below the melting
     * line, WP4), its liquid otherwise, with the fugacity {@code saturation} either way (the ice's or the liquid's
     * saturation pressure, {@link #condensedPressure}); the rule is the same for both.
     */
    private EquilibriumResult solveWet(double t, double p, double hydrocarbons, double water, boolean frozen,
                                       PhaseCompetition competition, Workspace w) {
        double saturation = frozen ? freeWater.ice().fugacity(t, p) : freeWater.saturationPressure(t);
        if (!(hydrocarbons > 0.0)) {
            // Pure water: all condensed at or above the condensed water's vapour pressure, all steam below.
            if (p >= saturation) {
                PhaseAmounts condensed = condensedPhase(t, p, saturation, water, frozen, w);
                var split = freeWaterSplit(Classification.NONE, saturation, p, 0.0, water, 0.0, frozen);
                return converged(competition, Classification.FREE_WATER, List.of(condensed), List.of(),
                        diagnostics(w, null, false), null, split);
            }
            PhaseAmounts gas = gasPhase(t, p, p, 0.0, water, null, w);
            var split = freeWaterSplit(Classification.NONE, saturation, p, p, 0.0, water, frozen);
            return converged(competition, Classification.FREE_WATER, List.of(gas), List.of(), diagnostics(w, null, false), null, split);
        }
        double saturatedPc = Double.NaN;
        double saturatedGas = 0.0;
        if (p > saturation + SATURATION_MARGIN) {
            double pc = p - saturation;
            setBranches(t, p, pc, w);
            int outcome = hydrocarbon(t, w);
            if (outcome == FAILED) return failed(t, p, competition, w);
            double gasVolume = hydrocarbonVapour(t, outcome, w);
            // The steam the gas holds at the condensed water's vapour pressure: n_w = p_sat V / (R T + p_sat B).
            double required = SteamVirial.moles(saturation, t, gasVolume);
            if (required <= water) {
                double partial = gasVolume > 0.0 ? SteamVirial.partialPressure(required, t, gasVolume) : 0.0;
                return wetResult(t, p, pc, outcome, required, water - required, saturation, partial, frozen, competition, w);
            }
            saturatedPc = pc;
            saturatedGas = gasVolume;
        }
        // The gas is unsaturated: all water is steam, and pc solves pc + p_w(V_gas(pc)) = P, the virial steam's
        // p_w = n_w R T / (V_gas - n_w B). Its fixed point first (P3 WP4 runtime diagnosis, section 2.6; WP7b): started
        // from the saturated split's gas volume scaled to the root as an ideal gas, then pc = P - p_w(V_gas(pc)); a point
        // is accepted only on the bisection's own stop test (tighter than its acceptance), otherwise the bisection below
        // decides. For a trace of water the fixed point is the root to rounding after one split, where the bisection needs
        // about 27.
        if (saturatedGas > 0.0) {
            double guess = p / (1.0 + water * R * t / (saturatedGas * saturatedPc));
            for (int step = 0; step < PARTIAL_PRESSURE_FIXED_POINT_STEPS && guess > PARTIAL_PRESSURE_FLOOR; step++) {
                setBranches(t, p, guess, w);
                int outcome = hydrocarbon(t, w);
                if (outcome == FAILED) break;
                double gasVolume = hydrocarbonVapour(t, outcome, w);
                if (!(gasVolume > 0.0)) break;
                double partial = SteamVirial.partialPressure(water, t, gasVolume);
                if (Math.abs(guess + partial - p) < PARTIAL_PRESSURE_TOLERANCE * p) {
                    return wetResult(t, p, guess, outcome, water, 0.0, saturation, partial, frozen, competition, w);
                }
                guess = p - partial;
            }
        }
        // All water is steam; the hydrocarbon partial pressure closes the gas volume (the network's bisection).
        double low = PARTIAL_PRESSURE_FLOOR;
        double high = p;
        double pc = high;
        int outcome = FAILED;
        double gasVolume = 0.0;
        for (int iteration = 0; iteration < PARTIAL_PRESSURE_BISECTIONS; iteration++) {
            pc = (low + high) * 0.5;
            setBranches(t, p, pc, w);
            outcome = hydrocarbon(t, w);
            if (outcome == FAILED) return failed(t, p, competition, w);
            gasVolume = hydrocarbonVapour(t, outcome, w);
            // A gas volume the steam's co-volume fills (V <= n_w B, a positive B) is too small: infinite, pc too high.
            double residual = gasVolume > 0.0 ? pc + SteamVirial.partialPressure(water, t, gasVolume) - p : Double.POSITIVE_INFINITY;
            if (Math.abs(residual) < PARTIAL_PRESSURE_TOLERANCE * p) break;
            if (residual > 0.0) high = pc; else low = pc;
        }
        double partial = gasVolume > 0.0 ? SteamVirial.partialPressure(water, t, gasVolume) : p;
        if (Math.abs(pc + partial - p) > PARTIAL_PRESSURE_ACCEPTANCE * p) {
            w.failure = "the hydrocarbon partial pressure closing the gas volume was not found (pc " + pc + " Pa, p_w "
                    + partial + " Pa at " + p + " Pa)";
            return failed(t, p, competition, w);
        }
        return wetResult(t, p, pc, outcome, water, 0.0, saturation, partial, frozen, competition, w);
    }

    /**
     * The hydrocarbon vapour of a wet hydrocarbon outcome at the branch pressures: its amounts in {@code vaporAmounts}
     * (liquid in {@code liquidAmounts}), its state in {@code vaporState}; returns its volume {@code n_V v_V(pc)}.
     */
    private double hydrocarbonVapour(double t, int outcome, Workspace w) {
        if (outcome == SINGLE || outcome == MERGED) {
            Root root = outcome == SINGLE ? w.singleRoot : w.feedResult.feedRoot();
            Arrays.fill(w.vaporAmounts, 0.0);
            Arrays.fill(w.liquidAmounts, 0.0);
            if (root == Root.VAPOR) {
                System.arraycopy(w.feed, 0, w.vaporAmounts, 0, count);
            } else {
                System.arraycopy(w.feed, 0, w.liquidAmounts, 0, count);
                return 0.0;
            }
            evaluator.evaluate(t, w.vaporPressure, w.vaporAmounts, Root.VAPOR, w.phases, w.vaporState);
            w.kernelEvaluations++;
        }
        double vapour = 0.0;
        for (int i = 0; i < count; i++) vapour += w.vaporAmounts[i];
        return vapour > 0.0 ? vapour * w.vaporState.molarVolume() : 0.0;
    }

    private EquilibriumResult wetResult(double t, double p, double pc, int outcome, double steam, double liquidWater,
                                        double saturation, double partial, boolean frozen, PhaseCompetition competition,
                                        Workspace w) {
        double vapour = 0.0;
        double liquid = 0.0;
        for (int i = 0; i < count; i++) {
            vapour += w.vaporAmounts[i];
            liquid += w.liquidAmounts[i];
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (vapour > 0.0 || steam > 0.0) {
            phases.add(gasPhase(t, p, pc, vapour, steam, vapour > 0.0 ? w.vaporState : null, w));
        }
        if (liquid > 0.0) {
            PhaseState state = outcome == SPLIT ? w.liquidState
                    : evaluator.evaluate(t, p, w.liquidAmounts, Root.LIQUID, w.phases, w.liquidState);
            if (outcome != SPLIT) w.kernelEvaluations++;
            phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t, p, expand(w.liquidAmounts), state.copy()));
        }
        if (liquidWater > 0.0) phases.add(condensedPhase(t, p, saturation, liquidWater, frozen, w));
        Classification hydrocarbon = switch (outcome) {
            case SPLIT -> Classification.VAPOR_LIQUID;
            case MERGED -> Classification.SINGLE_FLUID;
            default -> vapour > 0.0 ? singleClassification(w.vaporState, t, pc, w) : singleClassification(w.liquidState, t, p, w);
        };
        boolean band = inBand(t, p, w) || outcome != SINGLE && w.tieLine < BAND_TIE_LINE
                || outcome == SINGLE && w.presentCount > 1
                && w.feedResult.nearestStationaryDistance() < BAND_STATIONARY_DISTANCE;
        String why = band ? bandReason(t, p, w, w.feedResult.nearestStationaryDistance())
                + (outcome == MERGED ? "; " + mergedDetail(w) : "") : null;
        var split = freeWaterSplit(hydrocarbon, saturation, pc, partial, liquidWater, steam, frozen);
        return converged(competition, Classification.FREE_WATER, phases, List.of(), diagnostics(w, w.feedResult, band), why, split);
    }

    /** The rule's split with {@code condensed} moles of condensed water: ice where {@code frozen}, liquid otherwise. */
    private static EquilibriumResult.FreeWater freeWaterSplit(Classification hydrocarbon, double saturation, double pc,
                                                            double partial, double condensed, double steam, boolean frozen) {
        return new EquilibriumResult.FreeWater(hydrocarbon, saturation, pc, partial, frozen ? 0.0 : condensed, steam,
                frozen ? condensed : 0.0);
    }

    /** The condensed free water: its ice where {@code frozen} ({@link #icePhase}), its liquid otherwise ({@link #freeWaterPhase}). */
    private PhaseAmounts condensedPhase(double t, double p, double saturation, double amount, boolean frozen, Workspace w) {
        return frozen ? icePhase(t, p, saturation, amount, w) : freeWaterPhase(t, p, saturation, amount, w);
    }

    /**
     * The gas: hydrocarbon vapour ({@code vapour} moles, {@code hydrocarbonState} at {@code pc}) and the second-virial
     * steam ({@link SteamVirial}) sharing its volume, as one state over the contract basis at {@code P}: volume
     * {@code n_V v_V(pc)} (or {@code n_w (R T/P + B)} for steam alone), the steam at {@code p_w = n_w R T/(V - n_w B)},
     * enthalpy, entropy and Gibbs energy the sums of both parts (the steam's at its partial pressure with its residual),
     * {@code ln phi_i} from each component's chemical potential against the ideal gas at {@code P}.
     */
    private PhaseAmounts gasPhase(double t, double p, double pc, double vapour, double steam, PhaseState hydrocarbonState,
                                  Workspace w) {
        double rt = R * t;
        double volume = vapour > 0.0 ? vapour * hydrocarbonState.molarVolume() : steam * SteamVirial.molarVolume(t, p);
        double partial = !(steam > 0.0) ? 0.0 : vapour > 0.0 ? SteamVirial.partialPressure(steam, t, volume) : p;
        if (!(volume > 0.0) || !Double.isFinite(partial)) {
            throw new IllegalArgumentException("No virial steam volume at " + t + " K and " + (vapour > 0.0 ? partial : p) + " Pa");
        }
        double total = vapour + steam;
        IdealGasFunction steamGas = freeWater.vapor();
        double enthalpy = 0.0;
        double entropy = 0.0;
        double gibbs = 0.0;
        if (vapour > 0.0) {
            enthalpy += vapour * hydrocarbonState.molarEnthalpy();
            entropy += vapour * hydrocarbonState.molarEntropy();
            gibbs += vapour * hydrocarbonState.molarGibbsEnergy();
        }
        double steamIdealEnthalpy = 0.0;
        double steamGibbs = 0.0;
        if (steam > 0.0) {
            steamIdealEnthalpy = steamGas.enthalpy(t);
            double steamEnthalpy = steamIdealEnthalpy + SteamVirial.residualEnthalpy(t, partial);
            double steamEntropy = steamGas.entropy(t, partial) + SteamVirial.residualEntropy(t, partial);
            steamGibbs = steamEnthalpy - t * steamEntropy;
            enthalpy += steam * steamEnthalpy;
            entropy += steam * steamEntropy;
            gibbs += steam * steamGibbs;
        }
        Arrays.fill(w.gasComposition, 0.0);
        Arrays.fill(w.gasLogPhi, 0.0);
        Arrays.fill(w.gasIdealGibbs, Double.NaN);
        double idealEnthalpy = 0.0;
        double idealEntropy = 0.0;
        for (int i = 0; i < count; i++) {
            double amount = vapour > 0.0 ? w.vaporAmounts[i] : 0.0;
            if (!(amount > 0.0)) continue;
            int slot = basisOfComponent[i];
            double y = amount / total;
            IdealGasFunction gas = evaluator.idealGas(i);
            double h = gas.enthalpy(t);
            double s = gas.entropy(t, p);
            w.gasComposition[slot] = y;
            w.gasIdealGibbs[slot] = h - t * s;
            idealEnthalpy += y * h;
            idealEntropy += y * (s - R * DeterministicMath.log(y));
            w.gasLogPhi[slot] = (hydrocarbonState.chemicalPotential(i) - w.gasIdealGibbs[slot]) / rt - DeterministicMath.log(y);
        }
        if (steam > 0.0) {
            double y = steam / total;
            double s = steamGas.entropy(t, p);
            w.gasComposition[waterIndex] = y;
            w.gasIdealGibbs[waterIndex] = steamIdealEnthalpy - t * s;
            idealEnthalpy += y * steamIdealEnthalpy;
            idealEntropy += y * (s - R * DeterministicMath.log(y));
            w.gasLogPhi[waterIndex] = (steamGibbs - w.gasIdealGibbs[waterIndex]) / rt - DeterministicMath.log(y);
        }
        int roots = vapour > 0.0 ? hydrocarbonState.physicalRootCount() : 1;
        w.gasState.set(PhaseKind.VAPOR, null, Root.VAPOR, roots, t, p, volume / total, idealEnthalpy,
                enthalpy / total - idealEnthalpy, idealEntropy, entropy / total - idealEntropy, gibbs / total,
                w.gasComposition, w.gasLogPhi, w.gasIdealGibbs);
        double[] amounts = new double[basis];
        if (vapour > 0.0) for (int i = 0; i < count; i++) amounts[basisOfComponent[i]] = w.vaporAmounts[i];
        amounts[waterIndex] = steam;
        return new PhaseAmounts(PhaseKind.VAPOR, null, t, p, amounts, w.gasState.copy());
    }

    /**
     * Free liquid water at (T, P) from the water model, with the chemical potential of the rule's steam at
     * {@code p_w = p_sat} (so {@code ln phi = ln(p_sat/P) + B p_sat/(R T)} and {@code mu = g_ig(T, p_sat) + B p_sat},
     * {@link SteamVirial}): the steam it coexists with has the same chemical potential exactly when {@code p_w = p_sat},
     * as the rule sets it.
     */
    private PhaseAmounts freeWaterPhase(double t, double p, double saturation, double amount, Workspace w) {
        freeWater.liquid(t, p, w.waterProperties);
        IdealGasFunction steamGas = freeWater.vapor();
        double idealEnthalpy = steamGas.enthalpy(t);
        double idealEntropy = steamGas.entropy(t, p);
        double idealGibbs = idealEnthalpy - t * idealEntropy;
        double logPhi = DeterministicMath.log(saturation / p) + SteamVirial.residualGibbs(t, saturation) / (R * t);
        double gibbs = idealGibbs + R * t * logPhi;
        double enthalpy = w.waterProperties[1];
        double entropy = (enthalpy - gibbs) / t;
        w.oneLogPhi[0] = logPhi;
        w.oneIdealGibbs[0] = idealGibbs;
        w.waterState.set(PhaseKind.FREE_WATER, null, Root.LIQUID, 1, t, p, w.waterProperties[0], idealEnthalpy,
                enthalpy - idealEnthalpy, idealEntropy, entropy - idealEntropy, gibbs, w.one, w.oneLogPhi, w.oneIdealGibbs);
        double[] amounts = new double[basis];
        amounts[waterIndex] = amount;
        return new PhaseAmounts(PhaseKind.FREE_WATER, null, t, p, amounts, w.waterState.copy());
    }

    /**
     * The free water's ice at (T, P) (WP4): a solid phase naming the ice record, its volume and enthalpy the record's
     * ({@link FreeWaterIce#properties}), with the chemical potential of the rule's steam at {@code p_w = f_s} as the
     * liquid's is at {@code p_sat} ({@code ln phi = ln(f_s/P) + B f_s/(R T)}, {@code mu = g_ig(T, f_s) + B f_s}): the
     * steam it coexists with has its chemical potential exactly when {@code p_w = f_s}.
     */
    private PhaseAmounts icePhase(double t, double p, double fugacity, double amount, Workspace w) {
        FreeWaterIce ice = freeWater.ice();
        CrystalReference.State solid = ice.properties(t, p);
        IdealGasFunction steamGas = freeWater.vapor();
        double idealEnthalpy = steamGas.enthalpy(t);
        double idealEntropy = steamGas.entropy(t, p);
        double idealGibbs = idealEnthalpy - t * idealEntropy;
        double logPhi = DeterministicMath.log(fugacity / p) + SteamVirial.residualGibbs(t, fugacity) / (R * t);
        double gibbs = idealGibbs + R * t * logPhi;
        double entropy = (solid.enthalpy() - gibbs) / t;
        w.oneLogPhi[0] = logPhi;
        w.oneIdealGibbs[0] = idealGibbs;
        w.iceState.set(PhaseKind.SOLID, ice.crystal(), null, 0, t, p, solid.volume(), idealEnthalpy,
                solid.enthalpy() - idealEnthalpy, idealEntropy, entropy - idealEntropy, gibbs, w.one, w.oneLogPhi, w.oneIdealGibbs);
        double[] amounts = new double[basis];
        amounts[waterIndex] = amount;
        return new PhaseAmounts(PhaseKind.SOLID, ice.crystal(), t, p, amounts, w.iceState.copy());
    }

    // Results.

    /** EOS-basis amounts over the contract basis (water, if any, zero). */
    private double[] expand(double[] amounts) {
        double[] expanded = new double[basis];
        for (int i = 0; i < count; i++) expanded[basisOfComponent[i]] = amounts[i];
        return expanded;
    }

    private EquilibriumResult converged(PhaseCompetition competition, Classification classification,
                                        List<PhaseAmounts> phases, List<PhaseState> coexisting, Diagnostics diagnostics,
                                        String band, EquilibriumResult.FreeWater split) {
        return converged(competition, classification, phases, coexisting, diagnostics, band, split, null);
    }

    /** @param note appended to the detail (a coexistence solved from a balance, P3 WP6b), or null */
    private EquilibriumResult converged(PhaseCompetition competition, Classification classification,
                                        List<PhaseAmounts> phases, List<PhaseState> coexisting, Diagnostics diagnostics,
                                        String band, EquilibriumResult.FreeWater split, String note) {
        EquilibriumResult.Coverage coverage = band == null ? convergedCoverage
                : new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY,
                        contract.fluidCoverage().evidence() + "; " + band + ": research-only"
                                + FLUID_ONLY_NOTE);
        String detail = band == null ? CONVERGED : CONVERGED + " (" + band + ")";
        if (note != null) detail += "; " + note;
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, detail,
                contract.identity(), competition, classification, phases, coexisting, coverage, diagnostics, split);
    }

    private EquilibriumResult unsupported(Specification specification, PhaseCompetition competition,
                                          UnsupportedReason reason, String detail) {
        return EquilibriumResult.unsupported(specification, contract.identity(), competition, reason, detail);
    }

    private static Diagnostics diagnostics(Workspace w, TangentPlaneStability.Result feed, boolean band) {
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

    /** Lower Cholesky factor of {@code a + mu I} into {@code factor} ({@link TangentPlaneStability#cholesky}); false unless positive definite. */
    private static boolean factorShifted(double[] a, int m, double mu, double[] factor) {
        for (int j = 0; j < m; j++) {
            for (int i = j; i < m; i++) factor[i * m + j] = a[i * m + j] + (i == j ? mu : 0.0);
        }
        return TangentPlaneStability.cholesky(factor, m);
    }
}
