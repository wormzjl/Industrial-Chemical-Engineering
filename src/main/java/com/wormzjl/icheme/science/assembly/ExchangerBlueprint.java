package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.Blueprint.*;
import com.wormzjl.icheme.science.assembly.PortGraph.Fitting;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The assembly of a shell-and-tube exchanger's control lines (batch 2026-10-27-heat-exchanger-design, plan section 7,
 * W8): the exchanger is the master, and each side's lines follow the control chosen on its screen.
 *
 * <ul>
 * <li>{@link Lines#BYPASS} (outlet temperature or duty): from the side's inlet, against the stream, an inlet tee; one of
 * its branches is the feed (outside), the other the bypass line through a control valve to an outlet tee, whose
 * branches are the line to the side's outlet and the product (outside).</li>
 * <li>{@link Lines#RATIO}: a control valve on the side's feed, and a flowmeter on the other side's feed (the reference).</li>
 * </ul>
 *
 * Shutoffs, strainers and a flowmeter no step asks for pass the line on, as on a column's lines. Minecraft-free.
 */
public final class ExchangerBlueprint {
    /** What a side's lines are, from its control: none, a bypass around it, or a ratio station on its feed. */
    public enum Lines {
        NONE, BYPASS, RATIO
    }

    /** The lines of the exchanger's tube and shell sides. */
    public record Design(Lines tube, Lines shell) {
        public static final Design NONE = new Design(Lines.NONE, Lines.NONE);

        public Design {
            Objects.requireNonNull(tube);
            Objects.requireNonNull(shell);
        }

        public Lines of(HeatSides.Side side) {
            return side == HeatSides.Side.TUBE ? tube : shell;
        }

        /** Whether the exchanger has an assembly: a side with lines. */
        public boolean any() {
            return tube != Lines.NONE || shell != Lines.NONE;
        }
    }

    public static final Slot EXCHANGER = new Slot("exchanger", 0);
    private static final FittingForm VALVE = new FittingForm(Fitting.CONTROL_VALVE), METER = new FittingForm(Fitting.FLOWMETER);

    private ExchangerBlueprint() {
    }

    private static String name(HeatSides.Side side) {
        return side == HeatSides.Side.TUBE ? "tube" : "shell";
    }

    public static Slot inletTee(HeatSides.Side side) {
        return new Slot(name(side) + "_inlet_tee", 0);
    }

    public static Slot bypassValve(HeatSides.Side side) {
        return new Slot(name(side) + "_bypass_valve", 0);
    }

    public static Slot outletTee(HeatSides.Side side) {
        return new Slot(name(side) + "_outlet_tee", 0);
    }

    public static Slot feedValve(HeatSides.Side side) {
        return new Slot(name(side) + "_feed_valve", 0);
    }

    /** The flowmeter on {@code side}'s feed: the reference of the other side's ratio. */
    public static Slot meter(HeatSides.Side side) {
        return new Slot(name(side) + "_meter", 0);
    }

    /** The side's inlet nozzle. */
    public static Role inlet(HeatSides.Side side) {
        return side == HeatSides.Side.TUBE ? Role.TUBE_INLET : Role.SHELL_INLET;
    }

    /** The side's outlet nozzle. */
    public static Role outlet(HeatSides.Side side) {
        return side == HeatSides.Side.TUBE ? Role.TUBE_OUTLET : Role.SHELL_OUTLET;
    }

    private static HeatSides.Side other(HeatSides.Side side) {
        return side == HeatSides.Side.TUBE ? HeatSides.Side.SHELL : HeatSides.Side.TUBE;
    }

    /** The slot whose valve a side's control works ({@link #bypassValve} or {@link #feedValve}), or null for a side without lines. */
    public static @Nullable Slot operated(HeatSides.Side side, Lines lines) {
        return switch (lines) {
            case NONE -> null;
            case BYPASS -> bypassValve(side);
            case RATIO -> feedValve(side);
        };
    }

    /** The assembly an exchanger with {@code design} asks for. */
    public static Blueprint of(Design design) {
        if (!design.any()) throw new IllegalArgumentException("An exchanger without lines has no assembly");
        var slots = new ArrayList<Slot>();
        var links = new ArrayList<Link>();
        slots.add(EXCHANGER);
        for (var side : HeatSides.Side.values()) {
            if (side != HeatSides.Side.TUBE && side != HeatSides.Side.SHELL) continue;
            switch (design.of(side)) {
                case NONE -> {
                }
                case BYPASS -> {
                    Slot in = inletTee(side), valve = bypassValve(side), out = outletTee(side);
                    slots.addAll(List.of(in, valve, out));
                    var outletTee = new TeeForm(List.of(
                            new Branch(List.of(), new ToPort(new PortSpec(EXCHANGER, outlet(side), 0)), out),
                            new Branch(List.of(), new Outside(), out)));
                    var inletTee = new TeeForm(List.of(
                            new Branch(List.of(), new Outside(), in),
                            new Branch(List.of(Step.of(valve, VALVE), Step.of(out, outletTee)), new AtUnit(), valve)));
                    links.add(new Link(name(side) + "_bypass", Start.at(EXCHANGER, inlet(side), 0), List.of(Step.of(in, inletTee)), new AtUnit(), true, in));
                }
                case RATIO -> {
                    Slot valve = feedValve(side), meter = meter(other(side));
                    slots.addAll(List.of(valve, meter));
                    links.add(new Link(name(side) + "_feed", Start.at(EXCHANGER, inlet(side), 0), List.of(Step.of(valve, VALVE)), new Outside(), true, valve));
                    links.add(new Link(name(other(side)) + "_reference", Start.at(EXCHANGER, inlet(other(side)), 0), List.of(Step.of(meter, METER)), new Outside(), true, meter));
                }
            }
        }
        return new Blueprint(EXCHANGER, slots, links);
    }
}
