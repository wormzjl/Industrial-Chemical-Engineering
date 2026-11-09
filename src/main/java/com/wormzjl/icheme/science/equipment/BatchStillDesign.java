package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * A batch still ({@link ProcessUnit#batchStill}; batch 2026-11-09-batch-distiller, W2): a copper pot of 100 to 2000 L
 * (its main size, litres), heated as its {@link BatchStillOptions} say, with a worm-tub condenser, a column or neither.
 * Its pot's diameter follows the plan's size table ({@link #potDiameter}), its heated surface (the bottom and the
 * flue-swept lower wall) is about 1.73 D² (BATCH_DISTILLER_PLAN.md section 2).
 */
final class BatchStillDesign extends UnitDesign {
    /** The pot sizes a player picks, litres: the middle one (500 L) is a new still's. */
    static final List<Double> POTS = List.of(100.0, 150.0, 200.0, 300.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0);

    /**
     * The pot's diameter law {@code D = 0.5 (V / 100 L)^b}, b = ln 2.74 / ln 20 = 0.3365: through the plan's 0.50 m at
     * 100 L and 1.37 m at 2000 L, and 0.86 m at 500 L (section 2's table), to the centimetre.
     */
    static final double POT_EXPONENT = DeterministicMath.log(2.74) / DeterministicMath.log(20);

    /** The pot's heated surface over D²: its bottom and the lower wall the flue gas sweeps (0.3 D high). */
    static final double HEATED_AREA = 1.73;

    /** The pot's diameter for {@code litres}, m, to the centimetre. */
    static double potDiameter(double litres) {
        return Math.round(50 * DeterministicMath.pow(litres / 100, POT_EXPONENT)) / 100.0;
    }

    /** The column's diameter for a pot of {@code d}, m: the plan's 124 mm at 100 L to 338 mm at 2000 L (an F-factor of 1.5 at full boil-up). */
    static double columnDiameter(double d) {
        return Math.round(248 * d) / 1000.0;
    }

    @Override
    public double minSize() {
        return POTS.getFirst();
    }

    @Override
    public double maxSize() {
        return POTS.getLast();
    }

    /** A notional step; the sizes are the list ({@link #sizes()}). */
    @Override
    public double sizeStep() {
        return 50;
    }

    @Override
    public List<Double> sizes() {
        return POTS;
    }

    @Override
    public double snap(double size) {
        return Double.isFinite(size) ? MachineDuty.nearest(POTS, size) : minSize();
    }

    @Override
    public double step(double size, int steps) {
        return MachineDuty.step(POTS, size, steps);
    }

    @Override
    public String sizeUnit() {
        return "L";
    }

    @Override
    public String sizeText(double size) {
        return whole(size);
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
        return "still_pot";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = potDiameter(shape.diameter());
        return new Line("still_pot_held", size(d), number(HEATED_AREA * d * d));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return "." + shape.still().heater().key();
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        var options = shape.still();
        double d = potDiameter(shape.diameter());
        var out = new ArrayList<Line>();
        out.add(new Line("batch_still", whole(shape.diameter()), size(d), number(HEATED_AREA * d * d)));
        out.add(new Line("batch_still.heater." + options.heater().key()));
        if (options.column())
            out.add(new Line("batch_still.column", Integer.toString(options.stages()), whole(1000 * columnDiameter(d))));
        out.add(new Line("batch_still.condenser." + (options.column() ? "column" : options.condenser().key())));
        return out;
    }

    @Override
    void build(ProcessUnit unit) {
        unit.batchStill();
    }
}
