package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Text;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.HeatSides;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The flowsheet on a heat unit's Control tab (batch 2026-10-27-heat-exchanger-design, HEAT_GUI_PLAN.md section 2, the
 * approved {@code HEAT_GUI_MOCKUP.svg}), on a {@value #WIDTH} by {@value #HEIGHT} design grid: the unit's body, a square at
 * each nozzle, a pill for each stream outside, and an exchanger's control lines (W8) as their parts, coloured by the side's
 * report as a column's are. Every line carries a {@link Value}: a temperature, a flow or both, which the screen reads from
 * the side's view. Texts are language keys under {@value #KEY}. Minecraft-free: the screen draws it, scaled to fit.
 */
public final class HeatDiagram {
    public static final int WIDTH = 444, HEIGHT = 250;
    public static final String KEY = "gui.icheme.heat.";
    /** The slot of the unit's own body parts, drawn in the neutral colours. */
    public static final String BODY = "body";

    public enum Glyph {
        /** A shell (rounded), its channel head, a box (an air cooler's bundle, a heater's sections, its stack), a lying drum. */
        SHELL, HEAD, BOX, DRUM,
        TEE,
        /** A control valve on a line across (its stem up) or on a line down (its stem to the left). */
        VALVE, VALVE_DOWN,
        /** A flowmeter on a line, "FT" in a circle. */
        METER,
        FAN, BURNER
    }

    /**
     * A part: an exchanger's line slot by its name ({@code shell_inlet_tee}; a valve's own flowmeter is its valve's slot
     * with {@code _meter}), or {@link #BODY}; its glyph, box and label.
     */
    public record Part(String slot, Glyph glyph, int x, int y, int w, int h, @Nullable Text label) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        /** Whether this is one of the exchanger's line parts, which take a state colour. */
        public boolean line() {
            return !slot.equals(BODY);
        }
    }

    /** A pipe line through {@code points} (x, y pairs), with an arrow at its end; a utility line (air, flue, the bundle) is drawn dashed. */
    public record Wire(int[] points, boolean arrow, boolean utility) {
    }

    /** What a value shows. */
    public enum Show {
        TEMPERATURE, FLOW, BOTH, DUTY
    }

    /**
     * A reading: the first of {@code sources} that has one ({@code port:ROLE} a nozzle's stream, {@code part:slot} a line
     * part's, {@code duty} the side's heat, {@code air} an air cooler's air), shown as {@code show}.
     */
    public record Value(List<String> sources, Show show) {
        public Value {
            sources = List.copyOf(sources);
        }

        static Value of(Show show, String... sources) {
            return new Value(List.of(sources), show);
        }
    }

    /** A stream's end outside the unit: a rounded tag from {@code x}, {@code w} wide, centred on {@code y}, its nozzle's role, its name and its temperature. */
    public record Pill(int x, int y, int w, String role, Text name, Value value) {
    }

    /** A reading on a line, at design point ({@code x}, {@code y}). */
    public record Tag(int x, int y, Align align, Value value) {
    }

    public record Label(int x, int y, Text text, Align align) {
    }

    /** The square of the nozzle of role {@code role}. */
    public record Nozzle(String role, int x, int y) {
        public static final int SIZE = 8;
    }

    private final List<Part> parts = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private final List<Pill> pills = new ArrayList<>();
    private final List<Tag> tags = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Nozzle> nozzles = new ArrayList<>();

    private HeatDiagram() {
    }

    public List<Part> parts() {
        return parts;
    }

    public List<Wire> wires() {
        return wires;
    }

    public List<Pill> pills() {
        return pills;
    }

    public List<Tag> tags() {
        return tags;
    }

    public List<Label> labels() {
        return labels;
    }

    public List<Nozzle> nozzles() {
        return nozzles;
    }

    /** The nozzle roles of a side's streams: inlet then outlet, or a kettle's shell's feed, vapour and liquid. */
    public static List<String> roles(HeatSides.Unit unit, HeatSides.Side side) {
        if (unit == HeatSides.Unit.KETTLE && side == HeatSides.Side.SHELL) return List.of("FEED", "VAPOUR_OUTLET", "LIQUID_OUTLET");
        return switch (side) {
            case TUBE -> List.of("TUBE_INLET", "TUBE_OUTLET");
            case SHELL -> List.of("SHELL_INLET", "SHELL_OUTLET");
            case PROCESS -> List.of("PROCESS_INLET", "PROCESS_OUTLET");
            case CONVECTION -> List.of("CONVECTION_INLET", "CONVECTION_OUTLET");
            case RADIANT -> List.of("RADIANT_INLET", "RADIANT_OUTLET");
        };
    }

    /** The drawing of {@code unit} seen from {@code side}, an exchanger's with the lines {@code design} asks for. */
    public static HeatDiagram of(HeatSides.Unit unit, HeatSides.Side side, ExchangerBlueprint.Design design) {
        var d = new HeatDiagram();
        switch (unit) {
            case EXCHANGER -> d.exchanger(design);
            case AIR_COOLER -> d.airCooler();
            case FIRED_HEATER -> d.heater(side);
            case KETTLE -> d.kettle();
        }
        return d;
    }

    // The exchanger: the shell across the middle, its channel head on the left; the shell's lines above, the tubes' to the left.

    private void exchanger(ExchangerBlueprint.Design design) {
        part(BODY, Glyph.HEAD, 146, 136, 24, 44, null);
        part(BODY, Glyph.SHELL, 170, 130, 180, 56, t("label.exchanger"));
        tag(260, 168, Align.CENTRE, Value.of(Show.DUTY, "duty"));
        nozzle("SHELL_INLET", 195, 130);
        nozzle("SHELL_OUTLET", 325, 130);
        nozzle("TUBE_INLET", 158, 180);
        nozzle("TUBE_OUTLET", 158, 136);

        // The shell's feed comes in at the top left and drops into its inlet; its outlet rises and leaves at the top right.
        var shell = design.shell();
        if (shell == ExchangerBlueprint.Lines.BYPASS) {
            wire(false, 104, 50, 191, 50);
            part("shell_inlet_tee", Glyph.TEE, 191, 46, 8, 8, null);
            wire(true, 195, 54, 195, 130);
            wire(true, 195, 46, 195, 18, 325, 18, 325, 46);
            valve("shell_bypass_valve", 248, 18);
            meter("shell_bypass_valve_meter", 284, 18);
            tag(266, 32, Align.CENTRE, Value.of(Show.FLOW, "part:shell_bypass_valve"));
            part("shell_outlet_tee", Glyph.TEE, 321, 46, 8, 8, null);
            wire(true, 325, 130, 325, 54);
            wire(true, 329, 50, 369, 50);
            // The tubes' ratio reads this feed, ahead of the tee.
            if (design.tube() == ExchangerBlueprint.Lines.RATIO) meter("shell_meter", 150, 50);
        } else {
            wire(true, 104, 50, 195, 50, 195, 130);
            wire(true, 325, 130, 325, 50, 369, 50);
            int x = 128;
            if (shell == ExchangerBlueprint.Lines.RATIO) {
                valve("shell_feed_valve", x, 50);
                meter("shell_feed_valve_meter", x + 24, 50);
                x += 48;
            }
            if (design.tube() == ExchangerBlueprint.Lines.RATIO) meter("shell_meter", x, 50);
        }
        pill(4, 50, 100, "SHELL_INLET", Value.of(Show.TEMPERATURE, "part:shell_inlet_tee", "port:SHELL_INLET"));
        pill(370, 50, 74, "SHELL_OUTLET", Value.of(Show.TEMPERATURE, "part:shell_outlet_tee", "port:SHELL_OUTLET"));
        tag(201, 92, Align.LEFT, Value.of(Show.BOTH, "port:SHELL_INLET"));
        tag(319, 92, Align.RIGHT, Value.of(Show.TEMPERATURE, "port:SHELL_OUTLET"));

        // The tubes' feed comes in at the bottom left and rises into the head; their outlet rises from its top and leaves left.
        var tube = design.tube();
        if (tube == ExchangerBlueprint.Lines.BYPASS) {
            wire(false, 84, 224, 122, 224);
            part("tube_inlet_tee", Glyph.TEE, 122, 220, 8, 8, null);
            wire(true, 130, 224, 158, 224, 158, 180);
            wire(true, 126, 220, 126, 104);
            valveDown("tube_bypass_valve", 126, 178);
            meter("tube_bypass_valve_meter", 126, 144);
            tag(110, 198, Align.RIGHT, Value.of(Show.FLOW, "part:tube_bypass_valve"));
            part("tube_outlet_tee", Glyph.TEE, 122, 96, 8, 8, null);
            wire(true, 158, 136, 158, 100, 130, 100);
            wire(true, 122, 100, 85, 100);
            if (shell == ExchangerBlueprint.Lines.RATIO) meter("tube_meter", 103, 224);
        } else {
            wire(true, 84, 224, 158, 224, 158, 180);
            wire(true, 158, 136, 158, 100, 85, 100);
            int x = 100;
            if (tube == ExchangerBlueprint.Lines.RATIO) {
                valve("tube_feed_valve", x, 224);
                meter("tube_feed_valve_meter", x + 20, 224);
                x += 42;
            }
            if (shell == ExchangerBlueprint.Lines.RATIO) meter("tube_meter", x, 224);
        }
        pill(4, 224, 80, "TUBE_INLET", Value.of(Show.TEMPERATURE, "part:tube_inlet_tee", "port:TUBE_INLET"));
        pill(4, 100, 80, "TUBE_OUTLET", Value.of(Show.TEMPERATURE, "part:tube_outlet_tee", "port:TUBE_OUTLET"));
        tag(164, 204, Align.LEFT, Value.of(Show.BOTH, "port:TUBE_INLET"));
        tag(164, 118, Align.LEFT, Value.of(Show.TEMPERATURE, "port:TUBE_OUTLET"));
    }

    // The air cooler: its bundle across, the fans below it blowing air up through it.

    private void airCooler() {
        part(BODY, Glyph.BOX, 150, 60, 150, 46, t("label.air_cooler"));
        tag(225, 94, Align.CENTRE, Value.of(Show.DUTY, "duty"));
        nozzle("PROCESS_INLET", 150, 83);
        nozzle("PROCESS_OUTLET", 300, 83);
        wire(true, 104, 83, 150, 83);
        wire(true, 300, 83, 339, 83);
        pill(4, 83, 100, "PROCESS_INLET", Value.of(Show.TEMPERATURE, "port:PROCESS_INLET"));
        pill(340, 83, 100, "PROCESS_OUTLET", Value.of(Show.TEMPERATURE, "port:PROCESS_OUTLET"));
        tag(127, 72, Align.CENTRE, Value.of(Show.FLOW, "port:PROCESS_INLET"));
        for (int x : new int[]{185, 265}) {
            part(BODY, Glyph.FAN, x - 14, 126, 28, 28, null);
            utility(x, 125, x, 108);
            utility(x, 186, x, 156);
        }
        label(225, 140, t("label.fans"), Align.CENTRE);
        tag(225, 200, Align.CENTRE, Value.of(Show.TEMPERATURE, "air"));
    }

    // The fired heater: the convection section over the radiant box, the stack on top, the burners under the floor.

    private void heater(HeatSides.Side side) {
        part(BODY, Glyph.BOX, 220, 4, 20, 26, null);
        part(BODY, Glyph.BOX, 180, 30, 100, 50, t("label.convection"));
        part(BODY, Glyph.BOX, 160, 90, 140, 110, t("label.radiant"));
        utility(230, 89, 230, 81);
        nozzle("CONVECTION_INLET", 180, 45);
        nozzle("CONVECTION_OUTLET", 280, 65);
        nozzle("RADIANT_INLET", 300, 110);
        nozzle("RADIANT_OUTLET", 160, 180);
        wire(true, 104, 45, 180, 45);
        wire(true, 280, 65, 339, 65);
        wire(true, 340, 110, 300, 110);
        wire(true, 160, 180, 105, 180);
        pill(4, 45, 100, "CONVECTION_INLET", Value.of(Show.TEMPERATURE, "port:CONVECTION_INLET"));
        pill(340, 65, 100, "CONVECTION_OUTLET", Value.of(Show.TEMPERATURE, "port:CONVECTION_OUTLET"));
        pill(340, 110, 100, "RADIANT_INLET", Value.of(Show.TEMPERATURE, "port:RADIANT_INLET"));
        pill(4, 180, 100, "RADIANT_OUTLET", Value.of(Show.TEMPERATURE, "port:RADIANT_OUTLET"));
        tag(142, 34, Align.CENTRE, Value.of(Show.FLOW, "port:CONVECTION_INLET"));
        tag(320, 99, Align.CENTRE, Value.of(Show.FLOW, "port:RADIANT_INLET"));
        tag(230, side == HeatSides.Side.CONVECTION ? 68 : 160, Align.CENTRE, Value.of(Show.DUTY, "duty"));
        for (int x : new int[]{195, 230, 265}) {
            part(BODY, Glyph.BURNER, x - 5, 202, 10, 10, null);
            utility(x, 201, x, 190);
        }
        label(230, 224, t("label.burners"), Align.CENTRE);
    }

    // The kettle: the shell a lying drum, the tube bundle low in it, the weir at its far end.

    private void kettle() {
        part(BODY, Glyph.HEAD, 126, 110, 24, 50, null);
        part(BODY, Glyph.DRUM, 150, 90, 190, 90, t("label.kettle"));
        utility(166, 141, 284, 141, 284, 170, 166, 170, 166, 141);
        utility(300, 132, 300, 179);
        tag(245, 148, Align.CENTRE, Value.of(Show.DUTY, "duty"));
        nozzle("TUBE_INLET", 138, 160);
        nozzle("TUBE_OUTLET", 126, 124);
        nozzle("FEED", 220, 180);
        nozzle("VAPOUR_OUTLET", 245, 90);
        nozzle("LIQUID_OUTLET", 320, 180);
        wire(true, 104, 205, 138, 205, 138, 160);
        wire(true, 126, 124, 105, 124);
        wire(true, 104, 236, 220, 236, 220, 180);
        wire(true, 245, 90, 245, 40, 339, 40);
        wire(true, 320, 180, 320, 214, 339, 214);
        pill(4, 205, 100, "TUBE_INLET", Value.of(Show.TEMPERATURE, "port:TUBE_INLET"));
        pill(4, 124, 100, "TUBE_OUTLET", Value.of(Show.TEMPERATURE, "port:TUBE_OUTLET"));
        pill(4, 236, 100, "FEED", Value.of(Show.TEMPERATURE, "port:FEED"));
        pill(340, 40, 100, "VAPOUR_OUTLET", Value.of(Show.TEMPERATURE, "port:VAPOUR_OUTLET"));
        pill(340, 214, 100, "LIQUID_OUTLET", Value.of(Show.TEMPERATURE, "port:LIQUID_OUTLET"));
        tag(144, 186, Align.LEFT, Value.of(Show.FLOW, "port:TUBE_INLET"));
        tag(226, 222, Align.LEFT, Value.of(Show.FLOW, "port:FEED"));
        tag(251, 64, Align.LEFT, Value.of(Show.FLOW, "port:VAPOUR_OUTLET"));
        tag(326, 196, Align.LEFT, Value.of(Show.FLOW, "port:LIQUID_OUTLET"));
    }

    private static Text t(String key, Object... args) {
        return Text.of(key, args);
    }

    private void part(String slot, Glyph glyph, int x, int y, int w, int h, @Nullable Text label) {
        parts.add(new Part(slot, glyph, x, y, w, h, label));
    }

    /** A control valve whose bow tie is centred on ({@code x}, {@code y}) of a line across; its stem and actuator rise above it. */
    private void valve(String slot, int x, int y) {
        part(slot, Glyph.VALVE, x - 8, y - 17, 16, 23, null);
    }

    /** A control valve on a line down, centred on ({@code x}, {@code y}); its stem and actuator stand to the left. */
    private void valveDown(String slot, int x, int y) {
        part(slot, Glyph.VALVE_DOWN, x - 17, y - 8, 23, 16, null);
    }

    private void meter(String slot, int x, int y) {
        part(slot, Glyph.METER, x - 8, y - 8, 16, 16, null);
    }

    private void wire(boolean arrow, int... points) {
        wires.add(new Wire(points, arrow, false));
    }

    private void utility(int... points) {
        wires.add(new Wire(points, false, true));
    }

    private void pill(int x, int y, int w, String role, Value value) {
        pills.add(new Pill(x, y, w, role, port(role), value));
    }

    /** A nozzle's stream by name ("Shell in"), as the pills and the Overview's stream switch say it. */
    public static Text port(String role) {
        return Text.of("port." + role.toLowerCase(java.util.Locale.ROOT));
    }

    private void tag(int x, int y, Align align, Value value) {
        tags.add(new Tag(x, y, align, value));
    }

    private void label(int x, int y, Text text, Align align) {
        labels.add(new Label(x, y, text, align));
    }

    private void nozzle(String role, int x, int y) {
        nozzles.add(new Nozzle(role, x, y));
    }
}
