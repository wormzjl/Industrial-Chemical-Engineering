package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A chimney's sizing and reading (batch 2026-11-10-chimney, CHIMNEY_PLAN.md): pure arithmetic the engine, the simulator
 * and the constructor share. The stack itself is the run into its tip (the network's column, friction and exit loss,
 * {@link #STACK_LOSS}); its tip stands at the atmosphere of its height ({@link PhaseLayout#atmosphere}, owner C1 B).
 *
 * <ul>
 * <li>Two forms (owner C3 A): a self-supporting steel stack and a tapered brick or concrete chimney with a steel liner,
 * each with its diameter range, height range, design exit velocity (owner C7) and gas temperature notice.</li>
 * <li>Sizing (owner C7, the 20 % spare): the exit area for {@link #SPARE} times the design volume flow at the form's design
 * velocity; the exit velocity's window {@link #LOW_VELOCITY} to {@link #HIGH_VELOCITY} m/s at the design flow (stack
 * downwash below 1.5 times a 5 m/s wind; the exit loss above); the height by good engineering practice with L = H_b,
 * {@link #gepHeight} (owner C2 A).</li>
 * <li>Smoke (owner C4 A): Beer-Lambert opacity across the plume, {@code 1 - exp(-k C D)}, one specific extinction
 * {@link #EXTINCTION} for every solid.</li>
 * </ul>
 */
public final class Chimney {
    private Chimney() {
    }

    /** A chimney's form and its ranges: diameter m (the exit's), its step, height m, design exit velocity m/s, notice temperature K. */
    public enum Form {
        STEEL(.4, 3, .1, 10, 60, 20, 673.15),
        BRICK(1, 8, .25, 30, 150, 12, 873.15);
        public final double minDiameter, maxDiameter, diameterStep, designVelocity, hotLimit;
        public final int minHeight, maxHeight;

        Form(double minDiameter, double maxDiameter, double diameterStep, int minHeight, int maxHeight, double designVelocity, double hotLimit) {
            this.minDiameter = minDiameter;
            this.maxDiameter = maxDiameter;
            this.diameterStep = diameterStep;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.designVelocity = designVelocity;
            this.hotLimit = hotLimit;
        }

        /** The tallest chimney of this form at exit diameter {@code d}, m: a self-supporting steel stack at most {@link #SLENDERNESS} diameters. */
        public int maxHeight(double d) {
            return this == STEEL ? Math.max(minHeight, Math.min(maxHeight, (int) Math.floor(SLENDERNESS * d + 1e-9))) : maxHeight;
        }
    }

    /** A self-supporting steel stack's most height over its diameter (above it, guys or a derrick, not built). */
    public static final double SLENDERNESS = 30;
    /** The design spare on the design flow (owner rule: "always have enough excess"). */
    public static final double SPARE = 1.2;
    /** The exit velocity's window at the design flow, m/s (owner C7). */
    public static final double LOW_VELOCITY = 7.5, HIGH_VELOCITY = 30;
    /** The stack's minor losses in exit velocity heads: entry 0.5 and exit 1.0. */
    public static final double STACK_LOSS = 1.5;
    /** Good engineering practice height over the nearby structure's (H + 1.5 L, L = H), and the least height, m. */
    public static final double GEP = 2.5, MIN_HEIGHT = 10;
    /** The specific extinction of the plume's solids, m²/kg (1 m²/g, owner C4 A). */
    public static final double EXTINCTION = 1000;
    /** The gas inlet's centre over grade, m: on the second block's centre, where its pipe's centre line runs. */
    public static final double INLET = 1.5;
    /** Below this mass flow, kg/s, nothing flows. */
    public static final double NO_FLOW = 1e-6;

    /** {@link #gepHeight} of a nearby structure {@code nearby} m high, on whole metres. */
    public static int gepHeight(double nearby) {
        return (int) Math.ceil(Math.max(MIN_HEIGHT, GEP * Math.max(0, nearby)) - 1e-9);
    }

    /** The exit diameter for {@code volumeFlow} m³/s (at the exit) with the spare at {@code form}'s design velocity, m, before the grid. */
    public static double designDiameter(Form form, double volumeFlow) {
        return Math.sqrt(4 * SPARE * Math.max(0, volumeFlow) / (Math.PI * form.designVelocity));
    }

    /** The exit velocity of {@code volumeFlow} m³/s through a mouth {@code d} m across, m/s. */
    public static double exitVelocity(double volumeFlow, double d) {
        return d > 0 ? Math.max(0, volumeFlow) / (Math.PI * d * d / 4) : 0;
    }

    /** The plume's opacity, 0 to 1, of {@code solids} kg/s in {@code volumeFlow} m³/s leaving a mouth {@code d} m across. */
    public static double opacity(double solids, double volumeFlow, double d) {
        if (!(solids > 0) || !(volumeFlow > 0)) return 0;
        return 1 - DeterministicMath.exp(-EXTINCTION * solids / volumeFlow * d);
    }

    /** The draft of a column {@code height} m of gas of density {@code density} kg/m³ in the outside air, Pa. */
    public static double draft(double density, double height) {
        return (PhaseLayout.AIR_DENSITY - density) * 9.80665 * height;
    }

    /** The density of an ideal gas of molar mass {@code molarMass} kg/mol at {@code temperature} K and {@code pressure} Pa, kg/m³. */
    public static double density(double molarMass, double temperature, double pressure) {
        return temperature > 0 ? pressure * molarMass / (8.314462618 * temperature) : 0;
    }

    /**
     * The gas a chimney receives, read from the stream at its inlet: {@code massFlow} kg/s of fluid and {@code solids} kg/s,
     * at {@code temperature} K, {@code molarMass} kg/mol, {@code waterShare} kg of water per kg of dry gas, {@code wet} when
     * liquid arrives; leaving a mouth {@code d} m across at {@code pressure} Pa.
     */
    public record Reading(double massFlow, double solids, double temperature, double molarMass, double waterShare, boolean wet, double d, double pressure) {
        public double density() {
            return Chimney.density(molarMass, temperature, pressure);
        }

        /** The volume flow at the exit, m³/s. */
        public double volumeFlow() {
            double rho = density();
            return rho > 0 ? massFlow / rho : 0;
        }

        public double exitVelocity() {
            return Chimney.exitVelocity(volumeFlow(), d);
        }

        public double opacity() {
            return Chimney.opacity(solids, volumeFlow(), d);
        }
    }

    /**
     * A reading of {@code moles} (any amounts, water last) of molar masses {@code molarMasses} at {@code temperature},
     * {@code massFlow} kg/s of fluid with {@code solids} kg/s: its molar mass and its water per dry gas.
     */
    public static Reading reading(double[] moles, double[] molarMasses, double temperature, double massFlow, double solids, boolean wet, double d, double pressure) {
        double total = 0, mass = 0, water = 0;
        int last = moles.length - 1;
        for (int c = 0; c < moles.length; c++) {
            double n = Math.max(0, moles[c]);
            total += n;
            mass += n * molarMasses[c];
            if (c == last) water = n * molarMasses[c];
        }
        double molar = total > 0 ? mass / total : .029, dry = mass - water;
        return new Reading(Math.max(0, massFlow), Math.max(0, solids), temperature, molar, dry > 0 ? water / dry : 0, wet, d, pressure);
    }

    /** A chimney's status, by name; its text is the status word (owner C5 A, C6 A, C7). */
    public enum Status {
        NO_FLOW("NO FLOW"), VENTING("VENTING"), ABOVE_RATING("ABOVE RATING"), WET_STACK("WET STACK"), HOT_STACK("HOT STACK"), NO_DRAFT("NO DRAFT");
        public final String text;

        Status(String text) {
            this.text = text;
        }
    }

    /**
     * The status of {@code r} in a chimney of {@code form}: NO DRAFT when nothing flows while gas stands at its inlet (the
     * inlet below the tip's atmosphere and the column, the void one-way, owner C6 A), NO FLOW when no gas stands there,
     * then WET STACK (liquid, C5 A), ABOVE RATING (exit velocity over {@link #HIGH_VELOCITY}), HOT STACK (over the form's
     * temperature), VENTING.
     */
    public static Status status(Form form, Reading r, boolean gasAtInlet) {
        if (r.massFlow() < NO_FLOW) return gasAtInlet ? Status.NO_DRAFT : Status.NO_FLOW;
        if (r.wet()) return Status.WET_STACK;
        if (r.exitVelocity() > HIGH_VELOCITY) return Status.ABOVE_RATING;
        if (r.temperature() > form.hotLimit) return Status.HOT_STACK;
        return Status.VENTING;
    }

    /** The rated volume flow of a chimney of exit diameter {@code d}, m³/s: the top of the exit velocity's window. */
    public static double ratedVolumeFlow(double d) {
        return HIGH_VELOCITY * Math.PI * d * d / 4;
    }
}
