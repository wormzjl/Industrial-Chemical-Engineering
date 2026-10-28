package com.wormzjl.icheme.fluid.support;

import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.SlurryTransport;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Shared, independent fixture-basis and finite-ledger calculations for fluid tests. */
public final class FluidTestSupport {
    private static final String NETWORK_PACKAGE = "icheme:tjl20_methane_nitrogen";
    private static final int NITROGEN = com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN;
    private static final int WATER = com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK;

    /** Exact gameplay-basis compositions shared by the physical qualification fixtures. */
    public enum Mixture { NITROGEN, WATER, WET_CRUDE }

    private FluidTestSupport() {}

    /**
     * The registered kind of a one-cell device a test lays out by its hydraulic kind: a pump or compressor is an in-line
     * mover, a {@code PIPE} carrying its control (batch 2026-10-28-old-mover-removal); every other kind is itself.
     */
    public static com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind mechanical(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind kind) {
        return kind == com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PUMP || kind == com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.COMPRESSOR
                ? com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE : kind;
    }

    /** First step of the multi-interval line rigs: small enough that a transient does not begin with an oversized
     * attempt; the controller doubles out of it within a few substeps. */
    public static final double COLD_START_SECONDS = .05;

    /** A token for a worker that is never cancelled and has no deadline: tests solve every admitted slice to its end. */
    public static final BoundedCpuSolveService.CancellationToken NEVER = new BoundedCpuSolveService.CancellationToken() {
        public void throwIfCancellationRequested() {}
    };

    /** Creates the bundled gameplay network model. */
    public static FluidThermodynamics networkModel() {
        return FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), NETWORK_PACKAGE);
    }

    /** A model of any bundled or test package: the default velocity cap, no trace truncation and the default solid
     * settings. Not the network model ({@link #networkModel}), whose trace cutoff and package resolution are the
     * configured ones. */
    public static FluidThermodynamics model(MaterialCatalog catalog, String packageId) {
        return new FluidThermodynamics(catalog, packageId, FluidThermodynamics.DEFAULT_MAXIMUM_VELOCITY,
                TraceTruncationPolicy.OFF, SlurryTransport.Settings.defaults());
    }

    /** Returns a fresh gameplay-basis composition for the named qualification fixture. */
    private static double[] composition(Mixture mixture) {
        Objects.requireNonNull(mixture, "mixture");
        double[] amounts = new double[WATER+1];
        switch (mixture) {
            case NITROGEN -> amounts[NITROGEN] = 1;
            case WATER -> amounts[WATER] = 1;
            case WET_CRUDE -> {
                amounts = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(NETWORK_PACKAGE)
                        .crudeFeed("icheme:tia_juana_light_methane").moleFractions(), WATER+1);
                amounts[NITROGEN] = .1;
                amounts[WATER] = .2;
            }
        }
        return amounts;
    }

    /** One mole of pure water on the network basis. */
    public static double[] water() {
        return composition(Mixture.WATER);
    }

    /** One mole of pure nitrogen on the network basis. */
    public static double[] nitrogen() {
        return composition(Mixture.NITROGEN);
    }

    /** Builds the existing one-cubic-metre fixture without bypassing EOS/flash closure. */
    public static FluidThermodynamics.State oneCubicMetreState(
            FluidThermodynamics model, Mixture mixture, double temperature, double pressure) {
        Objects.requireNonNull(model, "model");
        double[] amounts = composition(mixture);
        var unit = model.flashTP(temperature, pressure, amounts, () -> {});
        for (int component = 0; component < amounts.length; component++) amounts[component] /= unit.volume();
        return model.flashTP(temperature, pressure, amounts, () -> {});
    }

    /**
     * A device's boundary with its tank charged: a reservoir holds nitrogen at the spec's volume, temperature and pressure,
     * exactly as {@code FluidDeviceSpec.initialize} built it before owner decision D54 (a placed tank now holds nothing), so
     * a fixture whose subject is a charged tank keeps its charge, explicitly and to the bit; every other kind as
     * {@code initialize} builds it.
     */
    public static PassiveNetwork.Reservoir chargedBoundary(com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec spec,
            com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Device device,FluidThermodynamics model) {
        if(device.kind()!=com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.RESERVOIR)return spec.initialize(device,model,()->{});
        spec.validate(model,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.VOID);
        var state=model.initialNitrogenCharge(spec.volume(),spec.temperature(),spec.pressure(),()->{});
        return new PassiveNetwork.Reservoir(device.id(),device.position().y(),state,PassiveNetwork.NodeKind.RESERVOIR,
                new PassiveNetwork.Inventory(spec.volume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()));
    }

    /**
     * The state a closed vessel holds at {@code inventory} (its amounts, internal energy and capacity), started from
     * {@code previous}: the network's own energy-volume solve, through its public entry, the interval-start refresh
     * ({@code InventoryEquilibrium.refresh}) of a one-vessel graph.
     */
    public static FluidThermodynamics.State inventoryState(FluidThermodynamics model, FluidThermodynamics.State previous,
                                                           PassiveNetwork.Inventory inventory) {
        var vessel = new PassiveNetwork.Reservoir(1, 0, previous, PassiveNetwork.NodeKind.RESERVOIR, inventory);
        return com.wormzjl.icheme.science.fluid.network.InventoryEquilibrium
                .refresh(new PassiveNetwork(List.of(vessel), List.of()), model, () -> {}).graph().reservoirs().getFirst().state();
    }

    /** Retains the production nitrogen-initialization path used by world fixtures. */
    public static FluidThermodynamics.State nitrogenCharge(
            FluidThermodynamics model, double volume, double temperature, double pressure) {
        Objects.requireNonNull(model, "model");
        return model.initialNitrogenCharge(volume, temperature, pressure, () -> {});
    }

    /** Returns independent gameplay-basis molecular weights; does not call a production mass helper. */
    public static double[] molecularWeights(FluidThermodynamics model) {
        Objects.requireNonNull(model, "model");
        int components = model.hydrocarbon.componentCount() + 1;
        double[] weights = new double[components];
        for (int component = 0; component < components; component++) {
            weights[component] = component == components - 1
                    ? model.waterMolecularWeight : model.hydrocarbon.molecularWeight(component);
        }
        return weights;
    }

    /** Calculates mass directly from the supplied mole and molecular-weight bases. */
    public static double mass(double[] moles, double[] molecularWeights) {
        Objects.requireNonNull(moles, "moles");
        Objects.requireNonNull(molecularWeights, "molecularWeights");
        if (moles.length != molecularWeights.length) throw new IllegalArgumentException("Component basis mismatch");
        double total = 0;
        for (int component = 0; component < moles.length; component++) {
            total += moles[component] * molecularWeights[component];
        }
        return total;
    }

    /** Totals one graph's finite component and internal-energy ownership (no potential energy, owner decision D37 S1). */
    public static Ledger finiteLedger(PassiveNetwork graph, double[] molecularWeights) {
        return finiteLedger(List.of(Objects.requireNonNull(graph, "graph")), molecularWeights);
    }

    /** Totals only finite RESERVOIR ownership; generators, voids, ports and junctions own no finite ledger here. */
    public static Ledger finiteLedger(Iterable<PassiveNetwork> graphs, double[] molecularWeights) {
        Objects.requireNonNull(graphs, "graphs");
        double[] weights = Objects.requireNonNull(molecularWeights, "molecularWeights").clone();
        double[] components = new double[weights.length];
        double energy = 0;
        for (var graph : graphs) {
            for (var reservoir : graph.reservoirs()) {
                if (reservoir.kind() != PassiveNetwork.NodeKind.RESERVOIR) continue;
                var inventory = reservoir.inventory();
                double[] moles = inventory.moles();
                if (moles.length != components.length) throw new IllegalArgumentException("Component basis mismatch");
                for (int component = 0; component < components.length; component++) {
                    if (!Double.isFinite(moles[component]) || moles[component] < 0) {
                        throw new AssertionError("Invalid finite inventory at reservoir " + reservoir.id()
                                + " component " + component + ": " + moles[component]);
                    }
                    components[component] += moles[component];
                }
                energy += inventory.internalEnergy();
            }
        }
        return new Ledger(components, energy);
    }

    /** Asserts closed component and energy balance with the shared BAL tolerances. */
    public static void assertClosed(Ledger before, Ledger after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        double[] beforeComponents = before.components();
        ConservationAssertions.components(
                beforeComponents, after.components(), new double[beforeComponents.length], beforeComponents);
        ConservationAssertions.energy(before.energy(), after.energy(), 0, Math.abs(before.energy()));
    }

    /** Immutable totals for finite component and internal-energy ownership. */
    public record Ledger(double[] components, double energy) {
        public Ledger {
            components = Objects.requireNonNull(components, "components").clone();
            if (!Double.isFinite(energy)) throw new IllegalArgumentException("Nonfinite ledger energy");
            for (double amount : components) {
                if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid ledger inventory");
            }
        }

        @Override public double[] components() { return components.clone(); }
    }
}
