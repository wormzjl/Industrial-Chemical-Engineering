package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A fired heater's burners (batch 2026-11-09-furnace-fuel-power, FURNACE_FUEL_POWER_PLAN.md W2-W4): its radiant coil heats
 * water in one island and its fuel valve feeds methane into the firebox in another. Unpowered, the burners stand: no fuel,
 * no heat, NO POWER. Powered, the valve asks the fuel the coil's demand needs, and the coil fires what the fuel gives: all of
 * it on a stiff supply, a reduced duty with LOW FUEL on a weak one, none with NO FUEL when no fuel line ever booked, and
 * nitrogen gives FUEL NOT COMBUSTIBLE.
 */
class FiredHeaterFuelTest {
    private final MaterialCatalog catalog = MaterialCatalog.bundled();
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB = new PipeResistance.Geometry(1, .05, .000045, 0);
    private static final long RADIANT = 30, CONVECTION = 31, BURNERS = 40, FUEL = 5;

    private static PhysicalFluidTopology.Position at(int x, int y, int z) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, z);
    }

    private static PhysicalFluidTopology.Device plain(long id, int x, int z, Kind kind) {
        return PhysicalFluidTopology.Device.builder(id, at(x, 0, z), kind, EAST, STUB, new FlowControl.Passive()).build();
    }

    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell, PhysicalFluidTopology.Direction face, PhysicalFluidTopology.ConnectionRole role) {
        return new PhysicalFluidTopology.Port(cell, face, PassiveNetwork.PhasePort.BULK, role);
    }

    private static HeatUnit coil(HeatSides.Side side, long partner) {
        return new HeatUnit(HeatSides.Unit.FIRED_HEATER, side, 100, partner).withControl(new HeatUnit.Control(HeatUnit.Mode.FIRING, 443.15, 0, 1));
    }

    private static PhysicalFluidTopology.Device side(long id, int z, HeatUnit heat) {
        var ports = List.of(port(at(2, 0, z), WEST, PhysicalFluidTopology.ConnectionRole.INLET), port(at(4, 0, z), EAST, PhysicalFluidTopology.ConnectionRole.OUTLET));
        return PhysicalFluidTopology.Device.builder(id, at(2, 0, z), Kind.HEAT_SIDE, NORTH, HeatSides.heaterPassage(100, 3), new FlowControl.Passive()).ports(ports).heat(heat).build();
    }

    /** The burners at the firebox cell (3, 0, 20), fed from the west, as {@code FluidWorldAuthority.burnerRecord} places them. */
    private static PhysicalFluidTopology.Device burners() {
        return PhysicalFluidTopology.Device.builder(BURNERS, at(3, 0, 20), Kind.VOID, NORTH, STUB, FlowControl.ControlValve.burner())
                .ports(List.of(port(at(3, 0, 20), WEST, PhysicalFluidTopology.ConnectionRole.VESSEL))).heat(new HeatUnit(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.BURNER, 100, RADIANT)).build();
    }

    private List<PhysicalFluidTopology.Device> devices() {
        return List.of(plain(1, 0, 0, Kind.GENERATOR), plain(130, 1, 0, Kind.PIPE), side(RADIANT, 0, coil(HeatSides.Side.RADIANT, CONVECTION)), plain(230, 5, 0, Kind.PIPE), plain(2, 6, 0, Kind.VOID),
                plain(3, 0, 10, Kind.GENERATOR), plain(131, 1, 10, Kind.PIPE), side(CONVECTION, 10, coil(HeatSides.Side.CONVECTION, RADIANT)), plain(231, 5, 10, Kind.PIPE), plain(4, 6, 10, Kind.VOID),
                plain(FUEL, 0, 20, Kind.GENERATOR), plain(140, 1, 20, Kind.PIPE), plain(141, 2, 20, Kind.PIPE), burners());
    }

    private FluidThermodynamics.State pure(String component, double temperature, double pressure) {
        int c = model.components().indexOf(component);
        assertTrue(c >= 0, component);
        double[] n = new double[model.componentCount()];
        n[c] = 1;
        n[c] /= model.flashTP(temperature, pressure, n, () -> {
        }).volume();
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    private PassiveNetwork.Reservoir reservoir(long id, FluidThermodynamics.State state, PassiveNetwork.NodeKind kind) {
        return new PassiveNetwork.Reservoir(id, 0, state, kind);
    }

    /** The heater's two islands with its fuel generator at {@code fuel}. */
    private record Islands(PassiveNetwork radiant, PassiveNetwork fuel) {
    }

    private Islands compile(FluidThermodynamics.State fuel) {
        return compile(fuel, 323.15, 900_000);
    }

    /** The heater's islands with the radiant coil's water at {@code water} K, drawn off to a void at {@code drain} Pa (1 MPa: still). */
    private Islands compile(FluidThermodynamics.State fuel, double water, double drain) {
        var compiled = PhysicalFluidTopology.compile(devices(), Map.of(1L, reservoir(1, pure("Water", water, 1_000_000), PassiveNetwork.NodeKind.GENERATOR),
                2L, reservoir(2, pure("Water", Math.min(water, 300), drain), PassiveNetwork.NodeKind.VOID), 3L, reservoir(3, pure("Water", 323.15, 1_000_000), PassiveNetwork.NodeKind.GENERATOR),
                4L, reservoir(4, pure("Water", 300, 900_000), PassiveNetwork.NodeKind.VOID), FUEL, reservoir(FUEL, fuel, PassiveNetwork.NodeKind.GENERATOR),
                BURNERS, reservoir(BURNERS, pure("Nitrogen", Burners.AIR, Burners.PRESSURE), PassiveNetwork.NodeKind.VOID)));
        PassiveNetwork radiant = null, burner = null;
        for (var island : compiled.islands())
            for (var pipe : island.graph().pipes()) {
                if (pipe.heat() instanceof FiredCoil) radiant = island.graph();
                if (pipe.control() instanceof FlowControl.ControlValve v && v.source() == FlowControl.ValveSource.BURNER) {
                    burner = island.graph();
                    assertEquals(BURNERS, island.graph().reservoirs().get(pipe.second()).id(), "the fuel valve is stated toward the firebox");
                }
            }
        assertNotNull(radiant, "the radiant coil's island");
        assertNotNull(burner, "the burners' island");
        assertNotSame(radiant, burner, "fuel and coil never share a fluid");
        return new Islands(radiant, burner);
    }

    /** The coordinator only takes the heaters' wakes here: no island is registered, nothing is dispatched. */
    private static IslandCoordinator coordinator(long[] epoch) {
        return new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers() {
                return 1;
            }

            public long nextRequestId() {
                return 1;
            }

            public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveJobs.FluidIslandCommand command) {
                throw new AssertionError("nothing is dispatched");
            }

            public void cancel(long request) {
                throw new AssertionError("no deadlines here");
            }
        }, changed -> {
        }, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.defaults()), () -> epoch[0]);
    }

    /** The heater's runtime: its ledger (the coils and burners), electric stores and burners, and the islands' last graphs. */
    private final class Rig {
        final long[] epoch;
        final IslandCoordinator coordinator;
        final WorldTopologyLedger ledger;
        final ElectricStores stores;
        final FiredHeaters heaters;
        PassiveNetwork radiant, fuel;
        PassiveIntervalSolver.Result radiantResult, fuelResult;
        /** One solver per island, as the engine keeps: a step plans the coil from the flows the last one carried. */
        final PassiveIntervalSolver radiantSolver = new PassiveIntervalSolver(model), fuelSolver = new PassiveIntervalSolver(model);
        /** A fresh solver for every slice: each slice's first step starts with no flows known. */
        boolean fresh;

        Rig(Islands islands) {
            this(islands, null);
        }

        Rig(Islands islands, CertifyingRig runtime) {
            epoch = runtime == null ? new long[]{0} : runtime.epoch;
            coordinator = runtime == null ? coordinator(epoch) : runtime.coordinator;
            var n2 = FluidDeviceSpec.nitrogen(catalog).composition();
            var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
            for (var d : devices())
                if (d.id() == RADIANT || d.id() == CONVECTION || d.id() == BURNERS)
                    active.put(d.id(), new WorldTopologyLedger.Registration(d, new FluidDeviceSpec(1, 300, 101325, n2), 0));
            var empty = WorldTopologyLedger.Snapshot.empty(catalog);
            ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
            stores = new ElectricStores(ledger, device -> null, coordinator, () -> ElectricStores.Settings.DEFAULT);
            heaters = new FiredHeaters(ledger, coordinator, stores, FluidWorldAuthority.heatingValues(catalog, model), model.molecularWeights(), FluidWorldAuthority.formulas(catalog, model));
            radiant = islands.radiant();
            fuel = islands.fuel();
        }

        /** Fills the burners' store and lets the cadence restart them. */
        void power() {
            for (int i = 0; i < 400 && !stores.powered(BURNERS); i++) {
                epoch[0]++;
                stores.receive(BURNERS, Integer.MAX_VALUE, false);
                if (epoch[0] % ElectricStores.CADENCE == 0) stores.cadence();
            }
            assertTrue(stores.powered(BURNERS), "a filled store restarts the burners");
        }

        private PassiveIntervalSolver.Result slice(PassiveIntervalSolver solver, PassiveNetwork graph, long island, long start, long end) {
            var plans = new HashMap<Long, ShaftPlan>();
            heaters.plans(graph, plans);
            var result = (fresh ? new PassiveIntervalSolver(model) : solver).solve(ShaftPlan.apply(graph, plans), coordinator.time().seconds(end - start), PassiveIntervalSolver.Settings.defaults(), () -> {
            });
            heaters.booked(island, start, end, result);
            return result;
        }

        /** {@code rounds} 20 s slices of the radiant island, each followed by one of the fuel island unless {@code withFuel} is false. */
        void run(int rounds, boolean withFuel) {
            for (int i = 0; i < rounds; i++) {
                long start = epoch[0];
                long end = start + 400;
                radiantResult = slice(radiantSolver, radiant, 1, start, end);
                radiant = radiantResult.graph();
                if (withFuel) {
                    fuelResult = slice(fuelSolver, fuel, 2, start, end);
                    fuel = fuelResult.graph();
                }
                epoch[0] += 400;
            }
        }

        /** The radiant coil's heat over the last slice, W. */
        double heat() {
            double joule = 0;
            for (var b : radiantResult.boundaries())
                if (b.kind() == ConservativeTransport.BoundaryTransfer.Kind.HEAT && b.nodeId() == PhysicalFluidTopology.sideIdentity(RADIANT)) joule += b.totalEnergyJoule();
            return joule / radiantResult.advancedSeconds();
        }
    }

    @Test
    void unpoweredBurnersTakeNoFuelAndFireNothing() {
        var rig = new Rig(compile(pure("Methane", 300, 500_000)));
        assertFalse(rig.stores.powered(BURNERS), "a new heater waits for power");
        rig.run(3, true);
        assertEquals(List.of(EquipmentAlarm.NO_POWER), rig.heaters.alarms(BURNERS));
        assertEquals(0, rig.heat(), 1, "no fans, no flame: the coil takes no heat");
        assertEquals(0, rig.heaters.view(BURNERS).fuelFlow(), 1e-9, "the fuel valve stays shut");
        assertEquals(Map.of(BURNERS, List.of(EquipmentAlarm.NO_POWER)), rig.heaters.alarms());
    }

    @Test
    void poweredBurnersFireTheCoilsDemandOnAStiffSupply() {
        var rig = new Rig(compile(pure("Methane", 300, 500_000)));
        rig.power();
        rig.run(12, true);
        var view = rig.heaters.view(BURNERS);
        System.out.printf(Locale.ROOT, "stiff supply: demand %.0f kW, asked %.0f kW, fired %.0f kW, fuel %.4f kg/s at %.2f MJ/kg, fans %.1f kW, coil %.0f kW%n",
                view.demand() / 1e3, view.asked() / 1e3, view.fired() / 1e3, view.fuelFlow(), view.heatingValue() / 1e6, view.fanDraw() / 1e3, rig.heat() / 1e3);
        assertEquals(List.of(), view.alarms(), "no alarm");
        assertEquals(5.0e7, view.heatingValue(), 2e5, "methane's lower heating value, from its material record");
        assertTrue(view.demand() > 0, "the cold water asks for heat");
        assertEquals(view.asked() * Burners.MARGIN, view.fired(), .02 * view.asked(), "the valve delivers what was asked, with the margin");
        assertEquals(view.demand(), rig.heat(), .02 * view.demand() + 1e3, "the coil fires its demand");
        assertEquals(ElectricStores.burnerDraw(rig.ledger.active().get(BURNERS), view.fired()), view.fanDraw(), 1e-9);
        assertTrue(view.fanDraw() > 5e-4 * view.fired() && view.fanDraw() < 1.5e-3 * view.fired(), "about 1 kW electric per MW fired: " + view.fanDraw());
    }

    /**
     * W6 G5 (batch 2026-11-11-aqueous-solvents): ethanol vapour fires the coil on its own record, with no code of its own. The
     * burners take its lower heating value over its molar mass (1277.5 kJ/mol, 27.7 MJ/kg); what they fire is the fuel flow times
     * that, and the coil takes its radiant share of it (the heater's efficiency, {@link Burners#radiant}).
     */
    @Test
    void ethanolVapourFiresTheCoilAtItsHeatingValue() {
        var rig = new Rig(compile(pure("Ethanol", 450, 500_000)));
        rig.power();
        rig.run(12, true);
        var view = rig.heaters.view(BURNERS);
        int e = model.components().indexOf("Ethanol");
        double perKilogram = FluidWorldAuthority.heatingValues(catalog, model)[e] / model.molecularWeights()[e];
        System.out.printf(Locale.ROOT, "ethanol: fired %.0f kW, fuel %.5f kg/s at %.3f MJ/kg, coil %.0f kW%n", view.fired() / 1e3, view.fuelFlow(), view.heatingValue() / 1e6, rig.heat() / 1e3);
        assertEquals(List.of(), view.alarms(), "no alarm");
        assertEquals(1_277_500 / .046069, perKilogram, .005 * perKilogram, "ethanol's lower heating value per kilogram");
        assertEquals(perKilogram, view.heatingValue(), 1e-9 * perKilogram, "the burners read ethanol's record");
        assertEquals(view.fuelFlow() * view.heatingValue(), view.fired(), 1e-9 * view.fired(), "fired = fuel flow times the heating value");
        assertEquals(view.demand(), rig.heat(), .02 * view.demand() + 1e3, "the coil fires its demand");
        assertEquals(Burners.radiant(view.fired()), rig.heat(), .05 * rig.heat() + 1e3, "the coil takes the radiant share of what was fired");
    }

    /** The flue for the stack's plume (batch 2026-11-10-custom-shader-system, W5): methane gas, its water, its fuel and air. */
    @Test
    void theFlueCarriesTheFuelsWaterAndItsCombustionAir() {
        var rig = new Rig(compile(pure("Methane", 300, 500_000)));
        var before = rig.heaters.flue(BURNERS);
        assertEquals(Burners.Fuel.GAS, before.fuel(), "before any fuel: gas");
        assertEquals(Burners.METHANE_WATER, before.waterShare(), "before any fuel: methane's water");
        assertEquals(0, before.flueFlow(), 1e-12, "unfired: no flue gas");
        rig.power();
        rig.run(12, true);
        var flue = rig.heaters.flue(BURNERS);
        var view = rig.heaters.view(BURNERS);
        assertEquals(Burners.Fuel.GAS, flue.fuel());
        assertEquals(.122, flue.waterShare(), .002, "methane's flue: 12 % water on the dry gas");
        assertEquals(view.fired(), flue.fired(), 1e-9);
        assertEquals(view.fuelFlow() + Burners.AIR_PER_FIRED * view.fired(), flue.flueFlow(), 1e-12, "the fuel and its air");
        assertTrue(flue.flueFlow() > 20 * flue.fuelFlow(), "about 20 kg of flue gas per kg of methane: " + flue.flueFlow() / flue.fuelFlow());
        assertNull(rig.heaters.flue(RADIANT), "the flue is the burners'");
    }

    /**
     * Still tubes (owner, 2026-11-17): a lit coil whose water stands still below its 443 K set point keeps its design duty's
     * fuel burning, so the first stream through it leaves at the set point; still water above the set point asks for none.
     * The coil takes no heat while nothing flows.
     */
    /**
     * After a load a heater knows no fuel island, and a fuel island saved at rest books nothing until it is woken (the
     * owner's test world, 2026-11-18: NO FUEL for five minutes after a reload). The radiant coil's first demand wakes every
     * island that holds the burners; once the fuel island has booked, that island is the one woken.
     */
    @Test
    void aHeaterThatKnowsNoFuelIslandWakesTheIslandsHoldingItsBurners() {
        var rig = new Rig(compile(pure("Methane", 300, 500_000), 323.15, 1_000_000));
        rig.power();
        var asked = new ArrayList<Long>();
        rig.heaters.islands(id -> {
            asked.add(id);
            return List.of(7L);
        });
        rig.run(1, false);
        assertTrue(rig.heaters.view(BURNERS).demand() > 0, "the still cold coil asks for its duty");
        assertEquals(List.of(BURNERS), asked, "its burners' islands are looked up: no fuel booking named one");
        assertEquals(0, rig.heaters.view(BURNERS).fired(), 0, "nothing fired yet");
        asked.clear();
        rig.run(2, true);
        assertTrue(rig.heaters.view(BURNERS).fired() > 0, "the woken fuel island fires");
        assertEquals(List.of(), asked, "a heater whose fuel island booked wakes that one, with no lookup");
    }

    @Test
    void cooledFeedReopensTheFuelValveAfterAnOutstandingClosedSliceCommits() {
        var methane = pure("Methane", 300, 500_000);
        var cold = compile(methane);
        var hot = compile(methane, 450, 900_000);
        var runtime = new CertifyingRig(CertificatePolicy.defaults(), model, 0).recording();
        var rig = new Rig(cold, runtime);
        rig.power();
        rig.run(2, true);
        assertTrue(rig.heaters.view(BURNERS).fired() > 0, "the initial cold feed fires the burners");

        long hotEnd = rig.epoch[0] + 400;
        rig.slice(rig.radiantSolver, hot.radiant(), 1, rig.epoch[0], hotEnd);
        rig.epoch[0] = hotEnd;
        assertEquals(0, rig.heaters.view(BURNERS).demand(), "feed above the set point closes the fuel valve");
        runtime.coordinator.shafts(new IslandCoordinator.Shafts() {
            public Map<Long, ShaftPlan> plans(long island, PassiveNetwork graph) {
                var plans = new HashMap<Long, ShaftPlan>();
                rig.heaters.plans(graph, plans);
                return plans;
            }
            public void booked(long island, PassiveNetwork graph, long start, long end, PassiveIntervalSolver.Result result) {
                rig.heaters.booked(island, start, end, result);
            }
            public void decide() {}
        });
        runtime.register(2, rig.fuel);
        runtime.run(100);
        assertEquals(0, rig.heaters.view(BURNERS).fuelFlow());
        assertTrue(runtime.stored(2).certificate().isEmpty(), "one closed slice is insufficient to certify");

        rig.epoch[0] += 100;
        runtime.coordinator.tick();
        var pending = runtime.attempts.values().iterator().next();
        assertEquals(2, pending.islandId());
        rig.slice(rig.radiantSolver, cold.radiant(), 1, hotEnd, rig.epoch[0]);
        assertTrue(rig.heaters.view(BURNERS).demand() > 0, "the next cold feed asks for heat again");
        runtime.coordinator.pump();
        runtime.drain();

        var closed = runtime.stored(2);
        assertEquals(pending.slice().endTick(), closed.clock().committedTick(), "the admitted zero-fuel slice still commits");
        assertEquals(0, rig.heaters.view(BURNERS).fuelFlow(), "the closed slice uses its original zero target");
        assertTrue(closed.certificate().isEmpty(), "the old target cannot certify the new demand");
        assertTrue(runtime.replayed.isEmpty(), "no certified replay of the closed valve");

        runtime.run(100);
        assertTrue(rig.heaters.view(BURNERS).fuelFlow() > 0, "the next admission reads the new target");
        assertTrue(rig.heaters.view(BURNERS).fired() > 0, "positive fuel delivery relights the burners");
    }

    @Test
    void stillTubesBelowTheSetPointKeepTheBurnersLit() {
        var cold = new Rig(compile(pure("Methane", 300, 500_000), 323.15, 1_000_000));
        cold.power();
        cold.run(6, true);
        var view = cold.heaters.view(BURNERS);
        System.out.printf(Locale.ROOT, "still tubes: demand %.0f kW, fired %.0f kW, coil %.0f kW%n", view.demand() / 1e3, view.fired() / 1e3, cold.heat() / 1e3);
        assertTrue(view.demand() > 0, "still cold water asks for the coil's duty");
        assertEquals(view.asked() * Burners.MARGIN, view.fired(), .02 * view.asked(), "the burners fire what the duty asks");
        assertEquals(List.of(), view.alarms(), "no alarm");
        assertEquals(0, cold.heat(), 1, "nothing flows: the coil takes no heat");
        var hot = new Rig(compile(pure("Methane", 300, 500_000), 450, 1_000_000));
        hot.power();
        hot.run(6, true);
        assertEquals(0, hot.heaters.view(BURNERS).demand(), 1e-9, "still water above the set point asks for nothing");
        assertEquals(0, hot.heaters.view(BURNERS).fuelFlow(), 1e-9, "and the fuel valve stays shut");
    }

    @Test
    void aWeakFuelSupplyReducesTheDutyAndRaisesLowFuel() {
        var stiff = new Rig(compile(pure("Methane", 300, 500_000)));
        stiff.power();
        stiff.run(12, true);
        double full = stiff.heat();
        // 2.016 bar against the burner tips' 2.013 bar: a trickle.
        var rig = new Rig(compile(pure("Methane", 300, 201_600)));
        rig.power();
        rig.run(12, true);
        var view = rig.heaters.view(BURNERS);
        System.out.printf(Locale.ROOT, "weak supply: asked %.0f kW, fired %.0f kW, coil %.0f kW (stiff %.0f kW)%n", view.asked() / 1e3, view.fired() / 1e3, rig.heat() / 1e3, full / 1e3);
        assertTrue(view.fired() > 0 && view.fired() < Burners.SHORT * view.asked(), "short of what was asked");
        assertEquals(List.of(EquipmentAlarm.LOW_FUEL), view.alarms());
        assertTrue(rig.heat() < .9 * full, "the coil works at a reduced duty: " + rig.heat());
        assertEquals(Burners.radiant(view.fired()), rig.heat(), .05 * rig.heat() + 1e3, "the radiant share of what was fired");
    }

    @Test
    void noFuelLineRaisesNoFuelAndNitrogenIsNotCombustible() {
        var rig = new Rig(compile(pure("Methane", 300, 500_000)));
        rig.power();
        rig.run(3, false);
        assertEquals(List.of(EquipmentAlarm.NO_FUEL), rig.heaters.alarms(BURNERS));
        assertEquals(0, rig.heat(), 1, "nothing burns");
        var inert = new Rig(compile(pure("Nitrogen", 300, 500_000)));
        inert.power();
        inert.run(4, true);
        assertTrue(inert.heaters.view(BURNERS).fuelFlow() > 0, "nitrogen flows in");
        assertEquals(List.of(EquipmentAlarm.NOT_COMBUSTIBLE), inert.heaters.alarms(BURNERS));
        assertEquals(0, inert.heat(), 1, "and does not burn");
    }

    /**
     * A slice whose first step knows no flows (a fresh solver, a rebuilt island) plans the coil from one rate solve's flows
     * (open item O2): planned at no flow, the coil fired fully for that step and took about 10 % more than its duty and its fuel.
     */
    @Test
    void aFreshSolverDoesNotOverfireTheCoil() {
        var rig = new Rig(compile(pure("Methane", 300, 500_000)));
        rig.fresh = true;
        rig.power();
        rig.run(12, true);
        var view = rig.heaters.view(BURNERS);
        System.out.printf(Locale.ROOT, "fresh solvers: coil %.0f kW, fired %.0f kW%n", rig.heat() / 1e3, view.fired() / 1e3);
        assertEquals(view.demand(), rig.heat(), .02 * view.demand() + 1e3, "the coil fires its duty, not more");
        assertTrue(rig.heat() <= Burners.radiant(view.fired()) * 1.01, "nor more than its fuel gives");
    }
}
