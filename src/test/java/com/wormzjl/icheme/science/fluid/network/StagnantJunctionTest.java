package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Two vessels holding different mixtures, joined through a junction, settle at one pressure (the branch's P6b
 * regression, re-homed for milestone M3 of the unified thermo port). On the branch the junction's mixture was frozen per
 * pass on its donors, so at a flow the pressure rows could not resolve (+2.3e-8 and -6.4e-9 kg/s, 0.004 Pa at 1.17 MPa)
 * each donor gave the flow the other sign and the passes closed a cycle; the branch stated such connections at zero
 * flow for the rest of the solve (its A1). Main has no donor re-pass: a junction mixes its own buffer with the live
 * inflow, continuously through zero flow, so the cycle is gone without A1. The CO2/N2 chain deposits CO2 in the cold
 * vessel at its refreshes; the CH4/N2 chain is the fluid-only control.
 *
 * <p>The cold vessel's connection draws its gas (a vapour port). Frost formed from a gas has no liquid carrier. Main's
 * outlet check closed a bulk connection drawing from it both ways (NO_LIQUID_CARRIER; plan section 3, item 4: only the
 * gas outlet stays open): on a bulk port that closure came at the ninth interval, when the refresh's latent heat lifted the
 * frosted vessel 45 Pa above the junction and made it the donor, and the vessels then stood 45 Pa apart. Under owner
 * decision D33 (solids ride the liquid) a bulk end on the frosted vessel draws its gas as the vapour port does.</p>
 */
class StagnantJunctionTest {
    private static final String PILOT = "icheme:pilot_cryogenic";
    private static final int CO2 = 3;
    private static final double[] WARM_CO2 = {.9, 0, 0, .1, 0}, WARM_METHANE = {.9, .1, 0, 0, 0}, COLD_NITROGEN = {1, 0, 0, 0, 0};
    private final FluidThermodynamics model = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);

    private FluidThermodynamics.State charge(double t, double p, double[] z, double volume) {
        var unit = model.flashTP(t, p, z, () -> {});
        double[] n = z.clone();
        for (int i = 0; i < n.length; i++) n[i] *= volume / unit.volume();
        return model.flashTP(t, p, n, () -> {});
    }

    private PassiveNetwork chain(double[] warm) {
        var hot = charge(250, 1.3e6, warm, .1);
        var cold = charge(140, 1e6, COLD_NITROGEN, .1);
        var pipe = new PipeResistance.Geometry(1, .004, .000045, 0);
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, hot), new PassiveNetwork.Reservoir(2, 0, hot, PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3, 0, cold)), List.of(new PassiveNetwork.Pipe(10, 0, 1, pipe),
                new PassiveNetwork.Pipe(11, 1, 2, pipe).withPorts(PassiveNetwork.PhasePort.BULK, PassiveNetwork.PhasePort.VAPOR)));
    }

    /** Moles of CO2 in a node's crystal. */
    private double crystal(PassiveNetwork.Reservoir node) {
        double moles = 0;
        for (var p : node.inventory().solids().crystals()) moles += p.massKg() / model.molecularWeight(CO2);
        return moles;
    }

    /** The island's species: every node's inventory, the junction's buffer (D46) and the crystals' CO2 included. */
    private double[] species(PassiveNetwork graph) {
        double[] total = new double[5];
        for (var node : graph.reservoirs()) {
            double[] n = node.inventory().moles();
            for (int c = 0; c < total.length; c++) total[c] += n[c];
            total[CO2] += crystal(node);
        }
        return total;
    }

    /** Twelve 5 s intervals through the interval solver, each committing; species conserved; the junction at rest at the end. */
    private PassiveNetwork settles(double[] warm, String label) {
        var graph = PassiveNetwork.sizeJunctionHoldups(chain(warm), model);
        double[] before = species(graph);
        var solver = new PassiveIntervalSolver(model);
        double step = 1;
        PassiveIntervalSolver.Result last = null;
        for (int k = 0; k < 12; k++) {
            last = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, step);
            step = solver.nextStepEstimate();
            graph = last.graph();
            var a = graph.reservoirs().get(0).state();
            var b = graph.reservoirs().get(2);
            System.out.printf(Locale.ROOT, "%s interval %d: warm %.3f K %.3f Pa | cold %.3f K %.3f Pa crystal %.6e mol | substeps %d rejected %d | flows %s%n", label, k + 1,
                    a.temperature(), a.pressure(), b.state().temperature(), b.state().pressure(), crystal(b), last.acceptedSubsteps(), last.rejectedSubsteps(),
                    Arrays.toString(last.averageMassFlows()));
        }
        double[] after = species(graph);
        for (int c = 0; c < before.length; c++) assertEquals(before[c], after[c], 1e-12 * Math.max(1, before[c]), "species " + c + " conserved");
        double pw = graph.reservoirs().get(0).state().pressure(), pc = graph.reservoirs().get(2).state().pressure();
        assertEquals(pw, pc, 1e-6 * pw, "the vessels settle at one pressure");
        for (double flow : last.averageMassFlows()) assertTrue(Math.abs(flow) < 1e-6, "the junction is at rest: " + flow);
        return graph;
    }

    @Test
    void aJunctionBetweenACo2DepositingVesselAndColdNitrogenSettles() {
        var graph = settles(WARM_CO2, "CO2/N2");
        assertTrue(crystal(graph.reservoirs().get(2)) > 0, "the cold vessel deposited CO2");
        assertTrue(graph.reservoirs().get(1).inventory().solids().empty(), "a junction never precipitates (D23 (5))");
    }

    @Test
    void aJunctionBetweenMethaneInNitrogenAndColdNitrogenSettles() {
        settles(WARM_METHANE, "CH4/N2");
    }
}
