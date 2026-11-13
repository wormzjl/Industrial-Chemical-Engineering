package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.List;

/**
 * A delayed coker's drum set (NEW_UNITS_PLAN.md; {@code NEW_UNITS_SURVEY_FCC_COKER.md} section 3; owner decisions D3 and
 * D4 2026-10-18): one or two pairs of coke drums 4 to 10 m across in one row with everything they stand on and in. The
 * massive concrete table with the switch deck under it, the unheading valves and telescoping chutes hanging beneath; on
 * the table the drums' skirts and the steel frame with its intermediate deck and the cutting deck; over each drum a
 * lattice derrick with its crown block and hoist, crosshead on guide rails, drill stem and high-pressure hose loop; the
 * overhead lines into the vapour, blowdown and relief headers; one stair tower. One drum of each pair is being cut: its
 * crosshead, stem and hose ride a slow slider-crank. The heater, fractionator, blowdown drum, jet pump, coke pit and crane
 * are separate units [Q4]. Sizes and layout in {@link DelayedCokerPlan}; drawn by {@link DelayedCokerDrums},
 * {@link DelayedCokerStructure} and {@link DelayedCokerDerrick}.
 */
final class DelayedCokerDesign extends UnitDesign {
    DelayedCokerDesign() {
        // minSupport: The bottom flanges 8 m up at least: the unheading valves, the switch deck and the chutes hang
        // under the table.
        super(new Sizes(4, 10, .5,
                1, 2,
                8, EquipmentShape.MAX_SUPPORT,
                Orientation.VERTICAL, "delayed_coker.drum", "delayed_coker.pairs", "delayed_coker.flange", true));
    }

    @Override
    public int defaultSupport(double size, int length) {
        return 12;
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("delayed_coker.coke", whole(DelayedCokerPlan.coke(d)), whole(DelayedCokerPlan.feed(d, (int) shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        var p = new DelayedCokerPlan(shape);
        return List.of(new Line("delayed_coker.drums", p.drums, size(p.d), number(p.straight), number(p.drumHeight())),
                new Line("delayed_coker.derricks", number(p.derrick), whole(p.yTop)),
                new Line("delayed_coker.coke", whole(DelayedCokerPlan.coke(p.d)), whole(DelayedCokerPlan.feed(p.d, p.pairs))));
    }

    @Override
    void build(ProcessUnit unit) {
        var plan = new DelayedCokerPlan(unit.shape);
        DelayedCokerDrums.draw(unit, plan);
        DelayedCokerStructure.draw(unit, plan);
        DelayedCokerDerrick.draw(unit, plan);
    }
}
