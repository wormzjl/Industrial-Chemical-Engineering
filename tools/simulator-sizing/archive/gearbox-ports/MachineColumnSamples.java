package com.wormzjl.icheme.client.gui.common;

import static com.wormzjl.icheme.client.gui.common.MachineColumn.KEY;
import static com.wormzjl.icheme.client.gui.common.MachineColumn.duration;
import static com.wormzjl.icheme.client.gui.common.MachineColumn.input;

import com.wormzjl.icheme.client.gui.common.MachineColumn.Input;
import com.wormzjl.icheme.client.gui.common.MachineColumn.Kind;
import com.wormzjl.icheme.client.gui.common.MachineColumn.Meter;
import com.wormzjl.icheme.client.gui.common.MachineColumn.Mode;
import com.wormzjl.icheme.runtime.fluid.MachineView;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.ArrayList;
import java.util.List;

/**
 * The machine page's illustrative cases, for {@code MachineColumnTest} and the render tool ({@code tools/heat-gui-renders/
 * GuiPrototypes.java}, with {@code build/classes/java/test} on its class path). Moved from {@code MachineColumn} at the
 * batch's tooling cleanup (batch 2026-11-03-rotating-machinery): the game never draws them.
 */
public final class MachineColumnSamples {
    private MachineColumnSamples() {
    }

    /**
     * An illustrative published view of a Create coupling (the gates' and the render tool's, W6b): Create at {@code createRpm}
     * with {@code stress} su to take, {@code drawn} su drawn, the train's first machine at {@code trainRpm} behind an 11.52
     * gearbox, {@code power} W at the shaft, 1.465 W per su, its design speed Create's highest.
     */
    public static MachineView couplingSample(String status, double createRpm, double trainRpm, double drawn, double stress, double power) {
        var rate = com.wormzjl.icheme.science.machine.CreateCoupling.wattsPerStress(2.5, com.wormzjl.icheme.science.machine.CreateCoupling.STRESS_PER_FE);
        var c = new MachineView.Coupling(createRpm, stress, createRpm > 0 ? drawn / createRpm : 0, trainRpm, rate);
        return new MachineView(1, ShaftNetwork.Kind.CREATE_COUPLING, status, trainRpm / 11.52, power, 0, 0, com.wormzjl.icheme.runtime.fluid.ShaftUnit.Settings.DEFAULT,
                ShaftTrain.Trip.NONE, 0, com.wormzjl.icheme.science.machine.CreateCoupling.DESIGN_SPEED, 0, true, Double.NaN, Double.NaN, "", 0,
                Double.NaN, Double.NaN, Double.NaN, c);
    }

    /** A case: its name and its input. */
    public record Case(String name, Input in) {
    }

    /** Every kind as an editor and a reader, each mode, running and stopped, tripped; illustrative readings. */
    public static List<Case> cases(HeatControlColumn.Texts t) {
        var out = new ArrayList<Case>();
        for (var kind : Kind.values())
            for (boolean edit : new boolean[]{true, false})
                for (boolean variable : kind == Kind.MOTOR ? new boolean[]{true, false} : new boolean[]{true})
                    for (var mode : kind.driver() ? List.of(Mode.values()) : List.of(Mode.SPEED))
                        for (boolean tripped : kind == Kind.MOTOR || kind == Kind.STEAM_TURBINE ? new boolean[]{false, true} : new boolean[]{false})
                            out.add(new Case(kind + (variable ? "" : " fixed") + " " + mode + (tripped ? " tripped" : "") + (edit ? "" : " reader"),
                                    sample(kind, edit, variable, mode, tripped, t)));
        return out;
    }

    /**
     * Option A's sheet: a 30 kW 2-pole IE3 motor on a variable drive at 2950 rpm, shaft 24.9 kW, electric 26.7 kW, store
     * 72 %; a 2500 kW turbine at 4200 rpm giving 1980 kW from 2.9 kg/s of steam, 40 bar in and 4 bar out.
     */
    public static Input sample(Kind kind, boolean edit, boolean variable, Mode mode, boolean tripped, HeatControlColumn.Texts t) {
        if (kind == Kind.CREATE_COUPLING) return input(couplingSample("RUNNING", 256, 2950, 17345, 20480, 25.41e3), edit, true, mode, t);
        boolean turbine = kind == Kind.STEAM_TURBINE;
        var shaft = new ArrayList<Meter>(List.of(new Meter("speed", tripped ? "0" : turbine ? "4200" : "2950", "rpm", null)));
        if (kind == Kind.GEARBOX) shaft.add(new Meter("ratio_now", "4.0", "", t.get(KEY + "ratio_now.hint")));
        shaft.add(new Meter("power", tripped ? "0.0" : turbine ? "1980" : "24.9", "kW", null));
        var supply = new ArrayList<Meter>();
        if (turbine) supply.addAll(List.of(new Meter("steam_flow", tripped ? "0.0" : "2.9", "kg/s", null), new Meter("inlet", "40.0", "bar", null),
                new Meter("exhaust", "4.0", "bar", null)));
        if (kind == Kind.MOTOR) supply.addAll(List.of(new Meter("electric", "26.7", "kW", null), new Meter("draw", "534", "FE/t", t.get(KEY + "fe.hint")),
                new Meter("store", "72", "%", t.get(KEY + "store.hint"))));
        if (kind == Kind.GENERATOR) supply.addAll(List.of(new Meter("output", "23.4", "kW", null), new Meter("give", "468", "FE/t", t.get(KEY + "fe.hint")),
                new Meter("store", "40", "%", t.get(KEY + "store.hint"))));
        return new Input(kind, edit, !tripped, variable, mode, tripped, kind == Kind.STEAM_TURBINE ? t.get(KEY + "governed", "FV-101") : null, shaft, supply,
                kind == Kind.MOTOR ? duration(370, t) : null);
    }
}
