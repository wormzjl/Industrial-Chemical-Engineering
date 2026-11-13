package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.List;

/**
 * A pressurised fluidized-bed gasifier of the HTW type (owner decisions D1, D3, D4 of 2026-10-18, NEW_UNITS_PLAN.md;
 * {@code NEW_UNITS_SURVEY_SOLID_FUEL.md} section 1, gasifier mode), drawn by {@link FluidizedBedGasifierLayout}. Its main
 * size is the refractory-lined vessel's inside diameter ID, 1.5 to 6 m in 0.25 m steps (Berrenrath's bed is 2.75 m,
 * 140 MW): the shell 0.8 m wider (0.3 to 0.45 m of refractory), 12 + 4 ID between its tangent lines (23 m at Berrenrath),
 * 2:1 heads; the fuel input Berrenrath's 140 MW per bed area (24 MW/m²). It has no second size. The support is the
 * bottom tangent line's height: at least the ash system under it (the outlet, the water-cooled ash screw, the ash lock
 * hopper half the ID across between its valves, its outlet over a free block), at most 16 m; the vessel stands on its
 * support ring a third of the way up, on the structure's floor there.
 */
final class FluidizedBedGasifierDesign extends UnitDesign {
    /** Berrenrath's fuel input per bed area, MW/m²: 140 MW over a 2.75 m bed. */
    static final double BED_RELEASE = 140 / (Math.PI / 4 * 2.75 * 2.75);

    /** The shell's outside diameter for inside diameter {@code id}, m: 0.4 m of refractory and shell each side. */
    static double shell(double id) {
        return id + .8;
    }

    /** The vessel's height between its tangent lines, m. */
    static double tangent(double id) {
        return 12 + 4 * id;
    }

    /** The fuel input, MW. */
    static double input(double id) {
        return BED_RELEASE * Math.PI / 4 * id * id;
    }

    /** The number of cyclones: two over 4.5 m ID (survey). */
    static int cyclones(double id) {
        return id > 4.5 ? 2 : 1;
    }

    /** A cyclone's inside diameter, m: 0.7 ID with one, the same gas through two of 0.7 ID / √2 (survey: about 0.7 ID). */
    static double cyclone(double id) {
        return .7 * id / Math.sqrt(cyclones(id));
    }

    /** The ash screw's casing diameter, m. */
    static double ashScrew(double id) {
        return Math.max(.45, Math.min(1, .12 * id + .3));
    }

    /** The ash lock hopper's diameter, m: half the ID, 1 to 2.5 m; it is 1.6 times as tall. */
    static double ashLock(double id) {
        return Math.max(1, Math.min(2.5, .5 * id));
    }

    FluidizedBedGasifierDesign() {
        // Computed in its overrides: minSupport.
        super(new Sizes(1.5, 6, .25,
                1, 1,
                Sizes.COMPUTED, EquipmentShape.MAX_SUPPORT,
                Orientation.VERTICAL, "fluidized_bed_gasifier.diameter", null, "fluidized_bed_gasifier.elevation", true));
    }

    /**
     * The lowest bottom tangent line: under the 2:1 head's apex the outlet's neck (0.4 m), the ash screw, its chute (0.3 m)
     * and the lock's top valve (0.5 m), the ash lock, its bottom valve (0.5 m) and outlet (0.3 m), its flange face
     * {@link ProcessUnit#NECK} out (a pipe's largest root, owner decision 2026-10-21) and the pipe block under it.
     */
    @Override
    public int minSupport(double size, int length) {
        double under = shell(size) / 4 + .4 + ashScrew(size) + .3 + .5 + 1.6 * ashLock(size) + .5 + .3 + 1 + ProcessUnit.NECK;
        return (int) Math.ceil(under - 1e-9);
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("fluidized_bed_gasifier.input", whole(input(shape.diameter())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double id = shape.diameter();
        return List.of(new Line("fluidized_bed_gasifier.vessel", size(id), number(shell(id)), number(tangent(id))),
                new Line("fluidized_bed_gasifier.cyclones", cyclones(id), number(cyclone(id))),
                new Line("fluidized_bed_gasifier.locks", number(1.1 * id), number(1.5 * id)),
                new Line("fluidized_bed_gasifier.input", whole(input(id))));
    }

    @Override
    void build(ProcessUnit unit) {
        new FluidizedBedGasifierLayout(unit).draw();
    }
}
