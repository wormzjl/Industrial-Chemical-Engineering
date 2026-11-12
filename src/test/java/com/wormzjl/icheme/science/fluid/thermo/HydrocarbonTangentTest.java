package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The first-order model of one phase ({@link HydrocarbonModel#tangent}, ANALYTIC_JACOBIAN_PLAN.md of batch
 * 2026-11-11-aqueous-solvents, tests 1 and 2): its derivatives are consistent with each other at the base point (the
 * Euler sums), and the phase it gives beside the base differs from the exact phase by the square of the step.
 */
class HydrocarbonTangentTest {
    private static final double R = 8.31446261815324;

    private record Case(String name, HydrocarbonModel model, double t, double p, Root root, double[] amounts) {}

    private static double[] amounts(HydrocarbonModel model, Object... pairs) {
        double[] n = new double[model.componentCount() + 1];
        for (int k = 0; k < pairs.length; k += 2) {
            String id = (String) pairs[k];
            int at = id.equals("Water") ? model.componentCount() : model.components().indexOf(id);
            if (at < 0) throw new IllegalArgumentException(id);
            n[at] = ((Number) pairs[k + 1]).doubleValue();
        }
        return n;
    }

    private static List<Case> cases() {
        var refinery = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), FluidMaterialCatalog.NETWORK_PACKAGE).hydrocarbon;
        var polar = FluidThermodynamics.forNetwork(PilotAqueousTestCatalog.catalog(), PilotAqueousTestCatalog.PACKAGE_ID).hydrocarbon;
        var spine = new HydrocarbonModel(PilotCryogenicTestCatalog.withPilot(), PilotCryogenicTestCatalog.PACKAGE_ID);
        assertTrue(refinery.wet() && polar.wet() && !spine.wet());
        double[] cryogenic = new double[spine.componentCount() + 1];
        cryogenic[0] = 3.0; cryogenic[1] = 24.0; cryogenic[2] = 2.4; cryogenic[3] = 0.6;
        var list = new ArrayList<Case>();
        list.add(new Case("dry vapour", refinery, 350, 5e5, Root.VAPOR, amounts(refinery, "Methane", 0.6, "Propylene", 0.2, "N-hexane", 0.1, "Nitrogen", 0.1)));
        list.add(new Case("dry liquid", refinery, 320, 5e5, Root.LIQUID, amounts(refinery, "N-hexane", 18.5, "Benzene", 11.1, "Toluene", 7.4)));
        list.add(new Case("wet vapour", refinery, 420, 3e5, Root.VAPOR, amounts(refinery, "Methane", 0.5, "N-hexane", 0.1, "Water", 0.4)));
        list.add(new Case("aqueous liquid", refinery, 320, 5e5, Root.LIQUID, amounts(refinery, "Water", 99.9, "CarbonDioxide", 0.05, "Benzene", 0.05)));
        list.add(new Case("oil with water", refinery, 400, 1e6, Root.LIQUID, amounts(refinery, "Toluene", 0.98, "Water", 0.02)));
        list.add(new Case("polar liquid", polar, 330, 2e5, Root.LIQUID, amounts(polar, "Water", 0.4, "Ethanol", 0.5, "N-hexane", 0.1)));
        list.add(new Case("polar vapour", polar, 400, 1e5, Root.VAPOR, amounts(polar, "Water", 0.4, "Ethanol", 0.5, "N-hexane", 0.1)));
        list.add(new Case("near-critical", refinery, 215, 55e5, Root.VAPOR, amounts(refinery, "Methane", 0.9, "Propylene", 0.1)));
        list.add(new Case("spine liquid (dry model)", spine, 120, 2e6, Root.LIQUID, cryogenic));
        return list;
    }

    private static int kernelCount(HydrocarbonModel model) { return model.componentCount() + (model.wet() ? 1 : 0); }

    /** Test 1: the Euler sums at the base, to 1e-10, and the base point is the exact phase bit for bit. */
    @Test void theDerivativesAreConsistentAtTheBase() {
        for (Case c : cases()) {
            var model = c.model();
            var tangent = model.tangent(c.t(), c.p(), c.amounts(), c.root(), model.prepare(c.t()), false);
            assertNotNull(tangent, c.name());
            var exact = model.phase(c.t(), c.p(), c.amounts(), c.root());
            int n = kernelCount(model);
            double total = 0;
            for (int i = 0; i < n; i++) total += c.amounts()[i];
            double volume = 0, enthalpy = 0;
            for (int i = 0; i < n; i++) {
                double x = c.amounts()[i] / total;
                volume += x * tangent.partialMolarVolume(i);
                enthalpy += x * tangent.partialMolarEnthalpy(i);
            }
            assertEquals(tangent.kernelVolume(), volume, 1e-10 * tangent.kernelVolume(), c.name() + ": sum x_i vbar_i = v");
            assertEquals(tangent.kernelEnthalpy(), enthalpy, 1e-10 * Math.max(Math.abs(tangent.kernelEnthalpy()), R * c.t()), c.name() + ": sum x_i hbar_i = h");
            for (int i = 0; i < n; i++) {
                double sum = 0;
                for (int j = 0; j < n; j++) sum += c.amounts()[j] * tangent.logFugacityAmountDerivative(i, j);
                assertEquals(0.0, sum, 1e-10, c.name() + ": sum_j n_j d ln phi_" + i + " / d n_j = 0");
            }
            // The closed form whose difference carries dv/dP beside the base is the base's own dv/dP there.
            assertEquals(tangent.kernelPressureSlope(), tangent.closedPressureSlope(), 1e-12 * Math.abs(tangent.kernelPressureSlope()), c.name() + ": dv/dP");
            // The base point: the exact phase, with water's share where the phase holds water.
            var same = tangent.at(c.t(), c.p(), c.amounts());
            for (var phase : List.of(tangent.base(), same)) {
                assertEquals(Double.doubleToLongBits(exact.molarVolume()), Double.doubleToLongBits(phase.molarVolume()), c.name());
                assertEquals(Double.doubleToLongBits(exact.molarEnthalpy()), Double.doubleToLongBits(phase.molarEnthalpy()), c.name());
                assertArrayEquals(exact.logFugacityView(), phase.logFugacityView(), 0.0, c.name());
                assertEquals(exact.volumePressureDerivative(), phase.volumePressureDerivative(), 0.0, c.name());
                assertEquals(exact.vaporLike(), phase.vaporLike(), c.name());
            }
            // More of the same phase is the same phase: the extensive derivatives are homogeneous, share included.
            double[] more = c.amounts().clone();
            for (int i = 0; i < more.length; i++) more[i] *= 1.5;
            var scaled = tangent.at(c.t(), c.p(), more);
            assertEquals(exact.molarVolume(), scaled.molarVolume(), 1e-10 * exact.molarVolume(), c.name());
            assertEquals(exact.molarEnthalpy(), scaled.molarEnthalpy(), 1e-10 * Math.max(Math.abs(exact.molarEnthalpy()), R * c.t()), c.name());
            for (int i = 0; i < n; i++) assertEquals(exact.logFugacityView()[i], scaled.logFugacityView()[i], 1e-10, c.name());
            assertEquals(0.0, tangent.compositionShift(more), 1e-15, c.name());
        }
    }

    /**
     * Section 3.2: a node's state from its tangents is the base state at the base point, the exact state to first order
     * beside it, and takes a phase from the equation of state where the point moves its composition by more than
     * {@link FluidThermodynamics#TANGENT_COMPOSITION_STEP}.
     */
    @Test void aStateFromTangentsIsTheBaseStateAndFirstOrderBesideIt() {
        var model = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), FluidMaterialCatalog.NETWORK_PACKAGE);
        double[] feed = amounts(model.hydrocarbon, "Methane", 2.0, "N-hexane", 3.0, "Toluene", 1.0, "Water", 4.0);
        var base = model.flashTP(320, 5e5, feed, () -> { });
        assertTrue(base.vaporPhase() != null && base.liquidPhase() != null && base.aqueousPhase() != null, "a gas and two liquids");
        double t = base.temperature(), p = base.pressure();
        var prepared = model.prepare(t);
        var tangents = model.tangents(base, prepared);
        for (int slot = 0; slot < 3; slot++) assertNotNull(tangents[slot]);
        var same = model.adoptingState(t, p, base.vaporAmounts(), base.liquidAmounts(), base.aqueousAmounts(), prepared, tangents);
        var exact = model.state(t, p, base.vaporAmounts(), base.liquidAmounts(), base.aqueousAmounts(), prepared);
        for (double[] pair : new double[][] {{exact.volume(), same.volume()}, {exact.enthalpy(), same.enthalpy()}, {exact.internalEnergy(), same.internalEnergy()},
                {exact.mass(), same.mass()}, {exact.vaporVolume(), same.vaporVolume()}, {exact.liquidSlotVolume(), same.liquidSlotVolume()},
                {exact.aqueousSlotVolume(), same.aqueousSlotVolume()}})
            assertEquals(Double.doubleToLongBits(pair[0]), Double.doubleToLongBits(pair[1]));
        for (int slot = 0; slot < 3; slot++) assertArrayEquals(exact.phase(slot).logFugacityView(), same.phase(slot).logFugacityView(), 0.0);
        // A small step: 1e-7 of the temperature and the pressure, and 1e-7 of the hexane moved from the oil to the gas.
        int hexane = model.components().indexOf("N-hexane");
        double[] vapor = base.vaporAmounts(), liquid = base.liquidAmounts(), aqueous = base.aqueousAmounts();
        double moved = 1e-7 * liquid[hexane];
        vapor[hexane] += moved;
        liquid[hexane] -= moved;
        double t1 = t * (1 + 1e-7), p1 = p * (1 + 1e-7);
        var near = model.adoptingState(t1, p1, vapor.clone(), liquid.clone(), aqueous.clone(), model.prepare(t1), tangents);
        var there = model.state(t1, p1, vapor, liquid, aqueous);
        assertEquals(there.volume(), near.volume(), 1e-11 * there.volume());
        assertEquals(there.enthalpy(), near.enthalpy(), 1e-11 * Math.abs(there.enthalpy()));
        for (int slot = 0; slot < 3; slot++)
            for (int i = 0; i < model.componentCount(); i++) assertEquals(there.phase(slot).logFugacityView()[i], near.phase(slot).logFugacityView()[i], 1e-9, slot + " " + i);
        // A step that is not small for the gas (three tenths of its methane leave): the gas is the equation of state's.
        int methane = model.components().indexOf("Methane");
        double[] thin = base.vaporAmounts();
        thin[methane] *= 0.7;
        assertTrue(tangents[FluidThermodynamics.State.VAPOR].compositionShift(thin) > FluidThermodynamics.TANGENT_COMPOSITION_STEP);
        var far = model.adoptingState(t, p, thin.clone(), base.liquidAmounts(), base.aqueousAmounts(), prepared, tangents);
        var farExact = model.state(t, p, thin, base.liquidAmounts(), base.aqueousAmounts(), prepared);
        assertEquals(Double.doubleToLongBits(farExact.vaporVolume()), Double.doubleToLongBits(far.vaporVolume()));
        assertEquals(Double.doubleToLongBits(exact.liquidSlotVolume()), Double.doubleToLongBits(far.liquidSlotVolume()), "the liquids did not move");
    }

    /** Test 2: beside the base, in temperature, pressure and every amount, the error falls with the square of the step. */
    @Test void theErrorFallsWithTheSquareOfTheStep() {
        double[] steps = {1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9};
        double floor = 1e-11;
        var table = new StringBuilder();
        for (Case c : cases()) {
            var model = c.model();
            var tangent = model.tangent(c.t(), c.p(), c.amounts(), c.root(), model.prepare(c.t()), false);
            int n = kernelCount(model);
            double total = 0;
            for (int i = 0; i < n; i++) total += c.amounts()[i];
            double[] worst = new double[steps.length];
            for (int direction = -2; direction < n; direction++) {
                double[] errors = new double[steps.length];
                for (int s = 0; s < steps.length; s++) {
                    double t = c.t(), p = c.p();
                    double[] amounts = c.amounts().clone();
                    if (direction == -2) t += steps[s] * t;
                    else if (direction == -1) p += steps[s] * p;
                    else amounts[direction] += steps[s] * total;
                    var exact = model.phase(t, p, amounts, c.root());
                    var near = tangent.at(t, p, amounts);
                    double error = Math.abs(near.molarVolume() - exact.molarVolume()) / exact.molarVolume();
                    error = Math.max(error, Math.abs(near.molarEnthalpy() - exact.molarEnthalpy()) / Math.max(Math.abs(exact.molarEnthalpy()), R * t));
                    for (int i = 0; i < n; i++) error = Math.max(error, Math.abs(near.logFugacityView()[i] - exact.logFugacityView()[i]));
                    error = Math.max(error, Math.abs(near.volumePressureDerivative() - exact.volumePressureDerivative()) / Math.abs(exact.volumePressureDerivative()));
                    errors[s] = error;
                    worst[s] = Math.max(worst[s], error);
                }
                String at = c.name() + ", direction " + (direction == -2 ? "T" : direction == -1 ? "P" : "n_" + direction) + ": " + java.util.Arrays.toString(errors);
                // The size is the phase's curvature, not the tangent's, so only the order is asserted: a heavy cut in the
                // aqueous liquid bends ln phi by 115 at a step of 1e-4 in its mole fraction (the local-composition sums
                // saturate near x = 1/G) and by 5.6e-4 at 1e-7, where the square law has only just set in. Every step
                // falls; the smallest pair falls as the square.
                for (int s = 1; s < steps.length; s++) assertTrue(errors[s] <= floor || errors[s] <= errors[s - 1] / 5.0, at);
                int last = steps.length - 1;
                assertTrue(errors[last] <= floor || errors[last] <= errors[last - 1] / 30.0, at);
            }
            table.append(String.format(Locale.ROOT, "TANGENT_ERROR %-26s %.2e %.2e %.2e %.2e %.2e %.2e%n", c.name(), worst[0], worst[1], worst[2], worst[3], worst[4], worst[5]));
        }
        System.out.print(table);
    }
}
