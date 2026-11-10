package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.flowsheet.EjectorLaw;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;

/**
 * A steam ejector set at process-simulator depth (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md, owner "mover
 * with a curve" and "Yes, correlation"): the sheet's own correlation, so a sheet-sized set runs its sheet duty in the world.
 * Its capacity is the suction volume flow {@code Q = f A v(P_s)}: the suction's area at the simulator's vacuum suction speed
 * ({@link Limit#VACUUM_SPEED}, 60 m/s from 5 kPa up, {@code 60 (5 kPa / P_s)^¼} below), {@code f} its motive's share
 * ({@link #motiveShare}). Its steam is the sheet's law ({@link EjectorLaw#steam}) for the load that volume holds. Its maximum
 * discharge rises with the motive: {@code 120 kPa × P_m / 1 MPa}. No mechanism, no design point to set.
 *
 * @param suction the suction's bore, m: the world's ejector is sized by it, as the sheet's is
 */
public record SteamEjector(double suction) {
    /** The motive pressure the speed is read at, Pa (the column door's and the tile's 10 bar), and the discharge the set holds there (their 120 kPa). */
    public static final double RATED_MOTIVE = com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit.MOTIVE_PRESSURE,
            RATED_DISCHARGE = com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit.DISCHARGE_PRESSURE;
    /** The motive nozzle's bore over the suction's: the drawn steam chest's neck ({@code ProcessUnit.ejector}). */
    public static final double MOTIVE_BORE = .3;

    public SteamEjector {
        if (!(suction > 0) || !Double.isFinite(suction)) throw new IllegalArgumentException("Invalid ejector suction " + suction);
    }

    /** The suction's area, m². */
    public double area() {
        return Math.PI / 4 * suction * suction;
    }

    /** The vacuum suction speed at {@code pressure} Pa, m/s: the sheet's row, read where the sheet reads it (bara). */
    public static double speed(double pressure) {
        return Limit.VACUUM_SPEED.value(Math.max(0, pressure) / 1e5);
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
