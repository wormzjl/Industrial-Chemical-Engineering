package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.HeldDraws;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A constructed centrifugal pump's curve on its page's glass (batch 2026-11-03-rotating-machinery W10d, owner decisions D37
 * and D38) over small and large pumps, every speed from rest past rated, every flow unit and NPSH available none, starving,
 * cavitating between NPSH3 and 4 x NPSH3, and quiet: its axes fit the head, NPSH3, the inception curve and the NPSH
 * available; everything it draws lies inside its box; no number is NaN at zero flow or zero speed; its loops of fills draw in
 * a few draw calls; every text it names has its English line.
 */
class PumpCurveTest {
    private static final Measure M = Measure.fixed(6);

    /** Records what the curve draws: fills and texts with their boxes, and the draw calls as the game makes them. */
    private static final class Recording implements Canvas {
        final HeldDraws held = new HeldDraws();
        final List<int[]> fills = new ArrayList<>(), texts = new ArrayList<>();

        @Override public Measure measure() {
            return M;
        }

        @Override public void batch(Runnable body) {
            held.begin(() -> {
            });
            try {
                body.run();
            } finally {
                held.end();
            }
        }

        @Override public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills.add(new int[]{Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)});
            held.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        }

        @Override public void text(String text, int x, int y, int argb) {
            text(text, x, y, 1, argb);
        }

        @Override public void text(String text, float x, float y, float scale, int argb) {
            assertFalse(text.contains("NaN") || text.contains("Infinity"), "a number that is none: " + text);
            texts.add(new int[]{(int) x, (int) y, (int) x + M.width(text), (int) y + M.height()});
            held.text(x, y, x + M.width(text) * scale, y + M.height() * scale, () -> {
            });
        }

        @Override public void clip(Rect area) {
            held.clip();
        }

        @Override public void unclip() {
            held.clip();
        }
    }

    /** The draws a curve takes at most: 40 measured over every case (2026-11-04), 10 % above. */
    private static final int BUDGET = 44;

    private static List<PumpCurve.Input> cases() {
        var out = new ArrayList<PumpCurve.Input>();
        for (double[] rating : new double[][]{{10 / 3600.0, 20}, {100 / 3600.0, 80}, {1500 / 3600.0, 200}})
            for (double rpm : new double[]{1450, 2950}) {
                var pump = new CentrifugalPump(rating[0], rating[1], CentrifugalPump.bestEfficiency(rating[0]), rpm);
                double npsh3 = pump.npsh(1).required(pump.ratedFlow());
                for (double speed : new double[]{0, .5, 1, 1.2})
                    for (double flow : new double[]{Double.NaN, 0, pump.ratedFlow(), 1.3 * pump.ratedFlow()})
                        for (double available : new double[]{Double.NaN, 0, .7 * npsh3, 2 * npsh3, 30 * npsh3})
                            for (double perTime : new double[]{1, 60, 3600}) {
                                double head = Double.isFinite(flow) ? Math.max(0, pump.head(flow, speed)) : Double.NaN;
                                out.add(new PumpCurve.Input(pump, speed, flow, head, available, perTime, perTime == 1 ? "m³/s" : perTime == 60 ? "m³/min" : "m³/h"));
                            }
            }
        return out;
    }

    private static final List<Rect> BOXES = List.of(new Rect(150, 78, 280, 180), new Rect(200, 78, 420, 330), new Rect(260, 78, 760, 420));

    @Test
    void theAxesFitEveryCurveAndTheNpshAvailable() {
        var texts = new HeatControlColumnTest.Recording();
        for (var in : cases())
            for (var box : BOXES) {
                var lay = PumpCurve.layout(box, in, M, texts);
                assertTrue(Double.isFinite(lay.flowTop()) && Double.isFinite(lay.headTop()) && Double.isFinite(lay.npshTop()) && lay.headTop() > 0 && lay.npshTop() > 0, in.toString());
                assertTrue(lay.flowTop() >= PumpCurve.SPAN * in.pump().ratedFlow() * in.perTime() * (1 - 1e-12), "the flow axis spans the curve: " + in);
                var npsh = in.pump().npsh(in.speed());
                for (int i = 0; i <= 100; i++) {
                    double q = PumpCurve.SPAN * in.pump().ratedFlow() * i / 100;
                    assertTrue(in.pump().head(q, in.speed()) <= lay.headTop(), "the head fits at " + q + ": " + in);
                    assertTrue(PumpCurve.INCEPTION * npsh.required(q) <= lay.npshTop(), "4 x NPSH3 fits at " + q + ": " + in);
                }
                if (Double.isFinite(in.available())) assertTrue(in.available() <= lay.npshTop(), "the NPSH available fits: " + in);
                if (Double.isFinite(in.head())) assertTrue(in.head() <= lay.headTop(), "the operating head fits: " + in);
                var p = lay.plot();
                assertTrue(p.x() >= box.x() && p.y() >= box.y() && p.right() <= box.right() && p.bottom() <= box.bottom() && p.w() > 100 && p.h() > 60, "the plot in its box: " + p + " in " + box);
                assertTrue(lay.headTicks().length >= 3 && lay.headTicks().length <= 7 && lay.npshTicks().length >= 3 && lay.npshTicks().length <= 7, "a few ticks: " + in);
            }
        assertEquals(Set.of(), texts.missing);
    }

    @Test
    void everythingItDrawsLiesInsideItsBoxInAFewDrawCalls() {
        var texts = new HeatControlColumnTest.Recording();
        int most = 0;
        for (var in : cases())
            for (var box : BOXES) {
                var c = new Recording();
                PumpCurve.draw(c, box, in, texts);
                var p = PumpCurve.layout(box, in, M, texts).plot();
                assertTrue(c.fills.size() > 20, "it draws: " + in);
                for (var f : c.fills) assertTrue(f[0] >= box.x() && f[1] >= box.y() && f[2] <= box.right() && f[3] <= box.bottom(), "a fill outside " + box + ": " + java.util.Arrays.toString(f) + " " + in);
                for (var t : c.texts) assertTrue(t[0] >= box.x() && t[1] >= box.y() && t[2] <= box.right() && t[3] <= box.bottom(), "a text outside " + box + ": " + java.util.Arrays.toString(t) + " " + in);
                // The operating point lies on the plot: its dot's centre inside the frame.
                if (Double.isFinite(in.flow()) && Double.isFinite(in.head())) {
                    var lay = PumpCurve.layout(box, in, M, texts);
                    int ox = lay.x(in.flow(), in.perTime()), oy = lay.y(in.head(), lay.headTop());
                    assertTrue(ox >= p.x() && ox <= p.right() && oy >= p.y() && oy <= p.bottom(), "the operating point on the plot: " + in);
                }
                most = Math.max(most, c.held.draws());
            }
        System.out.println("pump curve: at most " + most + " draw calls");
        assertTrue(most <= BUDGET, most + " draw calls against " + BUDGET);
        assertEquals(Set.of(), texts.missing);
    }

    /** Between NPSH3 and 4 x NPSH3 at the operating flow the line of NPSH available stands between the two curves (D38). */
    @Test
    void aCavitatingPumpsAvailableLineStandsBetweenItsCurves() {
        var texts = new HeatControlColumnTest.Recording();
        var pump = new CentrifugalPump(100 / 3600.0, 80, .70, 2950);
        double npsh3 = pump.npsh(1).required(pump.ratedFlow());
        var in = new PumpCurve.Input(pump, 1, pump.ratedFlow(), 80, 2 * npsh3, 3600, "m³/h");
        var lay = PumpCurve.layout(BOXES.get(1), in, M, texts);
        int available = lay.y(in.available(), lay.npshTop()), low = lay.y(npsh3, lay.npshTop()), high = lay.y(PumpCurve.INCEPTION * npsh3, lay.npshTop());
        assertTrue(high < available && available < low, "4 x NPSH3 above, NPSH3 below the line: " + high + " < " + available + " < " + low);
    }
}
