package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.fluid.network.Flare;

import java.util.List;

/**
 * An elevated flare stack ({@link ProcessUnit#flareStack}; batch 2026-11-09-flare-stack, owner X3: one size): its tip
 * {@link Flare#MIN_TIP} to {@link Flare#MAX_TIP} m across sets its rated heat release ({@link Flare#rated}) and its
 * stack's height ({@link Flare#stackHeight}).
 */
final class FlareStackDesign extends UnitDesign {
    @Override
    public double minSize() {
        return Flare.MIN_TIP;
    }

    @Override
    public double maxSize() {
        return Flare.MAX_TIP;
    }

    @Override
    public double sizeStep() {
        return Flare.TIP_STEP;
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public int maxSupport() {
        return 0;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "flare_tip";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("flare_rating", number(Flare.rated(shape.diameter()) / 1e6), number(Flare.stackHeight(shape.diameter())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double tip = shape.diameter();
        return List.of(new Line("flare_stack", size(tip), number(Flare.stackHeight(tip))),
                new Line("flare_stack.duty", number(Flare.rated(tip) / 1e6), number(Flare.FLAME_LIMIT)));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.flareStack();
    }
}
