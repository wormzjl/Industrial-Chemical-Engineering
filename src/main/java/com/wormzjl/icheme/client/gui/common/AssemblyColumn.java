package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;
import org.jetbrains.annotations.Nullable;

/**
 * A column's assembly screen as a panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 4): the title and the
 * theme key; the Structure and Operation tabs, the page shown latched; the status said once, on the message display; the
 * HMI glass at the left, with the drawing ({@link AssemblyFlowsheet}) and the parts list (each part's mark and word teal
 * connected, yellow misplaced, red missing, every second part on a band); the door column at the right, with Check (Check
 * parts, a summary line) and Selected (the part, Open unit); Close and Datasheet on the footer. Each group's widget rows
 * come before its texts, and the summary is one line in every state, so no widget moves while the engine's answers come.
 * Texts are keys under {@code gui.icheme.}. No Minecraft classes: the screen, the gates and the render tool place the
 * same door.
 */
public final class AssemblyColumn {
    private AssemblyColumn() {
    }

    /** The screen's keys under {@code gui.icheme.}. */
    public static final String KEY = "assembly.";
    /** The door column's and the glass's top (under the message display); the room under the glass (the footer); the footer's divider above the door's foot. */
    public static final int TOP = 74, FOOT = 38, DIVIDER = 35;
    /** The parts list's narrowest and widest, the gap between the drawing and the list, a part's mark and its room before the name. */
    public static final int LIST_MIN = 110, LIST_MAX = 260, GAP = 10, MARK = 10;

    // ---- The frame ----

    /** The door column's width on a door {@code width} wide: 134 to 190 px, so the parts list keeps room for its names (narrowed for its bar, the summary still in one line). */
    public static int width(int width) {
        return Math.clamp(width * 9 / 32, 134, 190);
    }

    /** The door column's area: at the right, 10 px in, from {@link #TOP} to the footer. */
    public static Rect area(int width, int height) {
        int w = width(width);
        return new Rect(width - 10 - w, TOP, w, height - FOOT - TOP);
    }

    /** The HMI glass: from x 5 to 6 px before the door column (5 px before the door's edge where there is none), from 3 px above {@link #TOP} to the footer. */
    public static Rect glass(int width, int height, boolean column) {
        int right = column ? area(width, height).x() - 6 : width - 5;
        return new Rect(5, TOP - 3, right - 5, height - FOOT - TOP + 3);
    }

    /** The message display's text line under the tabs; its window lies round it ({@code Skin.status}). */
    public static Rect display(int width) {
        return new Rect(10, 59, width - 20, 9);
    }

    /** The theme key on the title row. */
    public static Rect theme(int width) {
        return new Rect(width - 28, 5, 18, 18);
    }

    /** The title's room: the title row less the theme key. */
    public static int title(int width) {
        return width - 54;
    }

    /** The tabs' slots at y 29, as every fluid screen places its tabs ({@code FluidGuiLayout.tabs}). */
    public static List<Rect> tabSlots(int width) {
        return FluidGuiLayout.tabs(width, 2).stream().map(s -> new Rect(s.x(), 29, s.width(), 18)).toList();
    }

    /** The footer's push buttons, Close and Datasheet, sharing the door's width. */
    public static List<Rect> footer(int width, int height) {
        return DialogColumns.footer(width, height, 2);
    }

    /** The glass's inside split: the drawing at the left, the parts list at the right. */
    public record Split(Rect drawing, Rect list) {
    }

    /**
     * The glass's inside {@code box} split: the list takes what the drawing leaves at the drawing's full height, from
     * {@link #LIST_MIN} to {@link #LIST_MAX} (the drawing narrower, and so smaller, where the list needs its least); the
     * drawing's part is the rest, the drawing centred in it.
     */
    public static Split split(Rect box) {
        int byHeight = (int) Math.ceil(AssemblyDiagram.WIDTH * box.h() / (double) AssemblyDiagram.HEIGHT);
        int list = Math.min(box.w(), Math.min(LIST_MAX, Math.max(LIST_MIN, box.w() - byHeight - GAP)));
        return new Split(new Rect(box.x(), box.y(), Math.max(0, box.w() - list - GAP), box.h()), new Rect(box.right() - list, box.y(), list, box.h()));
    }

    // ---- Texts ----

    /** A drawing text in the player's language, its nested texts too. */
    public static String text(AssemblyDiagram.Text text, HeatControlColumn.Texts t) {
        var args = new Object[text.args().size()];
        for (int i = 0; i < args.length; i++) args[i] = text.args().get(i) instanceof AssemblyDiagram.Text nested ? text(nested, t) : text.args().get(i);
        return t.get(KEY + text.key(), args);
    }

    /** The status on the message display: its words, their tone, and the detail its hover gives (null: none). */
    public record Status(String text, int palette, @Nullable String hint) {
    }

    /**
     * The column's status in words, said once: no design; not checked yet; changed (where, on hover: no block
     * coordinates on the door); checked, with its counts; complete. {@code position} writes a packed block position.
     */
    public static Status status(AssemblyView view, LongFunction<String> position, HeatControlColumn.Texts t) {
        var report = view.report();
        return switch (view.status()) {
            case NO_DESIGN -> new Status(t.get(KEY + "status.no_design"), Palette.WARN, null);
            case NOT_VALIDATED -> view.changedAt() != AssemblyReport.NONE
                    ? new Status(t.get(KEY + "status.changed"), Palette.WARN, t.get(KEY + "status.changed.hint", position.apply(view.changedAt())))
                    : new Status(t.get(KEY + "status.not_validated"), Palette.WARN, null);
            case CHECKED -> new Status(t.get(KEY + "status.checked", report.connected(), report.misplaced(), report.missing()), Palette.WARN, null);
            case COMPLETE -> new Status(t.get(KEY + "status.complete"), Palette.ACCENT, null);
        };
    }

    // ---- The door column ----

    /**
     * What the door column reads: the parts the design asks for; the parts to fix (misplaced or missing; -1 before a
     * report); whether a check is queued; the selected part's name (null: none) and whether its unit opens.
     */
    public record Input(int parts, int fix, boolean queued, @Nullable String selected, boolean opens) {
    }

    /** The door column's input from {@code report} (null before the first). */
    public static Input input(int parts, @Nullable AssemblyReport report, boolean queued, @Nullable String selected, boolean opens) {
        return new Input(parts, report == null ? -1 : report.misplaced() + report.missing(), queued, selected, opens);
    }

    /**
     * Check: Check parts (it sends the check; it waits while one is queued and stands off once every part is in place),
     * then the summary line in one line: the parts to check, to fix or in place, or the check queued. Selected: the part
     * (or none), then Open unit. Keys: the buttons {@code check} and {@code open}.
     */
    public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t) {
        String summary = in.queued() ? t.get(KEY + "queued") : in.fix() < 0 ? t.get(KEY + "check.parts", in.parts())
                : in.fix() == 0 ? t.get(KEY + "check.done", in.parts()) : t.get(KEY + "check.fix", in.parts(), in.fix());
        var check = new Column.Group(t.get(KEY + "group.check"), t.get(KEY + "group.check.hint"), List.of(
                new Column.Buttons(List.of(new Column.Button("check", t.get(KEY + "validate"), !in.queued() && in.fix() != 0, null))),
                new Column.Line(summary, in.queued() ? Palette.MUTED : Palette.TEXT)));
        var selected = new Column.Group(t.get(KEY + "group.selected"), t.get(KEY + "group.selected.hint"), List.of(
                new Column.Line(in.selected() == null ? t.get(KEY + "selected.none") : in.selected(), in.selected() == null ? Palette.MUTED : Palette.TEXT),
                new Column.Buttons(List.of(new Column.Button("open", t.get(KEY + "open_unit"), in.opens(), t.get(KEY + "open_unit.hint"))))));
        return List.of(check, selected);
    }

    /** The tabs: Structure and Operation (keys {@code tab:structure}, {@code tab:operation}), the page shown latched; Operation only for a complete structure. */
    public static List<Column.Button> tabs(boolean operation, boolean complete, HeatControlColumn.Texts t) {
        return List.of(new Column.Button("tab:structure", t.get(KEY + "tab.structure"), operation, t.get(KEY + "tab.structure.hint"), !operation),
                new Column.Button("tab:operation", t.get(KEY + "tab.operation"), complete && !operation, t.get(KEY + "tab.operation.hint"), operation));
    }

    /** The footer's push buttons: Close, then Datasheet (keys {@code close}, {@code datasheet}). */
    public static List<Column.Button> footerButtons(HeatControlColumn.Texts t) {
        return List.of(new Column.Button("close", t.get("fluid.close"), true, null), new Column.Button("datasheet", t.get(KEY + "datasheet"), true, t.get(KEY + "datasheet.hint")));
    }

    // ---- The parts list on the glass ----

    /** A slot's state in one word, as a key under {@link #KEY}: connected, misplaced, missing or not loaded; not checked before a report. */
    public static String word(@Nullable SlotReport r) {
        if (r == null) return "word.not_validated";
        return "word." + switch (r.state().colour) {
            case TEAL -> "connected";
            case YELLOW -> "misplaced";
            case RED -> r.state() == AssemblyReport.State.NOT_LOADED ? "not_loaded" : "missing";
        };
    }

    /** Every word the list may show, for its word column's width: the column keeps its width whatever the report says. */
    public static List<String> words(HeatControlColumn.Texts t) {
        return List.of(t.get(KEY + "word.not_validated"), t.get(KEY + "word.connected"), t.get(KEY + "word.misplaced"), t.get(KEY + "word.missing"),
                t.get(KEY + "word.not_loaded"));
    }

    /** A part in the list: its name, its state word, and their colour (a mark and a word in the report's teal, yellow or red). */
    public record Part(String name, String word, int colour) {
    }

    /** The list's parts: every slot in the blueprint's order, named as the drawing names it, in the state {@code report} gives it (red before one). */
    public static List<Part> parts(List<Slot> slots, AssemblyDiagram diagram, @Nullable AssemblyReport report, HeatControlColumn.Texts t) {
        var out = new ArrayList<Part>();
        for (var slot : slots) {
            var part = diagram.part(slot);
            var r = AssemblyFlowsheet.slot(report, slot);
            out.add(new Part(part == null ? slot.part() : text(part.name(), t), t.get(KEY + word(r)), Look.of(AssemblyFlowsheet.colour(r)).stroke()));
        }
        return out;
    }

    /** A line of the list: a line of a part's name, the word on the part's line for it (else empty), the colour, the part's index, whether it is the part's first line. */
    public record PartLine(String name, String word, int colour, int entry, boolean first) {
    }

    /** The list's lines, the word column's width, and whether the list stacks (each word on the line under its name). */
    public record PartList(List<PartLine> lines, int words, boolean stacked) {
        public PartList {
            lines = List.copyOf(lines);
        }

        public int height() {
            return lines.size() * DialogColumns.PITCH + 4;
        }
    }

    /**
     * The parts list {@code width} wide: each part's mark, its name wrapped in its column, its word right-aligned on its
     * first line; the word column as wide as the widest of {@code words}. No name is cut: where a word of a name is wider
     * than its column, the list stacks, each name across the list and its word on the line under it.
     */
    public static PartList parts(List<Part> parts, List<String> words, int width, Measure m) {
        int wide = 0;
        for (var w : words) wide = Math.max(wide, m.width(w));
        int name = width - 3 - MARK - 6 - wide - 3, across = width - 3 - MARK - 3;
        boolean stacked = name < 1 || parts.stream().flatMap(p -> Arrays.stream(p.name().split(" "))).anyMatch(w -> m.width(w) > name);
        var lines = new ArrayList<PartLine>();
        for (int i = 0; i < parts.size(); i++) {
            var p = parts.get(i);
            var wrapped = m.wrap(p.name(), stacked ? across : name);
            for (int j = 0; j < wrapped.size(); j++) lines.add(new PartLine(wrapped.get(j), j == 0 && !stacked ? p.word() : "", p.colour(), i, j == 0));
            if (stacked) lines.add(new PartLine("", p.word(), p.colour(), i, false));
        }
        return new PartList(lines, wide, stacked);
    }

    /** The part whose line lies at canvas y {@code y} in {@code box}, the list moved up by {@code offset}; -1 for none. */
    public static int entry(PartList list, Rect box, int offset, double y) {
        if (y < box.y() || y >= box.bottom()) return -1;
        int i = (int) Math.floor((y - box.y() - 1 + offset) / DialogColumns.PITCH);
        return i >= 0 && i < list.lines().size() ? list.lines().get(i).entry() : -1;
    }

    /**
     * Draws {@code list} in {@code box} ({@link #parts}'s width wide), moved up by {@code offset}, in the screens'
     * palette: every second part on its band ({@link DialogColumns#BAND}), the {@code selected} one (an index, -1 none) on
     * the line colour; the mark on a part's first line. The caller clips to {@code box}.
     */
    public static void draw(Canvas c, PartList list, Rect box, int offset, int selected) {
        var m = c.measure();
        int y = box.y() + 2 - offset, right = box.right() - 3;
        for (var line : list.lines()) {
            if (y + DialogColumns.PITCH > box.y() && y < box.bottom()) {
                if (line.entry() == selected) c.fill(box.x(), y - 1, box.right(), y + DialogColumns.PITCH - 1, Palette.LINE);
                else if (line.entry() % 2 == 1) c.fill(box.x(), y - 1, box.right(), y + DialogColumns.PITCH - 1, DialogColumns.BAND);
                if (line.first()) c.fill(box.x() + 3, y + 1, box.x() + 9, y + 7, line.colour());
                if (!line.name().isEmpty()) c.text(line.name(), box.x() + 3 + MARK, y, Palette.TEXT);
                if (!line.word().isEmpty()) c.text(line.word(), right - m.width(line.word()), y, line.colour());
            }
            y += DialogColumns.PITCH;
        }
    }

    /** A sentence on the glass where it shows no drawing (no design; the operation page): wrapped to {@code box}, from its top left. */
    public static void note(Canvas c, String text, Rect box) {
        var lines = c.measure().wrap(text, box.w() - 6);
        for (int i = 0; i < lines.size(); i++) c.text(lines.get(i), box.x() + 3, box.y() + 2 + i * DialogColumns.PITCH, Palette.TEXT);
    }
}
