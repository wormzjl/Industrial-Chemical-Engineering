package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing.*;
import static com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing.Sizing.*;

/** One sizing's column and its block ({@link SheetSizing}; {@code SIZING_PLAN.md} section 3.6): the column at its diameter, then its members and their lines. */
final class BlockSizing {
    private final Sizing sizing;
    private final FlowsheetRun run;
    private final FlowThermo thermo;
    private final Entries entries;
    private final DesignBasis basis;
    private final MixtureViscosity.Workspace scratch;
    private final Map<String, Map<String, String>> wires;
    private final Map<String, Block> blocks;
    private final Map<String, Map<String, SizedUnit>> members;
    private final Map<String, SizedLine> memberLines;
    private final Map<String, ColumnComponents.Condenser> condensers;

    BlockSizing(Sizing sizing) {
        this.sizing = sizing;
        run = sizing.run;
        thermo = sizing.thermo;
        entries = sizing.entries;
        basis = sizing.basis;
        scratch = sizing.scratch;
        wires = sizing.wires;
        blocks = sizing.blocks;
        members = sizing.members;
        memberLines = sizing.memberLines;
        condensers = sizing.condensers;
    }

    /** The column at the diameter Auto or the player gives, then its members: a column whose run shows no profile has no size. */
    SizedUnit column(String id, UnitEntry entry, Params params) {
        var report = run.reports().get(id);
        var display = report == null ? null : report.display();
        if (display == null) return SizedUnit.NONE;
        var door = ColumnDoor.of(params);
        var load = ColumnSizing.Load.of(door, display, sizing.stream(id, ColumnUnit.BOTTOMS), basis, thermo);
        if (load == null) return new SizedUnit(null, new Rating(List.of(UnitSize.reading(null))));
        var warm = report.warm();
        // The trials solve on the package the run's own solve did: the sizing's, the network's view of the sheet's compounds.
        var trials = new ColumnSizing.Trials(load, thermo.catalog(), thermo.checkpoint(), warm != null && warm.current(thermo.catalog()) ? warm.seed() : null);
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
     * circulation its fall gives. Each cooler's cold side is the sheet's, at the door's duty, or tower water where its port is free
     * (C2). An air-then-water condenser has its air cooler to the split and its water trim below it, each a member.
     */
    void members(String id, Block block) {
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
        // Where the top is under ambient pressure, the ejector set lifts what the condenser vents, the overhead at the condenser's
        // temperature and pressure (W10b), and its motive steam condenses with the vapour: the condenser's duty already holds the
        // steam's heat.
        var readings = sizing.readings(id);
        double[] condensing = vapour.amounts();
        if (ColumnComponents.ejectorRequired(door.topPressure())) {
            boolean wired = wires.getOrDefault(id, Map.of()).containsKey(EjectorUnit.MOTIVE);
            var vent = thermo.atTemperature(condenser.temperatureKelvin(), condenser.pressurePascal(), sizing.stream(id, ColumnUnit.OVERHEAD).amounts(), SolidInventory.EMPTY);
            var load = EjectorSizing.Load.of(vent, readings.getOrDefault("ejector_need", 0.), wired ? readings.get("ejector_motive") : null, basis);
            out.put(member(id, "ejector"), ejector(entries.unit(member(id, "ejector")), load));
            condensing[thermo.water()] += readings.getOrDefault("ejector_motive", 0.) / thermo.waterMass();
        }
        var condensed = thermo.atTemperature(condenser.temperatureKelvin(), condenser.pressurePascal(), condensing, SolidInventory.EMPTY);
        var overhead = thermo.heated(condensed, block.condenser(), top.pressurePascal());
        double coolerDrop = 0;
        thermo.checkpoint().run();
        // A member's count is its door's (S3b): a kept size is rated at it, and Auto proposes one, the fewest units first.
        var counts = door.units();
        // The proposal reads the top vapour and the receiver (C2); the door's type is the player's, and its members are sized on it.
        condensers.put(id, CoolerSizing.condenser(top.temperatureKelvin(), condenser.temperatureKelvin()));
        // An air-then-water condenser's air cools the overhead to the split, and its water trim from there to the receiver.
        var split = condensed;
        // A twin whose member has no duty stays the player's: that member reads TWIN_IDLE at its kept size, or the smallest.
        var idle = door.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER ? CoolerSizing.twinIdle(top.temperatureKelvin(), condenser.temperatureKelvin()) : null;
        if (door.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER) {
            double at = HeatSides.airSplit(top.temperatureKelvin(), condenser.temperatureKelvin());
            split = idle == CoolerSizing.Idle.AIR ? overhead : idle == CoolerSizing.Idle.TRIM ? condensed : thermo.atTemperature(at, condenser.pressurePascal(), condensing, SolidInventory.EMPTY);
        }
        if (door.condenser() == ColumnComponents.Condenser.EXCHANGER) {
            var load = sizing.water(id, ColumnUnit.CONDENSER_IN, ColumnUnit.CONDENSER_OUT, overhead, condensed);
            var sized = exchanger(counted(entries.unit(member(id, "condenser")), counts.condenser()), load);
            out.put(member(id, "condenser"), sized);
            if (load != null && sized.size() instanceof ExchangerSizing.Size size) coolerDrop = ExchangerSizing.shellDrop(load, size);
        } else if (idle == CoolerSizing.Idle.AIR) {
            out.put(member(id, "condenser"), idle(counted(entries.unit(member(id, "condenser")), counts.condenser()), AirCoolerSizing.smallest().withBanks(counts.condenser())));
        } else {
            var load = AirCoolerSizing.Load.of(overhead, split, basis, thermo, scratch);
            var sized = airCooler(counted(entries.unit(member(id, "condenser")), counts.condenser()), load);
            out.put(member(id, "condenser"), sized);
            if (load != null && sized.size() instanceof AirCoolerSizing.Size size) coolerDrop = AirCoolerSizing.drop(load, size);
        }
        if (idle == CoolerSizing.Idle.TRIM) out.put(member(id, TRIM), idle(counted(entries.unit(member(id, TRIM)), counts.trim()), ExchangerSizing.smallest().withShells(counts.trim())));
        else if (door.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER) {
            var load = sizing.water(id, ColumnUnit.CONDENSER_IN, ColumnUnit.CONDENSER_OUT, split, condensed);
            var sized = exchanger(counted(entries.unit(member(id, TRIM)), counts.trim()), load);
            out.put(member(id, TRIM), sized);
            if (load != null && sized.size() instanceof ExchangerSizing.Size size) coolerDrop += ExchangerSizing.shellDrop(load, size);
        }
        int stages = solved.stageCount();
        if (ColumnComponents.kettleRequired(door.reboilerDuty())) {
            thermo.checkpoint().run();
            // The bottom tray's liquid, boiled by the duty the column's reboiler puts in, as the condenser's sides balance.
            var feed = ColumnSizing.liquid(thermo, solved, nodes.get(stages));
            var boiled = thermo.heated(feed, block.reboiler(), nodes.get(stages + 1).pressurePascal());
            out.put(member(id, "reboiler"), exchanger(counted(entries.unit(member(id, "reboiler")), counts.kettle()), ExchangerSizing.Load.of(true, sizing.stream(id, ColumnUnit.REBOILER_IN), sizing.stream(id, ColumnUnit.REBOILER_OUT), feed, boiled, basis, thermo, scratch)));
        }
        thermo.checkpoint().run();
        var condensate = ColumnSizing.liquid(thermo, solved, condenser);
        var receiverEntry = entries.unit(member(id, "receiver"));
        var receiver = drum(receiverEntry, DrumSizing.Load.of(sizing.stream(id, ColumnUnit.OVERHEAD), condensate, sizing.stream(id, ColumnUnit.WATER), receiverEntry.hold(), basis, thermo, scratch));
        out.put(member(id, "receiver"), receiver);
        var drum = receiver.size() == null ? null : UnitSize.built(receiver.size());

        // The condensate leaves the receiver for the pump, and leaves it above the top tray's pressure, where the reflux enters.
        thermo.checkpoint().run();
        var overheadLine = memberLine(member(id, ColumnUnit.OVERHEAD), overhead, Service.Ends.OPEN, block.nozzle(EquipmentShape.Role.VAPOUR_OUTLET, 1));
        var suction = memberLine(member(id, "receiver out"), condensate, new Service.Ends(false, true), drum == null ? PipeSizes.MAX_BORE : oilNozzle(drum));
        var pumped = thermo.atTemperature(condensate.temperature(), top.pressurePascal(), condensate.amounts(), SolidInventory.EMPTY);
        var pumpOut = memberLine(member(id, "pump out"), pumped, Service.Ends.OPEN, PipeSizes.MAX_BORE);
        double share = condensate.total() > 0 ? Math.max(0, 1 - sizing.stream(id, ColumnUnit.DISTILLATE).total() / condensate.total()) : 0;
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
            var draw = nodes.get(pumparound.drawTray());
            var hot = circulation(solved, draw, -pumparound.duty());
            var cooled = thermo.atTemperature(hot.temperature() - basis.paFall(), hot.pressure(), hot.amounts(), SolidInventory.EMPTY);
            var load = sizing.water(id, ColumnUnit.pumparoundIn(pumparound), ColumnUnit.pumparoundOut(pumparound), hot, cooled);
            var cooler = exchanger(counted(entries.unit(member(id, PumparoundMember.name(pumparound.id(), PumparoundMember.COOLER))), counts.cooler(i)), load);
            out.put(member(id, PumparoundMember.name(pumparound.id(), PumparoundMember.COOLER)), cooler);
            var drawLine = memberLine(member(id, PumparoundMember.name(pumparound.id(), "draw")), hot, new Service.Ends(true, true), block.nozzle(EquipmentShape.Role.PUMPAROUND_DRAW, i + 1));
            var returnLine = memberLine(member(id, PumparoundMember.name(pumparound.id(), "return")), cooled, Service.Ends.OPEN, block.nozzle(EquipmentShape.Role.RETURN, i + 1));
            double shell = load != null && cooler.size() instanceof ExchangerSizing.Size s ? ExchangerSizing.shellDrop(load, s) : 0;
            Double level = size == null ? null : size.level(pumparound.drawTray());
            double up = size == null ? 0 : size.level(pumparound.returnTray()) - size.level(pumparound.drawTray());
            double paRise = drawLine.line().density() * PassiveStepSolver.GRAVITY * up + 1e5 * (drop(drawLine) + drop(returnLine) + shell + Limit.VALVE_DROP.value());
            out.put(member(id, PumparoundMember.name(pumparound.id(), "pump")), pump(member(id, PumparoundMember.name(pumparound.id(), "pump")), drawLine, hot, paRise, level));
        }
        members.put(id, out);
    }

    /** An idle twin member (C2): its kept size, else {@code smallest}, built or not, and yellow on {@link Limit#TWIN_IDLE}. */
    private static SizedUnit idle(UnitEntry entry, UnitSize smallest) {
        var size = entry.size() != null ? entry.size() : smallest;
        return new SizedUnit(size, new Rating(List.of(UnitSize.reading(UnitSize.built(size)), Limit.TWIN_IDLE.read(0))));
    }

    /** {@code entry} with its kept size at {@code count} units in parallel: a member's count is its door's. Auto's entry stays. */
    private static UnitEntry counted(UnitEntry entry, int count) {
        UnitSize size = switch (entry.size()) {
            case ExchangerSizing.Size exchanger -> exchanger.withShells(count);
            case AirCoolerSizing.Size cooler -> cooler.withBanks(count);
            case null, default -> entry.size();
        };
        return new UnitEntry(size, entry.type(), entry.hold(), entry.kept());
    }

    /** The liquid a pumparound circulates to give {@code heat} W across its fall, at its draw tray. */
    FlowStream circulation(com.wormzjl.icheme.science.column.v3.V3ColumnInput solved, V3ColumnInspection.Node draw, double heat) {
        var one = ColumnSizing.stream(thermo, solved, draw.liquidFractions(), 1, 0, draw.temperatureKelvin(), draw.pressurePascal());
        double fall = one.enthalpy() - thermo.atTemperature(draw.temperatureKelvin() - basis.paFall(), draw.pressurePascal(), one.amounts(), SolidInventory.EMPTY).enthalpy();
        return ColumnSizing.stream(thermo, solved, draw.liquidFractions(), fall > 0 ? heat / fall : 0, 0, draw.temperatureKelvin(), draw.pressurePascal());
    }

    /** A member pump on {@code suction}, adding {@code rise} Pa. */
    SizedUnit pump(String id, SizedLine suction, FlowStream liquid, double rise, @Nullable Double height) {
        var entry = entries.unit(id);
        if (suction.size() == null) return SizedUnit.NONE;
        var load = PumpSizing.Load.of(suction.line(), suction.size(), liquid, rise, height, thermo);
        return sized(entry, PumpSizing.Size.class, size -> PumpSizing.rate(load, size), () -> PumpSizing.auto(load));
    }


    SizedLine memberLine(String id, FlowStream flow, Service.Ends ends, double nozzle) {
        var sized = sizing.line(id, flow, ends, nozzle);
        memberLines.put(id, sized);
        return sized;
    }
}
