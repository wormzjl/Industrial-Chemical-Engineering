package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.Board;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Passage;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

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
    private static final String FEED = "feed", OVERHEAD = "overhead", DISTILLATE = "distillate", WATER = "water", BOTTOMS = "bottoms", DRAW = "draw/", FREE_WATER = "free_water";
    private static final String MOTIVE = "motive", CONDENSER_IN = "condenser_in", CONDENSER_OUT = "condenser_out", REBOILER_IN = "reboiler_in", REBOILER_OUT = "reboiler_out";
    /** The passages of the condenser's and the kettle's sheet sides, by the door's drops. */
    public static final String CONDENSER_DROP = "condenser_drop", REBOILER_DROP = "reboiler_drop";
    /**
     * The feed is flashed to its tray's pressure, which only a solve can say, so it starts at the stream's own and goes round
     * while that moves the feed by more than this (K), at most this often. A stream at its tray's pressure solves once.
     */
    private static final double FEED_TEMPERATURE = .01;
    private static final int FEED_ROUNDS = 3;
    /** Liquid water up to this share of a stream's moles is a trace, not a phase. */
    private static final double TRACE = 1e-9;
    /**
     * A regulated side's flow is found again while its outlet's pressure moves by more than this (Pa), at most this often; its
     * condensate stands this far under its saturation (K), a liquid the flash reads as one.
     */
    private static final double SAME_PRESSURE = 100, SUBCOOLED = 1e-3;
    private static final int SETTLING = 5;
    /** Another unit's stripping steam may differ from its row's rate by this share of the larger, the world's steam band, before the column says so (U1b). */
    private static final double STEAM_BAND = .02;
    /** The calculator's learned start, on the budget the game ships with. */
    private static final V3InitializationOptions START = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);

    private static String drawPort(ColumnDoor.Draw draw) {
        return DRAW + draw.id();
    }

    /** Whether what leaves by {@code port} leaves a tray or the sump, as a liquid at its boiling point with only the column's own head behind it. */
    public static boolean leavesTray(String port) {
        return port.equals(BOTTOMS) || port.startsWith(DRAW);
    }

    private static String steamPort(ColumnDoor.Steam steam) {
        return "steam/" + steam.id();
    }

    private static String pumparoundIn(ColumnDoor.Pumparound pumparound) {
        return "pa/" + pumparound.id() + "/in";
    }

    private static String pumparoundOut(ColumnDoor.Pumparound pumparound) {
        return "pa/" + pumparound.id() + "/out";
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
        for (var pumparound : door.pumparounds()) passages.add(new Passage("pa/" + pumparound.id() + "/a", "pa/" + pumparound.id() + "/b", pumparoundIn(pumparound), pumparoundOut(pumparound)));
        return passages;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var door = ColumnDoor.of(u.params());
        var solve = new Solve(u, door);
        var calculations = new ArrayList<Calculation>();
        calculations.add(solve.calculation());
        if (u.wire(CONDENSER_IN) != null) {
            String heat = solve.condenserHeat();
            if (u.utilities().feeds().containsKey(CONDENSER_IN))
                calculations.add(utility(u, "condenser", CONDENSER_IN, CONDENSER_DROP, door.condenserDrop(), List.of(), List.of(heat), board -> board.link(heat), cooling(u, CONDENSER_IN, board -> door.condenserTemperature())));
            calculations.add(side(u, "condenser", CONDENSER_IN, CONDENSER_OUT, CONDENSER_DROP, door.condenserDrop(), heat, null));
            calculations.add(check(u, "condenser check", u.out(CONDENSER_OUT), null, (board, report) -> {
                if (!board.stream(u.out(CONDENSER_OUT)).empty()) report.reading("margin", door.condenserTemperature() - board.stream(u.out(CONDENSER_OUT)).temperature());
                if (!board.stream(u.out(CONDENSER_OUT)).empty() && board.stream(u.out(CONDENSER_OUT)).temperature() >= door.condenserTemperature()) report.note(Note.warning(u.id(), "column.condenser_hot"));
            }));
        } else if (u.wire(CONDENSER_OUT) != null) throw new FlowsheetFailure("unit.free_inlet", CONDENSER_IN);
        if (u.wire(REBOILER_IN) != null) {
            if (u.utilities().feeds().containsKey(REBOILER_IN))
                calculations.add(utility(u, "reboiler", REBOILER_IN, REBOILER_DROP, door.reboilerDrop(), List.of(), List.of(solve.bottomsTemperature()), board -> -door.reboilerDuty(), steam(u, solve.bottomsTemperature())));
            calculations.add(side(u, "reboiler", REBOILER_IN, REBOILER_OUT, REBOILER_DROP, door.reboilerDrop(), null, -door.reboilerDuty()));
            calculations.add(check(u, "reboiler check", u.out(REBOILER_OUT), u.out(BOTTOMS), (board, report) -> {
                var steam = board.stream(u.out(REBOILER_OUT));
                var bottoms = board.stream(u.out(BOTTOMS));
                if (!steam.empty() && !bottoms.empty()) report.reading("margin", steam.temperature() - bottoms.temperature());
                if (!steam.empty() && !bottoms.empty() && steam.temperature() <= bottoms.temperature()) report.note(Note.warning(u.id(), "column.reboiler_cold"));
            }));
        } else if (u.wire(REBOILER_OUT) != null) throw new FlowsheetFailure("unit.free_inlet", REBOILER_IN);
        // A sheet feed of steam on a stripping steam port flows at its row's rate (U1b): the inlet valve holds it.
        for (var row : door.steam())
            if (u.utilities().feeds().containsKey(steamPort(row))) calculations.add(utility(u, "steam " + row.id(), steamPort(row), steamPort(row), 0, List.of(), List.of(), board -> 0, rate(u, row)));
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
                calculations.add(utility(u, "pa " + pumparound.id(), in, pumparoundDrop(pumparound), pumparound.drop(), List.of(), List.of(tray), board -> -pumparound.duty(), cooling(u, in, board -> board.link(tray) - fall)));
            calculations.add(side(u, "pa " + pumparound.id(), in, out, pumparoundDrop(pumparound), pumparound.drop(), null, -pumparound.duty()));
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
        return "pa/" + pumparound.id() + "/drop";
    }

    /**
     * The utility side of a heat pair: {@code heat} W into it, from the door, or from {@code link} once the column has said. The
     * column's duty carries the column's sign, so this side takes its negative. It loses the door's {@code drop} (Pa), or once its
     * member has a size, the member's tubes' law at the run's flow (D19).
     */
    private static Calculation side(WiredUnit u, String name, String in, String out, String passage, double drop, @Nullable String link, @Nullable Double heat) {
        return new Calculation(u.part(name), u.id(), List.of(u.wire(in)), List.of(u.out(out)), link == null ? List.of() : List.of(link), List.of(), Calculation.Kind.STEP, (board, report) -> {
            var stream = board.stream(u.wire(in));
            if (stream.empty()) {
                u.write(board, report, out, stream);
                return;
            }
            double pressure = WiredUnit.after(stream, u.drop(passage, stream, drop, report));
            double watts = link == null ? heat : board.link(link);
            u.write(board, report, out, u.thermo().heated(stream, watts, pressure));
            report.reading("duty", watts);
        });
    }

    /**
     * What a regulated side asks of its feed ({@code COOLER_MODEL_PLAN.md} section 8): the flow, as a share of the typed one, that
     * carries {@code watts} into it with the side's outlet at {@code pressure}; NaN keeps the typed flow.
     */
    private interface Rule {
        double share(Board board, Report report, FlowStream typed, double watts, double pressure);
    }

    /**
     * The feed of a heat side that sets its flow ({@code COOLER_MODEL_PLAN.md} section 8): the sheet's feed of water alone on
     * {@code in}, which this calculation writes in place of a source, at its typed temperature, pressure and makeup and at the flow
     * {@code rule} asks for {@code watts}; the side then takes it as any inlet. The typed flow is the first guess, and stays where the
     * rule cannot set one. A sized passage's drop moves with the flow, so the flow is found again at each drop until the side's outlet
     * pressure settles.
     */
    private static Calculation utility(WiredUnit u, String name, String in, String passage, double drop, List<String> reads, List<String> links, ToDoubleFunction<Board> watts, Rule rule) {
        var feed = u.utilities().feeds().get(in);
        String wire = u.wire(in);
        return new Calculation(u.part(name + " utility"), u.id(), reads, List.of(wire), links, List.of(), Calculation.Kind.STEP, (board, report) -> {
            var thermo = u.thermo();
            var typed = com.wormzjl.icheme.science.flowsheet.FlowsheetCompiler.typed(feed, thermo);
            double heat = watts.applyAsDouble(board);
            var scratch = new Report();
            double pressure = WiredUnit.after(typed, u.drop(passage, typed, drop, scratch));
            double share = rule.share(board, scratch, typed, heat, pressure);
            for (int tries = 0; tries < SETTLING && share > 0; tries++) {
                double next = WiredUnit.after(typed, u.drop(passage, scaled(thermo, typed, share), drop, scratch));
                if (Math.abs(next - pressure) <= SAME_PRESSURE) break;
                pressure = next;
                share = rule.share(board, scratch, typed, heat, pressure);
            }
            share = rule.share(board, report, typed, heat, pressure);
            var stream = Double.isNaN(share) ? typed : scaled(thermo, typed, share);
            board.stream(wire, stream);
            report.enters(stream.mass(), stream.enthalpy());
            if (!Double.isNaN(share)) report.note(Note.info(wire, "flow.set_by", u.id()));
        });
    }

    /**
     * Cooling water's rule: the flow that takes the heat from its inlet to {@link HeatSides#coolingWaterOutlet}, 5 K under the
     * process cold end {@code coldEnd} (K) at most. Water that comes in there or above cannot hold it, and keeps its typed flow with a
     * warning; a feed that holds vapour is no cooling water, and keeps its own.
     */
    private static Rule cooling(WiredUnit u, String port, ToDoubleFunction<Board> coldEnd) {
        return (board, report, typed, watts, pressure) -> {
            var thermo = u.thermo();
            if (thermo.vapourShare(typed) > TRACE || watts < 0) return Double.NaN;
            if (watts == 0) return 0;
            double outlet = HeatSides.coolingWaterOutlet(typed.temperature(), coldEnd.applyAsDouble(board));
            if (!(outlet > typed.temperature())) {
                report.note(Note.warning(u.id(), "column.water_hot", port));
                return Double.NaN;
            }
            return watts / (thermo.atTemperature(outlet, pressure, typed.amountsView(), typed.solids()).enthalpy() - typed.enthalpy());
        };
    }

    /**
     * Steam's rule: the flow whose condensing gives the kettle's duty, the condensate leaving saturated at the side's outlet pressure.
     * Steam no hotter than the bottoms cannot heat them, and keeps its typed flow, which the reboiler's check reads as {@code
     * column.reboiler_cold}; steam hotter than them that condenses no hotter can give them its superheat alone, and keeps its typed
     * flow with {@code column.steam_low}. A feed of liquid water is no steam, and keeps its own.
     */
    private static Rule steam(WiredUnit u, String bottomsTemperature) {
        return (board, report, typed, watts, pressure) -> {
            var thermo = u.thermo();
            if (!(thermo.vapourShare(typed) > TRACE) || watts > 0) return Double.NaN;
            if (watts == 0) return 0;
            double saturation = EjectorUnit.saturation(thermo, pressure);
            double bottoms = board.link(bottomsTemperature);
            if (typed.temperature() <= bottoms) return Double.NaN;
            if (saturation <= bottoms) {
                report.note(Note.warning(u.id(), "column.steam_low"));
                return Double.NaN;
            }
            double given = typed.enthalpy() - thermo.atTemperature(saturation - SUBCOOLED, pressure, typed.amountsView(), typed.solids()).enthalpy();
            return given > 0 ? -watts / given : Double.NaN;
        };
    }

    /** Stripping steam's rule (U1b): its row's rate, which the inlet valve holds; a feed of liquid water is no steam, and keeps its own. */
    private static Rule rate(WiredUnit u, ColumnDoor.Steam row) {
        return (board, report, typed, watts, pressure) -> u.thermo().vapourShare(typed) > TRACE ? row.flow() / typed.total() : Double.NaN;
    }

    /** {@code typed} at {@code share} of its flow, in the same state; none at a share of 0. */
    private static FlowStream scaled(com.wormzjl.icheme.science.flowsheet.FlowThermo thermo, FlowStream typed, double share) {
        if (share == 0) return FlowStream.empty(typed.amountsView().length);
        double[] amounts = typed.amounts();
        for (int i = 0; i < amounts.length; i++) amounts[i] *= share;
        return thermo.atTemperature(typed.temperature(), typed.pressure(), amounts, typed.solids());
    }

    private static Calculation check(WiredUnit u, String name, String read, @Nullable String other, Calculation.Law law) {
        return new Calculation(u.part(name), u.id(), other == null ? List.of(read) : List.of(read, other), List.of(), List.of(), List.of(), Calculation.Kind.CHECK, law);
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

    /** The ejector's motive steam, the free water it joins, and the heat it gives the condenser (W). */
    private record Ejected(FlowStream steam, FlowStream water, double heat) {
    }

    /** The block's own solve. It keeps the last state, so each pass starts from it. */
    private static final class Solve implements Calculation.Law {
        private final WiredUnit u;
        private final ColumnDoor door;
        private final int components;
        private @Nullable V3NeuralSeed warm;
        private @Nullable V3ColumnDisplayResult display;
        /** The basis of the last input solved, on which its profile's fractions stand. */
        private @Nullable V3ComponentBasis basis;
        /** The feed tray's pressure the last solve left (Pa): tray hydraulics march it from the column's own traffic. */
        private double feedTray = Double.NaN;
        /** Each steam row's stage pressure the last solve left (Pa, by stage): its inlet valve throttles to it. */
        private final java.util.Map<Integer, Double> stages = new java.util.HashMap<>();

        Solve(WiredUnit u, ColumnDoor door) {
            this.u = u;
            this.door = door;
            components = u.thermo().axis().size();
        }

        String condenserHeat() {
            return u.part("condenser heat");
        }

        String drawTemperature(ColumnDoor.Pumparound pumparound) {
            return u.part("draw tray " + pumparound.id());
        }

        /** A wired steam row's stage pressure (Pa) the solve left, which its supply must reach (U1b); minus infinity with no solve. */
        String steamStage(ColumnDoor.Steam row) {
            return u.part("steam stage " + row.id());
        }

        /** The bottoms' temperature (K), which a kettle's steam must condense above; minus infinity with no bottoms. */
        String bottomsTemperature() {
            return u.part("bottoms temperature");
        }

        Calculation calculation() {
            var reads = new ArrayList<String>();
            reads.add(u.wire(FEED));
            for (var steam : door.steam()) if (u.wire(steamPort(steam)) != null) reads.add(u.wire(steamPort(steam)));
            if (u.wire(MOTIVE) != null) reads.add(u.wire(MOTIVE));
            var writes = new ArrayList<>(List.of(u.out(OVERHEAD), u.out(DISTILLATE), u.out(WATER), u.out(BOTTOMS)));
            for (var draw : door.draws()) writes.add(u.out(drawPort(draw)));
            var gives = new ArrayList<String>();
            if (u.wire(CONDENSER_IN) != null) gives.add(condenserHeat());
            for (var pumparound : door.pumparounds()) if (u.wire(pumparoundIn(pumparound)) != null) gives.add(drawTemperature(pumparound));
            if (u.utilities().feeds().containsKey(REBOILER_IN)) gives.add(bottomsTemperature());
            for (var row : door.steam()) if (u.wire(steamPort(row)) != null) gives.add(steamStage(row));
            return new Calculation(u.id(), u.id(), reads, writes, List.of(), gives, Calculation.Kind.BLOCK, this);
        }

        @Override public void solve(Board board, Report report) {
            var thermo = u.thermo();
            var feed = board.stream(u.wire(FEED));
            if (feed.empty()) {
                write(board, report, null, null, 0);
                return;
            }
            if (!feed.solids().empty()) throw new FlowsheetFailure("column.solids");
            double heatIn = 0;
            var supplies = new ArrayList<FlowStream>();
            for (var row : door.steam()) {
                var stream = steamOf(row, board, report);
                supplies.add(stream);
                heatIn += stream.enthalpy();
            }
            if (Double.isNaN(feedTray)) feedTray = feed.pressure();
            report.start(warm == null ? Report.Start.LEARNED : Report.Start.LAST);
            FlowStream flashed;
            V3ColumnResult result;
            List<FlowStream> valved;
            int round = 0;
            double moved;
            do {
                flashed = thermo.heated(feed, 0, feedTray);
                valved = valves(supplies);
                var steam = new ArrayList<V3SteamFeedSpec>();
                for (int i = 0; i < valved.size(); i++) steam.add(new V3SteamFeedSpec(door.steam().get(i).stage(), valved.get(i).total(), valved.get(i).temperature()));
                result = run(flashed, steam);
                warm = result.warmSeed().orElse(warm);
                var solved = result.inspection().orElseThrow().nodes();
                feedTray = solved.get(door.feedStage()).pressurePascal();
                for (var row : door.steam()) stages.put(row.stage(), solved.get(row.stage()).pressurePascal());
                moved = Math.abs(thermo.heated(feed, 0, feedTray).temperature() - flashed.temperature());
            } while (moved > FEED_TEMPERATURE && ++round < FEED_ROUNDS);
            if (feed.pressure() < feedTray) report.note(Note.warning(u.id(), "column.inlet_pressure", FEED));
            steamChecks(supplies, valved, report);
            var ejected = ejector(board, report, result);
            var ledger = result.dutyLedger().orElseThrow();
            double motiveHeat = ejected == null ? 0 : ejected.heat();
            double whole = ledger.condenserWatts() - motiveHeat, water = water(result, whole);
            write(board, report, result, ejected, water);
            report.display(display);
            report.reading("condenser", -whole);
            report.reading("condenser_air", water - whole);
            report.reading("condenser_water", -water);
            // A free condenser's coolant, air or tower water, comes in at 25 °C: a receiver there or below it cannot be held (C2).
            if (u.wire(CONDENSER_IN) == null && door.condenserTemperature() <= CoolerUnit.COOLANT) report.note(Note.warning(u.id(), "column.condenser_coolant"));
            report.reading("reboiler", ledger.reboilerWatts());
            report.reading("feed_temperature", flashed.temperature());
            heatIn += flashed.enthalpy();
            double heatOut = 0;
            for (var product : result.streams()) heatOut += product(product.streamId(), result).enthalpy();
            if (ejected != null) {
                heatIn += ejected.steam().enthalpy();
                heatOut += ejected.water().enthalpy() - product(FREE_WATER, result).enthalpy();
            }
            report.reading("energy_gap", heatIn + ledger.condenserWatts() - motiveHeat + ledger.reboilerWatts() + ledger.stageHeatTotalWatts() - heatOut);
        }

        /**
         * The ejector set, null where the top needs none or the condenser vents nothing: its load is the vent, V3's vapour product at the
         * condenser's temperature and pressure. Its steam joins the free water at the water product's temperature and pressure, or the
         * condenser's where V3 leaves none, and what it gives up there is the heat the condenser takes from it, so the block's energy
         * closes as before.
         */
        private @Nullable Ejected ejector(Board board, Report report, V3ColumnResult result) {
            if (!ColumnComponents.ejectorRequired(door.topPressure())) return null;
            var thermo = u.thermo();
            var inspection = result.inspection().orElseThrow();
            var condenser = inspection.nodes().get(0);
            var vent = product("overhead_vapor", result);
            if (vent.empty()) return null;
            var load = thermo.atTemperature(condenser.temperatureKelvin(), condenser.pressurePascal(), vent.amounts(), SolidInventory.EMPTY);
            String wire = u.wire(MOTIVE);
            var steam = EjectorUnit.motive(u.id() + "/ejector", "ejector_", thermo, load, wire == null ? null : board.stream(wire), door.ejector().motive(), door.ejector().discharge(), report);
            var free = product(FREE_WATER, result);
            double[] amounts = free.amounts();
            for (int i = 0; i < components; i++) amounts[i] += steam.amountsView()[i];
            var water = thermo.atTemperature(free.empty() ? condenser.temperatureKelvin() : free.temperature(), free.empty() ? condenser.pressurePascal() : free.pressure(), amounts, steam.solids());
            double heat = steam.enthalpy() + free.enthalpy() - water.enthalpy();
            report.reading("ejector_heat", heat);
            return new Ejected(steam, water, heat);
        }

        /**
         * The share of the condenser's {@code whole} duty (W, column sign) its water takes: all of it on an exchanger, none on an air
         * cooler, and on air then water the trim's, the overhead from the split to the receiver, as the world's solve reads it
         * ({@link ColumnSolve#trim}).
         */
        private double water(V3ColumnResult result, double whole) {
            var thermo = u.thermo();
            return switch (door.condenser()) {
                case EXCHANGER -> whole;
                case AIR_COOLER -> 0;
                case AIR_THEN_WATER -> ColumnSolve.trim(thermo.model(), result.inspection().orElseThrow(), java.util.Objects.requireNonNull(basis), thermo.axis(), door.condenserTemperature(), whole, thermo.checkpoint());
            };
        }

        /** V3 on the stream as flashed: its hydrocarbons are the feed, its water a steam feed if it is vapour there. */
        private V3ColumnResult run(FlowStream flashed, List<V3SteamFeedSpec> rows) {
            if (flashed.state().waterLiquid() > TRACE * flashed.total()) throw new FlowsheetFailure("column.liquid_water");
            double[] amounts = flashed.amounts();
            double water = amounts[components - 1];
            amounts[components - 1] = 0;
            var steam = new ArrayList<>(rows);
            if (water > 0) join(steam, new V3SteamFeedSpec(door.feedStage(), water, flashed.temperature()));
            return run(input(amounts, flashed.temperature(), steam));
        }

        /** A steam row's stream: what its wire brings, or the door's entries, which enter the sheet as a utility. */
        private FlowStream steamOf(ColumnDoor.Steam row, Board board, Report report) {
            var thermo = u.thermo();
            double pressure = door.pressure(row.stage());
            String wire = u.wire(steamPort(row));
            var wired = wire == null ? null : board.stream(wire);
            if (wired == null || wired.empty()) {
                double[] amounts = new double[components];
                amounts[components - 1] = row.flow();
                var entered = thermo.atTemperature(row.temperature(), pressure, amounts, SolidInventory.EMPTY);
                if (wired == null) report.enters(entered.mass(), entered.enthalpy());
                return entered;
            }
            if (wired.amountsView()[components - 1] != wired.total()) throw new FlowsheetFailure("column.steam_water", row.id());
            return wired;
        }

        /**
         * Each steam row's supply past its inlet valve (U1b, {@code COOLER_MODEL_PLAN.md} section 8): a wired one throttled at constant
         * enthalpy to its stage's pressure, the solved one once a solve has said, else the door's, so V3 takes it at the valve's outlet
         * temperature and the supply's superheat goes into the column. A supply under that pressure passes as it comes, and the
         * steam check reads it red once the sheet stands. The door's own entries stand at the door's pressure already. Steam wet past
         * the valve is refused.
         */
        private List<FlowStream> valves(List<FlowStream> supplies) {
            var thermo = u.thermo();
            var past = new ArrayList<FlowStream>();
            for (int i = 0; i < supplies.size(); i++) {
                var row = door.steam().get(i);
                var supply = supplies.get(i);
                if (u.wire(steamPort(row)) == null || supply.empty()) {
                    past.add(supply);
                    continue;
                }
                double stage = stages.getOrDefault(row.stage(), door.pressure(row.stage()));
                var valved = thermo.heated(supply, 0, Math.min(supply.pressure(), stage));
                if (valved.state().waterLiquid() > TRACE * valved.total()) throw new FlowsheetFailure("column.steam_wet", row.id());
                past.add(valved);
            }
            return past;
        }

        /**
         * What the inlet valves leave to say (U1b): steam that comes to its valve cooler than its row's temperature enters with a warning,
         * judged at the supply, for the valve's own cooling is the slight change the solver takes up (the owner); steam from another unit
         * more than the world's band off its row's rate enters as it comes, with a warning. What V3 took past the valve is read out. A
         * supply under its stage's pressure is the steam check's, once the sheet stands.
         */
        private void steamChecks(List<FlowStream> supplies, List<FlowStream> valved, Report report) {
            double kilograms = EjectorUnit.waterMass(u.thermo());
            for (int i = 0; i < supplies.size(); i++) {
                var row = door.steam().get(i);
                var supply = supplies.get(i);
                String port = steamPort(row);
                if (u.wire(port) == null || supply.empty()) continue;
                report.reading("steam_temperature/" + row.id(), valved.get(i).temperature());
                report.reading("steam_pressure/" + row.id(), valved.get(i).pressure());
                if (supply.temperature() < row.temperature()) report.note(Note.warning(u.id(), "column.steam_cool", row.id(), String.valueOf(supply.temperature()), String.valueOf(row.temperature())));
                if (!u.utilities().feeds().containsKey(port) && Math.abs(supply.total() - row.flow()) > STEAM_BAND * Math.max(supply.total(), row.flow()))
                    report.note(Note.warning(u.id(), "column.steam_rate", row.id(), String.valueOf(supply.total() * kilograms), String.valueOf(row.flow() * kilograms)));
            }
        }

        /** V3 takes one steam feed a stage, so two on one stage join at their mixed temperature. */
        private static void join(List<V3SteamFeedSpec> steam, V3SteamFeedSpec added) {
            for (int i = 0; i < steam.size(); i++) {
                var there = steam.get(i);
                if (there.stageNumber() != added.stageNumber()) continue;
                double flow = there.molarFlowMolPerSecond() + added.molarFlowMolPerSecond();
                steam.set(i, new V3SteamFeedSpec(there.stageNumber(), flow, (there.molarFlowMolPerSecond() * there.temperatureKelvin() + added.molarFlowMolPerSecond() * added.temperatureKelvin()) / flow));
                return;
            }
            steam.add(added);
        }

        /** The door's package while there is one and it holds the feed, else the sheet's. The learned start declines nitrogen, so the narrower one stands while it can. */
        private V3ColumnInput input(double[] amounts, double temperature, List<V3SteamFeedSpec> steam) {
            var thermo = u.thermo();
            var pack = thermo.catalog().packages().get(door.packageId());
            if (pack == null || !holds(pack.components(), amounts)) pack = thermo.catalog().requirePackage(thermo.packageId());
            if (!holds(pack.components(), amounts)) throw new FlowsheetFailure("column.component");
            var axis = thermo.axis();
            double[] flows = new double[pack.components().size()];
            for (int i = 0; i < flows.length; i++) {
                int at = axis.indexOf(pack.components().get(i));
                if (at >= 0) flows[i] = amounts[at];
            }
            try {
                return door.input(pack, flows, temperature, steam);
            } catch (IllegalArgumentException refused) {
                throw new FlowsheetFailure("column.failed", String.valueOf(refused.getMessage()));
            }
        }

        private boolean holds(List<String> basis, double[] amounts) {
            var axis = u.thermo().axis();
            for (int i = 0; i < amounts.length; i++) if (amounts[i] > 0 && !basis.contains(axis.get(i))) return false;
            return true;
        }

        private V3ColumnResult run(V3ColumnInput input) {
            basis = input.componentBasis();
            var outcome = ColumnUnit.solve(input, warm, u.thermo().catalog(), u.thermo().checkpoint());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                display = V3ColumnDisplayResult.fromAccepted(success);
                return success.result();
            }
            throw new FlowsheetFailure("column.failed", ((V3ColumnOutcome.Failure) outcome).summary());
        }

        /**
         * The outlets and links of {@code result}, or of no flow. Free sides book their heat here, because only a solved column has any.
         * The condenser's water side takes {@code water} (W, column sign) of its duty, and the air the rest.
         */
        private void write(Board board, Report report, @Nullable V3ColumnResult result, @Nullable Ejected ejected, double water) {
            u.write(board, report, OVERHEAD, product("overhead_vapor", result));
            u.write(board, report, DISTILLATE, product("distillate_liquid", result));
            u.write(board, report, WATER, ejected != null ? ejected.water() : product(FREE_WATER, result));
            var bottoms = product("bottoms_liquid", result);
            u.write(board, report, BOTTOMS, bottoms);
            if (u.utilities().feeds().containsKey(REBOILER_IN)) board.link(bottomsTemperature(), bottoms.empty() ? Double.NEGATIVE_INFINITY : bottoms.temperature());
            for (var row : door.steam()) if (u.wire(steamPort(row)) != null) board.link(steamStage(row), result == null ? Double.NEGATIVE_INFINITY : stages.getOrDefault(row.stage(), Double.NEGATIVE_INFINITY));
            for (var draw : door.draws()) u.write(board, report, drawPort(draw), product(String.format(Locale.ROOT, "side_liquid_tray_%02d", draw.tray()), result));
            var ledger = result == null ? null : result.dutyLedger().orElseThrow();
            double condenser = ledger == null ? 0 : ledger.condenserWatts() - (ejected == null ? 0 : ejected.heat());
            if (u.wire(CONDENSER_IN) != null) {
                board.link(condenserHeat(), -water);
                if (ledger != null && condenser != water) report.duty(condenser - water);
            } else if (ledger != null) report.duty(condenser);
            if (ledger != null && u.wire(REBOILER_IN) == null) report.duty(ledger.reboilerWatts());
            for (var pumparound : door.pumparounds()) {
                if (u.wire(pumparoundIn(pumparound)) != null) board.link(drawTemperature(pumparound), result == null ? Double.POSITIVE_INFINITY : result.inspection().orElseThrow().nodes().get(pumparound.drawTray()).temperatureKelvin());
                else if (ledger != null) report.duty(pumparound.duty());
            }
        }

        /** A published stream through the world's flash, on the sheet's axis; a stream the column does not make carries no flow. */
        private FlowStream product(String id, @Nullable V3ColumnResult result) {
            if (result != null) for (var stream : result.streams()) {
                if (!stream.streamId().equals(id)) continue;
                var axis = u.thermo().axis();
                double[] amounts = new double[components];
                for (var fraction : stream.moleFractions()) {
                    if (fraction.moleFraction() == 0) continue;
                    int at = axis.indexOf(fraction.componentId());
                    if (at < 0) throw new FlowsheetFailure("column.component");
                    amounts[at] += fraction.moleFraction() * stream.molarFlowMolPerSecond();
                }
                return u.thermo().atTemperature(stream.temperatureKelvin(), stream.pressurePascal(), amounts, SolidInventory.EMPTY);
            }
            return FlowStream.empty(components);
        }
    }
}
