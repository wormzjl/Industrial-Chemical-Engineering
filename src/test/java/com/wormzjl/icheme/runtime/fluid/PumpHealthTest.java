package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.Npsh;
import com.wormzjl.icheme.science.machine.Wear;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W10b (build plan revision 2, section 3.3, owner decision D34): an in-line pump draws water at 298.15 K from a supply and
 * delivers it to a void at 1 atm. Fed at about twice its NPSH3 it cavitates at the erosion peak, books its wear at its
 * commits and, certified, on the cadence, fails at 0, wakes its island and stands closed ({@code FAILED} plan); a repair
 * makes it new and it runs again. Fed at the atmosphere it is clean (σ near 6) and keeps its health.
 */
class PumpHealthTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry FAT = new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    /** The pump's set flow, m³/s: its NPSH3 is 1.68 m at the 2-pole speed (Npsh.forDuty). */
    private static final double FLOW = .01;
    /** Lives short enough for a test: 60 s at the erosion peak, 30 s dry. */
    private static final Wear.Settings FAST = new Wear.Settings(true, 1 / 60.0, .5);

    private FluidThermodynamics.State water(double pressure) {
        var n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return model.flashTP(298.15, pressure, n, () -> {
        });
    }

    private final class Rig {
        final long[] epoch = {0};
        final Map<Long, IslandCoordinator.Attempt> attempts = new LinkedHashMap<>();
        final Map<Long, ProcessSolveServices.FluidIslandCommand> commands = new HashMap<>();
        final IslandCoordinator coordinator;
        final PumpHealth health;
        long requests;

        Rig(double supply) {
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
            var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
            var trains = new ShaftTrains(ledger, device -> device == 1 ? 1L : null, coordinator);
            health = new PumpHealth(ledger, device -> device == 1 ? 1L : null, coordinator, device -> device == 1, device -> device == 1 ? new FlowControl.Pump(FLOW, 5e5) : null, () -> FAST);
            trains.health(health);
            trains.host(ShaftTrains.Host.NONE);
            coordinator.shafts(trains);
            var line = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, water(supply), PassiveNetwork.NodeKind.GENERATOR),
                    new PassiveNetwork.Reservoir(2, 0, water(101325), PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10, 0, 1, FAT, new FlowControl.Pump(FLOW, 5e5))));
            coordinator.register(new IslandCoordinator.Snapshot(1, 0, line, new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model);
        }

        void tick() {
            epoch[0]++;
            coordinator.tick();
            while (!attempts.isEmpty()) {
                for (long request : List.copyOf(attempts.keySet())) {
                    var attempt = attempts.remove(request);
                    var command = commands.remove(request);
                    coordinator.completed(attempt, Optional.of((ProcessSolveServices.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER)));
                }
                coordinator.pump();
            }
            if (epoch[0] % PumpHealth.CADENCE == 0) health.cadence();
            if (health.hasEvents()) health.apply();
        }

        double flow() {
            return coordinator.observe(1).lastResult().map(r -> r.endpointFlows()[0]).orElse(Double.NaN);
        }

        double ratio() {
            return coordinator.observe(1).lastResult().orElseThrow().observations().getFirst().npshRatio();
        }
    }

    /** The supply pressure that puts the pump's NPSHA at {@code sigma} times its NPSH3 at its set flow. */
    private double supplyAt(double sigma) {
        var s = water(101325);
        double rho = s.mass() / s.volume();
        return model.saturationPressure(298.15) + sigma * rho * 9.80665 * Npsh.forDuty(FLOW, 2950).required(FLOW);
    }

    @Test
    void aCavitatingPumpWearsToFailedStandsClosedAndARepairRestoresIt() {
        var rig = new Rig(supplyAt(2.06));
        for (int i = 0; i < 400; i++) rig.tick();
        assertTrue(rig.flow() > 9, "it delivers its set flow: " + rig.flow() + " kg/s");
        assertEquals(2.06, rig.ratio(), .1, "it runs at the erosion peak");
        // W10c: its mover state's crackle byte is its committed I_c(σ), near full; a set-flow pump has no churn read.
        assertEquals(Math.round(255 * Wear.intensity(rig.ratio())), rig.health.crackle(1), "the crackle follows I_c");
        assertTrue(rig.health.crackle(1) > 250, "loud at the peak: " + rig.health.crackle(1));
        assertEquals(0, rig.health.churn(1));
        double rate = rig.health.rate(1);
        assertEquals(Wear.rate(rig.ratio(), false, FAST), rate, 1e-12, "it books its commit's rate");
        double worn = rig.health.health(1);
        assertTrue(worn < 1 && worn > 0, "it has worn: " + worn);
        assertFalse(rig.health.failed(1));
        assertTrue(rig.health.status(1).startsWith("HEALTH "), rig.health.status(1));
        // It wears to 0 at about its 60 s life, fails at the next cadence and stands closed.
        for (int i = 0; i < 2400 && !rig.health.failed(1); i++) rig.tick();
        assertTrue(rig.health.failed(1), "it failed: " + rig.health.health(1));
        assertEquals(0, rig.health.health(1));
        assertEquals("FAILED / HEALTH 0 %", rig.health.status(1));
        for (int i = 0; i < 300; i++) rig.tick();
        assertEquals(0, rig.flow(), 1e-9, "a failed pump passes nothing");
        assertEquals(0, rig.health.crackle(1), "a stopped pump is silent");
        assertTrue(rig.coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Pump p && !p.enabled() && p.requestedVolumeFlow() == FLOW && p.maximumAddedPressure() == 5e5,
                "it is switched off with its settings kept: the closure is a slice input");
        assertEquals(Map.of(1L, 0.0), rig.health.facts(), "health is a fact");
        // A repair: accepted, the kit is taken, the pump is new and runs again.
        var outcome = new ArrayList<PumpHealth.Outcome>();
        int[] taken = {0};
        rig.health.queue(new PumpHealth.Repair(1, () -> ++taken[0] > 0, outcome::add));
        rig.tick();
        assertEquals(List.of(PumpHealth.Outcome.REPAIRED), outcome);
        assertEquals(1, taken[0], "one kit");
        assertFalse(rig.health.failed(1));
        assertEquals(1, rig.health.health(1), "new again");
        assertTrue(rig.health.facts().isEmpty());
        for (int i = 0; i < 300; i++) rig.tick();
        assertTrue(rig.health.health(1) < 1 && rig.health.health(1) > .7, "cavitating, it wears again: " + rig.health.health(1));
        assertTrue(rig.flow() > 9, "it runs again: " + rig.flow());
        assertTrue(rig.coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Pump p && p.enabled(), "switched back on");
    }

    @Test
    void aCleanPumpKeepsItsHealthAndAFullPumpRefusesAKit() {
        var rig = new Rig(101325);
        for (int i = 0; i < 2000; i++) rig.tick();
        assertTrue(rig.flow() > 9, "it delivers: " + rig.flow());
        assertTrue(rig.ratio() > 4, "its NPSHA is past inception: " + rig.ratio());
        assertEquals(1, rig.health.health(1));
        assertEquals(0, rig.health.rate(1));
        assertEquals(0, rig.health.crackle(1), "a clean pump does not crackle (W10c)");
        assertTrue(rig.health.facts().isEmpty(), "a new pump carries no entry");
        assertEquals("HEALTH 100 %", rig.health.status(1));
        var outcome = new ArrayList<PumpHealth.Outcome>();
        int[] taken = {0};
        rig.health.queue(new PumpHealth.Repair(1, () -> ++taken[0] > 0, outcome::add));
        rig.health.queue(new PumpHealth.Repair(2, () -> ++taken[0] > 0, outcome::add));
        rig.tick();
        assertEquals(List.of(PumpHealth.Outcome.FULL, PumpHealth.Outcome.NOT_A_PUMP), outcome);
        assertEquals(0, taken[0], "no kit taken");
    }
}
