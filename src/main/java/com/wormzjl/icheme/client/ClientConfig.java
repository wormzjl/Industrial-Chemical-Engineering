package com.wormzjl.icheme.client;

import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The client's own settings, {@code icheme-client.toml} (batch 2026-10-28-gui-toolkit): the process screens' panel
 * theme. Registered by {@code IChemE} as a client config, so a dedicated server never loads it. No Minecraft classes.
 */
public final class ClientConfig {
    /** The panel door the process screens draw (GUI_TOOLKIT_PLAN.md section 10): 3a light, 3c dark. */
    public enum Theme {
        LIGHT, DARK
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<Theme> THEME;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("gui");
        THEME = builder.comment("The process screens' control panel: LIGHT (a RAL 7035 grey door with red LED meters) or DARK (a RAL 7016 anthracite door with amber LED meters). The theme button on a screen's title row switches it too.")
                .defineEnum("theme", Theme.LIGHT);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /** The theme set, LIGHT before the config has loaded. */
    public static Theme theme() {
        return SPEC.isLoaded() ? THEME.get() : Theme.LIGHT;
    }

    public static Skin skin() {
        return theme() == Theme.DARK ? PanelSkin.DARK : PanelSkin.LIGHT;
    }

    /** Switches the theme and saves it to the file. */
    public static void toggleTheme() {
        if (!SPEC.isLoaded()) return;
        THEME.set(theme() == Theme.DARK ? Theme.LIGHT : Theme.DARK);
        THEME.save();
    }
}
