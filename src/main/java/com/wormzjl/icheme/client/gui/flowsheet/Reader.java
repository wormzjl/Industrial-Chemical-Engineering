package com.wormzjl.icheme.client.gui.flowsheet;

import org.jetbrains.annotations.Nullable;

/** A unit's meters as a door shows them: a reading of the results shown, scaled and rounded, blank before a run or where the run read none. */
record Reader(@Nullable SheetResults results, String unit) {
    /** The meters of {@code unit} on the results the sheet shows for it. */
    static Reader of(SheetContext x, String unit) {
        return new Reader(x.state().shown(unit), unit);
    }

    private double value(String key) {
        return results == null ? Double.NaN : results.reading(unit, key);
    }

    /** The reading {@code key} over {@code scale}, at {@code digits}. */
    String of(String key, double scale, int digits) {
        double value = value(key);
        return Double.isNaN(value) ? "" : SheetUnits.number(digits, value / scale);
    }

    String temperature(SheetUnits u) {
        double value = value("temperature");
        return Double.isNaN(value) ? "" : u.t(value);
    }

    String pressure(SheetUnits u) {
        double value = value("pressure");
        return Double.isNaN(value) ? "" : u.p(value);
    }
}
