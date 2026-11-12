package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.ConverterUnit;
import com.wormzjl.icheme.runtime.fluid.Converters;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRules;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The fluid converter in the world (batch 2026-11-12-fluid-conversion, W3). A liquid converter placed from its structure
 * registers its open vessel, its two hidden ports and its facts; on a fixture water rule a test handler fills 1000 mB of
 * water through its Forge port (only that cell's port face answers), the converter turns it into the vessel's water
 * (TO_ICHEME), then drains the vessel back (TO_FORGE) to its 1 % heel, and the handler drains what the rule produced.
 * Expected: the Forge litres in equal the litres booked plus what waits in the account; the vessel's water rises by the mass
 * booked in and falls by the mass booked out (an open vessel loses only the vapour its rising liquid vents, under 1e-3);
 * the litres out equal the mB drained plus the remainder. The converter takes the level gauge. A gas converter registers
 * closed and takes the gas blanketing valve and the pressure safety valve. Each test removes what it placed.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ConverterGameTests {
    private ConverterGameTests() {}

    /** The fixture rule (src/test/resources/fluid_conversion/fixture/water.json): water both ways at ambient. */
    static final String WATER = """
            {"revision": "1", "fluid": "minecraft:water", "phase": "liquid",
             "to_icheme": {"composition": {"Water": 1.0}, "temperature": "ambient", "pressure": "ambient"},
             "to_forge": {"components": {"Water": {"min": 0.99, "max": 1.0}}, "temperature": {"min": 273.15, "max": 373.15}}}""";
    static final int FILL = 1000;

    @GameTest(template = "empty", timeoutTicks = 16000, batch = "converter-liquid")
    public static void aLiquidConverterConvertsBothWaysThroughItsForgePort(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 4, 8)), new BlockPos(-8, -3, -8), new BlockPos(24, 30, 16));
        var level = plant.level;
        var world = plant.world;
        var before = ConversionRuntime.active();
        long[] id = {0};
        // Vessel water (kg) after the forward run; the converter's numbers at its end.
        double[] water = {-1, -1, -1};
        long[] seen = {-1};
        String[] failure = {null};
        Runnable cleanup = () -> {
            ConversionRuntime.publish(before);
            plant.remove();
            plant.release();
        };
        try {
            plant.hold();
            var catalog = MaterialRuntime.current();
            var rule = ConversionRules.parse("icheme_fluid_test:water", com.google.gson.JsonParser.parseString(WATER).getAsJsonObject(),
                    ConversionRules.names(catalog, FluidMaterialCatalog.networkPackage(catalog)));
            ConversionRuntime.publish(new ConversionRules(List.of(rule), List.of()));
            var converter = plant.place(shape(EquipmentShape.Kind.FLUID_CONVERTER, 2, 2), 0, 0, 0);
            var node = node(converter);
            var port = ShapeRasterizer.forgePort(converter.shape());
            if (port == null) throw new IllegalStateException("The converter has no Forge port");
            var portCell = converter.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = Direction.fromDelta(port.dx(), port.dy(), port.dz());
            helper.startSequence()
                    .thenWaitUntil(() -> {
                        var record = world.at(plant.position(node)).orElse(null);
                        helper.assertTrue(record != null && world.converters().unit(record.device().id()) != null && world.view(record.device().id()).tank() != null, "waiting for the converter");
                    })
                    .thenExecute(guarded(cleanup, () -> {
                        id[0] = world.at(plant.position(node)).orElseThrow().device().id();
                        var u = world.converters().unit(id[0]);
                        var spec = world.registrations().get(id[0]).spec();
                        check(spec.open() && !u.closed(), "the liquid converter registers open");
                        check(u.capacity() == Math.round(converter.shape().volume() * 1000) && u.maxRate() == converter.shape().converterRate(), "its Forge tank holds its vessel's volume, its rate starts at the rated flow");
                        check(world.registrations().containsKey(u.drain()) && world.registrations().containsKey(u.fill()), "its hidden ports register");
                        // The Forge port: only its cell, only its face.
                        check(level.getCapability(Capabilities.FluidHandler.BLOCK, portCell, face.getOpposite()) == null, "the port's back face answers nothing");
                        check(level.getCapability(Capabilities.FluidHandler.BLOCK, node, Direction.UP) == null, "another cell answers nothing");
                        var handler = handler(level, portCell, face);
                        check(!handler.isFluidValid(0, new FluidStack(Fluids.LAVA, 1000)) && handler.fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE) == 0, "a fluid no rule names is refused");
                        check(handler.fill(new FluidStack(Fluids.WATER, FILL), IFluidHandler.FluidAction.EXECUTE) == FILL, "the port takes 1000 mB of water");
                        // The gauge's place (plan D11).
                        var host = InstrumentHosts.at(level, converter.controller());
                        check(host != null && InstrumentHosts.LevelGauge.of(host) != null, "the liquid converter takes the level gauge");
                        world.converters().settings(id[0], ConverterUnit.Mode.TO_ICHEME, u.maxRate(), u.deadband());
                    }))
                    .thenWaitUntil(() -> {
                        var u = world.converters().unit(id[0]);
                        var t = world.converters().totals(id[0]);
                        var view = world.view(id[0]);
                        // Done: the Forge tank and its account empty, and the vessel shows it on two publications.
                        boolean done = u.stored() == 0 && u.account() < 1 && t.litresIn() > FILL - 1;
                        if (done && view.committedTick() != seen[0]) {seen[0] = view.committedTick();water[1] = water[0];water[0] = water(plant, view);}
                        helper.assertTrue(done && water[1] >= 0 && Math.abs(water[0] - water[1]) < 1e-9 * water[0], "waiting for the forward conversion: " + u + " " + t);
                    })
                    .thenExecute(() -> {
                        var u = world.converters().unit(id[0]);
                        var t = world.converters().totals(id[0]);
                        water[2] = water[0];
                        IChemE.LOGGER.info("converter_gametest forward litres_in={} account={} mass_in={} kg vessel_water={} kg status={}", t.litresIn(), u.account(), t.massIn(), water[0], world.converters().status(id[0]));
                        if (Math.abs(t.litresIn() + u.account() - FILL) > 1e-6) failure[0] = "forward: " + t.litresIn() + " L booked and " + u.account() + " L in the account of " + FILL + " mB";
                        else if (Math.abs(water[0] - t.massIn()) > 1e-3 * t.massIn()) failure[0] = "forward: the vessel holds " + water[0] + " kg of water, the converter booked " + t.massIn();
                        world.converters().settings(id[0], ConverterUnit.Mode.TO_FORGE, u.maxRate(), u.deadband());
                        seen[0] = -1;water[0] = water[1] = -1;
                    })
                    .thenWaitUntil(() -> {
                        var t = world.converters().totals(id[0]);
                        var status = world.converters().status(id[0]);
                        var view = world.view(id[0]);
                        boolean done = t.litresOut() > 500 && status != Converters.Status.CONVERTING;
                        if (done && view.committedTick() != seen[0]) {seen[0] = view.committedTick();water[1] = water[0];water[0] = water(plant, view);}
                        helper.assertTrue(done && water[1] >= 0 && Math.abs(water[0] - water[1]) < 1e-9 * water[0], "waiting for the reverse conversion: " + world.converters().unit(id[0]) + " " + t + " " + status);
                    })
                    .thenExecute(() -> {
                        var u = world.converters().unit(id[0]);
                        var t = world.converters().totals(id[0]);
                        long stored = u.stored();
                        var drained = handler(level, portCell, face).drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
                        var after = world.converters().unit(id[0]);
                        double fell = water[2] - water[0];
                        IChemE.LOGGER.info("converter_gametest reverse litres_out={} mass_out={} kg drained={} mB remainder={} vessel_water={} kg fell={} kg status={}",
                                t.litresOut(), t.massOut(), drained.getAmount(), u.remainder(), water[0], fell, world.converters().status(id[0]));
                        if (failure[0] == null && (drained.getFluid() != Fluids.WATER || drained.getAmount() != stored || after.stored() != 0)) failure[0] = "reverse: drained " + drained.getAmount() + " mB of " + stored;
                        else if (failure[0] == null && Math.abs(t.litresOut() - stored - u.remainder()) > 1e-6) failure[0] = "reverse: " + t.litresOut() + " L booked, " + stored + " mB and " + u.remainder() + " L remainder";
                        else if (failure[0] == null && Math.abs(fell - t.massOut()) > 1e-3 * t.massOut()) failure[0] = "reverse: the vessel's water fell " + fell + " kg, the converter booked " + t.massOut();
                        cleanup.run();
                    })
                    .thenWaitUntil(() -> helper.assertTrue(plant.removed() && world.converters().unit(id[0]) == null, "waiting for the plant's removal"))
                    .thenExecute(() -> {if (failure[0] != null) throw new GameTestAssertException(failure[0]);})
                    .thenSucceed();
        } catch (RuntimeException e) {
            IChemE.LOGGER.error("converter_gametest setup failed", e);
            cleanup.run();
            throw new GameTestAssertException("converter setup: " + e.getMessage());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 16000, batch = "converter-gas")
    public static void aGasConverterRegistersClosedAndTakesItsValves(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 4, 8)), new BlockPos(-8, -3, -8), new BlockPos(24, 30, 16));
        var level = plant.level;
        var world = plant.world;
        long[] id = {0};
        String[] failure = {null};
        Runnable cleanup = () -> {
            plant.remove();
            plant.release();
        };
        try {
            plant.hold();
            var converter = plant.place(shape(EquipmentShape.Kind.GAS_CONVERTER, 3, 2), 0, 0, 0);
            var node = node(converter);
            var relief = InstrumentHosts.ReliefValve.of(level, converter.origin(), converter.shape());
            var blanket = InstrumentHosts.BlanketValve.of(level, converter.origin(), converter.shape());
            if (relief == null || blanket == null) throw new IllegalStateException("The gas converter has no place for its valves: relief " + relief + ", blanket " + blanket);
            helper.startSequence()
                    .thenWaitUntil(() -> {
                        var record = world.at(plant.position(node)).orElse(null);
                        helper.assertTrue(record != null && world.converters().unit(record.device().id()) != null && world.view(record.device().id()).tank() != null, "waiting for the converter");
                    })
                    .thenExecute(guarded(cleanup, () -> {
                        id[0] = world.at(plant.position(node)).orElseThrow().device().id();
                        var u = world.converters().unit(id[0]);
                        check(!world.registrations().get(id[0]).spec().open() && u.closed(), "the gas converter registers closed");
                        var host = InstrumentHosts.at(level, converter.controller());
                        check(host == null || InstrumentHosts.LevelGauge.of(host) == null, "the gas converter takes no level gauge");
                        check(world.blanketValve(id[0], blanket.port()), "the gas blanketing valve fits");
                        check(world.reliefValve(id[0], relief.port()), "the pressure safety valve fits");
                    }))
                    .thenWaitUntil(() -> {
                        var spec = world.registrations().get(id[0]).spec();
                        helper.assertTrue(spec.blanket() > 0 && spec.relief() > 0, "waiting for the valves");
                    })
                    .thenExecute(() -> {
                        var spec = world.registrations().get(id[0]).spec();
                        IChemE.LOGGER.info("converter_gametest gas blanket={} Pa relief={} Pa relief_port={} blanket_port={} status={}", spec.blanket(), spec.relief(), relief.port(), blanket.port(), world.view(id[0]).status());
                        world.reliefValve(id[0], null);
                        world.blanketValve(id[0], null);
                    })
                    .thenWaitUntil(() -> {
                        var spec = world.registrations().get(id[0]).spec();
                        helper.assertTrue(spec.blanket() == 0 && spec.relief() == 0, "waiting for the valves' removal");
                    })
                    .thenExecute(cleanup)
                    .thenWaitUntil(() -> helper.assertTrue(plant.removed() && world.converters().unit(id[0]) == null, "waiting for the plant's removal: removed=" + plant.removed()
                            + " status=" + world.view(id[0]).status() + " registered=" + world.registrations().containsKey(id[0]) + " devices=" + world.converterDevices(id[0]) + " unit=" + world.converters().unit(id[0]) + " left=" + world.registrations().values().stream().filter(r -> plant.inside(new BlockPos(r.device().position().x(), r.device().position().y(), r.device().position().z()))).map(r -> r.device().id() + ":" + r.device().kind() + "@" + r.device().position()).toList()))
                    .thenExecute(() -> {if (failure[0] != null) throw new GameTestAssertException(failure[0]);})
                    .thenSucceed();
        } catch (RuntimeException e) {
            IChemE.LOGGER.error("converter_gametest gas setup failed", e);
            cleanup.run();
            throw new GameTestAssertException("gas converter setup: " + e.getMessage());
        }
    }

    /** A converter of {@code kind}, {@code diameter} across and {@code shell} high, as the constructor builds one. */
    static EquipmentShape shape(EquipmentShape.Kind kind, double diameter, int shell) {
        var d = com.wormzjl.icheme.science.equipment.UnitDesign.of(kind);
        return EquipmentShape.designed(kind, diameter, shell, EquipmentShape.Heading.NORTH, d.defaultSupport(diameter, shell), com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE);
    }

    static BlockPos node(EquipmentStructure.Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());
        return vessel.origin().offset(cell.x(), cell.y(), cell.z());
    }

    static IFluidHandler handler(net.minecraft.server.level.ServerLevel level, BlockPos cell, Direction face) {
        var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, cell, face);
        if (handler == null) throw new GameTestAssertException("The converter's Forge port answers nothing");
        return handler;
    }

    /** The water in a vessel's published view, kg, every phase. */
    static double water(AssemblyGameTests.Plant plant, com.wormzjl.icheme.runtime.fluid.FluidView view) {
        var model = plant.world.model();int w = model.components().indexOf("Water");
        if (view.state() == null || w < 0) return 0;
        double moles = 0;
        for (var phase : view.state().phaseMoles()) if (phase.length != model.components().size()) throw new IllegalStateException("A view on another list");
            else moles += phase[w];
        return moles * model.molecularWeights()[w];
    }

    /** {@code body}, which on a failure removes the plant ({@code cleanup}) before the test fails. */
    private static Runnable guarded(Runnable cleanup, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (RuntimeException e) {
                cleanup.run();
                throw e;
            }
        };
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new GameTestAssertException("Expected: " + what);
    }
}
