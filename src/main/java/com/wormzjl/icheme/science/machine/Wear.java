package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A pump's wear (batch 2026-11-03-rotating-machinery W10b, build plan revision 2, owner decision D34): its health falls
 * from 1 (new) to 0 (failed) from two faults the player can see and fix, never from clean running.
 * <ul>
 * <li><b>Cavitation:</b> {@code I_c(σ) / τ_c}, with {@code σ = NPSHA / NPSH3} and Gülich's erosion intensity
 * {@code I_c = (σ/2.06)³ ((4 − σ)/1.94)^2.83} for {@code 0 < σ < 4}, else 0 (plan section 3.7 without the gas cushion):
 * one at its peak σ = 2.06, the measured maximum near twice NPSH3; τ_c = {@link #CAVITATION_PROCESS_HOURS} h. Lives in
 * process time: 8.0 h at σ 2.06, 20.4 h at NPSH3 (σ 1), 75.6 h at σ 3.5, none from σ 4 (inception) up.</li>
 * <li><b>Running dry:</b> {@code 1 / τ_d} while its driver is on and it is gas- or vapour-locked, τ_d =
 * {@link #DRY_PROCESS_MINUTES} min.</li>
 * </ul>
 * Every fault of every machine (code base review M-12, {@link Fault}) has an intensity, 0 to 1, from its driving quantity
 * and a class: an {@link FaultClass#EROSION erosion} fault spends the erosion life ({@link Settings#cavitationHours}), a
 * {@link FaultClass#SEIZURE seizure} fault the seizure life ({@link Settings#dryMinutes}). Within a class the largest
 * intensity counts; the two classes add ({@link #rate(double, double, Settings)}).
 * Pure: no state. The engine books {@code rate × Δt} over committed process time (batch 2026-11-11-minecraft-time-delta, W4,
 * owner T3: wear in line with the time factor; the default lives four times the old online ones, so at the default factor 4
 * a pump lasts as long in play as before: 2 h at the peak and 10 min dry).
 */
public final class Wear {
    private Wear() {}

    /** The cavitation life at the erosion peak, h of process time. */
    public static final double CAVITATION_PROCESS_HOURS = 8;
    /** The life running dry, min of process time. */
    public static final double DRY_PROCESS_MINUTES = 40;
    /** The σ of the erosion peak, and the inception σ above which nothing erodes. */
    public static final double PEAK = 2.06, INCEPTION = 4;

    /**
     * The config's three keys ({@code machines.wear.*}); the lives in process time: {@code cavitationHours} is the erosion
     * life (at intensity one) and {@code dryMinutes} the seizure life (M-12: the keys keep their names).
     */
    public record Settings(boolean enabled, double cavitationHours, double dryMinutes) {
        public static final Settings DEFAULT = new Settings(true, CAVITATION_PROCESS_HOURS, DRY_PROCESS_MINUTES);

        public Settings {
            if (!(cavitationHours > 0) || !Double.isFinite(cavitationHours) || !(dryMinutes > 0) || !Double.isFinite(dryMinutes))
                throw new IllegalArgumentException("Invalid wear lives " + cavitationHours + " h, " + dryMinutes + " min");
        }
    }

    /** How a fault wears a machine: erosion over the erosion life, seizure over the seizure life. */
    public enum FaultClass { EROSION, SEIZURE }

    /**
     * A machine's faults (M-12), each with its class and its intensity, 0 to 1, from its driving quantity {@code q}:
     * {@code CAVITATION} from σ = NPSHA/NPSH3 ({@link #intensity}); {@code DRY} 1 for {@code q > 0} (the pump runs dry);
     * {@code LIQUID} from a dry screw's suction liquid volume share ({@link VacuumPump#liquidIntensity}, N5); the others
     * wear nothing yet.
     */
    public enum Fault {
        CAVITATION(FaultClass.EROSION), DRY(FaultClass.SEIZURE), LOW_FLOW(FaultClass.EROSION), WET_GAS(FaultClass.EROSION),
        LIQUID(FaultClass.SEIZURE), WET_EXHAUST(FaultClass.EROSION);

        public final FaultClass type;

        Fault(FaultClass type) {
            this.type = type;
        }

        /** The intensity, 0 to 1, at driving quantity {@code q}. */
        public double intensity(double q) {
            return switch (this) {
                case CAVITATION -> Wear.intensity(q);
                case DRY -> q > 0 ? 1 : 0;
                case LIQUID -> VacuumPump.liquidIntensity(q);
                case LOW_FLOW, WET_GAS, WET_EXHAUST -> 0;
            };
        }
    }

    /** Gülich's erosion intensity at {@code sigma} = NPSHA/NPSH3, one at its peak: 0 at and below 0, from 4 up and for NaN. */
    public static double intensity(double sigma) {
        if (!(sigma > 0) || !(sigma < INCEPTION)) return 0;
        double a = sigma / PEAK, b = (INCEPTION - sigma) / (INCEPTION - PEAK);
        return a * a * a * DeterministicMath.pow(b, 2.83);
    }

    /**
     * The rate rule (M-12): the health a machine loses per second of process time at its largest erosion intensity
     * {@code erosion} and its largest seizure intensity {@code seizure}; the two classes add. 0 with wear switched off.
     */
    public static double rate(double erosion, double seizure, Settings settings) {
        if (!settings.enabled()) return 0;
        return erosion / (settings.cavitationHours() * 3600) + seizure / (settings.dryMinutes() * 60);
    }

    /** The health a pump loses per second of process time at {@code sigma} (NaN: no cavitation read) and running dry or not. */
    public static double rate(double sigma, boolean dry, Settings settings) {
        return rate(Fault.CAVITATION.intensity(sigma), Fault.DRY.intensity(dry ? 1 : 0), settings);
    }

    /** {@link #rate} at the default lives. */
    public static double rate(double sigma, boolean dry) {
        return rate(sigma, dry, Settings.DEFAULT);
    }

    /** The process life, s, of a new pump at a steady {@code sigma} and dry state: infinite where nothing wears. */
    public static double life(double sigma, boolean dry, Settings settings) {
        double r = rate(sigma, dry, settings);
        return r > 0 ? 1 / r : Double.POSITIVE_INFINITY;
    }
}
