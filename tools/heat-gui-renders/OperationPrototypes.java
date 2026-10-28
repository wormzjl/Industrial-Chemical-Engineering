import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import java.util.List;
import java.util.Map;

/**
 * Prototypes of the column's Operation tab (batch 2026-10-28-column-operation, C7, plan section 13): the assembly screen's
 * frame (title, the Structure and Operation tabs, the status once), the ports and the duties and the last solve as tables on the HMI glass,
 * the door column with the run switch, the set points per unit feed, Close and Apply on the footer.
 * Two options for the page's size: A at the assembly screen's design size (464 by 254; the glass shows the ports or the
 * duties, the door scrolls), B at a taller design height (464 by 360, the 1920 by 1080 and 2560 by 1440 windows' GUI size
 * 640 by 360; everything in one view). The data are illustrative: a crude column at 86 % of its feed, its cooling water short.
 */
final class OperationPrototypes {
    private OperationPrototypes() {
    }

    static List<GuiPrototypes.Prototype> operation() {
        return List.of(new GuiPrototypes.Prototype("operation-a-design-size", 464, 254, (d, s) -> door(d, false), true),
                new GuiPrototypes.Prototype("operation-b-taller-page", 640, 360, (d, s) -> door(d, true)));
    }

    /** The ports: stream, rate, temperature, pressure at the port, its buffer's fill, its valve's opening. */
    static final List<String[]> PORTS = List.of(new String[]{"Port", "kmol/h", "°C", "bar", "Held %", "Valve %"},
            new String[]{"Feed", "103.2", "340.0", "2.40", "35", "62"},
            new String[]{"Steam", "5.2", "400.0", "3.50", "30", "41"},
            new String[]{"Off-gas", "1.8", "45.0", "1.30", "48", "22"},
            new String[]{"Distillate", "27.0", "45.0", "1.30", "51", "57"},
            new String[]{"Sour water", "5.3", "45.0", "1.30", "40", "18"},
            new String[]{"Kerosene", "16.0", "190.0", "1.62", "55", "46"},
            new String[]{"Diesel", "19.4", "262.0", "1.75", "52", "49"},
            new String[]{"Bottoms", "38.9", "331.0", "1.95", "58", "64"});

    /** The duties: what the column booked at its throughput, what the last solve gave at the set feed. */
    static final List<String[]> DUTIES = List.of(new String[]{"Duty", "Booked MW", "Solved MW"},
            new String[]{"Condenser", "-7.32", "-8.51"},
            new String[]{"Pumparound 1", "-1.81", "-2.10"},
            new String[]{"Pumparound 2", "-2.62", "-3.05"});

    static final Map<String, String> SET = Map.of("feed", "120.0", "condenser", "45.0", "reflux", "2.50", "steam", "0.050",
            "kerosene", "0.155", "diesel", "0.188", "pa1", "-63.0", "pa2", "-91.5");

    static List<Column.Row> rows() {
        return GuiPrototypes.Door.rows(
                GuiPrototypes.Door.group("Control", null, GuiPrototypes.Door.switchRow("Off", "On", true)),
                GuiPrototypes.Door.group("Set points", null,
                        GuiPrototypes.Door.field("feed", "Feed", "kmol/h"),
                        GuiPrototypes.Door.field("condenser", "Condenser", "°C"),
                        GuiPrototypes.Door.field("reflux", "Reflux ratio", ""),
                        GuiPrototypes.Door.field("steam", "Steam", "mol/mol"),
                        GuiPrototypes.Door.field("kerosene", "Kerosene", "mol/mol"),
                        GuiPrototypes.Door.field("diesel", "Diesel", "mol/mol"),
                        GuiPrototypes.Door.field("pa1", "Pumparound 1", "kJ/mol"),
                        GuiPrototypes.Door.field("pa2", "Pumparound 2", "kJ/mol")));
    }

    /** The last solve: its route in its heading, what started it, how long ago, how long it took. */
    static final List<String[]> SOLVE = List.of(new String[]{"Last solve", "Warm route"}, new String[]{"Trigger", "Feed temperature"},
            new String[]{"Age", "45 s"}, new String[]{"Took", "0.33 s"});

    /**
     * The Operation tab: {@code all} draws both tables on the glass (option B); otherwise the glass has its own two tabs,
     * Ports latched, and Duties latched on the sheet scrolled to its end (option A).
     */
    static void door(GuiPrototypes.Door d, boolean all) {
        var t = GuiPrototypes.DIALOG;
        d.title(t.get(AssemblyColumn.KEY + "title"));
        var tabs = AssemblyColumn.tabs(true, true, t);
        var tabSlots = AssemblyColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        d.skin.status(d.c, AssemblyColumn.display(d.w), "Limited: condenser, 86 % of the feed", Palette.WARN);
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - AssemblyColumn.DIVIDER);
        var box = d.well(AssemblyColumn.glass(d.w, d.h, true));
        d.c.clip(box);
        int cell = Math.min(56, (box.w() - 70) / 5);
        int[] ports = {box.w() - 5 * cell, cell, cell, cell, cell, cell}, duties = {box.w() - 2 * cell - 24, cell + 12, cell + 12},
                solve = {box.w() - 2 * cell - 24, 2 * cell + 24};
        if (all) {
            int y = table(d, new Rect(box.x(), box.y(), box.w(), box.h()), ports, PORTS);
            y = table(d, new Rect(box.x(), y + 8, box.w(), box.bottom() - y - 8), duties, DUTIES);
            table(d, new Rect(box.x(), y + 8, box.w(), box.bottom() - y - 8), solve, SOLVE);
        } else {
            int tw = (box.w() - 3) / 2;
            d.skin.button(d.c, new Rect(box.x(), box.y(), tw, 16), "Ports", d.end ? Skin.Press.REST : Skin.Press.CURRENT, false);
            d.skin.button(d.c, new Rect(box.x() + tw + 3, box.y(), tw, 16), "Duties", d.end ? Skin.Press.CURRENT : Skin.Press.REST, false);
            var under = new Rect(box.x(), box.y() + 20, box.w(), box.h() - 20);
            if (d.end) {
                int y = table(d, under, duties, DUTIES);
                table(d, new Rect(box.x(), y + 8, box.w(), box.bottom() - y - 8), solve, SOLVE);
            } else table(d, under, ports, PORTS);
        }
        d.c.unclip();
        // The door column in its view, narrowed for its bar where it is taller than its area (KitDoor).
        var area = AssemblyColumn.area(d.w, d.h);
        var rows = rows();
        int tall = Column.place(rows, area.x(), area.y(), area.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        var column = Column.place(rows, area.x(), area.y(), tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w(), d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(area.x() - 2, area.y(), area.w() + 4, area.h()), area.right() - ScrollView.BAR + 2, column.bounds().h());
        while (d.end && view.offset() < view.max()) view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, -1);
        d.c.clip(view.viewport());
        column.draw(d.c, -view.offset(), d.skin);
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f && view.visible(p.slots().get(f.key())))
                d.skin.field(d.c, view.onScreen(p.slots().get(f.key())), SET.getOrDefault(f.key(), ""), "", false);
            else if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (view.visible(r)) d.skin.button(d.c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
        }
        d.c.unclip();
        view.drawBar(d.c, d.skin);
        var footer = AssemblyColumn.footer(d.w, d.h);
        d.skin.button(d.c, footer.get(0), t.get("fluid.close"), Skin.Press.REST, false);
        d.skin.button(d.c, footer.get(1), "Apply", Skin.Press.PRIMARY, false);
        if (!d.end && d.skin == PanelSkin.LIGHT)
            System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d: door column %d px wide, %d px tall in a %d px view, glass %d by %d px%n", all ? "operation B" : "operation A",
                    d.w, d.h, area.w(), tall, area.h(), box.w(), box.h());
    }

    /** A table on the glass: the header muted under a rule, every second entry on a band, numbers right-aligned; returns the bottom of its last line. */
    static int table(GuiPrototypes.Door d, Rect box, int[] widths, List<String[]> rows) {
        int y = box.y() + 2, pitch = 12;
        for (int r = 0; r < rows.size() && y + 10 <= box.bottom(); r++, y += pitch) {
            if (r > 0 && r % 2 == 0) d.c.fill(box.x(), y - 2, box.right(), y + pitch - 2, DialogColumns.BAND);
            int x = box.x() + 3;
            for (int i = 0; i < widths.length; i++) {
                String text = rows.get(r)[i];
                int colour = r == 0 ? Palette.MUTED : i == 0 ? Palette.TEXT : Palette.ACCENT;
                d.c.text(text, i == 0 ? x : x + widths[i] - 6 - d.m.width(text), y, colour);
                x += widths[i];
            }
            if (r == 0) d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        }
        return y;
    }
}
