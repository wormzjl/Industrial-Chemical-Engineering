package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoModel;

/**
 * The support floor's lift: before an attempt derives its truncation support, every point and phase the floor
 * removed that the seed's retained neighbours deliver material to again is restored, and a retained point the
 * seed put far above what feeds it is brought back down. The companion of {@link V3TruncationSupport}, which
 * derives the support from the lifted seed.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2). Each function
 * reads only its arguments: a problem, a thermodynamic model, a state and the flow arrays it lifts in place.
 * {@code componentFloor} became package-private with the move ({@code V3AttemptLoop.seedForTopology} reads it).</p>
 */
final class V3FloorLift {
    /**
     * How far above its own material row a seeded point may sit before {@link #capOversizedPoint} resets it.
     *
     * <p>The rule has to tolerate the ordinary case, where a seed is merely inexact. A point's material row
     * says {@code l + v = I}, and a continuation seed satisfies it on the grid it came from, not on the one it
     * is being mapped onto, so a factor of a few is normal and carries real information about the profile.
     * What is not normal, and what this exists to catch, is a trace point sitting orders of magnitude above
     * anything that feeds it. Three is the smallest round factor that leaves the ordinary case alone;
     * measured on the cold 328 K pilot it is also the whole of the effect, because the flows it catches are
     * eight to eighteen e-folds too high rather than two.</p>
     */
    private static final double OVERSIZED_SEED_FACTOR = 3.0;

    private V3FloorLift() {}

    /**
     * Restores every phase the support floor removed that the current state says would carry material again.
     *
     * <p>Two rules, because a removed point and a removed phase are decided by different quantities.</p>
     *
     * <p>A point that is below the floor in both phases carries only what its retained neighbours deliver,
     * so the material inflow is the criterion, and the delivered material {@code I} is split by the local
     * equilibrium rather than evenly: {@code l = I / (1 + K_c V/L)}, {@code v = I − l}. The reinserted point
     * then satisfies its material row <em>and</em> its equilibrium row at the seed, so the refresh that
     * follows is the same one-to-three-iteration polish a drop is, instead of the five-to-eleven-iteration
     * restart an even split produced (measured: every reinsertion used to begin above a scaled residual of
     * two). Where the split gives one phase less than a floor the point simply re-enters as a one-phase
     * point, which is consistent: the same comparison decides it again in {@code derive}.</p>
     *
     * <p>The points are swept in flow direction, downward for the liquid the tray above delivers and then
     * upward for the vapour the tray below delivers, each pass reading the values the earlier lifts wrote.
     * A profile that re-enters over several trays is therefore restored in one pass; reading the unlifted
     * state instead made reinsertion a front that could only advance one tray per refresh, which is what
     * exhausted the refresh cap on the wet TJL19 case (a Tia Juana test fixture since 2026-11-05) and lost it entirely
     * at a cap of one.</p>
     *
     * <p>The same two sweeps also run the opposite rule, {@link #capOversizedPoint}: a retained point the seed
     * put far <em>above</em> the material its neighbours deliver is brought back down to that material. The two
     * rules share a criterion and a sweep on purpose — the inflow {@code I} is the whole right-hand side of the
     * point's material row, so a chain of oversized trays is repaired in the same single pass a chain of
     * removed ones is.</p>
     *
     * <p>A one-phase point is different and is lifted first, by {@link #liftAbsentPhase}.</p>
     */
    static V3DryMeshState liftFloorSupport(
            V3ColumnProblem problem, V3ThermoModel thermo, V3DryMeshState state) {
        V3ColumnTopology topology = problem.topology();
        int components = problem.activeComponentBasis().componentCount();
        double refluxRatio = problem.input().specifications().stream()
                .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast).findFirst().orElseThrow().ratio();
        double refluxFraction = refluxRatio / (1.0 + refluxRatio);
        double[][] liquid = new double[topology.nodeCount()][components];
        double[][] vapor = new double[topology.nodeCount()][components];
        double[] temperatures = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            temperatures[node] = state.temperatureKelvin(node);
            for (int component = 0; component < components; component++) {
                liquid[node][component] = state.liquidFlow(node, component);
                vapor[node][component] = state.vaporFlow(node, component);
            }
        }
        V3StageEquilibriumRatios equilibrium =
                V3StageEquilibriumRatios.of(problem, thermo, state, thermo.newWorkspace());
        double[] withdrawalRetained = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            withdrawalRetained[node] = 1.0 - problem.liquidWithdrawalFraction(state, node);
        }
        // One-phase points first: a phase restored here can feed a removed neighbour in the sweeps below.
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < components; component++) {
                double floor = componentFloor(problem, component);
                boolean hasLiquid = hasLiquidPhase(problem, node, component);
                boolean hasVapor = topology.hasVaporPhase(node);
                boolean liquidAbove = hasLiquid && liquid[node][component] >= floor;
                boolean vaporAbove = hasVapor && vapor[node][component] >= floor;
                if (liquidAbove == vaporAbove) continue;
                liftAbsentPhase(equilibrium.node(node), liquid, vapor, node, component, floor,
                        hasLiquid, hasVapor, liquidAbove);
            }
        }
        for (boolean downward : new boolean[] {true, false}) {
            for (int index = 0; index < topology.nodeCount(); index++) {
                int node = downward ? index : topology.nodeCount() - 1 - index;
                for (int component = 0; component < components; component++) {
                    double floor = componentFloor(problem, component);
                    boolean hasLiquid = hasLiquidPhase(problem, node, component);
                    boolean hasVapor = topology.hasVaporPhase(node);
                    double inflow = deliveredInflow(problem, liquid, vapor, withdrawalRetained, refluxFraction,
                            node, component);
                    if ((hasLiquid && liquid[node][component] >= floor)
                            || (hasVapor && vapor[node][component] >= floor)) {
                        if (node != topology.feedTrayNumber()) {
                            capOversizedPoint(equilibrium.node(node), liquid, vapor, node, component, inflow,
                                    floor, hasLiquid, hasVapor);
                        }
                        continue;
                    }
                    if (inflow < V3TruncationSupport.FLOOR_REINSERTION_FACTOR * floor) continue;
                    reinsertRemovedPoint(equilibrium.node(node), liquid, vapor, node, component, inflow,
                            hasLiquid, hasVapor);
                }
            }
        }
        return new V3DryMeshState(topology, components, liquid, vapor, temperatures,
                V3ColumnInitializer.freeWaterFlows(state));
    }

    /**
     * The whole right-hand side of one point's material row, read from the flows lifted so far.
     *
     * <p>It is the material the point's retained neighbours and the feed actually deliver to it, and it is
     * what both floor rules compare against: a point below the floor re-enters at this value, and a point far
     * above it is brought back to it. The terms are exactly those of
     * {@code V3MeshResidualEvaluator.materialBalance} — a pumparound moves heat rather than mass and a steam
     * feed is water rather than a hydrocarbon component, so neither adds a term here.</p>
     */
    private static double deliveredInflow(
            V3ColumnProblem problem, double[][] liquid, double[][] vapor, double[] withdrawalRetained,
            double refluxFraction, int node, int component) {
        V3ColumnTopology topology = problem.topology();
        if (node == topology.condenserNode()) return vapor[1][component];
        double inflow = node == 1
                ? refluxFraction * liquid[0][component]
                : withdrawalRetained[node - 1] * liquid[node - 1][component];
        if (node < topology.reboilerNode()) inflow += vapor[node + 1][component];
        if (node == topology.feedTrayNumber()) {
            inflow += problem.activeComponentBasis().feedFlowMolPerSecond(component);
        }
        return inflow;
    }

    /**
     * Brings a retained point that the seed put far above its own material row back to what feeds it.
     *
     * <p>A point's material row says its two outlets sum to what arrives: {@code l + v = I}. A seed can put a
     * trace point orders of magnitude above that — a continuation grid whose profile is stretched, or a
     * projection that carried a bulk value into a section where the component is a trace — and Newton is then
     * very slow to undo it. In log-flow coordinates the row is {@code r = I − a·e^z} and, once {@code a·e^z}
     * dominates {@code I}, the Newton step is {@code −1 + I/(a·e^z)}: one e-fold per iteration however good
     * the Jacobian is, so a point eighteen e-folds too high costs eighteen iterations on a residual that sits
     * at exactly one throughout. Setting it to the equilibrium split of {@code I}, the same split a
     * reinsertion uses, closes both of the point's rows at the seed instead.</p>
     *
     * <p>The rule fires only above {@link #OVERSIZED_SEED_FACTOR} times the delivered material, so a point at
     * or below what feeds it is never touched, and it never <em>raises</em> a flow: raising is the
     * reinsertion's job and doing both here would let the two rules chase each other across the sweeps. It is
     * also, by construction, inactive on any state that satisfies its material rows — every refresh reads a
     * solved state, where {@code l + v = I} exactly — so no support refresh, sink-edge defect or audited bound
     * can move because of it.</p>
     *
     * <p>Structure is left alone in both directions. A phase the point does not have is never written, and a
     * phase below the floor is left exactly where it is rather than being pulled up to the floor, so the
     * retention {@code V3TruncationSupport.derive} reads from the lifted seed is bit-for-bit the retention it
     * would have read without the cap. The feed tray is excluded by the caller for the same reason it is
     * retained whole by {@code derive}: it is the root of every material path, and its flows are the
     * continuation's anchor rather than a consequence of its neighbours.</p>
     */
    private static void capOversizedPoint(
            V3StageEquilibriumRatios.Node ratios, double[][] liquid, double[][] vapor, int node, int component,
            double inflow, double floor, boolean hasLiquid, boolean hasVapor) {
        boolean capLiquid = hasLiquid && liquid[node][component] >= floor;
        boolean capVapor = hasVapor && vapor[node][component] >= floor;
        double total = (capLiquid ? liquid[node][component] : 0.0) + (capVapor ? vapor[node][component] : 0.0);
        double supply = Math.max(inflow, floor);
        if (!(total > OVERSIZED_SEED_FACTOR * supply)) return;
        if (!capLiquid || !capVapor) {
            if (capLiquid) liquid[node][component] = Math.max(floor, Math.min(liquid[node][component], supply));
            if (capVapor) vapor[node][component] = Math.max(floor, Math.min(vapor[node][component], supply));
            return;
        }
        // Without properties the even split is the only defensible allocation, exactly as for a reinsertion.
        double liquidShare = ratios == null ? 0.5 * supply
                : supply / (1.0 + ratios.equilibriumRatio(component) * ratios.vaporTotalMolPerSecond()
                        / ratios.liquidTotalMolPerSecond());
        if (!Double.isFinite(liquidShare) || liquidShare < 0.0) liquidShare = 0.0;
        liquidShare = Math.min(liquidShare, supply);
        liquid[node][component] = Math.max(floor, Math.min(liquid[node][component], liquidShare));
        vapor[node][component] = Math.max(floor, Math.min(vapor[node][component], supply - liquidShare));
    }

    /** Splits the delivered material over the point's present phases the way its equilibrium row would. */
    private static void reinsertRemovedPoint(
            V3StageEquilibriumRatios.Node ratios, double[][] liquid, double[][] vapor, int node, int component,
            double inflow, boolean hasLiquid, boolean hasVapor) {
        if (!hasLiquid || !hasVapor) {
            if (hasLiquid) liquid[node][component] = Math.max(liquid[node][component], inflow);
            if (hasVapor) vapor[node][component] = Math.max(vapor[node][component], inflow);
            return;
        }
        // Without properties the even split is the only defensible allocation, as it was before.
        double liquidShare = ratios == null ? 0.5 * inflow
                : inflow / (1.0 + ratios.equilibriumRatio(component) * ratios.vaporTotalMolPerSecond()
                        / ratios.liquidTotalMolPerSecond());
        if (!Double.isFinite(liquidShare) || liquidShare < 0.0) liquidShare = 0.0;
        liquidShare = Math.min(liquidShare, inflow);
        liquid[node][component] = Math.max(liquid[node][component], liquidShare);
        vapor[node][component] = Math.max(vapor[node][component], inflow - liquidShare);
    }

    static double componentFloor(V3ColumnProblem problem, int component) {
        return problem.activeComponentBasis().flowScale(component) * V3TruncationSupport.TRACE_FLOOR_FRACTION;
    }

    private static boolean hasLiquidPhase(V3ColumnProblem problem, int node, int component) {
        return problem.topology().hasLiquidPhase(node)
                && problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component);
    }

    /**
     * Restores the absent phase of a one-phase point when equilibrium implies it would carry ten floors.
     *
     * <p>An inflow test is the wrong criterion here and the delivered material is the wrong value: the phase
     * that is present already carries the whole inflow, so splitting it again would put a bulk flow into a
     * phase the state says is empty. What decides a one-phase point is the flow its own equilibrium row
     * would give the absent phase — {@code v* = K_c (V/L) l}, or {@code l* = v L / (K_c V)} — and lifting to
     * exactly that value leaves the point's material row off by {@code v*} against a throughput of order
     * {@code l}, which is small by construction. It is also the quantity {@code PHASE_TRUNCATION_DEFECT}
     * audits, so a phase this rule declines to restore is one the audit can bound.</p>
     */
    private static void liftAbsentPhase(
            V3StageEquilibriumRatios.Node ratios, double[][] liquid, double[][] vapor,
            int node, int component, double floor, boolean hasLiquid, boolean hasVapor, boolean liquidAbove) {
        if (ratios == null || !hasLiquid || !hasVapor) return;
        double implied = liquidAbove
                ? ratios.impliedVaporFlowMolPerSecond(component, liquid[node][component])
                : ratios.impliedLiquidFlowMolPerSecond(component, vapor[node][component]);
        if (implied < V3TruncationSupport.FLOOR_REINSERTION_FACTOR * floor) return;
        if (liquidAbove) vapor[node][component] = Math.max(vapor[node][component], implied);
        else liquid[node][component] = Math.max(liquid[node][component], implied);
    }
}
