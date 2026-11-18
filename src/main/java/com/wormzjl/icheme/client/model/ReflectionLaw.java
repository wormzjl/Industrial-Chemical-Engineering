package com.wormzjl.icheme.client.model;

/**
 * The equipment reflection law (batch 2026-11-18-equipment-reflections, REFLECTION_PASS_REVIEW.md section 2), in Java:
 * {@code equipment_reflection.fsh} computes the same expressions with these constants, which the renderer passes as
 * uniforms, and {@code tools/reflection-render} draws the sheets with these methods. No Minecraft class.
 *
 * <p>A glossy surface shows its surroundings in the mirror direction {@code R = reflect(-V, N)}, weighted by Schlick's
 * Fresnel {@code F = F0 + (1 - F0)(1 - N.V)^5}. Outdoors the surroundings are the sky above the horizon and the ground
 * below it, and the sun adds a highlight round its mirror direction. The pass ADDS
 * {@code (sky(R) F tint + highlight) x sky light x strength} to what the chunk mesh drew; it does not darken the base by
 * {@code 1 - F} as a full shade would (at most {@code F0} of the base, 5 % for paint seen head-on).
 */
public final class ReflectionLaw {
    /** The preview's horizon colour; in the game the fog colour takes its place. */
    public static final double[] HORIZON = {.93, .95, .97};
    /** The preview's zenith colour; in the game the level's sky colour takes its place. */
    public static final double[] ZENITH = {.45, .62, .88};
    /** The ground just below the horizon and far below it, at full daylight. */
    public static final double[] NEAR_GROUND = {.42, .40, .36}, GROUND = {.25, .23, .20};
    /** The powers of the sky's and the ground's gradients in the reflected direction's height. */
    public static final double SKY_POWER = .45, GROUND_POWER = .5;
    /** The moon's highlight against the sun's. */
    public static final double MOON = .08;
    /** The sky light (0 to 1) over which the highlight comes in: none in shade under a roof. */
    public static final double HIGHLIGHT_SKY_FROM = 10 / 15.0, HIGHLIGHT_SKY_TO = 14 / 15.0;
    /** The share of the range over which the pass fades to nothing at its far edge. */
    public static final double FADE = .25;

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

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * The surroundings in direction height {@code ry} (the reflected direction's y, -1 to 1), with the horizon blurred
     * over {@code blur}: above, the horizon colour to the zenith by {@code ry^0.45}; below, the near ground to the ground
     * by {@code (-ry)^0.5}, times {@code daylight}; joined by {@code smoothstep(-blur, blur, ry)}.
     */
    public static double[] sky(double ry, double blur, double[] zenith, double[] horizon, double daylight) {
        double up = Math.pow(Math.max(ry, 0), SKY_POWER), down = Math.pow(Math.max(-ry, 0), GROUND_POWER), join = smoothstep(-blur, blur, ry);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) out[c] = mix(mix(NEAR_GROUND[c], GROUND[c], down) * daylight, mix(horizon[c], zenith[c], up), join);
        return out;
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
     * What the pass adds at one fragment, linear 0 to 1 per channel before the framebuffer clamps: the unit normal
     * {@code n}, the unit direction to the eye {@code v}, the surface's {@code type} and reflection {@code tint} (0xRRGGBB),
     * the sky ({@code zenith}, {@code horizon}, {@code daylight}), the light {@link #celestial}, the fragment's sky light
     * 0 to 1, the configured {@code strength} and the {@link #fade}.
     */
    public static double[] added(double[] n, double[] v, ReflectionClass type, int tint, double[] zenith, double[] horizon, double daylight,
                                 double[] light, double skyLight, double strength, double fade) {
        double cos = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
        double[] r = reflect(v, n);
        double[] s = sky(r[1], type.blur, zenith, horizon, daylight);
        double f = fresnel(type.f0, cos);
        double h = highlight(r, light, type.exponent, type.strength) * light[3] * smoothstep(HIGHLIGHT_SKY_FROM, HIGHLIGHT_SKY_TO, skyLight);
        double scale = skyLight * strength * fade;
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) out[c] = (s[c] * f * (tint >> 16 - 8 * c & 255) / 255.0 + h) * scale;
        return out;
    }
}
