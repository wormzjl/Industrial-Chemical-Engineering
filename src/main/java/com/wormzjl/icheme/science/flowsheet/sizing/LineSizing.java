package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.NetworkReduction;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.fluid.transport.SolidMobility;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A line's bore and runs (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.3 and 3.5). {@link #rate} reads any
 * size against the line's limits, and {@link #auto} searches the usual sizes with it. Every drop is the world's {@link PipeResistance}
 * on the stream as the world's bulk end carries it, at its own density and {@link NetworkReduction#viscosity}.
 */
public final class LineSizing {
    /** The bores Auto offers, m: the pipe tool takes any, these are the usual sizes, NPS 1 to 32 near enough. */
    private static final double[] BORES = {.025, .05, .075, .1, .15, .2, .25, .3, .35, .4, .45, .5, .6, .7, .8};
    /** The most parallel runs Auto offers. */
    private static final int MOST_RUNS = 4;
    private static final List<Size> SIZES = sizes();
    /** The length a drop limit in bar per 100 m reads, m. */
    private static final double HUNDRED = 100;

    private LineSizing() {
    }

    private static List<Size> sizes() {
        var sizes = new ArrayList<Size>();
        for (int runs = 1; runs <= MOST_RUNS; runs++) for (double bore : BORES) sizes.add(new Size(bore, runs));
        return List.copyOf(sizes);
    }

    /**
     * A size of line.
     *
     * @param bore m, each run's
     * @param runs parallel runs, which share the flow evenly
     */
    public record Size(double bore, int runs) {
        public Size {
            if (!(bore > 0) || !Double.isFinite(bore) || runs < 1) throw new IllegalArgumentException("Invalid line size");
        }
    }

    /**
     * A stream as the world's bulk end carries it at design flow. Solids ride the liquid, so a gas with no liquid leaves them behind.
     *
     * @param mass       kg/s
     * @param volume     m³/s
     * @param density    kg/m³
     * @param viscosity  Pa s
     * @param speedLimit the world's law at this state, m/s
     * @param solids     what it asks of a line, null with none aboard
     */
    record Carried(double mass, double volume, double density, double viscosity, double speedLimit, @Nullable Solids solids) {
        /** {@code stream}, which must flow, at {@code flow} times its steady rate. */
        static Carried of(FlowStream stream, double flow, FlowThermo thermo, MixtureViscosity.Workspace scratch) {
            var state = stream.state();
            var model = thermo.model();
            boolean dry = FluidThermodynamics.bulkDrawsGas(state);
            double mass = dry ? model.vaporMass(state) : state.mass(), volume = dry ? state.vaporVolume() : state.volume();
            double limit = dry ? model.phaseVelocityLimit(state, FluidThermodynamics.GAS) : model.velocityLimit(state);
            var solids = dry || state.solids().empty() ? null : new Solids(SolidMobility.donor(model, state), state);
            return new Carried(mass * flow, volume * flow, mass / volume, NetworkReduction.viscosity(model, state, scratch), limit, solids);
        }

        /** What it loses through {@code passage}, bar, split evenly over {@code ways} of them. */
        double drop(PipeResistance.Geometry passage, int ways) {
            return PipeResistance.pressureDrop(passage, mass / ways, density, viscosity) / 1e5;
        }
    }

    /**
     * What a line's solids ask of it: {@link SolidMobility}'s donor, on the stream it was prepared from.
     *
     * @param state its steady flow's state
     */
    public record Solids(SolidMobility.Donor donor, FluidThermodynamics.State state) {
    }

    /**
     * A line at design flow.
     *
     * @param service    null with no flow
     * @param mass       kg/s
     * @param volume     m³/s
     * @param density    kg/m³
     * @param viscosity  Pa s
     * @param pressure   Pa
     * @param length     m
     * @param nozzle     the largest bore its nozzles take, m
     * @param speedLimit the world's law at its state, m/s
     * @param solids     null with none aboard, so no reading
     */
    public record Line(@Nullable Service service, double mass, double volume, double density, double viscosity, double pressure, double length, double nozzle, double speedLimit, @Nullable Solids solids) {
        /** A line with no flow: no size, no lamp. */
        public static final Line NONE = new Line(null, 0, 0, 0, 0, 0, 0, 0, 0, null);

        public boolean flows() {
            return mass > 0;
        }

        /**
         * {@code stream} at {@code basis}'s design flow. A line with no flow, or no service to read it by, is {@link #NONE}.
         *
         * @param nozzle  what its ends take (m); with no nozzle to say, {@link PipeSizes#MAX_BORE}
         * @param length  its own length (m), null for the sheet's
         * @param scratch the caller's: this runs on the run's thread
         */
        public static Line of(FlowStream stream, @Nullable Service service, DesignBasis basis, FlowThermo thermo, double nozzle, @Nullable Double length, MixtureViscosity.Workspace scratch) {
            if (stream.state() == null || stream.empty() || service == null) return NONE;
            var carried = Carried.of(stream, basis.designFlow(), thermo, scratch);
            return new Line(service, carried.mass(), carried.volume(), carried.density(), carried.viscosity(), stream.pressure(), basis.length(length), nozzle, carried.speedLimit(), carried.solids());
        }

        /** The key a row of this line's stands at, for {@code bore} m. */
        private double key(Limit limit, double bore) {
            return switch (limit.bound().axis()) {
                case BORE -> bore;
                case GAUGE -> (pressure - PhaseLayout.AMBIENT_PRESSURE) / 1e5;
                case ABSOLUTE -> pressure / 1e5;
                default -> 0;
            };
        }

        private Reading read(Limit limit, double value, double bore) {
            return limit.read(value, key(limit, bore));
        }
    }

    /** What {@code line} loses over {@code metres} at {@code size}, bar. A bore the wall's roughness fills passes nothing. */
    public static double drop(Line line, Size size, double metres) {
        if (size.bore() <= PipeResistance.DEFAULT_ROUGHNESS_METRES) return Double.POSITIVE_INFINITY;
        return PipeResistance.pressureDrop(new PipeResistance.Geometry(metres, size.bore(), PipeResistance.DEFAULT_ROUGHNESS_METRES, 0, size.runs()), line.mass(), line.density(), line.viscosity()) / 1e5;
    }

    /** The world's own check of {@code line}'s solids at {@code size}, null with none aboard. Its reason says what a red row means. */
    public static @Nullable SolidMobility.Check mobility(Line line, Size size) {
        var solids = line.solids();
        if (solids == null) return null;
        var pipe = new PassiveNetwork.Pipe(0, 0, 1, List.of(new PipeResistance.Geometry(1, size.bore(), 0, 0, size.runs())), new FlowControl.Passive());
        return SolidMobility.check(solids.donor(), solids.state(), pipe, line.mass(), SolidMobility.Outlet.MIXED, solids.state().mass());
    }

    /** {@code check} as a reading, on the speed where speed decides it. What it refuses at any speed stands past any limit. */
    private static Reading solids(SolidMobility.Check check) {
        return switch (check.reason()) {
            case MOBILE, DEPOSITION -> Limit.LINE_SOLIDS.against(check.velocity(), check.minimumVelocity());
            default -> Limit.LINE_SOLIDS.against(check.velocity(), Double.POSITIVE_INFINITY);
        };
    }

    /** Every limit of {@code line} read at {@code size}, any bore and any count; nothing to read with no flow. */
    public static Rating rate(Line line, Size size) {
        var service = line.service();
        if (!line.flows() || service == null) return Rating.NONE;
        double bore = size.bore(), speed = line.volume() / (size.runs() * Math.PI * bore * bore / 4);
        var readings = new ArrayList<Reading>();
        readings.add(Limit.LINE_BORE.read(bore));
        readings.add(Limit.LINE_SPEED.against(speed, line.speedLimit()));
        readings.add(Limit.LINE_NOZZLE.against(bore, line.nozzle()));
        readings.add(Limit.LINE_RUNS.read(size.runs()));
        var check = mobility(line, size);
        if (check != null) readings.add(solids(check));
        readings.add(line.read(service.speed(), speed, bore));
        var drop = service.drop();
        if (drop == Limit.VACUUM_DROP) readings.add(line.read(drop, 100 * drop(line, size, line.length()) * 1e5 / line.pressure(), bore));
        else if (drop != null) readings.add(line.read(drop, drop(line, size, HUNDRED), bore));
        var momentum = service.momentum();
        if (momentum != null) readings.add(line.read(momentum, line.density() * speed * speed, bore));
        return new Rating(readings);
    }

    /** The smallest usual size that meets every limit, by {@link Selection}: bore first, then parallel runs. No flow, no size. */
    public static Selection.Choice<Size> auto(Line line) {
        if (!line.flows()) return Selection.Choice.none();
        return Selection.smallest(SIZES, size -> rate(line, size));
    }
}
