package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W6's headless worlds (batch 2026-11-11-minecraft-time-delta): the gate rigs of {@link ColumnRunTest} and
 * {@link ExchangerCouplingTest} on a model and coordinator at factor 4 and 72, each run for one hour of process time
 * ({@code SimulationTime.ticks(3600)} online ticks). Each must hold nothing and close its mass and energy with the rig's
 * own checks. Not a gate: tools/minecraft-time-delta/worlds/worlds.init.gradle attaches it to one test run.
 */
class TimeScaleWorldsTest {
    private static final double HOUR = 3600;

    @AfterEach void reset() {EventPathWorld.time = SimulationTime.ONLINE;}

    private static Method method(Class<?> owner, String name, Class<?>... types) throws ReflectiveOperationException {
        var m = owner.getDeclaredMethod(name, types);m.setAccessible(true);return m;
    }
    private static Object part(Object record, String name) throws ReflectiveOperationException {return method(record.getClass(), name).invoke(record);}

    /** ColumnRunTest's running column: a 0.5 mol/s nitrogen feed, its product to a tank, one hour of process time. */
    @ParameterizedTest @ValueSource(ints = {4, 72})
    void aRunningColumnRunsAnHourHoldingNothingAndClosing(int factor) throws Exception {
        var time = SimulationTime.of(factor);EventPathWorld.time = time;
        var test = new ColumnRunTest();
        Object r = method(ColumnRunTest.class, "rig", int.class).invoke(test, 0);
        var world = (EventPathWorld) part(r, "world");var counting = (ColumnRunTest.Counting) part(r, "counting");
        long unit = (long) part(r, "unit"), feed = (long) part(r, "feed"), product = (long) part(r, "product");
        assertEquals(factor, world.model.time().factor());
        long start = world.ledger.onlineTick(), ticks = Math.round(time.ticks(HOUR));
        world.runTo(start + ticks);
        int m = world.model.componentCount();var u = world.ledger.column(unit);
        method(ColumnRunTest.class, "closes", ColumnRunTest.Counting.class, ColumnUnit.class, long.class, long.class, int.class).invoke(null, counting, u, feed, product, m);
        double taken = u.totals().taken().total(), made = u.totals().produced().total();
        System.out.printf(Locale.ROOT, "COLUMN factor=%d ticks=%d processS=%.0f taken=%.2f mol (%.3f mol/s of process) produced=%.2f condenser=%.4g J held=%d status=%s%n",
                factor, ticks, time.seconds(ticks), taken, taken / time.seconds(ticks), made, u.totals().condenser(), world.held.size(), world.columns.status(unit));
        double given = 0;for (double x : counting.moles.getOrDefault(product, new double[m])) given += x;
        double outlet = u.station(Port.DISTILLATE, 0).account().total(), tankMoles = 0, tankPressure = 0;long tank = (long) part(r, "tank");
        for (var s : world.coordinator.snapshots()) for (var n : s.graph().reservoirs()) if (n.id() == tank) {for (double x : n.inventory().moles()) tankMoles += x;tankPressure = n.state().pressure();}
        System.out.printf(Locale.ROOT, "COLUMN factor=%d given=%.2f mol outletAccount=%.2f mol tank=%.2f mol at %.0f Pa%n", factor, given, outlet, tankMoles, tankPressure);
        assertTrue(world.held.isEmpty(), "no island held: " + world.held);
    }

    /** The failing heat case traced: the hot outlet, the transit and each island's status every 2 s of play over 3 h of process time at 72. */
    @org.junit.jupiter.api.Test
    void traceTheUnequalCadencePairAt72WithCertificates() throws Exception {
        var time = SimulationTime.of(72);EventPathWorld.time = time;
        var test = new ExchangerCouplingTest();
        Class<?> rigClass = Class.forName(ExchangerCouplingTest.class.getName() + "$Rig");
        Object rig = method(ExchangerCouplingTest.class, "waterPair", int.class, int.class, CertificatePolicy.class).invoke(test, 20, 400, CertificatePolicy.defaults());
        var coordinator = (IslandCoordinator) rigClass.getDeclaredField("coordinator").get(rig);
        long hot = rigClass.getDeclaredField("hotIsland").getLong(rig), cold = rigClass.getDeclaredField("coldIsland").getLong(rig);
        var run = method(rigClass, "run", double.class);var transit = method(rigClass, "transit");
        for (int k = 1; k <= 75; k++) {
            run.invoke(rig, 2.);
            var h = coordinator.observe(hot);var c = coordinator.observe(cold);
            var p = h.graph().pipes().stream().filter(x -> x.id() == PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
            double out = h.graph().reservoirs().get(p.second()).state().temperature();
            System.out.printf(Locale.ROOT, "TRACE play=%3ds hotOut=%.3f K transit=%.4g J hot=[%s] cold=[%s]%n", 2 * k, out, (double) transit.invoke(rig),
                    h.status().substring(0, Math.min(60, h.status().length())), c.status().substring(0, Math.min(60, c.status().length())));
        }
    }

    /** ExchangerCouplingTest's water pair, two islands at equal and unequal cadences, one hour of process time. */
    @ParameterizedTest @ValueSource(ints = {1, 4, 72})
    void aHeatPairInTwoIslandsRunsAnHourAtTheSteadyStateAndClosing(int factor) throws Exception {
        var time = SimulationTime.of(factor);EventPathWorld.time = time;
        var test = new ExchangerCouplingTest();
        Class<?> rigClass = Class.forName(ExchangerCouplingTest.class.getName() + "$Rig");
        var pair = method(ExchangerCouplingTest.class, "waterPair", int.class, int.class, CertificatePolicy.class);
        var failures = new ArrayList<String>();
        for (int[] cadences : new int[][]{{100, 100}, {20, 400}}) {
            for (var policy : List.of(CertificatePolicy.disabled(), CertificatePolicy.defaults())) {
                String label = "factor " + factor + " cadences " + cadences[0] + "/" + cadences[1] + (policy.equals(CertificatePolicy.disabled()) ? " certificates off" : " certificates on");
                Object rig = pair.invoke(test, cadences[0], cadences[1], policy);
                var coordinator = (IslandCoordinator) rigClass.getDeclaredField("coordinator").get(rig);
                assertEquals(factor, coordinator.time().factor());
                var held = new ArrayList<String>();
                method(rigClass, "run", double.class).invoke(rig, HOUR / factor);
                for (var s : coordinator.snapshots()) if (s.status().startsWith("HELD")) held.add(s.id() + ": " + s.status());
                System.out.print("HEAT " + label + ": ");
                try {
                    method(ExchangerCouplingTest.class, "assertSteady", rigClass, double.class, String.class).invoke(test, rig, .05, label);
                } catch (java.lang.reflect.InvocationTargetException e) {failures.add(e.getCause().getMessage());}
                try {
                    method(ExchangerCouplingTest.class, "assertLedger", rigClass, double.class, String.class).invoke(null, rig, 0., label);
                } catch (java.lang.reflect.InvocationTargetException e) {failures.add(e.getCause().getMessage());}
                if (!held.isEmpty()) failures.add(label + ": held " + held);
            }
        }
        failures.forEach(f -> System.out.println("HEAT FAILURE " + f));
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }
}
