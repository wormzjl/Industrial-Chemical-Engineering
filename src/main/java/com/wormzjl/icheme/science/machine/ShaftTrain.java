package com.wormzjl.icheme.science.machine;

import java.util.Objects;

/**
 * A shaft train's driver, stepped once per slice (batch 2026-11-03-rotating-machinery, W3; plan sections 4.1 and 5.2,
 * owner decision D27; build plan section 2). Quasi-steady, no inertia: from the train's demand at its present speed (the
 * driven machines' last booked shaft power over their gears, W) and, for a turbine, the power its steam gave, the next
 * speed, trip and status. Pure and Minecraft-free; the engine reads the inputs at commit and sends the speed at dispatch.
 */
public final class ShaftTrain {
    /** The creative motor's set speed and power limit when the player set none, rpm and W (reading A19). */
    public static final double CREATIVE_SPEED = 2950, CREATIVE_LIMIT = 1e6;
    /** A speed change smaller than this share of the design speed wakes nothing (build plan section 2). */
    public static final double SPEED_DEADBAND = 1e-3;

    private ShaftTrain() {
    }

    /** What drives the train. */
    public enum Drive {
        FIXED_MOTOR, VARIABLE_MOTOR, TURBINE, CREATIVE,
        /** A Create coupling (W6b, D40): {@code setPoint} is Create's |rpm|, {@code powerLimit} the power its network spares, W. */
        CREATE
    }

    /** How a variable-speed driver sets its speed: a set speed, or regulating its first machine's flow or discharge pressure. Saved by name. */
    public enum Mode {
        SPEED, REGULATE_FLOW, REGULATE_PRESSURE
    }

    /**
     * A trip. {@code OVERLOAD} and {@code OVERSPEED} are protection trips, latched until the player resets it (owner
     * decision A2). {@code NO_POWER} (W5) is a motor's stop on an empty store: not latched, it clears itself when the store
     * holds one slice again (D6), and is never saved as the unit's trip. Saved by name.
     */
    public enum Trip {
        NONE, OVERLOAD, OVERSPEED, NO_POWER;

        /** Whether it waits for the player's reset. */
        public boolean latched() {
            return this == OVERLOAD || this == OVERSPEED;
        }
    }

    /**
     * A driver as the player set it: {@code ratedPower} W, {@code designSpeed} rpm, a motor's {@code poles}; {@code setPoint}
     * a share of the design speed (0: 1, reading A19), a creative motor's rpm (0: {@link #CREATIVE_SPEED});
     * {@code powerLimit} a creative motor's W (0: {@link #CREATIVE_LIMIT}); {@code governed} whether a turbine's speed sensor
     * works its valve (D27); {@code powered} whether a motor's store holds energy (W5: false stops it, {@code NO POWER}).
     */
    public record Driver(Drive drive, double ratedPower, double designSpeed, int poles, boolean running, Mode mode, double setPoint, double powerLimit, Trip trip,
                         boolean governed, boolean powered) {
        /** A driver whose store holds energy (or that needs none). */
        public Driver(Drive drive, double ratedPower, double designSpeed, int poles, boolean running, Mode mode, double setPoint, double powerLimit, Trip trip, boolean governed) {
            this(drive, ratedPower, designSpeed, poles, running, mode, setPoint, powerLimit, trip, governed, true);
        }

        public Driver {
            Objects.requireNonNull(drive);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(trip);
            if (!(ratedPower >= 0) || !(designSpeed > 0) || !(setPoint >= 0) || !(powerLimit >= 0) || !Double.isFinite(ratedPower + designSpeed + setPoint + powerLimit))
                throw new IllegalArgumentException("Invalid driver");
        }

        /** The speed the driver aims at before any limit, rpm. */
        public double setSpeed() {
            if (drive == Drive.CREATE) return setPoint;
            return drive == Drive.CREATIVE ? setPoint > 0 ? setPoint : CREATIVE_SPEED : (setPoint > 0 ? setPoint : 1) * designSpeed;
        }

        /** The power the driver can give, W: its rating, or a creative motor's limit. */
        public double limit() {
            if (drive == Drive.CREATE) return powerLimit;
            return drive == Drive.CREATIVE ? powerLimit > 0 ? powerLimit : CREATIVE_LIMIT : ratedPower;
        }
    }

    /**
     * What the last slices measured: {@code demand} the train's shaft power at its present speed, W; {@code available} the
     * power a turbine's steam gave, W; {@code steamShort} whether its governor valve passed less than its target (the valve
     * wide open); {@code measured} and {@code target} the regulated quantity (flow m³/s or discharge pressure Pa, NaN for
     * none), with the slice before's speed and measure ({@code previousSpeed} rpm, NaN for none).
     */
    public record Measure(double demand, double available, boolean steamShort, double measured, double target, double previousSpeed, double previousMeasured) {
        public static Measure of(double demand) {
            return new Measure(demand, 0, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /** The step's answer: the next speed (rpm), its trip, its status word, the shaft power a governor feeds forward (W, at
     * the set speed) and a motor's electric draw (W; booked from its terminal's store by {@code ShaftTrains}, W5). */
    public record Step(double speed, Trip trip, String status, double governorPower, double electricPower) {
    }

    /** The next speed of {@code driver} turning at {@code speed} rpm after {@code measure}. */
    public static Step step(Driver driver, double speed, Measure measure) {
        if (driver.trip() != Trip.NONE) return new Step(0, driver.trip(), tripped(driver.trip()), 0, 0);
        if (!driver.running()) return new Step(0, Trip.NONE, "STOPPED", 0, 0);
        // A motor on an empty store stands (W5, D6): not latched, it restarts once its store holds a slice again.
        if (!driver.powered() && (driver.drive() == Drive.FIXED_MOTOR || driver.drive() == Drive.VARIABLE_MOTOR))
            return new Step(0, Trip.NO_POWER, tripped(Trip.NO_POWER), 0, 0);
        double demand = Math.max(0, measure.demand());
        double set = regulated(driver, speed, measure);
        return switch (driver.drive()) {
            case FIXED_MOTOR -> demand > driver.ratedPower() * (1 + 1e-9)
                    ? new Step(0, Trip.OVERLOAD, "TRIPPED: OVERLOAD", 0, 0)
                    : new Step(driver.designSpeed(), Trip.NONE, "RUNNING", 0, electric(driver, demand));
            case VARIABLE_MOTOR, CREATIVE -> {
                double next = limited(speed, driver.limit(), demand, set);
                String status = next < set * (1 - 1e-9) ? driver.drive() == Drive.CREATIVE ? "POWER LIMITED" : "CURRENT LIMITED" : "RUNNING";
                yield new Step(next, Trip.NONE, status, 0, driver.drive() == Drive.CREATIVE ? 0 : electric(driver, demand));
            }
            case TURBINE -> turbine(driver, speed, measure, demand, set);
            case CREATE -> create(driver, speed, demand);
        };
    }

    /**
     * A Create coupling (W6b, D40): the creative motor's limited step at Create's speed, its limit the power the network
     * spares. Create at rest stands the train ({@code NO ROTATION}); no spare at all stands it too ({@code POWER LIMITED}),
     * where the creative motor's step would restart it at full speed from rest each slice (reading A146).
     */
    private static Step create(Driver driver, double speed, double demand) {
        double set = driver.setSpeed();
        if (!(set > 0)) return new Step(0, Trip.NONE, "NO ROTATION", 0, 0);
        double next = driver.limit() > 0 ? limited(speed, driver.limit(), demand, set) : 0;
        return new Step(next, Trip.NONE, next < set * (1 - 1e-9) ? "POWER LIMITED" : "RUNNING", 0, 0);
    }

    /** A turbine (D27, readings A22, A23, A26). */
    private static Step turbine(Driver driver, double speed, Measure measure, double demand, double set) {
        double available = Math.max(0, measure.available());
        // At rest the speed governor opens for startup; this is a steam request, never delivered shaft power.
        double governor = driver.governed() ? speed > 0 ? demand * cube(set / speed) : Math.max(demand, driver.limit()) : 0;
        // This model has no rotor inertia: without delivered fluid work the shaft cannot coast, start or overspeed.
        if (!(available > 0)) return new Step(0, Trip.NONE, "STEAM LIMITED", governor, 0);
        if (driver.governed()) {
            // Feed-forward: the demand at the set speed, by the cube law from the demand here.
            if (speed <= 0 || demand <= 0) return new Step(set, Trip.NONE, "RUNNING", governor, 0);
            double balance = SpeedControl.balance(speed, available, demand);
            if (measure.steamShort()) {
                double next = Math.min(set, balance);
                return new Step(next, Trip.NONE, next < set * (1 - 1e-9) ? "STEAM LIMITED" : "RUNNING", governor, 0);
            }
            // Steam suffices: the governor holds the set speed, or brings the speed back up to it.
            double next = speed >= set * (1 - 1e-9) ? set : Math.min(set, Math.max(speed, balance));
            return new Step(next, Trip.NONE, next < set * (1 - 1e-9) ? "STEAM LIMITED" : "RUNNING", governor, 0);
        }
        // Ungoverned: the shaft settles where its steam's power meets the load, and overspeeds on a surplus.
        double next = speed <= 0 ? available > 0 ? set : 0 : SpeedControl.balance(speed, available, demand);
        if (next > SteamTurbine.TRIP_SPEED * set) return new Step(0, Trip.OVERSPEED, "TRIPPED: OVERSPEED", 0, 0);
        return new Step(next, Trip.NONE, next <= 0 ? "STEAM LIMITED" : "RUNNING", 0, 0);
    }

    /** A variable-speed driver's set speed, rpm: its set point, or its regulation of its first machine (reading A31). */
    private static double regulated(Driver driver, double speed, Measure measure) {
        double base = driver.setSpeed();
        if (driver.mode() == Mode.SPEED || driver.drive() == Drive.FIXED_MOTOR || !Double.isFinite(measure.measured()) || !(measure.target() > 0)) return base;
        double design = driver.drive() == Drive.CREATIVE ? base : driver.designSpeed();
        double share = speed > 0 ? speed / design : 1;
        double previous = Double.isFinite(measure.previousSpeed()) ? measure.previousSpeed() / design : Double.NaN;
        double next = SpeedControl.regulate(measure.target(), share, measure.measured(), previous, measure.previousMeasured(),
                driver.mode() == Mode.REGULATE_FLOW ? 1 : 2, Motor.LOWEST_SPEED, 1);
        return next * design;
    }

    /** A drive at its current or power limit lowers its speed by the damped step and brings it back up when the load fits. */
    private static double limited(double speed, double limit, double demand, double set) {
        if (speed <= 0 || demand <= 0) return set;
        if (demand > limit || speed < set) return SpeedControl.powerLimited(speed, limit, demand, set);
        return set;
    }

    private static double electric(Driver driver, double demand) {
        return driver.poles() == 0 || driver.ratedPower() <= 0 ? 0 : Motor.electricPower(demand, driver.ratedPower(), driver.poles(), driver.drive() == Drive.VARIABLE_MOTOR);
    }

    /** A trip's status word: {@code TRIPPED: OVERLOAD}, {@code TRIPPED: NO POWER}. */
    public static String tripped(Trip trip) {
        return "TRIPPED: " + trip.name().replace('_', ' ');
    }

    private static double cube(double x) {
        return x * x * x;
    }
}
