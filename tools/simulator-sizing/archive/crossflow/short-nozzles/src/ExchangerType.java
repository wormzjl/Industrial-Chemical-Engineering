package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.HeatRouting;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import java.util.List;

/** The exchanger configuration catalog, shared by construction, sizing and the running heat sides. Saved by name. */
public enum ExchangerType {
    STANDARD("standard", HeatRouting.SERIES, HeatRouting.SERIES, 8, false),
    CROSSFLOW("crossflow", HeatRouting.TWO_PASS_TUBES, HeatRouting.MIXED_CROSSFLOW, 64, true);

    public static final List<ExchangerType> CATALOG = List.of(values());
    private final String key;
    private final HeatRouting tube, shell;
    private final int cells;
    private final boolean transverseShell;

    ExchangerType(String key, HeatRouting tube, HeatRouting shell, int cells, boolean transverseShell) {
        this.key = key;
        this.tube = tube;
        this.shell = shell;
        this.cells = cells;
        this.transverseShell = transverseShell;
    }

    public String key() { return key; }
    public int cells() { return cells; }
    public HeatRouting routing(HeatSides.Side side) { return side == HeatSides.Side.TUBE ? tube : shell; }
    /** X-shell distribution and collection spaces lie inside the cylindrical pressure shell. */
    public record Layout(boolean transverseShell, double nozzleStandOff) {}
    public Layout layout(double diameter) { return new Layout(transverseShell,0); }

    public PipeResistance.Geometry tubePassage(double diameter, double length) {
        return HeatSides.tubePassage(diameter, length);
    }

    public PipeResistance.Geometry shellPassage(double diameter, double length) {
        if (this == STANDARD) return HeatSides.shellPassage(diameter, length);
        // Game-level transverse passage: free area 0.1 D L, path 2.5 D, with the existing hydraulic diameter and roughness.
        double hydraulic = .018;
        int channels = (int) Math.max(1, Math.round(.1 * diameter * length / (Math.PI * hydraulic * hydraulic / 4)));
        return new PipeResistance.Geometry(2.5 * diameter, hydraulic, HeatSides.TUBE_ROUGHNESS, 0, channels);
    }
}
