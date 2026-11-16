package com.wormzjl.icheme.science.machine;

import java.util.List;

/**
 * A triplex reciprocating plunger pump on a shaft (API 674; batch 2026-11-15-supercritical-fluids, stage S8,
 * PLUNGER_PUMP_PLAN.md section 3): it displaces a fixed suction volume per turn and delivers it against whatever rise the
 * system needs, up to its relief rise, whatever the fluid's density. It is the pump for one dense fluid lighter than its
 * critical density, which a centrifugal pump cannot lift, and for small flows to high pressures.
 *
 * <p>Its law is {@link PositiveDisplacement}'s own (form F2e), with the displacement at the train's speed: the
 * displacement less a slip of {@link PositiveDisplacement#SLIP} per relief rise, then the relief line, whose valve passes the
 * whole delivery back at the relief rise. Density, pressure and the heat capacity ratio enter neither its rise nor its row.
 * The work booked into the fluid is the suction volume flow times the rise ({@code Q ΔP}); the shaft takes the whole
 * displacement at its speed times the rise over {@link #MECHANICAL_EFFICIENCY}, since the plungers displace against the
 * rise whether the fluid is delivered or relieved.
 *
 * <p>Left out (PD_PUMP_RESEARCH_REVIEW.md, owner options 2 A and 3, "Accept all"): the clearance term
 * {@code η_v = 1 − c (ρ_d/ρ_s − 1)}, so the delivery is 0.4 to 4.5 % high on a dense fluid at a clearance of a tenth; and
 * the real isentropic work, so the work is 2 to 21 % high on a dense fluid (on the safe side for the driver). No NPSH3 of
 * its own: its suction limit is the liquid suction's E and p_v.
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
    /** The displacement left at a standing train, m³/s: the row needs a displacement above zero. */
    private static final double NONE = 1e-12;

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

    /** The row's law at {@code speed} (share of rated), read at speed 1: the displacement scaled by the speed, as
     * {@link VacuumPump#row} scales its capacity. */
    public PositiveDisplacement row(double speed) {
        double q = displacement * Math.max(0, speed);
        return new PositiveDisplacement(q > NONE ? q : NONE, reliefRise);
    }

    @Override public boolean liquid() {
        return true;
    }

    @Override public double rise(double flow, double speed, double density, double pressure, double k) {
        return row(speed).rise(flow, 1);
    }

    @Override public double riseSlope(double flow, double speed, double density, double pressure, double k) {
        return row(speed).riseSlope(flow, 1);
    }

    /** The flow form (F2e): a displacement law in the head form is near vertical. */
    @Override public double residual(double flow, double head, double speed, double density, double pressure, double k) {
        return row(speed).residual(flow, head, 1);
    }

    /** {@code Q ΔP}: the suction volume flow times the rise; none backwards, at no rise or no density. */
    @Override public double fluidPower(double massFlow, double rise, double density, double pressure, double k) {
        if (!(massFlow > 0) || !(rise > 0) || !(density > 0)) return 0;
        return massFlow / density * rise;
    }

    /** The whole displacement at {@code speed} against the law's rise at {@code flow}, over the mechanical efficiency:
     * finite and above zero at no flow, none at a standing train. */
    @Override public double shaftPower(double flow, double speed, double density, double pressure, double k) {
        if (!(speed > 0)) return 0;
        return displacement * speed * Math.max(0, rise(flow, speed, density, pressure, k)) / MECHANICAL_EFFICIENCY;
    }

    /** The shaft power at rated speed against the relief rise, W: the motor's sizing duty. */
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
