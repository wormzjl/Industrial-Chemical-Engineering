package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2's unit gate (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4, 3.5 and 3.10), on loads by hand. Per class:
 * Auto's size holds, one step down fails, a Fixed size reads the same limits; each tile's proposal at both sides of its limit; what
 * the constructor refuses is red and no exception. {@link SheetSizingTest} holds the loads a run gives.
 */
final class UnitSizingTest {
    private static LineSizing.Carried liquid(double mass, double density, double viscosity) {
        return new LineSizing.Carried(mass, mass / density, density, viscosity, 100, null);
    }

    // ---- Pumps ----

    private static final PumpSizing.Load PUMP = new PumpSizing.Load(731, 71, 204.5, 80.4);

    @Test void aPumpTakesTheSmallestDischargeWhoseRatedFlowFits() {
        var choice = PumpSizing.auto(PUMP);
        assertEquals(new PumpSizing.Size(.3), choice.size());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(PumpSizing.rate(PUMP, choice.size()), choice.rating(), "Fixed reads the same limits");
        var down = PumpSizing.rate(PUMP, new PumpSizing.Size(.25));
        assertEquals(Lamp.YELLOW, down.lamp(), "one step down");
        assertSame(Limit.PUMP_RATED, down.closest().limit());
    }

    @Test void aPumpsWorldLimitsAreRed() {
        var size = new PumpSizing.Size(.3);
        assertSame(Limit.PUMP_FLOW, PumpSizing.rate(new PumpSizing.Load(40000, 71, 204.5, 80.), size).closest().limit());
        assertEquals(Lamp.RED, PumpSizing.rate(new PumpSizing.Load(40000, 71, 204.5, 80.), size).lamp());
        assertEquals(Lamp.RED, PumpSizing.rate(new PumpSizing.Load(731, 210, 204.5, 80.), size).lamp());
        assertEquals(Lamp.RED, PumpSizing.rate(new PumpSizing.Load(731, 71, 204.5, 0.), size).lamp(), "NPSH on 0 misses");
        assertEquals(Lamp.YELLOW, PumpSizing.rate(new PumpSizing.Load(731, 71, 204.5, .5), size).lamp());
    }

    @Test void aPumpWithNoHeightReadsNoNpshAtAll() {
        var rating = PumpSizing.rate(new PumpSizing.Load(731, 71, 204.5, null), new PumpSizing.Size(.3));
        assertNull(rating.reading(Limit.PUMP_NPSH));
        assertNull(rating.reading(Limit.PUMP_NPSH_WORLD));
    }

    @Test void aSizeTheConstructorRefusesIsRedAndNoException() {
        var rating = PumpSizing.rate(PUMP, new PumpSizing.Size(.33));
        assertEquals(Lamp.RED, rating.lamp());
        assertSame(Limit.UNIT_SHAPE, rating.closest().limit());
        assertFalse(rating.reading(Limit.PUMP_RATED).limit().rank() == Limit.Rank.QUIET, "its other rows still read");
    }

    @Test void noFlowNoSizeNoLamp() {
        assertNull(PumpSizing.auto(PumpSizing.Load.NONE).size());
        assertEquals(Lamp.NONE, PumpSizing.rate(PumpSizing.Load.NONE, new PumpSizing.Size(.3)).lamp());
        assertNull(DrumSizing.auto(DrumSizing.Load.NONE, null).size());
        assertNull(TankSizing.auto(TankSizing.Load.NONE, null).size());
        assertNull(CompressorSizing.auto(CompressorSizing.Load.NONE, null).size());
        assertNull(HeaterSizing.auto(new HeaterSizing.Load(0, null), null).size());
        assertNull(ExchangerSizing.auto(null).size());
        assertNull(AirCoolerSizing.auto(null).size());
    }

    // ---- Exchangers ----

    private static ExchangerSizing.Load exchanger(double needed, LineSizing.Carried tube, LineSizing.Carried shell, Limit shellDrop) {
        return new ExchangerSizing.Load(false, tube, shell, Limit.EXCHANGER_DROP_LIQUID, shellDrop, needed, 2e7, 80, 45, 45, null, null);
    }

    private static List<ExchangerSizing.Size> exchangers() {
        var sizes = new ArrayList<ExchangerSizing.Size>();
        for (int shells = 1; shells <= 6; shells++)
            for (double shell = .5; shell <= 2.5; shell += .25)
                for (int tubes = Math.max(2, (int) Math.ceil(3 * shell - 1e-9)); tubes <= 12; tubes++) sizes.add(new ExchangerSizing.Size(false, shells, shell, tubes));
        return sizes;
    }

    private static final ExchangerSizing.Load E_101 = exchanger(440, liquid(180, 760, 3e-4), liquid(100, 720, 8e-4), Limit.EXCHANGER_DROP_LIQUID);

    @Test void anExchangerTakesTheFewestShellsThenTheLeastSurface() {
        var choice = ExchangerSizing.auto(E_101);
        var size = choice.size();
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(ExchangerSizing.rate(E_101, size), choice.rating(), "Fixed reads the same limits");
        for (var other : exchangers()) {
            boolean smaller = other.shells() < size.shells() || other.shells() == size.shells() && other.surface() < size.surface();
            if (smaller && other.surface() >= SPARE * E_101.needed()) assertNotEquals(Lamp.GREEN, ExchangerSizing.rate(E_101, other).lamp(), other.toString());
        }
    }

    @Test void underTheNeedFailsItsSurface() {
        var size = exchangers().stream().filter(other -> other.surface() < E_101.needed() && ExchangerSizing.rate(E_101, other).reading(Limit.UNIT_SHAPE).met())
                .max(java.util.Comparator.comparingDouble(ExchangerSizing.Size::surface)).orElseThrow();
        var down = ExchangerSizing.rate(E_101, size);
        assertEquals(Lamp.RED, down.lamp());
        assertSame(Limit.EXCHANGER_SURFACE, down.closest().limit());
    }

    /** A gas shell asks for a wide bundle: one shell meets its drop only far past the need, so the cap sends Auto to more shells. */
    @Test void theSurfaceStaysUnderTwiceTheNeed() {
        var tube = liquid(100, 900, 1e-3);
        var shell = liquid(50, 12, 1.5e-5);
        var noCap = exchanger(1, tube, shell, Limit.EXCHANGER_DROP_GAS);
        double least = exchangers().stream().filter(size -> size.shells() == 1 && ExchangerSizing.rate(noCap, size).readings().stream().filter(reading -> reading.limit() != Limit.EXCHANGER_OVER_NEED).allMatch(Reading::met)).mapToDouble(ExchangerSizing.Size::surface).min().orElseThrow();
        var load = exchanger(least / 2.2, tube, shell, Limit.EXCHANGER_DROP_GAS);
        var choice = ExchangerSizing.auto(load);
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertTrue(choice.size().shells() > 1, "one shell meets its drops only past the cap");
        assertTrue(choice.size().surface() < 2 * load.needed());
    }

    @Test void curvesThatTouchAreRedAndAutoStillAnswers() {
        var load = new ExchangerSizing.Load(false, E_101.tube(), E_101.shell(), Limit.EXCHANGER_DROP_LIQUID, Limit.EXCHANGER_DROP_LIQUID, Double.POSITIVE_INFINITY, 2e7, 5, -3, -3, null, null);
        var choice = ExchangerSizing.auto(load);
        assertEquals(Lamp.RED, choice.rating().lamp());
        assertNotNull(choice.size());
        assertFalse(choice.rating().reading(Limit.EXCHANGER_SURFACE).met());
        assertFalse(choice.rating().reading(Limit.EXCHANGER_COLD_END).met());
    }

    @Test void aSmallApproachIsYellowAndHidesNothingASizeCanChange() {
        var tight = new ExchangerSizing.Load(false, E_101.tube(), E_101.shell(), Limit.EXCHANGER_DROP_LIQUID, Limit.EXCHANGER_DROP_LIQUID, 440, 2e7, 8, 6, 6, null, null);
        var choice = ExchangerSizing.auto(tight);
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
        assertSame(Limit.EXCHANGER_APPROACH, choice.rating().closest().limit());
        assertEquals(ExchangerSizing.auto(E_101).size(), choice.size(), "what no size moves leaves the choice alone");
    }

    @Test void coolingWaterOutReadsOnlyOnWater() {
        var water = new ExchangerSizing.Load(false, E_101.tube(), E_101.shell(), Limit.EXCHANGER_DROP_LIQUID, Limit.EXCHANGER_DROP_LIQUID, 440, 2e7, 80, 45, 45, 46., null);
        var size = ExchangerSizing.auto(E_101).size();
        assertEquals(Lamp.YELLOW, ExchangerSizing.rate(water, size).lamp());
        assertNull(ExchangerSizing.rate(E_101, size).reading(Limit.EXCHANGER_WATER_OUT));
    }

    @Test void aKettleHasNoShellDropAndReadsItsFlux() {
        var steam = liquid(10, 4, 1.5e-5);
        var load = new ExchangerSizing.Load(true, steam, null, Limit.EXCHANGER_DROP_GAS, null, 100, 6e6, 40, 30, 30, null, null);
        var choice = ExchangerSizing.auto(load);
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertTrue(choice.size().kettle());
        assertNull(choice.rating().reading(Limit.EXCHANGER_DROP_LIQUID));
        assertSame(Limit.KETTLE_FLUX, choice.rating().closest().limit(), "the flux asks more surface than the duty's need");
        assertTrue(choice.size().surface() >= 6e3 / 38);
        var tight = ExchangerSizing.rate(load, new ExchangerSizing.Size(true, 1, .5, 3));
        assertFalse(tight.reading(Limit.KETTLE_FLUX).met());
        assertEquals(Lamp.RED, ExchangerSizing.rate(load, new ExchangerSizing.Size(true, 1, 2.5, 8)).lamp(), "the constructor's kettle stops at 2 m");
    }

    // ---- Air coolers ----

    private static final AirCoolerSizing.Load E_102 = new AirCoolerSizing.Load(liquid(60, 800, 2e-3), 724, 45, null, 0);

    private static List<AirCoolerSizing.Size> banks() {
        var sizes = new ArrayList<AirCoolerSizing.Size>();
        for (var bay : ProcessUnit.bays())
            for (int parallel = 1; parallel <= 6; parallel++) for (int series = 1; series <= 4; series++) sizes.add(new AirCoolerSizing.Size(bay[0], (int) bay[1], new ProcessUnit.Bank(parallel, series), 1));
        return sizes;
    }

    @Test void anAirCoolerTakesTheLeastFaceWithinItsDrop() {
        var choice = AirCoolerSizing.auto(E_102);
        var size = choice.size();
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(AirCoolerSizing.rate(E_102, size), choice.rating(), "Fixed reads the same limits");
        for (var other : banks()) {
            if (other.surface() < SPARE * E_102.needed()) continue;
            if (other.face() < size.face()) assertNotEquals(Lamp.GREEN, AirCoolerSizing.rate(E_102, other).lamp(), other.toString());
            if (other.face() == size.face()) assertTrue(other.bank().bays() >= size.bank().bays(), "its fewest bays");
        }
    }

    @Test void oneStepDownFailsItsSurfaceAndAThickLiquidItsDrop() {
        var down = AirCoolerSizing.rate(E_102, new AirCoolerSizing.Size(2, 5, new ProcessUnit.Bank(5, 4), 1));
        assertEquals(Lamp.RED, down.lamp());
        assertSame(Limit.AIR_COOLER_SURFACE, down.closest().limit());
        var thick = new AirCoolerSizing.Load(liquid(150, 800, .2), 400, 45, null, 0);
        var choice = AirCoolerSizing.auto(thick);
        assertTrue(choice.rating().reading(Limit.AIR_COOLER_DROP).met());
        assertTrue(choice.size().bank().parallel() > 1, "trains in parallel carry it");
    }

    /** S3b: a duty past the largest bank takes identical banks in parallel, the fewest first; each carries its share, so the drop falls. */
    @Test void aDutyPastTheLargestBankTakesBanksInParallel() {
        double largest = banks().stream().mapToDouble(AirCoolerSizing.Size::surface).max().orElseThrow();
        var big = new AirCoolerSizing.Load(E_102.mean(), 1.5 * largest, 45, null, 0);
        var choice = AirCoolerSizing.auto(big);
        assertEquals(Lamp.GREEN, choice.rating().lamp(), choice.rating().toString());
        assertEquals(2, choice.size().banks());
        assertEquals(2, choice.size().count());
        assertEquals(2 * choice.size().withBanks(1).surface(), choice.size().surface(), 1e-9);
        assertEquals(Lamp.RED, AirCoolerSizing.rate(big, choice.size().withBanks(1)).lamp(), "one of them is short");
        assertTrue(AirCoolerSizing.drop(big, choice.size()) <= AirCoolerSizing.drop(big, choice.size().withBanks(1)) / 2 * (1 + 1e-9),
                "half the flow each: half the drop where the flow is laminar, less where it is turbulent");
        assertEquals(1, AirCoolerSizing.auto(E_102).size().banks(), "a duty one bank holds keeps one");
        assertThrows(IllegalArgumentException.class, () -> choice.size().withBanks(com.wormzjl.icheme.science.equipment.ColumnComponents.MAX_UNITS + 1));
    }

    @Test void anOutletTooCloseToTheAirIsYellowAndHidesNothingASizeCanChange() {
        var close = new AirCoolerSizing.Load(E_102.mean(), 724, 10, null, 0);
        var choice = AirCoolerSizing.auto(close);
        assertEquals(Lamp.YELLOW, choice.rating().lamp());
        assertEquals(AirCoolerSizing.auto(E_102).size(), choice.size());
    }

    // ---- Heaters ----

    /** S4c: a vertical while the largest holds the duty with Auto's spare. */
    @Test void aHeaterIsVerticalWhileTheLargestHoldsIt() {
        double largest = Limit.HEATER_VERTICAL.value();
        assertEquals(UnitType.VERTICAL_HEATER, HeaterSizing.type(new HeaterSizing.Load(largest / SPARE - 1e-9, null)), "on the edge, short of the product's last bit");
        assertEquals(UnitType.CABIN_HEATER, HeaterSizing.type(new HeaterSizing.Load(largest / SPARE + .01, null)));
    }

    @Test void aHeaterTakesTheLeastDutyThatHoldsTheSpare() {
        var load = new HeaterSizing.Load(86.2, null);
        var choice = HeaterSizing.auto(load, null);
        assertEquals(new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 12, 45), choice.size(), "11 × 41 m held the bare 86.2 MW before S4c");
        assertTrue(choice.size().held() >= SPARE * load.duty());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(HeaterSizing.rate(load, choice.size()), choice.rating(), "Fixed reads the same limits");
        var down = HeaterSizing.rate(load, new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 40));
        assertEquals(Lamp.RED, down.lamp(), "one step under the bare need's least");
        assertSame(Limit.HEATER_DUTY, down.closest().limit());
    }

    @Test void aHeaterPastTheLargestCabinTakesACount() {
        double largest = Limit.TYPE_CABIN_LARGEST.value();
        var one = HeaterSizing.auto(new HeaterSizing.Load(largest / SPARE - 1e-9, null), null);
        assertEquals(1, one.size().count());
        assertEquals(Lamp.GREEN, one.rating().lamp());
        var two = HeaterSizing.auto(new HeaterSizing.Load(largest / SPARE + 1, null), null);
        assertEquals(2, two.size().count());
        assertEquals(Lamp.GREEN, two.rating().lamp());
        assertTrue(two.size().held() < 2 * largest, "the count shares the duty");
    }

    @Test void aKeptTypeReadsItsOwnLimits() {
        var vertical = HeaterSizing.auto(new HeaterSizing.Load(86, null), UnitType.VERTICAL_HEATER);
        assertEquals(Lamp.RED, vertical.rating().lamp(), "over the largest vertical");
        assertNotNull(vertical.rating().reading(Limit.HEATER_VERTICAL));
        var cabin = HeaterSizing.auto(new HeaterSizing.Load(20, null), UnitType.CABIN_HEATER);
        assertEquals(Lamp.YELLOW, cabin.rating().lamp(), "a cabin under its usual duty");
        assertSame(Limit.HEATER_CABIN, cabin.rating().closest().limit());
    }

    @Test void aHeaterReadsItsSpareOnlyOnceThePathGivesIt() {
        var size = new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 41);
        assertNull(HeaterSizing.rate(new HeaterSizing.Load(86.2, null), size).reading(Limit.HEATER_SPARE));
        assertEquals(Lamp.RED, HeaterSizing.rate(new HeaterSizing.Load(86.2, -.1), size).lamp());
        assertEquals(Lamp.GREEN, HeaterSizing.rate(new HeaterSizing.Load(86.2, 0.), size).lamp());
    }

    @Test void aHeaterTheConstructorRefusesIsRed() {
        var rating = HeaterSizing.rate(new HeaterSizing.Load(86.2, null), new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11.5, 41));
        assertEquals(Lamp.RED, rating.lamp());
        assertSame(Limit.UNIT_SHAPE, rating.closest().limit());
    }

    // ---- Auto's spare, S4c ----

    private static final double SPARE = 1 + Limit.AUTO_SPARE.value() / 100;

    /** S4c: Auto proposes the equipment to build, the first size in its order that holds the need with the spare; the Musts read the bare need. */
    @Test void autoHoldsTheSpareWhereASizeCan() {
        assertEquals(1.2, SPARE, 1e-12, "the owner's 20 %, 2026-11-03");
        var exchanger = ExchangerSizing.auto(E_101);
        assertEquals(Lamp.GREEN, exchanger.rating().lamp());
        assertTrue(exchanger.size().surface() >= SPARE * E_101.needed(), exchanger.size().toString());
        assertEquals(E_101.needed(), exchanger.rating().reading(Limit.EXCHANGER_SURFACE).low(), 1e-9, "the Must reads the bare need");
        var cooler = AirCoolerSizing.auto(E_102);
        assertEquals(Lamp.GREEN, cooler.rating().lamp());
        assertTrue(cooler.size().surface() >= SPARE * E_102.needed(), cooler.size().toString());
        assertEquals(E_102.needed(), cooler.rating().reading(Limit.AIR_COOLER_SURFACE).low(), 1e-9);
        var heater = HeaterSizing.auto(new HeaterSizing.Load(86.2, null), null);
        assertEquals(Lamp.GREEN, heater.rating().lamp());
        assertTrue(heater.size().held() >= SPARE * 86.2, heater.size().toString());
        assertEquals(86.2, heater.rating().reading(Limit.HEATER_DUTY).low(), 1e-9);
    }

    /** My call, S4c: the bank count is part of Auto's order, so where one bank holds the need but not the spare, it takes two. */
    @Test void theSpareTakesOneMoreBankWhereOneCannotHoldIt() {
        double largest = banks().stream().mapToDouble(AirCoolerSizing.Size::surface).max().orElseThrow();
        var load = new AirCoolerSizing.Load(E_102.mean(), .9 * largest, 45, null, 0);
        var choice = AirCoolerSizing.auto(load);
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(2, choice.size().banks());
        assertTrue(choice.size().surface() >= SPARE * load.needed());
    }

    /** Where no size holds the spare, today's search over every size decides: the least that holds the bare need. A held type stays the player's. */
    @Test void whereNoSizeHoldsTheSpareAutoFallsBack() {
        double largest = Limit.HEATER_VERTICAL.value();
        var load = new HeaterSizing.Load(largest / 1.1, null);
        var choice = HeaterSizing.auto(load, UnitType.VERTICAL_HEATER);
        assertEquals(UnitType.VERTICAL_HEATER, choice.size().type());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertTrue(choice.size().held() < SPARE * load.duty(), "no vertical holds the spare");
        var plain = Selection.smallest(verticals(), size -> HeaterSizing.rate(load, size));
        assertEquals(plain.size(), choice.size(), "today's least duty that holds");
    }

    /**
     * The orchestrator's reading of the owner's "always have enough excess" (S4c): the type and the count of cabins follow the
     * duty with the spare; the lamps read the bare duty.
     */
    @Test void theHeatersTypeAndCountFollowTheSparedDuty() {
        double vertical = Limit.HEATER_VERTICAL.value();
        var cabin = HeaterSizing.auto(new HeaterSizing.Load(vertical / 1.1, null), null);
        assertEquals(UnitType.CABIN_HEATER, cabin.size().type(), "the largest vertical holds the duty, not its spare");
        assertTrue(cabin.size().held() >= SPARE * vertical / 1.1);
        assertEquals(Lamp.GREEN, cabin.rating().lamp());
        double largest = Limit.TYPE_CABIN_LARGEST.value();
        var two = HeaterSizing.auto(new HeaterSizing.Load(largest, null), null);
        assertEquals(2, two.size().count(), "one cabin holds the duty, not its spare");
        assertTrue(two.size().held() >= SPARE * largest);
        assertEquals(Lamp.GREEN, two.rating().lamp());
        assertEquals(largest, two.rating().reading(Limit.HEATER_DUTY).low(), 1e-9, "the Must reads the bare duty");
    }

    /** S4c: a compressor's frames that hold the flow with the spare come first; its type stays on the design flow. */
    @Test void aCompressorHoldsTheSpareWithinItsType() {
        var load = gas(10000, 3);
        var choice = CompressorSizing.auto(load, null);
        assertEquals(UnitType.PISTON, choice.size().type());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertTrue(choice.size().displacement(choice.size().stages(3)) >= SPARE * 10000, choice.size().toString());
        assertEquals(10000, choice.rating().reading(Limit.COMPRESSOR_DISPLACEMENT).low(), 1e-9, "the Should reads the bare flow");
        assertEquals(UnitType.PISTON, CompressorSizing.type(gas(14000, 3)), "a piston at 14000 m³/h, though its spare is 16800");
    }

    private static PressurePath setPath(double spare, String taker) {
        return new PressurePath("P", 10, 0, "D", 9 - spare, true, taker, List.of(new PressurePath.Step("S", PressurePath.Step.LINE, 1, 0)));
    }

    /** S4c: a set path with no valve at its end reads its spare over its losses, at least 20 %; a valve's path keeps the valve's rows. */
    @Test void aSetPathWithNoValveKeepsTwentyPercentOverItsLosses() {
        assertEquals(Lamp.RED, setPath(-.05, null).rating().lamp(), "below 0 the Must");
        assertEquals(Lamp.YELLOW, setPath(.1, null).rating().lamp(), "10 % of its 1 bar");
        assertEquals(Lamp.YELLOW, setPath(0, null).rating().lamp());
        assertEquals(Lamp.GREEN, setPath(.2, null).rating().lamp(), "on 20 %");
        assertEquals(Lamp.GREEN, setPath(.5, null).rating().lamp());
        assertEquals(10, setPath(.1, null).rating().reading(Limit.PATH_EXCESS).value(), 1e-9);
        var valve = setPath(.1, "V").rating();
        assertNull(valve.reading(Limit.PATH_EXCESS), "a valve's path reads the valve's share instead");
        assertEquals(Lamp.GREEN, valve.lamp());
        assertEquals(Rating.NONE, new PressurePath("P", 10, 0, "PRODUCT", 9.1, false, null, List.of(new PressurePath.Step("S", PressurePath.Step.LINE, 1, 0))).rating(), "an end the sheet does not set");
    }

    /** A Fixed size between the need and the spare holds the need, so its lamp is green; the spare is only Auto's order. */
    @Test void aFixedSizeBetweenTheNeedAndTheSpareIsGreen() {
        var heater = new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 41);
        assertTrue(heater.held() >= 86.2 && heater.held() < SPARE * 86.2);
        assertEquals(Lamp.GREEN, HeaterSizing.rate(new HeaterSizing.Load(86.2, null), heater).lamp());
        var between = exchangers().stream().filter(size -> size.surface() >= E_101.needed() && size.surface() < SPARE * E_101.needed() && ExchangerSizing.rate(E_101, size).lamp() == Lamp.GREEN).findFirst();
        assertTrue(between.isPresent(), "a size under the spare that meets every limit");
        assertTrue(ExchangerSizing.rate(E_101, between.get()).reading(Limit.EXCHANGER_SURFACE).met());
    }

    private static List<HeaterSizing.Size> verticals() {
        var design = com.wormzjl.icheme.science.equipment.UnitDesign.of(EquipmentShape.Kind.VERTICAL_HEATER);
        var sizes = new ArrayList<HeaterSizing.Size>();
        for (double size : UnitSize.grid(EquipmentShape.Kind.VERTICAL_HEATER))
            for (int length = design.minLength(size); length <= design.maxLength(size); length++) sizes.add(new HeaterSizing.Size(UnitType.VERTICAL_HEATER, 1, size, length));
        sizes.sort(java.util.Comparator.comparingDouble(HeaterSizing.Size::held));
        return sizes;
    }

    // ---- Compressors ----

    private static CompressorSizing.Load gas(double flow, double ratio) {
        return new CompressorSizing.Load(flow, ratio, 0, 310, .25, 0);
    }

    @Test void aCompressorIsAPistonToItsFlowThenATurbo() {
        assertEquals(UnitType.PISTON, CompressorSizing.type(gas(15000, 3)));
        assertEquals(UnitType.TURBO, CompressorSizing.type(gas(15001, 3)));
        assertEquals(UnitType.PISTON, CompressorSizing.type(gas(100, 3)), "under the smallest frame no turbo exists");
    }

    @Test void aCompressorTakesItsTypesSmallestFrame() {
        var load = gas(10000, 3);
        var choice = CompressorSizing.auto(load, null);
        var size = choice.size();
        assertEquals(UnitType.PISTON, size.type());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(CompressorSizing.rate(load, size), choice.rating(), "Fixed reads the same limits");
        assertTrue(size.frame() >= 10000);
        double bore = size.size();
        while (new CompressorSizing.Size(UnitType.PISTON, bore, size.length()).displacement(1) >= 10000) bore = Math.round((bore - .05) * 100) / 100.;
        var down = CompressorSizing.rate(load, new CompressorSizing.Size(UnitType.PISTON, bore, size.length()));
        assertEquals(Lamp.YELLOW, down.lamp(), "a step under the flow");
        assertSame(Limit.COMPRESSOR_DISPLACEMENT, down.closest().limit());
        var turbo = gas(20000, 8);
        var impellers = CompressorSizing.auto(turbo, null);
        assertEquals(new CompressorSizing.Size(UnitType.TURBO, .6, 2), impellers.size(), "0.5 m takes 19500 m³/h; two impellers hold 2.8 a stage");
        assertEquals(Lamp.GREEN, impellers.rating().lamp());
    }

    @Test void aPistonSharesItsPairsAmongTheStagesItsRatioAsks() {
        var size = new CompressorSizing.Size(UnitType.PISTON, .5, 4);
        assertEquals(1, size.stages(3));
        assertEquals(2, size.stages(8), "2.8 a stage");
        assertEquals(ProcessUnit.displacement(.5, 4), size.displacement(1), 0);
        assertEquals(ProcessUnit.displacement(.5, 2), size.displacement(2), 0, "its first stage has half the throws");
        assertEquals(Math.sqrt(8), CompressorSizing.rate(gas(1000, 8), size).reading(Limit.COMPRESSOR_STAGE_PISTON).value(), 1e-12);
        assertEquals(8, CompressorSizing.rate(gas(1000, 8), new CompressorSizing.Size(UnitType.PISTON, .5, 2)).reading(Limit.COMPRESSOR_STAGE_PISTON).value(), 1e-12, "one pair is one stage");
    }

    @Test void aCompressorsWorldLimitsAreRed() {
        var size = new CompressorSizing.Size(UnitType.PISTON, .5, 4);
        assertEquals(Lamp.RED, CompressorSizing.rate(gas(1000, 11), size).lamp());
        // The world compresses a wet suction since request 11 of batch 2026-11-03-rotating-machinery (W10a), so a wet suction
        // is a SHOULD row; W10b's liquid knock trips a piston machine and makes it the world's (red) again.
        assertEquals(Lamp.YELLOW, CompressorSizing.rate(new CompressorSizing.Load(1000, 3, 2, 310, .25, 0), size).lamp(), "wet at the suction");
        var small = CompressorSizing.rate(gas(6000, 3), new CompressorSizing.Size(UnitType.TURBO, .3, 2));
        assertEquals(Lamp.RED, small.lamp(), "under the smallest frame");
        assertSame(Limit.TYPE_TURBO, small.closest().limit());
        var large = CompressorSizing.rate(gas(20000, 3), new CompressorSizing.Size(UnitType.PISTON, 1.2, 10));
        assertEquals(Lamp.YELLOW, large.lamp(), "a piston past its flow");
        assertSame(Limit.TYPE_PISTON, large.closest().limit());
    }

    @Test void hydrogenTakesItsOwnDischargeRow() {
        var size = new CompressorSizing.Size(UnitType.PISTON, .5, 4);
        var air = new CompressorSizing.Load(1000, 3, 0, 320, .25, 0);
        var rich = new CompressorSizing.Load(1000, 3, 0, 320, .25, 60);
        assertTrue(CompressorSizing.rate(air, size).reading(Limit.COMPRESSOR_DISCHARGE).met());
        assertNull(CompressorSizing.rate(air, size).reading(Limit.COMPRESSOR_DISCHARGE_HYDROGEN));
        assertFalse(CompressorSizing.rate(rich, size).reading(Limit.COMPRESSOR_DISCHARGE_HYDROGEN).met());
        assertNull(CompressorSizing.rate(rich, size).reading(Limit.COMPRESSOR_DISCHARGE));
    }

    // ---- Drums ----

    /** Gas 0.5 m³/s between 5 and 700 kg/m³ wants D 1.5 m standing, and at D 1.5 m L/D 5 holds 13.3 m³ to half full. */
    private static DrumSizing.Load drum(double oil) {
        return new DrumSizing.Load(.5, oil, 0, 5, 700, 5);
    }

    @Test void aDrumStandsWhileItsHoldNeedsNoMoreThanItsSlenderness() {
        assertEquals(UnitType.STANDING_DRUM, DrumSizing.type(drum(.0215)));
        assertEquals(UnitType.LYING_DRUM, DrumSizing.type(drum(.024)));
        assertEquals(UnitType.LYING_DRUM, DrumSizing.type(new DrumSizing.Load(.5, .02, .001, 5, 700, 5)), "a second liquid takes a boot");
    }

    @Test void aDrumTakesItsTypesLeastVolume() {
        var load = drum(.0215);
        var choice = DrumSizing.auto(load, null);
        var size = choice.size();
        assertEquals(new DrumSizing.Size(UnitType.STANDING_DRUM, 1.5, 7, false), size);
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(DrumSizing.rate(load, size), choice.rating(), "Fixed reads the same limits");
        var shorter = DrumSizing.rate(load, new DrumSizing.Size(UnitType.STANDING_DRUM, 1.5, 6, false));
        assertEquals(Lamp.YELLOW, shorter.lamp(), "one step down");
        assertSame(Limit.DRUM_HOLD, shorter.closest().limit());
        var narrower = DrumSizing.rate(load, new DrumSizing.Size(UnitType.STANDING_DRUM, 1, 15, false));
        assertFalse(narrower.reading(Limit.DRUM_GAS_STANDING).met());
    }

    @Test void aLyingDrumTakesAHarderGasLimitOnHalfItsSection() {
        var size = new DrumSizing.Size(UnitType.LYING_DRUM, 2, 6, false);
        var reading = DrumSizing.rate(drum(.024), size).reading(Limit.DRUM_GAS_LYING);
        assertEquals(.5 / (Math.PI * 4 / 8), reading.value(), 1e-12);
        assertEquals(1.25 * .107 / 2 * Math.sqrt(695 / 5.), reading.high(), 1e-12);
    }

    @Test void aSecondLiquidTakesABootAndItsOwnHold() {
        var load = new DrumSizing.Load(.2, .05, .002, 5, 700, 5);
        var choice = DrumSizing.auto(load, null);
        var size = choice.size();
        assertEquals(UnitType.LYING_DRUM, size.type());
        assertTrue(size.boot());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        var boot = choice.rating().reading(Limit.DRUM_BOOT_HOLD);
        var shape = size.shape();
        var bare = new DrumSizing.Size(UnitType.LYING_DRUM, size.diameter(), size.length(), false).shape();
        assertEquals((shape.volume() - bare.volume()) / .002 / 60, boot.value(), 1e-9, "what the boot adds to the drum");
        assertEquals(bare.volume() / 2 / .05 / 60, choice.rating().reading(Limit.DRUM_HOLD).value(), 1e-9, "the oil holds the drum, heads and all");
    }

    @Test void aBootWithNoRoomIsRedAndNoException() {
        var rating = DrumSizing.rate(new DrumSizing.Load(.2, .05, .002, 5, 700, 5), new DrumSizing.Size(UnitType.LYING_DRUM, .5, 3, true));
        assertEquals(Lamp.RED, rating.lamp());
        assertFalse(rating.reading(Limit.DRUM_BOOT).met());
        assertEquals(Lamp.RED, DrumSizing.rate(drum(.02), new DrumSizing.Size(UnitType.STANDING_DRUM, .75, 4, false)).lamp(), "off the constructor's grid");
        assertEquals(Lamp.RED, DrumSizing.rate(drum(.02), new DrumSizing.Size(UnitType.STANDING_DRUM, 11, 4, false)).lamp(), "over its diameter");
    }

    @Test void aDrumsEntryMovesItsHold() {
        var size = DrumSizing.auto(drum(.0215), null).size();
        var longer = new DrumSizing.Load(.5, .0215, 0, 5, 700, 10);
        assertFalse(DrumSizing.rate(longer, size).reading(Limit.DRUM_HOLD).met());
        assertTrue(DrumSizing.auto(longer, null).size().shape().volume() > size.shape().volume());
    }

    // ---- Tanks ----

    @Test void aTankIsAFloatingRoofToItsBubblePressureThenASphere() {
        double limit = Limit.ROOF_BUBBLE.value();
        assertEquals(UnitType.FLOATING_ROOF, TankSizing.type(new TankSizing.Load(100, limit, 24)));
        assertEquals(UnitType.SPHERE, TankSizing.type(new TankSizing.Load(100, limit + .001, 24)));
    }

    @Test void aTankTakesTheFewestVesselsThenTheLeastVolume() {
        var load = new TankSizing.Load(100, .1, 24);
        var choice = TankSizing.auto(load, null);
        var size = choice.size();
        assertEquals(new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 19, 11), size);
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(TankSizing.rate(load, size), choice.rating(), "Fixed reads the same limits");
        var down = TankSizing.rate(load, new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 18, 11));
        assertEquals(Lamp.YELLOW, down.lamp(), "one step down");
        assertSame(Limit.TANK_WORKING, down.closest().limit());
    }

    @Test void aLargeHoldTakesACount() {
        var choice = TankSizing.auto(new TankSizing.Load(3000, .1, 24), null);
        assertEquals(3, choice.size().count());
        assertEquals(40, choice.size().diameter(), 0);
        assertEquals(Lamp.GREEN, choice.rating().lamp());
    }

    @Test void aRoofOverAmbientIsRedAndASphereReadsItsDesignPressure() {
        var hot = new TankSizing.Load(100, 1.1, 24);
        var roof = TankSizing.rate(hot, new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 19, 11));
        assertEquals(Lamp.RED, roof.lamp());
        assertSame(Limit.ROOF_BUBBLE_WORLD, roof.closest().limit());
        var warm = new TankSizing.Load(100, .9, 24);
        assertEquals(Lamp.YELLOW, TankSizing.rate(warm, new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 19, 11)).lamp());
        var sphere = TankSizing.auto(new TankSizing.Load(100, 19, 24), null);
        assertEquals(Lamp.YELLOW, sphere.rating().lamp(), "1.1 times 19 bar");
        assertEquals(20.9, sphere.rating().reading(Limit.SPHERE_DESIGN).value(), 1e-9);
    }

    @Test void aTanksEntryMovesItsHold() {
        var day = TankSizing.auto(new TankSizing.Load(100, .1, 24), null).size();
        var two = TankSizing.auto(new TankSizing.Load(100, .1, 48), null).size();
        assertTrue(two.diameter() > day.diameter());
    }

    @Test void aTankTheConstructorRefusesIsRed() {
        var rating = TankSizing.rate(new TankSizing.Load(100, .1, 24), new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 19.5, 11));
        assertEquals(Lamp.RED, rating.lamp());
        assertSame(Limit.UNIT_SHAPE, rating.closest().limit());
    }

    // ---- Valves ----

    @Test void aValveReadsItsDropAndItsShareOnceThePathGivesIt() {
        assertEquals(Lamp.GREEN, ValveSizing.rate(new ValveSizing.Load(2.28, null)).lamp());
        assertNull(ValveSizing.rate(new ValveSizing.Load(2.28, null)).reading(Limit.VALVE_SHARE));
        assertEquals(Lamp.YELLOW, ValveSizing.rate(new ValveSizing.Load(.5, null)).lamp());
        var share = ValveSizing.rate(new ValveSizing.Load(2.28, 20.));
        assertEquals(Lamp.YELLOW, share.lamp());
        assertEquals(11.4, share.reading(Limit.VALVE_SHARE).value(), 1e-9);
        assertNull(new ValveSizing.Size(.7).shape(), "the constructor has no valve");
    }

    // ---- Every shape stands ----

    @Test void everySizeAutoGivesIsAShapeTheConstructorBuilds() {
        var sizes = new ArrayList<UnitSize>();
        sizes.add(PumpSizing.auto(PUMP).size());
        sizes.add(ExchangerSizing.auto(E_101).size());
        sizes.add(AirCoolerSizing.auto(E_102).size());
        sizes.add(HeaterSizing.auto(new HeaterSizing.Load(86.2, null), null).size());
        sizes.add(HeaterSizing.auto(new HeaterSizing.Load(10, null), null).size());
        sizes.add(CompressorSizing.auto(gas(10000, 3), null).size());
        sizes.add(CompressorSizing.auto(gas(20000, 8), null).size());
        sizes.add(DrumSizing.auto(drum(.0215), null).size());
        sizes.add(DrumSizing.auto(new DrumSizing.Load(.2, .05, .002, 5, 700, 5), null).size());
        sizes.add(TankSizing.auto(new TankSizing.Load(100, .1, 24), null).size());
        sizes.add(TankSizing.auto(new TankSizing.Load(100, 2, 24), null).size());
        for (var size : sizes) {
            assertNotNull(size);
            assertTrue(size.shape() instanceof EquipmentShape, size.toString());
        }
    }
}
