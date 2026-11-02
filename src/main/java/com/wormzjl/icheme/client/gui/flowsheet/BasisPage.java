package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Basis tab (batch 2026-11-02-process-simulator, sheet 16): the sheet's package, its model and solver numbers; its components on the glass. The package follows
 * the world's network, so the choice is one line: a sheet on another asks for it on Run. Tolerance reads in percent; threads are the client's own, so they wait for W7.
 */
public final class BasisPage {
    private BasisPage() {
    }

    public static List<Column.Row> rows(SheetContext x) {
        var pack = x.fluids().pack();
        var rows = new ArrayList<Column.Row>();
        rows.add(Door.group(x.text("basis.package"), new Column.Choice(MaterialCatalog.Package.label(pack.id()), null, true, "package")));
        rows.add(Door.group(x.text("basis.model"), new Column.Line(x.text("basis.eos"), Palette.TEXT), new Column.Line(x.text("basis.water"), Palette.TEXT), new Column.Line(pack.revision(), Palette.MUTED)));
        rows.add(Door.group(x.text("basis.solver"), Door.entry("tolerance", x.text("basis.tolerance"), "%"), Door.entry("passes", x.text("basis.passes"), "")));
        return rows;
    }

    public static Map<String, String> values(SheetContext x) {
        var settings = x.editor().sheet().settings();
        return Map.of("tolerance", SheetUnits.number(2, settings.flowTolerance() * 100), "passes", Integer.toString(settings.maxPasses()));
    }

    public static void entry(SheetContext x, String key, String text) {
        var value = SheetContext.number(text);
        if (value == null) return;
        var s = x.editor().sheet().settings();
        if (key.equals("tolerance") && value > 0) x.editor().settings(new FlowsheetSolver.Settings(value / 100, s.temperatureTolerance(), s.maxPasses(), s.lowestFactor(), s.highestFactor(), s.unknown()), "entry basis " + key);
        if (key.equals("passes") && value >= 1) x.editor().settings(new FlowsheetSolver.Settings(s.flowTolerance(), s.temperatureTolerance(), value.intValue(), s.lowestFactor(), s.highestFactor(), s.unknown()), "entry basis " + key);
    }

    public static String status(SheetContext x) {
        return x.text("basis.status", MaterialCatalog.Package.label(x.fluids().pack().id()), x.fluids().axis().size() - 1);
    }

    /** The first feed with a flow, whose share the table shows. */
    private static Flowsheet.Stream feed(SheetContext x) {
        for (var stream : x.editor().sheet().streams()) if (stream.feed() != null && stream.from() == null && !stream.feed().amounts().isEmpty()) return stream;
        return null;
    }

    public static void draw(Canvas c, SheetContext x, Rect box) {
        var feed = feed(x);
        String[] head = {x.text("basis.component"), x.text("basis.weight"), x.text("basis.boils", x.units().temperature().symbol()), feed == null ? "" : x.text("basis.share", feed.name()), x.text("basis.in_use")};
        int[] at = {0, 230, 320, 410, 510};
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < head.length; i++) c.text(head[i], x0 + at[i], y, Palette.MUTED);
        c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        var axis = x.fluids().axis();
        var properties = x.fluids().pack().properties();
        double total = 0;
        if (feed != null) for (double amount : feed.feed().amounts().values()) total += amount;
        c.clip(box);
        for (int r = 0; r < axis.size(); r++, y += 12) {
            if (r % 2 == 1) c.fill(box.x(), y - 2, box.right(), y + 10, DialogColumns.BAND);
            String id = axis.get(r);
            double amount = feed == null ? 0 : feed.feed().amounts().getOrDefault(id, 0.0);
            boolean used = used(x, id);
            double weight = r < properties.size() ? properties.get(r).molecularWeight() * 1000 : 18.015, boils = r < properties.size() ? properties.get(r).normalBoilingPoint() : 373.15;
            String[] cells = {x.fluids().names().apply(id), SheetUnits.number(1, weight), x.units().t(boils), total == 0 ? "" : SheetUnits.number(2, 100 * amount / total), x.text(used ? "basis.yes" : "basis.no")};
            for (int i = 0; i < 5; i++) c.text(cells[i], x0 + at[i], y, i == 0 ? Palette.TEXT : used ? Palette.ACCENT : Palette.MUTED);
        }
        c.unclip();
    }

    private static boolean used(SheetContext x, String component) {
        for (var stream : x.editor().sheet().streams()) if (stream.feed() != null && stream.feed().amounts().getOrDefault(component, 0.0) > 0) return true;
        return false;
    }
}
