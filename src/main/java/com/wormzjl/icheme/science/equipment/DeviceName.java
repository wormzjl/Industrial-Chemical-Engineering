package com.wormzjl.icheme.science.equipment;

/**
 * The rule for a placed device's name (batch 2026-11-02-process-simulator, N1; owner 2026-11-03: "Make sure that
 * equipment names can be customized", "Yes, every device"): what the player types, trimmed, at most {@link #MAX}
 * characters, no formatting codes and no control characters. Empty is no name: the device's titles show its kind, as
 * before names existed. Two devices may share a name; the sheet keeps its own rule.
 */
public final class DeviceName {
    /** The longest name, in characters (code points). */
    public static final int MAX = 32;
    /** No name. */
    public static final String NONE = "";

    private DeviceName() {
    }

    /**
     * {@code typed} as a name: formatting codes ({@code §} and the character after it) and control characters removed,
     * trimmed, cut to {@link #MAX} characters and trimmed again. Null reads as no name.
     */
    public static String clean(String typed) {
        if (typed == null) return NONE;
        var out = new StringBuilder();
        for (int i = 0; i < typed.length(); ) {
            int c = typed.codePointAt(i);
            i += Character.charCount(c);
            if (c == '§') {
                if (i < typed.length()) i += Character.charCount(typed.codePointAt(i));
                continue;
            }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) continue;
            out.appendCodePoint(c);
        }
        String trimmed = out.toString().strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX) trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX)).strip();
        return trimmed;
    }

    /** Whether {@code name} is a name as {@link #clean} leaves one. */
    public static boolean valid(String name) {
        return name != null && name.equals(clean(name));
    }

    /** {@code title} with {@code name} first, through {@code named} (a line "%s · %s"); the title alone for no name. */
    public static String titled(String name, String title, java.util.function.BiFunction<String, String, String> named) {
        return name == null || name.isEmpty() ? title : named.apply(name, title);
    }
}
