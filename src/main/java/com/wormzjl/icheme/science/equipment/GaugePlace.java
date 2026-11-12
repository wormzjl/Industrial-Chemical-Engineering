package com.wormzjl.icheme.science.equipment;

import org.jetbrains.annotations.Nullable;

/**
 * A vessel's level gauge accessory's place, kind-neutral (batch 2026-11-12-fluid-conversion, W3, plan D11), in its unit's
 * frame: the gauge's column axis ({@code x}, {@code z}) standing off a round shell of radius {@code r} on axis ({@code cx},
 * {@code cz}), facing {@code out}; its drain down to {@code grade}, its chamber from {@code low} to {@code high}; its scale ticked
 * at each metre over the {@code floor}, {@code scale} m of them. A floating-roof tank's ({@link TankDesign.Plan#gauge}) and a
 * liquid fluid converter's ({@link ConverterDesign#gauge}) give one; {@link TankGaugeGeometry} draws it.
 */
public record GaugePlace(double x, double z, double[] out, double cx, double cz, double r, double grade, double low, double high, double floor, double scale) {
    public GaugePlace {
        out = out.clone();
    }

    @Override
    public double[] out() {
        return out.clone();
    }

    /** The place on {@code shape}, or null for a shape that takes no level gauge. */
    public static @Nullable GaugePlace of(EquipmentShape shape) {
        return switch (shape.kind()) {
            case TANK -> TankDesign.Plan.of(shape.diameter(), (int) shape.tangentLength()).gaugePlace();
            case FLUID_CONVERTER -> ConverterDesign.gaugePlace(shape.diameter(), shape.tangentLength());
            default -> null;
        };
    }
}
