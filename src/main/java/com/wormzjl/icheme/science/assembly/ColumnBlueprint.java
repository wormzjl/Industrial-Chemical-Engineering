package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.Blueprint.*;
import com.wormzjl.icheme.science.assembly.PortGraph.Fitting;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A distillation column's assembly (batch 2026-10-26-multi-machine-framework, plan sections 5.1 and 5.2): the parts and
 * pipe paths its saved component design asks for. One blueprint with conditional slots covers every design: the ejector
 * with the ejector flag, the kettle with the kettle flag, a valve per side draw and steam stage, a pump and a cooler per
 * pumparound. The reflux line is in every one (owner answer Q2). A vacuum top held by a vacuum pump (batch 2026-11-10-vacuum-pump,
 * owner V5) has no ejector: the pump stands on the receiver's vapour outlet before the offgas valve, and a pump lifts the free
 * water out of the receiver's boot to its valve. Minecraft-free.
 */
public final class ColumnBlueprint {
    public static final Slot COLUMN = new Slot("column", 0), EJECTOR = new Slot("ejector", 0), CONDENSER = new Slot("condenser", 0),
            RECEIVER = new Slot("receiver", 0), OFFGAS_VALVE = new Slot("offgas_valve", 0), WATER_VALVE = new Slot("water_valve", 0),
            REFLUX_PUMP = new Slot("reflux_pump", 0), REFLUX_TEE = new Slot("reflux_tee", 0), REFLUX_VALVE = new Slot("reflux_valve", 0),
            DISTILLATE_VALVE = new Slot("distillate_valve", 0), FEED_VALVE = new Slot("feed_valve", 0), REBOILER = new Slot("reboiler", 0),
            BOTTOMS_VALVE = new Slot("bottoms_valve", 0),
            // An air-then-water condenser's water exchanger, after its air cooler (COOLER_MODEL_PLAN.md section 6).
            TRIM_CONDENSER = new Slot("trim_condenser", 0),
            // A vacuum pump on the receiver's vapour outlet, and the pump on its boot's water (batch 2026-11-10-vacuum-pump).
            VACUUM_PUMP = new Slot("vacuum_pump", 0), WATER_PUMP = new Slot("water_pump", 0);

    private static final Form VALVE = new FittingForm(Fitting.CONTROL_VALVE), INLINE_PUMP = new FittingForm(Fitting.INLINE_PUMP),
            PUMP = new UnitForm(Kind.PUMP, false, Role.SUCTION, Role.DISCHARGE, Set.of()),
            EJECTOR_UNIT = new UnitForm(Kind.EJECTOR, false, Role.SUCTION, Role.DISCHARGE, Set.of(Role.MOTIVE)),
            // The vapour condenses on the shell side, where its condensate drains (decision D15); the tube side is cooling water.
            EXCHANGER_CONDENSER = new UnitForm(Kind.EXCHANGER, false, Role.SHELL_INLET, null, Set.of(Role.TUBE_INLET, Role.TUBE_OUTLET)),
            AIR_COOLER = new UnitForm(Kind.AIR_COOLER, false, Role.PROCESS_INLET, null, Set.of()),
            // An air-then-water condenser's air cooler passes the overhead on to the trim; an exchanger in its place passes it on its shell.
            AIR_COOLER_THROUGH = new UnitForm(Kind.AIR_COOLER, false, Role.PROCESS_INLET, Role.PROCESS_OUTLET, Set.of()),
            EXCHANGER_THROUGH = new UnitForm(Kind.EXCHANGER, false, Role.SHELL_INLET, Role.SHELL_OUTLET, Set.of(Role.TUBE_INLET, Role.TUBE_OUTLET)),
            RECEIVER_DRUM = new UnitForm(Kind.DRUM, true, Role.SIDE, null, Set.of()),
            ANY_DRUM = new UnitForm(Kind.DRUM, false, Role.SIDE, null, Set.of()),
            PA_COOLER = new UnitForm(Kind.EXCHANGER, false, Role.SHELL_INLET, Role.SHELL_OUTLET, Set.of(Role.TUBE_INLET, Role.TUBE_OUTLET)),
            KETTLE = new UnitForm(Kind.KETTLE, false, Role.FEED, null, Set.of(Role.TUBE_INLET, Role.TUBE_OUTLET)),
            LIQUID_RING = new UnitForm(Kind.LIQUID_RING_PUMP, false, Role.SUCTION, Role.DISCHARGE, Set.of()),
            DRY_SCREW = new UnitForm(Kind.DRY_SCREW_PUMP, false, Role.SUCTION, Role.DISCHARGE, Set.of());

    private ColumnBlueprint() {
    }

    public static Slot steamValve(int i) {
        return new Slot("steam_valve", i);
    }

    public static Slot drawValve(int i) {
        return new Slot("draw_valve", i);
    }

    public static Slot pumparoundPump(int i) {
        return new Slot("pa_pump", i);
    }

    public static Slot pumparoundCooler(int i) {
        return new Slot("pa_cooler", i);
    }

    /** The assembly a column built from {@code trays} asks for. */
    public static Blueprint of(ColumnTrays trays) {
        var c = Objects.requireNonNull(trays.components(), "A column without a component design has no assembly");
        var slots = new ArrayList<Slot>();
        var links = new ArrayList<Link>();
        slots.add(COLUMN);
        boolean pumped = c.vacuumPump(), ejected = c.ejector() && !pumped;
        if (ejected) slots.add(EJECTOR);
        slots.add(CONDENSER);
        boolean trimmed = c.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER;
        if (trimmed) slots.add(TRIM_CONDENSER);
        slots.add(RECEIVER);
        if (pumped) slots.add(VACUUM_PUMP);
        slots.add(OFFGAS_VALVE);
        if (pumped) slots.add(WATER_PUMP);
        slots.addAll(List.of(WATER_VALVE, REFLUX_PUMP, REFLUX_TEE, REFLUX_VALVE, DISTILLATE_VALVE, FEED_VALVE));

        // The heat-transfer members take their counts of identical units in parallel (S3b). An air-then-water condenser's
        // air cooler passes the overhead on to its water trim, where the overhead link ends (COOLER_MODEL_PLAN.md section 6).
        var overhead = new ArrayList<Step>();
        if (ejected) overhead.add(Step.of(EJECTOR, EJECTOR_UNIT));
        switch (c.condenser()) {
            case AIR_COOLER -> overhead.add(Step.of(CONDENSER, AIR_COOLER, EXCHANGER_CONDENSER).withCount(c.units().condenser()));
            case EXCHANGER -> overhead.add(Step.of(CONDENSER, ((UnitForm)EXCHANGER_CONDENSER).withExchangerType(c.exchangers().condenser()), AIR_COOLER).withCount(c.units().condenser()));
            case AIR_THEN_WATER -> {
                overhead.add(Step.of(CONDENSER, AIR_COOLER_THROUGH, EXCHANGER_THROUGH).withCount(c.units().condenser()));
                overhead.add(Step.of(TRIM_CONDENSER, ((UnitForm)EXCHANGER_CONDENSER).withExchangerType(c.exchangers().trim()), AIR_COOLER).withCount(c.units().trim()));
            }
        }
        var last = trimmed ? TRIM_CONDENSER : CONDENSER;
        links.add(new Link("overhead", Start.at(COLUMN, Role.VAPOUR_OUTLET, 0), overhead, new AtUnit(), false, last));
        links.add(new Link("condensate", Start.after(last), List.of(Step.of(RECEIVER, RECEIVER_DRUM, ANY_DRUM)), new AtUnit(), false, RECEIVER));
        var offgas = new ArrayList<Step>();
        var water = new ArrayList<Step>();
        if (pumped) {
            offgas.add(c.vacuum() == ColumnComponents.Vacuum.DRY_SCREW ? Step.of(VACUUM_PUMP, DRY_SCREW) : Step.of(VACUUM_PUMP, LIQUID_RING));
            water.add(pump(WATER_PUMP, c.refluxPump()));
        }
        offgas.add(Step.of(OFFGAS_VALVE, VALVE));
        water.add(Step.of(WATER_VALVE, VALVE));
        links.add(new Link("offgas", Start.at(RECEIVER, Role.VAPOUR_OUTLET, 0), offgas, new Outside(), false, OFFGAS_VALVE));
        links.add(new Link("water", Start.at(RECEIVER, Role.BOOT_DRAIN, 0), water, new Outside(), false, WATER_VALVE));
        var tee = new TeeForm(List.of(
                new Branch(List.of(Step.of(REFLUX_VALVE, VALVE)), new ToPort(new PortSpec(COLUMN, Role.REFLUX, 0)), REFLUX_VALVE),
                new Branch(List.of(Step.of(DISTILLATE_VALVE, VALVE)), new Outside(), DISTILLATE_VALVE)));
        links.add(new Link("oil", Start.at(RECEIVER, Role.LIQUID_OUTLET, 0), List.of(pump(REFLUX_PUMP, c.refluxPump()), Step.of(REFLUX_TEE, tee)),
                new AtUnit(), false, REFLUX_TEE));
        links.add(new Link("feed", Start.at(COLUMN, Role.FEED, 0), List.of(Step.of(FEED_VALVE, VALVE)), new Outside(), true, FEED_VALVE));

        for (int i = 1; i <= trays.steam().size(); i++) {
            slots.add(steamValve(i));
            links.add(new Link("steam", Start.at(COLUMN, Role.STEAM, i - 1), List.of(Step.of(steamValve(i), VALVE)), new Outside(), true, steamValve(i)));
        }
        for (int i = 1; i <= trays.draws().size(); i++) {
            slots.add(drawValve(i));
            links.add(new Link("draw", Start.at(COLUMN, Role.DRAW, i - 1), List.of(Step.of(drawValve(i), VALVE)), new Outside(), false, drawValve(i)));
        }
        for (int i = 1; i <= trays.pumparounds().size(); i++) {
            slots.add(pumparoundPump(i));
            slots.add(pumparoundCooler(i));
            // The pump and the cooler in either order: a cooler before the pump is common where the draw is hot.
            var steps = List.of(pump(pumparoundPump(i), c.pumparoundPumps().get(i - 1)).withAnyOrder(), Step.of(pumparoundCooler(i), ((UnitForm)PA_COOLER).withExchangerType(c.exchangers().cooler(i-1))).withCount(c.units().cooler(i - 1)));
            links.add(new Link("pumparound", Start.at(COLUMN, Role.PUMPAROUND_DRAW, i - 1), steps, new ToPort(new PortSpec(COLUMN, Role.RETURN, i - 1)),
                    false, pumparoundCooler(i)));
        }
        if (c.kettle()) {
            slots.add(REBOILER);
            links.add(new Link("reboiler", Start.at(COLUMN, Role.BOTTOMS, 0), List.of(Step.of(REBOILER, KETTLE).withCount(c.units().kettle())), new AtUnit(), false, REBOILER));
            links.add(new Link("vapour_return", Start.at(REBOILER, Role.VAPOUR_OUTLET, 0), List.of(), new ToPort(new PortSpec(COLUMN, Role.VAPOUR_RETURN, 0)),
                    false, REBOILER));
            links.add(new Link("bottoms", Start.at(REBOILER, Role.LIQUID_OUTLET, 0), List.of(Step.of(BOTTOMS_VALVE, VALVE)), new Outside(), false, BOTTOMS_VALVE));
        } else {
            // Without a kettle the column has no vapour return (owner answer Q9 (a)): sump steam strips its bottom.
            links.add(new Link("bottoms", Start.at(COLUMN, Role.BOTTOMS, 0), List.of(Step.of(BOTTOMS_VALVE, VALVE)), new Outside(), false, BOTTOMS_VALVE));
        }
        slots.add(BOTTOMS_VALVE);
        return new Blueprint(COLUMN, slots, links);
    }

    /**
     * The slot whose units' water sides cool a column's condenser: the condenser's own for an exchanger, the trim's for an
     * air-then-water condenser, none for an air cooler.
     */
    public static @org.jetbrains.annotations.Nullable Slot waterCooled(ColumnComponents.Condenser condenser) {
        return switch (condenser) {
            case AIR_COOLER -> null;
            case EXCHANGER -> CONDENSER;
            case AIR_THEN_WATER -> TRIM_CONDENSER;
        };
    }

    /** A pump step of the designed kind; the other kind fills it as the wrong kind. */
    private static Step pump(Slot slot, ColumnComponents.Pump kind) {
        return kind == ColumnComponents.Pump.IN_LINE ? Step.of(slot, INLINE_PUMP, PUMP) : Step.of(slot, PUMP, INLINE_PUMP);
    }
}
