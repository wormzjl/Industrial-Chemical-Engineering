package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.fluid.network.Chimney;

import java.util.List;

/**
 * A chimney (batch 2026-11-10-chimney, owner C3 A): a self-supporting steel stack ({@link ProcessUnit#steelStack}) or a
 * tapered brick or concrete chimney with a steel liner ({@link ProcessUnit#brickChimney}). Its main size is the exit
 * diameter on its form's grid ({@link Chimney.Form}), its second the height in whole metres (owner: "Scalable").
 */
final class ChimneyDesign extends UnitDesign {
    private final Chimney.Form form;

    ChimneyDesign(Chimney.Form form) {
        this.form = form;
    }

    @Override
    public double minSize() {
        return form.minDiameter;
    }

    @Override
    public double maxSize() {
        return form.maxDiameter;
    }

    @Override
    public double sizeStep() {
        return form.diameterStep;
    }

    @Override
    public int minLength(double size) {
        return form.minHeight;
    }

    @Override
    public int maxLength(double size) {
        return form.maxHeight(size);
    }

    /** A new chimney stands at the height good engineering practice gives a 15 m building beside it (37.5 m), within its range. */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), Chimney.gepHeight(15)));
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
        return "chimney_diameter";
    }

    @Override
    public String lengthRow() {
        return "chimney_height";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("chimney_rating", number(Chimney.ratedVolumeFlow(d)), number(form.designVelocity * Math.PI * d * d / 4 / Chimney.SPARE));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        return List.of(new Line("chimney", size(d), whole(shape.tangentLength())),
                new Line("chimney.duty", number(Chimney.ratedVolumeFlow(d)), whole(form.hotLimit - 273.15)));
    }

    @Override
    void build(ProcessUnit unit) {
        if (form == Chimney.Form.STEEL) unit.steelStack();
        else unit.brickChimney();
    }
}
