package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * What a stream's meters read (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 6), from the state its run left, read only.
 *
 * @param vapour        mole % gas
 * @param moles         mol/s
 * @param mass          kg/s
 * @param liquidVolume  m³/s as liquid at each component's reference density
 * @param density       kg/m³ of the mixture
 * @param liquidMu      Pa s, or NaN with no liquid
 * @param gasMu         Pa s, or NaN with no gas
 * @param weight        kg/kmol
 * @param enthalpy      J/kg
 */
public record StreamProps(double temperature, double pressure, double vapour, double moles, double mass, double liquidVolume, double density, double liquidMu, double gasMu, double weight, double enthalpy, Phases phases) {
    /** Mole % in each phase. */
    public record Phases(double organic, double aqueous, double gas, double solid) {
        public static final Phases NONE = new Phases(0, 0, 0, 0);
    }

    /** The world's package as the screen reads it: its thermo, its list, a component's display name. */
    /** @param packName the package as a player reads it */
    public record Fluids(FluidThermodynamics model, MaterialCatalog.Package pack, Function<String, String> names, String packName) {
        public List<String> axis() {
            return model.components();
        }
    }

    /** A component's share of a stream. */
    public record Part(String name, double percent, double moles, double mass) {
    }

    private static final double WATER_WEIGHT = .018015, WATER_DENSITY = 998;

    public static StreamProps of(FlowStream stream, Fluids fluids) {
        double[] n = stream.amounts();
        double all = stream.total(), volume = 0;
        var properties = fluids.pack().properties();
        for (int i = 0; i + 1 < n.length && i < properties.size(); i++) volume += n[i] * properties.get(i).molecularWeight() / properties.get(i).density();
        var s = stream.state();
        if (s == null) {
            volume += n.length == 0 ? 0 : n[n.length - 1] * WATER_WEIGHT / WATER_DENSITY;
            return new StreamProps(stream.temperature(), stream.pressure(), 0, all, stream.mass(), volume, Double.NaN, Double.NaN, Double.NaN, all == 0 ? 0 : stream.mass() / all * 1000, stream.mass() == 0 ? 0 : stream.enthalpy() / stream.mass(), Phases.NONE);
        }
        double vapour = Arrays.stream(s.vaporView()).sum() + s.waterVapor(), oil = Arrays.stream(s.liquidView()).sum();
        volume += (s.waterLiquid() + s.waterVapor()) * WATER_WEIGHT / WATER_DENSITY;
        double mu = oil > 0 ? fluids.model().viscosity.liquid(s.temperature(), s.liquidView()).pascalSeconds() : s.waterLiquid() > 0 ? fluids.model().viscosity.waterLiquid(s.temperature()) : Double.NaN;
        double gas = vapour > 0 ? fluids.model().viscosity.vapor(s.temperature(), s.vaporView(), s.waterVapor()) : Double.NaN;
        double total = Math.max(all, Double.MIN_NORMAL);
        var phases = new Phases(100 * oil / total, 100 * s.waterLiquid() / total, 100 * vapour / total, 0);
        return new StreamProps(s.temperature(), s.pressure(), 100 * vapour / total, all, s.mass(), volume, s.volume() > 0 ? s.mass() / s.volume() : Double.NaN, mu, gas, all == 0 ? 0 : s.mass() / all * 1000, s.mass() == 0 ? 0 : s.enthalpy() / s.mass(), phases);
    }

    /** The parts a stream holds, by {@code phase}: 0 organic, 1 aqueous, 2 gas, 3 solid (none), any other the whole stream. */
    public static List<Part> parts(@Nullable FlowStream stream, Fluids fluids, int phase) {
        var parts = new ArrayList<Part>();
        if (stream == null) return parts;
        double[] amounts = amounts(stream, phase);
        double total = Arrays.stream(amounts).sum();
        var axis = fluids.axis();
        var properties = fluids.pack().properties();
        for (int i = 0; i < amounts.length && i < axis.size(); i++) {
            if (amounts[i] <= 0) continue;
            double weight = i < properties.size() ? properties.get(i).molecularWeight() : WATER_WEIGHT;
            parts.add(new Part(fluids.names().apply(axis.get(i)), 100 * amounts[i] / total, amounts[i], amounts[i] * weight));
        }
        return parts;
    }

    private static double[] amounts(FlowStream stream, int phase) {
        var s = stream.state();
        double[] whole = stream.amounts();
        if (s == null || phase < 0 || phase > 3) return whole;
        double[] out = new double[whole.length];
        int water = out.length - 1;
        switch (phase) {
            case 0 -> System.arraycopy(s.liquidView(), 0, out, 0, Math.min(water, s.liquidView().length));
            case 1 -> out[water] = s.waterLiquid();
            case 2 -> {
                System.arraycopy(s.vaporView(), 0, out, 0, Math.min(water, s.vaporView().length));
                out[water] = s.waterVapor();
            }
            default -> {
            }
        }
        return out;
    }

    /** The phase a stream's page opens on: the first it holds. */
    public static int firstPhase(StreamProps props) {
        var p = props.phases();
        return p.organic() > 0 ? 0 : p.aqueous() > 0 ? 1 : p.gas() > 0 ? 2 : 0;
    }
}
