package com.wormzjl.icheme.science.equipment;

/**
 * Starting sizes for a new constructor choice; saved and imported designs keep their explicit
 * sizes.
 */
public final class ConstructorDefaults {
    public record Sizes(double diameter, int length) {}

    private ConstructorDefaults() {}

    public static Sizes sizes(VesselPlacement.Form form) {
        if (!form.designed()) {
            double max =
                    form.column()
                            ? EquipmentShape.MAX_COLUMN_DIAMETER
                            : EquipmentShape.MAX_DIAMETER;
            double diameter = Math.round((EquipmentShape.MIN_DIAMETER + max) / 2 * 2) / 2.0;
            return new Sizes(diameter, Math.max(2, (int) Math.round(1.5 * diameter)));
        }
        var design = UnitDesign.of(form.kind());
        var sizes = design.sizes();
        double diameter = sizes.get(sizes.size() / 2);
        int low = design.minLength(diameter), high = design.maxLength(diameter);
        int length = design.defaultLength(diameter);
        // Keep intentional proportions and counts (beds, stages, throws); otherwise start a length
        // inside its range.
        if (design.lengthInMetres() && (length == low || length == high))
            length = low + ((high - low) / design.lengthStep() + 1) / 2 * design.lengthStep();
        if (design.hasDuty()) {
            var duty = design.duty(diameter, length);
            return new Sizes(design.size(duty), design.length(duty));
        }
        return new Sizes(diameter, length);
    }
}
