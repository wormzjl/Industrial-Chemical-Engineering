package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Board;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

import java.util.List;

/**
 * A drum or a storage tank (batch 2026-11-02-process-simulator, plan section 3.2): a flash with one outlet per phase and heat
 * none, temperature or duty. A drum stands at its inlet's pressure less a drop, or at a set pressure. A storage tile needs no
 * type to run: a liquid a floating roof stores ({@link com.wormzjl.icheme.science.flowsheet.FlowThermo#roofStores}: its vapour
 * pressure at equal vapour and liquid volumes at most 0.766 bar) stands at ambient, and the gas it held dissolved leaves by
 * the Gas outlet; any other is a sphere's, held unflashed at the higher of ambient and its bubble pressure. It cannot stand
 * above what arrives, and what stays gas then leaves by the Gas outlet.
 */
public final class VesselUnit implements UnitModel {
    private static final double AMBIENT = 101325;
    private static final String GAS = "gas", OIL = "oil", WATER = "water";
    private final boolean storage;

    public VesselUnit(boolean storage) {
        this.storage = storage;
    }

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out(GAS), Port.out(OIL), Port.out(WATER));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.calculation(List.of(u.wire("in")), List.of(u.out(GAS), u.out(OIL), u.out(WATER)), (board, report) -> {
            var in = board.stream(u.wire("in"));
            if (in.empty()) {
                split(u, board, report, in);
                return;
            }
            double pressure = pressure(u, in);
            if (!(pressure > 0)) throw new FlowsheetFailure("unit.pressure");
            var held = switch (u.params().text("heat", "none")) {
                case "none" -> u.thermo().heated(in, 0, pressure);
                case "temperature" -> u.thermo().atTemperature(u.params().number("temperature"), pressure, in.amounts(), in.solids());
                case "duty" -> u.thermo().heated(in, u.params().number("duty"), pressure);
                default -> throw new FlowsheetFailure("unit.setting", "heat");
            };
            report.duty(held.enthalpy() - in.enthalpy());
            report.reading("duty", held.enthalpy() - in.enthalpy());
            report.reading("temperature", held.temperature());
            report.reading("pressure", held.pressure());
            report.reading("vapour", u.thermo().vapourShare(held));
            split(u, board, report, held);
        }));
    }

    private double pressure(WiredUnit u, FlowStream in) {
        if (storage) {
            double ambient = u.params().number("ambient", AMBIENT);
            double[] amounts = in.amounts();
            // 17 flashes for a liquid under a roof, 15 more for a sphere's bubble pressure; no state kept between evaluations.
            return Math.min(in.pressure(), u.thermo().roofStores(in.temperature(), amounts) ? ambient : Math.max(ambient, u.thermo().bubblePressure(in.temperature(), amounts)));
        }
        return u.params().text("pressure_mode", "inlet").equals("set") ? u.params().number("pressure") : in.pressure() - u.params().number("drop", 0);
    }

    private static void split(WiredUnit u, Board board, Report report, FlowStream held) {
        if (held.empty()) {
            for (var port : List.of(GAS, OIL, WATER)) u.write(board, report, port, held);
            return;
        }
        u.write(board, report, GAS, u.thermo().phase(held, FluidThermodynamics.GAS));
        u.write(board, report, OIL, u.thermo().phase(held, FluidThermodynamics.OIL));
        u.write(board, report, WATER, u.thermo().phase(held, FluidThermodynamics.AQUEOUS));
    }
}
