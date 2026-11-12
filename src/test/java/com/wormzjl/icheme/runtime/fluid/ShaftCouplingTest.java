package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shaft trains' coupling to the islands (batch 2026-11-03-rotating-machinery W3, build plan section 2): a machine's
 * speed is a slice input read at dispatch, so a change reaches the island's next slice, one slice after the step that
 * made it; a certified island stays certified while its speed holds, and a change of speed ends its certificate
 * ({@link IslandCoordinator#shaftsMoved}, a coupling wake). The governor valve walk finds the turbine's valve.
 */
class ShaftCouplingTest {
    /**
     * A driver's state with no slice before it (NaN: none yet) saves and reads back (W5 found this: Writer.f64 refuses
     * non-finite numbers, so a world with a started driver failed to save).
     */
    @Test void aDriverStateWithNoSliceBeforeItSavesAndReadsBack() {
        var cell = new PhysicalFluidTopology.Position(EventPathWorld.DIM, 0, 66, 0);
        var states = List.of(new ShaftUnit.State(2950, Double.NaN, Double.NaN), new ShaftUnit.State(2950, 2940, Double.NaN),
                new ShaftUnit.State(1500, 1490, .8));
        var units = new HashMap<Long, ShaftUnit>();
        for (int i = 0; i < states.size(); i++) units.put(i + 1L, ShaftUnit.shaft(i + 1L, cell, 0).withState(states.get(i)));
        byte[] data = FluidFacts.topologyState(List.of(), Map.of(), units);
        var read = FluidFacts.topologyState(data, 0, data.length).shafts();
        for (int i = 0; i < states.size(); i++) assertEquals(states.get(i), read.get(i + 1L), "state " + i);
    }
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry FAT = new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    private static final CentrifugalPump PUMP = new CentrifugalPump(.01, 50, .7);

    private FluidThermodynamics.State water(double pressure) {
        var n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return model.flashTP(298.15, pressure, n, () -> {
        });
    }

    /** A test train: the share it sends the pump at node 1, and the speeds it saw each machine solve at. */
    private static final class Train implements IslandCoordinator.Shafts {
        double share;
        final List<Double> booked = new ArrayList<>();
        int decisions;

        public Map<Long, ShaftPlan> plans(long island, PassiveNetwork graph) {
            return Map.of(1L, new ShaftPlan(ShaftPlan.Kind.SPEED, share));
        }

        public void booked(long island, PassiveNetwork graph, long start, long end, PassiveIntervalSolver.Result result) {
            if (result.graph().pipes().getFirst().control() instanceof FlowControl.Machine m) booked.add(m.speed());
        }

        public void decide() {
            decisions++;
        }
    }

    private final class Rig {
        final long[] epoch = {0};
        final Map<Long, IslandCoordinator.Attempt> attempts = new LinkedHashMap<>();
        final Map<Long, ProcessSolveServices.FluidIslandCommand> commands = new HashMap<>();
        final IslandCoordinator coordinator;
        long requests;

        Rig() {
            coordinator = new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers() {
                    return 64 - attempts.size();
                }

                public long nextRequestId() {
                    return ++requests;
                }

                public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveServices.FluidIslandCommand command) {
                    attempts.put(attempt.slice().requestId(), attempt);
                    commands.put(attempt.slice().requestId(), command);
                    return true;
                }

                public void cancel(long request) {
                    throw new AssertionError("no deadlines here");
                }
            }, changed -> {
            }, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.defaults()), () -> epoch[0]);
        }

        void drain() {
            while (!attempts.isEmpty()) {
                for (long request : List.copyOf(attempts.keySet())) {
                    var attempt = attempts.remove(request);
                    var command = commands.remove(request);
                    coordinator.completed(attempt, Optional.of((ProcessSolveServices.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER)));
                }
                coordinator.pump();
            }
        }

        void run(double seconds) {
            long ticks = Math.round(seconds * 20);
            for (long i = 0; i < ticks; i++) {
                epoch[0]++;
                coordinator.tick();
                drain();
            }
        }
    }

    /** The pump between a supply and a void that asks its 0.8 speed line's rise at 80 % of its rated flow. */
    private PassiveNetwork line() {
        var supply = water(101325);
        double rho = supply.mass() / supply.volume(), rise = PUMP.rise(.008, .8, rho, supply.pressure(), 1.4);
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, supply, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, water(101325 + rise), PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10, 0, 1, FAT, new FlowControl.Machine(PUMP, 0, true))));
    }

    @Test
    void aSpeedReachesTheNextSliceAndAChangeWakesACertifiedIsland() {
        var rig = new Rig();
        var train = new Train();
        train.share = .8;
        rig.coordinator.shafts(train);
        rig.coordinator.register(new IslandCoordinator.Snapshot(1, 0, line(), new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model);
        for (int i = 0; i < 240 && rig.coordinator.observe(1).certificate().isEmpty(); i++) rig.run(5);
        assertTrue(rig.coordinator.observe(1).certificate().isPresent(), "a steady pump line certifies: " + rig.coordinator.certificationRefusal(1));
        assertTrue(train.booked.stream().allMatch(s -> s == .8), "every slice solved at the share sent at its dispatch");
        assertTrue(train.decisions > 0, "the trains decide at the coordinator's decision points");
        double flow = rig.coordinator.observe(1).lastResult().orElseThrow().endpointFlows()[0];
        // While the share holds, the certificate stands.
        int before = train.booked.size();
        rig.run(60);
        assertTrue(rig.coordinator.observe(1).certificate().isPresent(), "the share held: still certified");
        // A step to share 1: one wake, then the next slice solves at 1 (one slice after the step).
        boolean enabled = FluidRuntimeDiagnostics.ENABLED;
        FluidRuntimeDiagnostics.ENABLED = true;
        long wakes = FluidRuntimeDiagnostics.couplingWakes.sum();
        train.share = 1;
        int seen = train.booked.size();
        rig.coordinator.shaftsMoved(Set.of(1L));
        rig.run(10);
        long woken = FluidRuntimeDiagnostics.couplingWakes.sum() - wakes;
        FluidRuntimeDiagnostics.ENABLED = enabled;
        assertEquals(1, woken, "the speed change woke the certified island once");
        assertTrue(train.booked.size() > seen, "it solved again");
        assertEquals(1.0, train.booked.getLast(), 0, "at the new share");
        double faster = rig.coordinator.observe(1).lastResult().orElseThrow().endpointFlows()[0];
        assertTrue(faster > 1.1 * flow, "full speed against the same static rise moves more: " + flow + " -> " + faster);
        assertTrue(before > 0);
    }

    private static PhysicalFluidTopology.Position cell(int x) {
        return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, 64, 0);
    }

    @Test
    void newlyRegisteredMotorAndAutoGearboxWaitForPowerIncludingStartAndReset() {
        var rig = new Rig();
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        var trains = new ShaftTrains(ledger, device -> null, rig.coordinator);
        var stores = new ElectricStores(ledger, device -> null, rig.coordinator, () -> ElectricStores.Settings.DEFAULT);
        trains.electric(stores);
        var kind = com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR;
        var gearbox = com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.GEARBOX;
        var fixed = com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED;
        var none = com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE;
        trains.queue(new ShaftTrains.Put(cell(0), kind, id -> new ShaftUnit(id, kind, cell(0), 0,
                List.of(new ShaftUnit.End(cell(0), 1, 0, 0, true)), 0, 11e3, 2950, 2, fixed, ShaftUnit.Settings.DEFAULT, none, null)));
        trains.queue(new ShaftTrains.Put(cell(1), com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT, id -> ShaftUnit.shaft(id, cell(1), 0)));
        trains.queue(new ShaftTrains.Put(cell(2), gearbox, id -> new ShaftUnit(id, gearbox, cell(2), 0,
                List.of(new ShaftUnit.End(cell(2), -1, 0, 0, false), new ShaftUnit.End(cell(2), 1, 0, 0, true)),
                0, 0, 0, 0, fixed, ShaftUnit.Settings.DEFAULT, none, null)));
        trains.apply();
        long motor = trains.unitAt(cell(0)).id(), gear = trains.unitAt(cell(2)).id();
        assertFalse(stores.powered(motor), "a newly registered consumer has no legacy free-power exemption");
        assertEquals(0, trains.view(motor).speed());
        assertEquals(0, trains.view(gear).speed());
        trains.decide();
        assertEquals(0, trains.view(motor).speed());
        assertEquals(0, trains.view(gear).speed());
        trains.queue(new ShaftTrains.Configure(motor, new ShaftUnit.Settings(false, com.wormzjl.icheme.science.machine.ShaftTrain.Mode.SPEED, 0, 0, 0)));
        trains.apply();
        trains.queue(new ShaftTrains.Configure(motor, ShaftUnit.Settings.DEFAULT));
        trains.apply();
        assertEquals(0, trains.view(motor).speed(), "Run arms the driver without inventing power");
        ledger.shaft(ledger.shaft(motor).withTrip(com.wormzjl.icheme.science.machine.ShaftTrain.Trip.OVERLOAD));
        trains.queue(new ShaftTrains.Reset(motor));
        trains.apply();
        assertEquals(none, trains.unit(motor).trip());
        assertEquals(0, trains.view(motor).speed(), "Reset also waits for a power-backed step");
    }

    @Test void anUnloadedFullMotorNeedsALiveFeedAndKeepsItsOlderLargerSavedBuffer(){
        var rig=new Rig();var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        long id=ledger.reserve(1);
        var kind=com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR;
        var trip=com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE;
        ledger.shaft(new ShaftUnit(id,kind,cell(0),0,List.of(new ShaftUnit.End(cell(0),1,0,0,true)),0,11e3,3000,2,
                com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE,ShaftUnit.Settings.DEFAULT,trip,null));
        double saved=com.wormzjl.icheme.science.machine.ElectricStore.capacity(com.wormzjl.icheme.science.machine.ElectricStore.motorDraw(11e3,2,true),com.wormzjl.icheme.science.fluid.SimulationTime.ONLINE);
        ledger.stores(Map.of(id,new com.wormzjl.icheme.science.machine.ElectricStore.Facts(saved,true)));
        var stores=new ElectricStores(ledger,device->null,rig.coordinator,()->ElectricStores.Settings.DEFAULT);
        boolean[] live={false};stores.supply(motor->live[0]);
        var trains=new ShaftTrains(ledger,device->null,rig.coordinator);trains.electric(stores);
        trains.host(ShaftTrains.Host.NONE);trains.decide();
        assertEquals(0,trains.view(id).speed(),"Reloading a charged buffer does not invent a supply");
        assertEquals(saved,stores.view(id).joules(),0,"A reduced buffer limit never deletes saved energy");
        stores.receive(id,Integer.MAX_VALUE,true);
        assertFalse(stores.restart(id),"An FE simulation cannot invent a supply");
        live[0]=true;
        for(int tick=1;tick<=300;tick++){
            rig.epoch[0]=tick;rig.coordinator.tick();
            assertEquals(0,stores.receive(id,Integer.MAX_VALUE,true),"A full buffer needs no transfer; its connected source still supplies it");
            if(tick%100==0){stores.cadence();trains.cadence();}trains.decide();
        }
        assertTrue(trains.view(id).speed()>0,"An unloaded, full motor stays powered by the connected source");
        assertEquals(0,trains.view(id).electricPower());
        assertEquals(saved,stores.view(id).joules(),0,"Supply observation cannot change stored energy");
        live[0]=false;
        for(int tick=301;tick<=401;tick++){
            rig.epoch[0]=tick;rig.coordinator.tick();
            if(tick%100==0){stores.cadence();trains.cadence();}trains.decide();
        }
        assertEquals(0,trains.view(id).speed(),"Removing the live feed stops even a zero-load motor");
        assertEquals("TRIPPED: NO POWER",trains.view(id).status());
        assertEquals(saved,stores.view(id).joules(),0);
        assertEquals(trip,ledger.shaft(id).trip(),"A feed interruption never latches a protection trip");
    }

    @Test
    void turbineHasInternalAdmissionControlWithoutAnAccessoryAndStopClosesIt() {
        var rig = new Rig();
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        var trains = new ShaftTrains(ledger, device -> null, rig.coordinator);
        var kind = com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.STEAM_TURBINE;
        var none = com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE;
        trains.queue(new ShaftTrains.Put(cell(0), kind, id -> new ShaftUnit(id, kind, cell(0), 0,
                List.of(new ShaftUnit.End(cell(0), 1, 0, 0, true)), 1, 1e6, 6000, 0,
                com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED, ShaftUnit.Settings.DEFAULT, none, null)));
        trains.apply();
        var base = line();
        var graph = new PassiveNetwork(base.reservoirs(), List.of(base.pipes().getFirst().withControl(
                new FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(1e6)))));
        var plan = trains.plans(1, graph).get(1L);
        assertNotNull(plan, "no external speed sensor or valve is required");
        assertEquals(ShaftPlan.Kind.GOVERNOR, plan.kind());
        assertTrue(plan.value() > 0);
        long turbine = trains.unitAt(cell(0)).id();
        trains.decide();
        assertEquals(0, trains.view(turbine).speed(), "open admission without booked steam work does not start the shaft");
        assertEquals(none, trains.view(turbine).trip());
        trains.queue(new ShaftTrains.Configure(turbine, new ShaftUnit.Settings(false, com.wormzjl.icheme.science.machine.ShaftTrain.Mode.SPEED, 0, 0, 0)));
        trains.apply();
        assertEquals(0, trains.plans(1, graph).get(1L).value(), "Stop closes internal admission");
        trains.queue(new ShaftTrains.Configure(turbine, ShaftUnit.Settings.DEFAULT));
        trains.apply();
        assertTrue(trains.plans(1, graph).get(1L).value() > 0, "Run rearms admission");
        assertEquals(0, trains.view(turbine).speed(), "rearming still waits for fluid work");
    }

    /**
     * W5 (build plan section 7.4): an 11 kW motor drives the pump at node 1 through a shaft. Built from W5 on, its store
     * starts empty, so it stands (NO POWER) until its port is filled to one slice of its rated draw; it then runs, books
     * its draw at its train's steps and on the cadence, stops on feed loss (NO POWER, never saved as its trip) and restarts
     * by itself on a refill.
     */
    @Test
    void aMotorStopsOnLostFeedWithEnergyLeftAndRestartsWhenTheFeedReturns() {
        var rig = new Rig();
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        long first = ledger.reserve(3);
        var fixed = com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED;
        var trip = com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE;
        ledger.shaft(new ShaftUnit(first, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR, cell(0), 0, List.of(new ShaftUnit.End(cell(0), 1, 0, 0, true)), 0,
                11e3, 2950, 2, fixed, ShaftUnit.Settings.DEFAULT, trip, null));
        ledger.shaft(ShaftUnit.shaft(first + 1, cell(1), 0));
        ledger.shaft(new ShaftUnit(first + 2, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CASING, cell(2), 0, List.of(new ShaftUnit.End(cell(2), -1, 0, 0, false)), 1,
                0, 2950, 0, fixed, ShaftUnit.Settings.DEFAULT, trip, null));
        var trains = new ShaftTrains(ledger, device -> device == 1 ? 1L : null, rig.coordinator);
        var stores = new ElectricStores(ledger, device -> device == 1 ? 1L : null, rig.coordinator, () -> ElectricStores.Settings.DEFAULT);
        trains.electric(stores);
        rig.coordinator.shafts(trains);
        trains.host(ShaftTrains.Host.NONE);
        rig.coordinator.register(new IslandCoordinator.Snapshot(1, 0, line(), new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model);
        long motor = first;
        double rated = com.wormzjl.icheme.science.machine.ElectricStore.motorDraw(11e3, 2, true);
        int[] feed = {0};
        Runnable tick = () -> {
            rig.epoch[0]++;
            rig.coordinator.tick();
            rig.drain();
            if (feed[0] > 0) stores.receive(motor, feed[0], false);
            if (rig.epoch[0] % ElectricStores.CADENCE == 0) {
                stores.cadence();
                trains.cadence();
            }
        };
        // Empty: the motor stands.
        for (int i = 0; i < 300; i++) tick.run();
        assertEquals("TRIPPED: NO POWER", trains.view(motor).status(), "a new store is empty");
        assertEquals(com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NO_POWER, trains.view(motor).trip());
        assertEquals(trip, ledger.shaft(motor).trip(), "NO POWER is never the unit's saved trip");
        assertEquals(0, trains.share(1), "the pump stands");
        // A port filled at its full rate: the store passes one slice of rated draw and the motor restarts on the cadence.
        feed[0] = Integer.MAX_VALUE;
        assertEquals(stores.receive(motor, Integer.MAX_VALUE, true), (int) Math.floor(2 * rated * .05 / 2.5 + 1e-9), "the port takes twice the rated draw per tick");
        for (int i = 0; i < 1000 && !trains.view(motor).status().equals("RUNNING"); i++) tick.run();
        assertEquals("RUNNING", trains.view(motor).status());
        assertEquals(10*rated,stores.view(motor).capacity(),1e-9,"Only two presentation periods of motor buffer");
        for (int i = 0; i < 600; i++) tick.run();
        assertEquals(1, trains.share(1), 1e-9, "the pump turns at its design speed");
        double draw = trains.view(motor).electricPower();
        assertTrue(draw > 1e3 && draw < rated, "it draws the pump's shaft power over its efficiency: " + draw);
        double full = stores.view(motor).capacity();
        assertTrue(stores.view(motor).joules() >= full - draw * 5, "the port kept it full within a cadence's booking");
        // The feed stops: the next cadence stops the drive without draining the remaining buffer.
        feed[0] = 0;
        double before = stores.view(motor).joules();
        long start = rig.epoch[0];
        for (int i = 0; i < 102 && !trains.view(motor).status().equals("TRIPPED: NO POWER"); i++) tick.run();
        assertEquals("TRIPPED: NO POWER", trains.view(motor).status());
        assertTrue(stores.view(motor).joules()>0,"Unplugging retains the remaining joules");
        assertTrue(stores.view(motor).joules()<=before,"No energy is created at disconnection");
        assertTrue(rig.epoch[0]-start<=102,"The drive stops by the next scheduled decision");
        double retained=stores.view(motor).joules();
        for (int i = 0; i < 300; i++) tick.run();
        assertEquals(0, trains.share(1), "the pump stands without power");
        assertEquals(retained,stores.view(motor).joules(),1e-9,"Stored energy cannot restart an unplugged motor");
        assertEquals(trip, ledger.shaft(motor).trip());
        // A refill restarts it by itself (D6), no reset.
        feed[0] = Integer.MAX_VALUE;
        for (int i = 0; i < 1000 && !trains.view(motor).status().equals("RUNNING"); i++) tick.run();
        assertEquals("RUNNING", trains.view(motor).status());
        for (int i = 0; i < 300; i++) tick.run();
        assertEquals(1, trains.share(1), 1e-9);
    }

    /**
     * W6b (form A, D40): a Create coupling drives the pump at node 1 through a shaft, the casing rated at 256 rpm. Create's
     * inputs are queued events applied at the tick drain; the impact goes back through the host at the train's step.
     * Without an input it stands (NO ROTATION); at 256 rpm with stress to spare it runs at share 1 and draws the stress of
     * the train's power at 1.465 W per su; with little spare it slows (POWER LIMITED) and never draws more than the spare;
     * Create at rest stands it again and its draw goes to 0.
     */
    @Test
    void aCreateCouplingTurnsAtCreatesSpeedWithinItsSpareStress() {
        var rig = new Rig();
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        long first = ledger.reserve(3);
        var variable = com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE;
        var trip = com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE;
        ledger.shaft(new ShaftUnit(first, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CREATE_COUPLING, cell(0), 0, List.of(new ShaftUnit.End(cell(0), 1, 0, 0, true)), 0,
                0, com.wormzjl.icheme.science.machine.CreateCoupling.DESIGN_SPEED, 0, variable, ShaftUnit.Settings.DEFAULT, trip, null));
        ledger.shaft(ShaftUnit.shaft(first + 1, cell(1), 0));
        ledger.shaft(new ShaftUnit(first + 2, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CASING, cell(2), 0, List.of(new ShaftUnit.End(cell(2), -1, 0, 0, false)), 1,
                0, 256, 0, variable, ShaftUnit.Settings.DEFAULT, trip, null));
        var trains = new ShaftTrains(ledger, device -> device == 1 ? 1L : null, rig.coordinator);
        var sent = new ArrayList<Double>();
        rig.coordinator.shafts(trains);
        trains.host(new ShaftTrains.Host() {
            public void edit(long device, FlowControl.ControlValve control) {
            }

            public UUID speedSensor(long turbine) {
                return null;
            }

            public void resize(PhysicalFluidTopology.Position shaft, int pixels) {
            }

            @Override
            public void stress(PhysicalFluidTopology.Position coupling, double impact) {
                assertEquals(cell(0), coupling);
                sent.add(impact);
            }
        });
        rig.coordinator.register(new IslandCoordinator.Snapshot(1, 0, line(), new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model);
        long coupling = first;
        double wps = trains.wattsPerStress();
        assertEquals(1.46499, wps, 1e-5, "20 x 2.5 J per FE / 34.13 su per FE");
        Runnable tick = () -> {
            rig.epoch[0]++;
            if (trains.hasEvents()) trains.apply();
            rig.coordinator.tick();
            rig.drain();
        };
        for (int i = 0; i < 200; i++) tick.run();
        assertEquals("NO ROTATION", trains.view(coupling).status(), "no Create input yet");
        assertEquals(0, trains.share(1));
        // Create at 256 rpm with stress to spare: the train runs at Create's speed and draws its power's stress.
        trains.queue(new ShaftTrains.CreateInput(cell(0), 256, 1e6));
        for (int i = 0; i < 1200; i++) tick.run();
        assertEquals("RUNNING", trains.view(coupling).status());
        assertEquals(1, trains.share(1), 1e-9, "the pump at 256 rpm, its design speed");
        double demand = trains.view(coupling).demand();
        assertTrue(demand > 100, "the pump takes power: " + demand);
        assertEquals(demand / (wps * 256), sent.getLast(), 2e-3 * sent.getLast(), "impact = demand / (W per su x rpm)");
        assertEquals(sent.getLast(), trains.coupling(coupling).impact(), 0);
        // Little to spare: a fifth of the train's stress. It slows (POWER LIMITED) and draws no more than the spare.
        double spare = .2 * demand / wps;
        int from = sent.size();
        trains.queue(new ShaftTrains.CreateInput(cell(0), 256, spare));
        for (int i = 0; i < 2400; i++) tick.run();
        assertTrue(trains.view(coupling).status().startsWith("POWER LIMITED"), trains.view(coupling).status());
        assertTrue(trains.share(1) < .9 && trains.share(1) > 0, "the train slows: " + trains.share(1));
        assertTrue(sent.size() > from, "the limit sent a new impact");
        for (double impact : sent.subList(from, sent.size())) assertTrue(impact * 256 <= spare, "never more than the spare: " + impact * 256 + " of " + spare);
        assertTrue(sent.getLast() * 256 > .9 * spare, "the slowed train takes about all of it: " + sent.getLast() * 256 + " of " + spare);
        // Create at rest: the train stands and draws nothing.
        trains.queue(new ShaftTrains.CreateInput(cell(0), 0, spare));
        for (int i = 0; i < 400; i++) tick.run();
        assertEquals("NO ROTATION", trains.view(coupling).status());
        assertEquals(0, trains.share(1));
        assertEquals(0, sent.getLast(), 0);
    }

    /** The governor walk: from a turbine's inlet upstream through unbranched pipes to the nearest control valve. */
    @Test
    void theGovernorWalkFindsTheTurbinesValve() {
        var steam = water(4e6);
        var v = new FlowControl.ControlValve(FlowControl.ValveSource.NONE, 200000, .1, .01, null, 1);
        var turbine = new FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(55e3));
        // header 1 -> pipe node 2 -> valve 3 -> pipe node 4 -> turbine 5 -> exhaust 6
        var nodes = new ArrayList<PassiveNetwork.Reservoir>();
        for (long id = 1; id <= 6; id++) nodes.add(new PassiveNetwork.Reservoir(id, 0, steam, id == 1 ? PassiveNetwork.NodeKind.GENERATOR : id == 6 ? PassiveNetwork.NodeKind.VOID : PassiveNetwork.NodeKind.RESERVOIR));
        var pipes = List.of(new PassiveNetwork.Pipe(101, 0, 1, FAT, new FlowControl.Passive()), new PassiveNetwork.Pipe(102, 2, 1, FAT, v),
                new PassiveNetwork.Pipe(103, 2, 3, FAT, new FlowControl.Passive()), new PassiveNetwork.Pipe(104, 4, 3, FAT, new FlowControl.Passive()),
                new PassiveNetwork.Pipe(105, 4, 5, FAT, turbine));
        var graph = new PassiveNetwork(nodes, pipes);
        assertEquals(3, ShaftTrains.upstreamValve(graph, 5), "the valve's node");
        // A branch between them stops the walk: no governor valve.
        var branched = new ArrayList<>(pipes);
        branched.add(new PassiveNetwork.Pipe(106, 3, 5, FAT, new FlowControl.Passive()));
        assertEquals(0, ShaftTrains.upstreamValve(new PassiveNetwork(nodes, branched), 5));
    }
}
