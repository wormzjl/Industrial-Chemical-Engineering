package com.wormzjl.icheme.client.fx;

/**
 * The client's effects switch, {@code [effects] mode} in {@code icheme-client.toml} (CUSTOM_SHADER_SYSTEM_PLAN.md
 * section 4.3). Saved by name. Names no Minecraft class, so the client config can name it.
 */
public enum FxMode {
    /** The path is chosen each frame: the mod's own shaders without a shader pack, vanilla render types under one. */
    AUTO,
    /** Never the mod's own shaders: the escape hatch when another mod conflicts with them. */
    COMPATIBLE,
    /** No flames, smoke or plumes; sounds stay. */
    OFF
}
