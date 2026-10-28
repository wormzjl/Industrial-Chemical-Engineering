package com.wormzjl.icheme.client.gui.common.kit;

/**
 * The instrument palette every process screen draws in (ARGB), and the assembly report's three colours: teal in place,
 * yellow misplaced, red missing, each a stroke and a dark fill.
 */
public final class Palette {
    public static final int BG = 0xFF202A31, PANEL = 0xFF28363F, LINE = 0xFF536C7A, TEXT = 0xFFEAF1F5,
            MUTED = 0xFFA9BCC7, ACCENT = 0xFF79D6CE, WARN = 0xFFFFCD82;
    public static final int TEAL = 0xFF1D9E75, YELLOW = 0xFFEF9F27, RED = 0xFFE24B4A;
    public static final int TEAL_FILL = 0xFF12392F, YELLOW_FILL = 0xFF3D2E0F, RED_FILL = 0xFF3F1A1A;

    private Palette() {
    }
}
