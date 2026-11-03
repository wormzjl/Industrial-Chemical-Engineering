package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Passages;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FiredHeaterUnit;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sized unit's passages (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.11, D19): the world's own law of each
 * at {@code flow} times a stream's rate, as the run reads them at 1 and a rating at the design flow. A side's law reads its inlet,
 * as the rating does; an air cooler's reads the stream at the mean of its ends; a heater's coils march the homogeneous law down the
 * heat in slices, as a coil that boils loses most where it boils. A size the constructor cannot build has no passage, so the door's
 * estimate stands and the lamp is red.
 */
public final class SizedPassages {
    /** A heater's coils in slices along the heat: the probe's, whose 1.95 bar on the sample the plan reads. */
    private static final int CONVECTION_SLICES = 12, RADIANT_SLICES = 24;
    /** A marching pressure never falls under this, Pa, so a coil that loses all it has still has a state to read. */
    private static final double LEAST_PRESSURE = 1e4;

    private SizedPassages() {
    }

    /** {@code unit}'s passages by {@code facts}: none for a unit with no size of its class, or one the constructor refuses. */
    public static Passages of(SizingFacts facts, Flowsheet.Unit unit, FlowThermo thermo) {
        var laws = new HashMap<String, Passages.Law>();
        String id = unit.id();
        switch (unit.type()) {
            case "exchanger", "kettle" -> {
                if (exchanger(facts.unit(id).size(), unit.type().equals("kettle")) instanceof ExchangerSizing.Size size) {
                    laws.put("tube_drop", (in, out) -> tube(size, in, 1, thermo));
                    // A kettle's shell is its pool, with no law: its door's drop stands.
                    if (!size.kettle()) laws.put("shell_drop", (in, out) -> shell(size, in, 1, thermo));
                }
            }
            case "air_cooler" -> {
                if (facts.unit(id).size() instanceof AirCoolerSizing.Size size && UnitSize.built(size) != null) laws.put("drop", outlet((in, out) -> bank(size, in, out, 1, thermo)));
            }
            case "fired_heater" -> {
                if (facts.unit(id).size() instanceof HeaterSizing.Size size && UnitSize.built(size) != null)
                    for (var coil : new String[]{FiredHeaterUnit.COILS, FiredHeaterUnit.CONVECTION_DROP, FiredHeaterUnit.RADIANT_DROP})
                        laws.put(coil, outlet((in, out) -> coils(size, coil, in, out, 1, thermo)));
            }
            case "column" -> {
                // A member stands as many times as its door counts (S3b), each unit at the size its facts hold.
                var door = ColumnDoor.of(unit.params());
                var units = door.units();
                if (door.condenser() == ColumnComponents.Condenser.EXCHANGER && exchanger(facts.unit(SheetSizing.member(id, "condenser")).size(), false) instanceof ExchangerSizing.Size size)
                    laws.put(ColumnUnit.CONDENSER_DROP, (in, out) -> tube(size.withShells(units.condenser()), in, 1, thermo));
                if (exchanger(facts.unit(SheetSizing.member(id, "reboiler")).size(), true) instanceof ExchangerSizing.Size size)
                    laws.put(ColumnUnit.REBOILER_DROP, (in, out) -> tube(size.withShells(units.kettle()), in, 1, thermo));
                for (int i = 0; i < door.pumparounds().size(); i++) {
                    var pumparound = door.pumparounds().get(i);
                    int shells = units.cooler(i);
                    if (exchanger(facts.unit(SheetSizing.member(id, "pa " + pumparound.id() + " cooler")).size(), false) instanceof ExchangerSizing.Size size)
                        laws.put(ColumnUnit.pumparoundDrop(pumparound), (in, out) -> tube(size.withShells(shells), in, 1, thermo));
                }
            }
            default -> {
            }
        }
        if (laws.isEmpty()) return Passages.TYPED;
        Map<String, Passages.Law> held = Map.copyOf(laws);
        return held::get;
    }

    /** What {@link #of} reads of {@code facts} for {@code unit}: a change in it moves the unit's run. */
    public static List<Object> reads(SizingFacts facts, Flowsheet.Unit unit) {
        String id = unit.id();
        return switch (unit.type()) {
            case "exchanger", "kettle", "air_cooler", "fired_heater" -> Arrays.asList(facts.unit(id).size());
            case "column" -> {
                var door = ColumnDoor.of(unit.params());
                var read = new ArrayList<Object>(Arrays.asList(facts.unit(SheetSizing.member(id, "condenser")).size(), facts.unit(SheetSizing.member(id, "reboiler")).size(), door.units()));
                for (var pumparound : door.pumparounds()) read.add(facts.unit(SheetSizing.member(id, "pa " + pumparound.id() + " cooler")).size());
                yield read;
            }
            default -> List.of();
        };
    }

    /**
     * What {@code in} loses through {@code passage} of {@code size} at {@code flow} times its rate, Pa, on its way to {@code out}:
     * an exchanger's shell by {@code shell_drop}, its tubes by any other; a bank; a heater's coils. Null where {@code size} has no
     * law the constructor builds.
     */
    public static @Nullable Double drop(@Nullable UnitSize size, String passage, FlowStream in, @Nullable FlowStream out, double flow, FlowThermo thermo) {
        if (size == null || UnitSize.built(size) == null) return null;
        return switch (size) {
            case ExchangerSizing.Size exchanger -> passage.equals("shell_drop") ? exchanger.kettle() ? null : (Double) shell(exchanger, in, flow, thermo) : (Double) tube(exchanger, in, flow, thermo);
            case AirCoolerSizing.Size cooler -> bank(cooler, in, out, flow, thermo);
            case HeaterSizing.Size heater -> coils(heater, passage, in, out, flow, thermo);
            default -> null;
        };
    }

    /** {@code size} if it is an exchanger of the tile's kind the constructor builds. */
    private static ExchangerSizing.@Nullable Size exchanger(@Nullable UnitSize size, boolean kettle) {
        return size instanceof ExchangerSizing.Size exchanger && exchanger.kettle() == kettle && UnitSize.built(exchanger) != null ? exchanger : null;
    }

    private static Passages.Law outlet(Passages.Law law) {
        return new Passages.Law() {
            @Override public double drop(FlowStream in, @Nullable FlowStream out) {
                return law.drop(in, out);
            }

            @Override public boolean readsOutlet() {
                return true;
            }
        };
    }

    /** What {@code in} loses through {@code size}'s tubes at {@code flow} times its rate, Pa, its shells in parallel. */
    public static double tube(ExchangerSizing.Size size, FlowStream in, double flow, FlowThermo thermo) {
        return LineSizing.Carried.of(in, flow, thermo, new MixtureViscosity.Workspace()).drop(HeatSides.tubePassage(size.shell(), size.tubes()), size.shells()) * 1e5;
    }

    /** What {@code in} loses through {@code size}'s shells at {@code flow} times its rate, Pa. */
    public static double shell(ExchangerSizing.Size size, FlowStream in, double flow, FlowThermo thermo) {
        return LineSizing.Carried.of(in, flow, thermo, new MixtureViscosity.Workspace()).drop(HeatSides.shellPassage(size.shell(), size.tubes()), size.shells()) * 1e5;
    }

    /** What the stream loses through {@code size}'s banks in parallel between {@code in} and {@code out} at {@code flow} times its rate, Pa: at its mean temperature. */
    public static double bank(AirCoolerSizing.Size size, FlowStream in, @Nullable FlowStream out, double flow, FlowThermo thermo) {
        double mean = out == null ? in.temperature() : (in.temperature() + out.temperature()) / 2;
        var state = thermo.atTemperature(mean, in.pressure(), in.amountsView(), in.solids());
        return LineSizing.Carried.of(state, flow, thermo, new MixtureViscosity.Workspace()).drop(HeatSides.airCoolerPassage(size.width(), size.length(), size.bank()), size.banks()) * 1e5;
    }

    /**
     * What {@code in} loses through {@code size}'s coils heated to {@code out} at {@code flow} times its rate, Pa, its heaters in
     * parallel. {@code passage} names both coils in series, the convection coil taking its share of the heat first (its bare tube
     * at its flux against the radiant coil's), or one coil set apart, which takes the whole of its pair's heat.
     */
    public static double coils(HeaterSizing.Size size, String passage, FlowStream in, @Nullable FlowStream out, double flow, FlowThermo thermo) {
        PipeResistance.Geometry convection = null, radiant = null;
        double convectionHeat = 0, radiantHeat = 0;
        for (var side : HeatSides.of(size.shape())) {
            if (side.side() == HeatSides.Side.CONVECTION) {
                convection = side.passage();
                convectionHeat = HeatSides.coilDuty(HeatSides.Side.CONVECTION, side.area(), 1);
            } else if (side.side() == HeatSides.Side.RADIANT) {
                radiant = side.passage();
                radiantHeat = HeatSides.coilDuty(HeatSides.Side.RADIANT, side.area(), 1);
            }
        }
        if (convection == null || radiant == null) throw new IllegalStateException("A heater has two coils");
        double heat = out == null ? 0 : out.enthalpy() - in.enthalpy(), share = convectionHeat / (convectionHeat + radiantHeat);
        var march = new March(in, heat, size.count(), flow, thermo);
        if (passage.equals(FiredHeaterUnit.CONVECTION_DROP)) return march.through(convection, 0, 1, CONVECTION_SLICES);
        if (passage.equals(FiredHeaterUnit.RADIANT_DROP)) return march.through(radiant, 0, 1, RADIANT_SLICES);
        return march.through(convection, 0, share, CONVECTION_SLICES) + march.through(radiant, share, 1, RADIANT_SLICES);
    }

    /** A coil's march: the pressure it has reached, from the inlet's. */
    private static final class March {
        private final FlowStream in;
        private final double heat, flow;
        private final int ways;
        private final FlowThermo thermo;
        private final MixtureViscosity.Workspace scratch = new MixtureViscosity.Workspace();
        private double pressure;

        March(FlowStream in, double heat, int ways, double flow, FlowThermo thermo) {
            this.in = in;
            this.heat = heat;
            this.ways = ways;
            this.flow = flow;
            this.thermo = thermo;
            pressure = in.pressure();
        }

        /** What the coil {@code g} loses carrying the heat from share {@code from} to {@code to} of the whole, Pa. */
        double through(PipeResistance.Geometry g, double from, double to, int slices) {
            var slice = new PipeResistance.Geometry(g.length() / slices, g.diameter(), g.roughness(), g.minorLoss() / slices, g.channels());
            double lost = 0;
            for (int i = 0; i < slices; i++) {
                thermo.checkpoint().run();
                var state = thermo.heated(in, (from + (i + .5) / slices * (to - from)) * heat, pressure);
                double drop = LineSizing.Carried.of(state, flow, thermo, scratch).drop(slice, ways) * 1e5;
                lost += drop;
                pressure = Math.max(LEAST_PRESSURE, pressure - drop);
            }
            return lost;
        }
    }
}
