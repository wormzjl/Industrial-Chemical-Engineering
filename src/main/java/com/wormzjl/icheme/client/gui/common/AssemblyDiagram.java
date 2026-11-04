package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.wormzjl.icheme.science.assembly.ColumnBlueprint.*;

/**
 * A column assembly's parts and port links (batch 2026-10-26-multi-machine-framework, plan section 8): one part per slot of
 * the column's blueprint with its name, where it goes and the kind it asks for, and each port the blueprint pipes with the
 * part it pipes to. The screen's parts list names its parts by it; the laying tool colours a nozzle by its port's place in
 * the list and names the part it pipes to. The screen's drawing is {@code AssemblyBlock} (owner's option A, 2026-11-03).
 * Texts are language keys under {@value #KEY}. Minecraft-free.
 */
public final class AssemblyDiagram {
    public static final String KEY = "gui.icheme.assembly.";

    /** A text: a language key under {@link #KEY} and its arguments (numbers, or other texts). */
    public record Text(String key, List<Object> args) {
        public static Text of(String key, Object... args) {
            return new Text(key, List.of(args));
        }
    }

    public enum Align {
        LEFT, CENTRE, RIGHT
    }

    /** A part: the slot it stands for, its name, where it goes, and the kind its slot asks for (none for a valve or the tee). */
    public record Part(Slot slot, Text name, Text where, @Nullable Text wanted) {
    }

    /** Port {@code role} number {@code ordinal} (-1: any of that role) of the unit in {@code slot}, and the slot of the part it pipes to. */
    public record Port(Slot slot, Role role, int ordinal, Slot partner) {
    }

    private final List<Part> parts = new ArrayList<>();
    private final List<Port> ports = new ArrayList<>();

    private AssemblyDiagram() {
    }

    public List<Part> parts() {
        return parts;
    }

    /** The ports in a fixed order: the laying tool's colour of a port is its place here ({@code PortColours.assembly}). */
    public List<Port> ports() {
        return ports;
    }

    public @Nullable Part part(Slot slot) {
        for (var p : parts) if (p.slot().equals(slot)) return p;
        return null;
    }

    /** The parts and ports of a column built from {@code trays}, which carries a component design. */
    public static AssemblyDiagram of(ColumnTrays trays) {
        var c = Objects.requireNonNull(trays.components(), "A column without a component design has no assembly");
        var d = new AssemblyDiagram();
        boolean vac = c.ejector(), ket = c.kettle(), trim = c.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER,
                air = trim || c.condenser() == ColumnComponents.Condenser.AIR_COOLER;

        d.part(COLUMN, t("part.column"), t("where.column"), null);
        // The overhead: the ejector, the condenser (an air-then-water one: the air cooler, then its water trim), the receiver and its three lines.
        if (vac) d.part(EJECTOR, t("part.ejector"), t("where.ejector"), t("wanted.ejector"));
        if (trim) {
            d.part(CONDENSER, t("part.condenser"), t(vac ? "where.air_cooler_trim_ejector" : "where.air_cooler_trim"), t("wanted.air_cooler"));
            d.part(TRIM_CONDENSER, t("part.trim_condenser"), t("where.trim_condenser"), t("wanted.condenser"));
        } else
            d.part(CONDENSER, t("part.condenser"), t(vac ? "where.condenser_ejector" : "where.condenser"), t(air ? "wanted.air_cooler" : "wanted.condenser"));
        d.part(RECEIVER, t("part.receiver"), t("where.receiver"), t("wanted.receiver"));
        d.valve(OFFGAS_VALVE);
        boolean standard = c.refluxPump() == ColumnComponents.Pump.STANDARD;
        d.pump(REFLUX_PUMP, c.refluxPump(), t("part.reflux_pump"), t("where.reflux_pump"));
        d.part(REFLUX_TEE, t("part.reflux_tee"), t("where.reflux_tee"), null);
        d.valve(REFLUX_VALVE);
        d.valve(DISTILLATE_VALVE);
        d.valve(WATER_VALVE);
        // The pumparound loops: the pump, then the cooler's shell side, back to the return tray.
        for (int i = 1; i <= trays.pumparounds().size(); i++) {
            d.pump(pumparoundPump(i), c.pumparoundPumps().get(i - 1), t("part.pa_pump", i), t("where.pa_pump", i));
            d.part(pumparoundCooler(i), t("part.pa_cooler", i), t("where.pa_cooler", i), t("wanted.pa_cooler"));
        }
        // The side draws out, then the feed and the steam in, each through its valve; the kettle on the bottoms.
        for (int j = 1; j <= trays.draws().size(); j++) d.valve(drawValve(j));
        d.valve(FEED_VALVE);
        for (int i = 1; i <= trays.steam().size(); i++) d.valve(steamValve(i));
        if (ket) d.part(REBOILER, t("part.reboiler"), t("where.reboiler"), t("wanted.reboiler"));
        d.valve(BOTTOMS_VALVE);
        d.ports(trays, c, vac, ket, air, trim, standard);
        return d;
    }

    /** The ports: each nozzle the blueprint names, and the part it pipes to. Their order sets the laying tool's colours: keep it. */
    private void ports(ColumnTrays trays, ColumnComponents c, boolean vac, boolean ket, boolean air, boolean trim, boolean standard) {
        port(COLUMN, Role.VAPOUR_OUTLET, 0, vac ? EJECTOR : CONDENSER);
        if (vac) {
            port(EJECTOR, Role.SUCTION, 0, COLUMN);
            port(EJECTOR, Role.DISCHARGE, 0, CONDENSER);
        }
        port(CONDENSER, air ? Role.PROCESS_INLET : Role.SHELL_INLET, 0, vac ? EJECTOR : COLUMN);
        if (trim) {
            port(CONDENSER, Role.PROCESS_OUTLET, 0, TRIM_CONDENSER);
            port(TRIM_CONDENSER, Role.SHELL_INLET, 0, CONDENSER);
            port(TRIM_CONDENSER, Role.SHELL_OUTLET, 0, RECEIVER);
        } else port(CONDENSER, air ? Role.PROCESS_OUTLET : Role.SHELL_OUTLET, 0, RECEIVER);
        port(RECEIVER, Role.SIDE, -1, trim ? TRIM_CONDENSER : CONDENSER);
        port(RECEIVER, Role.VAPOUR_OUTLET, 0, OFFGAS_VALVE);
        port(RECEIVER, Role.BOOT_DRAIN, 0, WATER_VALVE);
        port(RECEIVER, Role.LIQUID_OUTLET, 0, REFLUX_PUMP);
        if (standard) {
            port(REFLUX_PUMP, Role.SUCTION, 0, RECEIVER);
            port(REFLUX_PUMP, Role.DISCHARGE, 0, REFLUX_TEE);
        }
        port(COLUMN, Role.REFLUX, 0, REFLUX_VALVE);
        for (int j = 1; j <= trays.draws().size(); j++) port(COLUMN, Role.DRAW, j - 1, drawValve(j));
        port(COLUMN, Role.FEED, 0, FEED_VALVE);
        for (int i = 1; i <= trays.steam().size(); i++) port(COLUMN, Role.STEAM, i - 1, steamValve(i));
        for (int i = 1; i <= trays.pumparounds().size(); i++) {
            port(COLUMN, Role.PUMPAROUND_DRAW, i - 1, pumparoundPump(i));
            port(COLUMN, Role.RETURN, i - 1, pumparoundCooler(i));
            port(pumparoundCooler(i), Role.SHELL_INLET, 0, pumparoundPump(i));
            port(pumparoundCooler(i), Role.SHELL_OUTLET, 0, COLUMN);
            if (c.pumparoundPumps().get(i - 1) == ColumnComponents.Pump.STANDARD) {
                port(pumparoundPump(i), Role.SUCTION, 0, COLUMN);
                port(pumparoundPump(i), Role.DISCHARGE, 0, pumparoundCooler(i));
            }
        }
        if (ket) {
            port(COLUMN, Role.BOTTOMS, 0, REBOILER);
            port(REBOILER, Role.FEED, 0, COLUMN);
            port(REBOILER, Role.VAPOUR_OUTLET, 0, COLUMN);
            port(COLUMN, Role.VAPOUR_RETURN, 0, REBOILER);
            port(REBOILER, Role.LIQUID_OUTLET, 0, BOTTOMS_VALVE);
        } else port(COLUMN, Role.BOTTOMS, 0, BOTTOMS_VALVE);
    }

    private static Text t(String key, Object... args) {
        return Text.of(key, args);
    }

    private void part(Slot slot, Text name, Text where, @Nullable Text wanted) {
        parts.add(new Part(slot, name, where, wanted));
    }

    /** A control valve: numbered for the side draws and the steam. */
    private void valve(Slot slot) {
        part(slot, slot.index() == 0 ? t("part." + slot.part()) : t("part." + slot.part(), slot.index()), t("where." + slot.part()), null);
    }

    private void pump(Slot slot, ColumnComponents.Pump kind, Text name, Text where) {
        part(slot, name, where, t(kind == ColumnComponents.Pump.STANDARD ? "wanted.pump" : "wanted.inline_pump"));
    }

    private void port(Slot slot, Role role, int ordinal, Slot partner) {
        ports.add(new Port(slot, role, ordinal, partner));
    }
}
