package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Objects;

/** Fully resolved immutable M0/M1 numerical contract ready for later solver admission. */
public final class V3ColumnProblem {
    private final V3ColumnInput input;
    private final V3ColumnTopology topology;
    private final V3ActiveComponentBasis activeComponentBasis;
    private final V3CondenserComponentPhases condenserComponentPhases;
    private final double[] nodePressuresPascal;
    private final double[] nodeSideDrawMolPerSecond;
    private final double[] nodeSteamFeedMolPerSecond;
    private final double[] nodeSteamFeedEnthalpyWatts;
    private final double[] nodeHeatDutyWatts;
    private final double[] waterVaporFlowMolPerSecond;
    private final WaterCondenserRegime waterCondenserRegime;
    private final double waterVaporSlipCoefficient;
    private final double freeWaterFlowScaleMolPerSecond;
    private final V3WetTraySet wetTraySet;
    private final V3DegreeOfFreedomLedger degreeOfFreedomLedger;
    private final V3TruncationSupport truncationSupport;

    /** Built only by {@link V3ColumnProblemResolver}, which validates every argument and builds the ledger from them. */
    V3ColumnProblem(
            V3ColumnInput input, V3ColumnTopology topology, V3ActiveComponentBasis activeComponentBasis,
            V3CondenserComponentPhases condenserComponentPhases, double[] nodePressuresPascal,
            V3DegreeOfFreedomLedger degreeOfFreedomLedger, V3TruncationSupport truncationSupport,
            V3WetTraySet wetTraySet) {
        this.input = Objects.requireNonNull(input, "input");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.activeComponentBasis = Objects.requireNonNull(activeComponentBasis, "activeComponentBasis");
        this.condenserComponentPhases = Objects.requireNonNull(condenserComponentPhases, "condenserComponentPhases");
        this.nodePressuresPascal = Objects.requireNonNull(nodePressuresPascal, "nodePressuresPascal").clone();
        this.nodeSideDrawMolPerSecond = new double[topology.nodeCount()];
        for (V3SideDrawSpec draw : input.sideDraws()) {
            this.nodeSideDrawMolPerSecond[draw.trayNumber()] = draw.molarFlowMolPerSecond();
        }
        this.nodeSteamFeedMolPerSecond = V3SteamFeedSpec.nodeFeedFlows(input, topology);
        this.nodeSteamFeedEnthalpyWatts = new double[topology.nodeCount()];
        // Steam enters alone at its node's pressure, the injection pressure the resolver checks its superheat at.
        for (V3SteamFeedSpec feed : input.steamFeeds()) {
            this.nodeSteamFeedEnthalpyWatts[feed.stageNumber()] = feed.molarFlowMolPerSecond()
                    * V3WaterProperties.steamMolarEnthalpy(feed.temperatureKelvin(),
                            this.nodePressuresPascal[feed.stageNumber()]);
        }
        this.nodeHeatDutyWatts = V3PumparoundSpec.nodeDutyWatts(input, topology);
        this.waterVaporFlowMolPerSecond = V3SteamFeedSpec.upwardVaporProfile(nodeSteamFeedMolPerSecond, topology);
        this.waterCondenserRegime = waterCondenserRegime(input, topology, this.nodePressuresPascal);
        this.waterVaporSlipCoefficient = condenserSlipCoefficient(topology, waterCondenserRegime, input,
                this.nodePressuresPascal);
        double steam = 0.0;
        for (V3SteamFeedSpec feed : input.steamFeeds()) steam += feed.molarFlowMolPerSecond();
        this.freeWaterFlowScaleMolPerSecond = steam > 0.0 ? steam : 1.0;
        this.wetTraySet = Objects.requireNonNull(wetTraySet, "wetTraySet");
        this.degreeOfFreedomLedger = Objects.requireNonNull(degreeOfFreedomLedger, "degreeOfFreedomLedger");
        this.truncationSupport = Objects.requireNonNull(truncationSupport, "truncationSupport");
    }

    public V3ColumnInput input() {
        return input;
    }

    public V3ColumnTopology topology() {
        return topology;
    }

    V3ActiveComponentBasis activeComponentBasis() {
        return activeComponentBasis;
    }

    V3CondenserComponentPhases condenserComponentPhases() {
        return condenserComponentPhases;
    }

    V3TruncationSupport truncationSupport() {
        return truncationSupport;
    }

    /**
     * Whether this point's liquid flow is an unknown of the resolved problem.
     *
     * <p>Three rules compose here and nothing in the package may consult them separately: the node's
     * structural liquid phase, the per-component condenser phase rule, and the attempt-local support mask,
     * which can remove one phase of a point ({@code LIQUID_ONLY} / {@code VAPOR_ONLY}) or the point as a
     * whole.</p>
     */
    boolean hasLiquidUnknown(int node, int component) {
        return truncationSupport.hasLiquidUnknown(condenserComponentPhases, node, component);
    }

    /** Whether this point's vapour flow is an unknown of the resolved problem. */
    boolean hasVaporUnknown(int node, int component) {
        return truncationSupport.hasVaporUnknown(node, component);
    }

    /** A vapour-liquid equilibrium row exists exactly where both phases of the point are unknowns. */
    boolean hasEquilibriumRow(int node, int component) {
        return truncationSupport.hasEquilibriumRow(condenserComponentPhases, node, component);
    }

    public double[] nodePressuresPascal() {
        return nodePressuresPascal.clone();
    }

    public double nodePressurePascal(int node) {
        return nodePressuresPascal[node];
    }

    public V3DegreeOfFreedomLedger degreeOfFreedomLedger() {
        return degreeOfFreedomLedger;
    }

    public boolean hasSideDraws() {
        return !input.sideDraws().isEmpty();
    }

    public double nodeSideDrawMolPerSecond(int node) {
        return nodeSideDrawMolPerSecond[node];
    }

    public boolean hasSteamFeeds() {
        return !input.steamFeeds().isEmpty();
    }

    public double nodeSteamFeedMolPerSecond(int node) {
        return nodeSteamFeedMolPerSecond[node];
    }

    public double steamFeedEnthalpyWatts(int node) {
        return nodeSteamFeedEnthalpyWatts[node];
    }

    public boolean hasPumparounds() {
        return !input.pumparounds().isEmpty();
    }

    /** Prescribed constant stage heat, positive into the column; zero on every node without a pumparound. */
    public double stageHeatWatts(int node) {
        return nodeHeatDutyWatts[node];
    }

    /** Authored upward steam profile of a node: every mole fed at or below it. Free water is added by state. */
    public double waterVaporFlowMolPerSecond(int node) {
        return waterVaporFlowMolPerSecond[node];
    }

    /** The trays of this attempt's frozen free-water set. */
    V3WetTraySet wetTraySet() {
        return wetTraySet;
    }

    boolean hasWetTrays() {
        return wetTraySet.hasWetTrays();
    }

    boolean isWetTray(int node) {
        return wetTraySet.isWet(node);
    }

    /**
     * Whether this wet tray's free water is an unknown of the resolved ledger.
     *
     * <p>False on every tray of a <em>parametric</em> wet set, whose free water is frozen at a value the
     * continuation chose: such a tray carries free water in the water balance, the dilution term and the
     * energy rows exactly as a solved one does, but contributes neither an unknown nor a saturation row.</p>
     */
    boolean hasFreeWaterUnknown(int node) {
        return wetTraySet.hasFreeWaterUnknown(node);
    }

    /** Log-flow scale of a free-water unknown: the total authored steam, or one on a dry column. */
    double freeWaterFlowScaleMolPerSecond() {
        return freeWaterFlowScaleMolPerSecond;
    }

    /**
     * Aqueous liquid leaving a tray: downward to the tray below, or, on a carry-over tray one, upward to the drum
     * ({@link #carriesTopTrayWaterOver}). Exactly zero unless the tray is in the frozen wet set.
     */
    double freeWaterFlowMolPerSecond(V3DryMeshState state, int node) {
        return isWetTray(node) ? state.freeWaterFlow(node) : 0.0;
    }

    /**
     * Whether tray one's free water leaves upward to the condenser drum instead of falling to tray two
     * (condenser carry-over, E31 of batch 2026-11-04-open-thermo-data).
     *
     * <p>Water condensed on any other tray falls to the tray below and comes straight back up as vapour, so it
     * cannot change the net water passing the tray that shed it ({@link V3WetTraySet}). On tray one that loop
     * leaves the tray's saturation row without a lever. A drum that decants free water (the {@code FREE_WATER}
     * regime, on either the two-phase or the total-condenser branch) takes tray one's excess as entrained liquid
     * instead: the drum decants it with its own condensate, and tray one's vapour carries only the rest of the
     * steam. A drum that cannot decant ({@code NONE}, {@code ALL_VAPOR}) receives no carry-over, and tray one keeps
     * the falling free water and the {@code WATER_DEW_POINT} warning it had before.</p>
     */
    boolean carriesTopTrayWaterOver() {
        return waterCondenserRegime == WaterCondenserRegime.FREE_WATER;
    }

    /** Whether a tray's free water falls to the tray below: every wet tray except a carry-over tray one. */
    boolean freeWaterFalls(int node) {
        return node != 1 || !carriesTopTrayWaterOver();
    }

    /** Free water falling from this tray onto the one below; zero on a dry tray and on a carry-over tray one. */
    double fallingFreeWaterMolPerSecond(V3DryMeshState state, int node) {
        return freeWaterFalls(node) ? freeWaterFlowMolPerSecond(state, node) : 0.0;
    }

    /** Free water tray one carries over to the drum as liquid; zero unless tray one is wet and the drum decants. */
    double carriedOverWaterMolPerSecond(V3DryMeshState state) {
        return carriesTopTrayWaterOver() ? freeWaterFlowMolPerSecond(state, 1) : 0.0;
    }

    /**
     * Water vapor rising out of a node, from the candidate state.
     *
     * <p>At the condenser the water is not a balance but the drum's regime split; see
     * {@link #waterCondenserSplit}. On a tray it is {@link #trayWaterVaporFlow} of the state's free water.</p>
     */
    double waterVaporFlow(V3DryMeshState state, int node) {
        if (node == topology.condenserNode()) return waterCondenserSplit(state).vaporFlowMolPerSecond();
        return trayWaterVaporFlow(node, freeWaterFlowMolPerSecond(state, node),
                node >= 2 ? freeWaterFlowMolPerSecond(state, node - 1) : 0.0);
    }

    /**
     * Water vapor rising out of a tray or the sump, given that node's own free water and the free water of the
     * tray above (each zero where the tray is dry): the one statement of the tray water balance.
     *
     * <p>The balance {@code W_n = W_(n+1) + F_(n-1) + S_n - F_n} telescopes downward to
     * {@code W_n = (steam fed at or below n) + F_(n-1) - F_R}, and the sump is never wet ({@link V3WetTraySet}), so
     * {@code F_R} is zero and only the tray directly above contributes. A carry-over tray one changes two terms:
     * its free water leaves upward, so {@code W_1 = (all steam) - F_1}, and none of it reaches tray two, so
     * {@code W_2} is the authored profile alone. {@link V3WetTraySet}, {@link V3FreeWaterContinuation} and the
     * residual rows all read the water through here, with their own trial flows.</p>
     *
     * @throws IllegalArgumentException when tray one would carry over all the steam that reaches it: the vapour
     *         water is then not positive and the saturation row has no logarithm (a rejected trial, not a hold)
     */
    double trayWaterVaporFlow(int node, double ownFreeWaterMolPerSecond, double freeWaterAboveMolPerSecond) {
        if (node == 1 && carriesTopTrayWaterOver()) {
            double water = waterVaporFlowMolPerSecond[1] - ownFreeWaterMolPerSecond;
            if (!(water > 0.0)) {
                throw new IllegalArgumentException("V3 tray one cannot carry over all the steam that reaches it");
            }
            return water;
        }
        return waterVaporFlowMolPerSecond[node] + (node == 2 && carriesTopTrayWaterOver() ? 0.0 : freeWaterAboveMolPerSecond);
    }

    public double waterVaporSlipCoefficient() {
        return waterVaporSlipCoefficient;
    }

    WaterCondenserRegime waterCondenserRegime() {
        return waterCondenserRegime;
    }

    boolean hasFreeWaterCondenser() {
        return waterCondenserRegime == WaterCondenserRegime.FREE_WATER;
    }

    boolean hasAllVaporWaterCondenser() {
        return waterCondenserRegime == WaterCondenserRegime.ALL_VAPOR;
    }

    /**
     * Splits the condenser-arriving water between the molecular overhead vapor and a
     * separate free-water product. The molecular vapor allocation is capped by arriving water:
     * an unsaturated overhead cannot create water that was never fed to the column.
     *
     * <p>The arriving water is the authored steam total whether or not the top trays carry free water: no free
     * water is shed into tray one from above, so all of it reaches the drum, as tray one's vapour {@code W_1} and,
     * on a carry-over tray one, as the entrained liquid {@code F_1} besides. The drum decants the two together, so
     * the regime and the slip coefficient are still resolved once from the authored total, and this split is
     * exactly what it was before wet trays existed.</p>
     */
    WaterCondenserSplit waterCondenserSplit(V3DryMeshState state) {
        state = Objects.requireNonNull(state, "state");
        int condenser = topology.condenserNode();
        double arrivingWater = waterVaporFlowMolPerSecond(1);
        return switch (waterCondenserRegime) {
            case NONE -> new WaterCondenserSplit(0.0, 0.0);
            case ALL_VAPOR -> new WaterCondenserSplit(arrivingWater, 0.0);
            case FREE_WATER -> {
                double vaporWater = topology.condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                        ? Math.min(arrivingWater, waterVaporSlipCoefficient * hydrocarbonVaporTotal(state, condenser)) : 0.0;
                yield new WaterCondenserSplit(vaporWater, arrivingWater - vaporWater);
            }
        };
    }

    private static double hydrocarbonVaporTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += state.vaporFlow(node, component);
        }
        return total;
    }

    /** Candidate-derived molecular-vapor/free-water allocation at the condenser. */
    record WaterCondenserSplit(double vaporFlowMolPerSecond, double freeWaterFlowMolPerSecond) {}

    /** Free-water outcome at the condenser, independent of the hydrocarbon condenser phase branch. */
    enum WaterCondenserRegime {
        /** No stripping-steam feed is authored. */
        NONE,
        /** A separate pure-water boot exists; any remaining water joins the hydrocarbon vapor as a slip. */
        FREE_WATER,
        /** Water saturation pressure exceeds the drum pressure, so all stripping steam remains in the mixed vapor. */
        ALL_VAPOR
    }

    private static WaterCondenserRegime waterCondenserRegime(
            V3ColumnInput input, V3ColumnTopology topology, double[] pressures) {
        if (input.steamFeeds().isEmpty()) return WaterCondenserRegime.NONE;
        double temperature = input.specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst().orElseThrow().kelvin();
        if (topology.condenserPhaseBranch() == V3CondenserPhaseBranch.VAPOR_ONLY
                || temperature >= V3WaterProperties.criticalTemperature()) {
            return WaterCondenserRegime.ALL_VAPOR;
        }
        double waterFraction = V3WaterProperties.saturationPressurePascal(temperature) / pressures[topology.condenserNode()];
        if (!(waterFraction >= 0.0) || !Double.isFinite(waterFraction)) {
            throw new IllegalArgumentException("V3 condenser water saturation ratio is not finite and nonnegative");
        }
        return waterFraction >= 1.0 ? WaterCondenserRegime.ALL_VAPOR : WaterCondenserRegime.FREE_WATER;
    }

    private static double condenserSlipCoefficient(
            V3ColumnTopology topology, WaterCondenserRegime regime, V3ColumnInput input, double[] pressures) {
        if (regime != WaterCondenserRegime.FREE_WATER
                || topology.condenserPhaseBranch() != V3CondenserPhaseBranch.TWO_PHASE) return 0.0;
        double temperature = input.specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst().orElseThrow().kelvin();
        double waterFraction = V3WaterProperties.saturationPressurePascal(temperature) / pressures[topology.condenserNode()];
        return waterFraction / (1.0 - waterFraction);
    }

    /** Recomputed from the candidate; intentionally not capped during Newton iteration. */
    double liquidWithdrawalFraction(V3DryMeshState state, int node) {
        double rate = nodeSideDrawMolPerSecond[node];
        if (rate == 0.0) return 0.0;
        return V3SideDrawSpec.withdrawal(state, node, rate).fraction();
    }
}
