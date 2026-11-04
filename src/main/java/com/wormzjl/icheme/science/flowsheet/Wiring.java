package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sheet's stream ends read as the roles its units' models name (batch 2026-11-02-process-simulator, PORT_SIDES_REVIEW.md option B,
 * owner 2026-11-04). A fixed port is its own role. A {@link Passage}'s two ends have no fixed inlet: an end that is a stream's
 * {@code to} is the inlet, one that is a stream's {@code from} the outlet, and the other end takes the other role. The first stream
 * on either end decides, as the first stream that names a port holds it. An end against that decision, both ends wired the same
 * way, plays no role and stays open, as an end on a port that is not live does.
 *
 * <p>Whatever reads a unit's inlet or outlet (the compiler, sizing, the doors and pages) reads the sheet through {@link #roles};
 * the drawing and the editor read the ends themselves.
 */
public final class Wiring {
    /** By unit, each passage end's passage. */
    private final Map<String, Map<String, Passage>> ends = new HashMap<>();
    /** By unit, each passage whose inlet a stream has decided, and that end. */
    private final Map<String, Map<Passage, String>> inlets = new HashMap<>();

    private Wiring() {
    }

    public static Wiring of(Flowsheet sheet) {
        return of(sheet, null);
    }

    /** The sheet's wiring with the stream {@code but} left out: what a lifted end may land on. */
    public static Wiring of(Flowsheet sheet, @Nullable String but) {
        var wiring = new Wiring();
        for (var unit : sheet.units()) {
            for (var passage : passages(unit)) {
                var byEnd = wiring.ends.computeIfAbsent(unit.id(), k -> new HashMap<>());
                byEnd.put(passage.a(), passage);
                byEnd.put(passage.b(), passage);
            }
        }
        for (var stream : sheet.streams()) {
            if (stream.id().equals(but)) continue;
            wiring.decide(stream.from(), false);
            wiring.decide(stream.to(), true);
        }
        return wiring;
    }

    /** A unit's passages by its model and settings: none for a removed type or settings that give no ports. */
    public static List<Passage> passages(Flowsheet.Unit unit) {
        var model = UnitTypes.model(unit.type());
        if (model == null) return List.of();
        try {
            return model.passages(unit.params());
        } catch (FlowsheetFailure noPorts) {
            return List.of();
        }
    }

    private void decide(Flowsheet.@Nullable End end, boolean inlet) {
        var passage = end == null ? null : passage(end.unit(), end.port());
        if (passage != null) inlets.computeIfAbsent(end.unit(), k -> new HashMap<>()).putIfAbsent(passage, inlet ? end.port() : passage.other(end.port()));
    }

    /** The passage {@code end} is an end of, null for a fixed port. */
    public @Nullable Passage passage(String unit, String end) {
        return ends.getOrDefault(unit, Map.of()).get(end);
    }

    /** The end that is {@code passage}'s inlet, null while no stream holds either end. */
    public @Nullable String inlet(String unit, Passage passage) {
        return inlets.getOrDefault(unit, Map.of()).get(passage);
    }

    /**
     * Whether a passage end is its passage's inlet: as a stream decided, or, while none has, {@code a}, the end drawn where an inlet
     * stood. Null for a fixed port.
     */
    public @Nullable Boolean isInlet(String unit, String end) {
        var passage = passage(unit, end);
        if (passage == null) return null;
        var decided = inlet(unit, passage);
        return (decided != null ? decided : passage.a()).equals(end);
    }

    /** Whether {@code end} is a passage end that no stream decides: it takes a stream's start or its end. */
    public boolean free(String unit, String end) {
        var passage = passage(unit, end);
        return passage != null && inlet(unit, passage) == null;
    }

    /**
     * The role {@code end} plays as a stream's {@code to} ({@code inlet}) or {@code from}: a fixed port's own id; a passage end's
     * {@code in} or {@code out}; null where its passage was decided the other way, or where the end names a passage's role, which
     * the sheet never shows.
     */
    public @Nullable String role(Flowsheet.End end, boolean inlet) {
        var passage = passage(end.unit(), end.port());
        if (passage == null) return namesRole(end) ? null : end.port();
        var decided = inlet(end.unit(), passage);
        if (decided != null && decided.equals(end.port()) != inlet) return null;
        return inlet ? passage.in() : passage.out();
    }

    private boolean namesRole(Flowsheet.End end) {
        for (var passage : ends.getOrDefault(end.unit(), Map.of()).values()) if (passage.in().equals(end.port()) || passage.out().equals(end.port())) return true;
        return false;
    }

    /**
     * The sheet with each stream's ends named by the roles they play. An end that plays none stays open: a passage end keeps its own
     * name, which no model has as a role, and an end that names a role names no port at all.
     */
    public static Flowsheet roles(Flowsheet sheet) {
        var wiring = of(sheet);
        if (wiring.ends.isEmpty()) return sheet;
        var streams = new ArrayList<Flowsheet.Stream>(sheet.streams().size());
        for (var s : sheet.streams()) streams.add(new Flowsheet.Stream(s.id(), s.name(), wiring.read(s.from(), false), wiring.read(s.to(), true), s.feed(), s.route(), s.unknown()));
        return new Flowsheet(sheet.basis(), sheet.units(), streams, sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    private Flowsheet.@Nullable End read(Flowsheet.@Nullable End end, boolean inlet) {
        if (end == null) return null;
        var role = role(end, inlet);
        if (role == null) return passage(end.unit(), end.port()) != null ? end : new Flowsheet.End(end.unit(), "", end.unknown());
        return role.equals(end.port()) ? end : new Flowsheet.End(end.unit(), role, end.unknown());
    }
}
