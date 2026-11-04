package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What one calculation says about its last solve (batch 2026-11-02-process-simulator, plan section 3.1): its readings, its
 * notes and what crosses the sheet's boundary there, which closes the run's mass and heat. A feed or a free utility stream
 * {@linkplain #enters enters}; a duty the sheet's streams do not carry is a {@linkplain #duty duty}; a filter's cake
 * {@linkplain #leaves leaves}. A stream through a block's port is no boundary: the block's side books the same heat with the
 * opposite sign. Each solve starts a new report, so a loop's last pass is what stays.
 */
public final class Report {
    /** Where a block's solve sets out from. */
    public enum Start {
        /** Nothing of its own yet: the learned start. */
        LEARNED,
        /** Its last solve's state. */
        LAST
    }

    /**
     * Where a column's last solve ended, which the next run's solve of it starts from (owner 2026-11-04, P2): state in this client's
     * memory only. A run's stored results never carry it, so a stored run's columns start from the learned start. The calculator
     * judges whether it still fits the column it is offered to, and solves from the learned start where it does not.
     *
     * @param seed     the calculator's warm start, on the package revision it names
     * @param feedTray the feed tray's pressure (Pa)
     * @param stages   each steam row's stage pressure (Pa, by stage)
     */
    public record Warm(V3NeuralSeed seed, double feedTray, Map<Integer, Double> stages) {
        public Warm {
            stages = Map.copyOf(stages);
        }

        /** Whether its package still stands at the revision it was solved on: a new revision starts from the learned start. */
        public boolean current(MaterialCatalog catalog) {
            var pack = catalog.packages().get(seed.input().packageId());
            return pack != null && pack.scientificRevision().equals(seed.propertyRevision());
        }
    }

    private final Map<String, Double> readings = new LinkedHashMap<>();
    private final List<Note> notes = new ArrayList<>();
    private double duty, massIn, enthalpyIn, massOut, enthalpyOut;
    private @Nullable V3ColumnDisplayResult display;
    private @Nullable Warm warm;
    private @Nullable Start start;
    private final @Nullable Consumer<Start> told;

    public Report() {
        this(null);
    }

    /** {@code told} hears a block's start as it sets out, while the solve is still to come. */
    Report(@Nullable Consumer<Start> told) {
        this.told = told;
    }

    public void start(Start start) {
        this.start = start;
        if (told != null) told.accept(start);
    }

    public @Nullable Start start() {
        return start;
    }

    public void reading(String key, double value) {
        readings.put(key, value);
    }

    /** What a column's last solve shows on its door's Results pages: a state, kept here because a stream or reading cannot hold a profile. */
    public void display(V3ColumnDisplayResult display) {
        this.display = display;
    }

    public @Nullable V3ColumnDisplayResult display() {
        return display;
    }

    public void warm(Warm warm) {
        this.warm = warm;
    }

    public @Nullable Warm warm() {
        return warm;
    }

    /** This report without its warm start: the same readings, notes, boundary, display and start. */
    Report cold() {
        if (warm == null) return this;
        var cold = new Report();
        cold.readings.putAll(readings);
        cold.notes.addAll(notes);
        cold.duty = duty;
        cold.massIn = massIn;
        cold.enthalpyIn = enthalpyIn;
        cold.massOut = massOut;
        cold.enthalpyOut = enthalpyOut;
        cold.display = display;
        cold.start = start;
        return cold;
    }

    public void note(Note note) {
        notes.add(note);
    }

    /** Heat or work across the boundary (W), positive into the sheet. */
    public void duty(double watts) {
        duty += watts;
    }

    /** Mass (kg/s) and enthalpy (W) that come into the sheet here. */
    public void enters(double mass, double enthalpy) {
        massIn += mass;
        enthalpyIn += enthalpy;
    }

    /** Mass (kg/s) and enthalpy (W) that leave the sheet here, not as a stream. */
    public void leaves(double mass, double enthalpy) {
        massOut += mass;
        enthalpyOut += enthalpy;
    }

    public Map<String, Double> readings() {
        return Map.copyOf(readings);
    }

    public List<Note> notes() {
        return List.copyOf(notes);
    }

    public double duty() {
        return duty;
    }

    public double massIn() {
        return massIn;
    }

    public double enthalpyIn() {
        return enthalpyIn;
    }

    public double massOut() {
        return massOut;
    }

    public double enthalpyOut() {
        return enthalpyOut;
    }
}
