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
    public static final ModConfigSpec.BooleanValue GUIDE_HUD;
    public static final ModConfigSpec.EnumValue<com.wormzjl.icheme.client.pollution.MapLayer> POLLUTION_MAP_LAYER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EQUIPMENT_PAINTS;
    public static final ModConfigSpec.BooleanValue LOW_POLYGON_MODE;
    public static final ModConfigSpec.DoubleValue LOD_SCALE;
    public static final ModConfigSpec.EnumValue<FxMode> EFFECTS_MODE;
    public static final ModConfigSpec.BooleanValue PBR_TEXTURES;
    public static final ModConfigSpec.BooleanValue HEAT_HAZE;
    public static final ModConfigSpec.BooleanValue DH_SILHOUETTES;
    public static final ModConfigSpec.BooleanValue FLAME_SHADER;
    public static final ModConfigSpec.BooleanValue SMOKE_SHADER;
    public static final ModConfigSpec.BooleanValue WIRING_SHADER;
    public static final ModConfigSpec.BooleanValue REFLECTIONS;
    public static final ModConfigSpec.BooleanValue SIGHT_GLASS_SHADER;
    public static final ModConfigSpec.DoubleValue REFLECTION_STRENGTH;
    public static final ModConfigSpec.IntValue REFLECTION_RANGE;
    /** The reflection pass's range, blocks: its bounds and default. */
    public static final int LEAST_REFLECTION_RANGE = 16, MOST_REFLECTION_RANGE = 256, REFLECTION_RANGE_DEFAULT = 96;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("gui");
        THEME = builder.comment("The process screens' control panel: LIGHT (a RAL 7035 grey door with red LED meters) or DARK (a RAL 7016 anthracite door with amber LED meters). The theme key on a screen's title row switches it too.")
                .defineEnum("theme", Theme.LIGHT);
        STREAM_CONDITIONS = builder.comment("Whether the process simulator's canvas shows each stream's temperature and pressure under its name. The P, T key at the simulator's foot switches it too.")
                .define("stream_conditions", false);
        GUIDE_HUD = builder.comment("Whether the Field Guide's current step stands on one line at the top left while no screen is open.")
                .define("guide_hud", true);
        POLLUTION_MAP_LAYER = builder.comment("The pollution layer the FTB Chunks map shows: BOTH (the worse of air and ground), AIR or GROUND. The pollution map layer key (unbound by default) switches it too.")
                .defineEnum("pollution_map_layer", com.wormzjl.icheme.client.pollution.MapLayer.BOTH);
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
        builder.comment("Flames, smoke, plumes, the instrument wiring's relief and the equipment's reflections, and every shader of the mod: each shader has its own switch below (batches 2026-11-10-custom-shader-system, 2026-11-18-equipment-reflections). A shader switched off, or under a shader pack, gives its effect the compatible drawing, never nothing. Drawing only: the server's process calculations never read these. Changes take effect on the next frame unless a line says otherwise.").push("effects");
        EFFECTS_MODE = builder.comment("The master switch. AUTO picks the path each frame: the mod's own shaders without a shader pack (each where its switch below is on), vanilla render types under one. COMPATIBLE never uses the mod's own shaders (use it if another mod conflicts with them). OFF draws no flames, smoke or plumes, and no reflections or wiring relief; sounds stay.")
                .defineEnum("mode", FxMode.AUTO);
        FLAME_SHADER = builder.comment("Shader fx_flame: flames animated on the graphics card. Off: the vanilla emissive render type with the flame flipbook, as under a shader pack.")
                .define("flame_shader", true);
        SMOKE_SHADER = builder.comment("Shader fx_plume: smoke and white plumes lit by the sun. Off: the vanilla translucent render type with the smoke and plume sprites, as under a shader pack.")
                .define("smoke_shader", true);
        WIRING_SHADER = builder.comment("Shader rendertype_wiring: the instrument wiring lit through its normal map (pbr_textures must be on too). Off: the same textures, lit flat by the vanilla entity shader.")
                .define("wiring_shader", true);
        REFLECTIONS = builder.comment("Shader equipment_reflection: the sky, the ground and the sun mirrored on equipment bodies, pipes and steel within reflection_range, faded out towards its edge. Off, or under a shader pack: the baked fixed sheen (metallicSheen in icheme-common.toml). Moves the chunks to the other look when changed (a re-mesh, as pbr_textures).")
                .define("reflections", true);
        REFLECTION_STRENGTH = builder.comment("How strong the reflections are: 1 is the physical law, 0 draws none (and keeps the baked sheen), 2 doubles them.")
                .defineInRange("reflection_strength", 1.0, 0.0, 2.0);
        REFLECTION_RANGE = builder.comment("How far from the camera equipment reflects, blocks; the last quarter fades out. Beyond it equipment shows its plain baked shade.")
                .defineInRange("reflection_range", REFLECTION_RANGE_DEFAULT, LEAST_REFLECTION_RANGE, MOST_REFLECTION_RANGE);
        SIGHT_GLASS_SHADER = builder.comment("Shader sight_glass: the bubbles in a sight glass, round, clipped to the glass, with a soft rim and a highlight. Off, or under a shader pack: the same bubbles drawn as rings.")
                .define("sight_glass_shader", true);
        PBR_TEXTURES = builder.comment("The instrument wiring lit through its normal map, by the mod's shader without a shader pack or by the pack; under a pack, the equipment's relief sprites (nuts, louvres, straps) lit by the pack from their normal maps. Off: the same textures, lit flat, and the equipment keeps its baked relief. Reloads the chunks when changed, as low_polygon_mode does.")
                .define("pbr_textures", true);
        HEAT_HAZE = builder.comment("Shader fx_haze: a heat shimmer over flames and hot stacks, one screen copy and a distortion. Only without a shader pack; it has no compatible drawing, so off (or under a pack) there is no shimmer.")
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

    /**
     * The units a column's Operation door shows (temperature, pressure, the flow's time), read now: the one place the
     * assembly screen and the DCS console take them from (finding C16, batch 2026-11-13-codebase-review).
     */
    public static com.wormzjl.icheme.client.gui.common.OperationColumn.Units operationUnits() {
        return new com.wormzjl.icheme.client.gui.common.OperationColumn.Units(temperature(), pressure(), flowTime());
    }

    /** The pollution map's layer (batch 2026-11-18-ground-pollution, G3), BOTH before the config has loaded. */
    public static com.wormzjl.icheme.client.pollution.MapLayer pollutionMapLayer() {
        return get(POLLUTION_MAP_LAYER, com.wormzjl.icheme.client.pollution.MapLayer.BOTH);
    }

    /** Sets the pollution map's layer and saves it to the file. */
    public static void pollutionMapLayer(com.wormzjl.icheme.client.pollution.MapLayer layer) {
        set(POLLUTION_MAP_LAYER, layer);
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

    /** Whether the Field Guide's one-line tracker shows; on before the config has loaded. */
    public static boolean guideHud() {
        return get(GUIDE_HUD, true);
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

    /** Whether the instrument wiring, and under a shader pack the equipment's relief sprites, are lit through their normal maps; on before the config has loaded. */
    public static boolean pbrTextures() {
        return get(PBR_TEXTURES, true);
    }

    /** Whether the heat shimmer is drawn (SHADER path only); off before the config has loaded. */
    public static boolean heatHaze() {
        return get(HEAT_HAZE, false);
    }

    /**
     * Whether the switch of {@code program} is on (on before the config has loaded): the one place every shader's switch
     * is read. {@code fx_haze}'s switch is {@code heat_haze}, off by default.
     */
    public static boolean shader(com.wormzjl.icheme.client.fx.FxShaderProgram program) {
        return switch (program) {
            case FLAME -> get(FLAME_SHADER, true);
            case PLUME -> get(SMOKE_SHADER, true);
            case HAZE -> heatHaze();
            case WIRING -> get(WIRING_SHADER, true);
            case REFLECTION -> get(REFLECTIONS, true);
            case SIGHT_GLASS -> get(SIGHT_GLASS_SHADER, true);
        };
    }

    /** The reflections' strength, 0 to 2; 1 before the config has loaded. */
    public static double reflectionStrength() {
        return get(REFLECTION_STRENGTH, 1.0);
    }

    /** The reflections' range, blocks; {@link #REFLECTION_RANGE_DEFAULT} before the config has loaded. */
    public static int reflectionRange() {
        return get(REFLECTION_RANGE, REFLECTION_RANGE_DEFAULT);
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
