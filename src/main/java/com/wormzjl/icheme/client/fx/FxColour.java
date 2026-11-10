package com.wormzjl.icheme.client.fx;

/**
 * The colours of the effects on the CPU, for the COMPATIBLE path's vertex colours. The same formulas as the SHADER
 * path's fragment shaders ({@code fx_flame.fsh}, {@code fx_plume.fsh}), without their per-pixel noise. Pure.
 */
public final class FxColour {
    /** Flame temperature at the tip and in the hottest core, K (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.1). */
    public static final double TIP_KELVIN = 1100, CORE_KELVIN = 1900;

    private FxColour() {
    }

    /**
     * A blackbody's display colour at {@code kelvin} as r, g, b in 0..1: Tanner Helland's fit to the CIE 1964 10° colour
     * matching data, good to a few per cent from 1000 to 40,000 K. At 1100 K deep orange (1, 0.30, 0), at 1900 K
     * orange-yellow (1, 0.52, 0).
     */
    public static double[] blackbody(double kelvin) {
        double t = Math.max(1000, Math.min(40_000, kelvin)) / 100;
        double r, g, b;
        if (t <= 66) {
            r = 1;
            g = .39008157876901960784 * Math.log(t) - .63184144378862745098;
            b = t <= 19 ? 0 : .54320678911019607843 * Math.log(t - 10) - 1.19625408914;
        } else {
            r = 1.29293618606274509804 * Math.pow(t - 60, -.1332047592);
            g = 1.12989086089529411765 * Math.pow(t - 60, -.0755148492);
            b = 1;
        }
        return new double[]{clamp(r), clamp(g), clamp(b)};
    }

    /**
     * A flame vertex's colour, 0xAARRGGBB with straight alpha: the blackbody colour of a temperature from the tip's 1100 K
     * to the core's 1900 K by {@code heat} and the position {@code v} along the flame (0 root, 1 tip), darkened by
     * {@code soot} over the upper half, brightened toward white in a hot core, faded over the last quarter.
     */
    public static int flame(double v, double heat, double soot, double opacity) {
        double along = clamp(v), core = clamp(heat) * (1 - along);
        double[] c = blackbody(TIP_KELVIN + (CORE_KELVIN - TIP_KELVIN) * core);
        double white = .35 * core * core;
        double dark = 1 - .85 * clamp(soot) * smooth(.45, 1, along);
        double alpha = clamp(opacity) * (1 - .7 * smooth(.75, 1, along));
        double r = (c[0] + (1 - c[0]) * white) * dark, g = (c[1] + (1 - c[1]) * white) * dark, b = (c[2] + (1 - c[2]) * white) * dark;
        double blue = blueRoot(along, heat, soot);
        return argb(alpha, r + (BLUE[0] - r) * blue, g + (BLUE[1] - g) * blue, b + (BLUE[2] - b) * blue);
    }

    /** The blue of a flame's root, where the fuel burns before soot forms (CH and C₂ light), r g b. */
    static final double[] BLUE = {.35, .55, 1};

    /**
     * How blue a flame is at {@code along} (0 root, 1 tip): 0.7 at the root of a hot clean flame, fading out by 0.18 of its
     * length, less with soot (a sooty flame is yellow to its root). As {@code fx_flame.fsh}.
     */
    public static double blueRoot(double along, double heat, double soot) {
        return .7 * clamp(heat) * (1 - clamp(soot)) * (1 - smooth(0, .18, clamp(along)));
    }

    /**
     * A smoke or plume puff's colour, 0xAARRGGBB: its albedo lit by the sky, from 15 % at night to full by day, and its
     * density as alpha. {@code daylight} is the sky brightness, 0.2 at night to 1 at noon ({@code ClientLevel.getSkyDarken}).
     */
    public static int puff(int params, double daylight) {
        double light = .15 + .85 * clamp((daylight - .2) / .8);
        double r = (params >> 16 & 0xFF) / 255.0, g = (params >> 8 & 0xFF) / 255.0, b = (params & 0xFF) / 255.0;
        return argb((params >>> 24) / 255.0, r * light, g * light, b * light);
    }

    private static int argb(double a, double r, double g, double b) {
        return channel(a) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(double v) {
        return (int) Math.round(clamp(v) * 255);
    }

    private static double smooth(double lo, double hi, double x) {
        double k = clamp((x - lo) / (hi - lo));
        return k * k * (3 - 2 * k);
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
