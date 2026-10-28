package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * What a heat unit's screen reads beyond its side's own view (batch 2026-10-27-heat-exchanger-design, HEAT_GUI_PLAN.md
 * section 1): the stream at each port, read from a solved island; the actuator and its effort; an exchanger's lines part by
 * part, with the stream at each part a report holds.
 */
class HeatPresentationTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB=new PipeResistance.Geometry(1,.05,.000045,0);
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z);}
    private static PhysicalFluidTopology.Device plain(long id,int x,int y,int z,Kind kind){return new PhysicalFluidTopology.Device(id,at(x,y,z),kind,EAST,STUB,new FlowControl.Passive());}
    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell,PhysicalFluidTopology.Direction face,PhysicalFluidTopology.ConnectionRole role){return new PhysicalFluidTopology.Port(cell,face,PassiveNetwork.PhasePort.BULK,role);}
    private static PhysicalFluidTopology.Device side(long id,PipeResistance.Geometry passage,HeatUnit heat) {
        var ports=List.of(port(at(10,0,10),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(0,0,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id,ports.getFirst().cell(),Kind.HEAT_SIDE,NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }
    private FluidThermodynamics.State water(double temperature,double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(temperature,pressure,n,()->{}).volume();
        return model.flashTP(temperature,pressure,n,()->{});
    }
    private static final EquipmentShape BAY=EquipmentShape.unit(EquipmentShape.Kind.AIR_COOLER,3,6,EquipmentShape.Heading.SOUTH,4);
    private static final HeatSides.Definition DEFINITION=HeatSides.of(BAY).getFirst();

    /** Water at 85 °C from a 1.3 bar generator through one 3 × 6 m air cooler bay (side 10) into a 1 atm void, after 20 s. */
    private IslandCoordinator.Snapshot bay(HeatUnit unit) {
        var devices=List.of(plain(1,8,0,10,Kind.GENERATOR),plain(20,9,0,10,Kind.PIPE),side(10,DEFINITION.passage(),unit),plain(30,1,0,0,Kind.PIPE),plain(2,2,0,0,Kind.VOID));
        var graph=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,water(358.15,130_000),PassiveNetwork.NodeKind.GENERATOR),
                2L,new PassiveNetwork.Reservoir(2,0,water(300,101_325),PassiveNetwork.NodeKind.VOID))).islands().getFirst().graph();
        var result=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),()->{});
        return new IslandCoordinator.Snapshot(1,0,result.graph(),new IslandClock.Snapshot(400,400,0,100),Optional.of(result),"FULL");
    }

    /** A source over fixed registrations, one island for side 10, lines and the views of the parts' devices. */
    private HeatPresentation.Source source(Map<Long,WorldTopologyLedger.Registration> registrations,IslandCoordinator.Snapshot island,Map<Long,FluidWorldAuthority.Lines> lines,Map<Long,FluidView> views) {
        return new HeatPresentation.Source() {
            @Override public WorldTopologyLedger.Registration registration(long id){return registrations.get(id);}
            @Override public IslandCoordinator.Snapshot island(long device){return device==10?island:null;}
            @Override public FluidWorldAuthority.Lines lines(long side){return lines.get(side);}
            @Override public WorldTopologyLedger.Registration at(PhysicalFluidTopology.Position position){
                for(var r:registrations.values())if(r.device().position().equals(position))return r;return null;
            }
            @Override public FluidView view(long device){return views.get(device);}
            @Override public double[] weights(){return model.molecularWeights();}
            @Override public double portPressure(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port){return node.state().pressure()+PassiveStepSolver.portHead(model,node,port,node.state());}
        };
    }
    private static WorldTopologyLedger.Registration registration(PhysicalFluidTopology.Device device){return new WorldTopologyLedger.Registration(device,FluidDeviceSpec.water(),1);}

    /**
     * An air cooler's ports are its inlet node and its outlet junction at its own pipe's flow: their temperatures, and phases
     * whose mass per second is that flow. Its actuator is its fans, at the share they turn.
     */
    @Test void anAirCoolersPortsAreItsInletNodeAndOutletJunctionAtItsFlow() {
        var unit=new HeatUnit(HeatSides.Unit.AIR_COOLER,HeatSides.Side.PROCESS,DEFINITION.area(),0).withControl(HeatUnit.Control.OFF.withShare(1));
        var island=bay(unit);var graph=island.graph();
        var own=graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
        double flow=island.lastResult().orElseThrow().averageMassFlows()[graph.pipes().indexOf(own)];
        assertTrue(flow>1,"water flows: "+flow);
        var view=new FluidView.Heat("AIR_COOLER","PROCESS",false,graph.reservoirs().get(own.second()).state().temperature(),-1e6,1.0,1e4,null,null,null,null);
        var shown=HeatPresentation.complete(view,10,unit,own.heat(),source(Map.of(10L,registration(side(10,DEFINITION.passage(),unit))),island,Map.of(),Map.of()));
        assertEquals(List.of("PROCESS_INLET","PROCESS_OUTLET"),shown.ports().stream().map(FluidView.Port::role).toList());
        var inlet=shown.port("PROCESS_INLET");var outlet=shown.port("PROCESS_OUTLET");
        assertEquals(graph.reservoirs().get(own.first()).state().temperature(),inlet.temperature(),1e-12);
        assertEquals(graph.reservoirs().get(own.second()).state().temperature(),outlet.temperature(),1e-12);
        assertTrue(inlet.temperature()>outlet.temperature()+5,"the bay cools the water");
        double[] w=model.molecularWeights();
        for(var p:List.of(inlet,outlet)) {
            assertEquals(flow,p.massFlow(),1e-12);
            double mass=0;var n=p.phaseMoles();for(var phase:n)for(int c=0;c<phase.length;c++)mass+=phase[c]*w[c];
            assertEquals(flow,mass,1e-9*flow,"the port's phases carry its flow");
        }
        assertEquals("louvers",shown.actuator());assertEquals(1.0,shown.effort(),"full fans: all the air");assertEquals(((AirCooled)own.heat()).limit().name(),shown.limit());
        assertNull(shown.lines(),"an air cooler has no lines");
    }

    /**
     * An exchanger's tube side held at an outlet temperature asks for a bypass: its lines list the three parts the design asks
     * for, each in its report's state, a tee with its temperature and the valve with its flow. On complete lines the bypass
     * valve acts, its effort the share of the side's feed it passes; on lines not complete the internal bypass acts. The
     * shell side's ports are listed too, empty while its island has not been read.
     */
    @Test void anExchangersLinesCarryEachPartsStateAndStream() {
        var tube=new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,DEFINITION.area(),11).withControl(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,330,0,0));
        var shell=new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.SHELL,DEFINITION.area(),10);
        var island=bay(new HeatUnit(HeatSides.Unit.AIR_COOLER,HeatSides.Side.PROCESS,DEFINITION.area(),0));var graph=island.graph();
        var own=graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
        double through=island.lastResult().orElseThrow().averageMassFlows()[graph.pipes().indexOf(own)];
        var registrations=new HashMap<Long,WorldTopologyLedger.Registration>();
        registrations.put(10L,registration(side(10,DEFINITION.passage(),tube)));
        registrations.put(11L,registration(side(11,DEFINITION.passage(),shell)));
        registrations.put(50L,registration(plain(50,12,0,10,Kind.PIPE)));registrations.put(51L,registration(plain(51,13,0,10,Kind.PIPE)));registrations.put(52L,registration(plain(52,14,0,10,Kind.PIPE)));
        var tee=new FluidView.State(130_000,358.15,1e-3,1,new double[]{1e-3,0,0},new double[3][model.componentCount()],com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY,1e-3);
        var views=Map.of(50L,new FluidView(50,1,0,0,"FULL",tee,0,List.of()),51L,new FluidView(51,1,0,0,"FULL",null,2.0,List.of()),52L,new FluidView(52,1,0,0,"FULL",tee,0,List.of()));
        long a=net.minecraft.core.BlockPos.asLong(12,0,10),b=net.minecraft.core.BlockPos.asLong(13,0,10),c=net.minecraft.core.BlockPos.asLong(14,0,10);
        var slots=List.of(new AssemblyReport.SlotReport(ExchangerBlueprint.EXCHANGER,AssemblyReport.State.OK,0,AssemblyReport.NONE,""),
                new AssemblyReport.SlotReport(ExchangerBlueprint.inletTee(HeatSides.Side.TUBE),AssemblyReport.State.OK,a,AssemblyReport.NONE,""),
                new AssemblyReport.SlotReport(ExchangerBlueprint.bypassValve(HeatSides.Side.TUBE),AssemblyReport.State.OK,b,AssemblyReport.NONE,""),
                new AssemblyReport.SlotReport(ExchangerBlueprint.outletTee(HeatSides.Side.TUBE),AssemblyReport.State.OK,c,AssemblyReport.NONE,""));
        var view=new FluidView.Heat("EXCHANGER","TUBE",true,330,-1e6,null,null,null,null,null,.25);
        var complete=HeatPresentation.complete(view,10,tube,own.heat(),source(registrations,island,Map.of(10L,new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.COMPLETE,0,51,slots)),views));
        assertEquals(List.of("TUBE_INLET","TUBE_OUTLET","SHELL_INLET","SHELL_OUTLET"),complete.ports().stream().map(FluidView.Port::role).toList());
        assertNull(complete.port("SHELL_INLET").temperature(),"the shell's island is not read");
        var lines=complete.lines();assertEquals("COMPLETE",lines.status());
        assertEquals(List.of("tube_inlet_tee","tube_bypass_valve","tube_outlet_tee"),lines.parts().stream().map(FluidView.Part::slot).toList());
        assertEquals(358.15,lines.part("tube_inlet_tee").temperature());assertNull(lines.part("tube_inlet_tee").massFlow());
        assertEquals(2.0,lines.part("tube_bypass_valve").massFlow());assertNull(lines.part("tube_bypass_valve").temperature());
        assertEquals("bypass_valve",complete.actuator());assertEquals(2/(2+through),complete.effort(),1e-12);
        // Lines short of a part: the internal bypass acts, at the share it holds; the part missing in red, with its detail.
        var missing=List.of(slots.get(1),new AssemblyReport.SlotReport(ExchangerBlueprint.bypassValve(HeatSides.Side.TUBE),AssemblyReport.State.MISSING,AssemblyReport.NONE,b,"terminal"));
        var checked=HeatPresentation.complete(view,10,tube,own.heat(),source(registrations,island,Map.of(10L,new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.CHECKED,2,0,missing)),views));
        assertEquals("CHECKED",checked.lines().status());assertEquals(2,checked.lines().missing());
        assertEquals("MISSING",checked.lines().part("tube_bypass_valve").state());assertEquals("terminal",checked.lines().part("tube_bypass_valve").detail());assertEquals(b,checked.lines().part("tube_bypass_valve").at());
        assertEquals("NOT_VALIDATED",checked.lines().part("tube_outlet_tee").state(),"a slot the report does not hold");
        assertEquals("bypass",checked.actuator());assertEquals(.25,checked.effort());
        // The shell side, its own control off, still draws the tube side's lines; its status says it asks for none.
        var fromShell=HeatPresentation.complete(new FluidView.Heat("EXCHANGER","SHELL",true,300,1e6,null,null,null,null,null,0.0),11,shell,null,
                source(registrations,island,Map.of(10L,new FluidWorldAuthority.Lines(FluidWorldAuthority.Lines.Status.COMPLETE,0,51,slots)),views));
        assertEquals("NONE",fromShell.lines().status());assertEquals(3,fromShell.lines().parts().size());assertEquals("OK",fromShell.lines().part("tube_bypass_valve").state());
        assertEquals(List.of("SHELL_INLET","SHELL_OUTLET","TUBE_INLET","TUBE_OUTLET"),fromShell.ports().stream().map(FluidView.Port::role).toList());
        // Neither side asks for lines: none.
        assertNull(HeatPresentation.complete(view,10,tube.withControl(HeatUnit.Control.OFF),own.heat(),source(registrations,island,Map.of(),views)).lines());
    }
}
