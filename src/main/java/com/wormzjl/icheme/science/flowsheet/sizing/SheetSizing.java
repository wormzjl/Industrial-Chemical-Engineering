package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A whole sheet's lines and units, sized and rated (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1 and 3.5).
 * The order is the physics': a unit's nozzles bound its lines, so units that read no line come first; a pump's NPSH and a valve's bore
 * read their inlet lines, so those units come last. It runs on the run's thread, inside {@code MaterialRuntime.with}: its one scratch
 * is local, and {@code thermo}'s checkpoint is asked between items, so Stop ends it there. It reads steady streams and writes nothing:
 * on a run that is not steady every item is red on {@link Limit#RUN_STEADY}, with no size.
 * A column waits for S3, and has no size and no lamp until then.
 */
public final class SheetSizing {
    private static final String OIL = "oil", WATER = "water", GAS = "gas", COLUMN = "column";

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
     * @param size   null for Auto
     * @param type   null for the proposal
     * @param height a pump's static head over grade, m: the column's nozzle's, S3's; on anything but a column 0 by default
     * @param path   what a valve's whole path loses, bar: S4's
     * @param spare  the pressure a heater's path has spare, bar: S4's
     * @param hold   a drum's entry, min, or a tank's, h: S4's; null for the row's
     */
    public record UnitEntry(@Nullable UnitSize size, @Nullable UnitType type, @Nullable Double height, @Nullable Double path, @Nullable Double spare, @Nullable Double hold) {
        public static final UnitEntry AUTO = new UnitEntry(null, null, null, null, null, null);
    }

    /** What a sizing reads beyond the run. An item with no entry is on Auto. */
    public record Entries(DesignBasis basis, Map<String, LineEntry> lines, Map<String, UnitEntry> units) {
        public static final Entries DEFAULT = new Entries(DesignBasis.DEFAULT, Map.of(), Map.of());

        public Entries {
            lines = Map.copyOf(lines);
            units = Map.copyOf(units);
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
     * A sheet, sized.
     *
     * @param lines by stream, in sheet order
     * @param units by unit, in sheet order
     */
    public record Sized(Map<String, SizedLine> lines, Map<String, SizedUnit> units) {
    }

    /** {@code run}'s streams on {@code sheet}, read on {@code thermo}'s own package. */
    public static Sized size(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, Entries entries) {
        return new Sizing(sheet, run, thermo, entries).all();
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

        Sizing(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, Entries entries) {
            this.sheet = sheet;
            this.run = run;
            this.thermo = thermo;
            this.entries = entries;
            basis = entries.basis();
            for (var stream : sheet.streams()) {
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
            }
            return new Sized(Collections.unmodifiableMap(lines), Collections.unmodifiableMap(ordered));
        }

        /** A run that failed, stopped or did not close: what it left is no steady state, so every item holds one red reading and no size. */
        private Sized unsteady() {
            var rating = new Rating(List.of(Limit.RUN_STEADY.read(0)));
            for (var stream : sheet.streams()) lines.put(stream.id(), new SizedLine(LineSizing.Line.NONE, null, rating));
            var ordered = new LinkedHashMap<String, SizedUnit>();
            for (var unit : sheet.units()) ordered.put(unit.id(), new SizedUnit(null, rating));
            return new Sized(Collections.unmodifiableMap(lines), Collections.unmodifiableMap(ordered));
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
            var load = ExchangerSizing.Load.of(kettle, stream(id, "tube_in"), stream(id, "tube_out"), stream(id, kettle ? "feed" : "shell_in"), shellOut, basis, thermo, scratch);
            return sized(entry, ExchangerSizing.Size.class, size -> ExchangerSizing.rate(load, size), () -> ExchangerSizing.auto(load));
        }

        private SizedUnit airCooler(String id, UnitEntry entry) {
            var load = AirCoolerSizing.Load.of(stream(id, "in"), stream(id, "out"), basis, thermo, scratch);
            return sized(entry, AirCoolerSizing.Size.class, size -> AirCoolerSizing.rate(load, size), () -> AirCoolerSizing.auto(load));
        }

        /** A heater's coils in series are one pair; set apart, each takes its share of the fire. */
        private SizedUnit heater(String id, UnitEntry entry) {
            double heat = 0;
            for (var coil : new String[]{"", "convection_", "radiant_"}) {
                var in = stream(id, coil + "in");
                if (!in.empty()) heat += stream(id, coil + "out").enthalpy() - in.enthalpy();
            }
            var load = new HeaterSizing.Load(heat * basis.designFlow() / 1e6, entry.spare());
            var type = entry.type();
            return sized(entry, HeaterSizing.Size.class, size -> HeaterSizing.rate(load, size), () -> type != null ? HeaterSizing.auto(load, type) : HeaterSizing.auto(load));
        }

        private SizedUnit compressor(String id, UnitEntry entry) {
            var load = CompressorSizing.Load.of(stream(id, "in"), stream(id, "out"), basis, thermo, scratch);
            var type = entry.type();
            return sized(entry, CompressorSizing.Size.class, size -> CompressorSizing.rate(load, size), () -> type != null ? CompressorSizing.auto(load, type) : CompressorSizing.auto(load));
        }

        private SizedUnit drum(String id, UnitEntry entry) {
            var load = DrumSizing.Load.of(stream(id, GAS), stream(id, OIL), stream(id, WATER), entry.hold(), basis, thermo, scratch);
            var type = entry.type();
            return sized(entry, DrumSizing.Size.class, size -> DrumSizing.rate(load, size), () -> type != null ? DrumSizing.auto(load, type) : DrumSizing.auto(load));
        }

        private SizedUnit tank(String id, UnitEntry entry) {
            var load = TankSizing.Load.of(stream(id, OIL), stream(id, WATER), entry.hold(), basis, thermo);
            var type = entry.type();
            return sized(entry, TankSizing.Size.class, size -> TankSizing.rate(load, size), () -> type != null ? TankSizing.auto(load, type) : TankSizing.auto(load));
        }

        private SizedLine line(Flowsheet.Stream stream) {
            var flow = run.streams().get(stream.id());
            if (flow == null) return new SizedLine(LineSizing.Line.NONE, null, Rating.NONE);
            var entry = entries.line(stream.id());
            var service = Service.of(flow, Service.Ends.of(sheet, stream), thermo, entry.service());
            var line = LineSizing.Line.of(flow, service, basis, thermo, Math.min(nozzle(stream.from()), nozzle(stream.to())), entry.length(), scratch);
            var fixed = entry.size();
            if (fixed != null) return new SizedLine(line, fixed, LineSizing.rate(line, fixed));
            var choice = LineSizing.auto(line);
            return new SizedLine(line, choice.size(), choice.rating());
        }

        /** The largest bore {@code end}'s nozzle takes, as its unit stands sized. Only a boot drum's oil outlet holds a line back. */
        private double nozzle(Flowsheet.@Nullable End end) {
            if (end == null || !end.port().equals(OIL)) return PipeSizes.MAX_BORE;
            var sized = units.get(end.unit());
            var shape = sized == null || sized.size() == null ? null : UnitSize.built(sized.size());
            double bore = PipeSizes.MAX_BORE;
            if (shape != null) for (int k = 0; k < shape.nozzles().size(); k++) if (shape.nozzles().get(k).role() == EquipmentShape.Role.LIQUID_OUTLET) bore = Math.min(bore, NozzleBore.largest(shape, k));
            return bore;
        }

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

        /** The head over the pump's suction: only a nozzle's height counts, a column's is S3's, and anything else stands at grade. */
        private @Nullable Double height(String id, UnitEntry entry) {
            if (entry.height() != null) return entry.height();
            var wired = wires.getOrDefault(id, Map.of()).get("in");
            for (var stream : sheet.streams())
                if (stream.id().equals(wired) && stream.from() != null) for (var unit : sheet.units()) if (unit.id().equals(stream.from().unit()) && unit.type().equals(COLUMN)) return null;
            return 0.;
        }

        private SizedUnit valve(String id, UnitEntry entry) {
            var sized = inlet(id);
            if (sized == null || sized.size() == null) return SizedUnit.NONE;
            return new SizedUnit(new ValveSizing.Size(sized.size().bore()), ValveSizing.rate(ValveSizing.Load.of(stream(id, "in"), stream(id, "out"), entry.path())));
        }
    }
}
