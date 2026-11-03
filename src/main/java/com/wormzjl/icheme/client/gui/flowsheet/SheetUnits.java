package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.display.DisplayUnit;
import java.util.Locale;

/**
 * The units the display keys chose (batch 2026-11-02-process-simulator): temperature, pressure, the flow's time, and the
 * basis a stream's flow reads in. Every builder reads SI values from a sheet or a run and formats them here, so a key
 * changes all of the screen at once.
 */
public record SheetUnits(DisplayUnit.TemperatureUnit temperature, DisplayUnit.PressureUnit pressure, DisplayUnit.FlowRateUnit time, Basis basis) {
    /** The mol/s in one kmol/h: the column's own settings and results, templates and the 100 kmol/h a new flow starts at are in kmol/h. */
    public static final double KMOL_PER_HOUR = 1000 / 3600.0;
    public static final SheetUnits DEFAULT = new SheetUnits(DisplayUnit.TemperatureUnit.CELSIUS, DisplayUnit.PressureUnit.BAR, DisplayUnit.FlowRateUnit.PER_HOUR, Basis.MOLE);

    /**
     * What a stream's flow reads in, the Mole, Mass and Volume display key (the owner, 2026-11-04: "The flowrate definition
     * in stream should follow the mass/mole button, and add volume to that button as well"): kmol, tonnes, or m³ at the
     * stream's own temperature and pressure (the owner's "Actual volume").
     */
    public enum Basis {
        MOLE, MASS, VOLUME;

        public Basis next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * A stream's flow on the chosen basis, from its mol/s, its kg/s and its density in kg/m³: the one place a flow is shown
     * by the key. Empty in Volume where the density is not known.
     */
    public String flow(double moles, double mass, double density) {
        return switch (basis) {
            case MOLE -> moles(moles);
            case MASS -> tonnes(mass);
            case VOLUME -> density > 0 && Double.isFinite(density) ? number(1, time.display(mass / density)) : "";
        };
    }

    /** The unit {@link #flow} reads in. */
    public String flowUnit() {
        return switch (basis) {
            case MOLE -> molesUnit();
            case MASS -> tonnesUnit();
            case VOLUME -> time.unit("m³");
        };
    }

    /**
     * The mol/s a flow typed on the chosen basis means, for a stream of {@code kgPerMole} mean molar mass and
     * {@code density} kg/m³: the one place a typed flow goes back. NaN where it means nothing (Volume with no density).
     */
    public double molesOf(double typed, double kgPerMole, double density) {
        double perSecond = time.perSecond(typed);
        boolean weighed = kgPerMole > 0 && Double.isFinite(kgPerMole);
        return switch (basis) {
            case MOLE -> molesPerSecond(typed);
            case MASS -> weighed ? perSecond * 1000 / kgPerMole : Double.NaN;
            case VOLUME -> weighed && density > 0 && Double.isFinite(density) ? perSecond * density / kgPerMole : Double.NaN;
        };
    }

    public static String number(int digits, double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%." + digits + "f", value) : "—";
    }

    public String t(double kelvin) {
        return number(1, temperature.display(kelvin));
    }

    /** A pressure at the digits a meter shows; typing keeps what the player typed, so nothing rounds on the way back. */
    public String p(double pascals) {
        return number(digits(), pressure.display(pascals));
    }

    /** A pressure drop, two digits finer than {@link #p}: a tray's few hundred pascals still read in bar. */
    public String drop(double pascals) {
        return number(digits() + 2, pressure.display(pascals));
    }

    private int digits() {
        return switch (pressure) {
            case PASCAL -> 0;
            case KILOPASCAL -> 1;
            case BAR -> 2;
            case MEGAPASCAL -> 3;
        };
    }

    /** Mol/s as kmol over the chosen time. */
    public String moles(double perSecond) {
        return number(1, time.display(perSecond) / 1000);
    }

    /** Kg/s as tonnes over the chosen time. */
    public String tonnes(double perSecond) {
        return number(2, time.display(perSecond) / 1000);
    }

    /**
     * The mol/s a typed molar flow means, kmol over the chosen time: how a unit's own flow setting reads back, which stays molar
     * whatever the basis (a draw's or an outlet's makeup is known only after a run), so only its time follows the key.
     */
    public double molesPerSecond(double typed) {
        return time.perSecond(typed) * 1000;
    }

    public String molesUnit() {
        return time.unit("kmol");
    }

    public String tonnesUnit() {
        return time.unit("t");
    }

    /** W as MW. */
    public static String megawatts(double watts) {
        return number(2, watts / 1e6);
    }
}
