package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;

import static com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing.*;

/**
 * Size all's proposal ({@link SheetSizing}; {@code SIZING_PLAN.md} section 3.1): every Auto item's size applied to a copy of the
 * sheet, its column doors and member counts with it, and the run once more where that moves it.
 */
public final class Proposal {
    private Proposal() {
    }

    /**
     * Size all (section 3.1): proposes every Auto item's size, applies the proposals to a copy of {@code sheet}, and where that
     * moves the run (a column's diameter, a sized unit's drops) runs the copy once, warm from {@code run}, and rates every item on
     * what it gives. Nothing is sized twice. A run that is not steady sizes nothing: the sheet stays, every item red. Its re-run tells
     * {@code progress} where it stands, as a run does.
     */
    public static SizeAll sizeAll(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, FlowsheetSolver.Progress progress) {
        if (run.status() != FlowsheetRun.Status.STEADY) return new SizeAll(sheet, run, rate(sheet, run, thermo));
        var proposal = pass(sheet, run, thermo, Entries.of(sheet, false));
        var sized = applied(sheet, proposal);
        if (!moves(sheet, sized)) return new SizeAll(sized, run, proposal);
        var rerun = FlowsheetEngine.run(sized, thermo.catalog(), thermo.checkpoint(), run, progress);
        if (rerun.status() == FlowsheetRun.Status.STOPPED) throw new CancellationException();
        return new SizeAll(sized, rerun, rate(sized, rerun, thermo));
    }

    /**
     * {@code sheet} with every Auto item at {@code proposal}'s size: a column's door at its diameter and at its Auto members' counts
     * (S3b), every other size a fact; a Fixed item stays as the player keeps it. Facts of an item the sheet no longer holds go. A
     * copy: {@code sheet} is unchanged.
     */
    public static Flowsheet applied(Flowsheet sheet, Sized proposal) {
        var facts = sheet.sizing();
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) {
            var json = unit.type().equals(COLUMN) ? door(unit, facts, proposal) : null;
            units.add(json == null ? unit : new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new Params(json), unit.unknown().deepCopy()));
        }
        for (var entry : proposal.units().entrySet()) {
            var held = facts.unit(entry.getKey());
            var size = entry.getValue().size();
            if (held.mode() == SizingFacts.Mode.AUTO && !(size instanceof ColumnSizing.Size) && !(size instanceof ValveSizing.Size)) facts = facts.with(entry.getKey(), held.with(size));
        }
        for (var entry : proposal.lines().entrySet()) {
            var held = facts.line(entry.getKey());
            if (held.mode() == SizingFacts.Mode.AUTO) facts = facts.with(entry.getKey(), held.with(entry.getValue().size()));
        }
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), pruned(sheet, facts), sheet.unknown());
    }

    /**
     * A column's door as {@code proposal} leaves it, null where nothing moves: the column's diameter where the column is on Auto, and
     * each member's count where that member is on Auto: the condenser's, an air-then-water condenser's trim's (C2), the kettle's and
     * each pumparound cooler's. Its condenser's type stays the player's.
     */
    private static com.google.gson.@Nullable JsonObject door(Flowsheet.Unit unit, SizingFacts facts, Sized proposal) {
        var door = ColumnDoor.of(unit.params());
        var json = unit.params().json().deepCopy();
        boolean moved = false;
        if (facts.unit(unit.id()).mode() == SizingFacts.Mode.AUTO && proposal.units().get(unit.id()) instanceof SizedUnit sized && sized.size() instanceof ColumnSizing.Size size
                && size.diameter() != door.diameter()) {
            json.addProperty("diameter", size.diameter());
            moved = true;
        }
        var counts = door.units();
        Integer condenser = proposedCount(unit.id(), "condenser", facts, proposal), kettle = proposedCount(unit.id(), "reboiler", facts, proposal), trim = proposedCount(unit.id(), TRIM, facts, proposal);
        if (door.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER && trim != null && trim != counts.trim()) {
            json.addProperty(ColumnDoor.TRIM_UNITS, trim);
            moved = true;
        }
        if (condenser != null && condenser != counts.condenser()) {
            json.addProperty(ColumnDoor.CONDENSER_UNITS, condenser);
            moved = true;
        }
        if (kettle != null && kettle != counts.kettle()) {
            json.addProperty(ColumnDoor.KETTLE_UNITS, kettle);
            moved = true;
        }
        for (int i = 0; i < door.pumparounds().size(); i++) {
            Integer cooler = proposedCount(unit.id(), PumparoundMember.name(door.pumparounds().get(i).id(), PumparoundMember.COOLER), facts, proposal);
            if (cooler != null && cooler != counts.cooler(i) && json.get("pumparounds") instanceof com.google.gson.JsonArray rows && rows.get(i) instanceof com.google.gson.JsonObject row) {
                row.addProperty(ColumnDoor.UNITS, cooler);
                moved = true;
            }
        }
        return moved ? json : null;
    }

    /** How many units Auto proposes for {@code block}'s member {@code member}: null where the member is Fixed or has no size. */
    private static @Nullable Integer proposedCount(String block, String member, SizingFacts facts, Sized proposal) {
        String id = member(block, member);
        if (facts.unit(id).mode() != SizingFacts.Mode.AUTO || !(proposal.units().get(id) instanceof SizedUnit sized) || sized.size() == null) return null;
        return sized.size().count();
    }

    /** {@code facts} without the rows of items {@code sheet} no longer holds: a member goes with its block, a line with its stream. */
    public static SizingFacts pruned(Flowsheet sheet, SizingFacts facts) {
        var ids = new java.util.HashSet<String>();
        for (var unit : sheet.units()) ids.add(unit.id());
        for (var stream : sheet.streams()) ids.add(stream.id());
        var kept = facts;
        for (var id : facts.units().keySet()) if (!ids.contains(root(id))) kept = kept.with(id, SizingFacts.UnitFacts.AUTO);
        for (var id : facts.lines().keySet()) if (!ids.contains(root(id))) kept = kept.with(id, SizingFacts.LineFacts.AUTO);
        return kept;
    }

    /** The item an id belongs to: a member's block. */
    private static String root(String id) {
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(0, slash);
    }

    /** Whether {@code after}'s facts move its run from {@code before}'s: a column's diameter, or a size a passage's law reads. */
    private static boolean moves(Flowsheet before, Flowsheet after) {
        for (int i = 0; i < after.units().size(); i++) {
            var was = before.units().get(i);
            var now = after.units().get(i);
            if (now.type().equals(COLUMN) && ColumnDoor.of(now.params()).diameter() != ColumnDoor.of(was.params()).diameter()) return true;
            // A member's count moves the run through its passage's law, which SizedPassages reads with the door's counts.
            if (!SizedPassages.reads(after.sizing(), now).equals(SizedPassages.reads(before.sizing(), was))) return true;
        }
        return false;
    }
}
