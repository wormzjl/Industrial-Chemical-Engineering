package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * A stream in a run (batch 2026-11-02-process-simulator, plan section 3.1): its temperature (K), pressure (Pa), amounts
 * (mol/s on the flowsheet's one axis, water last), enthalpy (W, on the network's datum) and mass (kg/s), and the phases the
 * stream thermo found, which a stub law leaves null. Solids ride in those phases, per second, and the enthalpy and the mass
 * count them. A stream holds its own array. Minecraft-free.
 *
 * <p>An {@linkplain #empty empty} stream is what a loop's first pass reads from a tear it does not know yet. No flow in means
 * no flow out, so every law passes it on, and an exchanger with an empty side moves no heat.
 */
public record FlowStream(double temperature, double pressure, double[] amounts, double enthalpy, double mass, FluidThermodynamics.@Nullable State state) {
    private static final double REFERENCE_TEMPERATURE = 298.15, REFERENCE_PRESSURE = 101325;

    public FlowStream {
        amounts = amounts.clone();
        for (double amount : amounts) if (!(amount >= 0) || !Double.isFinite(amount)) throw new IllegalArgumentException("Invalid stream amounts");
        if (!(temperature > 0) || !(pressure > 0) || !Double.isFinite(temperature + pressure + enthalpy + mass)) throw new IllegalArgumentException("Invalid stream state");
    }

    /** No flow, on an axis of {@code components}. */
    public static FlowStream empty(int components) {
        return new FlowStream(REFERENCE_TEMPERATURE, REFERENCE_PRESSURE, new double[components], 0, 0, null);
    }

    @Override public double[] amounts() {
        return amounts.clone();
    }

    /** The amounts themselves, for this package's laws. The caller must not change them. */
    public double[] amountsView() {
        return amounts;
    }

    public double total() {
        return Arrays.stream(amounts).sum();
    }

    public boolean empty() {
        return total() == 0;
    }

    /** The solids it carries, per second. */
    public SolidInventory solids() {
        return state == null ? SolidInventory.EMPTY : state.solids();
    }

    /** Enthalpy per mole (J/mol); 0 for an empty stream. */
    public double molarEnthalpy() {
        double total = total();
        return total == 0 ? 0 : enthalpy / total;
    }

    @Override public String toString() {
        return "FlowStream[T=" + temperature + ", P=" + pressure + ", amounts=" + Arrays.toString(amounts) + ", H=" + enthalpy + ", m=" + mass + "]";
    }
}
