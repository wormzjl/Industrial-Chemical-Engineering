import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.OperationColumn;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.column.operation.ColumnCycle;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The column's Operation tab (batch 2026-10-28-column-operation, C7; the owner chose layout B on 2026-10-29), drawn through
 * the shipped {@link OperationColumn} in the assembly screen's frame ({@link AssemblyColumn}): the status once, the ports,
 * duties and last solve on the HMI glass, Control and the set points on the door, Close and Apply on the footer. At the
 * 1920 by 1080 window's size (scale 3 for the page's 360 px design height: 640 by 360 GUI px, the image 624 by 344), and at
 * a narrow window's (1024 by 768, scale 2: the image 496 by 368), where the largest design's door scrolls. The data are
 * illustrative: a crude column at 86 % of its feed, its cooling water short.
 */
final class OperationPrototypes {
    private OperationPrototypes() {
    }

    static final OperationColumn.Units UNITS = new OperationColumn.Units(TemperatureUnit.CELSIUS, PressureUnit.BAR, FlowRateUnit.PER_HOUR);

    static List<GuiPrototypes.Prototype> operation() {
        return List.of(new GuiPrototypes.Prototype("operation-1920x1080", 624, 344, (d, s) -> door(d, crude(1, 2, 2))),
                new GuiPrototypes.Prototype("operation-narrow-largest", 496, 368, (d, s) -> door(d, crude(2, 4, 4)), true));
    }

    /** A crude column with {@code steam} inlets, {@code draws} and {@code pumparounds}, at 86 % of its 120 kmol/h feed. */
    static OperationView crude(int steam, int draws, int pumparounds) {
        double design = 120 / 3.6, alpha = .86;
        double[] s = new double[steam], d = new double[draws], p = new double[pumparounds];
        for (int j = 0; j < steam; j++) s[j] = .05 / steam;
        for (int j = 0; j < draws; j++) d[j] = .155 + .033 * j;
        for (int j = 0; j < pumparounds; j++) p[j] = -63000 - 28500 * j;
        var set = new Setpoints(design, design, 318.15, 2.5, 0, s, d, p);
        double f = design * alpha;
        var streams = new ArrayList<OperationView.Stream>();
        streams.add(new OperationView.Stream(Port.FEED, 0, f, 613.15, 240000, .35, .62));
        for (int j = 0; j < steam; j++) streams.add(new OperationView.Stream(Port.STEAM, j, f * s[j], 673.15, 350000, .30, .41));
        streams.add(new OperationView.Stream(Port.OFF_GAS, 0, f * .0175, 318.15, 130000, .48, .22));
        streams.add(new OperationView.Stream(Port.SOUR_WATER, 0, f * .051, 318.15, 130000, .40, .18));
        streams.add(new OperationView.Stream(Port.DISTILLATE, 0, f * .262, 318.15, 130000, .51, .57));
        for (int j = 0; j < draws; j++) streams.add(new OperationView.Stream(Port.DRAW, j, f * d[j], 463.15 + 72 * j, 162000 + 13000 * j, .55 - .03 * j, .46 + .03 * j));
        streams.add(new OperationView.Stream(Port.BOTTOMS, 0, f * .377, 604.15, 195000, .58, .64));
        var duties = new ArrayList<OperationView.Duty>();
        duties.add(new OperationView.Duty(OperationView.DutyKind.CONDENSER, 0, -8.51e6 * alpha, -8.51e6));
        for (int j = 0; j < pumparounds; j++) duties.add(new OperationView.Duty(OperationView.DutyKind.PUMPAROUND, j, p[j] * f, p[j] * design));
        return new OperationView(OperationView.Phase.LIMITED, ColumnCycle.Limit.CONDENSER, alpha, false, null, false,
                "LIMITED: condenser, 86 %", true, true, set, streams, duties, new OperationView.Solve(DriftBands.Trigger.ENTHALPY, true, 45));
    }

    /** The Operation page as AssemblyScreen draws it, through the shipped OperationColumn. */
    static void door(GuiPrototypes.Door d, OperationView view) {
        var t = GuiPrototypes.DIALOG;
        d.title(t.get(AssemblyColumn.KEY + "title"));
        var tabs = AssemblyColumn.tabs(true, true, t);
        var tabSlots = AssemblyColumn.tabSlots(d.w);
        for (int i = 0; i < tabs.size(); i++) {
            var spec = tabs.get(i);
            d.skin.button(d.c, tabSlots.get(i), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
        }
        var status = OperationColumn.status(view, t);
        d.skin.status(d.c, AssemblyColumn.display(d.w), status.text(), status.palette());
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - AssemblyColumn.DIVIDER);
        var box = d.well(AssemblyColumn.glass(d.w, d.h, true));
        // The tables, scrolled with the door to their end where they are taller than the glass.
        int need = OperationColumn.height(view);
        var glassView = new ScrollView();
        glassView.layout(box, box.right() - ScrollView.BAR, need);
        while (d.end && glassView.offset() < glassView.max()) glassView.wheel(box.x() + 2, box.y() + 2, -1);
        var lines = need > box.h() ? new Rect(box.x(), box.y(), box.w() - ScrollView.BAR - 2, box.h()) : box;
        d.c.clip(box);
        OperationColumn.glass(d.c, lines, glassView.offset(), view, UNITS, t, d.m);
        d.c.unclip();
        glassView.drawBar(d.c, d.skin);
        // The door column in its view, narrowed for its bar where it is taller than its area (KitDoor).
        var in = new OperationColumn.Input(view, false, UNITS);
        var area = AssemblyColumn.area(d.w, d.h);
        var rows = OperationColumn.rows(in, t);
        int tall = Column.place(rows, area.x(), area.y(), area.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        var column = Column.place(rows, area.x(), area.y(), tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w(), d.m, Column.Metrics.COMPACT);
        var scroll = new ScrollView();
        scroll.layout(new Rect(area.x() - 2, area.y(), area.w() + 4, area.h()), area.right() - ScrollView.BAR + 2, column.bounds().h());
        while (d.end && scroll.offset() < scroll.max()) scroll.wheel(scroll.viewport().x() + 2, scroll.viewport().y() + 2, -1);
        var values = OperationColumn.values(view.setpoints(), UNITS);
        d.c.clip(scroll.viewport());
        column.draw(d.c, -scroll.offset(), d.skin);
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f && scroll.visible(p.slots().get(f.key())))
                d.skin.field(d.c, scroll.onScreen(p.slots().get(f.key())), values.getOrDefault(f.key(), ""), "", false);
            else if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (scroll.visible(r)) d.skin.button(d.c, scroll.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
        }
        d.c.unclip();
        scroll.drawBar(d.c, d.skin);
        var footer = AssemblyColumn.footer(d.w, d.h);
        var keys = OperationColumn.footerButtons(OperationColumn.applies(in, java.util.Map.of()), t);
        for (int i = 0; i < keys.size(); i++)
            d.skin.button(d.c, footer.get(i), keys.get(i).text(), keys.get(i).key().equals("apply") ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        if (!d.end && d.skin == PanelSkin.LIGHT)
            System.out.printf(java.util.Locale.ROOT, "%-24s %d by %d: door column %d px wide, %d px tall in a %d px view; tables %d px on a %d px glass; %d keys%n", "operation",
                    d.w, d.h, area.w(), tall, area.h(), OperationColumn.height(view), box.h(), ConstructorColumn.buttons(rows).size());
    }
}
