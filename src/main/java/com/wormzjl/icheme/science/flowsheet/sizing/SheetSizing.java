package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FiredHeaterUnit;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A whole sheet's lines and units, sized and rated (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1, 3.5 and
 * 3.6). The order is the physics': a unit's nozzles bound its lines, so units that read no line come first, a column with its
 * members; a pump's NPSH and a valve's bore read their inlet lines, so those units come last. It runs on the run's thread, inside
 * {@code MaterialRuntime.with}: its one scratch is local, and {@code thermo}'s checkpoint is asked between items and inside every
 * trial column, so Stop ends it there. It reads steady streams and writes nothing: on a run that is not steady every item is red on
 * {@link Limit#RUN_STEADY}, with no size.
 *
 * <p>A column is a block: one row, its members under it, each by its id under the block's, {@code T-101/condenser}. Where Auto
 * gives a column another diameter than its door's, the sheet runs once more on a private copy at that diameter, warm from the run,
 * and every item is rated again on what that run gives, at the sizes already chosen. Nothing is sized twice.
 */
public final class SheetSizing {
    private static final String OIL = "oil", WATER = "water", GAS = "gas", COLUMN = "column";
    private static final String FEED = "feed", BOTTOMS = "bottoms", DISTILLATE = "distillate", OVERHEAD = "overhead", DRAW = "draw/", STEAM = "steam/";

    private SheetSizing() {
    }

    /**
     * What the player keeps on a line.
     *
     * @param service null for what the stream says
     * @param length  m, null for the sheet's
     * @param size    null for Auto
     */
    public record LineEntry(@Nullable Service service, @Nullable Double length, LineSizing.@Nullable Size size) {
        public static final LineEntry AUTO = new LineEntry(null, null, null);
    }

    /**
     * What the player or a later stage gives a unit.
     *
     * @param size   kept and rated; null for Auto's
     * @param type   null for the proposal
     * @param height a pump's static head over grade, m; by default its source's nozzle's: a column's tray, a drum's half level, grade
     * @param hold   a drum's entry, min, or a tank's, h; null for the row's
     * @param kept   a column keeps its door's diameter, rated there with no trial
     */
    public record UnitEntry(@Nullable UnitSize size, @Nullable UnitType type, @Nullable Double height, @Nullable Double hold, boolean kept) {
        public static final UnitEntry AUTO = new UnitEntry(null, null, null, null, false);
    }

    /** What a sizing reads beyond the run. An item with no entry is on Auto; a member's key is its block's id, a slash and its own. */
    public record Entries(DesignBasis basis, Map<String, LineEntry> lines, Map<String, UnitEntry> units) {
        public static final Entries DEFAULT = new Entries(DesignBasis.DEFAULT, Map.of(), Map.of());

        public Entries {
            lines = Map.copyOf(lines);
            units = Map.copyOf(units);
        }

        /**
         * What {@code sheet}'s facts give a sizing. For Size all ({@code all} false), a Fixed item keeps its size and a Fixed column
         * its door's diameter, and an Auto item gives only its type and entries, so Auto proposes its size. For a rating ({@code all}
         * true), every item keeps the size it holds and every column its door's diameter, so nothing is proposed that a solve
         * must find.
         */
        public static Entries of(Flowsheet sheet, boolean all) {
            var facts = sheet.sizing();
            var units = new HashMap<String, UnitEntry>();
            facts.units().forEach((id, unit) -> {
                boolean fixed = unit.mode() == SizingFacts.Mode.FIXED;
                units.put(id, new UnitEntry(all || fixed ? unit.size() : null, unit.type(), null, unit.hold(), fixed));
            });
            if (all) for (var unit : sheet.units()) if (unit.type().equals(COLUMN)) units.put(unit.id(), new UnitEntry(null, facts.unit(unit.id()).type(), null, null, true));
            var lines = new HashMap<String, LineEntry>();
            facts.lines().forEach((id, line) -> lines.put(id, new LineEntry(line.service(), line.length(), all || line.mode() == SizingFacts.Mode.FIXED ? line.size() : null)));
            return new Entries(facts.basis(), lines, units);
        }

        private LineEntry line(String stream) {
            return lines.getOrDefault(stream, LineEntry.AUTO);
        }

        private UnitEntry unit(String id) {
            return units.getOrDefault(id, UnitEntry.AUTO);
        }

    }

    /** @param size null with no flow */
    public record SizedLine(LineSizing.Line line, LineSizing.@Nullable Size size, Rating rating) {
    }

    /** @param size null with nothing to size */
    public record SizedUnit(@Nullable UnitSize size, Rating rating) {
        private static final SizedUnit NONE = new SizedUnit(null, Rating.NONE);

        private static <S extends UnitSize> SizedUnit of(Selection.Choice<S> choice) {
            return new SizedUnit(choice.size(), choice.rating());
        }
    }

    /**
     * A sheet, sized: state, rebuilt whenever it is asked for, never saved.
     *
     * @param lines by stream, in sheet order, then each block's member lines
     * @param units by unit, in sheet order, each block's members under it
     * @param paths the pressure paths, by the streams they start on, in sheet order
     */
    public record Sized(Map<String, SizedLine> lines, Map<String, SizedUnit> units, List<PressurePath> paths) {
        public Sized {
            paths = List.copyOf(paths);
        }
    }

    /**
     * What Size all leaves.
     *
     * @param sheet the sheet with every Auto item at its proposal: one edit, which Undo takes back whole
     * @param run   the run the ratings read: the sheet's run again where a size moves it, else the one it was given
     * @param sized every item rated at its size on that run
     */
    public record SizeAll(Flowsheet sheet, FlowsheetRun run, Sized sized) {
    }

    /** A member's key: its block's id, a slash and its own. */
    public static String member(String block, String member) {
        return block + "/" + member;
    }

    /**
     * One sizing of {@code run}'s streams on {@code sheet} at {@code entries}, read on {@code thermo}'s own package: what Auto
     * proposes there, and every item rated. A column on Auto tries its diameters.
     */
    public static Sized pass(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, Entries entries) {
        return new Sizing(sheet, run, thermo, entries).all();
    }

    /**
     * Rates every item of {@code sheet} at the size its facts hold, on a run that has just ended (section 3.1 item 5): no trial
     * column, as each column keeps its door's diameter and reads the run's own trays, and no proposal is applied. An item with no
     * size yet shows what Auto would propose. Cheap enough to rebuild the lamps whenever a reader opens the tab.
     */
    public static Sized rate(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo) {
        return pass(sheet, run, thermo, Entries.of(sheet, true));
    }

    /**
     * Size all (section 3.1): proposes every Auto item's size, applies the proposals to a copy of {@code sheet}, and where that
     * moves the run (a column's diameter, a sized unit's drops) runs the copy once, warm from {@code run}, and rates every item on
     * what it gives. Nothing is sized twice. A run that is not steady sizes nothing: the sheet stays, every item red.
     */
    public static SizeAll sizeAll(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo) {
        if (run.status() != FlowsheetRun.Status.STEADY) return new SizeAll(sheet, run, rate(sheet, run, thermo));
        var proposal = pass(sheet, run, thermo, Entries.of(sheet, false));
        var sized = applied(sheet, proposal);
        if (!moves(sheet, sized)) return new SizeAll(sized, run, proposal);
        var rerun = FlowsheetEngine.run(sized, thermo.catalog(), thermo.checkpoint(), run);
        if (rerun.status() == FlowsheetRun.Status.STOPPED) throw new CancellationException();
        return new SizeAll(sized, rerun, rate(sized, rerun, thermo));
    }

    /**
     * {@code sheet} with every Auto item at {@code proposal}'s size: a column's door at its diameter and at its Auto members' counts
     * (S3b), every other size a fact; a Fixed item stays as the player keeps it. Facts of an item the sheet no longer holds go. A
     * copy: {@code sheet} is unchanged.
     */
    public static Flowsheet applied(Flowsheet sheet, Sized proposal) {
        var facts = sheet.sizing();
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) {
            var json = unit.type().equals(COLUMN) ? door(unit, facts, proposal) : null;
            units.add(json == null ? unit : new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new Params(json), unit.unknown().deepCopy()));
        }
        for (var entry : proposal.units().entrySet()) {
            var held = facts.unit(entry.getKey());
            var size = entry.getValue().size();
            if (held.mode() == SizingFacts.Mode.AUTO && !(size instanceof ColumnSizing.Size) && !(size instanceof ValveSizing.Size)) facts = facts.with(entry.getKey(), held.with(size));
        }
        for (var entry : proposal.lines().entrySet()) {
            var held = facts.line(entry.getKey());
            if (held.mode() == SizingFacts.Mode.AUTO) facts = facts.with(entry.getKey(), held.with(entry.getValue().size()));
        }
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), pruned(sheet, facts), sheet.unknown());
    }

    /**
     * A column's door as {@code proposal} leaves it, null where nothing moves: the column's diameter where the column is on Auto, and
     * each member's count where that member is on Auto: the condenser's, the kettle's and each pumparound cooler's.
     */
    private static com.google.gson.@Nullable JsonObject door(Flowsheet.Unit unit, SizingFacts facts, Sized proposal) {
        var door = ColumnDoor.of(unit.params());
        var json = unit.params().json().deepCopy();
        boolean moved = false;
        if (facts.unit(unit.id()).mode() == SizingFacts.Mode.AUTO && proposal.units().get(unit.id()) instanceof SizedUnit sized && sized.size() instanceof ColumnSizing.Size size
                && size.diameter() != door.diameter()) {
            json.addProperty("diameter", size.diameter());
            moved = true;
        }
        var counts = door.units();
        Integer condenser = proposedCount(unit.id(), "condenser", facts, proposal), kettle = proposedCount(unit.id(), "reboiler", facts, proposal);
        if (condenser != null && condenser != counts.condenser()) {
            json.addProperty(ColumnDoor.CONDENSER_UNITS, condenser);
            moved = true;
        }
        if (kettle != null && kettle != counts.kettle()) {
            json.addProperty(ColumnDoor.KETTLE_UNITS, kettle);
            moved = true;
        }
        for (int i = 0; i < door.pumparounds().size(); i++) {
            Integer cooler = proposedCount(unit.id(), "pa " + door.pumparounds().get(i).id() + " cooler", facts, proposal);
            if (cooler != null && cooler != counts.cooler(i) && json.get("pumparounds") instanceof com.google.gson.JsonArray rows && rows.get(i) instanceof com.google.gson.JsonObject row) {
                row.addProperty(ColumnDoor.UNITS, cooler);
                moved = true;
            }
        }
        return moved ? json : null;
    }

    /** How many units Auto proposes for {@code block}'s member {@code member}: null where the member is Fixed or has no size. */
    private static @Nullable Integer proposedCount(String block, String member, SizingFacts facts, Sized proposal) {
        String id = member(block, member);
        if (facts.unit(id).mode() != SizingFacts.Mode.AUTO || !(proposal.units().get(id) instanceof SizedUnit sized) || sized.size() == null) return null;
        return sized.size().count();
    }

    /** {@code facts} without the rows of items {@code sheet} no longer holds: a member goes with its block, a line with its stream. */
    public static SizingFacts pruned(Flowsheet sheet, SizingFacts facts) {
        var ids = new java.util.HashSet<String>();
        for (var unit : sheet.units()) ids.add(unit.id());
        for (var stream : sheet.streams()) ids.add(stream.id());
        var kept = facts;
        for (var id : facts.units().keySet()) if (!ids.contains(root(id))) kept = kept.with(id, SizingFacts.UnitFacts.AUTO);
        for (var id : facts.lines().keySet()) if (!ids.contains(root(id))) kept = kept.with(id, SizingFacts.LineFacts.AUTO);
        return kept;
    }

    /** The item an id belongs to: a member's block. */
    private static String root(String id) {
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(0, slash);
    }

    /** Whether {@code after}'s facts move its run from {@code before}'s: a column's diameter, or a size a passage's law reads. */
    private static boolean moves(Flowsheet before, Flowsheet after) {
        for (int i = 0; i < after.units().size(); i++) {
            var was = before.units().get(i);
            var now = after.units().get(i);
            if (now.type().equals(COLUMN) && ColumnDoor.of(now.params()).diameter() != ColumnDoor.of(was.params()).diameter()) return true;
            // A member's count moves the run through its passage's law, which SizedPassages reads with the door's counts.
            if (!SizedPassages.reads(after.sizing(), now).equals(SizedPassages.reads(before.sizing(), was))) return true;
        }
        return false;
    }

    /**
     * One column as its sizing left it: what its members, its lines' nozzles and its pumps' heights read.
     *
     * @param condenser the heat its condenser takes out at the run's flow, W
     * @param reboiler  the heat its reboiler puts in, W
     */
    private record Block(ColumnDoor door, ColumnSizing.Load load, ColumnSizing.@Nullable Size size, V3ColumnInspection inspection, double condenser, double reboiler) {
        /** The largest bore on the {@code index}th nozzle of {@code role} (from 1), as the column stands sized. */
        double nozzle(EquipmentShape.Role role, int index) {
            var shape = size == null ? null : UnitSize.built(size);
            if (shape == null) return PipeSizes.MAX_BORE;
            var nozzles = shape.nozzles();
            for (int k = 0, seen = 0; k < nozzles.size(); k++) if (nozzles.get(k).role() == role && ++seen == index) return NozzleBore.largest(shape, k);
            return PipeSizes.MAX_BORE;
        }

        /** The nozzle a sheet port of the column leaves or enters by: feed, bottoms, a draw, a steam row. */
        double nozzle(String port) {
            if (port.equals(FEED)) return nozzle(EquipmentShape.Role.FEED, 1);
            if (port.equals(BOTTOMS)) return nozzle(EquipmentShape.Role.BOTTOMS, 1);
            for (int i = 0; i < door.draws().size(); i++) if (port.equals(DRAW + door.draws().get(i).id())) return nozzle(EquipmentShape.Role.DRAW, i + 1);
            for (int i = 0; i < door.steam().size(); i++) if (port.equals(STEAM + door.steam().get(i).id())) return nozzle(EquipmentShape.Role.STEAM, i + 1);
            return PipeSizes.MAX_BORE;
        }

        /** Where a pump on {@code port} draws from over grade, m: a draw's tray or the sump; null for a port that leaves no tray. */
        @Nullable Double level(String port) {
            if (size == null) return null;
            if (port.equals(BOTTOMS)) return size.level(load.solved().stageCount() + 1);
            for (var draw : door.draws()) if (port.equals(DRAW + draw.id())) return size.level(draw.tray());
            return null;
        }
    }

    /** One sizing's reads. */
    private static final class Sizing {
        private final Flowsheet sheet;
        private final FlowsheetRun run;
        private final FlowThermo thermo;
        private final Entries entries;
        private final DesignBasis basis;
        private final MixtureViscosity.Workspace scratch = new MixtureViscosity.Workspace();
        /** Each unit's ports, by the stream on them. */
        private final Map<String, Map<String, String>> wires = new HashMap<>();
        private final Map<String, SizedUnit> units = new HashMap<>();
        private final Map<String, SizedLine> lines = new LinkedHashMap<>();
        private final Map<String, Block> blocks = new HashMap<>();
        /** Each block's members, in the door's order. */
        private final Map<String, Map<String, SizedUnit>> members = new HashMap<>();
        private final Map<String, SizedLine> memberLines = new LinkedHashMap<>();
        private final Map<String, Flowsheet.Unit> byId = new HashMap<>();
        private final Map<String, Flowsheet.Stream> streams = new HashMap<>();
        /** Each heater's duty at design flow, MW, whose spare its path gives. */
        private final Map<String, Double> heaters = new LinkedHashMap<>();

        Sizing(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, Entries entries) {
            this.sheet = sheet;
            this.run = run;
            this.thermo = thermo;
            this.entries = entries;
            basis = entries.basis();
            for (var unit : sheet.units()) byId.put(unit.id(), unit);
            for (var stream : sheet.streams()) {
                streams.put(stream.id(), stream);
                wire(stream.from(), stream.id());
                wire(stream.to(), stream.id());
            }
        }

        private void wire(Flowsheet.@Nullable End end, String stream) {
            if (end != null) wires.computeIfAbsent(end.unit(), id -> new HashMap<>()).put(end.port(), stream);
        }

        private Sized all() {
            if (run.status() != FlowsheetRun.Status.STEADY) return unsteady();
            for (var unit : sheet.units()) {
                thermo.checkpoint().run();
                var sized = first(unit);
                if (sized != null) units.put(unit.id(), sized);
            }
            for (var stream : sheet.streams()) {
                thermo.checkpoint().run();
                lines.put(stream.id(), line(stream));
            }
            var ordered = new LinkedHashMap<String, SizedUnit>();
            for (var unit : sheet.units()) {
                thermo.checkpoint().run();
                var sized = units.get(unit.id());
                ordered.put(unit.id(), sized != null ? sized : last(unit));
                var block = members.get(unit.id());
                if (block != null) ordered.putAll(block);
            }
            // The paths read every size; a heater's spare and a valve's share read the paths.
            var paths = paths(ordered);
            heaters.forEach((id, duty) -> {
                if (!(ordered.get(id).size() instanceof HeaterSizing.Size size)) return;
                var path = paths.stream().filter(p -> p.set() && p.passes(id)).findFirst();
                ordered.put(id, new SizedUnit(size, HeaterSizing.rate(new HeaterSizing.Load(duty, path.map(PressurePath::spare).orElse(null)), size)));
            });
            for (var path : paths) {
                if (path.taker() == null || !(ordered.get(path.taker()) instanceof SizedUnit valve) || valve.size() == null) continue;
                ordered.put(path.taker(), new SizedUnit(valve.size(), ValveSizing.rate(new ValveSizing.Load(path.spare(), path.lost() + path.spare()))));
            }
            var all = new LinkedHashMap<>(lines);
            all.putAll(memberLines);
            return new Sized(Collections.unmodifiableMap(all), Collections.unmodifiableMap(ordered), paths);
        }

        /** A run that failed, stopped or did not close: what it left is no steady state, so every item holds one red reading, and the size it was given. */
        private Sized unsteady() {
            var rating = new Rating(List.of(Limit.RUN_STEADY.read(0)));
            for (var stream : sheet.streams()) lines.put(stream.id(), new SizedLine(LineSizing.Line.NONE, entries.line(stream.id()).size(), rating));
            var ordered = new LinkedHashMap<String, SizedUnit>();
            for (var unit : sheet.units()) ordered.put(unit.id(), new SizedUnit(entries.unit(unit.id()).size(), rating));
            return new Sized(Collections.unmodifiableMap(lines), Collections.unmodifiableMap(ordered), List.of());
        }

        /** What is on {@code port}: a wired line, or a free outlet's own stream. */
        private FlowStream stream(String unit, String port) {
            var wired = wires.getOrDefault(unit, Map.of()).get(port);
            var stream = run.streams().get(wired != null ? wired : unit + ":" + port);
            return stream != null ? stream : FlowStream.empty(thermo.axis().size());
        }

        /** A unit that reads no line, null for one that does or has no sizing. */
        private @Nullable SizedUnit first(Flowsheet.Unit unit) {
            String id = unit.id();
            var entry = entries.unit(id);
            return switch (unit.type()) {
                case COLUMN -> column(id, entry, unit.params());
                case "exchanger" -> exchanger(id, entry, false);
                case "kettle" -> exchanger(id, entry, true);
                case "air_cooler" -> airCooler(id, entry);
                case "fired_heater" -> heater(id, entry);
                case "compressor" -> compressor(id, entry);
                case "drum" -> drum(id, entry);
                case "storage_tank" -> tank(id, entry);
                default -> null;
            };
        }

        /** {@code entry}'s size if it is of class {@code type}, rated; else Auto's. */
        private static <S extends UnitSize> SizedUnit sized(UnitEntry entry, Class<S> type, Function<S, Rating> rate, Supplier<Selection.Choice<S>> auto) {
            if (!type.isInstance(entry.size())) return SizedUnit.of(auto.get());
            var size = type.cast(entry.size());
            return new SizedUnit(size, rate.apply(size));
        }

        private SizedUnit exchanger(String id, UnitEntry entry, boolean kettle) {
            var shellOut = !kettle ? stream(id, "shell_out") : stream(id, "liquid").empty() ? stream(id, GAS) : stream(id, "liquid");
            return exchanger(entry, ExchangerSizing.Load.of(kettle, stream(id, "tube_in"), stream(id, "tube_out"), stream(id, kettle ? "feed" : "shell_in"), shellOut, basis, thermo, scratch));
        }

        private static SizedUnit exchanger(UnitEntry entry, ExchangerSizing.@Nullable Load load) {
            return sized(entry, ExchangerSizing.Size.class, size -> ExchangerSizing.rate(load, size), () -> ExchangerSizing.auto(load));
        }

        private SizedUnit airCooler(String id, UnitEntry entry) {
            return airCooler(entry, AirCoolerSizing.Load.of(stream(id, "in"), stream(id, "out"), basis, thermo, scratch));
        }

        private static SizedUnit airCooler(UnitEntry entry, AirCoolerSizing.@Nullable Load load) {
            return sized(entry, AirCoolerSizing.Size.class, size -> AirCoolerSizing.rate(load, size), () -> AirCoolerSizing.auto(load));
        }

        /** A heater's coils in series are one pair; set apart, each takes its share of the fire. */
        private SizedUnit heater(String id, UnitEntry entry) {
            double heat = 0;
            for (var coil : new String[]{"", "convection_", "radiant_"}) {
                var in = stream(id, coil + "in");
                if (!in.empty()) heat += stream(id, coil + "out").enthalpy() - in.enthalpy();
            }
            // Auto picks on the duty; the coils' passes follow the duty held, so the spare barely moves with the size, and its path
            // rates it once every size is known.
            var load = new HeaterSizing.Load(heat * basis.designFlow() / 1e6, null);
            heaters.put(id, load.duty());
            var type = entry.type();
            return sized(entry, HeaterSizing.Size.class, size -> HeaterSizing.rate(load, size), () -> type != null ? HeaterSizing.auto(load, type) : HeaterSizing.auto(load));
        }

        private SizedUnit compressor(String id, UnitEntry entry) {
            var load = CompressorSizing.Load.of(stream(id, "in"), stream(id, "out"), basis, thermo, scratch);
            var type = entry.type();
            return sized(entry, CompressorSizing.Size.class, size -> CompressorSizing.rate(load, size), () -> type != null ? CompressorSizing.auto(load, type) : CompressorSizing.auto(load));
        }

        private SizedUnit drum(String id, UnitEntry entry) {
            return drum(entry, DrumSizing.Load.of(stream(id, GAS), stream(id, OIL), stream(id, WATER), entry.hold(), basis, thermo, scratch));
        }

        private static SizedUnit drum(UnitEntry entry, DrumSizing.Load load) {
            var type = entry.type();
            return sized(entry, DrumSizing.Size.class, size -> DrumSizing.rate(load, size), () -> type != null ? DrumSizing.auto(load, type) : DrumSizing.auto(load));
        }

        private SizedUnit tank(String id, UnitEntry entry) {
            var load = TankSizing.Load.of(stream(id, OIL), stream(id, WATER), entry.hold(), basis, thermo);
            var type = entry.type();
            return sized(entry, TankSizing.Size.class, size -> TankSizing.rate(load, size), () -> type != null ? TankSizing.auto(load, type) : TankSizing.auto(load));
        }

        // ---- The column and its block ----

        /** The column at the diameter Auto or the player gives, then its members: a column whose run shows no profile has no size. */
        private SizedUnit column(String id, UnitEntry entry, Params params) {
            var report = run.reports().get(id);
            var display = report == null ? null : report.display();
            if (display == null) return SizedUnit.NONE;
            var door = ColumnDoor.of(params);
            var load = ColumnSizing.Load.of(door, display, stream(id, BOTTOMS), basis, thermo);
            if (load == null) return new SizedUnit(null, new Rating(List.of(UnitSize.reading(null))));
            var trials = new ColumnSizing.Trials(load, thermo.catalog(), thermo.checkpoint());
            // A kept column stands at its door's diameter, which reads the run's own trays: no trial solves.
            var sized = entry.kept() && !(entry.size() instanceof ColumnSizing.Size) ? new SizedUnit(load.size(load.diameter()), ColumnSizing.rate(load, load.size(load.diameter()), trials))
                    : sized(entry, ColumnSizing.Size.class, size -> ColumnSizing.rate(load, size, trials), () -> ColumnSizing.auto(load, trials));
            var readings = report.readings();
            var block = new Block(door, load, (ColumnSizing.Size) sized.size(), display.inspection().orElseThrow(), readings.getOrDefault("condenser", 0.), readings.getOrDefault("reboiler", 0.));
            blocks.put(id, block);
            members(id, block);
            return sized;
        }

        /**
         * The block's members, from the run's own profile (section 3.6): the condenser on the top tray's vapour, a kettle on the
         * bottom tray's liquid, the receiver on the condensate, the reflux pump, and each pumparound's cooler and pump on the
         * circulation its fall gives. Each cooler's cold side is the sheet's, at the door's duty.
         */
        private void members(String id, Block block) {
            var out = new LinkedHashMap<String, SizedUnit>();
            var nodes = block.inspection().nodes();
            var solved = block.load().solved();
            var door = block.door();
            var top = nodes.get(1);
            var condenser = nodes.get(0);
            // What leaves the condenser at its own temperature, and what enters it: that, heated back by the duty the column's
            // condenser takes out. Near the dew point the world's flash and V3's differ, so the top tray's vapour flashed at V3's
            // temperature would hold less heat than V3 condenses, and the curves would cross; this way the two sides balance.
            var vapour = ColumnSizing.stream(thermo, solved, top.vaporFractions(), top.vaporMolPerSecond(), top.waterVaporMolPerSecond(), top.temperatureKelvin(), top.pressurePascal());
            var condensed = thermo.atTemperature(condenser.temperatureKelvin(), condenser.pressurePascal(), vapour.amounts(), SolidInventory.EMPTY);
            var overhead = thermo.heated(condensed, block.condenser(), top.pressurePascal());
            double coolerDrop = 0;
            thermo.checkpoint().run();
            // A member's count is its door's (S3b): a kept size is rated at it, and Auto proposes one, the fewest units first.
            var counts = door.units();
            if (door.condenser() == ColumnComponents.Condenser.EXCHANGER) {
                var load = ExchangerSizing.Load.of(false, stream(id, "condenser_in"), stream(id, "condenser_out"), overhead, condensed, basis, thermo, scratch);
                var sized = exchanger(counted(entries.unit(member(id, "condenser")), counts.condenser()), load);
                out.put(member(id, "condenser"), sized);
                if (load != null && sized.size() instanceof ExchangerSizing.Size size) coolerDrop = ExchangerSizing.shellDrop(load, size);
            } else {
                var load = AirCoolerSizing.Load.of(overhead, condensed, basis, thermo, scratch);
                var sized = airCooler(counted(entries.unit(member(id, "condenser")), counts.condenser()), load);
                out.put(member(id, "condenser"), sized);
                if (load != null && sized.size() instanceof AirCoolerSizing.Size size) coolerDrop = AirCoolerSizing.drop(load, size);
            }
            int stages = solved.stageCount();
            if (ColumnComponents.kettleRequired(door.reboilerDuty())) {
                thermo.checkpoint().run();
                // The bottom tray's liquid, boiled by the duty the column's reboiler puts in, as the condenser's sides balance.
                var feed = ColumnSizing.liquid(thermo, solved, nodes.get(stages));
                var boiled = thermo.heated(feed, block.reboiler(), nodes.get(stages + 1).pressurePascal());
                out.put(member(id, "reboiler"), exchanger(counted(entries.unit(member(id, "reboiler")), counts.kettle()), ExchangerSizing.Load.of(true, stream(id, "reboiler_in"), stream(id, "reboiler_out"), feed, boiled, basis, thermo, scratch)));
            }
            thermo.checkpoint().run();
            var condensate = ColumnSizing.liquid(thermo, solved, condenser);
            var receiverEntry = entries.unit(member(id, "receiver"));
            var receiver = drum(receiverEntry, DrumSizing.Load.of(stream(id, OVERHEAD), condensate, stream(id, WATER), receiverEntry.hold(), basis, thermo, scratch));
            out.put(member(id, "receiver"), receiver);
            var drum = receiver.size() == null ? null : UnitSize.built(receiver.size());

            // The condensate leaves the receiver for the pump, and leaves it above the top tray's pressure, where the reflux enters.
            thermo.checkpoint().run();
            var overheadLine = memberLine(member(id, OVERHEAD), overhead, Service.Ends.OPEN, block.nozzle(EquipmentShape.Role.VAPOUR_OUTLET, 1));
            var suction = memberLine(member(id, "receiver out"), condensate, new Service.Ends(false, true), drum == null ? PipeSizes.MAX_BORE : oilNozzle(drum));
            var pumped = thermo.atTemperature(condensate.temperature(), top.pressurePascal(), condensate.amounts(), SolidInventory.EMPTY);
            var pumpOut = memberLine(member(id, "pump out"), pumped, Service.Ends.OPEN, PipeSizes.MAX_BORE);
            double share = condensate.total() > 0 ? Math.max(0, 1 - stream(id, DISTILLATE).total() / condensate.total()) : 0;
            double[] back = condensate.amounts();
            for (int i = 0; i < back.length; i++) back[i] *= share;
            var reflux = memberLine(member(id, "reflux"), thermo.atTemperature(condensate.temperature(), top.pressurePascal(), back, SolidInventory.EMPTY), Service.Ends.OPEN, block.nozzle(EquipmentShape.Role.REFLUX, 1));
            thermo.checkpoint().run();
            var size = block.size();
            Double drumLevel = drum == null ? null : drum.support() + drum.diameter() / 2;
            double lift = size == null || drumLevel == null ? 0 : size.level(1) - drumLevel;
            double rise = suction.line().density() * PassiveStepSolver.GRAVITY * lift + 1e5 * (drop(suction) + drop(pumpOut) + drop(reflux) + drop(overheadLine) + coolerDrop + Limit.VALVE_DROP.value());
            out.put(member(id, "reflux pump"), pump(member(id, "reflux pump"), suction, condensate, rise, drumLevel));

            for (int i = 0; i < door.pumparounds().size(); i++) {
                thermo.checkpoint().run();
                var pumparound = door.pumparounds().get(i);
                String name = "pa " + pumparound.id();
                var draw = nodes.get(pumparound.drawTray());
                var hot = circulation(solved, draw, -pumparound.duty());
                var cooled = thermo.atTemperature(hot.temperature() - basis.paFall(), hot.pressure(), hot.amounts(), SolidInventory.EMPTY);
                var load = ExchangerSizing.Load.of(false, stream(id, "pa/" + pumparound.id() + "/in"), stream(id, "pa/" + pumparound.id() + "/out"), hot, cooled, basis, thermo, scratch);
                var cooler = exchanger(counted(entries.unit(member(id, name + " cooler")), counts.cooler(i)), load);
                out.put(member(id, name + " cooler"), cooler);
                var drawLine = memberLine(member(id, name + " draw"), hot, new Service.Ends(true, true), block.nozzle(EquipmentShape.Role.PUMPAROUND_DRAW, i + 1));
                var returnLine = memberLine(member(id, name + " return"), cooled, Service.Ends.OPEN, block.nozzle(EquipmentShape.Role.RETURN, i + 1));
                double shell = load != null && cooler.size() instanceof ExchangerSizing.Size s ? ExchangerSizing.shellDrop(load, s) : 0;
                Double level = size == null ? null : size.level(pumparound.drawTray());
                double up = size == null ? 0 : size.level(pumparound.returnTray()) - size.level(pumparound.drawTray());
                double paRise = drawLine.line().density() * PassiveStepSolver.GRAVITY * up + 1e5 * (drop(drawLine) + drop(returnLine) + shell + Limit.VALVE_DROP.value());
                out.put(member(id, name + " pump"), pump(member(id, name + " pump"), drawLine, hot, paRise, level));
            }
            members.put(id, out);
        }

        /** {@code entry} with its kept size at {@code count} units in parallel: a member's count is its door's. Auto's entry stays. */
        private static UnitEntry counted(UnitEntry entry, int count) {
            UnitSize size = switch (entry.size()) {
                case ExchangerSizing.Size exchanger -> exchanger.withShells(count);
                case AirCoolerSizing.Size cooler -> cooler.withBanks(count);
                case null, default -> entry.size();
            };
            return new UnitEntry(size, entry.type(), entry.height(), entry.hold(), entry.kept());
        }

        /** The liquid a pumparound circulates to give {@code heat} W across its fall, at its draw tray. */
        private FlowStream circulation(com.wormzjl.icheme.science.column.v3.V3ColumnInput solved, V3ColumnInspection.Node draw, double heat) {
            var one = ColumnSizing.stream(thermo, solved, draw.liquidFractions(), 1, 0, draw.temperatureKelvin(), draw.pressurePascal());
            double fall = one.enthalpy() - thermo.atTemperature(draw.temperatureKelvin() - basis.paFall(), draw.pressurePascal(), one.amounts(), SolidInventory.EMPTY).enthalpy();
            return ColumnSizing.stream(thermo, solved, draw.liquidFractions(), fall > 0 ? heat / fall : 0, 0, draw.temperatureKelvin(), draw.pressurePascal());
        }

        /** A member pump on {@code suction}, adding {@code rise} Pa. */
        private SizedUnit pump(String id, SizedLine suction, FlowStream liquid, double rise, @Nullable Double height) {
            var entry = entries.unit(id);
            if (suction.size() == null) return SizedUnit.NONE;
            var load = PumpSizing.Load.of(suction.line(), suction.size(), liquid, rise, entry.height() != null ? entry.height() : height, thermo);
            return sized(entry, PumpSizing.Size.class, size -> PumpSizing.rate(load, size), () -> PumpSizing.auto(load));
        }

        /** What a sized line loses over its length at design flow, bar; nothing with no size. */
        private static double drop(SizedLine line) {
            return line.size() == null ? 0 : LineSizing.drop(line.line(), line.size(), line.line().length());
        }

        private SizedLine memberLine(String id, FlowStream flow, Service.Ends ends, double nozzle) {
            var sized = line(id, flow, ends, nozzle);
            memberLines.put(id, sized);
            return sized;
        }

        // ---- Lines ----

        private SizedLine line(Flowsheet.Stream stream) {
            var flow = run.streams().get(stream.id());
            if (flow == null) return new SizedLine(LineSizing.Line.NONE, null, Rating.NONE);
            return line(stream.id(), flow, Service.Ends.of(sheet, stream), Math.min(nozzle(stream.from()), nozzle(stream.to())));
        }

        private SizedLine line(String id, FlowStream flow, Service.Ends ends, double nozzle) {
            var entry = entries.line(id);
            var service = Service.of(flow, ends, thermo, entry.service());
            var line = LineSizing.Line.of(flow, service, basis, thermo, nozzle, entry.length(), scratch);
            var fixed = entry.size();
            if (fixed != null) return new SizedLine(line, fixed, LineSizing.rate(line, fixed));
            var choice = LineSizing.auto(line);
            return new SizedLine(line, choice.size(), choice.rating());
        }

        /** The largest bore {@code end}'s nozzle takes, as its unit stands sized: a column's by its port, a boot drum's oil outlet. */
        private double nozzle(Flowsheet.@Nullable End end) {
            if (end == null) return PipeSizes.MAX_BORE;
            var block = blocks.get(end.unit());
            if (block != null) return block.nozzle(end.port());
            if (!end.port().equals(OIL)) return PipeSizes.MAX_BORE;
            var sized = units.get(end.unit());
            var shape = sized == null || sized.size() == null ? null : UnitSize.built(sized.size());
            return shape == null ? PipeSizes.MAX_BORE : oilNozzle(shape);
        }

        /** A drum's oil outlet: only a boot drum's, beside its saddle, holds a line back. */
        private static double oilNozzle(EquipmentShape shape) {
            double bore = PipeSizes.MAX_BORE;
            for (int k = 0; k < shape.nozzles().size(); k++) if (shape.nozzles().get(k).role() == EquipmentShape.Role.LIQUID_OUTLET) bore = Math.min(bore, NozzleBore.largest(shape, k));
            return bore;
        }

        // ---- Units on their inlet lines ----

        /** A unit that reads its inlet line, and those with no sizing yet. */
        private SizedUnit last(Flowsheet.Unit unit) {
            String id = unit.id();
            var entry = entries.unit(id);
            return switch (unit.type()) {
                case "pump" -> pump(id, entry);
                case "valve" -> valve(id, entry);
                default -> SizedUnit.NONE;
            };
        }

        private SizedUnit pump(String id, UnitEntry entry) {
            var sized = inlet(id);
            if (sized == null || sized.size() == null) return SizedUnit.NONE;
            var load = PumpSizing.Load.of(sized.line(), sized.size(), stream(id, "in"), stream(id, "out"), height(id, entry), thermo);
            return Sizing.sized(entry, PumpSizing.Size.class, size -> PumpSizing.rate(load, size), () -> PumpSizing.auto(load));
        }

        private @Nullable SizedLine inlet(String id) {
            var wired = wires.getOrDefault(id, Map.of()).get("in");
            return wired == null ? null : lines.get(wired);
        }

        /**
         * The head over the pump's suction, from the nozzle it draws by: a column's tray or sump, as the column stands sized; its
         * receiver's half level for the distillate, and its underside, over the boot, for the water; anything else at grade. Null
         * where the column has no size.
         */
        private @Nullable Double height(String id, UnitEntry entry) {
            if (entry.height() != null) return entry.height();
            var wired = wires.getOrDefault(id, Map.of()).get("in");
            var stream = wired == null ? null : streams.get(wired);
            return stream == null || stream.from() == null ? (Double) 0. : level(stream.from());
        }

        /**
         * How high over grade the liquid leaving by {@code from} stands, m: a column's tray or sump, as the column stands sized; its
         * receiver's half level for the distillate, and its underside, over the boot, for the water; null where the column has no
         * size; grade for anything else.
         */
        private @Nullable Double level(Flowsheet.End from) {
            var block = blocks.get(from.unit());
            if (block != null) {
                var level = block.level(from.port());
                if (level != null) return level;
                var receiver = members.getOrDefault(from.unit(), Map.of()).get(member(from.unit(), "receiver"));
                var drum = receiver == null || receiver.size() == null ? null : UnitSize.built(receiver.size());
                if (drum == null) return null;
                return from.port().equals(WATER) ? drum.support() : drum.support() + drum.diameter() / 2;
            }
            var unit = byId.get(from.unit());
            return unit != null && unit.type().equals(COLUMN) ? null : (Double) 0.;
        }

        /** A valve on its inlet line's bore, at the drop the run gives it until a path ends at it and gives its drop at design flow. */
        private SizedUnit valve(String id, UnitEntry entry) {
            var sized = inlet(id);
            if (sized == null || sized.size() == null) return SizedUnit.NONE;
            return new SizedUnit(new ValveSizing.Size(sized.size().bore()), ValveSizing.rate(ValveSizing.Load.of(stream(id, "in"), stream(id, "out"), null)));
        }

        // ---- Pressure paths, section 3.6 ----

        /** Every path, from each stream that starts one, through the sizes {@code sized} holds. */
        private List<PressurePath> paths(Map<String, SizedUnit> sized) {
            var paths = new ArrayList<PressurePath>();
            for (var stream : sheet.streams()) {
                thermo.checkpoint().run();
                var flow = run.streams().get(stream.id());
                String start = flow == null || flow.empty() ? null : start(stream);
                if (start == null) continue;
                var level = stream.from() == null || stream.from().port().equals(OVERHEAD) ? null : level(stream.from());
                double head = level == null ? 0 : flow.mass() / flow.state().volume() * PassiveStepSolver.GRAVITY * level / 1e5;
                walk(new Walk(start, flow.pressure() / 1e5, head), stream.id(), new ArrayList<>(), new java.util.HashSet<>(), sized, paths);
            }
            return paths;
        }

        /** Where a path starts. */
        private record Walk(String start, double from, double head) {
        }

        /** What a path on {@code stream} starts from, null where the stream only passes on from a unit's side. */
        private @Nullable String start(Flowsheet.Stream stream) {
            var from = stream.from();
            if (from == null) return stream.id();
            var unit = byId.get(from.unit());
            if (unit == null) return null;
            String port = from.port();
            return switch (unit.type()) {
                case "pump", "compressor", "drum", "storage_tank" -> unit.id();
                case "kettle" -> port.equals("tube_out") ? null : unit.id();
                case COLUMN -> port.equals("condenser_out") || port.equals("reboiler_out") || port.startsWith("pa/") ? null : unit.id();
                case "valve" -> set(unit) ? unit.id() : null;
                default -> null;
            };
        }

        /** Whether a valve holds its outlet, which ends a path and starts the next; one set to a drop is a step. */
        private static boolean set(Flowsheet.Unit valve) {
            return valve.params().text("target", "outlet").equals("outlet");
        }

        /** Walks on along {@code streamId}, its line one step, to the end of each path it reaches. */
        private void walk(Walk walk, String streamId, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
            if (!seen.add(streamId)) return;
            var line = lines.get(streamId);
            steps.add(new PressurePath.Step(streamId, PressurePath.Step.LINE, line == null ? 0 : drop(line), 0));
            var stream = streams.get(streamId);
            var to = stream == null ? null : stream.to();
            var unit = to == null ? null : byId.get(to.unit());
            if (unit == null) {
                end(walk, streamId, null, steps, null, paths);
                return;
            }
            String id = unit.id(), port = to.port();
            var params = unit.params();
            switch (unit.type()) {
                case "exchanger", "kettle" -> {
                    if (port.equals("tube_in")) through(walk, id, id, "tube_drop", params.number("tube_drop", 0), streamId, "tube_out", steps, seen, sized, paths);
                    else if (port.equals("shell_in")) through(walk, id, id, "shell_drop", params.number("shell_drop", 0), streamId, "shell_out", steps, seen, sized, paths);
                    else end(walk, id, null, steps, null, paths);
                }
                case "air_cooler" -> through(walk, id, id, "drop", params.number("drop", 0), streamId, "out", steps, seen, sized, paths);
                case "fired_heater" -> {
                    switch (port) {
                        case "in" -> through(walk, id, id, FiredHeaterUnit.COILS, params.number(FiredHeaterUnit.RADIANT_DROP, 0) + params.number(FiredHeaterUnit.CONVECTION_DROP, 0), streamId, "out", steps, seen, sized, paths);
                        case "convection_in" -> through(walk, id, id, FiredHeaterUnit.CONVECTION_DROP, params.number(FiredHeaterUnit.CONVECTION_DROP, 0), streamId, "convection_out", steps, seen, sized, paths);
                        default -> through(walk, id, id, FiredHeaterUnit.RADIANT_DROP, params.number(FiredHeaterUnit.RADIANT_DROP, 0), streamId, "radiant_out", steps, seen, sized, paths);
                    }
                }
                case COLUMN -> column(walk, unit, port, streamId, steps, seen, sized, paths);
                case "valve" -> {
                    if (set(unit)) end(walk, id, params.number("pressure") / 1e5, steps, id, paths);
                    else {
                        double typed = params.number("drop", 0);
                        steps.add(new PressurePath.Step(id, "drop", typed / 1e5, typed / 1e5));
                        onward(walk, id, "out", steps, seen, sized, paths);
                    }
                }
                case "mixer", "filter" -> onward(walk, id, "out", steps, seen, sized, paths);
                case "splitter" -> {
                    for (var outlet : wires.getOrDefault(id, Map.of()).entrySet())
                        if (!outlet.getKey().equals("in")) walk(walk, outlet.getValue(), new ArrayList<>(steps), new java.util.HashSet<>(seen), sized, paths);
                }
                case "drum" -> end(walk, id, params.text("pressure_mode", "inlet").equals("set") ? params.number("pressure") / 1e5 : null, steps, null, paths);
                case "storage_tank" -> {
                    var report = run.reports().get(id);
                    var held = report == null ? null : report.readings().get("pressure");
                    end(walk, id, held == null ? null : held / 1e5, steps, null, paths);
                }
                default -> end(walk, id, null, steps, null, paths);
            }
        }

        /** A column's inlet: a heat pair's sheet side passes through its member's tubes; a feed or a steam row ends on its tray. */
        private void column(Walk walk, Flowsheet.Unit unit, String port, String streamId, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
            String id = unit.id();
            var door = ColumnDoor.of(unit.params());
            if (port.equals("condenser_in")) {
                through(walk, id, member(id, "condenser"), ColumnUnit.CONDENSER_DROP, door.condenserDrop(), streamId, "condenser_out", steps, seen, sized, paths);
                return;
            }
            if (port.equals("reboiler_in")) {
                through(walk, id, member(id, "reboiler"), ColumnUnit.REBOILER_DROP, door.reboilerDrop(), streamId, "reboiler_out", steps, seen, sized, paths);
                return;
            }
            for (var pumparound : door.pumparounds()) {
                if (port.equals("pa/" + pumparound.id() + "/in")) {
                    through(walk, id, member(id, "pa " + pumparound.id() + " cooler"), ColumnUnit.pumparoundDrop(pumparound), pumparound.drop(), streamId, "pa/" + pumparound.id() + "/out", steps, seen, sized, paths);
                    return;
                }
            }
            var block = blocks.get(id);
            Integer stage = port.equals(FEED) ? (Integer) door.feedStage() : null;
            for (var row : door.steam()) if (port.equals(STEAM + row.id())) stage = row.stage();
            Double tray = block == null || stage == null ? null : block.inspection().nodes().get(stage).pressurePascal() / 1e5;
            end(walk, id, tray, steps, null, paths);
        }

        /**
         * A unit's side: one step, at design flow by {@code item}'s size where it has one the constructor builds, else the door's
         * {@code typed} (Pa); then on along what leaves by {@code outlet}.
         */
        private void through(Walk walk, String unit, String item, String passage, double typed, String streamId, String outlet, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
            var in = run.streams().get(streamId);
            var out = stream(unit, outlet);
            var size = sized.get(item) == null ? null : sized.get(item).size();
            var law = in == null || in.empty() ? null : SizedPassages.drop(size, passage, in, out.empty() ? null : out, basis.designFlow(), thermo);
            steps.add(new PressurePath.Step(item, passage, (law != null ? law : typed) / 1e5, typed / 1e5));
            onward(walk, unit, outlet, steps, seen, sized, paths);
        }

        /** On along what leaves {@code unit} by {@code port}: a free outlet's own stream ends the path there. */
        private void onward(Walk walk, String unit, String port, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
            var wired = wires.getOrDefault(unit, Map.of()).get(port);
            if (wired == null) end(walk, unit + ":" + port, null, steps, null, paths);
            else walk(walk, wired, steps, seen, sized, paths);
        }

        /**
         * The path's end: at {@code set} bar where the sheet sets it; else what the run delivers there on the doors' drops, which
         * reads no head, so a delivered path has its head and the doors' drops to spend.
         */
        private static void end(Walk walk, String end, @Nullable Double set, List<PressurePath.Step> steps, @Nullable String taker, List<PressurePath> paths) {
            double typed = 0;
            for (var step : steps) typed += step.typed();
            paths.add(new PressurePath(walk.start(), walk.from(), walk.head(), end, set != null ? set : walk.from() - typed, set != null, taker, steps));
        }
    }
}
