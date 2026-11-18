package com.wormzjl.icheme.client.model;

/**
 * How far the reflection pass pulls its vertices towards the eye so its depth test (LEQUAL, no depth write) passes on
 * the chunk mesh it lies on and fails behind anything in front (batch 2026-11-18-equipment-reflections,
 * REFLECTION_PASS_REVIEW.md section 4). The vertex shader moves each vertex along its view ray by
 * {@code pull(z, tan)} metres; the renderer passes the constants below as uniforms. No Minecraft class.
 *
 * <p>The same surface reaches the depth buffer by two routes: the chunk mesh (vanilla: float positions from the section
 * origin plus a float {@code ChunkOffset}; Sodium 0.6: positions truncated to 32/2^20 block, about 0.03 mm, plus a float
 * region offset; cut at every cell) and the pass (float positions from its region corner plus a float offset, whole
 * facets). The two depths at a pixel differ by
 * <ul>
 * <li>the vertex positions' rounding, at most {@link #POSITION} (Sodium's 3.05e-5 block, the float rounding of
 * offsets up to the range, times two), along the normal and, over a slope, times the slope {@code tan};</li>
 * <li>the rasterizer's subpixel snap of each vertex, 1/256 px for both meshes, over the depth slope: {@code z tan / (k 256)}
 * with k pixels per radian, at least {@link #K_LEAST} (480 lines at a 110 degree field of view);</li>
 * <li>the 24-bit depth buffer's rounding, one step each, a step being {@code z^2 / (near 2^24)} metres (near 0.05).</li>
 * </ul>
 * {@code ReflectionDepthTest} rasterizes a test scene through both routes in float, at camera coordinates far from the
 * origin, distances 1 to 128 m and incidence 0 to 85 degrees, and checks that this pull covers every pixel with no more
 * than it needs. The slope term is capped at {@link #TAN_CAP} (85 degrees). What lies less than the pull behind a
 * surface also gets reflection: 0.25 mm at 5 m, about 25 mm at 96 m.
 */
public final class ReflectionDepth {
    /** The near plane of Minecraft 1.21's projection, m. */
    public static final double NEAR = .05;
    /** Position rounding of the two routes together, m. */
    public static final double POSITION = 8e-5;
    /** The fewest pixels per radian a client draws (480 lines, field of view 110 degrees: 240 / tan 55 degrees = 168). */
    public static final double K_LEAST = 168;
    /** Snap, per metre of distance per unit of slope: one 1/256 px step at {@link #K_LEAST}. */
    public static final double SNAP = 1 / (K_LEAST * 256);
    /** Depth steps covered: one rounding for each route. */
    public static final double STEPS = 2;
    /** Depth buffer rounding per metre squared of distance: {@link #STEPS} steps of 2^-24 at the near plane. */
    public static final double QUANTUM = STEPS / (NEAR * (1 << 24));
    /** The slope cap, tan 85 degrees. */
    public static final double TAN_CAP = 11.43;

    private ReflectionDepth() {
    }

    /** The pull at eye distance {@code z} (m) on a surface whose normal makes {@code tan} with the view ray. */
    public static double pull(double z, double tan) {
        double t = Math.min(Math.max(tan, 0), TAN_CAP);
        return (POSITION + SNAP * z) * (1 + t) + QUANTUM * z * z;
    }
}
