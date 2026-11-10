package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.unit.PrimeMoverUnit;
import com.wormzjl.icheme.science.machine.PrimeMover;

import java.util.ArrayList;
import java.util.List;

/**
 * A combustion engine or a gas turbine (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md section 11): the smallest model whose
 * rated fuel holds the run's fuel heat at design flow, those that hold it with {@link Limit#AUTO_SPARE} first (the owner's 20 %
 * spare). A gas turbine whose coil the run wired is sized with its coil.
 */
public final class PrimeMoverSizing {
    private PrimeMoverSizing() {
    }

    /** @param family its family; @param model its model's number, 1 to the family's count; @param coil a gas turbine's steam coil */
    public record Size(PrimeMover.Family family, int model, boolean coil) implements UnitSize {
        public Size {
            java.util.Objects.requireNonNull(family);
            if (model < 1 || model > PrimeMoverUnit.models(family)) throw new IllegalArgumentException("No " + family + " model " + model);
            if (coil && family != PrimeMover.Family.GAS_TURBINE) throw new IllegalArgumentException("Only a gas turbine has a steam coil");
        }

        /** Its rated fuel heat, MW. */
        double ratedFuel() {
            return PrimeMover.models(family).get(model - 1).ratedFuel() / 1e6;
        }

        @Override public EquipmentShape shape() {
            var shape = PrimeMoverUnit.shape(family, model, coil);
            return UnitSize.unit(shape.kind(), shape.diameter(), (int) shape.tangentLength(), ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A prime mover at design flow.
     *
     * @param fired  the fuel's heat, MW
     * @param family the tile's family
     * @param coil   whether the run wired a gas turbine's coil
     */
    public record Load(double fired, PrimeMover.Family family, boolean coil) {
        public static Load of(double fired, PrimeMover.Family family, boolean coil, DesignBasis basis) {
            return new Load(fired > 0 ? fired * basis.designFlow() / 1e6 : 0, family, coil);
        }

        boolean flows() {
            return fired > 0;
        }
    }

    /** The models of the load's family, smallest first. */
    static List<Size> sizes(Load load) {
        var out = new ArrayList<Size>();
        for (int model = 1; model <= PrimeMoverUnit.models(load.family()); model++) out.add(new Size(load.family(), model, load.coil()));
        return out;
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.PRIME_MOVER_FUEL.against(load.fired(), size.ratedFuel()));
        readings.add(Limit.PRIME_MOVER_SPARE.read(100 * (size.ratedFuel() / load.fired() - 1)));
        return new Rating(readings);
    }

    /** The smallest model that meets every limit, those that hold the fuel with its spare first. No fuel, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        double held = Selection.spared(load.fired());
        return Selection.smallest(Selection.spareFirst(sizes(load), Size::ratedFuel, held), size -> rate(load, size));
    }
}
