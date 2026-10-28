package com.wormzjl.icheme.client.gui.common.kit;

import java.util.Locale;

/** Numbers as the process screens write them. */
public final class Format {
    private Format() {
    }

    /** One decimal; three significant figures below 0.05 (and not zero). */
    public static String number(double value) {
        return String.format(Locale.ROOT, value != 0 && Math.abs(value) < 0.05 ? "%.3g" : "%.1f", value);
    }
}
