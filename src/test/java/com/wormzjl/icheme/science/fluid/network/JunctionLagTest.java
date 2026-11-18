package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunctionLagTest {
    @Test void passiveHotAndColdFrontsReachTheReceivingPortAndKeepTheLifetimeLedger() {
        var model = FluidTestSupport.networkModel().withTime(new SimulationTime(4));
        var oil = TiaJuanaTestCatalog.worldCrude();
        var sink = model.flashTP(465, 260_000, oil, () -> {});
        var run = new PipeResistance.Geometry(1, .6, .000045, 0);
        var valve = new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 83, PortPlan.COLUMN_SENSOR, 0);
        var graph = PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, sink, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, sink, PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3, 0, sink, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(5, 0, 1, run), new PassiveNetwork.Pipe(6, 1, 2, run).withControl(valve))), model);
        var initial = graph.reservoirs().get(1).inventory();
        double externalEnergy = 0;
        var external = new double[oil.length];
        var solver = new PassiveIntervalSolver(model);
        PassiveIntervalSolver.Result last = null;
        // Change the largest crude cut along with temperature and rate, so the D46 component ledger
        // actually moves and the two pressure-enthalpy flashes each need their own composition.
        var richer = oil.clone();
        int largest = 0;
        for (int c = 1; c < oil.length; c++) if (oil[c] > oil[largest]) largest = c;
        richer[largest] *= 1.25;
        double change = Math.abs(richer[largest]/java.util.Arrays.stream(richer).sum()
                - oil[largest]/java.util.Arrays.stream(oil).sum());
        for (int i = 0; i < 6; i++) {
            boolean enriched = i % 2 == 0;
            boolean hot = enriched && i < 4;
            double rate = enriched ? 83 : 10;
            var source = model.flashTP(hot ? 638.15 : 465, 302_000, enriched ? richer : oil, () -> {});
            var nodes = new java.util.ArrayList<>(graph.reservoirs());
            nodes.set(0, new PassiveNetwork.Reservoir(1, 0, source, PassiveNetwork.NodeKind.GENERATOR));
            graph = new PassiveNetwork(nodes, graph.pipes());
            solver.replayStart(graph, last);
            last = solver.solve(graph, 20, new PassiveIntervalSolver.Settings(4, 20, 1024), () -> {}, 4,
                    Map.of(), Map.of(3L, new PortPlan(3, 2, rate, 0, null)));
            graph = last.graph();
            double sent=0, received=0;
            var supplied = new double[oil.length];
            var taken = new double[oil.length];
            for (var b : last.boundaries()) {
                externalEnergy += b.totalEnergyJoule();
                var n=b.moles();
                for(int c=0;c<n.length;c++) external[c]+=n[c];
                if (b.nodeId()==1) {
                    sent+=b.totalEnergyJoule();
                    for(int c=0;c<n.length;c++) supplied[c]+=n[c];
                }
                if (b.nodeId()==3) {
                    received-=b.totalEnergyJoule();
                    for(int c=0;c<n.length;c++) taken[c]-=n[c];
                }
            }
            assertEquals(rate,last.averageMassFlows()[1],.05);
            var predicted=model.fluidPH(source.temperature(),260_000,sent,supplied,()->{});
            var actual=model.fluidPH(source.temperature(),260_000,received,taken,()->{});
            assertNotNull(predicted);
            assertNotNull(actual);
            double inletFraction = supplied[largest]/java.util.Arrays.stream(supplied).sum();
            double feedFraction = taken[largest]/java.util.Arrays.stream(taken).sum();
            assertEquals(inletFraction, feedFraction, 1e-3*change, "first-slice composition front " + i);
            var required = model.flashTP(predicted.temperature(), 260_000, taken, () -> {});
            System.out.printf(java.util.Locale.ROOT, "PASSIVE_FRONT step=%d inlet_K=%.6f feed_K=%.6f delivered_h=%.3f feed_h=%.3f isothermal_feed_h=%.3f%n",
                    i, predicted.temperature(), actual.temperature(), sent/predicted.mass(), received/actual.mass(), required.enthalpy()/required.mass());
            assertEquals(predicted.temperature(),actual.temperature(),.5,"passive front "+i);
            var stock=graph.reservoirs().get(1).inventory();
            assertEquals(externalEnergy,stock.internalEnergy()-initial.internalEnergy(),1e-5*Math.max(1,Math.abs(externalEnergy)));
            var now=stock.moles();var before=initial.moles();
            for(int c=0;c<now.length;c++) assertEquals(external[c],now[c]-before[c],1e-9*Math.max(1,before[c]));
        }
    }

    @Test void twoInletsAndTwoOutletsCarryTheCombinedHeatOfTheFirstSlice() {
        var model = FluidTestSupport.networkModel().withTime(new SimulationTime(4));
        var oil = TiaJuanaTestCatalog.worldCrude();
        var cold = model.flashTP(465, 302_000, oil, () -> {});
        var hot = model.flashTP(638.15, 302_000, oil, () -> {});
        var sink = model.flashTP(465, 260_000, oil, () -> {});
        var run = new PipeResistance.Geometry(1, .6, .000045, 0);
        var graph = new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, hot, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, cold, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3, 0, cold, PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(4, 0, sink, PassiveNetwork.NodeKind.VOID),
                new PassiveNetwork.Reservoir(5, 0, sink, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(6, 0, 2, run), new PassiveNetwork.Pipe(7, 1, 2, run),
                        new PassiveNetwork.Pipe(8, 2, 3, run), new PassiveNetwork.Pipe(9, 2, 4, run)));
        var result = new PassiveIntervalSolver(model).solve(graph, 20, PassiveIntervalSolver.Settings.defaults(), () -> {});
        double sent = 0;
        double[] sentMoles = new double[oil.length];
        for (double flow : result.averageMassFlows()) assertTrue(flow > 0, "each manifold branch flows forward");
        for (var b : result.boundaries()) if (b.nodeId() == 1 || b.nodeId() == 2) {
            sent += b.totalEnergyJoule();
            var moles = b.moles();
            for (int c = 0; c < moles.length; c++) sentMoles[c] += moles[c];
        }
        var expected = model.fluidPH(550, sink.pressure(), sent, sentMoles, () -> {});
        assertNotNull(expected);
        for (long outlet : new long[]{4, 5}) {
            double received = 0;
            double[] taken = new double[oil.length];
            for (var b : result.boundaries()) if (b.nodeId() == outlet) {
                received -= b.totalEnergyJoule();
                var moles = b.moles();
                for (int c = 0; c < moles.length; c++) taken[c] -= moles[c];
            }
            var actual = model.fluidPH(550, sink.pressure(), received, taken, () -> {});
            assertNotNull(actual);
            assertEquals(expected.temperature(), actual.temperature(), .5, "outlet " + outlet);
        }
    }

    @Test void theFirstCommittedFeedSliceReceivesTheHeatActuallyDeliveredByTheCoil() {
        var model = FluidTestSupport.networkModel().withTime(new SimulationTime(4));
        var oil = TiaJuanaTestCatalog.worldCrude();
        var inlet = model.flashTP(465, 302_000, oil, () -> {});
        var sink = model.flashTP(465, 260_000, oil, () -> {});
        var run = new PipeResistance.Geometry(1, .6, .000045, 0);
        var valve = new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW, 83, PortPlan.COLUMN_SENSOR, 0);
        var graph = new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, inlet, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, sink, PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3, 0, sink, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(5, 0, 1, run).withHeat(new FiredCoil(638.15, 80e6, 1, 0)),
                        new PassiveNetwork.Pipe(6, 1, 2, run).withControl(valve)));
        var result = new PassiveIntervalSolver(model).solve(graph, 20, new PassiveIntervalSolver.Settings(4, 20, 1024), () -> {}, 4,
                Map.of(), Map.of(3L, new PortPlan(3, 2, 83, 0, null)));
        assertEquals(20, result.advancedSeconds());
        assertEquals(83, result.averageMassFlows()[0], .05);
        double input = 0, delivered = 0, received = 0;
        var composition = new double[oil.length];
        for (var b : result.boundaries()) {
            if (b.nodeId() == 1) input += b.totalEnergyJoule();
            if (b.nodeId() == 5 && b.kind() == ConservativeTransport.BoundaryTransfer.Kind.HEAT) delivered += b.totalEnergyJoule();
            if (b.nodeId() == 3) {
                received -= b.totalEnergyJoule();
                var n = b.moles();
                for (int i = 0; i < n.length; i++) composition[i] -= n[i];
            }
        }
        assertTrue(delivered > 0 && received != 0);
        double pressure = result.graph().reservoirs().get(2).state().pressure();
        var expected = model.fluidPH(638.15, pressure, input + delivered, composition, () -> {});
        var actual = model.fluidPH(638.15, pressure, received, composition, () -> {});
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.temperature(), actual.temperature(), .5, "temperature equivalent of the booked feed enthalpy");
        var endpoint = result.graph().reservoirs().get(1).state();
        var expectedAtJunction = model.fluidPH(638.15, endpoint.pressure(), input + delivered, composition, () -> {});
        assertNotNull(expectedAtJunction);
        assertEquals(expectedAtJunction.temperature(), endpoint.temperature(), .5, "junction endpoint");
        System.out.printf(java.util.Locale.ROOT, "JUNCTION_LAG expected_K=%.5f feed_K=%.5f flow=%.4f substeps=%d rejected=%d%n",
                expected.temperature(), actual.temperature(), result.averageMassFlows()[0], result.acceptedSubsteps(), result.rejectedSubsteps());
    }
}
