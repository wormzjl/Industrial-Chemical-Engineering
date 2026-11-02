package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.NumericalBox;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The stream thermo of a run (batch 2026-11-02-process-simulator, plan section 3.2): the world's own flash, on the network's
 * package, as streams. Run it inside {@code MaterialRuntime.with}. Minecraft-free.
 *
 * <p>A stream is what the world's TP flash says at the temperature that holds its enthalpy, and {@link #atEnthalpy} searches
 * along that flash. The engine's PH answers only on a true step of H(T). Measured: on crude with 20 mole % water it answers
 * free water boiling at pure saturation across 376 to 382 K at 2 bar, 393.36 K with no hydrocarbon vapour, where the TP flash
 * holds both; and on water under its melting line it answers ice, which a network state cannot carry (D23), so its enthalpy
 * misses the target by the latent heat.
 */
public final class FlowThermo implements StreamThermo {
    private static final double GAS_CONSTANT = 8.314462618;
    /** Where the bubble search looks (Pa), and how close it stops. */
    private static final double LOWEST_BUBBLE = 100, HIGHEST_BUBBLE = 2e7, BUBBLE_RATIO = 1.0005;
    /** A trace of vapour by mole counts as boiling. */
    private static final double BOILING = 1e-7;
    /** How close a stream comes to its enthalpy, as a share of its n R T. */
    private static final double ENTHALPY_SHARE = 1e-7;
    /** The walk from the guess to a bracket (K): its first and longest steps, and how far past the line's answer it goes. */
    private static final double FIRST_STEP = 8, LONGEST_STEP = 256, PAST_THE_LINE = 1.2;
    /** A bracket this narrow (K) holds a step of H(T), and the engine's answer on that step stands this close to it. */
    private static final double STEP_WIDTH = 1e-7, ON_THE_STEP = 1e-3;

    private final FluidThermodynamics model;
    private final Runnable checkpoint;

    public FlowThermo(FluidThermodynamics model, Runnable checkpoint) {
        this.model = model;
        this.checkpoint = checkpoint;
    }

    public FluidThermodynamics model() {
        return model;
    }

    public Runnable checkpoint() {
        return checkpoint;
    }

    public List<String> axis() {
        return model.components();
    }

    private FlowStream stream(FluidThermodynamics.State state, double[] amounts) {
        return new FlowStream(state.temperature(), state.pressure(), amounts, state.enthalpy(), state.mass(), state);
    }

    private static double total(double[] amounts) {
        return Arrays.stream(amounts).sum();
    }

    public FlowStream atTemperature(double temperature, double pressure, double[] amounts, SolidInventory solids) {
        if (total(amounts) == 0) return FlowStream.empty(amounts.length);
        return stream(flash(temperature, pressure, amounts, solids), amounts);
    }

    private FluidThermodynamics.State flash(double temperature, double pressure, double[] amounts, SolidInventory solids) {
        return model.flashTP(temperature, pressure, amounts, checkpoint).withSolids(solids);
    }

    /** What the search along the TP flash holds still: the target, how close it counts, and what it flashes. */
    private record Search(double pressure, double enthalpy, double[] amounts, SolidInventory solids, double close) {
        double gap(FluidThermodynamics.State state) {
            return state.enthalpy() - enthalpy;
        }

        boolean found(FluidThermodynamics.State state) {
            return Math.abs(gap(state)) <= close;
        }
    }

    @Override public FlowStream atEnthalpy(double pressure, double enthalpy, double[] amounts, double temperatureGuess, SolidInventory solids) {
        if (total(amounts) == 0) return FlowStream.empty(amounts.length);
        var search = new Search(pressure, enthalpy, amounts, solids, ENTHALPY_SHARE * total(amounts) * GAS_CONSTANT * temperatureGuess);
        try {
            return stream(found(search, flash(temperatureGuess, pressure, amounts, solids)), amounts);
        } catch (CancellationException | FlowsheetFailure ended) {
            throw ended;
        } catch (RuntimeException noAnswer) {
            return stream(engine(search, temperatureGuess), amounts);
        }
    }

    /** Walks out from {@code near} until a flash lands on the target's other side; each step is at least twice the last. */
    private FluidThermodynamics.State found(Search search, FluidThermodynamics.State near) {
        if (search.found(near)) return near;
        double step = search.gap(near) < 0 ? FIRST_STEP : -FIRST_STEP;
        while (true) {
            double temperature = near.temperature() + step;
            if (!NumericalBox.contains(temperature, search.pressure)) throw new FlowsheetFailure("thermo.no_state");
            var far = flash(temperature, search.pressure, search.amounts, search.solids);
            if (search.found(far)) return far;
            if (search.gap(far) * search.gap(near) < 0) return step > 0 ? between(search, near, far) : between(search, far, near);
            double line = search.gap(far) * step / (search.gap(near) - search.gap(far));
            step = Math.copySign(Math.min(LONGEST_STEP, Math.max(2 * Math.abs(step), PAST_THE_LINE * Math.abs(line))), step);
            near = far;
        }
    }

    /** The answer between two flashes: the line and the middle by turns, so a kink costs little and a step ends. */
    private FluidThermodynamics.State between(Search search, FluidThermodynamics.State low, FluidThermodynamics.State high) {
        boolean line = true;
        while (high.temperature() - low.temperature() > STEP_WIDTH) {
            double share = line ? -search.gap(low) / (search.gap(high) - search.gap(low)) : .5;
            var trial = flash(low.temperature() + share * (high.temperature() - low.temperature()), search.pressure, search.amounts, search.solids);
            if (search.found(trial)) return trial;
            if (search.gap(trial) < 0) low = trial;
            else high = trial;
            line = !line;
        }
        var answer = engine(search, low.temperature());
        if (Math.abs(answer.temperature() - low.temperature()) > ON_THE_STEP) throw new FlowsheetFailure("thermo.no_state");
        return answer;
    }

    /**
     * The engine's PH, for what the search cannot reach: no TP state holds the target where H(T) steps, as a pure component
     * boils, and a trial the flash cannot answer is a rejected step, as a gas of steam over a heavy cut, whose hydrocarbon
     * pressure of 1e-6 Pa it cannot close. Its answer must hold the enthalpy.
     */
    private FluidThermodynamics.State engine(Search search, double temperature) {
        double fluid = search.enthalpy - search.solids.moments().enthalpy(temperature, search.pressure);
        var split = model.equilibriumPH(temperature, search.pressure, fluid, search.amounts, checkpoint);
        if (split == null) throw new FlowsheetFailure("thermo.no_state");
        var answer = split.withSolids(search.solids);
        if (!search.found(answer)) throw new FlowsheetFailure("thermo.no_state");
        return answer;
    }

    /** {@code stream} with {@code watts} more heat, at {@code pressure}. */
    public FlowStream heated(FlowStream stream, double watts, double pressure) {
        return atEnthalpy(pressure, stream.enthalpy() + watts, stream.amountsView(), stream.temperature(), stream.solids());
    }

    /** The pressure at which {@code amounts} at {@code temperature} starts to boil (Pa): a bisection on the world's flash. */
    public double bubblePressure(double temperature, double[] amounts) {
        double low = LOWEST_BUBBLE, high = HIGHEST_BUBBLE;
        while (high / low > BUBBLE_RATIO) {
            double middle = Math.sqrt(low * high);
            if (boils(temperature, middle, amounts)) low = middle;
            else high = middle;
        }
        return high;
    }

    /** Whether {@code amounts} at {@code temperature} and {@code pressure} hold vapour. A trial with no state does not boil. */
    public boolean boils(double temperature, double pressure, double[] amounts) {
        try {
            var state = model.flashTP(temperature, pressure, amounts, checkpoint);
            return vapourAmount(state) > BOILING * total(amounts);
        } catch (CancellationException stop) {
            throw stop;
        } catch (RuntimeException noState) {
            return false;
        }
    }

    private static double vapourAmount(FluidThermodynamics.State state) {
        return Arrays.stream(state.vaporView()).sum() + state.waterVapor();
    }

    /** Vapour's share of a stream's moles, 0 to 1. */
    public double vapourShare(FlowStream stream) {
        return stream.state() == null || stream.empty() ? 0 : vapourAmount(stream.state()) / stream.total();
    }

    /** The curve of a stream's amounts at {@code pressure}, across {@code low} to {@code high} K, with the world's films. */
    public HeatCurve curve(FlowStream stream, double pressure, double low, double high) {
        return HeatCurve.sample(model, stream.amountsView(), pressure, low, high, HeatCurve.Films.DEFAULT, checkpoint);
    }

    /**
     * {@code stream}'s phase {@code phase} ({@link FluidThermodynamics#GAS}, {@code OIL} or {@code WATER}) as a stream of its
     * own, with its share of the state's enthalpy, so the phases add up to the whole, even at a pure stream's boiling point,
     * where a flash could land on either side. The liquids carry the solids; with no liquid the gas does.
     */
    public FlowStream phase(FlowStream stream, int phase) {
        var state = stream.state();
        if (state == null) throw new IllegalArgumentException("A phase split needs a flash");
        double[] amounts = FluidThermodynamics.phaseMoles(state, phase);
        if (total(amounts) == 0) return FlowStream.empty(amounts.length);
        boolean carried = phase == FluidThermodynamics.GAS && !(state.liquidVolume() + state.waterVolume() > 0);
        double share = carried ? 1 : FluidThermodynamics.phaseSolidShare(state, phase);
        double mass = model.phaseMass(state, phase), enthalpy = model.phaseSpecificEnthalpy(state, phase, null) * mass;
        var solids = share > 0 ? state.solids().scale(share) : SolidInventory.EMPTY;
        return atEnthalpy(stream.pressure(), carried ? enthalpy + state.solidMoments().enthalpy(state.temperature(), state.pressure()) : enthalpy, amounts, stream.temperature(), solids);
    }

    /** {@code stream}'s oil and free water as one stream, as they leave a kettle over its weir. */
    public FlowStream liquid(FlowStream stream) {
        var oil = phase(stream, FluidThermodynamics.OIL);
        var water = phase(stream, FluidThermodynamics.WATER);
        double[] amounts = oil.amounts();
        for (int i = 0; i < amounts.length; i++) amounts[i] += water.amountsView()[i];
        return atEnthalpy(stream.pressure(), oil.enthalpy() + water.enthalpy(), amounts, stream.temperature(), oil.solids().plus(water.solids()));
    }
}
