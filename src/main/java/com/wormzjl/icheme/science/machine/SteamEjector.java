package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A steam ejector set at process-simulator depth (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md, owner "mover
 * with a curve" and "Yes, correlation"): the sheet's own correlation, so a sheet-sized set runs its sheet duty in the world.
 * Its capacity is the suction volume flow {@code Q = f A v(P_s)}: the suction's area at the vacuum suction speed
 * ({@link #speed}, 60 m/s from 5 kPa up, {@code 60 (5 kPa / P_s)^¼} below; the sheet's {@code Limit.VACUUM_SPEED} row reads
 * these numbers), {@code f} its motive's share ({@link #motiveShare}). Its steam is the sheet's law ({@link EjectorLaw#steam})
 * for the load that volume holds. Its maximum discharge rises with the motive: {@code 120 kPa × P_m / 1 MPa}. No mechanism, no
 * design point to set.
 *
 * @param suction the suction's bore, m: the world's ejector is sized by it, as the sheet's is
 */
public record SteamEjector(double suction) {
    /**
     * The motive pressure the speed is read at, Pa (the column door's and the tile's 10 bar), and the discharge the set holds
     * there (their 120 kPa): {@code EjectorUnit}'s defaults read them.
     */
    public static final double RATED_MOTIVE = 1e6, RATED_DISCHARGE = 1.2e5;
    /**
     * The vacuum suction speed, m/s, from {@link #SPEED_EDGE} bara up, and below it {@code SPEED (SPEED_EDGE / P_s)^SPEED_EXPONENT}
     * ([KARM]; batch 2026-11-10-steam-ejector-flow, owner "Yes, correlation"): the numbers of the sheet's {@code Limit.VACUUM_SPEED}
     * row, which reads them from here.
     */
    public static final double SPEED = 60, SPEED_EDGE = .05, SPEED_EXPONENT = .25;
    /** The motive nozzle's bore over the suction's: the drawn steam chest's neck ({@code ProcessUnit.ejector}). */
    public static final double MOTIVE_BORE = .3;

    public SteamEjector {
        if (!(suction > 0) || !Double.isFinite(suction)) throw new IllegalArgumentException("Invalid ejector suction " + suction);
    }

    /** The suction's area, m². */
    public double area() {
        return Math.PI / 4 * suction * suction;
    }

    /**
     * The vacuum suction speed at {@code pressure} Pa, m/s, read in bara as the sheet's row reads it: {@link #SPEED} from
     * {@link #SPEED_EDGE} up, below it {@code SPEED (SPEED_EDGE / P)^SPEED_EXPONENT} (the operations of the row's rising step,
     * {@code Bound.Step.at}, in their order). A pressure that is not a number has no speed, as it has no step of the row.
     */
    public static double speed(double pressure) {
        double key = Math.max(0, pressure) / 1e5;
        if (key < SPEED_EDGE) return SPEED * DeterministicMath.pow(SPEED_EDGE / Math.max(key, 1e-9 * SPEED_EDGE), SPEED_EXPONENT);
        if (key <= Double.POSITIVE_INFINITY) return SPEED;
        throw new IllegalArgumentException("Invalid suction pressure " + pressure);
    }

    /** The motive's share of the rated capacity at {@code motivePressure} Pa: {@code (P_m / 1 MPa) / motiveFactor(P_m)} (0.41 at 5 bar, 1.62 at 15). */
    public static double motiveShare(double motivePressure) {
        return motivePressure > 0 ? motivePressure / RATED_MOTIVE / EjectorLaw.motiveFactor(motivePressure) : 0;
    }

    /** The maximum discharge pressure on motive steam at {@code motivePressure} Pa: {@code P_dr P_m / 1 MPa}. */
    public static double maximumDischarge(double motivePressure) {
        return RATED_DISCHARGE * Math.max(0, motivePressure) / RATED_MOTIVE;
    }

    /**
     * The suction volume flow it takes, m³/s, at suction pressure {@code suctionPressure} Pa on motive steam at
     * {@code motivePressure} Pa, of which its nozzle gets the share {@code delivered} (0 to 1) of the steam its law asks.
     */
    public double capacity(double suctionPressure, double motivePressure, double delivered) {
        if (!(suctionPressure > 0) || !(delivered > 0)) return 0;
        return Math.min(1, delivered) * motiveShare(motivePressure) * area() * speed(suctionPressure);
    }

    /** The motive nozzle's bore, m. */
    public double motiveBore() {
        return MOTIVE_BORE * suction;
    }
}
