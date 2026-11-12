package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A batch still in the engine (batch 2026-11-09-batch-distiller, W3 to W5; BATCH_STILL_ENGINE.md), on the island coordinator
 * with {@link Stills} as its port hooks and every island solved at once, as the worker would:
 *
 * <ul>
 * <li>A solid-fired pot of hot water heats at its rating, boils out of its VAPOUR nozzle at its heat over water's latent heat,
 * burns its items' heat at the heater's efficiency and goes out with NO FUEL when the slot is empty.</li>
 * <li>An electric pot waits for power (NO POWER), then heats at its rating and draws its rating over its efficiency.</li>
 * <li>A pentane pot with its column and condenser distils: the head is cooler than the pot, the distillate richer in
 * isopentane than the charge, the water takes the condenser's duty, and every mole is in the pot, an account or a gift.</li>
 * <li>A diverter valve joins its inlet and the one outlet it is set to.</li>
 * </ul>
 */
class StillRuntimeTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final PipeResistance.Geometry STUB = new PipeResistance.Geometry(1, .05, .000045, 0);
    private static final long WATER = 20, POT = 21, DISTILLATE = 22, VENT = 23;
    /** A 100 L pot: 0.50 m wide, 0.44 m² heated, 10.9 kW at full firing. */
    private static final double VOLUME = .1;
    /** One item of the solid still's fuel, J. */
    private static final double ITEM = 2e6;

    private static PhysicalFluidTopology.Position at(int x, int y, int z) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, y, z);
    }

    private static PhysicalFluidTopology.Device plain(long id, int x, int z, Kind kind) {
        return new PhysicalFluidTopology.Device(id, at(x, 0, z), kind, EAST, STUB, new FlowControl.Passive());
    }

    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell, PhysicalFluidTopology.Direction face, PassiveNetwork.PhasePort phase, PhysicalFluidTopology.ConnectionRole role) {
        return new PhysicalFluidTopology.Port(cell, face, phase, role);
    }

    /** The pot at (0, 0, 0) as {@code FluidWorldAuthority.placeStill} places it: closed with its VAPOUR nozzle east, or open (its head). */
    private static PhysicalFluidTopology.Device pot(BatchStill.Design design) {
        var ports = design.condenser() ? List.<PhysicalFluidTopology.Port>of() : List.of(port(at(0, 0, 0), EAST, PassiveNetwork.PhasePort.VAPOR, PhysicalFluidTopology.ConnectionRole.VESSEL));
        return new PhysicalFluidTopology.Device(POT, at(0, 0, 0), Kind.RESERVOIR, NORTH, STUB, new FlowControl.Passive(), ports, true, PhysicalFluidTopology.Cutoff.NONE, false,
                PhysicalFluidTopology.ALL_FACES, 0, 0, new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.POT, design.heatedArea(), 0));
    }

    /** The condenser's water side, its inlet at (2, 0, 10) and its outlet at (4, 0, 10), partnered with the pot. */
    private static PhysicalFluidTopology.Device water(BatchStill.Design design) {
        var ports = List.of(port(at(2, 0, 10), WEST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.INLET),
                port(at(4, 0, 10), EAST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(WATER, at(2, 0, 10), Kind.HEAT_SIDE, NORTH, new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0),
                new FlowControl.Passive(), ports, true, PhysicalFluidTopology.Cutoff.NONE, false, PhysicalFluidTopology.ALL_FACES, 0, 0,
                new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.WATER, design.condenserArea(), POT));
    }

    /** A still outlet at (0, 0, {@code z}): a generator carrying the still's valve, its one port east. */
    private static PhysicalFluidTopology.Device outlet(long id, int z) {
        return new PhysicalFluidTopology.Device(id, at(0, 0, z), Kind.GENERATOR, NORTH, STUB, FlowControl.ControlValve.still(),
                List.of(port(at(0, 0, z), EAST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL)), true, PhysicalFluidTopology.Cutoff.NONE, false,
                PhysicalFluidTopology.ALL_FACES, 0, 0, null);
    }

    private static FluidThermodynamics.State per(FluidThermodynamics model, double[] fractions, double temperature, double pressure) {
        double[] n = fractions.clone();
        double volume = model.flashTP(temperature, pressure, n, () -> {
        }).volume();
        for (int c = 0; c < n.length; c++) n[c] /= volume;
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    private static FluidThermodynamics.State water(FluidThermodynamics model, double temperature, double pressure) {
        double[] n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return per(model, n, temperature, pressure);
    }

    /** The pot's contents: {@code fill} of its volume of {@code liquid} (a cubic metre of it), the rest its vapour. */
    private static PassiveNetwork.Reservoir contents(FluidThermodynamics model, FluidThermodynamics.State liquid, double fill, boolean open) {
        double height = 1.1 * BatchStill.potDiameter(VOLUME);
        double[] n = PhaseLayout.totalAmounts(liquid);
        for (int c = 0; c < n.length; c++) n[c] *= fill * VOLUME;
        var inventory = new PassiveNetwork.Inventory(VOLUME, n, liquid.internalEnergy() * fill * VOLUME, SolidInventory.EMPTY, height, open, VesselGeometry.prism(VOLUME, height));
        return new PassiveNetwork.Reservoir(POT, 0, FluidTestSupport.inventoryState(model, liquid, inventory), PassiveNetwork.NodeKind.RESERVOIR, inventory);
    }

    /**
     * The still's runtime: a coordinator on its own epoch whose worker solves each admitted slice at once (as
     * {@code ExchangerCouplingTest}'s does), the ledger with the still's facts, its electric stores and {@link Stills} as
     * the coordinator's ports. A column solve completes at the next drain, as the worker's would.
     */
    private static final class Rig {
        final FluidThermodynamics model;
        final long[] epoch = {0};
        final Map<Long, IslandCoordinator.Attempt> attempts = new LinkedHashMap<>();
        final Map<Long, ProcessSolveServices.FluidIslandCommand> commands = new HashMap<>();
        final List<Runnable> solved = new ArrayList<>();
        final Map<Long, Long> committed = new HashMap<>(), owners = new HashMap<>();
        /** By node: the heat booked (HEAT boundaries, J) and the moles given (EXCHANGE boundaries) over every committed span. */
        final Map<Long, Double> heat = new HashMap<>();
        final Map<Long, double[]> given = new HashMap<>();
        /** By node and boundary kind: the energy booked (J) over every committed span. */
        final Map<String, Double> energies = new TreeMap<>();
        final IslandCoordinator coordinator;
        final WorldTopologyLedger ledger;
        final ElectricStores stores;
        final Stills stills;
        int items, taken, solves;
        /** Each column job as it was queued: its tick and its condenser outlet floor, K. */
        final List<double[]> jobs = new ArrayList<>();
        boolean feeding;
        long requests;

        Rig(FluidThermodynamics model, WorldTopologyLedger.FluidBasis basis, List<PhysicalFluidTopology.Device> devices, Map<Long, PassiveNetwork.Reservoir> states, StillUnit unit) {
            this.model = model;
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
            }, this::book, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.disabled()).withTime(model.time()), () -> epoch[0]);
            coordinator.onReplayed(this::book);
            // The still's devices in the ledger (their specs are not read here: water, on the basis).
            double[] composition = new double[model.componentCount()];
            composition[composition.length - 1] = 1;
            var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
            for (var d : devices)
                if (d.id() == POT || d.id() == WATER || d.id() == DISTILLATE || d.id() == VENT)
                    active.put(d.id(), new WorldTopologyLedger.Registration(d, new FluidDeviceSpec(1, 300, 101325, composition), 0));
            int m = basis.components().size();
            ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), WorldTopologyLedger.MaterialTotal.empty(m), WorldTopologyLedger.MaterialTotal.empty(m), basis));
            ledger.still(unit);
            stores = new ElectricStores(ledger, owners::get, coordinator, () -> ElectricStores.Settings.DEFAULT);
            stills = new Stills(ledger, owners::get, coordinator, island -> model, stores, model.components(), model.molecularWeights(), new double[model.componentCount()]);
            stills.fuel(pot -> {
                if (taken >= items) return 0;
                taken++;
                return ITEM;
            });
            stills.solves((pot, island, job, done) -> {
                solves++;
                jobs.add(new double[]{epoch[0], job.outlet()});
                solved.add(() -> done.accept(StillHead.run(job, model, () -> {
                })));
                return true;
            });
            stills.refresh();
            coordinator.ports(stills);
            var compiled = PhysicalFluidTopology.compile(devices, states);
            for (int i = 0; i < compiled.islands().size(); i++) {
                long id = i + 1;
                var graph = compiled.islands().get(i).graph();
                for (var r : graph.reservoirs()) owners.put(r.id(), id);
                for (var p : graph.pipes()) {
                    owners.put(p.id(), id);
                    owners.put(p.id() - PhysicalFluidTopology.sideIdentity(0), id);
                }
                coordinator.register(new IslandCoordinator.Snapshot(id, 0, graph, new IslandClock.Snapshot(epoch[0], epoch[0], 0, 100), Optional.empty(), "READY"), model);
                committed.put(id, epoch[0]);
            }
            assertNotNull(owners.get(POT), "the pot is in an island");
        }

        void book(List<IslandCoordinator.Snapshot> changed) {
            for (var s : changed) {
                long to = s.clock().committedTick(), from = committed.getOrDefault(s.id(), to);
                committed.put(s.id(), to);
                if (to == from || s.lastResult().isEmpty()) continue;
                var result = s.lastResult().orElseThrow();
                double scale = result.advancedSeconds() > 0 ? model.time().seconds(to - from) / result.advancedSeconds() : 0;
                if (Math.abs(scale - 1) < 1e-12) scale = 1;
                for (var b : result.boundaries()) {
                    if (b.kind() == ConservativeTransport.BoundaryTransfer.Kind.HEAT) heat.merge(b.nodeId(), b.totalEnergyJoule() * scale, Double::sum);
                    energies.merge(b.nodeId() + " " + b.kind(), b.totalEnergyJoule() * scale, Double::sum);
                    if (b.kind() == ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE) {
                        var sum = given.computeIfAbsent(b.nodeId(), id -> new double[model.componentCount()]);
                        var n = b.moles();
                        for (int c = 0; c < n.length; c++) sum[c] += Math.max(0, n[c]) * scale;
                    }
                }
            }
        }

        void drain() {
            while (!attempts.isEmpty() || !solved.isEmpty()) {
                for (long request : List.copyOf(attempts.keySet())) {
                    var attempt = attempts.remove(request);
                    var command = commands.remove(request);
                    coordinator.completed(attempt, Optional.of((ProcessSolveServices.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER)));
                }
                var done = List.copyOf(solved);
                solved.clear();
                done.forEach(Runnable::run);
                coordinator.pump();
            }
        }

        void run(double seconds) {
            long ticks = Math.round(seconds * 20);
            for (long i = 0; i < ticks; i++) {
                epoch[0]++;
                if (feeding) stores.receive(POT, Integer.MAX_VALUE, false);
                if (epoch[0] % ElectricStores.CADENCE == 0) stores.cadence();
                coordinator.tick();
                drain();
            }
        }

        Stills.View view() {
            return stills.view(POT);
        }

        /**
         * An inlet step: generator {@code node}'s island replaced at its committed tick, through a topology event, by its own
         * graph with that generator delivering {@code state} (as {@code ExchangerCouplingTest}'s step).
         */
        void step(long node, FluidThermodynamics.State state) {
            long id = owners.get(node);
            var island = coordinator.snapshot(id);
            var event = UUID.randomUUID();
            long tick = island.clock().committedTick();
            coordinator.fence(event, tick, Set.of(id));
            var nodes = new ArrayList<>(island.graph().reservoirs());
            for (int i = 0; i < nodes.size(); i++)
                if (nodes.get(i).id() == node) nodes.set(i, new PassiveNetwork.Reservoir(node, nodes.get(i).elevation(), state, PassiveNetwork.NodeKind.GENERATOR));
            var graph = new PassiveNetwork(nodes, island.graph().pipes(), island.graph().scheduledTransfers());
            coordinator.topology(Set.of(event), Set.of(id), List.of(new IslandCoordinator.Replacement(id, graph)), model, tick, island.clock().onlineTick(), Map.of(), Set.of(), Map.of(), () -> {
            });
        }

        StillUnit unit() {
            return ledger.still(POT);
        }

        /** The pot's moles as its island last committed them. */
        double[] pot() {
            return potNode().inventory().moles();
        }

        PassiveNetwork.Reservoir potNode() {
            var graph = coordinator.observe(owners.get(POT)).graph();
            return graph.reservoirs().stream().filter(r -> r.id() == POT).findFirst().orElseThrow();
        }
    }

    private static void print(String what, Rig rig) {
        var v = rig.view();
        System.out.printf(Locale.ROOT, "%s at %.0f s: %s %s, pot %.2f K %.0f %% full, head %.2f K, heat %.2f kW, distillate %.4f kg/s, vent %.4f kg/s, condenser %.2f, R %.2f%n",
                what, rig.epoch[0] / 20.0, v.status(), v.alarms(), v.potTemperature(), 100 * v.filled(), v.headTemperature(), v.heat() / 1e3, v.distillateFlow(), v.ventFlow(), v.condensed(), v.reflux());
    }

    /** A closed still of {@code heater} on the network model, 90 % full of water at 372 K, its VAPOUR line into the air. */
    private static Rig waterStill(BatchStill.Heater heater) {
        return waterStill(heater, 1);
    }

    /** That still under time factor {@code factor} (batch 2026-11-11-minecraft-time-delta). */
    private static Rig waterStill(BatchStill.Heater heater, int factor) {
        var model = FluidTestSupport.networkModel().withTime(com.wormzjl.icheme.science.fluid.SimulationTime.of(factor));
        var design = new BatchStill.Design(VOLUME, heater, false, 0);
        var devices = List.of(pot(design), plain(11, 1, 0, Kind.PIPE), plain(12, 2, 0, Kind.VOID));
        var states = new HashMap<Long, PassiveNetwork.Reservoir>();
        states.put(POT, contents(model, water(model, 372, 150_000), .9, false));
        states.put(12L, new PassiveNetwork.Reservoir(12, 0, per(model, nitrogen(model), 300, 101_325), PassiveNetwork.NodeKind.VOID));
        var unit = StillUnit.built(POT, 0, 0, 0, 0, design, model.componentCount()).withFiring(1);
        return new Rig(model, WorldTopologyLedger.FluidBasis.capture(CATALOG), devices, states, unit);
    }

    private static double[] nitrogen(FluidThermodynamics model) {
        double[] n = new double[model.componentCount()];
        n[model.components().indexOf("Nitrogen")] = 1;
        return n;
    }

    /** W4: the fire burns its items at the heater's efficiency, the pot boils at its heat over the latent heat, and goes out. */
    @Test
    void aSolidFiredPotHeatsBoilsAndGoesOutWhenItsSlotIsEmpty() {
        var rig = waterStill(BatchStill.Heater.SOLID);
        rig.items = 3;
        double rating = rig.unit().design().rating();
        rig.run(60);
        print("solid, heating", rig);
        assertEquals(List.of(), rig.view().alarms(), "the first item lit the fire");
        assertEquals(rating, rig.view().heat(), .01 * rating, "the pot takes its rating at full firing");
        rig.run(60);
        print("solid, boiling", rig);
        var boiling = rig.view();
        assertEquals(BatchStill.Status.DISTILLING, boiling.status(), "the vapour leaves by the VAPOUR nozzle");
        double boilUp = boiling.heat() / Stills.LATENT_CEILING;
        assertEquals(boilUp, boiling.ventFlow(), .2 * boilUp, "the boil-up is the heat over water's latent heat");
        rig.run(240);
        print("solid, out", rig);
        assertEquals(3, rig.taken, "every item burned");
        assertEquals(List.of(EquipmentAlarm.NO_FUEL), rig.view().alarms());
        assertEquals(0, rig.view().heat(), 0, "the fire is out");
        double budget = 3 * ITEM * BatchStill.Heater.SOLID.efficiency();
        assertEquals(0, rig.unit().burning(), 1e-6 * ITEM, "nothing is left burning");
        assertTrue(rig.unit().heated() <= budget * (1 + 1e-9) && rig.unit().heated() > .98 * budget,
                "the pot took the items' heat at the heater's efficiency: " + rig.unit().heated() + " of " + budget);
        assertEquals(rig.unit().heated(), rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(POT), 0.), 1e-3 * budget, "the facts' total is the solver's heat");
        assertTrue(rig.stills.statusLine(POT).contains("NO FUEL"), rig.stills.statusLine(POT));
    }

    /**
     * Fuel on process time (batch 2026-11-11-minecraft-time-delta, W4, owner T4): an item's heat is conserved, so at factors 4
     * and 72 the pot takes the same heat from its three items as at 1, burning through them in a factor's share of the play time.
     */
    @Test
    void aSolidFuelsHeatIsConservedAndBurnsOverProcessTimeAtFactorsFourAndSeventyTwo() {
        for (int factor : new int[]{4, 72}) {
            var rig = waterStill(BatchStill.Heater.SOLID, factor);
            rig.items = 3;
            double rating = rig.unit().design().rating(), budget = 3 * ITEM * BatchStill.Heater.SOLID.efficiency();
            // At factor 1 the three items last about budget / rating of play; here a factor's share of it, plus a few cycles.
            double play = budget / rating / factor + 30;
            rig.run(play);
            assertEquals(3, rig.taken, "factor " + factor + ": every item burned");
            assertEquals(0, rig.view().heat(), 0, "factor " + factor + ": the fire is out within " + play + " s of play");
            assertTrue(rig.unit().heated() <= budget * (1 + 1e-9) && rig.unit().heated() > .98 * budget,
                    "factor " + factor + ": the pot took the items' heat: " + rig.unit().heated() + " of " + budget);
            assertEquals(rig.unit().heated(), rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(POT), 0.), 1e-3 * budget, "the facts' total is the solver's heat");
        }
    }

    /** W4: an electric pot waits for its store (NO POWER), then heats at its rating and draws its rating over its efficiency. */
    @Test
    void anElectricPotWaitsForPowerThenDrawsItsRatingOverItsEfficiency() {
        var rig = waterStill(BatchStill.Heater.ELECTRIC);
        double rating = rig.unit().design().rating();
        rig.run(20);
        print("electric, unpowered", rig);
        assertEquals(List.of(EquipmentAlarm.NO_POWER), rig.view().alarms());
        assertEquals(0, rig.view().heat(), 0, "no power, no heat");
        rig.feeding = true;
        rig.run(60);
        print("electric, powered", rig);
        assertEquals(List.of(), rig.view().alarms(), "the store restarted the heater");
        assertEquals(rating, rig.view().heat(), .01 * rating);
        assertEquals(BatchStill.Heater.ELECTRIC.drawn(rating), rig.stores.view(POT).power(), .01 * rating / BatchStill.Heater.ELECTRIC.efficiency(),
                "it draws its heat over its efficiency");
    }

    /** Owner decision O1: a data-network controller's firing overrides the setting while it runs; the setting resumes when it stops. */
    @Test
    void aControllersFiringOverridesTheSettingUntilItStops() {
        var rig = waterStill(BatchStill.Heater.ELECTRIC);
        rig.feeding = true;
        double rating = rig.unit().design().rating();
        double[] plan = {Double.NaN};
        rig.stills.plans(new Stills.Plans() {
            public double firing(long pot) {
                return plan[0];
            }

            public double reflux(long pot) {
                return Double.NaN;
            }
        });
        rig.run(40);
        assertEquals(rating, rig.view().heat(), .01 * rating, "its own firing, 1");
        plan[0] = .5;
        rig.stills.replanned(POT);
        rig.run(20);
        assertEquals(.5 * rating, rig.view().heat(), .01 * rating, "the controller's half firing");
        assertEquals(.5, rig.stills.firing(POT), 0);
        assertEquals(1, rig.unit().firing(), 0, "the setting, a fact, is unchanged");
        plan[0] = Double.NaN;
        rig.stills.replanned(POT);
        rig.run(20);
        assertEquals(rating, rig.view().heat(), .01 * rating, "the setting resumes");
    }

    /**
     * W3: 60 L of a 50/50 pentane charge in a pot with four trays at reflux 2 and a water-cooled condenser distils. The head
     * is cooler than the pot, the distillate richer in isopentane than the charge, the water takes the condenser's duty, and
     * every mole stands in the pot, the head's accounts or what the outlets gave.
     */
    @Test
    void aPentanePotWithItsColumnDistilsIsopentaneFirst() {
        var model = FluidThermodynamics.forNetwork(CATALOG, "icheme:refinery").on(List.of("Isopentane", "N-pentane", "Nitrogen"));
        // A fluid basis holds nitrogen, and water last (WorldTopologyLedger.FluidBasis).
        assertEquals(List.of("Isopentane", "N-pentane", "Nitrogen", "Water"), model.components());
        var design = new BatchStill.Design(VOLUME, BatchStill.Heater.SOLID, true, 4);
        var devices = List.of(pot(design),
                plain(1, 0, 10, Kind.GENERATOR), plain(101, 1, 10, Kind.PIPE), water(design), plain(201, 5, 10, Kind.PIPE), plain(2, 6, 10, Kind.VOID),
                outlet(DISTILLATE, 20), plain(301, 1, 20, Kind.PIPE), plain(3, 2, 20, Kind.VOID),
                outlet(VENT, 30), plain(302, 1, 30, Kind.PIPE), plain(4, 2, 30, Kind.VOID));
        var charge = per(model, new double[]{.5, .5, 0, 0}, 300, 150_000);
        var cold = water(model, 288.15, 300_000);
        var states = new HashMap<Long, PassiveNetwork.Reservoir>();
        states.put(POT, contents(model, charge, .6, true));
        states.put(1L, new PassiveNetwork.Reservoir(1, 0, cold, PassiveNetwork.NodeKind.GENERATOR));
        states.put(2L, new PassiveNetwork.Reservoir(2, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        for (long id : new long[]{DISTILLATE, VENT})
            states.put(id, new PassiveNetwork.Reservoir(id, 0, per(model, new double[]{1, 0, 0, 0}, 298.15, 150_000), PassiveNetwork.NodeKind.GENERATOR));
        for (long id : new long[]{3, 4})
            states.put(id, new PassiveNetwork.Reservoir(id, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        var basis = new WorldTopologyLedger.FluidBasis("icheme:refinery", model.components(), "still-test");
        var unit = StillUnit.built(POT, WATER, DISTILLATE, VENT, 0, design, model.componentCount()).withFiring(1).withReflux(2);
        var rig = new Rig(model, basis, devices, states, unit);
        rig.items = Integer.MAX_VALUE;
        double[] start = rig.pot();
        rig.run(240);
        print("pentanes", rig);
        var v = rig.view();
        assertEquals(BatchStill.Status.DISTILLING, v.status(), () -> rig.stills.statusLine(POT));
        assertTrue(rig.solves > 0, "the column solved");
        assertTrue(v.headTemperature() < v.potTemperature(), "the head is cooler than the pot: " + v.headTemperature() + " / " + v.potTemperature());
        assertEquals(1, v.condensed(), 1e-9, "the water keeps up");
        assertEquals(2, v.reflux(), 1e-9, "the column runs at the valve's reflux");
        assertTrue(v.distillateFlow() > 0, "the distillate flows");
        var out = rig.given.getOrDefault(DISTILLATE, new double[4]);
        assertTrue(out[0] > 0 && out[0] > 1.2 * out[1], "the distillate is isopentane-rich: " + Arrays.toString(out));
        assertTrue(rig.unit().condensed() > 0, "the condenser gave its duty to the water");
        assertTrue(rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(WATER), 0.) > 0, "the water side took heat");
        // Every mole: the pot's, the head's accounts and what each outlet gave.
        double[] now = rig.pot(), head = rig.unit().head().moles(), vent = rig.unit().venting().moles(), dGave = out, vGave = rig.given.getOrDefault(VENT, new double[4]);
        for (int c = 0; c < 2; c++) {
            double total = now[c] + head[c] + vent[c] + dGave[c] + vGave[c];
            assertEquals(start[c], total, 1e-6 * start[c], "component " + c + " conserved");
        }
    }

    /**
     * The condenser with light ends (CONDENSER_LIGHT_ENDS_REVIEW.md, option c): a pot still with its condenser on 15 °C water,
     * charged with a light oil holding propane. The condenser's outlet stands at the water's inlet plus the approach; the
     * propane it cannot hold there goes to the vent account, richer in it than the distillate; every mole stands in the pot,
     * the head's accounts or what the outlets gave. A warmer water, stepped through a topology event, reaches the next queued
     * job only: the step itself forces no solve, the jobs before it kept the old outlet and those after it take the new.
     */
    @Test
    void aLightOilsGasGoesToTheVentAndTheNextJobTakesTheWatersStep() {
        var model = FluidThermodynamics.forNetwork(CATALOG, "icheme:refinery").on(List.of("Propane", "N-pentane", "N-hexane", "Nitrogen"));
        assertEquals(List.of("Propane", "N-pentane", "Nitrogen", "N-hexane", "Water"), model.components());
        int n = model.componentCount();
        var design = new BatchStill.Design(VOLUME, BatchStill.Heater.SOLID, true, 0);
        var devices = List.of(pot(design),
                plain(1, 0, 10, Kind.GENERATOR), plain(101, 1, 10, Kind.PIPE), water(design), plain(201, 5, 10, Kind.PIPE), plain(2, 6, 10, Kind.VOID),
                outlet(DISTILLATE, 20), plain(301, 1, 20, Kind.PIPE), plain(3, 2, 20, Kind.VOID),
                outlet(VENT, 30), plain(302, 1, 30, Kind.PIPE), plain(4, 2, 30, Kind.VOID));
        var charge = per(model, new double[]{.05, .5, 0, .45, 0}, 300, 150_000);
        var states = new HashMap<Long, PassiveNetwork.Reservoir>();
        states.put(POT, contents(model, charge, .6, true));
        states.put(1L, new PassiveNetwork.Reservoir(1, 0, water(model, 288.15, 300_000), PassiveNetwork.NodeKind.GENERATOR));
        states.put(2L, new PassiveNetwork.Reservoir(2, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        for (long id : new long[]{DISTILLATE, VENT})
            states.put(id, new PassiveNetwork.Reservoir(id, 0, per(model, new double[]{0, 1, 0, 0, 0}, 298.15, 150_000), PassiveNetwork.NodeKind.GENERATOR));
        for (long id : new long[]{3, 4})
            states.put(id, new PassiveNetwork.Reservoir(id, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        var basis = new WorldTopologyLedger.FluidBasis("icheme:refinery", model.components(), "still-test");
        var unit = StillUnit.built(POT, WATER, DISTILLATE, VENT, 0, design, n).withFiring(1);
        var rig = new Rig(model, basis, devices, states, unit);
        rig.items = Integer.MAX_VALUE;
        double[] start = rig.pot();
        rig.run(240);
        print("light oil", rig);
        assertTrue(rig.solves > 0, "the column solved");
        for (var job : rig.jobs)
            assertTrue(job[1] == StillColumn.TOTAL_CONDENSER || Math.abs(job[1] - (288.15 + BatchStill.OUTLET_APPROACH)) < .1,
                    "the outlet: the total condenser's before the water's first record, then the water's inlet (its pipe warms it a little) plus the approach: " + job[1]);
        assertEquals(288.15 + BatchStill.OUTLET_APPROACH, rig.jobs.getLast()[1], .1, "the water's inlet plus the approach");
        var dGave = rig.given.getOrDefault(DISTILLATE, new double[n]);
        double[] vented = rig.unit().venting().moles().clone(), vGave = rig.given.getOrDefault(VENT, new double[n]);
        for (int c = 0; c < n; c++) vented[c] += vGave[c];
        double ventTotal = Arrays.stream(vented).sum(), distilled = Arrays.stream(dGave).sum() + Arrays.stream(rig.unit().head().moles()).sum();
        System.out.printf(Locale.ROOT, "light oil: vented %s, distillate %s, status %s%n", Arrays.toString(vented), Arrays.toString(dGave), rig.view().status());
        assertTrue(vented[0] > 0, "the propane the outlet cannot hold went to the vent account");
        assertTrue(distilled > 0, "the condenser's liquid reached the spout's account");
        assertTrue(vented[0] / ventTotal > dGave[0] / Arrays.stream(dGave).sum(), "the vent is richer in propane than the distillate");
        double[] now = rig.pot(), head = rig.unit().head().moles(), vent = rig.unit().venting().moles();
        for (int c : new int[]{0, 1, 3})
            assertEquals(start[c], now[c] + head[c] + vent[c] + dGave[c] + vGave[c], 1e-6 * start[c], "component " + c + " conserved");
        // The water's step: no solve forced, the next queued job takes it.
        int solves = rig.solves, queued = rig.jobs.size();
        long stepped = rig.epoch[0];
        rig.step(1, water(model, 298.15, 300_000));
        assertEquals(solves, rig.solves, "the step forces no solve");
        rig.run(60);
        assertTrue(rig.jobs.size() > queued, "jobs were queued after the step");
        for (int k = 0; k < queued; k++) assertTrue(rig.jobs.get(k)[1] < 298.15, "a job before the step kept its outlet");
        assertEquals(298.15 + BatchStill.OUTLET_APPROACH, rig.jobs.getLast()[1], .1, "a job after the step: the new water's inlet plus the approach");
        for (var job : rig.jobs.subList(queued, rig.jobs.size()))
            assertTrue(job[0] > stepped, "every later job was queued on the schedule, after the step");
    }

    /** An open still of {@code stages} trays at reflux {@code reflux} on the pilot's ethanol and water, charged with 60 L of a wash at 364 K. */
    private static Rig washStill(int stages, double reflux) {
        return washStill(com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.catalog(), com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.PACKAGE_ID, stages, reflux);
    }

    /** {@link #washStill(int, double)} on {@code id} of {@code catalog}: its view of ethanol and nitrogen, water last, amounts by id. */
    private static Rig washStill(com.wormzjl.icheme.science.material.MaterialCatalog catalog, String id, int stages, double reflux) {
        return washStill(catalog, id, stages, reflux, 1);
    }

    /** {@link #washStill(com.wormzjl.icheme.science.material.MaterialCatalog, String, int, double)} under time factor {@code factor}. */
    private static Rig washStill(com.wormzjl.icheme.science.material.MaterialCatalog catalog, String id, int stages, double reflux, int factor) {
        var model = FluidThermodynamics.forNetwork(catalog, id).on(List.of("Ethanol", "Nitrogen")).withTime(com.wormzjl.icheme.science.fluid.SimulationTime.of(factor));
        assertEquals(java.util.Set.of("Ethanol", "Nitrogen", "Water"), java.util.Set.copyOf(model.components()));
        assertEquals("Water", model.components().getLast());
        int e = model.components().indexOf("Ethanol"), w = model.components().indexOf("Water");
        double[] wash = new double[3], spirit = new double[3];
        wash[e] = WASH;
        wash[w] = 1 - WASH;
        spirit[e] = 1;
        var design = new BatchStill.Design(VOLUME, BatchStill.Heater.SOLID, true, stages);
        var devices = List.of(pot(design),
                plain(1, 0, 10, Kind.GENERATOR), plain(101, 1, 10, Kind.PIPE), water(design), plain(201, 5, 10, Kind.PIPE), plain(2, 6, 10, Kind.VOID),
                outlet(DISTILLATE, 20), plain(301, 1, 20, Kind.PIPE), plain(3, 2, 20, Kind.VOID),
                outlet(VENT, 30), plain(302, 1, 30, Kind.PIPE), plain(4, 2, 30, Kind.VOID));
        var states = new HashMap<Long, PassiveNetwork.Reservoir>();
        states.put(POT, contents(model, per(model, wash, 364, 150_000), .6, true));
        states.put(1L, new PassiveNetwork.Reservoir(1, 0, water(model, 288.15, 300_000), PassiveNetwork.NodeKind.GENERATOR));
        states.put(2L, new PassiveNetwork.Reservoir(2, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        for (long d : new long[]{DISTILLATE, VENT})
            states.put(d, new PassiveNetwork.Reservoir(d, 0, per(model, spirit, 298.15, 150_000), PassiveNetwork.NodeKind.GENERATOR));
        for (long d : new long[]{3, 4})
            states.put(d, new PassiveNetwork.Reservoir(d, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        var basis = new WorldTopologyLedger.FluidBasis(id, model.components(), "still-test");
        var unit = StillUnit.built(POT, WATER, DISTILLATE, VENT, 0, design, model.componentCount()).withFiring(1).withReflux(reflux);
        var rig = new Rig(model, basis, devices, states, unit);
        rig.items = Integer.MAX_VALUE;
        return rig;
    }

    /** A wash of 10 % ethanol by volume: 0.0332 by mole (BatchStillTest). */
    private static final double WASH = .0332;

    /**
     * W4: 60 L of a wash in an open pot (2981 mol, 98.975 ethanol), fired at 10.95 kW from 364 K. It warms to its bubble
     * point at the pot's ambient (about 365.9 K at 102 kPa; 365.72 K at 1 atm), gains its gas there and boils at about
     * 0.27 mol/s, following its bubble point as it strips. Its energy closes: the heat in is the pot's energy gained and what
     * left by its vent. (The network found no gas in an open pot below its height's ambient: W4_WASH_NO_BOIL.md.)
     */
    @Test
    void aWashInAnOpenPotBoilsAtItsBubblePoint() {
        var rig = washStill(0, 0);
        double[] onset = boil(rig);
        assertEquals(365.72, onset[0], .3, "the wash's bubble point");
        assertEquals(.27, onset[1], .03, "the boil-up: the firing over the latent heat");
    }

    /**
     * W6 G10 (batch 2026-11-11-aqueous-solvents): the same wash on the network package {@code icheme:refinery}, whose ethanol is
     * the main record: it boils from the pilot's bubble point (92.57 C at 1 atm, 365.72 K) at the pilot's boil-up.
     */
    @Test
    void aWashOnTheNetworkPackageBoilsAsOnThePilot() {
        var catalog = com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
        String network = catalog.presets().network().packageId();
        assertEquals("icheme:refinery", network);
        double[] onset = boil(washStill(catalog, network, 0, 0));
        double[] pilot = boil(washStill(0, 0));
        System.out.printf(Locale.ROOT, "W6 wash pot on %s: boils from %.4f K at %.5f mol/s (pilot %.4f K, %.5f mol/s)%n", network, onset[0], onset[1], pilot[0], pilot[1]);
        assertEquals(365.72, onset[0], .3, "the wash's bubble point");
        assertEquals(pilot[0], onset[0], 1e-6, "the pilot's onset");
        assertEquals(pilot[1], onset[1], 1e-6 * pilot[1], "the pilot's boil-up");
    }

    /**
     * The merge of main 0.27.1 (MAIN_MERGE_PLAN section 5): the wash of {@link #aWashInAnOpenPotBoilsAtItsBubblePoint} under
     * time factor 4 is fired on process time, so in a quarter of the ticks it takes the heat and boils off what it does in
     * 180 s at factor 1; the view keeps its compounds and its water (withTime on a view). The heat booked trails the run by
     * one cadence (100 ticks of play: 5 s of process time at factor 1, 20 s at 4), so each runs 5 s of play past 180 s of
     * process time: 3600 ticks at factor 1 and 900 at 4 are booked. The boil-off agrees within the coarser slices' 5 %.
     */
    @Test
    void aWashAtTimeFactorFourBoilsInAQuarterOfTheTicks() {
        var catalog = com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.catalog();
        String id = com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.PACKAGE_ID;
        int[] factors = {1, 4};
        double[] heat = new double[2], gone = new double[2];
        double rating = Double.NaN;
        for (int k = 0; k < 2; k++) {
            var rig = washStill(catalog, id, 0, 0, factors[k]);
            assertEquals(List.of("Ethanol", "Nitrogen", "Water"), rig.model.components());
            rating = rig.unit().design().rating();
            double start = Arrays.stream(rig.potNode().inventory().moles()).sum();
            rig.run(180.0 / factors[k] + 5);
            heat[k] = rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(POT), 0.);
            gone[k] = start - Arrays.stream(rig.potNode().inventory().moles()).sum();
        }
        System.out.printf(Locale.ROOT, "wash at factors 1 and 4: heat %.1f / %.1f J, boiled %.4f / %.4f mol%n", heat[0], heat[1], gone[0], gone[1]);
        assertEquals(rating * 180, heat[0], 1e-3 * rating * 180, "factor 1: 180 s of firing");
        assertEquals(heat[0], heat[1], 1e-9 * heat[0], "factor 4: the same heat in a quarter of the ticks");
        assertTrue(gone[0] > 10, "the wash boils: " + gone[0] + " mol");
        assertEquals(gone[0], gone[1], .05 * gone[0], "the same boil-off over the same process time");
    }

    /**
     * The pot of {@code rig} fired for 180 s in 10 s slices: the temperature where it gains its gas and its first boil-up, mol/s.
     * It never superheats, follows its bubble point once boiling, and its energy closes (heat in = the pot's gain and what left).
     */
    private static double[] boil(Rig rig) {
        var model = rig.model;
        var start = rig.potNode();
        double u0 = start.inventory().internalEnergy();
        double onset = Double.NaN, released = Double.NaN;
        double[] before = null;
        for (int k = 0; k < 18; k++) {
            rig.run(10);
            var node = rig.potNode();
            var s = node.state();
            boolean gas = FluidThermodynamics.holdsPhase(s, FluidThermodynamics.GAS);
            double[] moles = node.inventory().moles();
            var column = new StillColumn(model, s.pressure());
            double bubble = column.bubble(moles, s.temperature()).temperature();
            System.out.printf(Locale.ROOT, "W4 wash pot at %.0f s: %.3f K (bubble %.3f K at %.1f Pa), gas %b, %.3f mol%n", rig.epoch[0] / 20.0, s.temperature(), bubble,
                    s.pressure(), gas, Arrays.stream(moles).sum());
            if (gas && Double.isNaN(onset)) onset = s.temperature();
            else if (gas && Double.isNaN(released)) released = (Arrays.stream(before).sum() - Arrays.stream(moles).sum()) / 10;
            if (gas) assertEquals(bubble, s.temperature(), .5, "the boiling pot follows its bubble point");
            else assertTrue(s.temperature() < bubble + .5, "no superheat: " + s.temperature() + " over " + bubble);
            before = moles;
        }
        System.out.printf(Locale.ROOT, "W4 wash pot: boils from %.3f K, releases %.4f mol/s at first; energies %s%n", onset, released, rig.energies);
        var end = rig.potNode();
        double heat = rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(POT), 0.);
        double out = 0;
        for (var e : rig.energies.entrySet()) if (e.getKey().startsWith(POT + " ") && !e.getKey().endsWith("HEAT")) out += e.getValue();
        assertTrue(heat > 0);
        // What leaves the pot is booked negative: its vent and its exchanges.
        assertEquals(end.inventory().internalEnergy() - u0, heat + out, 1e-6 * heat, "the heat in is the pot's energy gained and what left");
        return new double[]{onset, released};
    }

    /**
     * W4: the same wash under four trays at reflux 3: the column carries the pot's water, so the head passes ethanol before
     * water (StillHeadTest: an ethanol share of 0.727 and water's 0.095), and every mole of ethanol and water stands in the
     * pot, the head's accounts or what the outlets gave.
     */
    @Test
    void aWashInAPlacedStillIsRectified() {
        var rig = washStill(4, 3);
        double[] start = rig.pot();
        rig.run(300);
        print("wash", rig);
        var v = rig.view();
        assertEquals(BatchStill.Status.DISTILLING, v.status(), () -> rig.stills.statusLine(POT));
        assertTrue(rig.solves > 0, "the column solved");
        assertTrue(v.headTemperature() < v.potTemperature(), "the head is cooler than the pot: " + v.headTemperature() + " / " + v.potTemperature());
        var head = rig.potNode().inventory().pool().head();
        assertNotNull(head, "the column's head is on the pot");
        System.out.printf(Locale.ROOT, "W4 wash in a still: head ethanol share %.4f, water share %.4f, status %s%n", head.recovery(0), head.recovery(2), rig.stills.statusLine(POT));
        assertEquals(.727, head.recovery(0), .05, "the head passes ethanol as StillHeadTest's");
        assertTrue(head.recovery(0) > 2 * head.recovery(2), "ethanol before water");
        var out = rig.given.getOrDefault(DISTILLATE, new double[3]);
        assertTrue(Arrays.stream(out).sum() > 0, "the distillate flows");
        double[] now = rig.pot(), held = rig.unit().head().moles(), vent = rig.unit().venting().moles(), vGave = rig.given.getOrDefault(VENT, new double[3]);
        for (int c : new int[]{0, 2}) {
            double total = now[c] + held[c] + vent[c] + out[c] + vGave[c];
            assertEquals(start[c], total, 1e-9 * start[c], "component " + c + " conserved");
        }
    }

    /** A diverter valve fed from the west joins the outlet it is set to: east, then south, then north. */
    @Test
    void aDiverterValveJoinsItsInletAndTheOutletItIsSetTo() {
        var model = FluidTestSupport.networkModel();
        var liquid = water(model, 300, 300_000);
        var states = Map.of(1L, new PassiveNetwork.Reservoir(1, 0, liquid, PassiveNetwork.NodeKind.GENERATOR),
                7L, new PassiveNetwork.Reservoir(7, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID),
                9L, new PassiveNetwork.Reservoir(9, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID),
                11L, new PassiveNetwork.Reservoir(11, 0, water(model, 300, 101_325), PassiveNetwork.NodeKind.VOID));
        assertEquals(List.of(EAST, SOUTH, NORTH), PhysicalFluidTopology.diverterOutlets(WEST));
        long[] voids = {7, 9, 11};
        for (int k = 0; k < 3; k++) {
            int joints = PhysicalFluidTopology.diverterJoints(WEST, k);
            assertEquals(k, PhysicalFluidTopology.diverterOutlet(WEST, joints));
            var diverter = new PhysicalFluidTopology.Device(5, at(1, 0, 0), Kind.DIVERTER_VALVE, WEST, STUB, new FlowControl.Passive(), List.of(), true,
                    PhysicalFluidTopology.Cutoff.NONE, false, joints);
            assertEquals(Kind.PIPE, diverter.hydraulicKind(), "a diverter is a pipe through its open pair");
            var devices = List.of(plain(1, 0, 0, Kind.GENERATOR), diverter,
                    plain(6, 2, 0, Kind.PIPE), plain(7, 3, 0, Kind.VOID),
                    new PhysicalFluidTopology.Device(8, at(1, 0, 1), Kind.PIPE, SOUTH, STUB, new FlowControl.Passive()), new PhysicalFluidTopology.Device(9, at(1, 0, 2), Kind.VOID, SOUTH, STUB, new FlowControl.Passive()),
                    new PhysicalFluidTopology.Device(10, at(1, 0, -1), Kind.PIPE, NORTH, STUB, new FlowControl.Passive()), new PhysicalFluidTopology.Device(11, at(1, 0, -2), Kind.VOID, NORTH, STUB, new FlowControl.Passive()));
            var compiled = PhysicalFluidTopology.compile(devices, states);
            var fed = compiled.islands().stream().map(PhysicalFluidTopology.Island::graph).filter(g -> g.reservoirs().stream().anyMatch(r -> r.id() == 1)).findFirst().orElseThrow();
            for (int j = 0; j < 3; j++) {
                long sink = voids[j];
                assertEquals(j == k, fed.reservoirs().stream().anyMatch(r -> r.id() == sink), "outlet " + k + ": void " + sink);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new PhysicalFluidTopology.Device(5, at(1, 0, 0), Kind.DIVERTER_VALVE, WEST, STUB, new FlowControl.Passive(), List.of(), true,
                PhysicalFluidTopology.Cutoff.NONE, false, PhysicalFluidTopology.ALL_FACES), "a diverter has one outlet open");
    }

    /** The still's facts survive the fluid facts' write and read: its devices, design, accounts, fire, settings and totals. */
    @Test
    void aStillsFactsRoundTripThroughTheFluidFacts() {
        var model = FluidTestSupport.networkModel();
        int m = model.componentCount();
        var design = new BatchStill.Design(.5, BatchStill.Heater.LIQUID, true, 3);
        double[] held = new double[m], vented = new double[m];
        held[0] = 1.25;
        vented[m - 1] = .5;
        var unit = new StillUnit(POT, WATER, DISTILLATE, VENT, 24, design.volume(), design.heater(), true, 3,
                new Account(held, -4.5e4), new Account(vented, 1.5e3), 0, .65, Double.POSITIVE_INFINITY, 3.2e7, 2.9e7);
        var still = new StillUnit(30, 0, 0, 0, 0, .1, BatchStill.Heater.SOLID, false, 0, Account.empty(m), Account.empty(m), 1.75e6, .4, StillUnit.DEFAULT_REFLUX, 1e5, 0);
        var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
        for (var d : List.of(pot(design), new PhysicalFluidTopology.Device(30, at(0, 5, 0), Kind.RESERVOIR, NORTH, STUB, new FlowControl.Passive(), List.of(), true,
                PhysicalFluidTopology.Cutoff.NONE, false, PhysicalFluidTopology.ALL_FACES, 0, 0, new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.POT, .4, 0))))
            active.put(d.id(), new WorldTopologyLedger.Registration(d, FluidDeviceSpec.nitrogen(CATALOG), 0));
        var empty = WorldTopologyLedger.Snapshot.empty(CATALOG);
        var ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
        ledger.still(unit);
        ledger.still(still);
        var bytes = FluidFacts.world(ledger.snapshot());
        var read = FluidFacts.world(bytes, 0, bytes.length);
        assertEquals(2, read.stills().size());
        for (var expected : List.of(unit, still)) {
            var actual = read.stills().stream().filter(u -> u.id() == expected.id()).findFirst().orElseThrow();
            assertEquals(List.of(expected.water(), expected.distillate(), expected.vent(), expected.burners()), List.of(actual.water(), actual.distillate(), actual.vent(), actual.burners()));
            assertEquals(expected.design(), actual.design());
            assertArrayEquals(expected.head().moles(), actual.head().moles(), 0);
            assertEquals(expected.head().energy(), actual.head().energy(), 0);
            assertArrayEquals(expected.venting().moles(), actual.venting().moles(), 0);
            assertEquals(expected.venting().energy(), actual.venting().energy(), 0);
            assertEquals(List.of(expected.burning(), expected.firing(), expected.reflux(), expected.heated(), expected.condensed()),
                    List.of(actual.burning(), actual.firing(), actual.reflux(), actual.heated(), actual.condensed()), "the fire, the settings and the totals");
        }
        var snapshot = read.snapshot(0, List.of(), solid -> {
            throw new AssertionError("no solids");
        });
        assertEquals(Set.of(POT, 30L), snapshot.stills().keySet(), "the ledger takes them back");
    }
}
