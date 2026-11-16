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
    /**
     * A centrifugal pump's minimum continuous flow, a share of its rated flow at its speed (N1; API 610's minimum
     * continuous stable flow): below it the pump recirculates at its suction and discharge and its vanes erode.
     */
    public static final double MINIMUM_FLOW = .3;
    /**
     * A centrifugal compressor's wet gas (N2): the condensed mass share of its suction stream at and below which its
     * impeller does not erode, and the span above it to full intensity (0.5 % to 5.5 %).
     */
    public static final double WET_GAS_ONSET = .005, WET_GAS_SPAN = .05;
    /**
     * A steam turbine's wet exhaust (N4): the condensed mass share of its own exhaust at and below which its last blades do
     * not erode, and the span above it to full intensity (1 % to 6 %). The share is the solve's, on the turbine's work law
     * (an ideal-gas isentropic drop on the inlet's heat capacity ratio), which takes less enthalpy from condensing steam than
     * the steam tables do: saturated steam from 40 bar to 0.1 bar reads 6.2 % where the tables give about 19 % at the same
     * efficiency, and 150 K of superheat reads none. So the numbers are set on that scale: saturated steam wears at the
     * full rate, enough superheat at none.
     */
    public static final double WET_EXHAUST_ONSET = .01, WET_EXHAUST_SPAN = .05;
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
     * {@code LIQUID} from a dry screw's suction liquid volume share ({@link VacuumPump#liquidIntensity}, N5);
     * {@code LOW_FLOW} from a centrifugal pump's flow over its rated flow at its speed ({@link #lowFlow}, N1);
     * {@code WET_GAS} from a centrifugal compressor's suction condensed mass share ({@link #wetGas}, N2);
     * {@code WET_EXHAUST} from a steam turbine's exhaust condensed mass share ({@link #wetExhaust}, N4).
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
                case LOW_FLOW -> lowFlow(q);
                case WET_GAS -> wetGas(q);
                case WET_EXHAUST -> wetExhaust(q);
            };
        }
    }

    /**
     * A steam turbine's wet exhaust erosion intensity at {@code share}, the condensed mass share of its exhaust (N4): the
     * moisture of the last stages erodes their blades. {@code (share − 0.01) / 0.05}, between 0 and 1: 0 at and below 1 %
     * and for NaN, one from 6 % up. In the erosion class: at the default lives 8 h of process time from 6 %, 16 h at 3.5 %.
     */
    public static double wetExhaust(double share) {
        if (!(share > WET_EXHAUST_ONSET)) return 0;
        return Math.min(1, (share - WET_EXHAUST_ONSET) / WET_EXHAUST_SPAN);
    }

    /**
     * The wet-exhaust onset at 20 kPa and at 10 kPa (batch 2026-11-15-equipment-visual-design, W5; owner: "you need to
     * change the wear rules instead"): a condensing last stage is built for its design moisture, and the fast damage is
     * from inlet steam that lost its superheat. Each is the largest condensed share of the four inlet classes' design steam
     * expanded to that exhaust on the solve's work law (W1, 10 MW at η 0.76: at 20 kPa MP 4.66 %, LP 4.50 %, HP 3.91 %,
     * VHP 3.36 %; at 10 kPa LP 5.45 %, MP 5.39 %, HP 4.61 %, VHP 4.06 %), up to the next 0.1 %, plus 0.1 %.
     */
    public static final double WET_ONSET_20_KPA = .048, WET_ONSET_10_KPA = .056;
    /** The onset's exhaust pressures, Pa: {@link #WET_EXHAUST_ONSET} at and above the first, the two above at the others. */
    static final double ONSET_FROM = 150e3, ONSET_20 = 20e3, ONSET_10 = 10e3;

    /**
     * A steam turbine's wet-exhaust onset at {@code exhaustPressure}, Pa (W5): {@link #WET_EXHAUST_ONSET} from 150 kPa up
     * (and for NaN), {@link #WET_ONSET_20_KPA} at 20 kPa, {@link #WET_ONSET_10_KPA} at 10 kPa and below, linear in ln P
     * between: continuous, with no step at the vacuum limit.
     */
    public static double wetOnset(double exhaustPressure) {
        if (!(exhaustPressure < ONSET_FROM)) return WET_EXHAUST_ONSET;
        if (exhaustPressure <= ONSET_10) return WET_ONSET_10_KPA;
        double x = DeterministicMath.log(exhaustPressure);
        if (exhaustPressure >= ONSET_20) {
            double a = DeterministicMath.log(ONSET_20), b = DeterministicMath.log(ONSET_FROM);
            return WET_ONSET_20_KPA + (WET_EXHAUST_ONSET - WET_ONSET_20_KPA) * (x - a) / (b - a);
        }
        double a = DeterministicMath.log(ONSET_10), b = DeterministicMath.log(ONSET_20);
        return WET_ONSET_10_KPA + (WET_ONSET_20_KPA - WET_ONSET_10_KPA) * (x - a) / (b - a);
    }

    /**
     * A turbine's wet-exhaust driving quantity (W5): its exhaust's condensed {@code share} less the rise of the onset at
     * {@code exhaustPressure} (Pa) over {@link #WET_EXHAUST_ONSET}, so {@link #wetExhaust(double)} and
     * {@link Fault#intensity} read it as they read a share. The share itself from 150 kPa up; NaN stays NaN.
     */
    public static double wetExcess(double share, double exhaustPressure) {
        return share - (wetOnset(exhaustPressure) - WET_EXHAUST_ONSET);
    }

    /** A steam turbine's wet-exhaust erosion intensity at {@code share} exhausting at {@code exhaustPressure}, Pa (W5): {@code (share − onset) / 0.05}, 0 to 1. */
    public static double wetExhaust(double share, double exhaustPressure) {
        double onset = wetOnset(exhaustPressure);
        if (!(share > onset)) return 0;
        return Math.min(1, (share - onset) / WET_EXHAUST_SPAN);
    }

    /**
     * A centrifugal compressor's wet gas erosion intensity at {@code share}, the condensed mass share of its suction stream
     * (N2): droplets erode its impeller. {@code (share − 0.005) / 0.05}, between 0 and 1: 0 at and below 0.5 % and for NaN,
     * one from 5.5 % up. In the erosion class: at the default lives 8 h of process time from 5.5 %, 16 h at 3 %.
     */
    public static double wetGas(double share) {
        if (!(share > WET_GAS_ONSET)) return 0;
        return Math.min(1, (share - WET_GAS_ONSET) / WET_GAS_SPAN);
    }

    /**
     * A centrifugal pump's low-flow erosion intensity at {@code share} = Q / (Q_r s), its suction volume flow over its rated
     * flow at its speed share (N1): {@code ((0.3 − share) / 0.3)²}, one against a closed discharge, 0 from 30 % up and for NaN.
     * In the erosion class: at the default lives 8 h of process time dead-headed, 32 h at 15 %.
     */
    public static double lowFlow(double share) {
        if (!(share < MINIMUM_FLOW)) return 0;
        double x = Math.min(1, (MINIMUM_FLOW - share) / MINIMUM_FLOW);
        return x * x;
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
