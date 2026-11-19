package com.wormzjl.icheme.science.fluid.network;

import com.google.gson.Gson;
import com.wormzjl.icheme.fluid.support.ConservationAssertions;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/** Physical initial-value data, not a Minecraft checkpoint: equal outlet setpoints with phase-selective draws. */
class PressureValveContinuationTest extends PortFixture {
    private record Node(long id,double elevation,PassiveNetwork.NodeKind kind,double capacity,double height,boolean open,
                        double[] moles,double energy,double temperature,double pressure,double[] liquid,double[] vapor,
                        double waterLiquid,double waterVapor,double hydrocarbonPressure) {}
    private record Control(String kind,double pressure) {}
    private record Pipe(long id,int first,int second,double[][] sections,Control control,int[] ports,int blocked) {}
    private record Endpoint(double seconds,double[] massFlows,int accepted,int rejected,double pumpWork,
                            List<FlowControl.Mode> modes,double[] heads,double[] endpointFlows) {}
    private record Fixture(List<String> components,List<Node> nodes,List<Pipe> pipes,Endpoint endpoint) {}
    private record Start(PassiveNetwork graph,PassiveIntervalSolver.Result endpoint) {}
    /** The network model on the fixture's own compound list, as an island holding only them runs (batch 2026-11-04-refinery-compounds, V2). */
    PressureValveContinuationTest()throws IOException{super(com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel().on(fixture().components().stream().filter(id->!id.equals("Water")).toList()));}
    private static Fixture fixture()throws IOException {
        try(var input=Objects.requireNonNull(PressureValveContinuationTest.class.getResourceAsStream("/fluid/pressure-valve-continuation.json"));
            var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){return new Gson().fromJson(reader,Fixture.class);}
    }

    private Start start()throws IOException {
        var fixture=fixture();
        assertEquals(model.components(),fixture.components());
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(var n:fixture.nodes()) {
            var state=TestPhases.state(model,n.temperature(),n.pressure(),n.liquid(),n.vapor(),n.waterLiquid(),n.waterVapor());
            var inventory=new PassiveNetwork.Inventory(n.capacity(),n.moles(),n.energy(),SolidInventory.EMPTY,n.height(),n.open());
            nodes.add(new PassiveNetwork.Reservoir(n.id(),n.elevation(),state,n.kind(),inventory));
        }
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var p:fixture.pipes()) {
            var geometry=Arrays.stream(p.sections()).map(g->new PipeResistance.Geometry(g[0],g[1],g[2],g[3])).toList();
            FlowControl control=p.control().kind().equals("valve")?new FlowControl.PressureValve(p.control().pressure()):new FlowControl.Passive();
            pipes.add(new PassiveNetwork.Pipe(p.id(),p.first(),p.second(),geometry,control,p.blocked(),null,
                    PassiveNetwork.PhasePort.values()[p.ports()[0]],PassiveNetwork.PhasePort.values()[p.ports()[1]]));
        }
        var graph=new PassiveNetwork(nodes,pipes);var e=fixture.endpoint();
        return new Start(graph,new PassiveIntervalSolver.Result(graph,e.seconds(),e.massFlows(),e.accepted(),e.rejected(),e.pumpWork(),
                List.of(),Map.of(),e.modes(),e.heads(),e.endpointFlows(),List.of()));
    }

    @Test void equalSetpointOutletsContinueMixedDischargeWithoutLosingMaterialOrEnergy()throws IOException {
        var initial=start();var graph=initial.graph();var previous=initial.endpoint();var solver=new PassiveIntervalSolver(model);
        var before=owned(graph);double energy=ownedEnergy(graph),externalEnergy=0,energyTurnover=0;
        var external=new double[mw.length];var turnover=new double[mw.length];double[] finalFlows=null;
        for(int interval=0;interval<12;interval++) {
            solver.replayStart(graph,previous);
            var result=solver.solve(graph,4.5,PassiveIntervalSolver.Settings.defaults(),NOOP);
            assertEquals(4.5,result.advancedSeconds(),0,"whole intervals must commit");
            graph=result.graph();previous=result;
            for(var b:result.boundaries()) {
                var n=b.moles();for(int c=0;c<n.length;c++){external[c]+=n[c];turnover[c]+=Math.abs(n[c]);}
                externalEnergy+=b.totalEnergyJoule();energyTurnover+=Math.abs(b.totalEnergyJoule());
            }
            externalEnergy+=result.pumpWorkJoule();energyTurnover+=Math.abs(result.pumpWorkJoule());
            ConservationAssertions.components(before,owned(graph),external,turnover);
            ConservationAssertions.energy(energy,ownedEnergy(graph),externalEnergy,energyTurnover);
            var bottom=result.pipeTransfers().stream().filter(p->p.pipeId()==7798601278550083437L).findFirst().orElseThrow().forward();
            assertTrue(Arrays.stream(bottom.phaseMoles()[0]).sum()>0,"bottom outlet transports liquid");
            assertTrue(Arrays.stream(bottom.phaseMoles()[2]).sum()>0,"bottom outlet also transports vapour");
            finalFlows=result.averageMassFlows();
        }
        // W3b stage A group 5: the drum (11.4 of its 11.5 m3 vapour) sends vapour through its top port and its liquid with
        // vapour through its bottom port; no liquid fills its vapour space, so the top port draws no liquid. The bottom line
        // then passes at its velocity cap, its junction stands above the set point and its valve is open; the top valve holds
        // its junction at the set point.
        assertEquals(FlowControl.Mode.VALVE_OPEN,previous.endpointModes().get(3),"the bottom line passes at its velocity cap");
        assertTrue(graph.reservoirs().get(2).state().pressure()>200000,"the bottom junction stands above the set point: "+graph.reservoirs().get(2).state().pressure());
        assertEquals(FlowControl.Mode.VALVE_REGULATING,previous.endpointModes().get(4));
        assertEquals(200000,graph.reservoirs().get(3).state().pressure(),1,"the top valve holds its junction at the set point");
        assertEquals(finalFlows[0],finalFlows[1]+finalFlows[2],1e-7,"settled average inlet equals both outlets");
    }

    @Test void anInterruptedAttemptCannotChangeDeterministicAcceptedEndpointReplay()throws IOException {
        var initial=start();var solver=new PassiveIntervalSolver(model);var fresh=new PassiveIntervalSolver(model);
        solver.replayStart(initial.graph(),initial.endpoint());int[] calls={0};
        assertThrows(IllegalStateException.class,()->solver.solve(initial.graph(),4.5,PassiveIntervalSolver.Settings.defaults(),()->{
            if(++calls[0]==1000)throw new IllegalStateException("diagnostic cancellation");
        }));
        solver.replayStart(initial.graph(),initial.endpoint());fresh.replayStart(initial.graph(),initial.endpoint());
        same(fresh.solve(initial.graph(),4.5,PassiveIntervalSolver.Settings.defaults(),NOOP),
                solver.solve(initial.graph(),4.5,PassiveIntervalSolver.Settings.defaults(),NOOP));
    }

    @Test void changedValveSettingsDiscardTheOldAcceptedRegime()throws IOException {
        var initial=start();var pipes=new ArrayList<>(initial.graph().pipes());
        var old=pipes.get(3);pipes.set(3,new PassiveNetwork.Pipe(old.id(),old.first(),old.second(),old.sections(),new FlowControl.PressureValve(180000),
                old.blockedDirections(),old.filter(),old.firstPort(),old.secondPort()));
        var changed=new PassiveNetwork(initial.graph().reservoirs(),pipes);var reused=new PassiveIntervalSolver(model);var fresh=new PassiveIntervalSolver(model);
        reused.replayStart(changed,initial.endpoint());fresh.replayStart(changed,null);
        same(fresh.solve(changed,.001,PassiveIntervalSolver.Settings.defaults(),NOOP),reused.solve(changed,.001,PassiveIntervalSolver.Settings.defaults(),NOOP));
    }

    private static void same(PassiveIntervalSolver.Result expected,PassiveIntervalSolver.Result actual) {
        assertEquals(expected.endpointModes(),actual.endpointModes());assertArrayEquals(expected.endpointFlows(),actual.endpointFlows(),0);
        for(int i=0;i<expected.graph().reservoirs().size();i++) {
            var a=expected.graph().reservoirs().get(i);var b=actual.graph().reservoirs().get(i);
            assertEquals(a.inventory(),b.inventory());assertEquals(a.state().temperature(),b.state().temperature(),0);assertEquals(a.state().pressure(),b.state().pressure(),0);
        }
    }
}
