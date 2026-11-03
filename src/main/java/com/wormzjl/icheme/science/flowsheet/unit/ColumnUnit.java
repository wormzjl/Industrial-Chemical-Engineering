package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Passage;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The column as one block (batch 2026-11-02-process-simulator, plan sections 3.3 and 3.11): the calculator's own solve, with its
 * ports a pure function of its door. The block's solve reads its inlets and writes its products, so a loop tears there, and each
 * pass after the first starts from the last state. A heat side is a calculation of its own. A pumparound or the reboiler has its
 * duty on the door, so what passes through its cold side depends on nothing the column solves, and heating the feed of the same
 * column is no loop. The condenser's duty is an answer, and reaches its side by a link. A free side leaves the heat to a utility.
 * Each pair is checked once the sheet stands, for heat that cannot flow that way. An air-then-water condenser ({@code
 * COOLER_MODEL_PLAN.md} section 6, C2) has the exchanger's pair on its water trim: the water takes the trim's share, the overhead from
 * the split to the receiver as the world's solve reads it, and the air the rest. The block reads both shares. A heat side whose inlet
 * is a sheet feed of water alone sets that feed's flow ({@code COOLER_MODEL_PLAN.md} section 8), as a plant's controller opens its
 * valve: cooling water to carry the duty to 5 K under the process cold end, 10 K above its inlet at most; steam to leave as saturated
 * condensate. A calculation of its own writes the feed, so the side reads it as any inlet.
 *
 * <p>V3 has no feed pressure, so the stream is flashed at its own enthalpy to the feed tray's solved pressure, which gives V3's
 * feed temperature. V3's feed is hydrocarbons: water joins as steam if it is vapour there. The products come back through the world's
 * flash at V3's temperature and pressure; what the two thermo models then differ by is read out as the block's energy gap.
 *
 * <p>Where the top is under ambient pressure, an ejector set stands on the vapour line (main plan W10). It is not modelled apart: its
 * load is what the condenser cannot condense, V3's vapour product at the condenser's temperature and pressure (W10b), and its Motive
 * steam port takes the ejector tile's rule ({@link EjectorUnit#motive}). The steam condenses with the overhead and
 * leaves as free water at the water product's temperature, and the heat it gives up joins the condenser's duty. V3 solves the column
 * at its own pressures, so the products stay at them: the ejector moves no product's pressure.
 */
public final class ColumnUnit implements UnitModel {
    /** The column's ports; a draw's, a steam row's and a pumparound's carry their row's id after their prefix. */
    public static final String FEED = "feed", OVERHEAD = "overhead", DISTILLATE = "distillate", WATER = "water", BOTTOMS = "bottoms", DRAW = "draw/", STEAM = "steam/", PUMPAROUND = "pa/";
    public static final String CONDENSER_IN = "condenser_in", CONDENSER_OUT = "condenser_out", REBOILER_IN = "reboiler_in", REBOILER_OUT = "reboiler_out";
    static final String MOTIVE = "motive";
    /** The passages of the condenser's and the kettle's sheet sides, by the door's drops. */
    public static final String CONDENSER_DROP = "condenser_drop", REBOILER_DROP = "reboiler_drop";
    /** Liquid water up to this share of a stream's moles is a trace, not a phase. */
    static final double TRACE = 1e-9;
    /** The calculator's learned start, on the budget the game ships with. */
    private static final V3InitializationOptions START = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);

    public static String drawPort(ColumnDoor.Draw draw) {
        return DRAW + draw.id();
    }

    /**
     * Adds {@code flow} mol/s at the share {@code fraction} of {@code component} onto {@code amounts}, on the sheet's {@code axis}: the
     * column's products and its sizing's tray streams. No share adds nothing; a share of a component the axis lacks fails the
     * column, for dropping it would lose its moles.
     */
    public static void add(double[] amounts, List<String> axis, String component, double fraction, double flow) {
        if (fraction == 0) return;
        int at = axis.indexOf(component);
        if (at < 0) throw new FlowsheetFailure("column.component");
        amounts[at] += fraction * flow;
    }

    /** Whether what leaves by {@code port} leaves a tray or the sump, as a liquid at its boiling point with only the column's own head behind it. */
    public static boolean leavesTray(String port) {
        return port.equals(BOTTOMS) || port.startsWith(DRAW);
    }

    public static String steamPort(ColumnDoor.Steam steam) {
        return STEAM + steam.id();
    }

    public static String pumparoundIn(ColumnDoor.Pumparound pumparound) {
        return PUMPAROUND + pumparound.id() + "/in";
    }

    public static String pumparoundOut(ColumnDoor.Pumparound pumparound) {
        return PUMPAROUND + pumparound.id() + "/out";
    }

    @Override public List<Port> ports(Params params) {
        var door = ColumnDoor.of(params);
        var ports = new ArrayList<>(List.of(Port.in(FEED), Port.out(OVERHEAD), Port.out(DISTILLATE), Port.out(WATER), Port.out(BOTTOMS)));
        if (door.condenser().waterCooled()) ports.addAll(List.of(Port.utilityIn(CONDENSER_IN), Port.out(CONDENSER_OUT)));
        if (ColumnComponents.kettleRequired(door.reboilerDuty())) ports.addAll(List.of(Port.utilityIn(REBOILER_IN), Port.out(REBOILER_OUT)));
        for (var pumparound : door.pumparounds()) ports.addAll(List.of(Port.utilityIn(pumparoundIn(pumparound)), Port.out(pumparoundOut(pumparound))));
        for (var steam : door.steam()) ports.add(Port.utilityIn(steamPort(steam)));
        for (var draw : door.draws()) ports.add(Port.out(drawPort(draw)));
        if (ColumnComponents.ejectorRequired(door.topPressure())) ports.add(Port.optionalIn(MOTIVE));
        return ports;
    }

    /**
     * The heat members' utility sides, either way (owner 2026-11-04, "Column components like heat exchanger utility side only"): the
     * condenser's water, or its trim's; the kettle's heating side; each pumparound cooler's. The process ports and stripping steam stay fixed.
     */
    @Override public List<Passage> passages(Params params) {
        var door = ColumnDoor.of(params);
        var passages = new ArrayList<Passage>();
        if (door.condenser().waterCooled()) passages.add(Passage.named("condenser"));
        if (ColumnComponents.kettleRequired(door.reboilerDuty())) passages.add(Passage.named("reboiler"));
        for (var pumparound : door.pumparounds()) passages.add(new Passage(PUMPAROUND + pumparound.id() + "/a", PUMPAROUND + pumparound.id() + "/b", pumparoundIn(pumparound), pumparoundOut(pumparound)));
        return passages;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var door = ColumnDoor.of(u.params());
        var solve = new ColumnBlockSolve(u, door);
        var calculations = new ArrayList<Calculation>();
        calculations.add(solve.calculation());
        if (u.wire(CONDENSER_IN) != null) {
            String heat = solve.condenserHeat();
            if (u.utilities().feeds().containsKey(CONDENSER_IN))
                calculations.add(ColumnSides.utility(u, "condenser", CONDENSER_IN, CONDENSER_DROP, door.condenserDrop(), List.of(), List.of(heat), board -> board.link(heat), ColumnSides.cooling(u, CONDENSER_IN, board -> door.condenserTemperature())));
            calculations.add(ColumnSides.side(u, "condenser", CONDENSER_IN, CONDENSER_OUT, CONDENSER_DROP, door.condenserDrop(), heat, null));
            calculations.add(ColumnSides.check(u, "condenser check", u.out(CONDENSER_OUT), null, (board, report) -> {
                if (!board.stream(u.out(CONDENSER_OUT)).empty()) report.reading("margin", door.condenserTemperature() - board.stream(u.out(CONDENSER_OUT)).temperature());
                if (!board.stream(u.out(CONDENSER_OUT)).empty() && board.stream(u.out(CONDENSER_OUT)).temperature() >= door.condenserTemperature()) report.note(Note.warning(u.id(), "column.condenser_hot"));
            }));
        } else if (u.wire(CONDENSER_OUT) != null) throw new FlowsheetFailure("unit.free_inlet", CONDENSER_IN);
        if (u.wire(REBOILER_IN) != null) {
            if (u.utilities().feeds().containsKey(REBOILER_IN))
                calculations.add(ColumnSides.utility(u, "reboiler", REBOILER_IN, REBOILER_DROP, door.reboilerDrop(), List.of(), List.of(solve.bottomsTemperature()), board -> -door.reboilerDuty(), ColumnSides.steam(u, solve.bottomsTemperature())));
            calculations.add(ColumnSides.side(u, "reboiler", REBOILER_IN, REBOILER_OUT, REBOILER_DROP, door.reboilerDrop(), null, -door.reboilerDuty()));
            calculations.add(ColumnSides.check(u, "reboiler check", u.out(REBOILER_OUT), u.out(BOTTOMS), (board, report) -> {
                var steam = board.stream(u.out(REBOILER_OUT));
                var bottoms = board.stream(u.out(BOTTOMS));
                if (!steam.empty() && !bottoms.empty()) report.reading("margin", steam.temperature() - bottoms.temperature());
                if (!steam.empty() && !bottoms.empty() && steam.temperature() <= bottoms.temperature()) report.note(Note.warning(u.id(), "column.reboiler_cold"));
            }));
        } else if (u.wire(REBOILER_OUT) != null) throw new FlowsheetFailure("unit.free_inlet", REBOILER_IN);
        // A sheet feed of steam on a stripping steam port flows at its row's rate (U1b): the inlet valve holds it.
        for (var row : door.steam())
            if (u.utilities().feeds().containsKey(steamPort(row))) calculations.add(ColumnSides.utility(u, "steam " + row.id(), steamPort(row), steamPort(row), 0, List.of(), List.of(), board -> 0, ColumnSides.rate(u, row)));
        // A supply under its stage's pressure cannot pass its valve: the column says so once the sheet stands, at the pressure the
        // converged sheet has, so no pass of a loop decides it (U1b).
        for (var row : door.steam()) {
            String wire = u.wire(steamPort(row)), stage = solve.steamStage(row);
            if (wire != null) calculations.add(new Calculation(u.part("steam " + row.id() + " check"), u.id(), List.of(wire), List.of(), List.of(stage), List.of(), Calculation.Kind.CHECK, (board, report) -> {
                var supply = board.stream(wire);
                if (!supply.empty()) report.reading("margin", supply.pressure() - board.link(stage));
                if (!supply.empty() && supply.pressure() < board.link(stage)) report.note(Note.error(u.id(), "column.steam_pressure", row.id(), String.valueOf(supply.pressure()), String.valueOf(board.link(stage))));
            }));
        }
        for (var pumparound : door.pumparounds()) {
            String in = pumparoundIn(pumparound), out = pumparoundOut(pumparound);
            if (u.wire(in) == null) {
                if (u.wire(out) != null) throw new FlowsheetFailure("unit.free_inlet", in);
                continue;
            }
            String tray = solve.drawTemperature(pumparound);
            double fall = u.utilities().pumparoundFall();
            if (u.utilities().feeds().containsKey(in))
                calculations.add(ColumnSides.utility(u, "pa " + pumparound.id(), in, pumparoundDrop(pumparound), pumparound.drop(), List.of(), List.of(tray), board -> -pumparound.duty(), ColumnSides.cooling(u, in, board -> board.link(tray) - fall)));
            calculations.add(ColumnSides.side(u, "pa " + pumparound.id(), in, out, pumparoundDrop(pumparound), pumparound.drop(), null, -pumparound.duty()));
            calculations.add(new Calculation(u.part("pa " + pumparound.id() + " check"), u.id(), List.of(u.out(out)), List.of(), List.of(tray), List.of(), Calculation.Kind.CHECK, (board, report) -> {
                var cold = board.stream(u.out(out));
                if (!cold.empty()) report.reading("margin", board.link(tray) - cold.temperature());
                if (!cold.empty() && cold.temperature() >= board.link(tray)) report.note(Note.warning(u.id(), "column.pa_hot", pumparound.id()));
            }));
        }
        return calculations;
    }

    /** The passage of a pumparound cooler's sheet side, by its row: what its member's size gives once sized. */
    public static String pumparoundDrop(ColumnDoor.Pumparound pumparound) {
        return PUMPAROUND + pumparound.id() + "/drop";
    }

    /**
     * One solve of {@code input}: warm from {@code seed} when there is one, and from the learned start if that does not answer, as a
     * pass's change can leave the warm start behind. The block's solve and the sizing stage's trial diameters both take it.
     */
    public static V3ColumnOutcome solve(V3ColumnInput input, @Nullable V3NeuralSeed seed, MaterialCatalog catalog, Runnable checkpoint) {
        double screen = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        return MaterialRuntime.with(catalog, input.packageId(), () -> {
            var outcome = seed == null ? null : V3ColumnCalculator.calculateWithStrategy(input, checkpoint::run, V3SolveStrategy.AGGRESSIVE, seed, screen);
            if (!(outcome instanceof V3ColumnOutcome.Success)) outcome = V3ColumnCalculator.calculate(input, checkpoint::run, 0, 0, START, V3NeuralRegistry.bundled().bind(catalog, input), screen);
            return outcome;
        });
    }
}
