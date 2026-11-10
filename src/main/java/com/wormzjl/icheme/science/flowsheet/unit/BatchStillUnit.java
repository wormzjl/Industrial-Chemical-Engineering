package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Board;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.science.fluid.network.StillColumn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * A batch still (batch 2026-11-09-batch-distiller, W7; {@code BATCH_STILL_SIMULATOR.md}): the steady sheet's view of a still that runs
 * one batch a cycle. Its inlet fills a charge tank over the cycle, so the charge is the inlet's flow times the cycle time, of the
 * inlet's composition; its calculation runs that batch on the world's law ({@link StillBatch}, {@link BatchStill}, {@link StillColumn}),
 * and each cut, the vent and the residue leave as the batch's moles and enthalpy over the cycle: cycle-averaged flows.
 *
 * <p>Its door: the pot's volume, the heater and its firing, the built-in condenser and its water's temperature (at the condenser's
 * design flow), the column's stages and the reflux ratio, the cycle time, and the cuts, each ended by the head's temperature or by the
 * batch's time. The heat into the pot crosses the sheet's boundary as the fuel's (a fired heater's fuel stays off the sheet too); the
 * condenser's duty leaves it to the water. Both are the law's, so the unit's boundary closes energy and each component exactly.</p>
 *
 * <p>The charge starts at the inlet's enthalpy let down to the pot's pressure. A charge above its bubble point at the pot's pressure
 * flashes as it enters: the pot starts at its bubble point and the excess is booked as the charge's own duty, with a warning. Water and
 * solids refuse the unit: the still's column takes hydrocarbons only.</p>
 */
public final class BatchStillUnit implements UnitModel {
    /** A dropped still's pot (m³), cycle (s), reflux ratio, firing, condenser water (K) and its two cuts' head temperatures (K). */
    public static final double VOLUME = .5, CYCLE = 7200, REFLUX = 2, FIRING = 1, WATER = 298.15, FIRST_CUT = 333.15, SECOND_CUT = 393.15;
    /** The pot's pressure: it vents to the atmosphere. */
    public static final double PRESSURE = BatchStill.ATMOSPHERIC;
    /** The ports: the charge in, a cut's {@code cut/<id>}, the vent and the residue (the pot drained). */
    public static final String CHARGE = "charge", CUT = "cut/", VENT = "vent", RESIDUE = "residue", SERIES = "batch";
    /** The curve's composition channels: at most this many components, each at least this share of what the batch distilled. */
    static final int MOST_COMPONENTS = 5;
    static final double LEAST_SHARE = .01;
    /** Past this share of its volume a pot boils over into its neck (plan section 6, item 6). */
    public static final double PUKE = .75;
    /** The let-down's search goes no colder than this, K: a numerical floor, under every charge the network carries. */
    private static final double LOWEST = 100;

    /** The door's cut rows, or a dropped still's two. */
    public static List<Params> cutRows(Params params) {
        var rows = params.rows("cuts");
        if (!rows.isEmpty()) return rows;
        var defaults = new ArrayList<Params>();
        for (double head : new double[]{FIRST_CUT, SECOND_CUT}) {
            var row = Params.none();
            row.json().addProperty("id", String.valueOf(defaults.size() + 1));
            row.json().addProperty("end", "head");
            row.json().addProperty("value", head);
            defaults.add(row);
        }
        return defaults;
    }

    public static boolean condenses(Params params) {
        return params.text("condenser", "built_in").equals("built_in");
    }

    @Override public List<Port> ports(Params params) {
        var ports = new ArrayList<Port>();
        ports.add(Port.in(CHARGE));
        for (var row : cutRows(params)) ports.add(Port.out(CUT + row.text("id", "")));
        if (condenses(params)) ports.add(Port.out(VENT));
        ports.add(Port.out(RESIDUE));
        return ports;
    }

    /** The still's design from its door: refused as a setting where the world's still cannot be built so. */
    static BatchStill.Design design(Params p) {
        double volume = p.number("volume", VOLUME);
        var heater = switch (p.text("heater", "solid")) {
            case "solid" -> BatchStill.Heater.SOLID;
            case "liquid" -> BatchStill.Heater.LIQUID;
            case "electric" -> BatchStill.Heater.ELECTRIC;
            default -> throw new FlowsheetFailure("unit.setting", "heater");
        };
        boolean condenser = switch (p.text("condenser", "built_in")) {
            case "built_in" -> true;
            case "none" -> false;
            default -> throw new FlowsheetFailure("unit.setting", "condenser");
        };
        int stages = p.integer("stages", 0);
        // A column takes its reflux from the built-in condenser (owner O1 of BATCH_STILL_STRUCTURE.md).
        if (stages != 0 && (stages < BatchStill.MIN_STAGES || stages > StillColumn.MAX_STAGES || !condenser)) throw new FlowsheetFailure("unit.setting", "stages");
        if (!(volume > 0)) throw new FlowsheetFailure("unit.setting", "volume");
        return new BatchStill.Design(volume, heater, condenser, stages);
    }

    /** The world's options of the still the door runs: what Send to block builds. */
    public static com.wormzjl.icheme.science.equipment.BatchStillOptions options(Params p) {
        var design = design(p);
        var heater = switch (design.heater()) {
            case SOLID -> com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.SOLID;
            case LIQUID -> com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.LIQUID;
            case ELECTRIC -> com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.ELECTRIC;
        };
        var condenser = design.condenser() ? com.wormzjl.icheme.science.equipment.BatchStillOptions.Condenser.BUILT_IN : com.wormzjl.icheme.science.equipment.BatchStillOptions.Condenser.NONE;
        return new com.wormzjl.icheme.science.equipment.BatchStillOptions(heater, condenser, design.stages());
    }

    /** The cuts from the door's rows, in order. */
    static List<StillBatch.Cut> cuts(Params p) {
        var rows = cutRows(p);
        if (rows.size() > StillBatch.MOST_CUTS) throw new FlowsheetFailure("unit.setting", "cuts");
        var cuts = new ArrayList<StillBatch.Cut>();
        for (var row : rows) {
            var end = switch (row.text("end", "head")) {
                case "head" -> StillBatch.End.HEAD;
                case "time" -> StillBatch.End.TIME;
                default -> throw new FlowsheetFailure("unit.setting", "cuts");
            };
            double value = row.number("value");
            if (!(value > 0)) throw new FlowsheetFailure("unit.setting", "cuts");
            cuts.add(new StillBatch.Cut(row.text("id", ""), end, value));
        }
        return cuts;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var p = u.params();
        var outlets = new ArrayList<String>();
        for (var port : ports(p)) if (port.direction() == Port.Direction.OUT) outlets.add(port.id());
        var writes = outlets.stream().map(u::out).toList();
        return List.of(u.calculation(List.of(u.wire(CHARGE)), writes, (board, report) -> {
            var in = board.stream(u.wire(CHARGE));
            if (in.empty()) {
                for (var port : outlets) u.write(board, report, port, FlowStream.empty(u.thermo().axis().size()));
                return;
            }
            run(u, in, board, report);
        }));
    }

    /** What one cycle of the still gives, from {@code in}. */
    private static void run(WiredUnit u, FlowStream in, Board board, Report report) {
        var p = u.params();
        var design = design(p);
        var cuts = cuts(p);
        double cycle = p.number("cycle", CYCLE), firing = p.number("firing", FIRING), reflux = p.number("reflux", REFLUX), water = p.number("water", WATER);
        if (!(cycle > 0)) throw new FlowsheetFailure("unit.setting", "cycle");
        if (!(firing > 0 && firing <= 1)) throw new FlowsheetFailure("unit.setting", "firing");
        if (!(reflux >= 0) || !Double.isFinite(reflux)) throw new FlowsheetFailure("unit.setting", "reflux");
        if (!(water > 0)) throw new FlowsheetFailure("unit.setting", "water");
        var thermo = u.thermo();
        if (!in.solids().empty()) throw new FlowsheetFailure("still.solids");
        int axis = thermo.axis().size(), waterAt = thermo.water();
        double[] flows = in.amountsView();
        if (flows[waterAt] > 0) throw new FlowsheetFailure("still.water");
        var column = new StillColumn(thermo.model().hydrocarbon, PRESSURE);
        int n = column.componentCount();
        if (n != axis - 1) throw new IllegalStateException("The still's basis is the sheet's less water");
        // The charge: what the inlet brings over one cycle, let down into the pot at its enthalpy.
        double[] charge = new double[n];
        double moles = 0;
        for (int i = 0; i < n; i++) moles += charge[i] = flows[i] * cycle;
        double perMole = in.enthalpy() * cycle / moles;
        var bubble = column.bubble(charge, Double.NaN);
        if (bubble == null) throw new FlowsheetFailure("still.no_bubble");
        double tb = bubble.temperature();
        double start = letDown(column, charge, perMole, in.temperature(), tb);
        if (start >= tb && perMole > column.liquidEnthalpy(tb, charge) * (1 + 1e-9))
            report.note(Note.warning(u.id(), "still.hot_charge", String.valueOf(in.temperature()), String.valueOf(tb)));
        double potStart = moles * column.liquidEnthalpy(start, charge);
        double volume = moles * column.liquidVolume(start, charge), fill = volume / design.volume();
        if (fill > 1) throw new FlowsheetFailure("still.overfull", litres(volume), litres(design.volume()));
        if (fill > PUKE) report.note(Note.warning(u.id(), "still.full", percent(fill)));
        var cooling = design.condenser() ? new BatchStill.Cooling(water, BatchStill.designWaterFlow(design.rating()) * BatchStill.WATER_HEAT_CAPACITY) : null;
        var batch = StillBatch.run(column, new StillBatch.Settings(design, firing, reflux, cooling, cuts), charge, start, thermo.checkpoint());
        if (batch.finish() == StillBatch.Finish.UNFINISHED) throw new FlowsheetFailure("still.unfinished", String.valueOf(batch.steps()), hours(batch.seconds()));
        // Each outlet over the cycle, at the pot's pressure, holding the law's enthalpy.
        double out = 0;
        for (var taken : batch.cuts()) {
            var stream = outlet(u, taken.moles(), taken.enthalpy(), cycle, axis, tb);
            u.write(board, report, CUT + taken.id(), stream);
            out += stream.enthalpy();
        }
        if (design.condenser()) {
            var vent = outlet(u, batch.vent(), batch.ventEnthalpy(), cycle, axis, tb);
            u.write(board, report, VENT, vent);
            out += vent.enthalpy();
        }
        var end = batch.end();
        double residueEnthalpy = end.total() > 0 ? end.total() * column.liquidEnthalpy(end.temperature(), end.moles()) : 0;
        var residue = outlet(u, end.moles(), residueEnthalpy, cycle, axis, end.temperature());
        u.write(board, report, RESIDUE, residue);
        out += residue.enthalpy();
        // The boundary: the heat into the pot, the condenser's duty to its water and the charge's own let-down; their sum closes the unit.
        report.duty(out - in.enthalpy());
        double charged = (potStart - in.enthalpy() * cycle) / cycle;
        report.reading("duty", batch.heat() / cycle);
        report.reading("fuel", batch.fuel() / cycle);
        report.reading("condenser", batch.condenser() / cycle);
        report.reading("charge_heat", charged);
        report.reading("warming", batch.warming() / cycle);
        report.reading("rated", design.rating());
        report.reading("firing", firing);
        report.reading("cycle", cycle);
        report.reading("batch", batch.seconds());
        report.reading("boiled", batch.boiled());
        report.reading("charge", moles * column.molarMass(fractions(charge)));
        report.reading("charge_volume", volume);
        report.reading("fill", fill);
        report.reading("condensed", batch.condensed());
        report.reading("flooded", batch.seconds() > 0 ? batch.flooded() / batch.seconds() : 0);
        report.reading("f_factor", batch.fFactor());
        report.reading("pot_end", end.temperature());
        report.reading("steps", batch.steps());
        double distilled = 0, chargedMass = 0;
        for (int i = 0; i < n; i++) {
            chargedMass += charge[i] * column.molarMass(i);
            distilled += (charge[i] - end.moles()[i]) * column.molarMass(i);
        }
        report.reading("distilled", chargedMass > 0 ? distilled / chargedMass : 0);
        report.series(SERIES, series(column, batch, thermo.axis()));
        notes(u, report, batch, cuts, cycle, design);
    }

    /** The warnings a finished batch leaves. */
    private static void notes(WiredUnit u, Report report, StillBatch.Batch batch, List<StillBatch.Cut> cuts, double cycle, BatchStill.Design design) {
        String id = u.id();
        switch (batch.finish()) {
            case DRY -> report.note(Note.warning(id, "still.dry", cutName(batch, cuts)));
            case OVERHEAT -> report.note(Note.warning(id, "still.overheat", String.valueOf(BatchStill.OVERHEAT)));
            default -> {
            }
        }
        if (batch.seconds() > cycle) report.note(Note.warning(id, "still.cycle", hours(batch.seconds()), hours(cycle)));
        if (design.condenser() && batch.condensed() < 1 - 1e-6) report.note(Note.warning(id, "still.cooling", percent(1 - batch.condensed())));
        if (batch.flooded() > 0) report.note(Note.warning(id, "still.flooding", percent(batch.flooded() / batch.seconds())));
        if (batch.failure() != null) report.note(Note.warning(id, "still.column", batch.failure()));
    }

    /** The cut that was running when the batch ended early. */
    private static String cutName(StillBatch.Batch batch, List<StillBatch.Cut> cuts) {
        for (var taken : batch.cuts()) if (taken.to() >= batch.seconds() && taken.from() < batch.seconds()) return taken.id();
        return cuts.getLast().id();
    }

    /** {@code moles} and {@code enthalpy} (J) over {@code cycle} s as a stream at the pot's pressure, on the sheet's axis, water none. */
    private static FlowStream outlet(WiredUnit u, double[] moles, double enthalpy, double cycle, int axis, double guess) {
        double[] amounts = new double[axis];
        double total = 0;
        for (int i = 0; i < moles.length; i++) total += amounts[i] = Math.max(0, moles[i]) / cycle;
        if (!(total > 0)) return FlowStream.empty(axis);
        return u.thermo().atEnthalpy(PRESSURE, enthalpy / cycle, amounts, guess, com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY);
    }

    /**
     * The pot's start temperature: the charge's liquid at {@code perMole} J/mol let down to the pot's pressure, at most its bubble point
     * {@code bubble} K, where a hotter charge flashes. A bisection on the liquid's enthalpy, from below the inlet's temperature.
     */
    static double letDown(StillColumn column, double[] charge, double perMole, double inlet, double bubble) {
        double high = bubble;
        if (perMole >= column.liquidEnthalpy(high, charge)) return high;
        double low = Math.max(LOWEST, Math.min(inlet, high) - 20);
        while (low > LOWEST && column.liquidEnthalpy(low, charge) > perMole) low = Math.max(LOWEST, low - 40);
        if (column.liquidEnthalpy(low, charge) > perMole) return low;
        for (int iteration = 0; iteration < 60 && high - low > 1e-7; iteration++) {
            double mid = (low + high) / 2;
            if (column.liquidEnthalpy(mid, charge) > perMole) high = mid;
            else low = mid;
        }
        return (low + high) / 2;
    }

    /** The batch's curve: pot and head temperatures (K), the rate to the spout (kg/s), the cut, and the distillate's main components (mole fractions). */
    static Report.Series series(StillColumn column, StillBatch.Batch batch, List<String> axis) {
        var points = batch.points();
        int count = points.size(), n = column.componentCount();
        double[] time = new double[count], pot = new double[count], head = new double[count], rate = new double[count], cut = new double[count];
        double[] distilled = new double[n];
        for (var taken : batch.cuts()) for (int i = 0; i < n; i++) distilled[i] += taken.moles()[i];
        for (int i = 0; i < n; i++) distilled[i] += batch.vent()[i];
        double all = Arrays.stream(distilled).sum();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (all > 0 && distilled[i] >= LEAST_SHARE * all) order.add(i);
        order.sort((a, b) -> Double.compare(distilled[b], distilled[a]));
        var shown = order.subList(0, Math.min(MOST_COMPONENTS, order.size()));
        double[][] shares = new double[shown.size()][count];
        for (int k = 0; k < count; k++) {
            var point = points.get(k);
            time[k] = point.time();
            pot[k] = point.pot();
            head[k] = point.head();
            var x = point.composition();
            rate[k] = x == null ? 0 : point.rate() * column.molarMass(x);
            cut[k] = point.cut() < 0 ? Double.NaN : point.cut() + 1;
            for (int c = 0; c < shown.size(); c++) shares[c][k] = x == null ? Double.NaN : x[shown.get(c)];
        }
        var channels = new LinkedHashMap<String, double[]>();
        channels.put("pot", pot);
        channels.put("head", head);
        channels.put("rate", rate);
        channels.put("cut", cut);
        for (int c = 0; c < shown.size(); c++) channels.put("x:" + axis.get(shown.get(c)), shares[c]);
        return new Report.Series(time, channels);
    }

    private static double[] fractions(double[] moles) {
        double sum = Arrays.stream(moles).sum();
        double[] x = new double[moles.length];
        for (int i = 0; i < x.length; i++) x[i] = moles[i] / sum;
        return x;
    }

    private static String hours(double seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds / 3600);
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f", 100 * share);
    }

    private static String litres(double cubicMetres) {
        return String.format(Locale.ROOT, "%.0f", 1000 * cubicMetres);
    }
}
