package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.List;

/**
 * A centrifugal pump casing by its design point ({@link ProcessUnit#pump}; batch 2026-11-03-rotating-machinery, W4):
 * rated flow, rated head and speed class (2 or 4 poles). Its discharge carries the flow at 3 m/s; its impeller follows
 * the head and the speed. API 610 OH2 up to a {@link ProcessUnit#OH2_MAX} discharge, BB2 radially split between bearings above, both nozzles on top, up to
 * 0.6 m. It has no motor of its own: its shaft end takes a driver through a shaft block (owner decision D4). Its plinth
 * stands at grade; the constructor's Up lifts it onto a deck. A pump saved before its design point takes the datasheet's
 * values of its discharge (decision A3): the flow at 3 m/s, 100 m, 2 poles.
 */
final class PumpDesign extends UnitDesign {
    /** The pump's heads, m, and the least flow, m³/h. */
    static final double MIN_HEAD = 5, MAX_HEAD = 300, MIN_FLOW = 1;
    /** A pump saved before its design point: its head, m, and its poles (decision A3). */
    static final double DEFAULT_HEAD = 100;
    static final int DEFAULT_POLES = 2;

    @Override
    public double minSize() {
        return ProcessUnit.SUCTION_STEP;
    }

    @Override
    public double maxSize() {
        // The largest discharge whose suction, one size larger (ProcessUnit.pumpSuction), is a pipe's largest.
        return .6;
    }

    @Override
    public double sizeStep() {
        return ProcessUnit.SUCTION_STEP;
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
        return Orientation.HORIZONTAL;
    }

    @Override
    public boolean moves() {
        return true;
    }

    @Override
    public boolean hasDuty() {
        return true;
    }

    @Override
    public MachineDuty duty(double size, int length) {
        return MachineDuty.pump(ProcessUnit.pumpFlow(size), DEFAULT_HEAD, DEFAULT_POLES);
    }

    @Override
    public double size(MachineDuty duty) {
        return ProcessUnit.pumpDischarge(duty.flow());
    }

    @Override
    void checkDuty(MachineDuty duty) {
        if (!(duty.flow() >= MIN_FLOW && duty.flow() <= ProcessUnit.pumpFlow(maxSize()) + 1e-6))
            throw new IllegalArgumentException("A pump's rated flow is " + MIN_FLOW + " to " + whole(ProcessUnit.pumpFlow(maxSize())) + " m³/h, not " + duty.flow());
        if (!(duty.head() >= MIN_HEAD && duty.head() <= MAX_HEAD)) throw new IllegalArgumentException("A pump's rated head is 5 to 300 m, not " + duty.head());
        if (duty.poles() != 2 && duty.poles() != 4) throw new IllegalArgumentException("A pump runs at 2 or 4 poles, not " + duty.poles());
        if (duty.polytropicHead() != 0 || duty.power() != 0 || duty.steam() != null || duty.exhaust() != 0 || duty.drive() != MachineDuty.Drive.FIXED)
            throw new IllegalArgumentException("A pump's design point is its flow, head and speed");
    }

    /** Flow and head step along the R10 numbers, to the largest discharge's flow and to 300 m. */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        return switch (setting) {
            case "flow" -> MachineDuty.pump(MachineDuty.step(MachineDuty.series(MIN_FLOW, ProcessUnit.pumpFlow(maxSize())), duty.flow(), steps), duty.head(), duty.poles());
            case "head" -> MachineDuty.pump(duty.flow(), MachineDuty.step(MachineDuty.series(MIN_HEAD, MAX_HEAD), duty.head(), steps), duty.poles());
            default -> duty;
        };
    }

    @Override
    public String sizeRow() {
        return "pump_discharge";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        var duty = shape.duty();
        return new Line("pump_flow", whole(duty.flow()), whole(duty.head()), whole(ProcessUnit.pumpPower(duty)));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return shape.diameter() > ProcessUnit.OH2_MAX + 1e-9 ? ".split" : "";
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        var duty = shape.duty();
        return List.of(new Line("pump", size(d), size(ProcessUnit.pumpSuction(d))),
                new Line("pump_duty", whole(duty.flow()), whole(duty.head()), whole(duty.speed()), whole(ProcessUnit.pumpPower(duty))));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.pump();
    }
}
