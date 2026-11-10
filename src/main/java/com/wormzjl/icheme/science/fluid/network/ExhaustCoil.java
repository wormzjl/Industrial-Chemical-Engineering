package com.wormzjl.icheme.science.fluid.network;

/**
 * A gas turbine's heat-recovery steam coil (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md section 10, owner P8 A): one
 * counterflow coil in the exhaust duct, water in at its cold end, steam out at its hot end. The gas enters at
 * {@code gasInlet} (K) with capacity rate {@code flue} (W/K); both are slice inputs the turbine's last fired slice sets
 * ({@link ShaftPlan.Kind#EXHAUST}), 0 with the turbine stopped. The coil is designed to its pinch (S4, no UA): the stream's
 * outlet enthalpy is the least of its enthalpy {@link #APPROACH} under the gas inlet and, at every knot of its curve from its
 * inlet up, the enthalpy that leaves the gas {@link #PINCH} over the stream there:
 * {@code h(T) + C_g (T_g − T − ΔT_p)/ṁ}. On a boiling stream the bubble point's liquid end decides; the stack leaves at
 * {@code T_g − Q/C_g}. The heat enters from outside, the exhaust's, booked as the coil's {@code HEAT} boundary.
 */
public record ExhaustCoil(double gasInlet, double flue) implements HeatLaw {
    /** The pinch, K, and the superheater's hot-end approach, K. */
    public static final double PINCH = 10, APPROACH = 25;
    /** The hottest exhaust a turbine gives, K: the stream's curve spans up to it. */
    public static final double HOTTEST = 900;
    /** The coldest the stream meets, K: the air. */
    public static final double COLDEST = 298.15;

    public ExhaustCoil {
        if (!(gasInlet >= 0) || !Double.isFinite(gasInlet) || !(flue >= 0) || !Double.isFinite(flue)) throw new IllegalArgumentException("Invalid exhaust coil");
    }

    /** A coil whose turbine has given no exhaust yet. */
    public static final ExhaustCoil COLD = new ExhaustCoil(0, 0);

    /** The coil's equations carry no facts of their own: its gas is its slice input. */
    public record Shape() {}

    @Override public Shape shape() { return new Shape(); }
    @Override public double coldest() { return COLDEST; }
    @Override public double hottest() { return HOTTEST; }
    @Override public HeatCurve.Films films() { return HeatCurve.Films.DEFAULT; }

    @Override
    public double heat(HeatCurve curve, double inlet, double massFlow) {
        double mass = Math.abs(massFlow);
        if (mass == 0 || flue == 0 || !(gasInlet > COLDEST)) return 0;
        double tin = curve.temperatureAt(inlet), top = gasInlet - APPROACH;
        if (!(tin < gasInlet - PINCH)) return 0;
        double ratio = flue / mass;
        double out = Math.min(curve.enthalpyAt(top), inlet + ratio * (gasInlet - tin - PINCH));
        for (int k = 0; k < curve.knots(); k++) {
            double t = curve.temperature(k);
            if (t <= tin || t > top || curve.enthalpy(k) <= inlet) continue;
            out = Math.min(out, curve.enthalpy(k) + ratio * (gasInlet - t - PINCH));
        }
        return mass * Math.max(0, out - inlet);
    }

    /** The stack, K, after {@code heat} W left the gas; the gas inlet without a coil's heat, the air with no exhaust. */
    public static double stack(double gasInlet, double flue, double heat) {
        if (!(flue > 0)) return COLDEST;
        return Math.clamp(gasInlet - Math.max(0, heat) / flue, COLDEST, Math.max(COLDEST, gasInlet));
    }

    public ExhaustCoil withGas(double inlet, double capacity) { return new ExhaustCoil(inlet, capacity); }
}
