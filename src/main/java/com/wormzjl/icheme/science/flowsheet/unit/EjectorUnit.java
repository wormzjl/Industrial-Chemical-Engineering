package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.EjectorLaw;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A steam ejector set: its stages and intercondensers as one tile (batch 2026-11-02-process-simulator, main plan W10, W10b,
 * {@code SIZING_PLAN.md} section 3.12). The door sets the discharge pressure and the motive steam's. {@link EjectorLaw} gives the
 * steam the suction's load takes; a free Motive port is a
 * utility that gives exactly that, dry at its saturation temperature, and a wired one brings what its stream brings, with a warning
 * where that is less than the need, not mostly water, or under the door's pressure. The discharge is the load and the motive mixed
 * adiabatically and flashed at the discharge pressure, so mass, energy and components close.
 */
public final class EjectorUnit implements UnitModel {
    public static final String SUCTION = "suction", MOTIVE = "motive", DISCHARGE = "discharge";
    /** The door's defaults (Pa): to 120 kPa on 10 bar steam, as section 3.12's table. */
    public static final double DISCHARGE_PRESSURE = 1.2e5, MOTIVE_PRESSURE = 1e6;
    /** A wired motive with less water than this share of its mass is not the steam the law is fitted on: a warning. */
    public static final double STEAM_SHARE = .9;
    /** A wired motive this much under the need is short of it: the law's own last digits. */
    private static final double SHORT = 1e-9;
    /** The bounds and tolerance of the saturation search, K; a temperature past the critical point reads infinite. */
    private static final double COLDEST = 200, HOTTEST = 700, SATURATION = 1e-6;

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in(SUCTION), Port.optionalIn(MOTIVE), Port.out(DISCHARGE));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var reads = new ArrayList<String>();
        reads.add(u.wire(SUCTION));
        String wire = u.wire(MOTIVE);
        if (wire != null) reads.add(wire);
        return List.of(u.calculation(reads, List.of(u.out(DISCHARGE)), (board, report) -> {
            var thermo = u.thermo();
            var load = board.stream(u.wire(SUCTION));
            double discharge = u.params().number("discharge", DISCHARGE_PRESSURE), motive = u.params().number("motive", MOTIVE_PRESSURE);
            var wired = wire == null ? null : board.stream(wire);
            if (load.empty()) {
                // Nothing to lift: a free nozzle takes no steam, and a wired one's steam passes to the discharge.
                u.write(board, report, DISCHARGE, wired == null || wired.empty() ? FlowStream.empty(thermo.axis().size()) : thermo.heated(wired, 0, discharge));
                return;
            }
            var steam = motive(u.id(), "", thermo, load, wired, motive, discharge, report);
            double[] amounts = load.amounts();
            for (int i = 0; i < amounts.length; i++) amounts[i] += steam.amountsView()[i];
            double guess = (load.temperature() * load.total() + steam.temperature() * steam.total()) / (load.total() + steam.total());
            u.write(board, report, DISCHARGE, thermo.atEnthalpy(discharge, load.enthalpy() + steam.enthalpy(), amounts, guess, load.solids().plus(steam.solids())));
        }));
    }

    /**
     * The motive steam that lifts {@code load} from its pressure to {@code discharge} on steam at {@code motive} (Pa), and the
     * readings and notes of the law: the column block's Motive steam port takes the same rule. A free port ({@code wired} null) is a
     * utility that enters the sheet here; a wired one is what it brings. Its readings go under {@code prefix}: the block's are its
     * ejector's.
     *
     * @return the steam that joins the load
     */
    public static FlowStream motive(String id, String prefix, FlowThermo thermo, FlowStream load, @Nullable FlowStream wired, double motive, double discharge, Report report) {
        if (!(discharge > load.pressure())) throw new FlowsheetFailure("ejector.rise");
        if (!(motive > discharge)) throw new FlowsheetFailure("ejector.motive");
        double[] amounts = load.amountsView(), weights = thermo.model().molecularWeights();
        int water = thermo.water();
        double gas = 0;
        for (int i = 0; i < water; i++) gas += EjectorLaw.asAir(amounts[i] * weights[i], 1e3 * weights[i]);
        double condensing = EjectorLaw.CONDENSING * thermo.model().saturationPressure(load.temperature());
        double need = EjectorLaw.steam(gas, amounts[water] * weights[water], motive, load.pressure(), discharge, condensing);
        report.reading(prefix + "load", load.mass());
        report.reading(prefix + "need", need);
        report.reading(prefix + "ratio", need / load.mass());
        report.reading(prefix + "compression", discharge / load.pressure());
        FlowStream steam;
        if (wired == null) {
            steam = utility(thermo, need, motive);
            report.enters(steam.mass(), steam.enthalpy());
            report.reading(prefix + "motive", need);
        } else steam = wired(id, prefix, thermo, wired, need, motive, report);
        return steam;
    }

    /** A wired Motive port's steam, with a warning where it is short of {@code need}, not mostly water, or under the door's {@code motive} Pa. */
    private static FlowStream wired(String id, String prefix, FlowThermo thermo, FlowStream wired, double need, double motive, Report report) {
        report.reading(prefix + "motive", wired.mass());
        if (wired.mass() < need * (1 - SHORT)) report.note(Note.warning(id, "ejector.motive_short", String.valueOf(wired.mass()), String.valueOf(need)));
        if (!wired.empty() && wired.amountsView()[thermo.water()] * thermo.waterMass() < STEAM_SHARE * wired.mass()) report.note(Note.warning(id, "ejector.motive_water"));
        if (!wired.empty() && wired.pressure() < motive) report.note(Note.warning(id, "ejector.motive_pressure", String.valueOf(wired.pressure()), String.valueOf(motive)));
        return wired;
    }

    /** {@code mass} kg/s of water as steam at {@code pressure}, dry at its saturation temperature: the motive utility. */
    public static FlowStream utility(FlowThermo thermo, double mass, double pressure) {
        return thermo.atTemperature(saturation(thermo, pressure), pressure, thermo.waterAlone(mass / thermo.waterMass()), SolidInventory.EMPTY);
    }

    /** The least temperature at which water at {@code pressure} is all steam in the world's model, K. */
    static double saturation(FlowThermo thermo, double pressure) {
        double low = COLDEST, high = HOTTEST;
        while (high - low > SATURATION) {
            double middle = (low + high) / 2;
            if (thermo.model().saturationPressure(middle) >= pressure) high = middle;
            else low = middle;
        }
        return high;
    }
}
