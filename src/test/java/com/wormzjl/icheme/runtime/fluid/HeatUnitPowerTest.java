package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fan units without power (batch 2026-11-03-rotating-machinery W5, build plan section 7.4): a cooler's and a tower's
 * fans stop through a slice input ({@link ShaftPlan.Kind#FANS}), the louvers give natural draught, a regulating cooler
 * stands at its highest (LIMITED), the stack draught alone works a tower, and the player's settings are kept.
 */
class HeatUnitPowerTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB = new PipeResistance.Geometry(1, .05, .000045, 0);

    private static PhysicalFluidTopology.Position at(int x, int y, int z) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, z);
    }

    private static PhysicalFluidTopology.Device plain(long id, int x, int y, int z, Kind kind) {
        return new PhysicalFluidTopology.Device(id, at(x, y, z), kind, EAST, STUB, new FlowControl.Passive());
    }

    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell, PhysicalFluidTopology.Direction face, PhysicalFluidTopology.ConnectionRole role) {
        return new PhysicalFluidTopology.Port(cell, face, PassiveNetwork.PhasePort.BULK, role);
    }

    private FluidThermodynamics.State water(double temperature, double pressure) {
        double[] n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        n[n.length - 1] /= model.flashTP(temperature, pressure, n, () -> {
        }).volume();
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    /** One 3 × 6 m bay under {@code control}, fed water at 360 K from a 1.3 bar generator into a 1 atm void, and its side. */
    private PassiveNetwork bay(HeatUnit unit) {
        var shape = com.wormzjl.icheme.science.equipment.EquipmentShape.unit(com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.AIR_COOLER, 3, 6,
                com.wormzjl.icheme.science.equipment.EquipmentShape.Heading.SOUTH, 4);
        var definition = HeatSides.of(shape).getFirst();
        var ports = List.of(port(at(10, 0, 10), WEST, PhysicalFluidTopology.ConnectionRole.INLET), port(at(0, 0, 0), EAST, PhysicalFluidTopology.ConnectionRole.OUTLET));
        var side = new PhysicalFluidTopology.Device(10, ports.getFirst().cell(), Kind.HEAT_SIDE, NORTH, definition.passage(), new FlowControl.Passive(), ports, true,
                PhysicalFluidTopology.Cutoff.NONE, false, PhysicalFluidTopology.ALL_FACES, 0, 0, unit.withArea(definition.area()).withFans(HeatSides.fans(shape)));
        var devices = List.of(plain(1, 8, 0, 10, Kind.GENERATOR), plain(20, 9, 0, 10, Kind.PIPE), side, plain(30, 1, 0, 0, Kind.PIPE), plain(2, 2, 0, 0, Kind.VOID));
        return PhysicalFluidTopology.compile(devices, Map.of(1L, new PassiveNetwork.Reservoir(1, 0, water(360, 130_000), PassiveNetwork.NodeKind.GENERATOR),
                2L, new PassiveNetwork.Reservoir(2, 0, water(300, 101_325), PassiveNetwork.NodeKind.VOID))).islands().getFirst().graph();
    }

    private static AirCooled cooler(PassiveNetwork graph) {
        for (var p : graph.pipes()) if (p.id() == PhysicalFluidTopology.sideIdentity(10)) return (AirCooled) p.heat();
        throw new AssertionError("no side");
    }

    /** The bay's outlet after a minute of {@code graph}, and its side as solved. */
    private record Run(double outlet, AirCooled side) {
    }

    private Run run(PassiveNetwork graph) {
        var result = new PassiveIntervalSolver(model).solve(graph, 60, PassiveIntervalSolver.Settings.defaults(), () -> {
        });
        var end = result.graph();
        for (var p : end.pipes())
            if (p.id() == PhysicalFluidTopology.sideIdentity(10)) return new Run(end.reservoirs().get(p.second()).state().temperature(), (AirCooled) p.heat());
        throw new AssertionError("no side");
    }

    @Test
    void anUnpoweredCoolerRunsAtNaturalDraughtAndKeepsItsFanSetting() {
        var unit = new HeatUnit(HeatSides.Unit.AIR_COOLER, HeatSides.Side.PROCESS, 1, 0);
        assertEquals(1, unit.control().share(), "a new cooler's fans run at full speed");
        var graph = bay(unit);
        var powered = run(ShaftPlan.apply(graph, Map.of(10L, new ShaftPlan(ShaftPlan.Kind.FANS, 1))));
        var unpowered = run(ShaftPlan.apply(graph, Map.of(10L, new ShaftPlan(ShaftPlan.Kind.FANS, 0))));
        var natural = run(bay(unit.withControl(HeatUnit.Control.OFF)));
        assertEquals(1, powered.side().fans(), 1e-12);
        assertEquals(0, unpowered.side().fans(), 1e-12, "no power, no fans");
        assertEquals(HeatSides.AIR.natural(), unpowered.side().airShare(), 1e-12, "the louvers stay open: natural draught");
        assertEquals(1, unpowered.side().draught(), 1e-12, "the player's fan setting is kept");
        assertEquals(natural.outlet(), unpowered.outlet(), 1e-6, "as a cooler whose fans stand still");
        assertTrue(unpowered.outlet() > powered.outlet() + 1, "it cools less: " + powered.outlet() + " -> " + unpowered.outlet());
        assertEquals(0, ElectricStores.fanDraw(unit, 0), "still fans draw nothing");
    }

    @Test
    void anUnpoweredRegulatingCoolerStandsAtItsHighestLimited() {
        // A 30 °C target no natural draught reaches.
        var unit = new HeatUnit(HeatSides.Unit.AIR_COOLER, HeatSides.Side.PROCESS, 1, 0).withControl(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 303.15, 0, .6));
        var graph = ShaftPlan.apply(bay(unit), Map.of(10L, new ShaftPlan(ShaftPlan.Kind.FANS, 0)));
        var run = run(graph);
        assertEquals(0, run.side().fans(), 1e-12);
        assertEquals(HeatControl.Limit.HIGHEST, run.side().limit(), "short of its target");
        assertTrue(FluidWorldAuthority.controlStatus(run.side(), unit).startsWith("LIMITED: NO POWER"), FluidWorldAuthority.controlStatus(run.side(), unit));
        assertEquals(.6, unit.control().share(), "its manual share and target stay");
        assertEquals(1, cooler(bay(unit)).fanLimit(), "a compiled side carries no limit: only the dispatch does");
    }

    @Test
    void anUnpoweredTowerRunsOnItsStackDraughtAlone() {
        var tower = CoolingTower.induced(8, 2, 1, 1);
        var amounts = new double[model.componentCount()];
        amounts[amounts.length - 1] = 1;
        double scale = 128 / model.flashTP(308.15, 101325, amounts, () -> {
        }).volume();
        amounts[amounts.length - 1] *= scale;
        var state = model.flashTP(308.15, 101325, amounts, () -> {
        });
        for (var mode : CoolingTower.Mode.values()) {
            var set = tower.withControl(mode, 1, 298.15);
            var inventory = new PassiveNetwork.Inventory(set.basinVolume(), PhaseLayout.totalAmounts(state), state.internalEnergy(), state.solids(), CoolingTower.BASIN_DEPTH, true).withTower(set);
            var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, state, PassiveNetwork.NodeKind.RESERVOIR, inventory)), List.of());
            var cut = ShaftPlan.apply(graph, Map.of(1L, new ShaftPlan(ShaftPlan.Kind.FANS, 0)));
            assertEquals(0, cut.reservoirs().getFirst().inventory().tower().fanLimit());
            assertEquals(set, cut.reservoirs().getFirst().inventory().tower().facts(), "its mode and set speed are kept");
            var result = new PassiveIntervalSolver(model).solve(cut, 1, PassiveIntervalSolver.Settings.defaults(), () -> {
            });
            var rates = result.towers().get(1L);
            assertEquals(0, rates.fill().speed(), mode + ": the fans stand");
            assertEquals(0, rates.fill().fanPowerWatt(), mode + ": and draw nothing");
            assertEquals(0, ElectricStores.towerDraw(set, rates.fill().speed()));
        }
        assertTrue(ElectricStores.towerDraw(tower, 1) > tower.fanPower(1), "full fans draw their power over the fan motors");
    }
}
