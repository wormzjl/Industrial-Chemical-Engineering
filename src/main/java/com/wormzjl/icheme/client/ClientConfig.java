package com.wormzjl.icheme.client;

import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The client's own settings, {@code icheme-client.toml} (batch 2026-10-28-gui-toolkit): the process screens' panel
 * theme and display units, one switch for every screen (owner: "Panel color and unit should be a client-wide switch,
 * so it persists between GUI and does not affect server side"). Controls cross the network in SI, so a unit never
 * reaches the server. Registered by {@code IChemE} as a client config, so a dedicated server never loads it; it names
 * no Minecraft classes and no screen classes.
 */
public final class ClientConfig {
    /** The panel door the process screens draw (GUI_TOOLKIT_PLAN.md section 10): 3a light, 3c dark. */
    public enum Theme {
        LIGHT, DARK
    }

    /** The basis the composition tables and flows show. */
    public enum Basis {
        MOLE, MASS
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<Theme> THEME;
    public static final ModConfigSpec.EnumValue<TemperatureUnit> TEMPERATURE;
    public static final ModConfigSpec.EnumValue<PressureUnit> PRESSURE;
    public static final ModConfigSpec.EnumValue<FlowRateUnit> FLOW_TIME;
    public static final ModConfigSpec.EnumValue<Basis> BASIS;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("gui");
        THEME = builder.comment("The process screens' control panel: LIGHT (a RAL 7035 grey door with red LED meters) or DARK (a RAL 7016 anthracite door with amber LED meters). The theme key on a screen's title row switches it too.")
                .defineEnum("theme", Theme.LIGHT);
        builder.pop();
        builder.comment("The units every process screen shows; the unit keys at a screen's foot switch them too. Display only: the server keeps SI.").push("units");
        TEMPERATURE = builder.comment("CELSIUS or KELVIN.").defineEnum("temperature", TemperatureUnit.CELSIUS);
        PRESSURE = builder.comment("PASCAL, KILOPASCAL, BAR or MEGAPASCAL.").defineEnum("pressure", PressureUnit.KILOPASCAL);
        FLOW_TIME = builder.comment("Flows per second (PER_SECOND), per minute (PER_MINUTE) or per hour (PER_HOUR).").defineEnum("flow_time", FlowRateUnit.PER_SECOND);
        BASIS = builder.comment("Compositions and flows in moles (MOLE) or in mass (MASS).").defineEnum("basis", Basis.MOLE);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /** The theme set, LIGHT before the config has loaded. */
    public static Theme theme() {
        return get(THEME, Theme.LIGHT);
    }

    public static Skin skin() {
        return theme() == Theme.DARK ? PanelSkin.DARK : PanelSkin.LIGHT;
    }

    /** Switches the theme and saves it to the file. */
    public static void toggleTheme() {
        set(THEME, theme() == Theme.DARK ? Theme.LIGHT : Theme.DARK);
    }

    public static TemperatureUnit temperature() {
        return get(TEMPERATURE, TemperatureUnit.CELSIUS);
    }

    public static void temperature(TemperatureUnit unit) {
        set(TEMPERATURE, unit);
    }

    public static PressureUnit pressure() {
        return get(PRESSURE, PressureUnit.KILOPASCAL);
    }

    public static void pressure(PressureUnit unit) {
        set(PRESSURE, unit);
    }

    public static FlowRateUnit flowTime() {
        return get(FLOW_TIME, FlowRateUnit.PER_SECOND);
    }

    public static void flowTime(FlowRateUnit unit) {
        set(FLOW_TIME, unit);
    }

    public static Basis basis() {
        return get(BASIS, Basis.MOLE);
    }

    public static void basis(Basis basis) {
        set(BASIS, basis);
    }

    /** The value set, {@code fallback} before the config has loaded. */
    private static <T extends Enum<T>> T get(ModConfigSpec.EnumValue<T> value, T fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    /** Sets the value and saves it to the file; nothing before the config has loaded. */
    private static <T extends Enum<T>> void set(ModConfigSpec.EnumValue<T> value, T next) {
        if (!SPEC.isLoaded()) return;
        value.set(next);
        value.save();
    }
}
