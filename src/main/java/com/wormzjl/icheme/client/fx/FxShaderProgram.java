package com.wormzjl.icheme.client.fx;

/**
 * Every core shader program the mod ships ({@code assets/icheme/shaders/core/}), each with its own client switch in
 * {@code icheme-client.toml}'s {@code [effects]} section (owner, 2026-11-18: "make every custom shader in the mod
 * configurable"; batch 2026-11-18-equipment-reflections). A program draws only on the SHADER path; switched off, or
 * where it cannot run, its effect takes its compatible path, never nothing. No Minecraft class, so the choice is tested
 * alone ({@code FxShaderProgramTest}).
 */
public enum FxShaderProgram {
    /** {@code fx_flame}: flame ribbons animated on the GPU. Compatible: {@code entityTranslucentEmissive} on the flame flipbook. */
    FLAME(true, "flame_shader"),
    /** {@code fx_plume}: smoke and plume puffs lit by the sun. Compatible: {@code entityTranslucentCull} on the smoke and plume sprites. */
    PLUME(true, "smoke_shader"),
    /** {@code fx_haze}: the heat shimmer over a screen copy. It has no compatible drawing: off, no shimmer (as today with {@code heat_haze} off). */
    HAZE(true, "heat_haze"),
    /** {@code rendertype_wiring}: the instrument wiring lit through its normal map. Compatible: {@code entityCutoutNoCull}, lit flat. */
    WIRING(false, "wiring_shader"),
    /** {@code equipment_reflection}: sky, ground and sun mirrored on equipment. Compatible: the baked sheen in the chunk mesh. */
    REFLECTION(false, "reflections");

    /** Whether the effect is one of the flames, smoke, plumes or haze that {@link FxMode#OFF} takes away; the rest fall back. */
    public final boolean effect;
    /** The switch's key in the config's {@code [effects]} section. */
    public final String key;

    FxShaderProgram(boolean effect, String key) {
        this.effect = effect;
        this.key = key;
    }

    /**
     * The path this program's effect draws on this frame: {@code mode} the master switch ({@link FxMode#OFF} draws no
     * flames, smoke, plumes or haze, and puts the wiring and the reflections on their compatible paths; COMPATIBLE
     * puts every program on its compatible path); under AUTO the program's own shader runs only when Iris is confirmed
     * absent or confirmed to have no pack ({@code irisKnown}, {@code packInUse}), the program compiled ({@code loaded})
     * and its switch is on ({@code on}).
     */
    public FxPath path(FxMode mode, boolean irisKnown, boolean packInUse, boolean loaded, boolean on) {
        if (mode == FxMode.OFF) return effect ? FxPath.OFF : FxPath.COMPATIBLE;
        if (mode == FxMode.COMPATIBLE || !irisKnown || packInUse || !loaded || !on) return FxPath.COMPATIBLE;
        return FxPath.SHADER;
    }

    /** The program that draws {@code kind} on the SHADER path. */
    public static FxShaderProgram of(FxKind kind) {
        return switch (kind) {
            case FLAME -> FLAME;
            case SMOKE, PLUME -> PLUME;
            case HAZE -> HAZE;
        };
    }
}
