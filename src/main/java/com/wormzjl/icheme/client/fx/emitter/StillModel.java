package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxSink;
import org.jetbrains.annotations.Nullable;

/**
 * A fuelled batch still's fire at its firing door (batch 2026-11-10-custom-shader-system, plan sections 2 and 4.5, W6), from
 * the still's published fire share and the design's marks ({@code door}, {@code fire_bed}, {@code burner}). Pure; names
 * no Minecraft class. Positions are from the door's centre.
 * <ul>
 * <li><b>Solid fire.</b> The bed glows orange low in the door, a wide ember band of {@link #EMBER} of its height; three
 * flames lick up from it, to (0.35 + 0.5 fire) of the door's height.</li>
 * <li><b>Liquid burner.</b> A pressure-jet flame across the door from the burner's side: (0.4 + 0.5 fire) of its width
 * long, hot and clean, so its root shows blue ({@code FxColour.blueRoot}) and its tip yellow.</li>
 * </ul>
 * Both stand {@link #PROUD} m out of the door's face. The glided value is the fire share alone; the door fixes the rest.
 */
public final class StillModel {
    /** The ember band's height as a share of the door's, and how far the fire stands out of the door's face, m. */
    public static final double EMBER = .25, PROUD = .01;
    /** Flames over a solid bed, and segments per flame and per jet. */
    public static final int FLAMES = 3, SEGMENTS = 3, JET_SEGMENTS = 4;

    private StillModel() {
    }

    /**
     * The door: its outward unit normal (x, z), its width and height, m, and the horizontal unit way the jet runs across
     * it (x, z: from the burner's side toward the far side; any for a solid fire).
     */
    public record Door(double outX, double outZ, double width, double height, double jetX, double jetZ) {
    }

    /**
     * The door from its marks' boxes, in one frame (world or the box's): {@code door} and {@code bed} the door's and the
     * fire bed's centres, {@code gun} the burner's or null, {@code box} the door's box (low corner, then high). Outward is
     * the horizontal way from the bed to the door; the jet runs across the door away from the burner's side.
     */
    public static Door door(double[] door, double[] bed, double @Nullable [] gun, double[] box) {
        double ox = door[0] - bed[0], oz = door[2] - bed[2], o = Math.hypot(ox, oz);
        if (o < 1e-6) {
            ox = 0;
            oz = -1;
        } else {
            ox /= o;
            oz /= o;
        }
        double ax = -oz, az = ox, sign = 1;
        if (gun != null && (bed[0] - gun[0]) * ax + (bed[2] - gun[2]) * az < 0) sign = -1;
        double width = Math.abs(ax) * (box[3] - box[0]) + Math.abs(az) * (box[5] - box[2]);
        return new Door(ox, oz, width, box[4] - box[1], ax * sign, az * sign);
    }

    /** The quads of the fire for {@code solid} (else a liquid burner): none without fire. */
    public static int quads(boolean solid, double fire) {
        return fire > 0 ? solid ? 1 + FLAMES * SEGMENTS : JET_SEGMENTS : 0;
    }

    /** Writes the fire eased from share {@code from} to {@code to}. */
    public static void write(FxSink sink, Door door, boolean solid, double from, double to) {
        if (!(to > 0 || from > 0)) return;
        double ox = door.outX() * PROUD, oz = door.outZ() * PROUD, w = door.width(), h = door.height();
        // Across the door, in its plane.
        double ax = -door.outZ(), az = door.outX();
        if (solid) {
            double bottom = -h / 2;
            sink.ribbon(new FxSink.Point(ox, bottom, oz, ox, bottom, oz, w * .45 * glow(from), w * .45 * glow(to)), 0,
                    new FxSink.Point(ox, bottom + EMBER * h, oz, ox, bottom + EMBER * h, oz, w * .4 * glow(from), w * .4 * glow(to)), .9,
                    FxSink.params(0, .35, .2, .9 * glow(to)), 1);
            for (int k = 0; k < FLAMES; k++) {
                double across = (k - (FLAMES - 1) / 2.0) * w * .27;
                double fx = ox + ax * across, fz = oz + az * across;
                double ha = h * (.35 + .5 * from), hb = h * (.35 + .5 * to);
                int params = FxSink.params(0, .6 + .3 * to, .3, .9);
                FxSink.Point last = null;
                for (int i = 0; i <= SEGMENTS; i++) {
                    double v = (double) i / SEGMENTS, width = w * .12 * (1 - .7 * v);
                    var p = new FxSink.Point(fx, bottom + v * ha * (from > 0 ? 1 : 0), fz, fx, bottom + v * hb * (to > 0 ? 1 : 0), fz,
                            from > 0 ? width : 0, to > 0 ? width : 0);
                    if (last != null) sink.ribbon(last, (double) (i - 1) / SEGMENTS, p, v, params, 300 + k * 97 + i);
                    last = p;
                }
            }
        } else {
            double la = w * (.4 + .5 * from) * (from > 0 ? 1 : 0), lb = w * (.4 + .5 * to) * (to > 0 ? 1 : 0);
            double sx = ox - door.jetX() * w / 2, sz = oz - door.jetZ() * w / 2;
            int params = FxSink.params(0, 1, .05, .9);
            FxSink.Point last = null;
            for (int i = 0; i <= JET_SEGMENTS; i++) {
                double v = (double) i / JET_SEGMENTS, half = h * .1 * (.5 + 1.2 * v * (1 - v) * 2);
                var p = new FxSink.Point(sx + door.jetX() * la * v, 0, sz + door.jetZ() * la * v, sx + door.jetX() * lb * v, 0, sz + door.jetZ() * lb * v,
                        from > 0 ? half : 0, to > 0 ? half : 0);
                if (last != null) sink.ribbon(last, (double) (i - 1) / JET_SEGMENTS, p, v, params, 700 + i);
                last = p;
            }
        }
    }

    /** The glow's strength at fire share {@code fire}: 0.4 at a low fire, 1 at full. */
    static double glow(double fire) {
        return fire > 0 ? .4 + .6 * Math.min(1, fire) : 0;
    }
}
