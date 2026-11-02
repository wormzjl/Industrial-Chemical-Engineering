package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.display.DisplayUnit;
import java.util.Locale;

/**
 * The units the display keys chose (batch 2026-11-02-process-simulator): temperature, pressure, the flow's time, mole or mass basis.
 * Every builder reads SI values from a sheet or a run and formats them here, so a key changes all of the screen at once.
 */
public record SheetUnits(DisplayUnit.TemperatureUnit temperature, DisplayUnit.PressureUnit pressure, DisplayUnit.FlowRateUnit time, boolean mass) {
    public static final SheetUnits DEFAULT = new SheetUnits(DisplayUnit.TemperatureUnit.CELSIUS, DisplayUnit.PressureUnit.BAR, DisplayUnit.FlowRateUnit.PER_HOUR, false);

    public static String number(int digits, double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%." + digits + "f", value) : "—";
    }

    public String t(double kelvin) {
        return number(1, temperature.display(kelvin));
    }

    /** A temperature difference, which has no offset. */
    public String kelvins(double difference) {
        return number(1, difference);
    }

    public String p(double pascals) {
        return pressure.number(pascals);
    }

    /** Mol/s as kmol over the chosen time. */
    public String moles(double perSecond) {
        return number(1, time.display(perSecond) / 1000);
    }

    /** Kg/s as tonnes over the chosen time. */
    public String tonnes(double perSecond) {
        return number(2, time.display(perSecond) / 1000);
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
