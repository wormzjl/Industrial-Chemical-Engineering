package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.SheetCompounds;
import com.wormzjl.icheme.science.flowsheet.Wiring;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import com.wormzjl.icheme.science.flowsheet.unit.TurbineUnit;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    static final String OIL = "oil", WATER = "water", GAS = "gas", COLUMN = "column";
    /** An air-then-water condenser's water exchanger, a member beside its air cooler, {@code condenser} (C2). */
    public static final String TRIM = "trim";

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
     * @param hold   a drum's entry, min, or a tank's, h; null for the row's
     * @param kept   a column keeps its door's diameter, rated there with no trial
     */
    public record UnitEntry(@Nullable UnitSize size, @Nullable UnitType type, @Nullable Double hold, boolean kept) {
        public static final UnitEntry AUTO = new UnitEntry(null, null, null, false);
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
                units.put(id, new UnitEntry(all || fixed ? unit.size() : null, fixed?unit.type():null, unit.hold(), fixed));
            });
            if (all) for (var unit : sheet.units()) if (unit.type().equals(COLUMN)) units.put(unit.id(), new UnitEntry(null, facts.unit(unit.id()).type(), null, true));
            var lines = new HashMap<String, LineEntry>();
            facts.lines().forEach((id, line) -> lines.put(id, new LineEntry(line.service(), line.length(), all || line.mode() == SizingFacts.Mode.FIXED ? line.size() : null)));
            return new Entries(facts.basis(), lines, units);
        }

        private LineEntry line(String stream) {
            return lines.getOrDefault(stream, LineEntry.AUTO);
        }

        UnitEntry unit(String id) {
            return units.getOrDefault(id, UnitEntry.AUTO);
        }

    }

    /** @param size null with no flow */
    public record SizedLine(LineSizing.Line line, LineSizing.@Nullable Size size, Rating rating) {
    }

    /** @param size null with nothing to size */
    public record SizedUnit(@Nullable UnitSize size, Rating rating) {
        static final SizedUnit NONE = new SizedUnit(null, Rating.NONE);

        private static <S extends UnitSize> SizedUnit of(Selection.Choice<S> choice) {
            return new SizedUnit(choice.size(), choice.rating());
        }
    }

    /**
     * A sheet, sized: state, rebuilt whenever it is asked for, never saved.
     *
     * @param lines by stream, in sheet order, then each block's member lines
     * @param units by unit, in sheet order, each block's members under it
     * @param paths      the pressure paths, by the streams they start on, in sheet order
     * @param condensers each column's proposed condenser type, by its id (C2): the door's type is the player's and sets its ports,
     *                   so sizing rates the door's and only shows this one
     */
    public record Sized(Map<String, SizedLine> lines, Map<String, SizedUnit> units, List<PressurePath> paths, Map<String, ColumnComponents.Condenser> condensers) {
        public Sized {
            paths = List.copyOf(paths);
            condensers = Map.copyOf(condensers);
        }

        /** A sizing with no column's proposal. */
        public Sized(Map<String, SizedLine> lines, Map<String, SizedUnit> units, List<PressurePath> paths) {
            this(lines, units, paths, Map.of());
        }

        /** What every air cooler's fans draw at design flow, W, members too: the sheet's load on the electricity network to come (section 8). */
        public double fanPower() {
            double power = 0;
            for (var unit : units.values()) power += unit.rating().values().getOrDefault(Rating.FAN_POWER, 0.);
            return power;
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
     * A pumparound's member, {@code pa 2 cooler}: its pumparound's id and its part, {@code cooler}, {@code pump}, {@code draw} or
     * {@code return}. Its own name is the lang line {@code sizing.member.pa_<part>} with the id as its argument.
     */
    public record PumparoundMember(String pumparound, String part) {
        public static final String COOLER = "cooler";
        private static final String HEAD = "pa";

        public static String name(String pumparound, String part) {
            return HEAD + " " + pumparound + " " + part;
        }

        /** The pumparound member {@code member} names, or null for a member of none. */
        public static @Nullable PumparoundMember of(String member) {
            String[] words = member.split(" ");
            return words.length == 3 && words[0].equals(HEAD) ? new PumparoundMember(words[1], words[2]) : null;
        }

        /** The tail of its own name's lang key, after {@code sizing.member.}. */
        public String key() {
            return HEAD + "_" + part;
        }
    }

    /**
     * What Send to block stores ({@code SIZING_PLAN.md} section 3.7): each unit and member at the size the tab shows, the player's where
     * the sheet holds one, else the rating's, in the rating's order. A column goes at its door's diameter with its components and member
     * counts. A unit the constructor has no kind for (a valve: the pipe tool lays it at its line's bore) or a size it refuses is left out,
     * as is a unit with nothing to size.
     */
    public static List<SentDesign> designs(Flowsheet sheet, Sized sized) {
        var names = new HashMap<String, String>();
        for (var unit : sheet.units()) names.put(unit.id(), unit.name());
        var out = new ArrayList<SentDesign>();
        for (var entry : sized.units().entrySet()) {
            String id = entry.getKey();
            var held = sheet.sizing().unit(id).size();
            UnitSize size = held != null ? held : entry.getValue().size();
            var shape = size == null ? null : UnitSize.built(size);
            if (shape == null) continue;
            int slash = id.indexOf('/');
            String block = slash < 0 ? id : id.substring(0, slash), member = slash < 0 ? "" : id.substring(slash + 1);
            var column = size instanceof ColumnSizing.Size c ? new ColumnDesign(c.layout(), c.diameter()) : null;
            out.add(new SentDesign(names.getOrDefault(block, block), member, size.count(), shape, column));
            if (out.size() == SentDesign.MAX) break;
        }
        return out;
    }

    /**
     * One sizing of {@code run}'s streams on {@code sheet} at {@code entries}: what Auto proposes, and every item rated. A column on
     * Auto tries its diameters. {@code thermo} is the network package's, on whose axis {@code run} stands; the sizing reads the run
     * on the network's view of the sheet's compounds, as the run solved there ({@link SheetCompounds}), so it evaluates no compound
     * the sheet does not hold.
     */
    public static Sized pass(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, Entries entries) {
        return SheetCompounds.on(sheet, thermo, view -> new Sizing(sheet, view.down(run), view.thermo(), entries).all());
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
     * One column as its sizing left it: what its members, its lines' nozzles and its pumps' heights read.
     *
     * @param condenser the heat its condenser takes out at the run's flow, W
     * @param reboiler  the heat its reboiler puts in, W
     */
    record Block(ColumnDoor door, ColumnSizing.Load load, ColumnSizing.@Nullable Size size, V3ColumnInspection inspection, double condenser, double reboiler) {
        /** The largest bore on the {@code index}th nozzle of {@code role} (from 1), as the column stands sized. */
        double nozzle(EquipmentShape.Role role, int index) {
            var shape = size == null ? null : UnitSize.built(size);
            if (shape == null) return PipeSizes.MAX_BORE;
            var nozzles = shape.nozzles();
            for (int k = 0, seen = 0; k < nozzles.size(); k++) if (nozzles.get(k).role() == role && ++seen == index) return NozzleBore.proposed(shape, k);
            return PipeSizes.MAX_BORE;
        }

        /** The nozzle a sheet port of the column leaves or enters by: feed, bottoms, a draw, a steam row. */
        double nozzle(String port) {
            if (port.equals(ColumnUnit.FEED)) return nozzle(EquipmentShape.Role.FEED, 1);
            if (port.equals(ColumnUnit.BOTTOMS)) return nozzle(EquipmentShape.Role.BOTTOMS, 1);
            for (int i = 0; i < door.draws().size(); i++) if (port.equals(ColumnUnit.drawPort(door.draws().get(i)))) return nozzle(EquipmentShape.Role.DRAW, i + 1);
            for (int i = 0; i < door.steam().size(); i++) if (port.equals(ColumnUnit.steamPort(door.steam().get(i)))) return nozzle(EquipmentShape.Role.STEAM, i + 1);
            return PipeSizes.MAX_BORE;
        }

        /** Where a pump on {@code port} draws from over grade, m: a draw's tray or the sump; null for a port that leaves no tray. */
        @Nullable Double level(String port) {
            if (size == null) return null;
            if (port.equals(ColumnUnit.BOTTOMS)) return size.level(load.solved().stageCount() + 1);
            for (var draw : door.draws()) if (port.equals(ColumnUnit.drawPort(draw))) return size.level(draw.tray());
            return null;
        }
    }

    /** One sizing's reads. */
    static final class Sizing {
        final Flowsheet sheet;
        final FlowsheetRun run;
        final FlowThermo thermo;
        final Entries entries;
        final DesignBasis basis;
        final MixtureViscosity.Workspace scratch = new MixtureViscosity.Workspace();
        /** Each unit's ports, by the stream on them. */
        final Map<String, Map<String, String>> wires = new HashMap<>();
        final Map<String, SizedUnit> units = new HashMap<>();
        final Map<String, SizedLine> lines = new LinkedHashMap<>();
        final Map<String, Block> blocks = new HashMap<>();
        /** Each block's members, in the door's order. */
        final Map<String, Map<String, SizedUnit>> members = new HashMap<>();
        final Map<String, SizedLine> memberLines = new LinkedHashMap<>();
        final Map<String, Flowsheet.Unit> byId = new HashMap<>();
        final Map<String, Flowsheet.Stream> streams = new HashMap<>();
        /** Each column's proposed condenser type. */
        final Map<String, ColumnComponents.Condenser> condensers = new LinkedHashMap<>();
        /** Each heater's duty at design flow, MW, whose spare its path gives. */
        final Map<String, Double> heaters = new LinkedHashMap<>();

        Sizing(Flowsheet drawn, FlowsheetRun run, FlowThermo thermo, Entries entries) {
            // A passage's ends read as the roles they play, as the run read them.
            this.sheet = Wiring.roles(drawn);
            this.run = run;
            this.thermo = thermo;
            this.entries = entries;
            basis = entries.basis();
            for (var unit : this.sheet.units()) byId.put(unit.id(), unit);
            for (var stream : this.sheet.streams()) {
                streams.put(stream.id(), stream);
                wire(stream.from(), stream.id());
                wire(stream.to(), stream.id());
            }
        }

        void wire(Flowsheet.@Nullable End end, String stream) {
            if (end != null) wires.computeIfAbsent(end.unit(), id -> new HashMap<>()).put(end.port(), stream);
        }

        Sized all() {
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
            var paths = new PathWalk(this).paths(ordered);
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
            return new Sized(Collections.unmodifiableMap(all), Collections.unmodifiableMap(ordered), paths, condensers);
        }

        /** A run that failed, stopped or did not close: what it left is no steady state, so every item holds one red reading, and the size it was given. */
        Sized unsteady() {
            var rating = new Rating(List.of(Limit.RUN_STEADY.read(0)));
            for (var stream : sheet.streams()) lines.put(stream.id(), new SizedLine(LineSizing.Line.NONE, entries.line(stream.id()).size(), rating));
            var ordered = new LinkedHashMap<String, SizedUnit>();
            for (var unit : sheet.units()) ordered.put(unit.id(), new SizedUnit(entries.unit(unit.id()).size(), rating));
            return new Sized(Collections.unmodifiableMap(lines), Collections.unmodifiableMap(ordered), List.of());
        }

        /** What is on {@code port}: a wired line, or a free outlet's own stream. */
        FlowStream stream(String unit, String port) {
            var wired = wires.getOrDefault(unit, Map.of()).get(port);
            var stream = run.streams().get(wired != null ? wired : unit + ":" + port);
            return stream != null ? stream : FlowStream.empty(thermo.axis().size());
        }

        /** A unit that reads no line, null for one that does or has no sizing. */
        @Nullable SizedUnit first(Flowsheet.Unit unit) {
            String id = unit.id();
            var entry = entries.unit(id);
            return switch (unit.type()) {
                case COLUMN -> new BlockSizing(this).column(id, entry, unit.params());
                case "exchanger" -> exchanger(id, entry, false);
                case "kettle" -> exchanger(id, entry, true);
                case "cooler" -> cooler(id, entry);
                case "fired_heater" -> heater(id, entry);
                case "compressor" -> compressor(id, entry);
                case "drum" -> drum(id, entry);
                case "storage_tank" -> tank(id, entry);
                case "ejector" -> ejector(id, entry);
                case "flare" -> flare(id, entry);
                case "chimney" -> chimney(id, entry, unit.params());
                case "batch_still" -> still(id, entry, unit.params());
                case "steam_turbine" -> turbine(id, entry, unit.params().number("exhaust", TurbineUnit.EXHAUST_PRESSURE));
                default -> null;
            };
        }

        /** {@code entry}'s size if it is of class {@code type}, rated; else Auto's. */
        static <S extends UnitSize> SizedUnit sized(UnitEntry entry, Class<S> type, Function<S, Rating> rate, Supplier<Selection.Choice<S>> auto) {
            if (!type.isInstance(entry.size())) return SizedUnit.of(auto.get());
            var size = type.cast(entry.size());
            return new SizedUnit(size, rate.apply(size));
        }

        /**
         * An exchanger or a kettle. An exchanger with one side free and the other cooled has tower water on the free side (C2), as the
         * run gives it; a free side that heats is a utility this model has no temperature for, so it stays unsized.
         */
        SizedUnit exchanger(String id, UnitEntry entry, boolean kettle) {
            if (!kettle && wired(id, "tube_in") != wired(id, "shell_in")) {
                boolean tube = wired(id, "tube_in");
                return exchanger(entry, ExchangerSizing.Load.cooled(stream(id, tube ? "tube_in" : "shell_in"), stream(id, tube ? "tube_out" : "shell_out"), basis, thermo, scratch));
            }
            var shellOut = !kettle ? stream(id, "shell_out") : stream(id, "liquid").empty() ? stream(id, GAS) : stream(id, "liquid");
            return exchanger(entry, ExchangerSizing.Load.of(kettle, stream(id, "tube_in"), stream(id, "tube_out"), stream(id, kettle ? "feed" : "shell_in"), shellOut, basis, thermo, scratch));
        }

        /** Whether {@code unit}'s {@code port} has a stream on it. */
        boolean wired(String unit, String port) {
            return wires.getOrDefault(unit, Map.of()).containsKey(port);
        }

        /**
         * A heat pair's water side from {@code hotIn} to {@code hotOut}, the process on the shell: the sheet's stream on {@code in}
         * and {@code out} where the port is wired, else tower water (C2), back where a regulated side's water would leave: under the
         * process cold end, {@code hotOut}, the receiver or the pumparound's return ({@code COOLER_MODEL_PLAN.md} section 8).
         */
        ExchangerSizing.@Nullable Load water(String unit, String in, String out, FlowStream hotIn, FlowStream hotOut) {
            return wired(unit, in) ? ExchangerSizing.Load.of(false, stream(unit, in), stream(unit, out), hotIn, hotOut, basis, thermo, scratch)
                    : ExchangerSizing.Load.cooled(hotIn, hotOut, CoolerSizing.columnReturn(hotOut.temperature(), hotIn.temperature()), basis, thermo, scratch);
        }

        /**
         * A Cooler (C2): its type is the player's key, else its kept size's, else the proposal by its outlet ({@link CoolerSizing#type}).
         * Air sizes an air cooler; Water an exchanger on tower water, which shows the duty an air cooler ahead of it would take.
         */
        SizedUnit cooler(String id, UnitEntry entry) {
            var in = stream(id, "in");
            var out = stream(id, "out");
            var kept = SizingFacts.UnitFacts.typeOf(entry.size());
            var type = entry.type() != null ? entry.type() : kept != null ? kept : CoolerSizing.type(out.temperature());
            if (type != UnitType.WATER_COOLER) return airCooler(entry, AirCoolerSizing.Load.of(in, out, basis, thermo, scratch));
            var sized = exchanger(entry, ExchangerSizing.Load.cooled(in, out, basis, thermo, scratch));
            double ahead = CoolerSizing.airAhead(in, out, thermo);
            return ahead > 0 && !sized.rating().readings().isEmpty() ? new SizedUnit(sized.size(), sized.rating().with(Rating.AIR_AHEAD, ahead * basis.designFlow() / 1e6)) : sized;
        }

        static SizedUnit exchanger(UnitEntry entry, ExchangerSizing.@Nullable Load load) {
            return sized(entry, ExchangerSizing.Size.class, size -> ExchangerSizing.rate(load, size), () -> ExchangerSizing.auto(load));
        }

        SizedUnit airCooler(String id, UnitEntry entry) {
            return airCooler(entry, AirCoolerSizing.Load.of(stream(id, "in"), stream(id, "out"), basis, thermo, scratch));
        }

        /** An air cooler, with its fans' speed and power on the size it holds or Auto proposes ({@code COOLER_MODEL_PLAN.md} section 8). */
        static SizedUnit airCooler(UnitEntry entry, AirCoolerSizing.@Nullable Load load) {
            var sized = sized(entry, AirCoolerSizing.Size.class, size -> AirCoolerSizing.rate(load, size), () -> AirCoolerSizing.auto(load));
            var fans = sized.size() instanceof AirCoolerSizing.Size size ? AirCoolerSizing.fans(load, size) : null;
            return fans == null ? sized : new SizedUnit(sized.size(), sized.rating().with(Rating.FANS, fans.speed()).with(Rating.FAN_POWER, fans.power()));
        }

        /** A heater's coils in series are one pair; set apart, each takes its share of the fire. */
        SizedUnit heater(String id, UnitEntry entry) {
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
            return sized(entry, HeaterSizing.Size.class, size -> HeaterSizing.rate(load, size), () -> HeaterSizing.auto(load, type));
        }

        SizedUnit compressor(String id, UnitEntry entry) {
            var load = CompressorSizing.Load.of(stream(id, "in"), stream(id, "out"), basis, thermo, scratch);
            var type = entry.type();
            return sized(entry, CompressorSizing.Size.class, size -> CompressorSizing.rate(load, size), () -> CompressorSizing.auto(load, type));
        }

        SizedUnit drum(String id, UnitEntry entry) {
            return drum(entry, DrumSizing.Load.of(stream(id, GAS), stream(id, OIL), stream(id, WATER), entry.hold(), basis, thermo, scratch));
        }

        static SizedUnit drum(UnitEntry entry, DrumSizing.Load load) {
            var type = entry.type();
            return sized(entry, DrumSizing.Size.class, size -> DrumSizing.rate(load, size), () -> DrumSizing.auto(load, type));
        }

        SizedUnit tank(String id, UnitEntry entry) {
            var load = TankSizing.Load.of(stream(id, OIL), stream(id, WATER), entry.hold(), basis, thermo);
            var type = entry.type();
            return sized(entry, TankSizing.Size.class, size -> TankSizing.rate(load, size), () -> TankSizing.auto(load, type));
        }

        /** An ejector on its suction's load, with what its law needs and what a wired Motive port brings (W10). */
        SizedUnit ejector(String id, UnitEntry entry) {
            var readings = readings(id);
            boolean wired = wires.getOrDefault(id, Map.of()).containsKey(EjectorUnit.MOTIVE);
            var load = EjectorSizing.Load.of(stream(id, EjectorUnit.SUCTION), readings.getOrDefault("need", 0.), wired ? readings.get("motive") : null, basis);
            return ejector(entry, load);
        }

        static SizedUnit ejector(UnitEntry entry, EjectorSizing.Load load) {
            return sized(entry, EjectorSizing.Size.class, size -> EjectorSizing.rate(load, size), () -> EjectorSizing.auto(load));
        }

        /** A flare on the gas its run took, with the heat, heating value, density and flame its reading gave (batch 2026-11-09-flare-stack). */
        SizedUnit flare(String id, UnitEntry entry) {
            var r = readings(id);
            var load = FlareSizing.Load.of(stream(id, "in").mass(), r.getOrDefault("per_kilogram", 0.), r.getOrDefault("heating_value", 0.), r.getOrDefault("density", 0.),
                    r.getOrDefault("flame", com.wormzjl.icheme.science.fluid.network.Flare.AMBIENT), basis);
            return sized(entry, FlareSizing.Size.class, size -> FlareSizing.rate(load, size), () -> FlareSizing.auto(load));
        }

        /** A chimney on the gas its run read at the exit, its door's form and nearby height (batch 2026-11-10-chimney). */
        SizedUnit chimney(String id, UnitEntry entry, com.wormzjl.icheme.science.flowsheet.Params params) {
            var r = readings(id);
            var load = ChimneySizing.Load.of(com.wormzjl.icheme.science.flowsheet.unit.ChimneyUnit.form(params), r.getOrDefault("volume_flow", 0.),
                    params.number("nearby", com.wormzjl.icheme.science.flowsheet.unit.ChimneyUnit.NEARBY), basis);
            return sized(entry, ChimneySizing.Size.class, size -> ChimneySizing.rate(load, size), () -> ChimneySizing.auto(load));
        }

        /**
         * A batch still on what its batch read: the charge's volume, the heat over the cycle, its condenser's and its column's shares
         * (batch 2026-11-09-batch-distiller, W7). A door the run refused sizes nothing.
         */
        @Nullable SizedUnit still(String id, UnitEntry entry, com.wormzjl.icheme.science.flowsheet.Params params) {
            com.wormzjl.icheme.science.equipment.BatchStillOptions options;
            try {
                options = com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.options(params);
            } catch (com.wormzjl.icheme.science.flowsheet.FlowsheetFailure refused) {
                return null;
            }
            var r = readings(id);
            var load = StillSizing.Load.of(options, r.getOrDefault("charge_volume", 0.), r.getOrDefault("duty", 0.), r.getOrDefault("firing", 0.), r.getOrDefault("cycle", 0.),
                    r.getOrDefault("condensed", 1.), r.getOrDefault("flooded", 0.), r.getOrDefault("f_factor", 0.), basis);
            // The options are the door's: a kept pot takes them, so Send to block builds the still the run ran.
            var held = entry.size() instanceof StillSizing.Size kept ? new UnitEntry(kept.withOptions(options), entry.type(), entry.hold(), entry.kept()) : entry;
            return sized(held, StillSizing.Size.class, size -> StillSizing.rate(load, size), () -> StillSizing.auto(load));
        }

        /** A steam turbine on the shaft power its run gave, from its inlet to the tile's exhaust (Pa) (W8, D43). */
        SizedUnit turbine(String id, UnitEntry entry, double exhaust) {
            var load = TurbineSizing.Load.of(stream(id, "in"), readings(id).getOrDefault("power", 0.), exhaust, basis);
            return sized(entry, TurbineSizing.Size.class, size -> TurbineSizing.rate(load, size), () -> TurbineSizing.auto(load));
        }

        /** What the run read on unit {@code id}, nothing where it has no report. */
        Map<String, Double> readings(String id) {
            var report = run.reports().get(id);
            return report == null ? Map.of() : report.readings();
        }

        /** What a sized line loses over its length at design flow, bar; nothing with no size. */
        static double drop(SizedLine line) {
            return line.size() == null ? 0 : LineSizing.drop(line.line(), line.size(), line.line().length());
        }

        // ---- Lines ----

        SizedLine line(Flowsheet.Stream stream) {
            var flow = run.streams().get(stream.id());
            if (flow == null) return new SizedLine(LineSizing.Line.NONE, null, Rating.NONE);
            return line(stream.id(), flow, Service.Ends.of(sheet, stream), Math.min(nozzle(stream.from()), nozzle(stream.to())));
        }

        SizedLine line(String id, FlowStream flow, Service.Ends ends, double nozzle) {
            var entry = entries.line(id);
            var service = Service.of(flow, ends, thermo, entry.service());
            var line = LineSizing.Line.of(flow, service, basis, thermo, nozzle, entry.length(), scratch);
            var fixed = entry.size();
            if (fixed != null) return new SizedLine(line, fixed, LineSizing.rate(line, fixed));
            var choice = LineSizing.auto(line);
            return new SizedLine(line, choice.size(), choice.rating());
        }

        /** The largest bore {@code end}'s nozzle takes, as its unit stands sized: a column's by its port, a boot drum's oil outlet. */
        double nozzle(Flowsheet.@Nullable End end) {
            if (end == null) return PipeSizes.MAX_BORE;
            var block = blocks.get(end.unit());
            if (block != null) return block.nozzle(end.port());
            if (!end.port().equals(OIL)) return PipeSizes.MAX_BORE;
            var sized = units.get(end.unit());
            var shape = sized == null || sized.size() == null ? null : UnitSize.built(sized.size());
            return shape == null ? PipeSizes.MAX_BORE : oilNozzle(shape);
        }

        /** A drum's oil outlet: only a boot drum's, beside its saddle, holds a line back. */
        static double oilNozzle(EquipmentShape shape) {
            double bore = PipeSizes.MAX_BORE;
            for (int k = 0; k < shape.nozzles().size(); k++) if (shape.nozzles().get(k).role() == EquipmentShape.Role.LIQUID_OUTLET) bore = Math.min(bore, NozzleBore.proposed(shape, k));
            return bore;
        }

        // ---- Units on their inlet lines ----

        /** A unit that reads its inlet line, and those with no sizing yet. */
        SizedUnit last(Flowsheet.Unit unit) {
            String id = unit.id();
            var entry = entries.unit(id);
            return switch (unit.type()) {
                case "pump" -> pump(id, entry);
                case "valve" -> valve(id, entry);
                default -> SizedUnit.NONE;
            };
        }

        SizedUnit pump(String id, UnitEntry entry) {
            var sized = inlet(id);
            if (sized == null || sized.size() == null) return SizedUnit.NONE;
            var load = PumpSizing.Load.of(sized.line(), sized.size(), stream(id, "in"), stream(id, "out"), height(id), thermo);
            return Sizing.sized(entry, PumpSizing.Size.class, size -> PumpSizing.rate(load, size), () -> PumpSizing.auto(load));
        }

        @Nullable SizedLine inlet(String id) {
            var wired = wires.getOrDefault(id, Map.of()).get("in");
            return wired == null ? null : lines.get(wired);
        }

        /**
         * The head over the pump's suction, from the nozzle it draws by: a column's tray or sump, as the column stands sized; its
         * receiver's half level for the distillate, and its underside, over the boot, for the water; anything else at grade. Null
         * where the column has no size.
         */
        @Nullable Double height(String id) {
            var wired = wires.getOrDefault(id, Map.of()).get("in");
            var stream = wired == null ? null : streams.get(wired);
            return stream == null || stream.from() == null ? (Double) 0. : level(stream.from());
        }

        /**
         * How high over grade the liquid leaving by {@code from} stands, m: a column's tray or sump, as the column stands sized; its
         * receiver's half level for the distillate, and its underside, over the boot, for the water; null where the column has no
         * size; grade for anything else.
         */
        @Nullable Double level(Flowsheet.End from) {
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
        SizedUnit valve(String id, UnitEntry entry) {
            var sized = inlet(id);
            if (sized == null || sized.size() == null) return SizedUnit.NONE;
            return new SizedUnit(new ValveSizing.Size(sized.size().bore()), ValveSizing.rate(ValveSizing.Load.of(stream(id, "in"), stream(id, "out"), null)));
        }
    }
}
