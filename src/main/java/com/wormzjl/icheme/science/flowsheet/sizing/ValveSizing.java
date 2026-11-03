package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/**
 * A control valve (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.4). The world's valve has no Cv, so a valve
 * takes its inlet line's bore and its limits read the drop it holds. No kind of the constructor's builds one.
 */
public final class ValveSizing {
    private ValveSizing() {
    }

    /** @param bore m, its inlet line's */
    public record Size(double bore) implements UnitSize {
        @Override public @Nullable EquipmentShape shape() {
            return null;
        }
    }

    /**
     * A valve on its path.
     *
     * @param drop bar
     * @param path what the whole path loses, the valve with it (bar), null until the Pressure view gives it: then its row does not read
     */
    public record Load(double drop, @Nullable Double path) {
        public static Load of(FlowStream in, FlowStream out, @Nullable Double path) {
            return new Load((in.pressure() - out.pressure()) / 1e5, path);
        }
    }

    /** Every limit of {@code load}, whose size is its line's. */
    public static Rating rate(Load load) {
        var readings = new ArrayList<Reading>();
        readings.add(Limit.VALVE_DROP.read(load.drop()));
        var path = load.path();
        if (path != null) readings.add(Limit.VALVE_SHARE.read(100 * load.drop() / path));
        return new Rating(readings);
    }
}
