package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PackageBinding;
import com.wormzjl.icheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseDomain;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import com.wormzjl.icheme.science.thermo.phase.ThermoIdentity;
import com.wormzjl.icheme.science.thermo.phase.WaterParticipation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The equilibrium engine on the column's own kernel, for the feeds the feed flash proves unstable and cannot split itself
 * (batch 2026-11-16-supercritical-followups, P3, form F3): {@link FluidTpEquilibrium} on the derived evaluator of the
 * package's compounds other than water, the pairs the column evaluates ({@link V3PropertyPackage#derivedPairs}), fluid
 * phases only, an open domain (a data range never refuses). Its constant volume translation shifts every phase's
 * {@code ln phi_i} by the same {@code P c_i / R T} at one state, so its split is the column's: on the review's grid the
 * column's own converged splits and the engine's agree to {@code 1e-10} in {@code ln K} (P3_COLUMN_FEED_REVIEW.md).
 *
 * <p>A feed with water is not given to it: the engine carries water in its cubic with its own share, the column keeps free
 * water apart (owner rule: free water on a tray is a warning). Built once per package, on first use.</p>
 */
final class V3FeedEngine {
    private final FluidTpEquilibrium engine;
    private final List<String> species;
    /** The column component of each engine species. */
    private final int[] columnComponent;
    /** The column's water component, -1 without one. */
    private final int water;

    private V3FeedEngine(FluidTpEquilibrium engine, List<String> species, int[] columnComponent, int water) {
        this.engine = engine;
        this.species = species;
        this.columnComponent = columnComponent;
        this.water = water;
    }

    /** The engine over {@code basis} without water, on {@code catalog}'s package {@code packageId}. */
    static V3FeedEngine of(MaterialCatalog catalog, String packageId, String revision, V3ComponentBasis basis) {
        List<String> species = new ArrayList<>();
        List<Integer> columns = new ArrayList<>();
        int water = -1;
        for (int i = 0; i < basis.componentCount(); i++) {
            String id = basis.componentId(i);
            if (id.equals(CubicPhaseEvaluator.WATER)) {
                water = i;
                continue;
            }
            species.add(id);
            columns.add(i);
        }
        CubicPhaseEvaluator evaluator = PackageBinding.derivedEvaluator(catalog, packageId, species);
        var identity = new ThermoIdentity("column-feed:" + revision, evaluator.family(), ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
        var contract = new PhaseContract("column-feed:" + packageId, identity, evaluator.components(), WaterParticipation.NONE, null,
                Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), PhaseDomain.open("column-feed:" + packageId, evaluator.components()),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, "the column feed flash's fallback split"));
        return new V3FeedEngine(new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT),
                List.copyOf(species), columns.stream().mapToInt(Integer::intValue).toArray(), water);
    }

    /**
     * The engine's two-phase split: the lighter phase's share of the feed and whether that phase is the column's vapour
     * by the engine's own state of it (rule D): labelled {@code VAPOR}, or of reduced density below 1, or on a
     * supercritical isotherm of its own composition. A lighter phase that is none of these is a liquid: the split is two
     * liquids. Its own state decides, not a re-evaluation on the column's vapour root: a subcritical liquid can have a
     * metastable vapour root at the same state.
     */
    record Split(double lighterShare, boolean lighterIsVapour) {}

    FluidTpEquilibrium.Workspace newWorkspace() {
        return engine.newWorkspace();
    }

    /**
     * The engine's two-phase split of {@code z} (normalised over the column basis): the denser phase's shares into
     * {@code liquid}, the lighter one's into {@code vapour}, both over the column basis.
     *
     * @return the split, or null where the feed holds water or the engine answers no converged split of two fluid phases
     */
    Split split(double t, double p, double[] z, double[] liquid, double[] vapour, FluidTpEquilibrium.Workspace workspace) {
        if (water >= 0 && z[water] > 0.0) return null;
        double[] amounts = new double[species.size()];
        for (int a = 0; a < amounts.length; a++) amounts[a] = z[columnComponent[a]];
        EquilibriumResult result = engine.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
        if (!result.converged() || result.phases().size() != 2) return null;
        PhaseAmounts first = result.phases().get(0);
        PhaseAmounts second = result.phases().get(1);
        if (first.kind() == PhaseKind.SOLID || second.kind() == PhaseKind.SOLID) return null;
        // The vapour where there is one; of two liquids the one of lower reduced density (the list keeps no order).
        boolean firstLighter = first.kind() == PhaseKind.VAPOR || second.kind() != PhaseKind.VAPOR
                && first.traits() != null && second.traits() != null
                && first.traits().reducedDensity() < second.traits().reducedDensity();
        PhaseAmounts light = firstLighter ? first : second;
        PhaseAmounts heavy = firstLighter ? second : first;
        double lightTotal = light.total();
        double heavyTotal = heavy.total();
        java.util.Arrays.fill(liquid, 0.0);
        java.util.Arrays.fill(vapour, 0.0);
        for (int a = 0; a < amounts.length; a++) {
            liquid[columnComponent[a]] = heavy.amount(a) / heavyTotal;
            vapour[columnComponent[a]] = light.amount(a) / lightTotal;
        }
        boolean lighterIsVapour = light.kind() == PhaseKind.VAPOR || light.traits() != null
                && (light.traits().reducedDensity() < 1.0 || light.traits().supercriticalIsotherm());
        return new Split(lightTotal / (lightTotal + heavyTotal), lighterIsVapour);
    }
}
