package com.wormzjl.icheme.science.flowsheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one calculation says about its last solve (batch 2026-11-02-process-simulator, plan section 3.1): its readings, its
 * notes and what crosses the sheet's boundary there, which closes the run's mass and heat. A feed or a free utility stream
 * {@linkplain #enters enters}; a duty the sheet's streams do not carry is a {@linkplain #duty duty}; a filter's cake
 * {@linkplain #leaves leaves}. A stream through a block's port is no boundary: the block's side books the same heat with the
 * opposite sign. Each solve starts a new report, so a loop's last pass is what stays.
 */
public final class Report {
    private final Map<String, Double> readings = new LinkedHashMap<>();
    private final List<Note> notes = new ArrayList<>();
    private double duty, massIn, enthalpyIn, massOut, enthalpyOut;

    public void reading(String key, double value) {
        readings.put(key, value);
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
