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
 * The plant drawing of a column's assembly (batch 2026-10-26-multi-machine-framework, plan section 8), laid out as in
 * {@code COLUMN_ASSEMBLY_CASES.html} on a {@value #WIDTH} by {@value #HEIGHT} design grid: the column in the middle, the
 * overhead system at the top right, the pumparound loops to the right, the side draws, feed and steam to the left, the
 * kettle at the bottom left. One part per slot of the column's blueprint, a square per port.
 * Texts are language keys under {@value #KEY}. Minecraft-free: the screen draws it, scaled to fit.
 */
public final class AssemblyDiagram {
    public static final int WIDTH = 680, HEIGHT = 724;
    public static final String KEY = "gui.icheme.assembly.";

    /** A text: a language key under {@link #KEY} and its arguments (numbers, or other texts). */
    public record Text(String key, List<Object> args) {
        public static Text of(String key, Object... args) {
            return new Text(key, List.of(args));
        }
    }

    public enum Glyph {
        COLUMN, UNIT, DRUM, TEE, VALVE,
        /** A standard pump drawn on a falling line (its motor to the right) or a line across (its motor below). */
        PUMP_DOWN, PUMP_ACROSS,
        INLINE_PUMP_DOWN, INLINE_PUMP_ACROSS
    }

    /**
     * A part: the slot it stands for, its glyph and box, the label drawn in it (or none), its name, where it goes, and the
     * kind its slot asks for (none for a valve or the tee).
     */
    public record Part(Slot slot, Glyph glyph, int x, int y, int w, int h, @Nullable Text label, Text name, Text where, @Nullable Text wanted) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** A pipe line through {@code points} (x, y pairs), with an arrow at its end; a utility line is advice, drawn dashed. */
    public record Wire(int[] points, boolean arrow, boolean utility) {
    }

    public enum Align {
        LEFT, CENTRE, RIGHT
    }

    public record Label(int x, int y, Text text, Align align) {
    }

    /** A stream's end outside the assembly: a rounded tag from {@code x}, {@code w} wide, centred on {@code y}. */
    public record Pill(int x, int y, int w, Text text) {
    }

    /**
     * The square of port {@code role} number {@code ordinal} (-1: any of that role) of the unit in {@code slot}, and the
     * slot of the part it pipes to.
     */
    public record PortMark(Slot slot, Role role, int ordinal, int x, int y, Slot partner) {
        public static final int SIZE = 8;

        public boolean contains(double px, double py) {
            return Math.abs(px - x) <= SIZE / 2.0 && Math.abs(py - y) <= SIZE / 2.0;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Pill> pills = new ArrayList<>();
    private final List<PortMark> ports = new ArrayList<>();
    private final List<int[]> trays = new ArrayList<>();

    private AssemblyDiagram() {
    }

    public List<Part> parts() {
        return parts;
    }

    public List<Wire> wires() {
        return wires;
    }

    public List<Label> labels() {
        return labels;
    }

    public List<Pill> pills() {
        return pills;
    }

    public List<PortMark> ports() {
        return ports;
    }

    /** The tray lines in the column: x from, y, x to. */
    public List<int[]> trays() {
        return trays;
    }

    public @Nullable Part part(Slot slot) {
        for (var p : parts) if (p.slot().equals(slot)) return p;
        return null;
    }

    /** The drawing of a column built from {@code trays}, which carries a component design. */
    public static AssemblyDiagram of(ColumnTrays trays) {
        var c = Objects.requireNonNull(trays.components(), "A column without a component design has no assembly");
        var d = new AssemblyDiagram();
        boolean vac = c.ejector(), ket = c.kettle(), air = c.condenser() == ColumnComponents.Condenser.AIR_COOLER;

        d.part(COLUMN, Glyph.COLUMN, 232, 120, 56, 500, null, t("part.column"), t("where.column"), null);
        for (int k = 0, y = 146; y < 594; k++, y += 18) if (y <= 312 || y >= 348) d.trays.add(k % 2 == 1 ? new int[]{236, y, 276} : new int[]{244, y, 284});
        d.label(260, 330, t("label.column"), Align.CENTRE);

        // The overhead: the ejector, the condenser, the receiver and its three lines.
        if (vac) {
            d.wire(true, 260, 120, 260, 40, 339, 40);
            d.part(EJECTOR, Glyph.UNIT, 340, 30, 56, 20, t("label.ejector"), t("part.ejector"), t("where.ejector"), t("wanted.ejector"));
            d.wire(true, 397, 40, 459, 40);
            d.utility(368, 29, 368, 12);
            d.label(374, 14, t("utility.motive_steam"), Align.LEFT);
        } else d.wire(true, 260, 120, 260, 40, 459, 40);
        d.part(CONDENSER, Glyph.UNIT, 460, 24, 120, 32, units(t(air ? "label.air_cooler" : "label.exchanger"), c.units().condenser()), t("part.condenser"),
                t(vac ? "where.condenser_ejector" : "where.condenser"), t(air ? "wanted.air_cooler" : "wanted.condenser"));
        if (!air) {
            d.utility(530, 23, 530, 8);
            d.utility(546, 23, 546, 8);
            d.label(554, 13, t("utility.cooling_water"), Align.LEFT);
        }
        d.wire(true, 580, 40, 605, 40, 605, 109);
        d.part(RECEIVER, Glyph.DRUM, 440, 110, 190, 38, t("label.receiver"), t("part.receiver"), t("where.receiver"), t("wanted.receiver"));
        d.label(527, 163, t("label.boot"), Align.LEFT);
        d.wire(true, 465, 110, 465, 84, 351, 84);
        d.valve(OFFGAS_VALVE, 420, 84);
        d.pill(290, 84, 60, t("outside.offgas"));
        boolean standard = c.refluxPump() == ColumnComponents.Pump.STANDARD;
        int r = standard ? 10 : 7;
        d.wire(true, 470, 148, 470, 191 - r);
        d.pump(REFLUX_PUMP, 470, 192, c.refluxPump(), true, t("part.reflux_pump"), t("where.reflux_pump"));
        d.label(454, 192, t(standard ? "label.reflux_pump" : "label.inline_reflux_pump"), Align.RIGHT);
        d.wire(false, 470, 192 + r, 470, 232);
        d.part(REFLUX_TEE, Glyph.TEE, 466, 228, 8, 8, null, t("part.reflux_tee"), t("where.reflux_tee"), null);
        d.wire(true, 470, 232, 320, 232, 320, 150, 289, 150);
        d.valve(REFLUX_VALVE, 400, 232);
        d.label(356, 246, t("label.reflux"), Align.CENTRE);
        d.wire(true, 470, 232, 470, 262, 599, 262);
        d.valve(DISTILLATE_VALVE, 540, 262);
        d.pill(600, 262, 74, t("outside.distillate"));
        d.wire(true, 511, 174, 511, 196, 599, 196);
        d.valve(WATER_VALVE, 555, 196);
        d.pill(600, 196, 74, t("outside.sour_water"));

        // The pumparound loops: the pump, then the cooler's shell side, back to the return tray.
        for (int i = 1; i <= trays.pumparounds().size(); i++) {
            int top = 300 + 58 * (i - 1), back = top + 12, out = top + 40;
            var kind = c.pumparoundPumps().get(i - 1);
            int pr = kind == ColumnComponents.Pump.STANDARD ? 10 : 7;
            d.wire(false, 289, out, 345 - pr, out);
            d.pump(pumparoundPump(i), 345, out, kind, false, t("part.pa_pump", i), t("where.pa_pump", i));
            d.wire(true, 345 + pr, out, 419, out);
            d.part(pumparoundCooler(i), Glyph.UNIT, 420, top, 80, 52, units(t("label.pa_cooler", i), c.units().cooler(i - 1)), t("part.pa_cooler", i), t("where.pa_cooler", i), t("wanted.pa_cooler"));
            d.wire(true, 420, back, 289, back);
            d.label(362, top + 24, t(kind == ColumnComponents.Pump.STANDARD ? "label.standard" : "label.inline"), Align.LEFT);
            d.utility(500, back + 4, 522, back + 4);
            d.utility(500, out - 4, 522, out - 4);
            d.label(526, top + 26, t("utility.cooling_water"), Align.LEFT);
        }

        // The left side: the side draws out, then the feed and the steam in, each through its valve.
        for (int j = 1; j <= trays.draws().size(); j++) d.side(160 + 34 * (j - 1), false, drawValve(j), t("outside.draw", j));
        d.side(360, true, FEED_VALVE, t("outside.feed"));
        int traySteam = 0;
        for (int i = 1; i <= trays.steam().size(); i++) {
            int stage = trays.steam().get(i - 1);
            boolean sump = stage == trays.trays() + 1;
            d.side(sump ? 545 : 420 + 50 * traySteam++, true, steamValve(i), sump ? t("outside.sump_steam") : t("outside.tray_steam", stage));
        }

        // The bottom: the kettle on the bottoms, or (sump steam, no vapour return) the bottoms straight out.
        if (ket) {
            d.part(REBOILER, Glyph.UNIT, 90, 640, 120, 34, units(t("label.reboiler"), c.units().kettle()), t("part.reboiler"), t("where.reboiler"), t("wanted.reboiler"));
            d.wire(true, 260, 620, 260, 657, 211, 657);
            d.wire(true, 150, 640, 150, 580, 231, 580);
            d.utility(110, 639, 110, 622);
            d.utility(124, 639, 124, 622);
            d.label(117, 612, t("utility.heating_medium"), Align.CENTRE);
            d.wire(true, 90, 657, 66, 657, 66, 700, 359, 700);
            d.valve(BOTTOMS_VALVE, 300, 700);
        } else {
            d.wire(true, 260, 620, 260, 700, 359, 700);
            d.valve(BOTTOMS_VALVE, 310, 700);
        }
        d.pill(360, 700, 72, t("outside.bottoms"));
        d.ports(trays, c, vac, ket, air, standard);
        return d;
    }

    /** The port squares: each nozzle the blueprint names, and the part it pipes to. */
    private void ports(ColumnTrays trays, ColumnComponents c, boolean vac, boolean ket, boolean air, boolean standard) {
        port(COLUMN, Role.VAPOUR_OUTLET, 0, 260, 120, vac ? EJECTOR : CONDENSER);
        if (vac) {
            port(EJECTOR, Role.SUCTION, 0, 340, 40, COLUMN);
            port(EJECTOR, Role.DISCHARGE, 0, 396, 40, CONDENSER);
        }
        port(CONDENSER, air ? Role.PROCESS_INLET : Role.SHELL_INLET, 0, 460, 40, vac ? EJECTOR : COLUMN);
        port(CONDENSER, air ? Role.PROCESS_OUTLET : Role.SHELL_OUTLET, 0, 580, 40, RECEIVER);
        port(RECEIVER, Role.SIDE, -1, 605, 110, CONDENSER);
        port(RECEIVER, Role.VAPOUR_OUTLET, 0, 465, 110, OFFGAS_VALVE);
        port(RECEIVER, Role.BOOT_DRAIN, 0, 511, 174, WATER_VALVE);
        port(RECEIVER, Role.LIQUID_OUTLET, 0, 470, 148, REFLUX_PUMP);
        if (standard) {
            port(REFLUX_PUMP, Role.SUCTION, 0, 470, 182, RECEIVER);
            port(REFLUX_PUMP, Role.DISCHARGE, 0, 470, 202, REFLUX_TEE);
        }
        port(COLUMN, Role.REFLUX, 0, 288, 150, REFLUX_VALVE);
        for (int j = 1; j <= trays.draws().size(); j++) port(COLUMN, Role.DRAW, j - 1, 232, 160 + 34 * (j - 1), drawValve(j));
        port(COLUMN, Role.FEED, 0, 232, 360, FEED_VALVE);
        int traySteam = 0;
        for (int i = 1; i <= trays.steam().size(); i++)
            port(COLUMN, Role.STEAM, i - 1, 232, trays.steam().get(i - 1) == trays.trays() + 1 ? 545 : 420 + 50 * traySteam++, steamValve(i));
        for (int i = 1; i <= trays.pumparounds().size(); i++) {
            int top = 300 + 58 * (i - 1), back = top + 12, out = top + 40;
            port(COLUMN, Role.PUMPAROUND_DRAW, i - 1, 288, out, pumparoundPump(i));
            port(COLUMN, Role.RETURN, i - 1, 288, back, pumparoundCooler(i));
            port(pumparoundCooler(i), Role.SHELL_INLET, 0, 420, out, pumparoundPump(i));
            port(pumparoundCooler(i), Role.SHELL_OUTLET, 0, 420, back, COLUMN);
            if (c.pumparoundPumps().get(i - 1) == ColumnComponents.Pump.STANDARD) {
                port(pumparoundPump(i), Role.SUCTION, 0, 335, out, COLUMN);
                port(pumparoundPump(i), Role.DISCHARGE, 0, 355, out, pumparoundCooler(i));
            }
        }
        if (ket) {
            port(COLUMN, Role.BOTTOMS, 0, 260, 620, REBOILER);
            port(REBOILER, Role.FEED, 0, 210, 657, COLUMN);
            port(REBOILER, Role.VAPOUR_OUTLET, 0, 150, 640, COLUMN);
            port(COLUMN, Role.VAPOUR_RETURN, 0, 232, 580, REBOILER);
            port(REBOILER, Role.LIQUID_OUTLET, 0, 90, 657, BOTTOMS_VALVE);
        } else port(COLUMN, Role.BOTTOMS, 0, 260, 620, BOTTOMS_VALVE);
    }

    /** A member's label with its count of identical units in parallel (S3b), "× N" past one. */
    private static Text units(Text label, int count) {
        return count == 1 ? label : t("label.units", label, count);
    }

    private static Text t(String key, Object... args) {
        return Text.of(key, args);
    }

    private void part(Slot slot, Glyph glyph, int x, int y, int w, int h, @Nullable Text label, Text name, Text where, @Nullable Text wanted) {
        parts.add(new Part(slot, glyph, x, y, w, h, label, name, where, wanted));
    }

    /** A control valve whose bow tie is centred on ({@code x}, {@code y}); its stem and actuator rise above it. */
    private void valve(Slot slot, int x, int y) {
        part(slot, Glyph.VALVE, x - 8, y - 17, 16, 23, null, name(slot), t("where." + slot.part()), null);
    }

    /** A valve's name: numbered for the side draws and the steam. */
    private static Text name(Slot slot) {
        return slot.index() == 0 ? t("part." + slot.part()) : t("part." + slot.part(), slot.index());
    }

    private void pump(Slot slot, int x, int y, ColumnComponents.Pump kind, boolean down, Text name, Text where) {
        boolean standard = kind == ColumnComponents.Pump.STANDARD;
        int r = standard ? 10 : 7;
        var glyph = standard ? down ? Glyph.PUMP_DOWN : Glyph.PUMP_ACROSS : down ? Glyph.INLINE_PUMP_DOWN : Glyph.INLINE_PUMP_ACROSS;
        part(slot, glyph, x - r, y - r, 2 * r, 2 * r, null, name, where, t(standard ? "wanted.pump" : "wanted.inline_pump"));
    }

    /** A line on the column's left side: out to its tag ({@code in} false) or in from it, through its valve. */
    private void side(int y, boolean in, Slot valve, Text text) {
        pill(36, y, 80, text);
        if (in) wire(true, 117, y, 231, y);
        else wire(true, 231, y, 117, y);
        valve(valve, 174, y);
    }

    private void wire(boolean arrow, int... points) {
        wires.add(new Wire(points, arrow, false));
    }

    private void utility(int... points) {
        wires.add(new Wire(points, false, true));
    }

    private void label(int x, int y, Text text, Align align) {
        labels.add(new Label(x, y, text, align));
    }

    private void pill(int x, int y, int w, Text text) {
        pills.add(new Pill(x, y, w, text));
    }

    private void port(Slot slot, Role role, int ordinal, int x, int y, Slot partner) {
        ports.add(new PortMark(slot, role, ordinal, x, y, partner));
    }
}
