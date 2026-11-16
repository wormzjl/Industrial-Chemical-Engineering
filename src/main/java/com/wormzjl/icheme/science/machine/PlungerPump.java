package com.wormzjl.icheme.science.machine;

import java.util.List;

/**
 * A triplex reciprocating plunger pump on a shaft (API 674; batch 2026-11-15-supercritical-fluids, stage S8,
 * PLUNGER_PUMP_PLAN.md section 3): it displaces a fixed suction volume per turn and delivers it against whatever rise the
 * system needs, up to its relief rise. It is the pump for one dense fluid lighter than its critical density, which a
 * centrifugal pump cannot lift, and for small flows to high pressures.
 *
 * <p>Its law (batch 2026-11-16-supercritical-followups, P1, FOLLOWUPS_PLAN.md section 3) is {@link PositiveDisplacement}'s
 * share of the displacement at the train's speed (form F2e: the slip of {@link PositiveDisplacement#SLIP} per relief rise,
 * then the relief line), less the clearance loss: the fluid left in the clearance {@link #CLEARANCE} at the end of the
 * stroke re-expands before the suction valve opens, so the share falls by {@code c (r − 1)}, {@code r = ρ_d/ρ_s}. The
 * density ratio is the isentrope {@code r = ((p + ΔP)/p)^(1/n)} on the suction's isentropic exponent {@code n = K_s/p}
 * ({@code FluidThermodynamics#isentropicExponent}), which the law takes in its {@code k} argument: about 2,200 for water
 * at 1 MPa (no loss to speak of), γ for an ideal gas. The work booked into the fluid is the isentropic
 * {@code ∫ v dP = p n/(n − 1) (((p + ΔP)/p)^((n−1)/n) − 1)} per suction volume, which tends to {@code ΔP} on a liquid; the
 * shaft takes the volume the plungers draw (the displacement times {@code 1 − c (r − 1)}) at that work over
 * {@link #MECHANICAL_EFFICIENCY}, since the plungers compress against the rise whether the fluid is delivered or relieved.
 *
 * <p>Left out: the real-gas isentrope by integration (one exponent at the suction) and a Murnaghan law with a second
 * parameter (FOLLOWUPS_PLAN.md section 3, "Ruled out"). No NPSH3 of its own: its suction limit is the liquid suction's E
 * and p_v.
 *
 * @param displacement Q_d, the suction volume flow at rated speed and no rise, m³/s
 * @param reliefRise   Δp_relief, the rise at which the relief valve passes the whole delivery back, Pa
 * @param ratedSpeed   the design speed, rpm
 */
public record PlungerPump(double displacement, double reliefRise, double ratedSpeed) implements MachineLaw {
    /** The share of the shaft power that reaches the plungers (crank, crossheads and packing). */
    public static final double MECHANICAL_EFFICIENCY = .88;
    /** Every model's relief rise, Pa: inside the network's 30 MPa envelope. */
    public static final double RELIEF_RISE = 25e6;
    /** Every model's design speed, rpm: a motor at 1,500 rpm drives it through a gearbox. */
    public static final double SPEED = 300;
    /** {@code c}, the clearance volume as a share of the displacement: a liquid end built for its service. */
    public static final double CLEARANCE = .1;
    /** The least isentropic exponent the law reads ({@code FluidThermodynamics#isentropicExponent}'s floor). */
    public static final double LEAST_EXPONENT = 1.05;
    /** The exponent of a suction with none (not a number): an ideal diatomic gas's. */
    public static final double NO_EXPONENT = 1.4;
    /** The displacement left at a standing train, m³/s: the row needs a displacement above zero. */
    private static final double NONE = 1e-12;
    /** The least absolute pressure, Pa, the law reads at its suction and its discharge: finite at every rise. */
    private static final double LEAST_PRESSURE = 1;
    /** The inverse's iteration cap and relative tolerance. */
    private static final int MOST_STEPS = 60;
    private static final double TOLERANCE = 1e-13;

    /**
     * One catalogue model: its name, nominal displacement at {@link #SPEED} (m³/h) and the drawing's scale {@code k}.
     */
    public record Model(String name, double capacity, double scale) {
    }

    /** The models by number. */
    public static final List<Model> MODELS = List.of(new Model("PP-3", 3, .55), new Model("PP-10", 10, .75),
            new Model("PP-30", 30, 1), new Model("PP-100", 100, 1.35));

    /** Model {@code number} (1 up, rounded; below the first the first, past the last the last). */
    public static Model model(double number) {
        return MODELS.get((int) Math.max(0, Math.min(MODELS.size() - 1, Math.round(number) - 1)));
    }

    /** The law of model {@code m}. */
    public static PlungerPump of(Model m) {
        return new PlungerPump(m.capacity() / 3600, RELIEF_RISE, SPEED);
    }

    public PlungerPump {
        if (!(displacement > 0) || !Double.isFinite(displacement) || !(reliefRise > 0) || !Double.isFinite(reliefRise)
                || !(ratedSpeed > 0) || !Double.isFinite(ratedSpeed))
            throw new IllegalArgumentException("Invalid plunger pump " + displacement + " m3/s, " + reliefRise + " Pa at " + ratedSpeed + " rpm");
    }

    /** The law with no clearance at {@code speed} (share of rated), read at speed 1: the displacement scaled by the speed, as
     * {@link VacuumPump#row} scales its capacity. The plunger's delivery lies below it at every rise above zero. */
    public PositiveDisplacement row(double speed) {
        return new PositiveDisplacement(capacity(speed), reliefRise);
    }

    /** The displacement at {@code speed}, m³/s, floored above zero. */
    private double capacity(double speed) {
        double q = displacement * Math.max(0, speed);
        return q > NONE ? q : NONE;
    }

    /** The exponent the law reads of {@code k}: at least {@link #LEAST_EXPONENT}; {@link #NO_EXPONENT} for none. */
    static double exponent(double k) {
        return Double.isNaN(k) ? NO_EXPONENT : Math.max(LEAST_EXPONENT, k);
    }

    /** {@code ln((p + ΔP)/p)} on the floored pressures, without cancellation at a small rise. */
    private static double logRatio(double rise, double pressure) {
        double p = Math.max(LEAST_PRESSURE, pressure);
        return Math.log1p(Math.max(rise, LEAST_PRESSURE - p) / p);
    }

    /** {@code r − 1}, the density ratio across the isentrope less one: {@code expm1(ln((p + ΔP)/p)/n)}. */
    static double compression(double rise, double pressure, double k) {
        return Math.expm1(logRatio(rise, pressure) / exponent(k));
    }

    /** {@code η_v}'s clearance part, {@code 1 − c (r − 1)}: the share of the displacement the plungers draw at {@code rise}. */
    public static double volumetricEfficiency(double rise, double pressure, double k) {
        return 1 - CLEARANCE * compression(rise, pressure, k);
    }

    /**
     * {@code w_s}, the isentropic work per suction volume of a rise of {@code rise} from {@code pressure}, J/m³:
     * {@code p n/(n − 1) (((p + ΔP)/p)^((n−1)/n) − 1)}, in the form {@code p/a expm1(a ln((p + ΔP)/p))} with
     * {@code a = 1 − 1/n}, so it is {@code ΔP} itself as {@code n} grows without bound.
     */
    public static double isentropicWork(double rise, double pressure, double k) {
        double p = Math.max(LEAST_PRESSURE, pressure), a = 1 - 1 / exponent(k);
        return p / a * Math.expm1(a * logRatio(rise, pressure));
    }

    /** The suction volume flow, m³/s, the law delivers at {@code rise} (Pa): the share of the displacement less the clearance
     * loss. Falls strictly with the rise at every rise; finite at every rise. */
    public double flow(double rise, double speed, double pressure, double k) {
        return capacity(speed) * (PositiveDisplacement.share(rise / reliefRise) - CLEARANCE * compression(rise, pressure, k));
    }

    /** {@code d flow / d rise}, m³/s per Pa: below zero at every rise. */
    double flowSlope(double rise, double speed, double pressure, double k) {
        double p = Math.max(LEAST_PRESSURE, pressure), n = exponent(k);
        // Past the discharge pressure's floor the clearance term stands still.
        double clearance = p + rise > LEAST_PRESSURE ? CLEARANCE * (1 + compression(rise, pressure, k)) / (n * (p + rise)) : 0;
        return capacity(speed) * (PositiveDisplacement.shareSlope(rise / reliefRise) / reliefRise - clearance);
    }

    @Override public boolean liquid() {
        return true;
    }

    /**
     * The rise, Pa, at which the law delivers {@code flow}: the inverse of {@link #flow} by a safeguarded Newton inside a
     * bracket, from the law with no clearance ({@link PositiveDisplacement#rise}, an upper bound wherever it is at or above
     * zero). At most {@link #MOST_STEPS} steps, to {@link #TOLERANCE} relative; on a miss, the bracket's midpoint.
     */
    @Override public double rise(double flow, double speed, double density, double pressure, double k) {
        double free = row(speed).rise(flow, 1), lo, hi;
        if (free >= 0) {
            // Below the upper bound the clearance loss is at most its value there: the free law at that much more flow.
            hi = free;
            lo = row(speed).rise(flow + capacity(speed) * CLEARANCE * compression(free, pressure, k), 1);
        } else {
            // A flow above the displacement: no rise delivers less, and the clearance gains below no rise.
            lo = free;
            hi = 0;
        }
        double x = hi;
        for (int step = 0; step < MOST_STEPS; step++) {
            double f = flow(x, speed, pressure, k) - flow;
            if (f == 0) return x;
            if (f > 0) lo = x;
            else hi = x;
            double next = x - f / flowSlope(x, speed, pressure, k);
            if (!(next > lo && next < hi)) next = .5 * (lo + hi);
            double tolerance = TOLERANCE * Math.max(Math.abs(next), 1e-6 * reliefRise);
            if (Math.abs(next - x) <= tolerance || hi - lo <= tolerance) return next;
            x = next;
        }
        return .5 * (lo + hi);
    }

    /** {@code 1 / (d flow / d rise)} at the law's rise at {@code flow}. */
    @Override public double riseSlope(double flow, double speed, double density, double pressure, double k) {
        return 1 / flowSlope(rise(flow, speed, density, pressure, k), speed, pressure, k);
    }

    /** The flow form (F2e): the relief rise times the flow less the law's flow at {@code head}, over the displacement. Its
     * flow derivative is the relief rise over the displacement at every point. */
    @Override public double residual(double flow, double head, double speed, double density, double pressure, double k) {
        return reliefRise * (flow - flow(head, speed, pressure, k)) / capacity(speed);
    }

    /** {@code Q w_s}: the suction volume flow times the isentropic work per suction volume; none backwards, at no rise or no
     * density. */
    @Override public double fluidPower(double massFlow, double rise, double density, double pressure, double k) {
        if (!(massFlow > 0) || !(rise > 0) || !(density > 0)) return 0;
        return massFlow / density * isentropicWork(rise, pressure, k);
    }

    /** The volume the plungers draw at {@code speed}, the displacement times {@code 1 − c (r − 1)}, against the isentropic
     * work of the law's rise at {@code flow}, over the mechanical efficiency: finite and above zero at no flow, none at a
     * standing train. */
    @Override public double shaftPower(double flow, double speed, double density, double pressure, double k) {
        if (!(speed > 0)) return 0;
        double rise = Math.max(0, rise(flow, speed, density, pressure, k));
        return displacement * speed * Math.max(0, volumetricEfficiency(rise, pressure, k)) * isentropicWork(rise, pressure, k) / MECHANICAL_EFFICIENCY;
    }

    /** The shaft power at rated speed against the relief rise on a liquid, W: the motor's sizing duty. */
    public double maximumShaftPower() {
        return displacement * reliefRise / MECHANICAL_EFFICIENCY;
    }

    @Override public double ratedFlow() {
        return displacement;
    }

    /** No head: its rise is the system's, up to the relief rise. */
    @Override public double ratedHead() {
        return 0;
    }

    @Override public double efficiency() {
        return MECHANICAL_EFFICIENCY;
    }
}
