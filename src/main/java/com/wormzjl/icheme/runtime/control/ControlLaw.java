package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;

import java.util.*;

/**
 * One local controller's decision at a cadence: the valve law, the heat outlet target, a batch
 * still's firing or reflux, or a diverter valve's outlet it asks for, or the status that says why
 * it asks for none. A level controller remembers its last inventory sample between cadences, and a
 * still's its last correction.
 */
final class ControlLaw {
    /**
     * A controller's result this cadence: its status and the setpoint it asks for, if any: a valve
     * law, a heat outlet target, a still's firing (0 to 1) or reflux ratio (+Infinity closed), or a
     * diverter valve's outlet (0 to 2).
     */
    record Decision(
            String status,
            FlowControl.ControlValve valve,
            Double heat,
            Double firing,
            Double reflux,
            Integer outlet,
            Double feed,
            long unit) {
        static Decision noSetpoint(String status) {
            return new Decision(status, null, null, null, null, null, null, 0);
        }

        static Decision valveLaw(FlowControl.ControlValve law) {
            return new Decision("CONTROLLING", law, null, null, null, null, null, 0);
        }

        static Decision outletTarget(double target) {
            return new Decision("CONTROLLING", null, target, null, null, null, null, 0);
        }

        static Decision stillFiring(double firing) {
            return new Decision("CONTROLLING", null, null, firing, null, null, null, 0);
        }

        static Decision stillReflux(double reflux) {
            return new Decision("CONTROLLING", null, null, null, reflux, null, null, 0);
        }

        static Decision divert(int outlet) {
            return new Decision("CONTROLLING", null, null, null, null, outlet, null, 0);
        }

        /** A column loop's planned feed rate for column unit {@code unit}, mol/s (W6.3). */
        static Decision columnFeed(long unit, double feed) {
            return new Decision("CONTROLLING", null, null, null, null, null, feed, unit);
        }

        /** Whether it asks for a setpoint. */
        boolean sets() {
            return valve != null || heat != null || firing != null || reflux != null || outlet != null || feed != null;
        }
    }

    private record LevelSample(UUID sensor, long output, long tick, double volume) {}

    /** A still controller's last correction: its pot, handle and target, the reading's tick and the setpoint. */
    private record StillSample(long output, boolean firing, double target, long tick, double value) {}

    /** Below this, kg/s, a still is taken to give no distillate. */
    private static final double FLOWING = 1e-9;

    private final FluidWorldAuthority world;
    private final ControlHosts hosts;
    private final Map<UUID, LevelSample> levels = new HashMap<>();
    private final Map<UUID, StillSample> stills = new HashMap<>();

    ControlLaw(FluidWorldAuthority world, ControlHosts hosts) {
        this.world = world;
        this.hosts = hosts;
    }

    /**
     * Forgets a controller's level sample: its sensor, output, action or switch changed, or it was
     * removed.
     */
    void forget(UUID panel) {
        levels.remove(panel);
        stills.remove(panel);
    }

    Decision decide(ControlFacts.Panel panel, ControlInput sensor, Sensor.Reading reading) {
        var s = panel.settings();
        // A column loop (W6.3): its output is a column's host or a running column's feed boundary valve. It plans the feed
        // rate at the cadence (its sensor may stand on another island), before the valve's own checks.
        long unit = world.columnLoop(s.output(), hosts.columnMaster(s.output()));
        if (unit != 0) return column(panel, sensor, reading, unit);
        var record = world.registrations().get(s.output());
        if (sensor == null || record == null) return Decision.noSetpoint("MISSING DEVICE");
        if (world.controlReserved(s.output())) return Decision.noSetpoint("OUTPUT RESERVED");
        if (!hosts.wired(panel.id(), sensor.key().host()) || !hosts.wired(panel.id(), s.output()))
            return Decision.noSetpoint("NO DATA LINK");
        // A still's firing and reflux and a diverter valve's outlet (batch 2026-11-09-batch-distiller,
        // owner decisions O1 and O2) act on the reading at the cadence, not inside the solve: their
        // sensor may be on another island (a cut's head temperature switches its line's diverter).
        var device = record.device();
        boolean cadenced =
                device.stillPot() || device.kind() == TopologyCompiler.Kind.DIVERTER_VALVE;
        if (!cadenced && !world.controlCoupled(sensor.source(), s.output()))
            return Decision.noSetpoint("NOT SAME PROCESS");
        if (reading == null || reading.availability() != Instrument.Availability.AVAILABLE)
            return Decision.noSetpoint("NO SIGNAL");
        // An alarm is read and shown, never regulated on (batch 2026-11-09-furnace-fuel-power).
        if (sensor.kind() == Sensor.Kind.ALARM) return Decision.noSetpoint("SELECT A MEASUREMENT");
        if (sensor.physical() == null
                && s.unit() == Sensor.Unit.CUBIC_METRE_PER_SECOND
                && !BuiltInInputs.volumeAvailable(sensor))
            return Decision.noSetpoint("UNIT UNAVAILABLE");
        if (s.unit() != reading.unit()
                && reading.secondaryUnit().filter(u -> u == s.unit()).isEmpty())
            return Decision.noSetpoint("UNIT MISMATCH");
        if (device.stillPot()) return still(panel, sensor, reading);
        // A diverter valve: its straight outlet while the reading stands below the target, its first
        // side outlet at or above it. Two in a line give three cuts.
        if (device.kind() == TopologyCompiler.Kind.DIVERTER_VALVE)
            return Decision.divert(measured(s, reading) < s.target() ? 0 : 1);
        if (sensor.kind() == Sensor.Kind.TEMPERATURE) {
            // Native outlet control already solves bypass/fans/firing against the finite heat
            // curves.
            if (record.device().heat() == null
                    || !record.device().heat().controllable()
                    || sensor.source() != s.output()
                    || sensor.physical() == null && !sensor.role().endsWith("OUTLET"))
                return Decision.noSetpoint("SELECT HEAT OUTLET");
            return Decision.outletTarget(s.target());
        }
        if (!(record.device().control() instanceof FlowControl.ControlValve law))
            return Decision.noSetpoint("SELECT CONTROL VALVE");
        if (sensor.kind() == Sensor.Kind.LEVEL || sensor.kind() == Sensor.Kind.PRESSURE) {
            var action = world.controlAction(sensor.key().host(), s.output());
            if (action != null && action != s.action())
                return Decision.noSetpoint("CHECK FLOW DIRECTION");
        }
        var source = FlowControl.ValveSource.MASS_FLOW;
        double target = s.target();
        switch (sensor.kind()) {
            case FLOW -> {
                source =
                        s.unit() == Sensor.Unit.CUBIC_METRE_PER_SECOND
                                ? FlowControl.ValveSource.VOLUME_FLOW
                                : FlowControl.ValveSource.MASS_FLOW;
                var output = world.instrumentPipeReading(s.output());
                if (reading.value() < -1e-10) return Decision.noSetpoint("REVERSE FLOW");
                if (output != null && reading.value() > 1e-10 && output.massFlow() > 1e-10) {
                    double measured =
                            s.unit() == Sensor.Unit.CUBIC_METRE_PER_SECOND
                                    ? reading.secondary()
                                    : reading.value();
                    if (measured > 1e-12) {
                        source = FlowControl.ValveSource.MASS_FLOW;
                        target = s.target() * output.massFlow() / measured;
                    }
                }
            }
            case PRESSURE -> {
                if (s.action() != ControlSettings.Action.OUTLET)
                    return Decision.noSetpoint("PRESSURE: OUTLET ACTION");
                var local = world.instrumentPipeReading(s.output());
                if (local == null) return Decision.noSetpoint("NO SIGNAL");
                source = FlowControl.ValveSource.PRESSURE;
                // The native valve holds its upstream node. Correct the intervening line's measured
                // pressure loss.
                target = Math.max(1, local.pressure() + s.target() - reading.value());
            }
            case LEVEL -> {
                var view = world.view(sensor.key().host());
                var state = view.state();
                var tank = view.tank();
                var output = world.instrumentPipeReading(s.output());
                if (state == null
                        || tank == null
                        || output == null
                        || output.volumeFlow() == null
                        || sensor.key().mount().equals("level_boot"))
                    return Decision.noSetpoint("NO LEVEL BALANCE");
                double height =
                        s.unit() == Sensor.Unit.HEIGHT_PERCENT
                                ? s.target() * reading.span() / 100
                                : s.target();
                if (height > reading.span()) return Decision.noSetpoint("TARGET OUT OF RANGE");
                double volume =
                        state.phaseVolumes()[0] + state.phaseVolumes()[1] + state.solids().volume();
                double desired = tank.geometry().volumeAt(height),
                        flow = Math.max(0, output.volumeFlow());
                var previous = levels.get(panel.id());
                double elapsed = 0, prior = volume;
                if (previous != null
                        && previous.sensor().equals(sensor.instance())
                        && previous.output() == s.output()
                        && reading.tick() > previous.tick()) {
                    elapsed = world.time().seconds(reading.tick() - previous.tick());
                    prior = previous.volume();
                }
                // One scheduled interval to recover the inventory error, rather than a tuned
                // integral loop.
                double requested =
                        InventoryControl.target(
                                volume,
                                desired,
                                flow,
                                prior,
                                elapsed,
                                world.time().seconds(ControlService.CADENCE),
                                s.action() == ControlSettings.Action.OUTLET);
                source = FlowControl.ValveSource.VOLUME_FLOW;
                target = Math.max(0, requested);
                if (previous == null || reading.tick() > previous.tick())
                    levels.put(
                            panel.id(),
                            new LevelSample(sensor.instance(), s.output(), reading.tick(), volume));
            }
            default -> {
                return Decision.noSetpoint("READ ONLY SENSOR");
            }
        }
        double p = source == FlowControl.ValveSource.PRESSURE ? target : law.targetPressure();
        double m = source == FlowControl.ValveSource.MASS_FLOW ? target : law.massTarget(),
                v = source == FlowControl.ValveSource.VOLUME_FLOW ? target : law.volumeTarget();
        return Decision.valveLaw(
                new FlowControl.ControlValve(source, p, m, v, sensor.instance(), panel.revision()));
    }

    /**
     * A column loop's decision (W6.3): the feed rate it plans for column unit {@code unit}, which the column's cycle takes
     * below its set rate. FLOW: the target mass flow over the feed's molar mass. LEVEL on a vessel upstream with action
     * OUTLET: the volume flow that brings the level to its target in one cadence ({@link InventoryControl#target}, as a
     * valve's level loop), turned to mass with the feed valve's pipe reading and to mol/s with the same molar mass.
     */
    private Decision column(ControlFacts.Panel panel, ControlInput sensor, Sensor.Reading reading, long unit) {
        var s = panel.settings();
        if (sensor == null) return Decision.noSetpoint("MISSING DEVICE");
        if (!hosts.wired(panel.id(), sensor.key().host()) || !hosts.wired(panel.id(), s.output()))
            return Decision.noSetpoint("NO DATA LINK");
        if (reading == null || reading.availability() != Instrument.Availability.AVAILABLE)
            return Decision.noSetpoint("NO SIGNAL");
        if (sensor.kind() != Sensor.Kind.FLOW && sensor.kind() != Sensor.Kind.LEVEL)
            return Decision.noSetpoint("SELECT FLOW OR LEVEL");
        if (s.unit() != reading.unit() && reading.secondaryUnit().filter(u -> u == s.unit()).isEmpty())
            return Decision.noSetpoint("UNIT MISMATCH");
        double molarMass = world.columnFeedMolarMass(unit);
        if (!(molarMass > 0)) return Decision.noSetpoint("NO FEED DATA");
        if (sensor.kind() == Sensor.Kind.FLOW) {
            double mass = s.target();
            if (s.unit() == Sensor.Unit.CUBIC_METRE_PER_SECOND) {
                // A volume target: to mass at the reading's own density.
                if (!(reading.secondary() > 1e-12) || !BuiltInInputs.volumeAvailable(sensor) && sensor.physical() == null)
                    return Decision.noSetpoint("UNIT UNAVAILABLE");
                mass = s.target() * reading.value() / reading.secondary();
            }
            return Decision.columnFeed(unit, flowFeed(mass, molarMass));
        }
        if (s.action() != ControlSettings.Action.OUTLET) return Decision.noSetpoint("LEVEL: OUTLET ACTION");
        var view = world.view(sensor.key().host());
        var state = view.state();
        var tank = view.tank();
        var valve = world.instrumentPipeReading(world.columnFeedValve(unit));
        if (state == null || tank == null || valve == null || valve.volumeFlow() == null || sensor.key().mount().equals("level_boot"))
            return Decision.noSetpoint("NO LEVEL BALANCE");
        double height = s.unit() == Sensor.Unit.HEIGHT_PERCENT ? s.target() * reading.span() / 100 : s.target();
        if (height > reading.span()) return Decision.noSetpoint("TARGET OUT OF RANGE");
        double volume = state.phaseVolumes()[0] + state.phaseVolumes()[1] + state.solids().volume();
        double flow = Math.abs(valve.volumeFlow()), density = feedDensity(valve.massFlow(), valve.volumeFlow());
        // The feed's density from its valve's pipe; with nothing flowing, none to turn a volume into mass.
        if (!(flow > 1e-12) || !(density > 0)) return Decision.noSetpoint("NO LEVEL BALANCE");
        var previous = levels.get(panel.id());
        double elapsed = 0, prior = volume;
        if (previous != null && previous.sensor().equals(sensor.instance()) && previous.output() == s.output() && reading.tick() > previous.tick()) {
            elapsed = world.time().seconds(reading.tick() - previous.tick());
            prior = previous.volume();
        }
        double requested = InventoryControl.target(volume, tank.geometry().volumeAt(height), flow, prior, elapsed, world.time().seconds(ControlService.CADENCE), true);
        if (previous == null || reading.tick() > previous.tick())
            levels.put(panel.id(), new LevelSample(sensor.instance(), s.output(), reading.tick(), volume));
        return Decision.columnFeed(unit, levelFeed(Math.max(0, requested), density, molarMass));
    }

    /**
     * A column feed valve's density, kg/m³, from its pipe reading's mass and volume flow; NaN where nothing flows. The
     * magnitudes: a reading's sign is its pipe run's compiled sense, and a running column's feed flows only into it.
     */
    static double feedDensity(double massFlow, double volumeFlow) {
        double v = Math.abs(volumeFlow), m = Math.abs(massFlow);
        return v > 1e-12 && m > 1e-12 ? m / v : Double.NaN;
    }

    /** A flow loop's planned feed, mol/s: the target mass flow, kg/s, over the feed's molar mass, kg/mol (W6.3). */
    static double flowFeed(double massTarget, double molarMass) {
        return Math.max(0, massTarget) / molarMass;
    }

    /** A level loop's planned feed, mol/s: the volume flow, m³/s, at the feed's density, kg/m³, over its molar mass, kg/mol (W6.3). */
    static double levelFeed(double volumeFlow, double density, double molarMass) {
        return Math.max(0, volumeFlow) * density / molarMass;
    }

    /** The reading in the settings' unit: its primary value, or its secondary one. */
    private static double measured(ControlSettings s, Sensor.Reading reading) {
        return s.unit() == reading.unit() ? reading.value() : reading.secondary();
    }

    /**
     * A batch still's handle (owner decision O1), each holding the distillate flow its sensor
     * reads at the target: action INLET sets the firing, action OUTLET the reflux valve. Boiling
     * steadily, the distillate is the boil-up over R + 1 and the boil-up follows the heat, so the
     * firing scales by target over measured and R + 1 by measured over target. Before any
     * distillate, full fire and the valve open; a target of 0 puts the fire out or closes the valve.
     * One correction per {@link Stills#RESPONSE_TICKS}, the time a change takes to show in the
     * distillate; a new target corrects at once.
     */
    private Decision still(ControlFacts.Panel panel, ControlInput sensor, Sensor.Reading reading) {
        var s = panel.settings();
        if (sensor.kind() != Sensor.Kind.FLOW) return Decision.noSetpoint("SELECT DISTILLATE FLOW");
        boolean firing = s.action() == ControlSettings.Action.INLET;
        var stillsNow = world.stills();
        if (!firing && !stillsNow.refluxes(s.output()))
            return Decision.noSetpoint("NO REFLUX VALVE");
        double now = firing ? stillsNow.firing(s.output()) : stillsNow.reflux(s.output());
        if (Double.isNaN(now)) return Decision.noSetpoint("MISSING DEVICE");
        var previous = stills.get(panel.id());
        double next;
        if (previous != null
                && previous.output() == s.output()
                && previous.firing() == firing
                && previous.target() == s.target()
                && reading.tick() < previous.tick() + Stills.RESPONSE_TICKS) next = previous.value();
        else {
            double measured = measured(s, reading), target = s.target();
            if (firing)
                next =
                        !(target > 0)
                                ? 0
                                : measured > FLOWING ? Math.clamp(now * target / measured, 0, 1) : 1;
            else
                next =
                        !(target > 0)
                                ? Double.POSITIVE_INFINITY
                                : measured > FLOWING
                                        ? Math.max(0, (now + 1) * measured / target - 1)
                                        : 0;
            stills.put(panel.id(), new StillSample(s.output(), firing, target, reading.tick(), next));
        }
        return firing ? Decision.stillFiring(next) : Decision.stillReflux(next);
    }
}
