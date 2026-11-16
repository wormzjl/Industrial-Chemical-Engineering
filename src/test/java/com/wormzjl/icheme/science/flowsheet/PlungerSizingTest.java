package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Pump tile's plunger pump on the simulator's sheet (batch 2026-11-15-supercritical-fluids, S8, PLUNGER_PUMP_PLAN.md
 * section 5): Auto's type, the plunger's rows (flow with its spare against the displacement, rise against the relief rise; no
 * head, suction density or NPSH row), its power as flow times rise over 0.88, and its size in the sheet file.
 */
class PlungerSizingTest {
    private static final double MOST = 204.5, G = 9.80665;

    /** A pump at {@code flow} m³/h and {@code head} m of a liquid of {@code density}, its suction's reduced density {@code delta} (NaN: no dense fluid). */
    private static PumpSizing.Load load(double flow, double head, double density, double delta) {
        return new PumpSizing.Load(flow, head, MOST, null, density, Double.POSITIVE_INFINITY, true, delta);
    }

    @Test void autoProposesThePlungerPumpForALightDenseFluidOrAHighRiseAtASmallFlow() {
        assertEquals(UnitType.CENTRIFUGAL_PUMP, PumpSizing.type(load(50, 80, 1000, Double.NaN)), "water at 80 m");
        assertEquals(UnitType.PLUNGER, PumpSizing.type(load(10, 300, 1000, Double.NaN)), "water above the centrifugal casing's head");
        assertEquals(UnitType.PLUNGER, PumpSizing.type(load(20, 100, 330, .7)), "carbon dioxide at reduced density 0.7");
        assertEquals(UnitType.CENTRIFUGAL_PUMP, PumpSizing.type(load(20, 100, 450, .9)), "carbon dioxide at 0.9: its suction density row stays dark");
        assertEquals(UnitType.CENTRIFUGAL_PUMP, PumpSizing.type(load(150, 300, 330, .7)), "beyond the largest plunger pump");
        assertEquals(UnitType.CENTRIFUGAL_PUMP, PumpSizing.type(PumpSizing.Load.NONE), "no flow");
    }

    @Test void autoTakesTheSmallestModelThatHoldsTheFlowWithItsSpare() {
        var load = load(20, 1000, 1000, Double.NaN);
        var choice = PumpSizing.autoPlunger(load);
        assertEquals(new PumpSizing.Plunger(3), choice.size(), "20 m³/h with 20 % is 24: the 30 m³/h model");
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(80, choice.rating().reading(Limit.PUMP_RATED).value(), 1e-9);
        assertEquals(PumpSizing.rate(load, choice.size()), choice.rating(), "Fixed reads the same limits");
        assertEquals(Lamp.YELLOW, PumpSizing.rate(load, new PumpSizing.Plunger(2)).lamp(), "24 m³/h is more than 10 displace");
        assertNull(PumpSizing.autoPlunger(PumpSizing.Load.NONE).size());
    }

    @Test void aPlungerPumpReadsItsRiseAndNoDensityHeadOrNpshRow() {
        var rating = PumpSizing.rate(load(20, 100, 330, .7), new PumpSizing.Plunger(3));
        assertNull(rating.reading(Limit.PUMP_DENSITY));
        assertNull(rating.reading(Limit.PUMP_HEAD));
        assertNull(rating.reading(Limit.PUMP_NPSH));
        assertNull(rating.reading(Limit.PUMP_NPSH_WORLD));
        assertEquals(100 * 330 * G / 1e6, rating.reading(Limit.PLUNGER_RISE).value(), 1e-12);
        assertEquals(Lamp.GREEN, rating.lamp());
        double over = 26e6 / (1000 * G);
        assertEquals(Lamp.YELLOW, PumpSizing.rate(load(20, over, 1000, Double.NaN), new PumpSizing.Plunger(3)).lamp(), "above its relief rise");
    }

    @Test void itsPowerIsFlowTimesRiseOverTheMechanicalEfficiency() {
        double head = 1000, rise = head * 1000 * G;
        var duty = PumpSizing.Duty.of(PumpSizing.rate(load(20, head, 1000, Double.NaN), new PumpSizing.Plunger(3)));
        assertNotNull(duty);
        assertEquals(20 / 3600. * rise / .88, duty.shaft(), 1e-6);
        assertTrue(duty.motor() >= 1.2 * duty.shaft(), "a motor with its spare");
        assertTrue(Double.isNaN(duty.npsh()), "no NPSH3 of its own");
    }

    /** On a compressible suction (P1 of batch 2026-11-16-supercritical-followups): the duty is the flow times the isentropic work
     * over 0.88, and the flow is rated against the displacement times the clearance's share. Carbon dioxide at 310 K from
     * 8.5 MPa, a rise of 16.5 MPa, its exponent 2.6 (about the model's). */
    @Test void onACompressibleSuctionItsDutyIsTheIsentropicWorkAndItsRatingTheDelivery() {
        double p = 8.5e6, n = 2.6, rise = 16.5e6, density = 470, head = rise / (density * G);
        var load = new PumpSizing.Load(20, head, MOST, null, density, Double.POSITIVE_INFINITY, true, 1.1, p, n);
        var rating = PumpSizing.rate(load, new PumpSizing.Plunger(3));
        double etaV = com.wormzjl.icheme.science.machine.PlungerPump.volumetricEfficiency(rise, p, n);
        assertTrue(etaV < .97 && etaV > .9, "the clearance costs delivery: " + etaV);
        assertEquals(100 * 24 / (30 * etaV), rating.reading(Limit.PUMP_RATED).value(), 1e-9);
        var duty = PumpSizing.Duty.of(rating);
        assertNotNull(duty);
        assertEquals(20 / 3600. * com.wormzjl.icheme.science.machine.PlungerPump.isentropicWork(rise, p, n) / .88, duty.shaft(), 1e-6);
        assertTrue(duty.shaft() < 20 / 3600. * rise / .88, "below Q dP");
        var liquid = new PumpSizing.Load(20, head, MOST, null, density, Double.POSITIVE_INFINITY, true, Double.NaN);
        assertEquals(80, PumpSizing.rate(liquid, new PumpSizing.Plunger(3)).reading(Limit.PUMP_RATED).value(), 1e-9, "no suction given: the displacement");
    }

    @Test void aPlungerSizeAndItsTypeComeBackFromTheSheetFile() {
        var facts = SizingFacts.NONE.with("p1", new SizingFacts.UnitFacts(SizingFacts.Mode.FIXED, UnitType.PLUNGER, new PumpSizing.Plunger(4), null, new JsonObject(), new JsonObject()))
                .with("p2", new SizingFacts.UnitFacts(SizingFacts.Mode.FIXED, UnitType.CENTRIFUGAL_PUMP, new PumpSizing.Size(.3), null, new JsonObject(), new JsonObject()));
        var back = SizingFactsFile.read(new JsonFields(SizingFactsFile.write(facts), "sizing"));
        assertEquals(new PumpSizing.Plunger(4), back.unit("p1").size());
        assertEquals(UnitType.PLUNGER, back.unit("p1").type());
        assertEquals(new PumpSizing.Size(.3), back.unit("p2").size());
        assertEquals(UnitType.CENTRIFUGAL_PUMP, back.unit("p2").type());
        assertEquals(UnitType.PLUNGER, SizingFacts.UnitFacts.typeOf(new PumpSizing.Plunger(1)));
        assertEquals(UnitType.CENTRIFUGAL_PUMP, SizingFacts.UnitFacts.typeOf(new PumpSizing.Size(.3)));
    }

    @Test void aPlungerSizeBuildsItsModel() {
        for (int model = 1; model <= 4; model++) {
            var shape = new PumpSizing.Plunger(model).shape();
            assertEquals(com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.PLUNGER_PUMP, shape.kind());
            assertEquals(model, shape.diameter());
        }
        assertThrows(IllegalArgumentException.class, () -> new PumpSizing.Plunger(5));
    }
}
