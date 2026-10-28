package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/** A unit's heat side in the topology (batch 2026-10-27-heat-exchanger-design, work package W2). */
class HeatSideTopologyTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final HeatUnit TUBE=new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,450,0);
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z);}
    private static PhysicalFluidTopology.Device plain(long id,int x,int y,int z,Kind kind){return new PhysicalFluidTopology.Device(id,at(x,y,z),kind,EAST,STUB,new FlowControl.Passive());}
    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell,PhysicalFluidTopology.Direction face,PhysicalFluidTopology.ConnectionRole role){return new PhysicalFluidTopology.Port(cell,face,PassiveNetwork.PhasePort.BULK,role);}
    private static PhysicalFluidTopology.Device side(long id,List<PhysicalFluidTopology.Port> ports,PipeResistance.Geometry passage,HeatUnit heat) {
        return new PhysicalFluidTopology.Device(id,ports.getFirst().cell(),Kind.HEAT_SIDE,NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }
    private PassiveNetwork.Reservoir nitrogenAt(long id,int y,PassiveNetwork.NodeKind kind,double pressure){return new PassiveNetwork.Reservoir(id,y,model.initialNitrogenCharge(1,298.15,pressure,()->{}),kind);}

    /**
     * Nitrogen from a 1.5 bar generator through a pipe into the side's inlet, out of its outlet 2 m higher through a pipe
     * into a 1 atm void. The side compiles as a filter does: its inlet is its node, its outlet a junction of its own at the
     * outlet's height, and a passive pipe of its passage joins them.
     */
    @Test void aSideIsAPassivePipeOfItsPassageBetweenItsInletNodeAndAnOutletJunction() {
        var passage=HeatSides.tubePassage(.5,2);
        var ports=List.of(port(at(10,0,10),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(0,2,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        var devices=List.of(plain(1,8,0,10,Kind.GENERATOR),plain(20,9,0,10,Kind.PIPE),side(10,ports,passage,TUBE),plain(30,1,2,0,Kind.PIPE),plain(2,2,2,0,Kind.VOID));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.GENERATOR,150000),2L,nitrogenAt(2,2,PassiveNetwork.NodeKind.VOID,101325)));
        assertEquals(1,compiled.islands().size());
        var graph=compiled.islands().getFirst().graph();
        var own=graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
        assertEquals(List.of(passage),own.sections());assertInstanceOf(FlowControl.Passive.class,own.control());assertNull(own.heat(),"no partner read yet: the side moves no heat");
        assertEquals(10,graph.reservoirs().get(own.first()).id());
        var outlet=graph.reservoirs().get(own.second());assertEquals(PassiveNetwork.NodeKind.JUNCTION,outlet.kind());assertEquals(2,outlet.elevation());
        // The outlet run leaves from the outlet junction; the inlet run ends at the node; the nozzles are stubs at the line's bore.
        var runs=graph.pipes().stream().filter(p->p!=own).toList();assertEquals(2,runs.size());
        for(var run:runs)assertTrue(run.sections().stream().allMatch(g->g.channels()==1&&g.diameter()==.05));
        assertTrue(runs.stream().anyMatch(p->p.first()==own.second()||p.second()==own.second()));
        assertEquals(own.id(),compiled.pipeViews().get(10L).getFirst().pipeId());
        // Three runs in series carry one flow; the passage takes its own drop.
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var q=result.averageMassFlows();int index=graph.pipes().indexOf(own);
        assertTrue(q[index]>0);
        for(int i=0;i<q.length;i++)assertEquals(q[index],Math.abs(q[i]),1e-6*q[index]);
        var end=result.graph().reservoirs();
        assertTrue(end.get(own.first()).state().pressure()>end.get(own.second()).state().pressure());
    }
    private FluidThermodynamics.State water(double temperature,double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(temperature,pressure,n,()->{}).volume();
        return model.flashTP(temperature,pressure,n,()->{});
    }
    /**
     * W5's gate (plan section 10): water at 85 °C from a 1.3 bar generator through one 3 × 6 m air cooler bay into a 1 atm
     * void. The side compiles with its heat law, a flat wall of air at 25 °C behind the liquid's effective film. The outlet
     * junction holds half the tubes' water (about 180 kg, a lag of about 14 s at 13.5 kg/s), so after 300 s it stands on the
     * exponential approach to the air for the flow the line carries:
     * T_out = 25 °C + 60 K × exp(−h A / (ṁ c_p)). Still fans cool by natural draught, less.
     */
    @Test void anAirCoolersOutletApproachesItsAirWithTheFansOnAndOff() {
        var shape=com.wormzjl.icheme.science.equipment.EquipmentShape.unit(com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.AIR_COOLER,3,6,
                com.wormzjl.icheme.science.equipment.EquipmentShape.Heading.SOUTH,4);
        var definition=HeatSides.of(shape).getFirst();
        double inlet=358.15,air=HeatSides.AIR_TEMPERATURE;double[] outlets=new double[2];
        for(int fans=1;fans>=0;fans--) {
            var unit=new HeatUnit(HeatSides.Unit.AIR_COOLER,HeatSides.Side.PROCESS,definition.area(),0).withControl(HeatUnit.Control.OFF.withShare(fans));
            var ports=List.of(port(at(10,0,10),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(0,0,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
            var devices=List.of(plain(1,8,0,10,Kind.GENERATOR),plain(20,9,0,10,Kind.PIPE),side(10,ports,definition.passage(),unit),plain(30,1,0,0,Kind.PIPE),plain(2,2,0,0,Kind.VOID));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,water(inlet,130_000),PassiveNetwork.NodeKind.GENERATOR),
                    2L,new PassiveNetwork.Reservoir(2,0,water(300,101_325),PassiveNetwork.NodeKind.VOID)));
            var graph=compiled.islands().getFirst().graph();
            var own=graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
            assertEquals(unit.heatSide(),own.heat(),"the side compiles with its unit's heat law");
            var result=new PassiveIntervalSolver(model).solve(graph,300,PassiveIntervalSolver.Settings.defaults(),()->{});
            var end=result.graph();var after=end.pipes().stream().filter(p->p.id()==own.id()).findFirst().orElseThrow();int index=end.pipes().indexOf(after);
            double flow=result.averageMassFlows()[index];assertTrue(flow>0);
            double outlet=end.reservoirs().get(after.second()).state().temperature();
            var curve=HeatCurve.sample(model,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(water(inlet,130_000)),130_000,air-10,inlet+10,HeatCurve.Films.DEFAULT,()->{});
            double cp=(curve.enthalpy(curve.knots()-1)-curve.enthalpy(0))/(curve.temperature(curve.knots()-1)-curve.temperature(0));
            double film=HeatSides.airFilms(fans).liquid();
            assertEquals(air+(inlet-air)*Math.exp(-film*definition.area()/(flow*cp)),outlet,.1,"fans "+fans+" at "+flow+" kg/s");
            outlets[fans]=outlet;
        }
        assertTrue(outlets[1]<outlets[0]-1,"the fans cool more than natural draught");
    }
    /** One 3 × 6 m air cooler bay under {@code control}, fed water at {@code inlet} K from a 1.3 bar generator into a 1 atm void. */
    private PassiveNetwork controlledBay(HeatUnit.Control control,double inlet) {
        var shape=com.wormzjl.icheme.science.equipment.EquipmentShape.unit(com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.AIR_COOLER,3,6,
                com.wormzjl.icheme.science.equipment.EquipmentShape.Heading.SOUTH,4);
        var definition=HeatSides.of(shape).getFirst();
        var unit=new HeatUnit(HeatSides.Unit.AIR_COOLER,HeatSides.Side.PROCESS,definition.area(),0).withControl(control);
        var ports=List.of(port(at(10,0,10),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(0,0,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        var devices=List.of(plain(1,8,0,10,Kind.GENERATOR),plain(20,9,0,10,Kind.PIPE),side(10,ports,definition.passage(),unit),plain(30,1,0,0,Kind.PIPE),plain(2,2,0,0,Kind.VOID));
        return PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,water(inlet,130_000),PassiveNetwork.NodeKind.GENERATOR),
                2L,new PassiveNetwork.Reservoir(2,0,water(300,101_325),PassiveNetwork.NodeKind.VOID))).islands().getFirst().graph();
    }
    /** {@code graph} with its generator {@code id} delivering water at {@code inlet} K instead: an inlet step. */
    private PassiveNetwork withInlet(PassiveNetwork graph,long id,double inlet) {
        var nodes=new ArrayList<>(graph.reservoirs());
        for(int i=0;i<nodes.size();i++)if(nodes.get(i).id()==id)nodes.set(i,new PassiveNetwork.Reservoir(id,nodes.get(i).elevation(),water(inlet,130_000),PassiveNetwork.NodeKind.GENERATOR));
        return new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }
    /** The bay's side after {@code seconds} s of {@code graph}: its outlet temperature (K) and its law as last planned. */
    private record Cooled(PassiveNetwork graph,double outlet,AirCooled law) {}
    private Cooled run(PassiveNetwork graph,double seconds) {
        var end=new PassiveIntervalSolver(model).solve(graph,seconds,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        var side=end.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
        return new Cooled(end,end.reservoirs().get(side.second()).state().temperature(),(AirCooled)side.heat());
    }
    /**
     * W4's air cooler gate (plan section 6): the fans hold the outlet of 85 °C water at 65 °C, between natural draught
     * (77.4 °C) and full fans (62.7 °C) on this bay's 61 m² of bare tube, and again after the inlet steps to 75 °C. The controller plans the fans at each step
     * from its start (as a fired coil's firing), so after 300 s, the outlet junction's lag spent, the outlet stands at its
     * set point. A set point below what full fans reach stops at full fans, LIMITED; the low limit holds above one; the
     * louvers hold a set point above what natural draught reaches, the fans still (the owner's test, 2026-10-28), and one
     * above the inlet shuts them: no air, no heat, LIMITED.
     */
    @Test void anAirCoolersFansHoldItsOutletAfterAnInletStep() {
        var held=run(controlledBay(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,338.15,0,1),358.15),300);
        assertEquals(338.15,held.outlet(),.1,"the fans hold 65 °C");
        assertEquals(HeatControl.Limit.NONE,held.law().limit());assertTrue(held.law().fans()>0&&held.law().fans()<1,"fans at "+held.law().fans());
        var stepped=run(withInlet(held.graph(),1,348.15),300);
        assertEquals(338.15,stepped.outlet(),.1,"and again after the inlet falls to 75 °C");
        assertTrue(stepped.law().fans()<held.law().fans(),"with slower fans: "+stepped.law().fans()+" against "+held.law().fans());
        System.out.printf(Locale.ROOT,"air cooler at 65 °C: fans %.3f from 85 °C, %.3f from 75 °C%n",held.law().fans(),stepped.law().fans());
        var cold=run(controlledBay(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,318.15,0,1),358.15),300);
        assertEquals(1,cold.law().fans());assertEquals(HeatControl.Limit.HIGHEST,cold.law().limit());
        assertTrue(cold.outlet()>318.15+5,"45 °C is out of reach: "+cold.outlet());
        assertEquals("LIMITED: the fans run at full speed / ",FluidWorldAuthority.controlStatus(cold.law(),null));
        var guarded=run(controlledBay(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,318.15,340.15,1),358.15),300);
        assertEquals(340.15,guarded.outlet(),.1,"the low limit holds 67 °C");
        assertEquals(HeatControl.Limit.LOW_LIMIT,guarded.law().limit());
        var warm=run(controlledBay(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,353.15,0,1),358.15),300);
        assertEquals(353.15,warm.outlet(),.1,"the louvers hold 80 °C, above natural draught's 77.4 °C");
        assertEquals(HeatControl.Limit.NONE,warm.law().limit());assertEquals(0,warm.law().fans());
        assertTrue(warm.law().louvers()>0&&warm.law().louvers()<1,"louvers at "+warm.law().louvers());
        var shut=run(controlledBay(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,363.15,0,1),358.15),300);
        assertEquals(0,shut.law().draught());assertEquals(HeatControl.Limit.LOWEST,shut.law().limit());
        assertEquals(358.15,shut.outlet(),.1,"shut louvers move no heat");
        assertEquals("LIMITED: the louvers are shut / ",FluidWorldAuthority.controlStatus(shut.law(),null));
        System.out.printf(Locale.ROOT,"air cooler from 85 °C: full fans %.2f K, louvers %.3f at %.2f K, low limit %.2f K%n",cold.outlet(),warm.law().louvers(),warm.outlet(),guarded.outlet());
    }
    /** A fired heater's coil over {@code area} m² firing to {@code setPoint} K with its burners at {@code burners}. */
    private static HeatUnit coil(HeatSides.Side side,double area,double setPoint,double burners) {
        return new HeatUnit(HeatSides.Unit.FIRED_HEATER,side,area,0).withControl(new HeatUnit.Control(HeatUnit.Mode.FIRING,setPoint,0,burners));
    }
    /** A short coil passage, 19 m of 6" tube (its outlet junction holds 160 kg of water: a lag of about 20 s), and a wide outlet run. */
    private static final PipeResistance.Geometry COIL=HeatSides.heaterPassage(10,1),OUTLET=new PipeResistance.Geometry(1,.2,.000045,0);
    /** The coils' heat in the last interval, W, in their order, and the network after it. */
    private record Fired(PassiveNetwork graph,PassiveIntervalSolver.Result last,double[] heat,double flow) {
        PassiveNetwork.Pipe coil(long id){return graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(id)).findFirst().orElseThrow();}
        FluidThermodynamics.State outlet(long id){return graph.reservoirs().get(coil(id).second()).state();}
    }
    /**
     * Water at {@code inlet} K from a 10 bar generator through {@code coils} in series into a 9.9 bar void, settled for 300 s
     * (about 15 lags of a coil's outlet junction), then measured over 30 s more.
     */
    private Fired fire(double inlet,HeatUnit... coils) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>(List.of(plain(1,8,0,10,Kind.GENERATOR),plain(20,9,0,10,Kind.PIPE)));
        var from=at(10,0,10);
        for(int c=0;c<coils.length;c++) {
            var to=at(c*5,0,0);
            devices.add(side(10+c,List.of(port(from,WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(to,EAST,PhysicalFluidTopology.ConnectionRole.OUTLET)),COIL,coils[c]));
            devices.add(new PhysicalFluidTopology.Device(30+c,at(c*5+1,0,0),Kind.PIPE,EAST,OUTLET,new FlowControl.Passive()));
            from=at(c*5+2,0,0);
        }
        devices.add(plain(2,from.x(),0,0,Kind.VOID));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,water(inlet,1_000_000),PassiveNetwork.NodeKind.GENERATOR),
                2L,new PassiveNetwork.Reservoir(2,0,water(300,990_000),PassiveNetwork.NodeKind.VOID)));
        assertEquals(1,compiled.islands().size());
        var solver=new PassiveIntervalSolver(model);
        var settled=solver.solve(compiled.islands().getFirst().graph(),300,PassiveIntervalSolver.Settings.defaults(),()->{});
        var last=solver.solve(settled.graph(),30,PassiveIntervalSolver.Settings.defaults(),()->{});
        var heat=new double[coils.length];
        for(var boundary:last.boundaries())for(int c=0;c<coils.length;c++)
            if(boundary.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT&&boundary.nodeId()==PhysicalFluidTopology.sideIdentity(10+c))heat[c]+=boundary.totalEnergyJoule()/last.advancedSeconds();
        int index=-1;for(int i=0;i<last.graph().pipes().size();i++)if(last.graph().pipes().get(i).id()==PhysicalFluidTopology.sideIdentity(10))index=i;
        return new Fired(last.graph(),last,heat,last.averageMassFlows()[index]);
    }
    /** Water's curve at 10 bar from 290 to 490 K, its liquid end and its steam. */
    private HeatCurve waterCurve(double inlet){return HeatCurve.sample(model,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(water(inlet,1_000_000)),1_000_000,290,490,HeatCurve.Films.DEFAULT,()->{});}

    /**
     * W6's gates (plan section 10). A radiant coil of 7 MW (200 m² at 35 kW/m²) takes water at 50 °C to its 120 °C set point
     * exactly; its outlet never passes it.
     */
    @Test void aFiredCoilWithinItsDutyBringsItsOutletToTheSetPoint() {
        var run=fire(323.15,coil(HeatSides.Side.RADIANT,200,393.15,1));
        assertTrue(run.flow()>1,"the line flows: "+run.flow());
        assertEquals(1,((FiredCoil)run.coil(10).heat()).firing(),"within its duty it fires as the set point needs");
        assertEquals(393.15,run.outlet(10).temperature(),.1);
        var curve=waterCurve(323.15);
        assertEquals(run.flow()*(curve.enthalpyAt(393.15)-curve.enthalpyAt(323.15)),run.heat()[0],.01*run.heat()[0]);
        assertTrue(run.heat()[0]<7e6);
    }
    /** With its burners at a tenth, the coil fires at its 700 kW and shows the shortfall: h_out = h_in + Q_max / ṁ. */
    @Test void aFiredCoilShortOfDutyFiresAtItsMaximum() {
        var run=fire(323.15,coil(HeatSides.Side.RADIANT,200,393.15,.1));
        var fired=(FiredCoil)run.coil(10).heat();
        assertEquals(7e5,fired.duty(),1e-6);assertTrue(fired.firing()>0&&fired.firing()<1,"at its maximum: "+fired.firing());
        assertEquals(7e5,run.heat()[0],.01*7e5);
        var curve=waterCurve(323.15);
        double outlet=run.outlet(10).temperature();
        assertEquals(curve.temperatureAt(curve.enthalpyAt(323.15)+7e5/run.flow()),outlet,.1);
        assertTrue(outlet<393.15-10,"short of the set point: "+outlet);
    }
    /** Water arriving at 130 °C, above the 120 °C set point: the coil does not fire, and the stream passes unheated and uncooled. */
    @Test void aFiredCoilDoesNotFireAStreamAboveItsSetPoint() {
        var run=fire(403.15,coil(HeatSides.Side.RADIANT,200,393.15,1));
        assertEquals(0,((FiredCoil)run.coil(10).heat()).firing());
        assertEquals(0,run.heat()[0]);
        assertEquals(403.15,run.outlet(10).temperature(),.05);
    }
    /** A set point above saturation boils: water at 50 °C and 10 bar leaves a 70 MW coil as steam at 200 °C. */
    @Test void aFiredCoilRaisesSteamAboveSaturation() {
        var run=fire(323.15,coil(HeatSides.Side.RADIANT,2000,473.15,1));
        assertEquals(1,((FiredCoil)run.coil(10).heat()).firing(),"within its duty: "+run.flow()+" kg/s");
        var steam=run.outlet(10);
        assertFalse(FluidThermodynamics.holdsLiquid(steam),"all vapour");
        assertEquals(473.15,steam.temperature(),1);
    }
    /** A heater's convection coil and radiant coil in series: 30 % and 70 % of the heat, the outlet at the set point. */
    @Test void aHeatersTwoCoilsInSeriesShareTheHeatThirtySeventy() {
        var run=fire(323.15,coil(HeatSides.Side.CONVECTION,200,393.15,1),coil(HeatSides.Side.RADIANT,200,393.15,1));
        double total=run.heat()[0]+run.heat()[1];
        assertEquals(.3,run.heat()[0]/total,.005);
        assertEquals(393.15,run.outlet(11).temperature(),.1);
        var curve=waterCurve(323.15);
        assertEquals(run.flow()*(curve.enthalpyAt(393.15)-curve.enthalpyAt(323.15)),total,.01*total);
    }
    @Test void onlyASideCarriesAUnitAndInletAndOutletPorts() {
        var ports=List.of(port(at(0,0,0),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(2,0,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        assertDoesNotThrow(()->side(5,ports,STUB,TUBE));
        assertThrows(IllegalArgumentException.class,()->side(5,ports,STUB,null),"a side without its unit");
        assertThrows(IllegalArgumentException.class,()->side(5,List.of(ports.getLast(),ports.getFirst()),STUB,TUBE),"its node is its first inlet's cell");
        assertThrows(IllegalArgumentException.class,()->side(5,List.of(ports.getFirst()),STUB,TUBE),"an inlet without an outlet");
        assertThrows(IllegalArgumentException.class,()->side(5,ports,STUB,TUBE.withPartner(5)),"its own partner");
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(6,at(0,0,0),Kind.PUMP,NORTH,STUB,new FlowControl.Pump(.01,500000),ports),"a pump with inlet and outlet ports");
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(7,at(0,0,0),Kind.PIPE,NORTH,STUB,new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,TUBE),"a pipe with a unit");
    }
}
