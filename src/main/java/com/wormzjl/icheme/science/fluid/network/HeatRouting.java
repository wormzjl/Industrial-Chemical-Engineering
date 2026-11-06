package com.wormzjl.icheme.science.fluid.network;

import org.jetbrains.annotations.Nullable;

/** Routes through a shared wall. Crossflow walls use canonical row/column coordinates on both sides. */
public enum HeatRouting {
    SERIES, TWO_PASS_TUBES, MIXED_CROSSFLOW;

    @FunctionalInterface
    interface CellWall {
        double temperature(int cell, double enthalpy, double massFlow);
    }

    int grid(int cells) {
        int n = (int) Math.sqrt(cells);
        if (n < 2 || n * n != cells || n % 2 != 0) throw new IllegalArgumentException("Crossflow needs an even square wall grid");
        return n;
    }

    public void validate(int cells) {
        if (this != SERIES) grid(cells);
    }

    double laneFlow(double flow, int cells) {
        return switch (this) {
            case SERIES -> flow;
            case TWO_PASS_TUBES -> flow / (grid(cells) / 2);
            case MIXED_CROSSFLOW -> flow / grid(cells);
        };
    }

    /** Marches each parallel lane, mixing by enthalpy at headers and between shell rows. */
    double visit(HeatCurve curve, double inlet, double mass, double area, double[] wall, boolean forward,
                 double @Nullable [] entering, @Nullable CellWall update) {
        if (!(mass > 0)) {
            if (entering != null) java.util.Arrays.fill(entering, inlet);
            return inlet;
        }
        double cell = area / wall.length;
        if (this == SERIES) {
            double h = inlet;
            for (int i = 0; i < wall.length; i++) {
                int k = forward ? i : wall.length - 1 - i;
                h = cell(curve, h, mass, cell, wall, k, entering, update);
            }
            return h;
        }
        int n = grid(wall.length);
        double lane = laneFlow(mass, wall.length), h = inlet;
        if (this == MIXED_CROSSFLOW) {
            for (int i = 0; i < n; i++) {
                int row = forward ? i : n - 1 - i;
                double sum = 0;
                for (int col = 0; col < n; col++) sum += cell(curve, h, lane, cell, wall, row * n + col, entering, update);
                h = sum / n;
            }
        } else {
            for (int pass = 0; pass < 2; pass++) {
                // Row zero is at the upper shell inlet. The front channel's tube inlet is below the outlet.
                int first = (forward == (pass == 0)) ? n / 2 : 0;
                double sum = 0;
                for (int row = first; row < first + n / 2; row++) {
                    double value = h;
                    for (int i = 0; i < n; i++) {
                        int col = pass == 0 ? i : n - 1 - i;
                        value = cell(curve, value, lane, cell, wall, row * n + col, entering, update);
                    }
                    sum += value;
                }
                h = sum / (n / 2);
            }
        }
        return h;
    }

    private static double cell(HeatCurve curve, double h, double mass, double area, double[] wall, int k,
                               double @Nullable [] entering, @Nullable CellWall update) {
        if (entering != null) entering[k] = h;
        if (update != null) wall[k] = update.temperature(k, h, mass);
        return curve.march(h, mass, area, wall[k]);
    }
}
