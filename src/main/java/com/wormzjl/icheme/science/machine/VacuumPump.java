package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.Locale;

/**
 * A vacuum pump casing on a shaft (batch 2026-11-10-vacuum-pump, VACUUM_PUMP_PLAN.md section 2): a curve, not a mechanism.
 * Its suction capacity is {@code S = S_n s φ(P_s)}, carried on the positive-displacement row (form F2e) with that
 * capacity as its displacement and {@code P_dmax − P_s} as its relief rise, as {@code FlowControl.Ejector} does.
 *
 * <ul>
 *   <li>Liquid ring (owner V2 B: an internal seal at {@link #SEAL_TEMPERATURE}): the cells carry the ring's water vapour,
 *       {@code φ = (1 − P_v/P_s)^n}, {@code n} 2 for one stage and 1 for two, none at {@link #SEAL_VAPOUR_PRESSURE}. Shaft
 *       power: the ring's friction {@code P_f s³} plus the isothermal work {@code p_s Q ln(P_atm/p_s)/η_iso}, which peaks
 *       at {@code P_atm/e}. The gas leaves at its suction temperature: none of the work is booked into it, the seal water
 *       takes it.</li>
 *   <li>Dry screw: {@code φ = 1 − P_u/P_s}, {@link #SCREW_ULTIMATE} 1 Pa. Shaft power, the vendors' form:
 *       {@code P_r [0.6 s² + 0.4 s (p_s/P_atm)^0.3]}. It runs hot: the gas is booked the enthalpy of
 *       {@code T_d = T_s [1 + 0.6 (1 − p_s/p_d)]} (owner V9), the jacket takes the rest of the shaft power.</li>
 * </ul>
 * Shaft power reads the suction only (decision D4), against the atmosphere as the discharge (its design discharge).
 *
 * @param type       liquid ring of one or two stages, or dry screw
 * @param capacity   S_n, the nominal suction capacity at rated speed, m³/s
 * @param ratedSpeed the design speed, rpm
 */
public record VacuumPump(Type type, double capacity, double ratedSpeed) implements MachineLaw {
    /** The internal seal's temperature, K (owner V2 B), and the water's vapour pressure there, Pa (IAPWS-95: 3.1699 kPa). */
    public static final double SEAL_TEMPERATURE = 298.15, SEAL_VAPOUR_PRESSURE = 3169.9;
    /** A dry screw's ultimate pressure, Pa (catalogue 0.01 mbar). */
    public static final double SCREW_ULTIMATE = 1;
    /** The largest discharge either type holds, Pa (vendors 1.2 to 1.4 bar a). */
    public static final double MAXIMUM_DISCHARGE = 1.3e5;
    /** The design discharge the shaft power reads, Pa. */
    public static final double ATMOSPHERE = 101325;
    /** The liquid ring's isothermal efficiency. */
    public static final double ISOTHERMAL_EFFICIENCY = .45;
    /** A dry screw's rated shaft power per m³/h of capacity, W (Busch COBRA DX 0650: 650 m³/h on 15 kW). */
    public static final double SCREW_POWER = 19;
    /** A dry screw's discharge temperature rise over its suction's at a deep vacuum, a share of the suction temperature. */
    public static final double SCREW_HEATING = .6;
    /**
     * Owner V6 B: a dry screw wears, as a pump running dry does ({@link Wear}), while liquid takes more than this share of
     * its suction volume, and its liquid noise rises with the share to full loudness at {@link #LIQUID_LOUD}.
     */
    public static final double LIQUID_TRACE = .01, LIQUID_LOUD = .1;
    /** The capacity left when the curve gives none, m³/s: the row needs a displacement above zero. */
    private static final double NONE = 1e-12;

    public enum Type {
        /** Liquid ring, one stage: {@code n} 2, ring friction 9 W per m³/h. */
        LIQUID_RING_ONE_STAGE(2, 9),
        /** Liquid ring, two stages: {@code n} 1, friction 12 W per m³/h (two impellers). */
        LIQUID_RING_TWO_STAGE(1, 12),
        DRY_SCREW(0, 0);

        /** The exponent of the liquid ring's vapour share; the friction, W per m³/h of capacity at rated speed. */
        public final int exponent;
        public final double friction;

        Type(int exponent, double friction) {
            this.exponent = exponent;
            this.friction = friction;
        }

        public boolean liquidRing() {
            return this != DRY_SCREW;
        }

        /** Its blank-off pressure, Pa: where its capacity ends. */
        public double blankOff() {
            return liquidRing() ? SEAL_VAPOUR_PRESSURE : SCREW_ULTIMATE;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public VacuumPump {
        java.util.Objects.requireNonNull(type);
        if (!(capacity > 0) || !Double.isFinite(capacity) || !(ratedSpeed > 0) || !Double.isFinite(ratedSpeed))
            throw new IllegalArgumentException("Invalid vacuum pump " + capacity + " m3/s at " + ratedSpeed + " rpm");
    }

    /** Whether {@code law} is a dry screw: liquid at its suction wears it (owner V6 B). */
    public static boolean dryScrew(MachineLaw law) {
        return law instanceof VacuumPump v && v.type == Type.DRY_SCREW;
    }

    /** The liquid noise byte, 0 to 255, at a suction liquid volume share {@code liquid} (owner V6 B). */
    public static int liquidNoise(double liquid) {
        return liquid > LIQUID_TRACE ? (int) Math.round(255 * Math.min(1, liquid / LIQUID_LOUD)) : 0;
    }

    /** φ, the share of the capacity it takes at suction pressure {@code pressure} Pa: 0 at or below blank-off. */
    public double share(double pressure) {
        if (!(pressure > 0)) return 0;
        double dry = 1 - type.blankOff() / pressure;
        if (!(dry > 0)) return 0;
        return type == Type.LIQUID_RING_ONE_STAGE ? dry * dry : dry;
    }

    /** The suction volume flow it takes at {@code pressure} Pa and {@code speed} (share of rated), m³/s. */
    public double suction(double pressure, double speed) {
        return capacity * Math.max(0, speed) * share(pressure);
    }

    /** The relief rise at suction {@code pressure}: {@link #MAXIMUM_DISCHARGE} less the suction, smoothly at least a thousandth of it. */
    public static double reliefRise(double pressure) {
        double d = MAXIMUM_DISCHARGE - pressure, e = 1e-3 * MAXIMUM_DISCHARGE;
        return .5 * (d + Math.sqrt(d * d + e * e));
    }

    /** The row's law at {@code speed} and suction {@code pressure}. */
    public PositiveDisplacement row(double speed, double pressure) {
        double q = suction(pressure, speed);
        return new PositiveDisplacement(q > NONE ? q : NONE, reliefRise(pressure));
    }

    @Override public boolean liquid() {
        return false;
    }

    @Override public double rise(double flow, double speed, double density, double pressure, double k) {
        return row(speed, pressure).rise(flow, 1);
    }

    @Override public double riseSlope(double flow, double speed, double density, double pressure, double k) {
        return row(speed, pressure).riseSlope(flow, 1);
    }

    /** The flow form (F2e): never flat, never vertical. */
    @Override public double residual(double flow, double head, double speed, double density, double pressure, double k) {
        return row(speed, pressure).residual(flow, head, 1);
    }

    /** None for a liquid ring (isothermal); a dry screw's discharge temperature rule on the suction's {@code P/ρ} and {@code k}. */
    @Override public double fluidPower(double massFlow, double rise, double density, double pressure, double k) {
        if (type.liquidRing() || !(massFlow > 0) || !(rise > 0) || !(density > 0) || !(pressure > 0) || !(k > 1)) return 0;
        return massFlow * k / (k - 1) * pressure / density * SCREW_HEATING * rise / (pressure + rise);
    }

    @Override public double shaftPower(double flow, double speed, double density, double pressure, double k) {
        if (!(speed > 0)) return 0;
        double nominal = capacity * 3600;
        if (!type.liquidRing()) {
            double ratio = Math.min(1, Math.max(0, pressure) / ATMOSPHERE);
            return SCREW_POWER * nominal * (.6 * speed * speed + .4 * speed * DeterministicMath.pow(ratio, .3));
        }
        double work = pressure > 0 && pressure < ATMOSPHERE ? pressure * Math.max(0, flow) * DeterministicMath.log(ATMOSPHERE / pressure) : 0;
        return type.friction * nominal * speed * speed * speed + work / ISOTHERMAL_EFFICIENCY;
    }

    /** The largest shaft power at rated speed, W, over the suction pressures, on its own curve: the motor's sizing duty. */
    public double maximumShaftPower() {
        if (!type.liquidRing()) return shaftPower(capacity, 1, 1, ATMOSPHERE, 1.4);
        double best = 0;
        for (int i = 1; i <= 400; i++) {
            double p = ATMOSPHERE * i / 400;
            best = Math.max(best, shaftPower(suction(p, 1), 1, 1, p, 1.4));
        }
        return best;
    }

    @Override public double ratedFlow() {
        return capacity;
    }

    /** No head: its rise is the relief rise's. */
    @Override public double ratedHead() {
        return 0;
    }

    /** The liquid ring's isothermal efficiency; none stated for a screw. */
    @Override public double efficiency() {
        return type.liquidRing() ? ISOTHERMAL_EFFICIENCY : 0;
    }
}
