package com.wormzjl.icheme.science.plant.still;

import com.wormzjl.icheme.science.column.still.StageThermo;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.fluid.thermo.TranslatedPengRobinson;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import java.util.List;

/**
 * The still column's thermo on an island's own model (batch 2026-11-13-codebase-review, C17 option (a)): the network's
 * phase evaluator ({@link HydrocarbonModel}, any package the network takes, {@code icheme:refinery} included) and, for a
 * liquid that holds water, the engine's own TP split ({@link FluidThermodynamics#flashTP}). Each method makes the one model
 * call the column made itself before the move, with the same arguments, and returns what it gave. The column's basis is the
 * model's conserved basis where the model carries water (water last); a dry model's basis is its compounds, and each vector
 * is passed to the model with water appended at zero. Minecraft-free.
 */
public final class NetworkStageThermo implements StageThermo {
    private static final Runnable NO_CHECKPOINT = () -> {
    };

    private final FluidThermodynamics thermo;
    private final HydrocarbonModel model;
    /** The basis: the compounds, then water where the model carries it ({@link #water}). */
    private final int n, water;
    private final double[] tc, pc, omega, molarMass;

    /** The thermo of {@code thermo}'s model. */
    public NetworkStageThermo(FluidThermodynamics thermo) {
        this.thermo = thermo;
        model = thermo.hydrocarbon;
        int compounds = model.componentCount();
        water = model.wet() ? compounds : -1;
        n = model.wet() ? compounds + 1 : compounds;
        tc = new double[n];
        pc = new double[n];
        omega = new double[n];
        molarMass = new double[n];
        var components = components(model);
        for (int i = 0; i < compounds; i++) {
            var c = components.get(i);
            tc[i] = c.criticalTemperatureKelvin();
            pc[i] = c.criticalPressurePascal();
            omega[i] = c.acentricFactor();
        }
        if (water >= 0) {
            // Water's constants for the Wilson start: the model's own derivation, where its cubic carries water last.
            var derivation = model.evaluator().derivation();
            tc[water] = derivation.criticalTemperatures()[water];
            pc[water] = derivation.criticalPressures()[water];
            omega[water] = derivation.acentricFactors()[water];
        }
        for (int i = 0; i < n; i++) molarMass[i] = model.molecularWeight(i);
    }

    private static List<ThermoComponent> components(HydrocarbonModel model) {
        if (model.spine().isPresent()) return model.spine().get().components();
        return model.propertyPackage().properties().stream().map(p -> new ThermoComponent(p.component(), p.pr().criticalTemperature(),
                p.pr().criticalPressure(), p.pr().acentricFactor(), p.molecularWeight())).toList();
    }

    /** The island's model this thermo reads. */
    public FluidThermodynamics thermo() {
        return thermo;
    }

    /** Its phase evaluator. */
    public HydrocarbonModel model() {
        return model;
    }

    @Override
    public int componentCount() {
        return n;
    }

    @Override
    public int water() {
        return water;
    }

    @Override
    public double criticalTemperature(int i) {
        return tc[i];
    }

    @Override
    public double criticalPressure(int i) {
        return pc[i];
    }

    @Override
    public double acentricFactor(int i) {
        return omega[i];
    }

    @Override
    public double molecularWeight(int i) {
        return molarMass[i];
    }

    /** A vector on the column's basis on the model's conserved basis: itself where the model carries water, else water at zero. */
    private double[] conserved(double[] x) {
        return water >= 0 ? x : java.util.Arrays.copyOf(x, n + 1);
    }

    private static PengRobinsonKernel.Root root(Root root) {
        return root == Root.LIQUID ? PengRobinsonKernel.Root.LIQUID : PengRobinsonKernel.Root.VAPOR;
    }

    @Override
    public Phase phase(double t, double p, double[] x, Root root) {
        var phase = model.phase(t, p, conserved(x), root(root));
        return new Phase(phase.molarEnthalpy(), phase.molarVolume());
    }

    /** The model's workspace at one temperature. */
    private record Terms(TranslatedPengRobinson.Workspace workspace) implements Prepared {
    }

    @Override
    public Prepared prepare(double t) {
        return new Terms(model.prepare(t));
    }

    @Override
    public double[] logFugacity(double t, double p, double[] x, Root root, Prepared prepared) {
        return model.phase(t, p, conserved(x), root(root), ((Terms) prepared).workspace(), false).logFugacityView();
    }

    @Override
    public Phases flash(double t, double p, double[] x) {
        var state = thermo.flashTP(t, p, conserved(x), NO_CHECKPOINT);
        return new Phases(FluidThermodynamics.holdsPhase(state, FluidThermodynamics.GAS), FluidThermodynamics.holdsPhase(state, FluidThermodynamics.OIL),
                FluidThermodynamics.holdsPhase(state, FluidThermodynamics.AQUEOUS), state.labelAmountsView(FluidThermodynamics.GAS),
                state.labelAmountsView(FluidThermodynamics.OIL), state.labelAmountsView(FluidThermodynamics.AQUEOUS));
    }
}
