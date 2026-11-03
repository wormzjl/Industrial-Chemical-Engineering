package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 4), a slow gate: the
 * sheet's units take the probe's size and closest limit, from {@code sizing-sample.tsv}. T-101 and its ten members (the condenser,
 * the receiver, the reflux pump, three pumparound coolers and their pumps, and the member lines) are S3's, in
 * {@link ColumnSizingSampleTest}.
 *
 * <p>Two surfaces leave the probe's, because the probe holds one heat capacity along a duty and the curves hold the real one, which
 * rises with temperature, so more of the heat passes where the difference is wide. E-101 needs 440 m², not 444, and keeps its size. E-102
 * needs 724 m², not 741, and that moves it: 6 × 4 bays of 2.0 × 5 m hold it on 145 m² of face, where the probe's 5 × 3 of 2.5 × 6 m take 150.
 * The two gates below rebuild the probe's number from the same streams, so what moved is the form and nothing else.
 *
 * <p>P-102 draws from T-101's tray, whose height the column's own layout gives, and an entry with the probe's height reads the same.
 * V-101's share of its path and H-101's spare wait for S4: their rows are not read at all until an entry gives them.
 */
class UnitSizingSampleTest {
    /** Where the probe's P-102 draws: T-101's D 8.0 m skirt, its bottom space and the 22 trays over them. */
    private static final double DRAW_HEIGHT = EquipmentShape.minimumSkirt(8) + ColumnTrays.bottomSpace(8) + 22 * ColumnTrays.SPACING;
    /** What the probe's path to V-101 loses, V-101 with it: 3.66 + 2.28 bar. */
    private static final double V_101_PATH = 5.94;
    /** Probe values stand in the table's printed places. */
    private static final double TENTH = .05, ONE = .5, HUNDREDTH = .005;

    private static SheetSizing.Sized alone, given;
    private static FlowsheetRun run;
    private static FlowThermo thermo;
    private static Flowsheet sheet;

    @BeforeAll
    static void sizeTheSample() {
        sheet = SheetFixture.sample();
        run = FlowsheetEngine.run(sheet, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        var later = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(),
                Map.of("P-102", new SheetSizing.UnitEntry(null, null, DRAW_HEIGHT, null, null, null), "V-101", new SheetSizing.UnitEntry(null, null, null, V_101_PATH, null, null),
                        "H-101", new SheetSizing.UnitEntry(null, null, null, null, 5.34, null)));
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            alone = SheetSizing.size(sheet, run, thermo, SheetSizing.Entries.DEFAULT);
            given = SheetSizing.size(sheet, run, thermo, later);
            return null;
        });
    }

    /** Heat that a mean capacity would spread evenly, on a stream whose capacity rises: the upper half of its range holds more. */
    private static void itsCapacityRises(String stream, double high, double low) {
        var flow = run.streams().get(stream);
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            double top = thermo.atTemperature(high, flow.pressure(), flow.amountsView(), flow.solids()).enthalpy(), middle = thermo.atTemperature((high + low) / 2, flow.pressure(), flow.amountsView(), flow.solids()).enthalpy();
            double bottom = thermo.atTemperature(low, flow.pressure(), flow.amountsView(), flow.solids()).enthalpy();
            assertTrue(top - middle > 1.05 * (middle - bottom), stream);
            return null;
        });
    }

    private static double read(Rating rating, Limit limit) {
        return rating.reading(limit).value();
    }

    private static void closest(Rating rating, Limit limit) {
        assertEquals(Lamp.GREEN, rating.lamp());
        assertSame(limit, rating.closest().limit());
    }

    @Test
    void p101() {
        var unit = alone.units().get("P-101");
        assertEquals(new PumpSizing.Size(.30), unit.size());
        closest(unit.rating(), Limit.PUMP_RATED);
        assertEquals(96, read(unit.rating(), Limit.PUMP_RATED), ONE);
        assertEquals(731, read(unit.rating(), Limit.PUMP_FLOW), ONE);
        assertEquals(71, read(unit.rating(), Limit.PUMP_HEAD), ONE);
        assertEquals(80.4, read(unit.rating(), Limit.PUMP_NPSH), TENTH, "a feed stands at grade, so no height is missing");
    }

    @Test
    void p102ReadsItsDrawTraysHeight() {
        var unit = alone.units().get("P-102");
        assertEquals(new PumpSizing.Size(.15), unit.size());
        closest(unit.rating(), Limit.PUMP_RATED);
        assertEquals(71, read(unit.rating(), Limit.PUMP_RATED), ONE);
        assertEquals(135, read(unit.rating(), Limit.PUMP_FLOW), ONE);
        assertEquals(51, read(unit.rating(), Limit.PUMP_HEAD), ONE);
        assertEquals(31.1, read(unit.rating(), Limit.PUMP_NPSH), TENTH, "the column's own layout gives the probe's height");
        assertEquals(31.1, read(given.units().get("P-102").rating(), Limit.PUMP_NPSH), TENTH, "and so does the probe's height given as an entry");
    }

    @Test
    void e101() {
        var unit = alone.units().get("E-101");
        assertEquals(new ExchangerSizing.Size(false, 1, 1, 6), unit.size());
        closest(unit.rating(), Limit.EXCHANGER_SURFACE);
        var surface = unit.rating().reading(Limit.EXCHANGER_SURFACE);
        assertEquals(450, surface.value(), ONE);
        assertEquals(440, surface.low(), ONE, "the curves' surface");
        assertEquals(444, probesSurface(), ONE, "the probe's one LMTD at U 1000, from the same streams");
        assertEquals(81.8, read(unit.rating(), Limit.EXCHANGER_HOT_END), TENTH);
        assertEquals(46.7, read(unit.rating(), Limit.EXCHANGER_COLD_END), TENTH);
        assertEquals(46.7, read(unit.rating(), Limit.EXCHANGER_APPROACH), TENTH);
        assertEquals(2, read(unit.rating(), Limit.EXCHANGER_OVER_NEED), ONE);
        var drops = unit.rating().readings().stream().filter(reading -> reading.limit() == Limit.EXCHANGER_DROP_LIQUID).toList();
        assertEquals(.44, drops.get(0).value(), HUNDREDTH, "the tube side");
        assertEquals(.15, drops.get(1).value(), HUNDREDTH, "the shell side");
    }

    private static double probesSurface() {
        var s = run.streams();
        double hotIn = s.get("S-9").temperature(), hotOut = s.get("S-10").temperature(), coldIn = s.get("S-4").temperature(), coldOut = s.get("S-5").temperature();
        double a = hotIn - coldOut, b = hotOut - coldIn;
        return 1.1 * (s.get("S-9").enthalpy() - s.get("S-10").enthalpy()) / (1000 * (a - b) / Math.log(a / b));
    }

    @Test
    void e101KeptOneStepDownIsRatedByTheSameRule() {
        var kept = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.size(sheet, run, thermo,
                new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("E-101", new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 1, .75, 6), null, null, null, null, null))))).units().get("E-101");
        assertEquals(new ExchangerSizing.Size(false, 1, .75, 6), kept.size());
        assertEquals(Lamp.RED, kept.rating().lamp());
        assertSame(Limit.EXCHANGER_SURFACE, kept.rating().closest().limit());
        assertEquals(253, read(kept.rating(), Limit.EXCHANGER_SURFACE), ONE);
        var drops = kept.rating().readings().stream().filter(reading -> reading.limit() == Limit.EXCHANGER_DROP_LIQUID).toList();
        assertEquals(1.29, drops.get(0).value(), HUNDREDTH);
        assertEquals(Lamp.YELLOW, drops.get(0).lamp());
        assertEquals(.41, drops.get(1).value(), HUNDREDTH);
    }

    @Test
    void h101TakesACabin() {
        var unit = alone.units().get("H-101");
        assertEquals(new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 41), unit.size());
        closest(unit.rating(), Limit.HEATER_DUTY);
        var duty = unit.rating().reading(Limit.HEATER_DUTY);
        assertEquals(87.6, duty.value(), TENTH);
        assertEquals(86.2, duty.low(), TENTH);
        assertNull(unit.rating().reading(Limit.HEATER_SPARE), "S4's");
        assertEquals(5.34, read(given.units().get("H-101").rating(), Limit.HEATER_SPARE), 0);
    }

    @Test
    void v101TakesItsLinesBore() {
        var unit = alone.units().get("V-101");
        assertEquals(new ValveSizing.Size(.7), unit.size());
        assertEquals(2.28, read(unit.rating(), Limit.VALVE_DROP), HUNDREDTH);
        assertNull(unit.rating().reading(Limit.VALVE_SHARE), "S4's");
        var rating = given.units().get("V-101").rating();
        closest(rating, Limit.VALVE_SHARE);
        assertEquals(1.19, V_101_PATH * Limit.VALVE_SHARE.value() / 100, HUNDREDTH, "the probe's 0.2 of the path");
        assertEquals(100 * 2.28 / V_101_PATH, read(rating, Limit.VALVE_SHARE), .1);
    }

    @Test
    void e102() {
        var unit = alone.units().get("E-102");
        assertEquals(new AirCoolerSizing.Size(2, 5, new ProcessUnit.Bank(6, 4)), unit.size(), "the least face on the curve's need");
        closest(unit.rating(), Limit.AIR_COOLER_SURFACE);
        var surface = unit.rating().reading(Limit.AIR_COOLER_SURFACE);
        assertEquals(726, surface.value(), ONE);
        assertEquals(724, surface.low(), ONE, "the curve's surface");
        assertEquals(741, probesBareSurface(), ONE, "the probe's one capacity, from the same streams");
        itsCapacityRises("S-10", run.streams().get("S-10").temperature(), run.streams().get("RESIDUE").temperature());
        assertEquals(45, read(unit.rating(), Limit.AIR_COOLER_OUTLET), TENTH);
        assertEquals(.07, read(unit.rating(), Limit.AIR_COOLER_DROP), HUNDREDTH);
    }

    private static double probesBareSurface() {
        var in = run.streams().get("S-10");
        var out = run.streams().get("RESIDUE");
        double duty = 1.1 * (in.enthalpy() - out.enthalpy()), capacity = duty / (in.temperature() - out.temperature()), air = HeatSides.AIR_TEMPERATURE;
        return capacity * Math.log((in.temperature() - air) / (out.temperature() - air)) / HeatSides.airFilms(1).liquid();
    }

    @Test
    void theProbesBankStillHoldsItsDutyWhenThePlayerKeepsIt() {
        var kept = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.size(sheet, run, thermo,
                new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("E-102", new SheetSizing.UnitEntry(new AirCoolerSizing.Size(2.5, 6, new ProcessUnit.Bank(5, 3)), null, null, null, null, null))))).units().get("E-102");
        assertEquals(Lamp.GREEN, kept.rating().lamp());
        assertEquals(752, read(kept.rating(), Limit.AIR_COOLER_SURFACE), ONE);
        assertEquals(.05, read(kept.rating(), Limit.AIR_COOLER_DROP), HUNDREDTH);
    }

    /** The plan's what-if: the sample has no storage tile, so each product stands for the liquid one would hold. */
    private static TankSizing.Load tile(String stream) {
        return MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> TankSizing.Load.of(run.streams().get(stream), FlowStream.empty(thermo.axis().size()), null, DesignBasis.DEFAULT, thermo));
    }

    @Test
    void theUnstabilisedNaphthaTakesASphereAndTheResidueARoof() {
        var naphtha = tile("NAPHTHA");
        assertEquals(2.299, naphtha.bubble(), 2.299 * .0005, "both bisect to a ratio of 1.0005, from other bounds");
        assertEquals(new TankSizing.Size(UnitType.SPHERE, 1, 17, 1), TankSizing.auto(naphtha).size());
        var residue = tile("RESIDUE");
        assertTrue(residue.bubble() < .015);
        assertEquals(new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 30, 18), TankSizing.auto(residue).size());
    }

    @Test
    void everyLineStandsSizedInSheetOrder() {
        assertEquals(sheet.streams().stream().map(Flowsheet.Stream::id).toList(), alone.lines().keySet().stream().limit(21).toList(), "the block's member lines come after");
        assertEquals(.7, alone.lines().get("S-6").size().bore());
        assertEquals(Lamp.GREEN, alone.lines().get("S-6").rating().lamp());
    }

    @Test
    void stopReachesItBetweenItems() {
        var asked = new AtomicInteger();
        var stopping = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            if (asked.incrementAndGet() == 5) throw new CancellationException();
        });
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> assertThrows(CancellationException.class, () -> SheetSizing.size(sheet, run, stopping, SheetSizing.Entries.DEFAULT)));
    }
}
