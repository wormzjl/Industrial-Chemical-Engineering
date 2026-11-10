package com.wormzjl.icheme.client;

import com.wormzjl.icheme.client.fx.FxMode;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import com.wormzjl.icheme.science.equipment.Paint;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.UUID;

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

    /**
     * The basis the composition tables and flows show. VOLUME is the process simulator's: a stream's flow in m³ at its own
     * temperature and pressure; the fluid device screen, which keys only MOLE and MASS, reads it as MOLE.
     */
    public enum Basis {
        MOLE, MASS, VOLUME
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<Theme> THEME;
    public static final ModConfigSpec.EnumValue<TemperatureUnit> TEMPERATURE;
    public static final ModConfigSpec.EnumValue<PressureUnit> PRESSURE;
    public static final ModConfigSpec.EnumValue<FlowRateUnit> FLOW_TIME;
    public static final ModConfigSpec.EnumValue<Basis> BASIS;
    public static final ModConfigSpec.BooleanValue STREAM_CONDITIONS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EQUIPMENT_PAINTS;
    public static final ModConfigSpec.BooleanValue LOW_POLYGON_MODE;
    public static final ModConfigSpec.DoubleValue LOD_SCALE;
    public static final ModConfigSpec.EnumValue<FxMode> EFFECTS_MODE;
    public static final ModConfigSpec.BooleanValue PBR_TEXTURES;
    public static final ModConfigSpec.BooleanValue HEAT_HAZE;
    public static final ModConfigSpec.BooleanValue DH_SILHOUETTES;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("gui");
        THEME = builder.comment("The process screens' control panel: LIGHT (a RAL 7035 grey door with red LED meters) or DARK (a RAL 7016 anthracite door with amber LED meters). The theme key on a screen's title row switches it too.")
                .defineEnum("theme", Theme.LIGHT);
        STREAM_CONDITIONS = builder.comment("Whether the process simulator's canvas shows each stream's temperature and pressure under its name. The P, T key at the simulator's foot switches it too.")
                .define("stream_conditions", false);
        builder.pop();
        builder.comment("The units every process screen shows; the unit keys at a screen's foot switch them too. Display only: the server keeps SI.").push("units");
        TEMPERATURE = builder.comment("CELSIUS or KELVIN.").defineEnum("temperature", TemperatureUnit.CELSIUS);
        PRESSURE = builder.comment("PASCAL, KILOPASCAL, BAR or MEGAPASCAL.").defineEnum("pressure", PressureUnit.KILOPASCAL);
        FLOW_TIME = builder.comment("Flows per second (PER_SECOND), per minute (PER_MINUTE) or per hour (PER_HOUR).").defineEnum("flow_time", FlowRateUnit.PER_SECOND);
        BASIS = builder.comment("Compositions and flows in moles (MOLE) or in mass (MASS); VOLUME shows the process simulator's stream flows in m³ at the stream's own temperature and pressure, and the fluid device screens read it as MOLE.").defineEnum("basis", Basis.MOLE);
        builder.pop();
        builder.push("equipment");
        LOW_POLYGON_MODE = builder.comment("Drawing only: textured flange bolts and fewer round-surface facets. Placement, collision and process calculations keep their normal geometry. Reload resources (F3+T) after changing.").define("low_polygon_mode", false);
        LOD_SCALE = builder.comment("Drawing only: how soon gauges, sensors, cables and sight glasses lose detail with distance. 1 follows what a pixel shows; 2 drops detail at half the distance, 0.5 at twice it. Each still stops at its own largest range.")
                .defineInRange("lod_scale", 1.0, RenderLod.LEAST_SCALE, RenderLod.MOST_SCALE);
        EQUIPMENT_PAINTS = builder.comment("Constructor body and structure colours by player account: UUID:#RRGGBB:#RRGGBB. Remembered when the Paint page is edited.")
                .defineListAllowEmpty("paint_schemes", List.<String>of(),
                        () -> new UUID(0, 0) + ":#A1A1A0:#5B686D", EquipmentPaints::valid);
        builder.pop();
        builder.comment("Flames, smoke, plumes and the instrument wiring's relief (batch 2026-11-10-custom-shader-system). Drawing only: the server's process calculations never read these. Changes take effect on the next frame.").push("effects");
        EFFECTS_MODE = builder.comment("AUTO picks the path each frame: the mod's own shaders without a shader pack, vanilla render types under one. COMPATIBLE never uses the mod's own shaders (use it if another mod conflicts with them). OFF draws no flames, smoke or plumes; sounds stay.")
                .defineEnum("mode", FxMode.AUTO);
        PBR_TEXTURES = builder.comment("The instrument wiring lit through its normal map, by the mod's shader without a shader pack or by the pack. Off: the same texture, lit flat. Reloads the chunks when changed, as low_polygon_mode does.")
                .define("pbr_textures", true);
        HEAT_HAZE = builder.comment("A heat shimmer over flames and hot stacks: one screen copy and a distortion. Only without a shader pack.")
                .define("heat_haze", false);
        DH_SILHOUETTES = builder.comment("With Distant Horizons: painted equipment silhouettes in its far terrain. No effect without Distant Horizons.")
                .define("dh_silhouettes", true);
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

    /** Whether the process simulator's canvas shows each stream's temperature and pressure; off before the config has loaded. */
    public static boolean streamConditions() {
        return get(STREAM_CONDITIONS, false);
    }

    public static void streamConditions(boolean shown) {
        set(STREAM_CONDITIONS, shown);
    }

    public static com.wormzjl.icheme.science.equipment.RenderDetail renderDetail() {
        return get(LOW_POLYGON_MODE, false) ? com.wormzjl.icheme.science.equipment.RenderDetail.LOW : com.wormzjl.icheme.science.equipment.RenderDetail.NORMAL;
    }

    /** The level-of-detail scale, 1 before the config has loaded. */
    public static double lodScale() {
        return get(LOD_SCALE, 1.0);
    }

    /** This frame's level-of-detail view of a projection whose m11 is {@code m11} on a framebuffer {@code height} high. */
    public static RenderLod.View lodView(double m11, int height) {
        return RenderLod.View.of(m11, height, lodScale());
    }

    /** The effects mode, AUTO before the config has loaded. */
    public static FxMode effectsMode() {
        return get(EFFECTS_MODE, FxMode.AUTO);
    }

    /** Whether the instrument wiring is lit through its normal map; on before the config has loaded. */
    public static boolean pbrTextures() {
        return get(PBR_TEXTURES, true);
    }

    /** Whether the heat shimmer is drawn (SHADER path only); off before the config has loaded. */
    public static boolean heatHaze() {
        return get(HEAT_HAZE, false);
    }

    /** Whether Distant Horizons shows painted equipment silhouettes; on before the config has loaded. */
    public static boolean dhSilhouettes() {
        return get(DH_SILHOUETTES, true);
    }

    public static Paint equipmentPaint(UUID player, Paint fallback) {
        return EquipmentPaints.find(get(EQUIPMENT_PAINTS, List.of()), player, fallback);
    }

    public static void rememberEquipmentPaint(UUID player, Paint paint) {
        set(EQUIPMENT_PAINTS, EquipmentPaints.remember(get(EQUIPMENT_PAINTS, List.of()), player, paint));
    }

    /** The value set, {@code fallback} before the config has loaded. */
    private static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    /** Sets the value and saves it to the file; nothing before the config has loaded. */
    private static <T> void set(ModConfigSpec.ConfigValue<T> value, T next) {
        if (!SPEC.isLoaded()) return;
        value.set(next);
        value.save();
    }
}
