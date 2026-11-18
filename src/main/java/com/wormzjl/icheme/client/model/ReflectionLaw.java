package com.wormzjl.icheme.client.model;

/**
 * The equipment gloss law (batch 2026-11-18-equipment-reflections, REFLECTION_PASS_REVIEW.md sections 2 and 13), in Java:
 * {@code equipment_reflection.fsh} computes the same expressions with these constants, which the renderer passes as
 * uniforms, and {@code tools/reflection-render} draws the sheets with these methods. No Minecraft class.
 *
 * <p>A stylised gloss, not a mirror image (owner, 2026-11-18: "it doesn't need to reflect color", "doesn't need to be
 * real reflection"): a surface shows a grey sheen whose share rises from {@code F0} head-on to all at grazing angles
 * (Schlick's {@code F = F0 + (1 - F0)(1 - N.V)^5}), the sheen's brightness the horizon's ({@link #surround}: the fog
 * colour, which follows the time of day and the weather), and the sun's highlight round its mirror direction. The pass
 * keeps {@link #kept} = {@code 1 - F x sky light x strength} of what the chunk mesh drew and adds
 * {@code (surround F + highlight) x sky light x strength}, the same in every channel (blend ONE, ONE_MINUS_SRC_ALPHA).
 * So the sheen looks the same from above and below, and never colours the shell.
 */
public final class ReflectionLaw {
    /** The preview's horizon colour; in the game the fog colour takes its place. */
    public static final double[] HORIZON = {.93, .95, .97};
    /** The moon's highlight against the sun's. */
    public static final double MOON = .08;
    /** The sky light (0 to 1) over which the highlight comes in: none in shade under a roof. */
    public static final double HIGHLIGHT_SKY_FROM = 10 / 15.0, HIGHLIGHT_SKY_TO = 14 / 15.0;
    /** The share of the range over which the pass fades to nothing at its far edge. */
    public static final double FADE = .25;
    /** Rec. 709 luma weights: the sheen's brightness from the horizon's colour. */
    public static final double LUMA_R = .2126, LUMA_G = .7152, LUMA_B = .0722;

    private ReflectionLaw() {
    }

    /** Schlick's Fresnel reflectance at {@code cos} = N.V. */
    public static double fresnel(double f0, double cos) {
        double m = 1 - Math.max(0, Math.min(1, cos));
        double m2 = m * m;
        return f0 + (1 - f0) * m2 * m2 * m;
    }

    /** GLSL's smoothstep. */
    public static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /** The sheen's grey: the brightness (Rec. 709 luma) of the horizon colour {@code horizon}. */
    public static double surround(double[] horizon) {
        return LUMA_R * horizon[0] + LUMA_G * horizon[1] + LUMA_B * horizon[2];
    }

    /** The highlight: {@code max(R.L, 0)^n s}. */
    public static double highlight(double[] r, double[] light, double exponent, double strength) {
        double d = r[0] * light[0] + r[1] * light[1] + r[2] * light[2];
        return d <= 0 ? 0 : Math.pow(d, exponent) * strength;
    }

    /** The mirror direction of the view direction {@code v} (surface to eye, unit) about the unit normal {@code n}: {@code reflect(-v, n)}. */
    public static double[] reflect(double[] v, double[] n) {
        double d = v[0] * n[0] + v[1] * n[1] + v[2] * n[2];
        return new double[]{2 * d * n[0] - v[0], 2 * d * n[1] - v[1], 2 * d * n[2] - v[2]};
    }

    /**
     * The light the highlight comes from: the sun at {@code sunAngle} (radians, {@code Level.getSunAngle}; vanilla's sun
     * at {@code (-sin a, cos a, 0)}, up at noon, east at dawn), or the moon opposite it while the sun is down; and its
     * strength, the sun's 1 and the moon's {@link #MOON}, eased in over the first 0.15 of height above the horizon and
     * taken away by rain ({@code rain} 0 to 1). Returns x, y, z, strength.
     */
    public static double[] celestial(double sunAngle, double rain) {
        double x = -Math.sin(sunAngle), y = Math.cos(sunAngle);
        double scale = y >= 0 ? 1 : MOON;
        if (y < 0) {
            x = -x;
            y = -y;
        }
        return new double[]{x, y, 0, scale * smoothstep(-.05, .15, y) * (1 - Math.max(0, Math.min(1, rain)))};
    }

    /** The pass's strength by the fragment's distance {@code d} and the range: 1 inside, falling to 0 over the last {@link #FADE} of it. */
    public static double fade(double d, double range) {
        return 1 - smoothstep(range * (1 - FADE), range, d);
    }

    /**
     * What the pass adds at one fragment, linear 0 to 1, the same in every channel: the unit normal {@code n}, the unit
     * direction to the eye {@code v}, the surface's {@code type}, the {@code horizon} colour, the light {@link #celestial},
     * the fragment's sky light 0 to 1, the configured {@code strength} and the {@link #fade}.
     */
    public static double added(double[] n, double[] v, ReflectionClass type, double[] horizon, double[] light, double skyLight, double strength, double fade) {
        double cos = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
        double[] r = reflect(v, n);
        double h = highlight(r, light, type.exponent, type.strength) * light[3] * smoothstep(HIGHLIGHT_SKY_FROM, HIGHLIGHT_SKY_TO, skyLight);
        return (surround(horizon) * fresnel(type.f0, cos) + h) * skyLight * strength * fade;
    }

    /**
     * The share of the chunk mesh's colour the pass keeps at one fragment: {@code 1 - F x skyLight x strength x fade},
     * at least 0 (the shader's alpha is the sheen's share, clamped by the framebuffer). The arguments as {@link #added}.
     */
    public static double kept(double[] n, double[] v, ReflectionClass type, double skyLight, double strength, double fade) {
        double cos = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
        return 1 - Math.min(1, fresnel(type.f0, cos) * skyLight * strength * fade);
    }
}
