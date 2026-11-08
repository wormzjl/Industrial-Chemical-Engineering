package com.wormzjl.icheme.science.machine;

/**
 * A powered unit's electric store (batch 2026-11-03-rotating-machinery W5, build plan sections 1 to 3, owner decision
 * D30): a buffer of energy in joules behind the unit's FE port. Pure and Minecraft-free: the capacity and rates a
 * rated draw gives, a booked draw that runs the store short, the restart threshold, a generator's load from the room
 * left, and a generator's speed window. FE rounding happens only at the port ({@link #fe}); the store keeps joules
 * (reading A55).
 */
public final class ElectricStore {
    /** The buffer: two slices at the longest 20 s cadence of rated draw (plan 7). */
    public static final double BUFFER_SECONDS = 40;
    /** One slice at the longest cadence, s: the restart threshold's span of rated draw (reading A62). */
    public static final double SLICE_SECONDS = 20;
    /** Motors only bridge two presentation periods; their buffer is not a stand-alone power supply. */
    public static final double MOTOR_BUFFER_SECONDS = 10;
    public static final double MOTOR_RESTART_SECONDS = 5;
    /** The most a port takes or gives per tick, as a multiple of the rated draw (plan 7). */
    public static final double RATE = 2;
    /** A generator delivers within this share of its rated speed either way (reading A64). */
    public static final double SPEED_WINDOW = .05;
    /** Seconds per tick at the nominal 20 TPS. */
    public static final double TICK_SECONDS = .05;
    /** Fan motors are 4-pole, one per fan (reading A70). */
    public static final int FAN_POLES = 4;

    private ElectricStore() {
    }

    /**
     * What the store saves (fluid facts schema 11): its content, J, and whether it is metered. An unmetered store (a fan
     * unit from a save before W5, owner decision A73 option A) runs its unit free until its port first receives FE.
     */
    public record Facts(double joules, boolean metered) {
        /** A store built from W5 on: metered and empty. */
        public static final Facts EMPTY = new Facts(0, true);
        /** A unit from an older save: unmetered, empty. */
        public static final Facts UNMETERED = new Facts(0, false);

        public Facts {
            if (!(joules >= 0) || !Double.isFinite(joules)) throw new IllegalArgumentException("Invalid electric store content " + joules);
        }
    }

    /** The store of a unit drawing {@code ratedDraw} W at rating: 40 s of it, J. */
    public static double capacity(double ratedDraw) {
        return BUFFER_SECONDS * Math.max(0, ratedDraw);
    }

    /** The most its port takes or gives per tick, J: twice its rated draw over one tick. */
    public static double perTick(double ratedDraw) {
        return RATE * Math.max(0, ratedDraw) * TICK_SECONDS;
    }

    /** What its store must hold for a stopped unit to restart, J: one slice of its rated draw (reading A62). */
    public static double restart(double ratedDraw) {
        return SLICE_SECONDS * Math.max(0, ratedDraw);
    }

    /** {@code joules} in FE at {@code joulesPerFE}. */
    public static double fe(double joules, double joulesPerFE) {
        return joules / joulesPerFE;
    }

    /**
     * A draw booked against a store: the content left, J, the energy booked, J, and whether the store ran short (it then
     * books what it held and goes to 0).
     */
    public record Draw(double content, double booked, boolean shortfall) {
    }

    /** A draw of {@code power} W over {@code seconds} s from a store holding {@code content} J. */
    public static Draw draw(double content, double power, double seconds) {
        double wanted = Math.max(0, power) * Math.max(0, seconds);
        if (wanted <= content) return new Draw(content - wanted, wanted, false);
        return new Draw(0, content, wanted > 0);
    }

    /** The energy, J, a store holding {@code content} of {@code capacity} accepts of {@code offered} J. */
    public static double accepted(double content, double capacity, double offered) {
        return Math.max(0, Math.min(Math.max(0, offered), capacity - content));
    }

    /**
     * A generator's electric load, W (reading A63): its rating {@code ratedOutput}, but no more than fills the room its
     * store has left over the next {@code seconds}. A full store takes nothing.
     */
    public static double generatorLoad(double content, double capacity, double ratedOutput, double seconds) {
        double room = Math.max(0, capacity - content);
        if (!(seconds > 0)) return 0;
        return Math.min(Math.max(0, ratedOutput), room / seconds);
    }

    /** Whether a generator at {@code speed} of rated {@code ratedSpeed} (rpm) delivers (reading A64). */
    public static boolean onSpeed(double speed, double ratedSpeed) {
        return ratedSpeed > 0 && speed >= (1 - SPEED_WINDOW) * ratedSpeed && speed <= (1 + SPEED_WINDOW) * ratedSpeed;
    }

    /** A motor's rated electric draw, W: its rating over its IE3 efficiency, through a variable-speed drive when {@code variable}. */
    public static double motorDraw(double ratedPower, int poles, boolean variable) {
        return poles == 0 || !(ratedPower > 0) ? 0 : Motor.electricPower(ratedPower, ratedPower, poles, variable);
    }

    /** A generator's efficiency, from the motor's IE3 table (build plan section 1). */
    public static double generatorEfficiency(double ratedPower, int poles) {
        return poles == 0 || !(ratedPower > 0) ? 1 : Motor.efficiency(ratedPower, poles);
    }

    /**
     * The efficiency of fan motors driving {@code fanPower} W of fans at full speed, {@code fans} of them (reading A70): the
     * IE3 4-pole efficiency of the standard rating at one fan's power. {@code fans} 0 (not known) takes the whole power as
     * one fan.
     */
    public static double fanEfficiency(double fanPower, int fans) {
        if (!(fanPower > 0)) return 1;
        double each = fanPower / Math.max(1, fans), rating = Motor.standardRating(each);
        if (!Double.isFinite(rating)) rating = each;
        return Motor.efficiency(rating, FAN_POLES);
    }
}
