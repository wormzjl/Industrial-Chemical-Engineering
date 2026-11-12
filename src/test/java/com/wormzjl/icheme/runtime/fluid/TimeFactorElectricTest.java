package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.machine.CreateCoupling;
import com.wormzjl.icheme.science.machine.ElectricStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FE at the play-time rate (batch 2026-11-11-minecraft-time-delta, W4, owner T2): an FE carries {@code joulesPerFE x factor}
 * of process energy, so at factors 4 and 72 a motor's store holds the same FE, its port moves the same FE per tick and its
 * page shows the same FE per tick as at factor 1, while the store books the process joules; and the round trip FE, shaft,
 * Create stress, FE gains nothing at any factor.
 */
class TimeFactorElectricTest {
    private static final double JPF = ElectricStores.Settings.DEFAULT.joulesPerFE();

    /** A 30 kW two-pole motor's store on a coordinator under {@code factor}, as the shaft trains register it. */
    private record Motor(ElectricStores stores, long id) {}

    private static Motor motor(int factor) {
        var coordinator = new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers() {return 1;}
            public long nextRequestId() {return 1;}
            public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveServices.FluidIslandCommand command) {return false;}
            public void cancel(long request) {}
        }, changed -> {}, () -> 0L, new IslandCoordinator.Settings(2_000_000_000L, 1_500_000_000L, 64, false, 100, CertificatePolicy.defaults()).withTime(SimulationTime.of(factor)), () -> 0L);
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        long id = ledger.reserve(1);
        var cell = new PhysicalFluidTopology.Position(EventPathWorld.DIM, 0, 64, 0);
        ledger.shaft(new ShaftUnit(id, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR, cell, 0, List.of(new ShaftUnit.End(cell, 1, 0, 0, true)), 0, 30e3, 3000, 2,
                com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED, ShaftUnit.Settings.DEFAULT, com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE, null));
        var stores = new ElectricStores(ledger, device -> null, coordinator, () -> ElectricStores.Settings.DEFAULT);
        var trains = new ShaftTrains(ledger, device -> null, coordinator);
        trains.electric(stores);
        trains.host(ShaftTrains.Host.NONE);
        trains.decide();
        return new Motor(stores, id);
    }

    @Test void aStoreHoldsAndMovesTheSameFeAtAnyFactorWhileBookingProcessJoules() {
        var one = motor(1);
        int capacity = one.stores().capacity(one.id()), perTick = one.stores().receive(one.id(), Integer.MAX_VALUE, true);
        assertTrue(capacity > 0 && perTick > 0, "a motor's store: " + capacity + " FE, " + perTick + " FE/t");
        for (int factor : new int[]{4, 72}) {
            var m = motor(factor);
            assertEquals(capacity, m.stores().capacity(m.id()), 1, "factor " + factor + ": the same FE capacity");
            assertEquals(perTick, m.stores().receive(m.id(), Integer.MAX_VALUE, true), 1, "factor " + factor + ": the same FE per tick at the port");
            int taken = m.stores().receive(m.id(), 100, false);
            assertEquals(100, taken);
            assertEquals(100 * JPF * factor, m.stores().view(m.id()).joules(), 1e-9, "factor " + factor + ": an FE carries joulesPerFE x factor of process energy");
            assertEquals(100, m.stores().stored(m.id()), "and reads back as the FE it was");
        }
    }

    /** A 1 MW draw is 20 000 FE/t at the defaults at any factor, and no round trip through Create gains energy. */
    @Test void aMegawattIsTwentyThousandFePerTickAndTheCreateRoundTripGainsNothing() {
        double stressPerFe = CreateCoupling.STRESS_PER_FE, watts = 1e6;
        for (int factor : new int[]{1, 4, 72}) {
            var time = SimulationTime.of(factor);
            // FE bought per tick for a process power: its process joules over the joules an FE carries.
            double fePerTick = watts * time.tickSeconds() / (JPF * factor);
            assertEquals(20_000, fePerTick, 1e-6, "factor " + factor);
            // That power on the shaft into Create: its stress at the coupling's rate, and the FE Create's generators give for it.
            double stress = watts / CreateCoupling.wattsPerStress(JPF, stressPerFe), back = stress / stressPerFe;
            assertEquals(fePerTick, back, 1e-9 * fePerTick, "factor " + factor + ": FE -> shaft -> Create -> FE returns what it took");
        }
        assertEquals(ElectricStore.fe(1e6 / 20, JPF), 20_000, 1e-9, "the play-time rate: watts over 20 joulesPerFE");
    }
}
