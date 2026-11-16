package com.wormzjl.icheme.science.flowsheet.metallurgy;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Wiring;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.integrity.CorrosionDigest;
import com.wormzjl.icheme.science.integrity.CorrosionLaw;
import com.wormzjl.icheme.science.integrity.CorrosionStandard;
import com.wormzjl.icheme.science.integrity.CorrosionStandards;
import com.wormzjl.icheme.science.integrity.ErosionLaw;
import com.wormzjl.icheme.science.integrity.FoulingLaw;
import com.wormzjl.icheme.science.integrity.Mechanism;
import com.wormzjl.icheme.science.integrity.Metallurgy;
import com.wormzjl.icheme.science.integrity.WallState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The process simulator's Materials tab (batch 2026-11-16-fouling-corrosion, WP6): each unit of a solved sheet rated for each
 * material of construction, from the streams on its ports, by the world's own laws ({@link CorrosionLaw}, {@link ErosionLaw}). A
 * structure has one material, so its worst stream governs. A control valve has no wall: its row is its trim's life. Mixers,
 * splitters and filters are pipe fittings with no wall and get no row. The corrosive air (M9) is not rated: a sheet has no place in
 * the world. State, rebuilt with every rating, never saved: the player's choice is a sizing fact ({@code SizingFacts.UnitFacts#material}).
 * Pure; no Minecraft types.
 */
public final class SheetMetallurgy {
    private SheetMetallurgy() {
    }

    /**
     * Process hours per corrosion year: the server's default ({@code corrosion.yearProcessHours}, owner D3). The simulator is a client
     * tool and reads no server setting, so it rates at the default; a gate holds the two equal.
     */
    public static final double YEAR_PROCESS_HOURS = 20;
    /** The rates, mm/y, up to which a row's lamp is green and yellow (amber); above, red. */
    public static final double GREEN = .1, AMBER = .5;
    /** The sheet unit types that become a constructed structure with a fluid device, and so have a wall. */
    public static final Set<String> WALLED = Set.of("drum", "storage_tank", "exchanger", "kettle", "cooler", "fired_heater", "flare", "chimney",
            "batch_still", "column", "pump", "compressor", "ejector", "vacuum_pump", "combustion_engine", "gas_turbine", "steam_turbine");
    /** The types whose heat side erodes at its lines' speed where its streams carry solids. */
    public static final Set<String> HEAT_SIDES = Set.of("exchanger", "kettle", "cooler", "fired_heater");
    /** A control valve: a trim row, no wall. */
    public static final String VALVE = "valve";

    /**
     * What one stream on a unit's port brings to its wall.
     *
     * @param stream     its id on the run
     * @param digest     its digest ({@link CorrosionStandard#digest})
     * @param solidShare its inert solid mass share, 0 to 1 (crystals melt and do not count)
     * @param speed      its line's speed as the sizing has it, m/s; 0 for none
     */
    public record StreamService(String stream, CorrosionDigest digest, double solidShare, double speed) {
    }

    /**
     * One material on a unit.
     *
     * @param mechanism the governing mechanism, null where nothing acts
     * @param rate      mm/y
     * @param life      process hours until a new wall is through; infinite at rate 0
     */
    public record Rated(Metallurgy material, @Nullable Mechanism mechanism, double rate, double life, Lamp lamp) {
    }

    /**
     * A control valve's trim.
     *
     * @param drop   Pa
     * @param speed  m/s, {@link ErosionLaw#trimSpeed}
     * @param life   process hours until its health is gone; infinite without solids
     */
    public record Trim(double drop, double density, double solidShare, double speed, double life) {
    }

    /**
     * One row of the tab.
     *
     * @param temperature the hottest stream's, K
     * @param ratings     one per material, in {@link Metallurgy} order; empty for a valve
     * @param trim        a valve's trim, null for a wall
     */
    public record Row(String unit, String type, double temperature, List<Rated> ratings, @Nullable Trim trim) {
        public Row {
            ratings = List.copyOf(ratings);
        }

        public boolean wall() {
            return trim == null;
        }

        public Rated rated(Metallurgy material) {
            return ratings.get(material.ordinal());
        }

        /** Auto's choice ({@link SheetMetallurgy#auto}); carbon steel for a valve. */
        public Metallurgy auto() {
            return ratings.isEmpty() ? Metallurgy.CARBON_STEEL : SheetMetallurgy.auto(ratings);
        }

        /** The material built: {@code manual} where the player chose one, else Auto's. */
        public Metallurgy chosen(@Nullable Metallurgy manual) {
            return manual != null ? manual : auto();
        }
    }

    /** A sheet's rows, by unit id in sheet order. Empty before a steady run. */
    public record Rows(Map<String, Row> rows) {
        public static final Rows NONE = new Rows(Map.of());

        public Rows {
            rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
        }
    }

    // ---- The laws on a unit ----

    /** A wall's lamp at {@code rate}, mm/y. */
    public static Lamp lamp(double rate) {
        if (!(rate > GREEN)) return Lamp.GREEN;
        return rate <= AMBER ? Lamp.YELLOW : Lamp.RED;
    }

    /**
     * {@code material} on {@code streams}: the worst stream governs, as a structure has one material. A stream's rate is its inside
     * rate ({@link CorrosionLaw#inside}) plus, where {@code erodes} (a heat side) and it carries solids at a speed, its erosion
     * ({@link ErosionLaw#wall}); erosion governs where it is the larger part.
     */
    public static Rated rate(Metallurgy material, List<StreamService> streams, boolean erodes) {
        Mechanism governing = null;
        double worst = 0;
        for (var s : streams) {
            var inside = CorrosionLaw.inside(material, s.digest());
            double erosion = erodes ? ErosionLaw.wall(material, s.solidShare(), s.speed()) : 0;
            double rate = Math.min(CorrosionLaw.MAX_RATE, inside.rate() + erosion);
            if (rate > worst) {
                worst = rate;
                governing = erosion > inside.rate() ? Mechanism.EROSION : inside.mechanism();
            }
        }
        double life = WallState.lifeProcessSeconds(0, material.allowance(), worst, YEAR_PROCESS_HOURS) / 3600;
        return new Rated(material, governing, worst, life, lamp(worst));
    }

    /** Every material on {@code streams}, in {@link Metallurgy} order. */
    public static List<Rated> ratings(List<StreamService> streams, boolean erodes) {
        var out = new ArrayList<Rated>();
        for (var m : Metallurgy.values()) out.add(rate(m, streams, erodes));
        return out;
    }

    /** Auto: the cheapest material whose lamp is green; where none is, the lowest rate, the cheaper on a tie. */
    public static Metallurgy auto(List<Rated> ratings) {
        Rated best = null;
        for (var r : ratings)
            if (r.lamp() == Lamp.GREEN && (best == null || r.material().cost() < best.material().cost())) best = r;
        if (best != null) return best.material();
        for (var r : ratings)
            if (best == null || r.rate() < best.rate() || r.rate() == best.rate() && r.material().cost() < best.material().cost()) best = r;
        return best == null ? Metallurgy.CARBON_STEEL : best.material();
    }

    /** A control valve's trim at drop {@code dropPa} on a liquid of {@code density} with inert solid share {@code solidShare}. */
    public static Trim trim(double dropPa, double density, double solidShare) {
        double speed = ErosionLaw.trimSpeed(dropPa, density);
        double perSecond = ErosionLaw.trim(solidShare, speed, YEAR_PROCESS_HOURS);
        return new Trim(dropPa, density, solidShare, speed, perSecond > 0 ? 1 / perSecond / 3600 : Double.POSITIVE_INFINITY);
    }

    // ---- A solved sheet ----

    /**
     * Every row of {@code sheet} on {@code run}, whose streams stand on {@code thermo}'s axis (the network package's); a run that is not
     * steady has none. {@code sized}, the sizing on show, gives a heat side's line speeds; null for none (no erosion term).
     */
    public static Rows rate(Flowsheet drawn, FlowsheetRun run, FlowThermo thermo, SheetSizing.@Nullable Sized sized) {
        if (run.status() != FlowsheetRun.Status.STEADY) return Rows.NONE;
        // A passage's ends read as the roles they play, as the run and the sizing read them: a valve's in and out.
        var sheet = Wiring.roles(drawn);
        var standard = standard(thermo);
        var rows = new LinkedHashMap<String, Row>();
        for (var unit : sheet.units()) {
            thermo.checkpoint().run();
            boolean valve = unit.type().equals(VALVE);
            if (!valve && !WALLED.contains(unit.type())) continue;
            if (valve) {
                var in = stream(sheet, run, unit.id(), "in");
                var out = stream(sheet, run, unit.id(), "out");
                if (in == null || in.state() == null || in.empty()) continue;
                double drop = out == null ? 0 : in.pressure() - out.pressure();
                rows.put(unit.id(), new Row(unit.id(), unit.type(), in.temperature(), List.of(), trim(drop, density(in.state()), solidShare(in.state()))));
                continue;
            }
            var streams = new ArrayList<StreamService>();
            double hottest = 0;
            for (var id : streamsOn(sheet, run, unit.id())) {
                var s = run.streams().get(id);
                if (s == null || s.empty() || s.state() == null) continue;
                var state = s.state();
                if (standard == null || standard.size() != state.componentCount()) continue;
                streams.add(new StreamService(id, digest(standard, state), solidShare(state), speed(sized, id)));
                hottest = Math.max(hottest, state.temperature());
            }
            if (streams.isEmpty()) continue;
            rows.put(unit.id(), new Row(unit.id(), unit.type(), hottest, ratings(streams, HEAT_SIDES.contains(unit.type())), null));
        }
        return new Rows(rows);
    }

    /** The corrosion standard of {@code thermo}'s compound list, or null where it cannot be built. */
    public static @Nullable CorrosionStandard standard(FlowThermo thermo) {
        return CorrosionStandards.of(thermo.catalog(), thermo.packageId(), thermo.axis(), thermo.model().molecularWeights()).apply(thermo.axis());
    }

    /**
     * The design fouling deposit of a heat side whose stream enters as {@code in} and leaves as {@code out}, m²K/W (batch
     * 2026-11-16-fouling-corrosion, WP5, plan section 12): the deposit the walls grow towards on it,
     * {@link FoulingLaw#asymptote} of the hotter end's digest and solid share, as the walls read a side by the hotter end of
     * its passage. 0 for a clean service (gas, steam, a light clean liquid), and where no standard can be built.
     */
    public static double designFouling(@Nullable CorrosionStandard standard, FlowStream in, @Nullable FlowStream out) {
        var end = out == null || out.empty() || out.state() == null || in.state() != null && !in.empty() && in.temperature() >= out.temperature() ? in : out;
        if (standard == null || end.empty() || end.state() == null || standard.size() != end.state().componentCount()) return 0;
        return FoulingLaw.asymptote(digest(standard, end.state()), solidShare(end.state()));
    }

    /** {@code state}'s digest: its oil liquid and aqueous liquid as the walls read them (WP2). */
    public static CorrosionDigest digest(CorrosionStandard standard, FluidThermodynamics.State state) {
        var v = state.vaporAmountsView();
        var l = state.liquidAmountsView();
        var a = state.aqueousAmountsView();
        var total = new double[v.length];
        for (int c = 0; c < total.length; c++) total[c] = v[c] + l[c] + a[c];
        return standard.digest(total, state.temperature(), state.pressure(), l, a);
    }

    /** The inert solids' share of {@code state}'s mass, 0 to 1. */
    static double solidShare(FluidThermodynamics.State state) {
        double solids = state.solids().withoutCrystals().massKg(), mass = state.mass();
        if (!(solids > 0) || !(mass > 0)) return 0;
        double share = solids / mass;
        return Double.isFinite(share) ? Math.min(share, 1) : 0;
    }

    private static double density(FluidThermodynamics.State state) {
        double v = state.volume();
        return v > 0 ? state.mass() / v : 0;
    }

    /** A line's speed as {@code sized} has it, m/s: its volume flow over its runs' bore; 0 with no size. */
    private static double speed(SheetSizing.@Nullable Sized sized, String stream) {
        var line = sized == null ? null : sized.lines().get(stream);
        if (line == null || line.size() == null || !line.line().flows()) return 0;
        double bore = line.size().bore();
        double v = line.line().volume() / (line.size().runs() * Math.PI * bore * bore / 4);
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    /** The streams on {@code unit}'s ports: those wired to it, and its free outlets ({@code unit:port}), in sheet order. */
    static List<String> streamsOn(Flowsheet sheet, FlowsheetRun run, String unit) {
        var ids = new LinkedHashSet<String>();
        for (var stream : sheet.streams())
            if (stream.from() != null && stream.from().unit().equals(unit) || stream.to() != null && stream.to().unit().equals(unit)) ids.add(stream.id());
        String free = unit + ":";
        for (var id : run.streams().keySet()) if (id.startsWith(free)) ids.add(id);
        return List.copyOf(ids);
    }

    /** What is on {@code unit}'s {@code port}: its wired stream, or its free outlet's own. */
    private static @Nullable FlowStream stream(Flowsheet sheet, FlowsheetRun run, String unit, String port) {
        for (var stream : sheet.streams()) {
            if (stream.from() != null && stream.from().unit().equals(unit) && stream.from().port().equals(port)) return run.streams().get(stream.id());
            if (stream.to() != null && stream.to().unit().equals(unit) && stream.to().port().equals(port)) return run.streams().get(stream.id());
        }
        return run.streams().get(unit + ":" + port);
    }
}
