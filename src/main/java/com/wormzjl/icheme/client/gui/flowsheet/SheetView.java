package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import org.jetbrains.annotations.Nullable;

/**
 * Pan and zoom over the canvas (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): canvas pixels from design units and back,
 * one zoom step, and fit. A wheel step keeps what is under the cursor where it is.
 */
public final class SheetView {
    public static final double LEAST = .25, MOST = 2, STEP = 1.25;
    /** Fit leaves this much room round a sheet, in canvas pixels. */
    private static final int ROOM = 16;

    private double zoom = 1, x, y;
    private Rect box = new Rect(0, 0, 0, 0);

    public void layout(Rect box) {
        this.box = box;
    }

    public Rect box() {
        return box;
    }

    public double zoom() {
        return zoom;
    }

    public double canvasX(double designX) {
        return box.x() + x + designX * zoom;
    }

    public double canvasY(double designY) {
        return box.y() + y + designY * zoom;
    }

    public double designX(double canvasX) {
        return (canvasX - box.x() - x) / zoom;
    }

    public double designY(double canvasY) {
        return (canvasY - box.y() - y) / zoom;
    }

    public void pan(double dx, double dy) {
        x += dx;
        y += dy;
    }

    /** Zooms by {@code factor} about canvas point ({@code cx}, {@code cy}), held in its limits. */
    public void zoom(double factor, double cx, double cy) {
        double dx = designX(cx), dy = designY(cy);
        zoom = Math.clamp(zoom * factor, LEAST, MOST);
        x = cx - box.x() - dx * zoom;
        y = cy - box.y() - dy * zoom;
    }

    public void wheel(double delta, double cx, double cy) {
        zoom(delta > 0 ? STEP : 1 / STEP, cx, cy);
    }

    /** Shows {@code sheet} whole, never above zoom 1; no sheet goes to the origin. */
    public void fit(@Nullable Rect sheet) {
        if (sheet == null || sheet.w() == 0 && sheet.h() == 0) {
            zoom = 1;
            x = 0;
            y = 0;
            return;
        }
        double wide = (double) (box.w() - 2 * ROOM) / Math.max(1, sheet.w()), tall = (double) (box.h() - 2 * ROOM) / Math.max(1, sheet.h());
        zoom = Math.clamp(Math.min(wide, tall), LEAST, 1);
        x = (box.w() - sheet.w() * zoom) / 2 - sheet.x() * zoom;
        y = (box.h() - sheet.h() * zoom) / 2 - sheet.y() * zoom;
    }
}
