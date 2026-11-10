package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Board;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit.*;

/**
 * The column block's own solve ({@link ColumnUnit}). It keeps the last state, so each pass starts from it, and leaves it on its
 * report, so a run that starts from a steady one starts the column where it ended there (owner 2026-11-04, P2).
 */
final class ColumnBlockSolve implements Calculation.Law {
    /**
     * The feed is flashed to its tray's pressure, which only a solve can say, so it starts at the stream's own and goes round
     * while that moves the feed by more than this (K), at most this often. A stream at its tray's pressure solves once.
     */
    private static final double FEED_TEMPERATURE = .01;
    private static final int FEED_ROUNDS = 3;
    /** Another unit's stripping steam may differ from its row's rate by this share of the larger, the world's steam band, before the column says so (U1b). */
    private static final double STEAM_BAND = .02;
    private static final String FREE_WATER = "free_water";

    /** The ejector's motive steam, the free water it joins, and the heat it gives the condenser (W). */
    private record Ejected(FlowStream steam, FlowStream water, double heat) {
    }

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

    ColumnBlockSolve(WiredUnit u, ColumnDoor door) {
        this.u = u;
        this.door = door;
        components = u.thermo().axis().size();
        // Where the column's solve ended in the steady run this one starts from, while its package stands at that revision.
        var left = u.last() == null ? null : u.last().warm();
        if (left != null && left.current(u.thermo().catalog())) {
            warm = left.seed();
            feedTray = left.feedTray();
            stages.putAll(left.stages());
        }
    }

    /** Leaves where the column now stands on {@code report}, for a run that starts from this one. */
    private void leave(Report report) {
        if (warm != null) report.warm(new Report.Warm(warm, feedTray, stages));
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
            leave(report);
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
        leave(report);
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
        if (door.ejector().vacuum().pump()) {
            vacuumPump(report, load);
            return null;
        }
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
     * A vacuum pump on the receiver's vapour outlet (batch 2026-11-10-vacuum-pump, owner V5): it takes the vent as it leaves the
     * receiver, after the condenser, so no steam joins the free water and the condenser's duty stands as V3 gives it. Its model is
     * the smallest that holds the vent with its spare; its shaft power the world's curve's at that suction, to the atmosphere.
     */
    private void vacuumPump(Report report, FlowStream load) {
        double volume = load.state().volume(), pressure = load.pressure();
        var pick = VacuumPumpUnit.pick(door.ejector().vacuum(), pressure, volume);
        if (pick.model() == 0) report.note(Note.warning(u.id(), "vacuum_pump.capacity"));
        var law = pick.law();
        double density = load.mass() / volume, k = u.thermo().model().heatCapacityRatio(load.state());
        report.reading("vacuum_model", pick.model() == 0 ? VacuumPumpUnit.models(pick.type()) : pick.model());
        report.reading("vacuum_stages", pick.type() == com.wormzjl.icheme.science.machine.VacuumPump.Type.LIQUID_RING_TWO_STAGE ? 2 : 1);
        report.reading("vacuum_power", law.shaftPower(volume, 1, density, pressure, k));
        report.reading("vacuum_capacity", law.suction(pressure, 1));
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
            var entered = thermo.atTemperature(row.temperature(), pressure, thermo.waterAlone(row.flow()), SolidInventory.EMPTY);
            if (wired == null) report.enters(entered.mass(), entered.enthalpy());
            return entered;
        }
        if (wired.amountsView()[thermo.water()] != wired.total()) throw new FlowsheetFailure("column.steam_water", row.id());
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
        double kilograms = u.thermo().waterMass();
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

    /**
     * The column on the sheet's package, the network's view of the sheet's compounds ({@code SheetCompounds}): the sheet's axis is
     * its compounds, then water, which the caller has moved to the steam. The learned start binds by the feed's own compounds.
     */
    private V3ColumnInput input(double[] amounts, double temperature, List<V3SteamFeedSpec> steam) {
        var thermo = u.thermo();
        var pack = thermo.catalog().requirePackage(thermo.packageId());
        try {
            return door.input(pack, java.util.Arrays.copyOf(amounts, pack.components().size()), temperature, steam);
        } catch (IllegalArgumentException refused) {
            throw new FlowsheetFailure("column.failed", String.valueOf(refused.getMessage()));
        }
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
            for (var fraction : stream.moleFractions()) add(amounts, axis, fraction.componentId(), fraction.moleFraction(), stream.molarFlowMolPerSecond());
            return u.thermo().atTemperature(stream.temperatureKelvin(), stream.pressurePascal(), amounts, SolidInventory.EMPTY);
        }
        return FlowStream.empty(components);
    }
}
