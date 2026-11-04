package com.wormzjl.icheme.science.machine;

/**
 * The Create coupling's power rule (batch 2026-11-03-rotating-machinery, W6b form A, owner decision D40; plan section 8,
 * decision D7): one FE per tick trades for {@link #STRESS_PER_FE} stress units, so a stress unit is worth
 * {@code 20 × (J per FE) / (su per FE)} W, 1.465 W at the defaults. The coupling drives its train at Create's |rpm| up
 * to the power its Create network has to spare, and draws on that network the stress of the power its train takes.
 * Minecraft-free: the engine reads Create's inputs as queued events and sends the stress back on its schedule.
 */
public final class CreateCoupling {
    /** Stress units one FE per tick trades for (config {@code create.stressPerFE}; Crafts and Additions' and New Age's rate). */
    public static final double STRESS_PER_FE = 34.13;
    /** Create's highest speed, rpm: a coupling's design speed, which an auto gearbox behind it matches (reading A142). */
    public static final double DESIGN_SPEED = 256;
    /** A spare-stress change smaller than this share of the larger value, and than {@link #STRESS_FLOOR} su, wakes nothing (reading A143). */
    public static final double STRESS_DEADBAND = .01, STRESS_FLOOR = 1;
    /** A Create speed change smaller than this, rpm, wakes nothing (Create's speeds are whole numbers). */
    public static final double SPEED_DEADBAND = 1e-3;
    /** An impact change smaller than this share of the larger value is not sent to Create (reading A143). */
    public static final double IMPACT_DEADBAND = 1e-3;
    /** The share of the network's spare stress the coupling may take: a margin for Create's float sums (reading A144). */
    public static final double SPARE_SHARE = 1 - 1e-4;

    private CreateCoupling() {
    }

    /** Watts per stress unit: {@code 20 × joulesPerFE / stressPerFE} (1.465 W at 2.5 J per FE and 34.13 su per FE). */
    public static double wattsPerStress(double joulesPerFE, double stressPerFE) {
        if (!(joulesPerFE > 0) || !(stressPerFE > 0) || !Double.isFinite(joulesPerFE + stressPerFE)) throw new IllegalArgumentException("Invalid FE or stress rate");
        return 20 * joulesPerFE / stressPerFE;
    }

    /**
     * The coupling's stress impact, su per rpm: the power its train takes, at most {@code limit} W, over
     * {@code wattsPerStress × rpm}; 0 at rest. Create multiplies it by its |rpm| again, so the stress drawn is
     * {@code min(demand, limit) / wattsPerStress} su.
     */
    public static double impact(double demand, double limit, double rpm, double wattsPerStress) {
        if (!(rpm > 0) || !(wattsPerStress > 0)) return 0;
        return Math.min(Math.max(0, demand), Math.max(0, limit) * SPARE_SHARE) / (wattsPerStress * rpm);
    }

    /** Whether Create's input moved past its deadbands: a speed change, or a spare-stress change past 1 % and 1 su. */
    public static boolean moved(double rpm, double spare, double newRpm, double newSpare) {
        if (!Double.isFinite(rpm) || !Double.isFinite(spare)) return true;
        if (Math.abs(newRpm - rpm) > SPEED_DEADBAND) return true;
        return Math.abs(newSpare - spare) > Math.max(STRESS_FLOOR, STRESS_DEADBAND * Math.max(Math.abs(spare), Math.abs(newSpare)));
    }

    /** Whether a new impact differs from the one sent past {@link #IMPACT_DEADBAND}, or starts or stops a draw. */
    public static boolean impactMoved(double sent, double impact) {
        if ((sent > 0) != (impact > 0)) return true;
        return Math.abs(impact - sent) > IMPACT_DEADBAND * Math.max(Math.abs(sent), Math.abs(impact));
    }
}
