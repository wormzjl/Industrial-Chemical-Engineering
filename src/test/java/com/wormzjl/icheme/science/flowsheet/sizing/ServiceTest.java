package com.wormzjl.icheme.science.flowsheet.sizing;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.fluid.network.NetworkReduction;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.fluid.transport.SolidMobility;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S1's service gate on the world's own flash (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.3): every rule at both sides of its threshold. */
final class ServiceTest {
    private static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final FluidThermodynamics MODEL = MaterialRuntime.with(CATALOG, PACKAGE, () -> FluidThermodynamics.forNetwork(CATALOG, PACKAGE));
    private static final FlowThermo THERMO = new FlowThermo(MODEL, CATALOG, PACKAGE, () -> {
    });
    private static final List<String> AXIS = MODEL.components();
    private static final int WATER = AXIS.size() - 1, NITROGEN = AXIS.indexOf(FluidMaterialCatalog.NITROGEN);
    private static final Service.Ends OPEN = Service.Ends.OPEN, PUMP = new Service.Ends(false, true), TRAY = new Service.Ends(true, false), TRAY_TO_PUMP = new Service.Ends(true, true);
    private static final double[] CRUDE = crude();

    private static double[] crude() {
        var crude = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), AXIS.size());
        crude[WATER] = 0;
        return crude;
    }

    /** 100 mol/s of {@code of}, fractions that sum to 1, with {@code share} of {@code other} in its place. */
    private static double[] mix(double[] of, double share, int other) {
        double[] amounts = new double[of.length];
        for (int i = 0; i < of.length; i++) amounts[i] = 100 * of[i] * (1 - share);
        amounts[other] += 100 * share;
        return amounts;
    }

    private static double[] pure(int component) {
        return mix(new double[AXIS.size()], 1, component);
    }

    /** 100 mol/s of steam with {@code share} of nitrogen. */
    private static double[] steamWith(double share) {
        double[] amounts = pure(WATER);
        amounts[WATER] -= 100 * share;
        amounts[NITROGEN] = 100 * share;
        return amounts;
    }

    private static FlowStream at(double temperature, double pressure, double[] amounts) {
        return THERMO.atTemperature(temperature, pressure, amounts, SolidInventory.EMPTY);
    }

    private static Service service(FlowStream stream, Service.Ends ends) {
        return Service.of(stream, ends, THERMO, null);
    }

    private static <T> T on(Supplier<T> test) {
        return MaterialRuntime.with(CATALOG, PACKAGE, test::get);
    }

    /** Vapour's share of a stream's mass, %. */
    private static double vapour(FlowStream stream) {
        return 100 * MODEL.vaporMass(stream.state()) / stream.mass();
    }

    /** The crude at the temperature that gives {@code share} % vapour by mass at {@code pressure}. */
    private static FlowStream crudeAt(double share, double pressure) {
        double low = 300, high = 1000;
        double[] amounts = mix(CRUDE, 0, WATER);
        for (int i = 0; i < 60; i++) {
            double middle = .5 * (low + high);
            if (vapour(at(middle, pressure, amounts)) < share) low = middle;
            else high = middle;
        }
        return at(.5 * (low + high), pressure, amounts);
    }

    @Test void aVapourAndTwoPhaseBothSidesOfTheirShares() {
        on(() -> {
            assertEquals(Service.GAS, service(crudeAt(95.5, 3e5), OPEN));
            assertEquals(Service.TWO_PHASE, service(crudeAt(94.5, 3e5), OPEN));
            assertEquals(Service.TWO_PHASE, service(crudeAt(.07, 3e5), OPEN));
            assertEquals(Service.LIQUID, service(crudeAt(.03, 3e5), OPEN));
            return null;
        });
    }

    @Test void waterIsAbove95MolePercentOfTheLiquid() {
        on(() -> {
            assertEquals(Service.WATER, service(at(300, 5e5, mix(CRUDE, .96, WATER)), OPEN));
            assertEquals(Service.LIQUID, service(at(300, 5e5, mix(CRUDE, .94, WATER)), OPEN));
            assertEquals(Service.WATER, service(at(300, 5e5, pure(WATER)), OPEN));
            return null;
        });
    }

    @Test void whatALineFeedsOrLeavesComesBeforeWhatItCarries() {
        on(() -> {
            var water = at(300, 5e5, pure(WATER));
            assertEquals(Service.DRAW, service(water, TRAY), "a draw's limit protects the column's head, whatever the liquid");
            assertEquals(Service.DRAW, service(water, TRAY_TO_PUMP));
            assertEquals(Service.SUCTION_COLD, service(water, PUMP), "a suction's limit protects its pump, whatever the liquid");
            return null;
        });
    }

    @Test void aLiquidLeavingATrayIsADrawEvenWhereItFeedsAPump() {
        on(() -> {
            var liquid = at(400, 30e5, mix(CRUDE, 0, WATER));
            assertEquals(Service.DRAW, service(liquid, TRAY));
            assertEquals(Service.DRAW, service(liquid, TRAY_TO_PUMP));
            return null;
        });
    }

    @Test void aSuctionIsBoilingWithinItsThresholdOfTheBubblePressure() {
        on(() -> {
            double[] amounts = mix(CRUDE, 0, WATER);
            double bubble = THERMO.bubblePressure(400, amounts);
            assertEquals(Service.SUCTION_BOILING, service(at(400, bubble + .25e5, amounts), PUMP));
            assertEquals(Service.SUCTION_COLD, service(at(400, bubble + .35e5, amounts), PUMP));
            assertEquals(Service.LIQUID, service(at(400, bubble + .25e5, amounts), OPEN));
            return null;
        });
    }

    /** Water's saturation temperature at {@code pressure}. */
    private static double saturation(double pressure) {
        double low = 300, high = 640;
        for (int i = 0; i < 60; i++) {
            double middle = .5 * (low + high);
            if (MODEL.saturationPressure(middle) < pressure) low = middle;
            else high = middle;
        }
        return .5 * (low + high);
    }

    @Test void steamIsSuperheatedFromFiveKelvinOverItsSaturation() {
        on(() -> {
            double saturated = saturation(3.5e5);
            assertEquals(Service.STEAM_SATURATED, service(at(saturated + 4.5, 3.5e5, pure(WATER)), OPEN));
            assertEquals(Service.STEAM_SUPERHEATED, service(at(saturated + 5.5, 3.5e5, pure(WATER)), OPEN));
            assertEquals(Service.STEAM_SUPERHEATED, service(at(1000, 3.5e5, pure(WATER)), OPEN), "above the critical point there is no saturation");
            return null;
        });
    }

    @Test void wetSteamIsSaturatedUntilItIsTwoPhase() {
        on(() -> {
            double saturated = saturation(3.5e5);
            double liquid = at(saturated - 1, 3.5e5, pure(WATER)).enthalpy(), steam = at(saturated + 1, 3.5e5, pure(WATER)).enthalpy();
            var wet = THERMO.atEnthalpy(3.5e5, .98 * steam + .02 * liquid, pure(WATER), saturated, SolidInventory.EMPTY);
            assertTrue(vapour(wet) > 95 && vapour(wet) < 100);
            assertEquals(Service.STEAM_SATURATED, service(wet, OPEN));
            assertEquals(Service.TWO_PHASE, service(THERMO.atEnthalpy(3.5e5, .9 * steam + .1 * liquid, pure(WATER), saturated, SolidInventory.EMPTY), OPEN));
            return null;
        });
    }

    @Test void itsGasIsWaterAbove95MolePercentAndReadsItsPartialPressure() {
        on(() -> {
            assertEquals(Service.GAS, service(at(500, 3e5, steamWith(.06)), OPEN));
            double partial = saturation(.96 * 3e5);
            assertEquals(Service.STEAM_SATURATED, service(at(partial + 4.5, 3e5, steamWith(.04)), OPEN));
            assertEquals(Service.STEAM_SUPERHEATED, service(at(partial + 5.5, 3e5, steamWith(.04)), OPEN));
            return null;
        });
    }

    @Test void aVapourUnderAmbientIsVacuumVapourWhateverItsGas() {
        on(() -> {
            assertEquals(Service.VACUUM_VAPOUR, service(at(300, 1.01e5, pure(NITROGEN)), OPEN));
            assertEquals(Service.GAS, service(at(300, 101325, pure(NITROGEN)), OPEN));
            assertEquals(Service.GAS, service(at(300, 1.02e5, pure(NITROGEN)), OPEN));
            assertEquals(Service.VACUUM_VAPOUR, service(at(400, .5e5, pure(WATER)), OPEN), "steam under ambient");
            assertEquals(Service.STEAM_SUPERHEATED, service(at(400, 1.1e5, pure(WATER)), OPEN));
            return null;
        });
    }

    @Test void gasAndVacuumVapourMeetAtAmbient() {
        assertEquals(Limit.GAS_SPEED.value(0), Limit.VACUUM_SPEED.value(1), 0, "the same speed either side");
        double drop = Limit.VACUUM_DROP.value() / 100 * Limit.SERVICE_VACUUM.value() * 100 / DesignBasis.DEFAULT.lineLength();
        assertEquals(.10, drop, .002, "bar/100 m on the default line, against Gas's 0.11");
    }

    @Test void thePlayersChoiceStands() {
        on(() -> {
            assertEquals(Service.GAS, Service.of(at(300, 5e5, pure(WATER)), OPEN, THERMO, Service.GAS));
            assertEquals(Service.DRAW, Service.of(FlowStream.empty(AXIS.size()), OPEN, THERMO, Service.DRAW));
            return null;
        });
    }

    @Test void noFlowHasNoService() {
        var empty = FlowStream.empty(AXIS.size());
        assertNull(service(empty, OPEN));
        assertEquals(LineSizing.Line.NONE, LineSizing.Line.of(empty, null, DesignBasis.DEFAULT, THERMO, PipeSizes.MAX_BORE, null, new MixtureViscosity.Workspace()));
    }

    private static Flowsheet.Unit unit(String id, String type) {
        return new Flowsheet.Unit(id, type, id, 0, 0, new Params(new JsonObject()));
    }

    private static Service.Ends ends(String port, String to) {
        var stream = new Flowsheet.Stream("s", "s", new Flowsheet.End("T", port), new Flowsheet.End(to, "in"), null);
        var sheet = new Flowsheet(new Flowsheet.Basis(PACKAGE, "r", AXIS), List.of(unit("T", "column"), unit("P", "pump"), unit("V", "valve")), List.of(stream), FlowsheetSolver.Settings.DEFAULT);
        return Service.Ends.of(sheet, stream);
    }

    @Test void aLinesEndsSayWhatItLeavesAndFeeds() {
        assertEquals(TRAY_TO_PUMP, ends("draw/2", "P"));
        assertEquals(TRAY, ends("bottoms", "V"));
        assertEquals(PUMP, ends("distillate", "P"));
        assertEquals(OPEN, ends("overhead", "V"));
        assertEquals(OPEN, ends("pa/1/out", "V"), "a pumparound's cold side leaves no tray");
        var pill = new Flowsheet.Stream("s", "s", null, null, null);
        assertEquals(OPEN, Service.Ends.of(new Flowsheet(new Flowsheet.Basis(PACKAGE, "r", AXIS), List.of(), List.of(pill), FlowsheetSolver.Settings.DEFAULT), pill));
    }

    private static FlowStream slurry(FlowStream fluid, double massFlow) {
        var material = MODEL.solids.materials().get("icheme:demo_particle");
        var solids = new SolidInventory(List.of(new SolidInventory.Population(material, ParticleSize.micrometres("100"), massFlow)));
        return THERMO.atTemperature(fluid.temperature(), fluid.pressure(), fluid.amounts(), solids);
    }

    @Test void aLiquidLineCarriesItsSolids() {
        on(() -> {
            var water = at(320, 3e5, pure(WATER));
            var mud = slurry(water, 5);
            assertEquals(Service.WATER, service(mud, OPEN), "solids leave the phase tests alone");
            var scratch = new MixtureViscosity.Workspace();
            var clean = LineSizing.Line.of(water, Service.WATER, DesignBasis.DEFAULT, THERMO, PipeSizes.MAX_BORE, null, scratch);
            var line = LineSizing.Line.of(mud, Service.WATER, DesignBasis.DEFAULT, THERMO, PipeSizes.MAX_BORE, null, scratch);
            assertEquals(1.10 * (water.mass() + 5), line.mass(), 1e-9);
            assertTrue(line.density() > clean.density() && line.viscosity() > clean.viscosity(), "the world's slurry");
            assertEquals(NetworkReduction.viscosity(MODEL, mud.state(), scratch), line.viscosity(), 0);
            return null;
        });
    }

    private static LineSizing.Line line(FlowStream stream, Service service) {
        return LineSizing.Line.of(stream, service, DesignBasis.DEFAULT, THERMO, PipeSizes.MAX_BORE, null, new MixtureViscosity.Workspace());
    }

    private static LineSizing.Line solids(LineSizing.Line line, SolidMobility.Donor donor) {
        return new LineSizing.Line(line.service(), line.mass(), line.volume(), line.density(), line.viscosity(), line.pressure(), line.length(), line.nozzle(), line.speedLimit(), new LineSizing.Solids(donor, line.solids().state()));
    }

    @Test void aLineWithNoSolidsCarriesNoSuchReading() {
        on(() -> {
            var size = new LineSizing.Size(.3, 1);
            var line = line(at(320, 3e5, pure(WATER)), Service.WATER);
            assertNull(LineSizing.rate(line, size).reading(Limit.LINE_SOLIDS));
            assertNull(LineSizing.mobility(line, size));
            return null;
        });
    }

    /** The row says what the world's own check says, at every usual bore. */
    @Test void aSlurryLineReadsTheWorldsCheck() {
        on(() -> {
            var line = line(slurry(at(320, 3e5, pure(WATER)), .2), Service.WATER);
            int refused = 0;
            for (double bore : new double[]{.05, .1, .2, .3, .5, .8}) {
                var size = new LineSizing.Size(bore, 1);
                var check = LineSizing.mobility(line, size);
                var reading = LineSizing.rate(line, size).reading(Limit.LINE_SOLIDS);
                assertEquals(check.allowed(), reading.met(), bore + " m");
                assertEquals(check.minimumVelocity(), reading.low(), 0);
                if (!check.allowed()) refused++;
            }
            assertTrue(refused > 0 && refused < 6, "some bores carry it and some do not: " + refused);
            return null;
        });
    }

    @Test void onTheSpeedThatSuspendsItsSolidsALineStillDeposits() {
        on(() -> {
            var line = line(slurry(at(320, 3e5, pure(WATER)), .2), Service.WATER);
            var size = new LineSizing.Size(.3, 1);
            double speed = LineSizing.mobility(line, size).velocity();
            var donor = line.solids().donor();
            var on = solids(line, new SolidMobility.Donor(false, false, true, donor.liquidVolume(), donor.solidVolume(), donor.largestDiameter(), speed));
            assertEquals(SolidMobility.Reason.DEPOSITION, LineSizing.mobility(on, size).reason(), "the check is strict");
            assertEquals(Lamp.RED, LineSizing.rate(on, size).lamp());
            var just = solids(line, new SolidMobility.Donor(false, false, true, donor.liquidVolume(), donor.solidVolume(), donor.largestDiameter(), Math.nextDown(speed)));
            assertEquals(SolidMobility.Reason.MOBILE, LineSizing.mobility(just, size).reason());
            assertTrue(LineSizing.rate(just, size).reading(Limit.LINE_SOLIDS).met());
            return null;
        });
    }

    @Test void aParticleAsWideAsTheBoreIsRedUntilTheBoreClearsIt() {
        on(() -> {
            var line = line(slurry(at(320, 3e5, pure(WATER)), .2), Service.WATER);
            var donor = line.solids().donor();
            var coarse = solids(line, new SolidMobility.Donor(false, false, true, donor.liquidVolume(), donor.solidVolume(), .1, 0));
            var size = new LineSizing.Size(.1, 1);
            assertEquals(SolidMobility.Reason.PARTICLE_TOO_LARGE, LineSizing.mobility(coarse, size).reason(), "on the bore itself it is refused");
            assertEquals(Lamp.RED, LineSizing.rate(coarse, size).lamp());
            assertTrue(LineSizing.auto(coarse).size().bore() > .1, "Auto goes past it");
            return null;
        });
    }

    @Test void aRefusalNoSizeFixesStaysRedAndAutoStillAnswers() {
        on(() -> {
            var line = line(slurry(at(320, 3e5, pure(WATER)), .2), Service.WATER);
            var donor = line.solids().donor();
            var packed = solids(line, new SolidMobility.Donor(false, false, true, donor.liquidVolume(), donor.liquidVolume(), donor.largestDiameter(), 0));
            var stuck = solids(line, new SolidMobility.Donor(false, true, false, donor.liquidVolume(), 0, 0, 0));
            var size = new LineSizing.Size(.3, 1);
            assertEquals(SolidMobility.Reason.PACKED_SLURRY, LineSizing.mobility(packed, size).reason());
            assertEquals(SolidMobility.Reason.IMMOBILE_LIQUID, LineSizing.mobility(stuck, size).reason());
            for (var refused : List.of(packed, stuck)) {
                var choice = LineSizing.auto(refused);
                assertEquals(Lamp.RED, choice.rating().lamp());
                assertSame(Limit.LINE_SOLIDS, choice.rating().closest().limit());
                assertEquals(LineSizing.auto(line(at(320, 3e5, pure(WATER)), Service.WATER)).size().bore() > 0, choice.size().bore() > 0, "it still answers");
            }
            return null;
        });
    }

    @Test void aLineReadsTheWorldsSpeedLawAtItsState() {
        on(() -> {
            var gas = at(320, 3e5, pure(NITROGEN));
            assertEquals(MODEL.velocityLimit(gas.state()), line(gas, Service.GAS).speedLimit(), 0);
            var wet = crudeAt(5, 1.5e5);
            double limit = MODEL.velocityLimit(wet.state());
            assertTrue(limit < 100, "a dense two-phase line at low pressure: " + limit);
            var line = line(wet, Service.TWO_PHASE);
            assertEquals(limit, line.speedLimit(), 0);
            double bore = Math.sqrt(4 * line.volume() / (1.01 * limit) / Math.PI);
            var over = LineSizing.rate(line, new LineSizing.Size(bore, 1)).reading(Limit.LINE_SPEED);
            assertEquals(limit, over.high(), 0);
            assertEquals(Lamp.RED, over.lamp(), "a Fixed size is checked against what the world does");
            return null;
        });
    }

    @Test void aDryGasLeavesItsSolidsBehindAsTheWorldsBulkEndDoes() {
        on(() -> {
            var gas = at(320, 3e5, pure(NITROGEN));
            var dusty = slurry(gas, 50);
            assertEquals(Service.GAS, service(dusty, OPEN));
            var scratch = new MixtureViscosity.Workspace();
            var clean = LineSizing.Line.of(gas, Service.GAS, DesignBasis.DEFAULT, THERMO, PipeSizes.MAX_BORE, null, scratch);
            assertEquals(clean, LineSizing.Line.of(dusty, Service.GAS, DesignBasis.DEFAULT, THERMO, PipeSizes.MAX_BORE, null, scratch));
            return null;
        });
    }
}
