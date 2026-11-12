package com.wormzjl.icheme.client.gui.flowsheet;

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
 * rises with temperature, so more of the heat passes where the difference is wide. E-101 needs 440 m², not 443.5, and keeps its size. E-102
 * needs 724 m², not 741, and that moves it: 6 × 4 bays of 2.0 × 5 m hold it on 145 m² of face, where the probe's 5 × 3 of 2.5 × 6 m take 150.
 * The two gates below rebuild the probe's number from the same streams, so what moved is the form and nothing else.
 *
 * <p>P-102 draws from T-101's tray, whose height the column's own layout gives, and an entry with the probe's height reads the same.
 * V-101's share and H-101's spare read P-101's pressure path, which ends at V-101's set outlet (S4a).
 *
 * <p>With condenser carry-over (E31 of batch 2026-11-04-open-thermo-data) the column sends more naphtha overhead and a little
 * less residue: E-101 needs 568 m² (the probe's 574.3), E-102 732.5 (750.2), H-101 80.39 MW, and the naphtha takes a 15 m
 * sphere, not 14. P-102 reads 131.6 m³/h and an NPSH of 32.35 m (34.69 m since the NPSH counts over p_v). Every other size stands.
 */
class UnitSizingSampleTest {
    /** Probe values stand in the table's printed places. */
    private static final double TENTH = .05, ONE = .5, HUNDREDTH = .005;

    private static SheetSizing.Sized alone;
    private static FlowsheetRun run;
    private static FlowThermo thermo;
    private static Flowsheet sheet;

    @BeforeAll
    static void sizeTheSample() {
        sheet = SheetFixture.sample();
        run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            alone = SheetSizing.pass(sheet, run, thermo, SheetSizing.Entries.DEFAULT);
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
        assertEquals(730, read(unit.rating(), Limit.PUMP_FLOW), ONE);
        assertEquals(71, read(unit.rating(), Limit.PUMP_HEAD), ONE);
        // 80.4 m before W3b: the derived pairs (light ends over the heavy cuts) put the dry crude's bubble pressure at
        // 298.15 K at 129.6 kPa instead of 115.0 kPa, 1.72 m of this crude's head (batch 2026-11-11-aqueous-solvents).
        // 78.65 m while the NPSH counted over the full bubble pressure: it counts over p_v now, the crude's vapour pressure
        // without its methane and ethane (above their critical temperature at 298.15 K), 8.74 m of this crude's head lower
        // (W3B_NETWORK_PLAN.md section 44, as the world's suction limit counts it).
        assertEquals(87.39, read(unit.rating(), Limit.PUMP_NPSH), TENTH, "a feed stands at grade, so no height is missing");
    }

    @Test
    void p102ReadsItsDrawTraysHeight() {
        var unit = alone.units().get("P-102");
        assertEquals(new PumpSizing.Size(.15), unit.size());
        // O3 of batch 2026-11-04-open-thermo-data: on the medium crude grade the diesel draw is smaller and the pump runs at 69 % of
        // its rated flow, under the lamp's 70 %, so Auto's pump reads yellow on that limit.
        assertEquals(Lamp.YELLOW, unit.rating().lamp());
        assertSame(Limit.PUMP_RATED, unit.rating().closest().limit());
        assertEquals(69, read(unit.rating(), Limit.PUMP_RATED), ONE);
        assertEquals(131.6, read(unit.rating(), Limit.PUMP_FLOW), ONE);
        assertEquals(52, read(unit.rating(), Limit.PUMP_HEAD), ONE);
        // 32.35 m over the full bubble pressure; over p_v the tray's liquid has 2.34 m more (section 44).
        assertEquals(34.69, read(unit.rating(), Limit.PUMP_NPSH), TENTH, "the column's own layout gives the probe's height");
    }

    /**
     * S4c: Auto takes the least surface that holds 1.2 × the need, 528 m²: a 1.25 m shell with 5 m tubes, 586 m², where the bare
     * need's least was the plan's 1.00 × 6 m, 450 m². Its wider shell and shorter tubes lose less on both sides. On the medium
     * crude grade (O3 of batch 2026-11-04-open-thermo-data) the need is 573 m², 688 with the spare: the same shell with 6 m tubes, 703 m².
     * Since the column took the network's water (E30 (a)) the residue brings E-101 a little less heat, and the need is 572 m² (the probe's 578.5).
     * Since the pumps' losses heat the crude (D42 of batch 2026-11-03-rotating-machinery) the crude reaches E-101 0.08 K warmer:
     * both ends close by 0.05 K and the need is 569 m² (the probe's 575.0).
     */
    @Test
    void e101() {
        var unit = alone.units().get("E-101");
        assertEquals(new ExchangerSizing.Size(false, 1, 1.25, 6), unit.size());
        closest(unit.rating(), Limit.EXCHANGER_SURFACE);
        var surface = unit.rating().reading(Limit.EXCHANGER_SURFACE);
        assertEquals(703, surface.value(), ONE);
        assertEquals(569, surface.low(), ONE, "the curves' surface");
        assertEquals(575.0, probesSurface(), ONE, "the probe's one LMTD at U 1000, from the same streams");
        assertEquals(67.91, read(unit.rating(), Limit.EXCHANGER_HOT_END), TENTH);
        assertEquals(32.64, read(unit.rating(), Limit.EXCHANGER_COLD_END), TENTH);
        assertEquals(32.63, read(unit.rating(), Limit.EXCHANGER_APPROACH), TENTH);
        assertEquals(23.6, read(unit.rating(), Limit.EXCHANGER_OVER_NEED), ONE);
        var drops = unit.rating().readings().stream().filter(reading -> reading.limit() == Limit.EXCHANGER_DROP_LIQUID).toList();
        assertEquals(.20, drops.get(0).value(), HUNDREDTH, "the tube side, 0.44 on 1.00 × 6 m; 0.17 before O4's crude viscosity");
        assertEquals(.07, drops.get(1).value(), HUNDREDTH, "the shell side, 0.15 on 1.00 × 6 m");
    }

    private static double probesSurface() {
        var s = run.streams();
        double hotIn = s.get("S-9").temperature(), hotOut = s.get("S-10").temperature(), coldIn = s.get("S-4").temperature(), coldOut = s.get("S-5").temperature();
        double a = hotIn - coldOut, b = hotOut - coldIn;
        return 1.1 * (s.get("S-9").enthalpy() - s.get("S-10").enthalpy()) / (1000 * (a - b) / Math.log(a / b));
    }

    @Test
    void e101KeptOneStepDownIsRatedByTheSameRule() {
        var kept = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo,
                new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("E-101", new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 1, .75, 6), null, null, false))))).units().get("E-101");
        assertEquals(new ExchangerSizing.Size(false, 1, .75, 6), kept.size());
        assertEquals(Lamp.RED, kept.rating().lamp());
        assertSame(Limit.EXCHANGER_SURFACE, kept.rating().closest().limit());
        assertEquals(253, read(kept.rating(), Limit.EXCHANGER_SURFACE), ONE);
        var drops = kept.rating().readings().stream().filter(reading -> reading.limit() == Limit.EXCHANGER_DROP_LIQUID).toList();
        assertEquals(1.31, drops.get(0).value(), HUNDREDTH, "1.29 before O4's crude viscosity");
        assertEquals(Lamp.YELLOW, drops.get(0).lamp());
        assertEquals(.432, drops.get(1).value(), HUNDREDTH, "0.41 before O4's crude viscosity, 0.44 before condenser carry-over (E31)");
    }

    /**
     * S4c: the bare 86.2 MW sets the type and the one cabin; the spare sets the size: 12.0 × 45 m holds 105.4 MW, the least over
     * 1.2 × 86.2 = 103.4, where 11.0 × 41 m held 87.6. On the medium crude grade (O3 of batch 2026-11-04-open-thermo-data) the bare
     * duty is 80.3 MW: 11.0 × 45 m holds 96.6, over 1.2 × 80.3 = 96.4.
     */
    @Test
    void h101TakesACabin() {
        var unit = alone.units().get("H-101");
        assertEquals(new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 45), unit.size());
        closest(unit.rating(), Limit.HEATER_DUTY);
        var duty = unit.rating().reading(Limit.HEATER_DUTY);
        assertEquals(96.6, duty.value(), TENTH);
        assertEquals(80.39, duty.low(), TENTH);
        assertEquals(path("P-101").spare(), read(unit.rating(), Limit.HEATER_SPARE), 0, "its path's spare, to V-101's set outlet");
    }

    /** V-101 ends P-101's path at its set outlet and takes what the path leaves at design flow: its drop and its share read that. */
    @Test
    void v101TakesItsLinesBoreAndItsPathsSpare() {
        var unit = alone.units().get("V-101");
        assertEquals(new ValveSizing.Size(.7), unit.size());
        var path = path("P-101");
        assertEquals("V-101", path.taker());
        assertEquals(path.spare(), read(unit.rating(), Limit.VALVE_DROP), 0);
        assertEquals(path.spare(), read(unit.rating(), Limit.PATH_SPARE), 0);
        assertEquals(100 * path.spare() / path.available(), read(unit.rating(), Limit.VALVE_SHARE), 1e-9);
        assertEquals(Lamp.GREEN, unit.rating().lamp());
    }

    private static com.wormzjl.icheme.science.flowsheet.sizing.PressurePath path(String start) {
        return alone.paths().stream().filter(path -> path.start().equals(start)).findFirst().orElseThrow();
    }

    @Test
    void e102() {
        var unit = alone.units().get("E-102");
        assertEquals(new AirCoolerSizing.Size(2.5, 6, new ProcessUnit.Bank(6, 3), 1), unit.size(), "S4c: the least face that holds 1.2 × the curve's need, 869 m²; 6 × 4 bays of 2.0 × 5 m held the bare need");
        closest(unit.rating(), Limit.AIR_COOLER_SURFACE);
        var surface = unit.rating().reading(Limit.AIR_COOLER_SURFACE);
        assertEquals(903, surface.value(), ONE);
        assertEquals(732.5, surface.low(), ONE, "the curve's surface");
        assertEquals(750.2, probesBareSurface(), ONE, "the probe's one capacity, from the same streams");
        itsCapacityRises("S-10", run.streams().get("S-10").temperature(), run.streams().get("RESIDUE").temperature());
        assertEquals(45, read(unit.rating(), Limit.AIR_COOLER_OUTLET), TENTH);
        assertEquals(.045, read(unit.rating(), Limit.AIR_COOLER_DROP), HUNDREDTH);
    }

    private static double probesBareSurface() {
        var in = run.streams().get("S-10");
        var out = run.streams().get("RESIDUE");
        double duty = 1.1 * (in.enthalpy() - out.enthalpy()), capacity = duty / (in.temperature() - out.temperature()), air = HeatSides.AIR_TEMPERATURE;
        return capacity * Math.log((in.temperature() - air) / (out.temperature() - air)) / HeatSides.airFilms(1).liquid();
    }

    @Test
    void theProbesBankStillHoldsItsDutyWhenThePlayerKeepsIt() {
        var kept = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo,
                new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("E-102", new SheetSizing.UnitEntry(new AirCoolerSizing.Size(2.5, 6, new ProcessUnit.Bank(5, 3), 1), null, null, false))))).units().get("E-102");
        assertEquals(Lamp.GREEN, kept.rating().lamp());
        assertEquals(752, read(kept.rating(), Limit.AIR_COOLER_SURFACE), ONE);
        assertEquals(.05, read(kept.rating(), Limit.AIR_COOLER_DROP), HUNDREDTH, "0.05 before O4's crude viscosity: the residue cooled to 45 C");
    }

    /** The plan's what-if: the sample has no storage tile, so each product stands for the liquid one would hold. */
    private static TankSizing.Load tile(String stream) {
        return MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> TankSizing.Load.of(run.streams().get(stream), FlowStream.empty(thermo.axis().size()), null, DesignBasis.DEFAULT, thermo));
    }

    @Test
    void theUnstabilisedNaphthaTakesASphereAndTheResidueARoof() {
        var naphtha = tile("NAPHTHA");
        // W5 D1 of batch 2026-11-11-aqueous-solvents: the column flashes on the network's derived pairs, so the receiver's naphtha is again at the network's bubble point of its own drum (2.3186 while the column
        // flashed on the authored matrix and the network on the derived pairs, W3b).
        assertEquals(2.2989, naphtha.bubble(), 2.2989 * .0005, "both bisect to a ratio of 1.0005, from other bounds; 2.3186 with the column on the authored matrix (W3b to W5a)");
        assertEquals(new TankSizing.Size(UnitType.SPHERE, 1, 15, 1), TankSizing.auto(naphtha, null).size(), "17 m on Tia Juana; the medium grade makes less naphtha (O3); 14 m before condenser carry-over (E31)");
        var residue = tile("RESIDUE");
        assertTrue(residue.bubble() < .015);
        assertEquals(new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 30, 18), TankSizing.auto(residue, null).size());
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
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> assertThrows(CancellationException.class, () -> SheetSizing.pass(sheet, run, stopping, SheetSizing.Entries.DEFAULT)));
    }
}
