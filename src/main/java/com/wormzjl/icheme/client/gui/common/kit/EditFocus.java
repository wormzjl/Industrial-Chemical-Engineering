package com.wormzjl.icheme.client.gui.common.kit;

import org.jetbrains.annotations.Nullable;

/**
 * When an entry's edit ends (the owner, 2026-11-04: "I have to click another case to make the value to stay, clicking
 * anywhere else doesn't work"): a press anywhere outside the focused entry ends it, as Enter does. Minecraft-free, so the
 * rule is gated; {@link SkinField#clickOutside} applies it on the screens.
 */
public final class EditFocus {
    private EditFocus() {
    }

    /** Whether a press at ({@code x}, {@code y}) ends the edit of the focused entry standing at {@code entry}; with none focused, nothing ends. */
    public static boolean ends(@Nullable Rect entry, double x, double y) {
        return entry != null && !entry.contains(x, y);
    }
}
