package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The pilot world's row E (the branch's P6b failure 2, re-measured for milestone M3 of the unified thermo port): liquid
 * methane (generator 115 K, 200 kPa) and CO2 (generator 250 K, 250 kPa) through a 1 m3 vessel to a void at 101.325 kPa,
 * from the reservoir's initial nitrogen at 298.15 K. The vessel ends holding a vapour, a methane-rich liquid and CO2-I.
 *
 * <p>On the branch every accepted substep closed the vessel's crystal UV: the cold UV cost 21,576 to 60,133 kernel
 * evaluations, 93 to 99.9 % of each interval's checkpoints (1,324,561, 5,015,036 and 10,489,051 in the first three
 * intervals, the in-game "HELD: soft budget"), and P6b's warm start brought them to 362,486, 1,078,475 and 4,301,502.
 * Under the refresh form (owner decision D23) the crystal test runs once per vessel per interval, at its refresh, and the
 * steps carry the crystal inert: 5,799, 31,158 and 52,881 checkpoints. The steps carry the CO2 the vessel has not yet
 * frozen condensed in its liquid ({@code FluidThermodynamics.adopt}); its own fluid split, the crystal held out, is a
 * CO2-rich liquid beside the methane, three fluid phases, on which this world held at its first step. The warm start
 * stays in the engine and is pinned here: from the vessel's own state it equals the cold search to 1e-10 at a fraction
 * of its cost.</p>
 */
class CrystalFlushBudgetTest {
    private static final String PILOT = "icheme:pilot_cryogenic";
    private static final MaterialCatalog CATALOG = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
    private static final int CO2 = 3;
    private final FluidThermodynamics model = FluidTestSupport.model(CATALOG, PILOT);

    private FluidThermodynamics.State metre(double t, double p, double[] feed) {
        var probe = model.flashTP(t, p, feed, () -> {});
        double[] amounts = feed.clone();
        for (int i = 0; i < amounts.length; i++) amounts[i] /= probe.volume();
        return model.flashTP(t, p, amounts, () -> {});
    }

    private PassiveNetwork rowE() {
        var pipe = new PipeResistance.Geometry(1.0, 0.05, 4.5e-5, 0.0);
        var nitrogen = metre(298.15, 101325, new double[] {1, 0, 0, 0, 0});
        return new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(63, 0, metre(115, 2e5, new double[] {0, 1, 0, 0, 0}), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(65, 0, metre(250, 2.5e5, new double[] {0, 0, 0, 1, 0}), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(71, 0, nitrogen),
                new PassiveNetwork.Reservoir(75, 0, nitrogen, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(1, 0, 2, pipe), new PassiveNetwork.Pipe(2, 1, 2, pipe), new PassiveNetwork.Pipe(3, 2, 3, pipe)));
    }

    private double crystal(PassiveNetwork.Inventory inventory) {
        double moles = 0;
        for (var p : inventory.solids().crystals()) moles += p.massKg() / model.molecularWeight(CO2);
        return moles;
    }

    private static double solid(EquilibriumResult result) {
        double s = 0;
        for (var phase : result.phases()) if (phase.kind() == PhaseKind.SOLID) s += phase.amount(CO2);
        return s;
    }

    @Test
    void theFlushOfLiquidMethaneBesideCo2CrystalsTestsItsCrystalOncePerInterval() {
        var graph = rowE();
        var solver = new PassiveIntervalSolver(model);
        double step = 0.05;
        long[] bounds = {BOUND_1, BOUND_2, BOUND_3};
        for (int k = 0; k < 3; k++) {
            long[] checkpoints = {0};
            var result = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> checkpoints[0]++, step);
            step = solver.nextStepEstimate();
            graph = result.graph();
            var vessel = graph.reservoirs().get(2);
            var s = vessel.state();
            System.out.printf(Locale.ROOT, "row E interval %d: %d checkpoints, %d substeps, %d rejected | %.4f K %.1f Pa liquid %.5f m3 vapour %.5f m3 crystal %.6f mol%n", k + 1,
                    checkpoints[0], result.acceptedSubsteps(), result.rejectedSubsteps(), s.temperature(), s.pressure(), TestPhases.liquidVolume(s), s.vaporVolume(), crystal(vessel.inventory()));
            assertTrue(checkpoints[0] < bounds[k], "interval " + (k + 1) + ": " + checkpoints[0] + " checkpoints (the branch's per-substep form 1,324,561 / 5,015,036 / "
                    + "10,489,051, with its warm start 362,486 / 1,078,475 / 4,301,502)");
        }
        var settled = InventoryEquilibrium.refresh(graph, model, () -> {}).graph();
        var node = settled.reservoirs().get(2);
        var s = node.state();
        System.out.printf(Locale.ROOT, "row E vessel at the next refresh: %.4f K %.1f Pa liquid %.5f m3 vapour %.5f m3 crystal %.6f mol%n", s.temperature(), s.pressure(),
                TestPhases.liquidVolume(s), s.vaporVolume(), crystal(node.inventory()));
        assertTrue(TestPhases.liquidVolume(s) > 0 && s.vaporVolume() > 0 && crystal(node.inventory()) > 0, "a vapour, a liquid and the crystal");
        // The vessel's UV warm from its own state equals the cold search to 1e-10 at a fraction of its cost.
        var inventory = node.inventory();
        double[] totals = inventory.moles();
        totals[CO2] += crystal(inventory);
        double energy = inventory.internalEnergy();
        for (var p : inventory.solids().crystals()) energy += p.massKg() * p.material().crystal().energyOffset();
        var formation = model.hydrocarbon.spine().orElseThrow().formationReference();
        energy = EnergyReference.sensible(formation.components()).rebase(energy, Arrays.copyOf(totals, totals.length - 1), formation);
        var engine = FluidTpEquilibrium.forPackage(CATALOG, PILOT);
        var request = EquilibriumRequest.uv(energy, inventory.volume(), engine.contract().components(), totals, PhaseCompetition.FLUID_AND_CRYSTALS);
        var cold = engine.uv(request, engine.newWorkspace());
        var warm = engine.uv(request, engine.newWorkspace(), s.temperature(), s.pressure());
        System.out.printf(Locale.ROOT, "row E vessel UV: cold %s %.9f K %d kernel evaluations; warm %.9f K %d kernel evaluations%n", cold.classification(),
                cold.phases().getFirst().temperature(), cold.diagnostics().kernelEvaluations(), warm.phases().getFirst().temperature(), warm.diagnostics().kernelEvaluations());
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID_SOLID, cold.classification());
        assertEquals(cold.classification(), warm.classification());
        double tc = cold.phases().getFirst().temperature(), pc = cold.phases().getFirst().pressure();
        assertEquals(tc, warm.phases().getFirst().temperature(), 1e-10 * tc);
        assertEquals(pc, warm.phases().getFirst().pressure(), 1e-10 * pc);
        assertEquals(solid(cold), solid(warm), 1e-10 * totals[CO2]);
        assertTrue(warm.diagnostics().kernelEvaluations() < 0.25 * cold.diagnostics().kernelEvaluations(),
                "warm " + warm.diagnostics().kernelEvaluations() + " of cold " + cold.diagnostics().kernelEvaluations());
    }
    /** The refresh form's checkpoints, 5,799, 31,158 and 52,881 when re-measured (M3), with half again as headroom: the
     * per-substep form's cost is ten to two hundred times these. */
    // BOUND_1 9000 -> 10000 (owner decision of 2026-10-18 on the t38 plan): with the velocity cap in every line's law the row
    // E flush cost 9381 checkpoints in interval 1 (7992 before); with the Newton iteration limit of 30 it costs 8495, so the
    // 9000 bound would pass too, and 10000 is kept as headroom (owner, 2026-10-18, on the final test's review).
    private static final long BOUND_1 = 10_000, BOUND_2 = 47_000, BOUND_3 = 80_000;
}
