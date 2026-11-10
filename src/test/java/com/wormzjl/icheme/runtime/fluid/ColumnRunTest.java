package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.operation.Condition;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A running column in the network (batch 2026-10-28-column-operation, C4, plan 4.3, 4.4, 5 and 9): its feed port takes
 * what its valve passes, each cycle splits what it took into the products' accounts, and the outlet port gives the
 * product into the downstream line, never more than its account holds; every component the column took is in its
 * accounts or downstream, exactly.
 */
class ColumnRunTest {
    /** The ports' bookings, summed by port node, beside the column's own. */
    static final class Counting implements IslandCoordinator.Ports {
        final IslandCoordinator.Ports inner;final Map<Long,double[]> moles=new HashMap<>();final Map<Long,Double> heat=new HashMap<>(),solids=new HashMap<>(),energy=new HashMap<>();
        /** A condenser's read as before U2: no heat rate for a valve. */
        boolean before;
        /** The world list the counts lie on, and the islands whose own lists their boundaries lie on (V2). */
        final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics world;final IslandCoordinator islands;
        Counting(IslandCoordinator.Ports inner,EventPathWorld w){this.inner=inner;world=w.model;islands=w.coordinator;}
        public boolean ready(long island,PassiveNetwork graph,long start){return inner.ready(island,graph,start);}
        public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice){return inner.plans(island,graph,slice);}
        public com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read heatRead(long side,long partner,long start,double seconds,double transit) {
            var read=inner.heatRead(side,partner,start,seconds,transit);
            return before&&read!=null&&read.ceiling()>0?com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read.column(read.transitDue(),read.partnerSeconds(),read.ceiling()):read;
        }
        public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
            for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat.merge(b.nodeId(),b.totalEnergyJoule(),Double::sum);
            for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE){var sum=moles.computeIfAbsent(b.nodeId(),id->new double[world.componentCount()]);var n=IslandSets.amounts(b.moles(),islands.model(island),world);for(int i=0;i<n.length;i++)sum[i]+=n[i];solids.merge(b.nodeId(),b.solids().massKg(),Double::sum);energy.merge(b.nodeId(),b.totalEnergyJoule(),Double::sum);}
            inner.booked(island,graph,start,end,result);
        }
    }
    private static PhysicalFluidTopology.Device port(long id,int x,Kind kind,Direction toward,PipeResistanceGeometry g){return port(id,x,0,kind,toward,g);}
    private static PhysicalFluidTopology.Device port(long id,int x,int z,Kind kind,Direction toward,PipeResistanceGeometry g) {
        var at=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,64,z,PhysicalFluidTopology.PORT_LANE);
        return new PhysicalFluidTopology.Device(id,at,kind,toward,g.geometry,new FlowControl.Passive(),List.of(new PhysicalFluidTopology.Port(at,toward,PassiveNetwork.PhasePort.BULK)),
                true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
    }
    /** A heat side from x = 2 to 4 along row {@code z}. */
    private static PhysicalFluidTopology.Device heatSide(long id,int z,com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry passage,HeatUnit heat) {
        var in=new PhysicalFluidTopology.Position(EventPathWorld.DIM,2,64,z);var out=new PhysicalFluidTopology.Position(EventPathWorld.DIM,4,64,z);
        var ports=List.of(new PhysicalFluidTopology.Port(in,Direction.WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.INLET),new PhysicalFluidTopology.Port(out,Direction.EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id,in,Kind.HEAT_SIDE,Direction.NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }
    private record PipeResistanceGeometry(com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry geometry) {}
    private static double sum(double[] n){double s=0;for(double v:n)s+=v;return s;}

    private record Rig(EventPathWorld world,ColumnOperation ops,Counting counting,long unit,long feed,long product,long tank,long steamSource,long waterSource,long tube,long shell,long waterValve,
                       long utilitySource,long utilityTube,long utilityShell,long utilityValve){}
    /** A 3 bar nitrogen feed line into a feed port, a distillate port out to a tank, a table sending everything to the distillate; jobs taking {@code lag} ticks. */
    private Rig rig(int lag){return rig(lag,null);}
    private Rig rig(int lag,Fake solves){return rig(lag,solves,false,false);}
    private Rig rig(int lag,Fake solves,boolean steam,boolean blocked){return rig(lag,solves,steam,blocked,false);}
    /**
     * The rig with its tables from {@code solves}, or installed at once where null; with {@code steam}, a 450 K steam line into
     * a steam port at 0.2 mol per mol of feed; with {@code blocked}, nothing downstream of the product valve; with
     * {@code condenser}, a water-cooled condenser: a 3 bar cooling-water line through its tubes, its shell held by the column.
     */
    private Rig rig(int lag,Fake solves,boolean steam,boolean blocked,boolean condenser) {
        var world=new EventPathWorld();world.lag=lag;var model=world.model;int m=model.componentCount();
        var n2=EventPathWorld.nitrogen().composition();
        var water=new double[m];water[m-1]=1;
        boolean liquid=pumped||feedSolids!=SlurryFeed.NONE;
        long generator=liquid?world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,300,300000,water,feedSolids),.05):world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,n2),.05);
        if(pumped)world.place(1,64,0,Kind.PUMP,Direction.EAST,new FlowControl.Pump(.01,500000),null,.05);else world.place(1,64,0,Kind.PIPE,Direction.EAST,lineBore);
        long feedValve=world.place(2,64,0,Kind.VALVE,Direction.EAST,FluidDeviceSpec.nitrogen(world.catalog),lineBore);
        long productValve=world.place(11,64,0,Kind.VALVE,Direction.EAST,FluidDeviceSpec.nitrogen(world.catalog),lineBore),tank=0,steamSource=0,steamValve=0;
        if(!blocked){
            world.place(12,64,0,Kind.PIPE,Direction.EAST,productBore);
            if(!Double.isNaN(secondValve))world.place(13,64,0,Kind.PIPE,Direction.EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,secondValve,new UUID(1,2),1),null,productBore);
            tank=world.place(Double.isNaN(secondValve)?13:14,64,0,Kind.RESERVOIR,Direction.EAST);
        }
        long waterSource=0,waterValve=0;
        // One cooling-water line a condenser shell, each on its own row (S3b: shells in parallel); with a bore for the
        // water valve, a flow station on the first shell's return (its valve between two pipes), which the column sets (U2).
        for(int k=0;condenser&&k<shells;k++){long source=world.place(0,64,4+8*k,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,waterTemperature,waterPressure,water),.05);if(k==0)waterSource=source;
            world.place(1,64,4+8*k,Kind.PIPE,Direction.EAST,.05);world.place(5,64,4+8*k,Kind.PIPE,Direction.EAST,.05);int end=6;
            if(k==0&&!Double.isNaN(waterValveBore)){waterValve=world.place(6,64,4,Kind.VALVE,Direction.EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.05,new UUID(5,5),1),null,waterValveBore);world.place(7,64,4,Kind.PIPE,Direction.EAST,.05);end=8;}
            world.place(end,64,4+8*k,Kind.VOID,Direction.EAST,new FluidDeviceSpec(1,300,200000,water),.05);}
        // A reboiler's heating or a pumparound cooler's water (W4): one line on row 20 through an exchanger's tubes, its shell held by the column.
        long utilitySource=0,utilityValve=0;
        if(utility!=null){utilitySource=world.place(0,64,20,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,utilityTemperature,utilityPressure,water),.05);
            world.place(1,64,20,Kind.PIPE,Direction.EAST,.05);world.place(5,64,20,Kind.PIPE,Direction.EAST,.05);int end=6;
            // With a bore for its valve, a flow station on the return the column sets (W4: a reboiler's tubes in the COLUMN mode).
            if(!Double.isNaN(utilityValveBore)){utilityValve=world.place(6,64,20,Kind.VALVE,Direction.EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.05,new UUID(6,6),1),null,utilityValveBore);world.place(7,64,20,Kind.PIPE,Direction.EAST,.05);end=8;}
            world.place(end,64,20,Kind.VOID,Direction.EAST,new FluidDeviceSpec(1,300,200000,water),.05);}
        // The steam line is sized for its 0.1 mol/s (1 cm): a 5 cm valve regulating 6 g/s from 3 to 1.5 bar holds its island (README, open items).
        if(steam){steamSource=world.place(0,64,2,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,450,300000,water),.05);world.place(1,64,2,Kind.PIPE,Direction.EAST,.01);steamValve=world.place(2,64,2,Kind.VALVE,Direction.EAST,FluidDeviceSpec.nitrogen(world.catalog),.01);}
        var ops=new ColumnOperation(world.ledger,world.registry,world.coordinator,model.components(),model.molecularWeights());var counting=new Counting(ops,world);world.coordinator.ports(counting);
        world.runTo(40);
        // Start: a feed port inside the feed valve, a distillate port inside the product valve.
        long unit=world.ledger.reserve(1);long feed=world.ledger.nextIdentity(),product=feed+1,steamPort=feed+2,tube=condenser?feed+3:0,shell=condenser?feed+4:0;
        var tubes=new ArrayList<Long>();for(int k=0;condenser&&k<shells;k++)tubes.add(feed+3+2*k);
        var g=new PipeResistanceGeometry(world.registry.registrations().get(feedValve).device().geometry());
        long utilityTube=utility==null?0:feed+3+2L*tubes.size(),utilityShell=utility==null?0:utilityTube+1;
        boolean pumparound=utility==Utility.PUMPAROUND;
        var setpoints=new Setpoints(feedRate,feedRate,330,1,utility==Utility.REBOILER?reboilerPerFeed:0,steam?new double[]{.2}:new double[0],new double[0],pumparound?new double[]{-pumparoundPerFeed}:new double[0]);
        var stations=new ArrayList<>(List.of(new ColumnUnit.Station(Port.FEED,0,feed,feedValve,Account.empty(m)),new ColumnUnit.Station(productPort,0,product,productValve,Account.empty(m))));
        if(steam)stations.add(new ColumnUnit.Station(Port.STEAM,0,steamPort,steamValve,Account.empty(m)));
        // The utility sides as a Start after W4 finds them; none where the unit was saved before them.
        var utilities=utility==null||!utilitySaved?ColumnUnit.Utilities.NONE:pumparound?new ColumnUnit.Utilities(List.of(),List.of(List.of(utilityTube))):new ColumnUnit.Utilities(List.of(utilityTube),List.of());
        world.ledger.column(new ColumnUnit(unit,new PhysicalFluidTopology.Position(EventPathWorld.DIM,6,70,0),true,setpoints,stations,ColumnUnit.Totals.empty(m),tubes,null,false,airSurface,utilities));
        var edits=new ArrayList<>(List.of(new WorldTopologyLedger.Edit(feed,new WorldTopologyLedger.Registration(port(feed,3,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,330,150000,n2),0)),
                new WorldTopologyLedger.Edit(product,new WorldTopologyLedger.Registration(port(product,10,Kind.GENERATOR,Direction.EAST,g),new FluidDeviceSpec(1,330,250000,n2),0))));
        if(steam)edits.add(new WorldTopologyLedger.Edit(steamPort,new WorldTopologyLedger.Registration(port(steamPort,3,2,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,420,150000,n2),0)));
        long last=steamPort;
        for(long t:tubes) {
            long s=t+1;int z=4+8*(int)((t-tube)/2);last=s;
            var passage=com.wormzjl.icheme.science.equipment.HeatSides.tubePassage(.5,3);double area=com.wormzjl.icheme.science.equipment.ProcessUnit.tubeSurface(.5,3);
            edits.add(new WorldTopologyLedger.Edit(t,new WorldTopologyLedger.Registration(heatSide(t,z,passage,new HeatUnit(com.wormzjl.icheme.science.equipment.HeatSides.Unit.EXCHANGER,com.wormzjl.icheme.science.equipment.HeatSides.Side.TUBE,area,s)),new FluidDeviceSpec(1,300,300000,water),0)));
            edits.add(new WorldTopologyLedger.Edit(s,new WorldTopologyLedger.Registration(heatSide(s,z+4,passage,new HeatUnit(com.wormzjl.icheme.science.equipment.HeatSides.Unit.EXCHANGER,com.wormzjl.icheme.science.equipment.HeatSides.Side.SHELL,area,t)).withSuspendedBy(unit),FluidDeviceSpec.nitrogen(world.catalog),0)));
        }
        if(utility!=null) {
            var passage=com.wormzjl.icheme.science.equipment.HeatSides.tubePassage(.5,3);double area=com.wormzjl.icheme.science.equipment.ProcessUnit.tubeSurface(.5,3);
            edits.add(new WorldTopologyLedger.Edit(utilityTube,new WorldTopologyLedger.Registration(heatSide(utilityTube,20,passage,new HeatUnit(com.wormzjl.icheme.science.equipment.HeatSides.Unit.EXCHANGER,com.wormzjl.icheme.science.equipment.HeatSides.Side.TUBE,area,utilityShell)),new FluidDeviceSpec(1,utilityTemperature,utilityPressure,water),0)));
            edits.add(new WorldTopologyLedger.Edit(utilityShell,new WorldTopologyLedger.Registration(heatSide(utilityShell,24,passage,new HeatUnit(com.wormzjl.icheme.science.equipment.HeatSides.Unit.EXCHANGER,com.wormzjl.icheme.science.equipment.HeatSides.Side.SHELL,area,utilityTube)).withSuspendedBy(unit),FluidDeviceSpec.nitrogen(world.catalog),0)));
            last=utilityShell;
        }
        world.registry.submit(edits,last+1,null);
        // The water valve's lines complete (U2): the column sets it.
        if(waterValve!=0)world.coordinator.operations(Map.of(PhysicalFluidTopology.sideIdentity(tube),com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Operation.column(waterValve,com.wormzjl.icheme.science.equipment.HeatSides.WATER_RANGE)));
        if(utilityValve!=0)world.coordinator.operations(Map.of(PhysicalFluidTopology.sideIdentity(utilityTube),com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Operation.column(utilityValve,com.wormzjl.icheme.science.equipment.HeatSides.WATER_RANGE)));
        if(solves!=null){solves.model=model;ops.solves(solves);}
        ops.refresh();world.columns=ops;
        if(solves==null){var s=liquid?waterTable(model,setpoints,productPort):table(model,setpoints,setpoints.steamPerFeed(),productPort);var t=s.table();
            // The reboiler's heat or the pumparound's removal in the table, the solved condenser closing the balance with it (W4).
            if(utility!=null){double q=setpoints.reboilerPerFeed(),pa=pumparound?-pumparoundPerFeed:0;
                t=new SplitTable(t.axis(),t.products(),q,setpoints.pumparoundsPerFeed(),t.condenserPerFeed()-q-pa,t.reference(),t.held());}
            if(waterShare!=1||!Double.isNaN(waterBelow)||productTemperature!=330||airArea.length>0) {
                var p=t.products().getFirst();var products=List.of(new SplitTable.Product(p.port(),p.index(),p.recovery(),productTemperature,p.pressure()));
                t=Double.isNaN(waterBelow)&&airArea.length==0?new SplitTable(t.axis(),products,t.reboilerPerFeed(),t.pumparoundPerFeed(),t.condenserPerFeed(),t.reference(),t.held(),waterShare*t.condenserPerFeed())
                        :new SplitTable(t.axis(),products,t.reboilerPerFeed(),t.pumparoundPerFeed(),t.condenserPerFeed(),t.reference(),t.held(),waterShare*t.condenserPerFeed(),
                                Double.isNaN(waterBelow)?SplitTable.WATER_NEVER:waterBelow,airArea);
            }
            ops.table(unit,t,s.enthalpy());}
        return new Rig(world,ops,counting,unit,feed,product,tank,steamSource,waterSource,tube,shell,waterValve,utilitySource,utilityTube,utilityShell,utilityValve);
    }
    /** A rig's utility line (W4): a reboiler's heating, or the first pumparound's cooler. */
    private enum Utility { REBOILER, PUMPAROUND }
    /** The rig's utility line, or none; its fluid (water), K and Pa; whether its unit names the side (false: a unit saved before W4). */
    private Utility utility;private double utilityTemperature=450,utilityPressure=300000,utilityValveBore=Double.NaN;private boolean utilitySaved=true;
    /** The rig's reboiler duty and its pumparound's removal, J per mol of feed. */
    private double reboilerPerFeed=400,pumparoundPerFeed=400;
    /** A liquid water feed on the rig's line through a pump, ahead of its boundary valve. */
    private boolean pumped;
    /** The rig's condenser shells in parallel, each with its own cooling-water line, and its feed rate, mol/s (S3b). */
    private int shells=1;private double feedRate=.5;
    /** The share of the condenser duty the rig's table gives its cooling water: 1 water-cooled, less an air-then-water condenser's trim. */
    private double waterShare=1;
    /** The temperature below which the rig's table gives a booking difference to the water, K (NaN: the share's default), and its product's, K. */
    private double waterBelow=Double.NaN,productTemperature=330;
    /** The rig's cooling water, K and Pa, and the bore of a valve on its first line the column sets, m (NaN: a plain pipe), U2. */
    private double waterTemperature=300,waterPressure=300000,waterValveBore=Double.NaN;
    /** The rig's air condenser: its bare tube, m² (0: none), and its table's tube per mol/s of feed at each fan step, m² (U2). */
    private double airSurface=0;private double[] airArea=new double[0];
    /** The bore of the rig's product line and of its feed line and valves, m. */
    private double productBore=.05,lineBore=.05;
    /** A player's flow valve on the rig's product line, kg/s, or NaN for a plain pipe. */
    private double secondValve=Double.NaN;
    /** The solids in the rig's feed, and the port its product leaves by. */
    private SlurryFeed feedSolids=SlurryFeed.NONE;private Port productPort=Port.DISTILLATE;
    /** The rig's table for a water feed: everything to the distillate, 1 kJ/mol cooler. */
    private static ColumnSolve.Solved waterTable(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model,Setpoints setpoints,Port port) {
        int m=model.componentCount();var water=new double[m];water[m-1]=1;double h=model.flashTP(300,300000,water.clone(),()->{}).enthalpy();
        var rows=new double[1][m];Arrays.fill(rows[0],h-1000);
        var all=new double[m];Arrays.fill(all,1);var held=new boolean[m];Arrays.fill(held,true);
        var table=new SplitTable(model.components(),List.of(new SplitTable.Product(port,0,all,330,250000)),0,new double[0],-1000,
                new SplitTable.Reference(water,h,30,new double[0],1,setpoints,330,new double[0]),held);
        return new ColumnSolve.Solved(table,rows,null,-1000,"test","");
    }

    @Test void aRunningColumnTakesItsFeedAndGivesItsProductDownstreamConservingEveryComponent() {
        var r=rig(0);var world=r.world();var ops=r.ops();var counting=r.counting();long unit=r.unit(),feed=r.feed(),product=r.product(),tank=r.tank();int m=world.model.componentCount();
        world.runTo(3000);
        var u=world.ledger.column(unit);var totals=u.totals();
        var arrived=counting.moles.getOrDefault(feed,new double[m]);var given=counting.moles.getOrDefault(product,new double[m]);
        assertTrue(totals.taken().total()>40,"the column took its feed at its set rate, 0.5 mol/s from the first cycle's demand a fence later, 121 s: "+totals.taken().total()+" mol, status "+ops.status(unit));
        for(int i=0;i<m;i++) {
            double in=-arrived[i],inlet=u.station(Port.FEED,0).account().moles()[i],taken=totals.taken().moles()[i];
            assertEquals(in,inlet+taken,1e-9*Math.max(1,in),"component "+i+": what arrived is in the feed account or taken");
            double made=totals.produced().moles()[i],outlet=u.station(Port.DISTILLATE,0).account().moles()[i];
            assertEquals(taken,made,1e-9*Math.max(1,taken),"component "+i+": the cycle puts every taken mole in a product");
            assertEquals(made,outlet+given[i],1e-9*Math.max(1,made),"component "+i+": what was made is in the outlet account or downstream");
            assertTrue(outlet>=0);
        }
        var stock=0.0;for(var s:world.coordinator.snapshots())for(var n:s.graph().reservoirs())if(n.id()==tank)stock=sum(n.inventory().moles());
        assertTrue(sum(given)>15&&stock>sum(given)*.5,"the product reached the tank, the outlet filling its buffer toward 60 s of production first: given "+sum(given)+", tank "+stock);
        assertTrue(totals.condenser()<0,"the condenser removed heat: "+totals.condenser());
        assertTrue(world.held.isEmpty(),"no island held: "+world.held);
    }
    /** What the ports carried after Stop was pressed and before their event committed is booked (merge review, finding 4). */
    @Test void aStopBooksWhatItsPortsCarryUntilTheyLeave() {
        var r=rig(0);var world=r.world();var ops=r.ops();var counting=r.counting();long unit=r.unit();int m=world.model.componentCount();
        world.runTo(3000);
        ops.stop(unit,false,edits->world.registry.submit(edits,world.ledger.nextIdentity(),null));
        assertTrue(world.ledger.column(unit).running(),"the unit runs until its ports leave");
        world.runTo(3400);
        var u=world.ledger.column(unit);assertFalse(u.running(),"its ports left: "+ops.status(unit));
        var arrived=counting.moles.getOrDefault(r.feed(),new double[m]);var given=counting.moles.getOrDefault(r.product(),new double[m]);
        for(int i=0;i<m;i++) {
            double in=-arrived[i],inlet=u.station(Port.FEED,0).account().moles()[i],taken=u.totals().taken().moles()[i];
            assertEquals(in,inlet+taken,1e-9*Math.max(1,in),"component "+i+": every arrival is booked");
            assertEquals(u.totals().produced().moles()[i],u.station(Port.DISTILLATE,0).account().moles()[i]+given[i],1e-9*Math.max(1,in),"component "+i+": every gift is debited");
        }
        assertTrue(world.registry.registrations().values().stream().noneMatch(x->x.device().suspendedBy()==unit),"it holds nothing");
    }
    /** Dismantling a running column: a save before its release commits finds every held device's unit, and the unit goes with its ports (merge review, finding 3). */
    @Test void aDismantledColumnLeavesWithItsPorts() {
        var r=rig(0);var world=r.world();var ops=r.ops();long unit=r.unit();
        world.runTo(3000);
        var held=world.ledger.column(unit);double accounts=0;
        ops.stop(unit,true,edits->world.registry.submit(edits,world.ledger.nextIdentity(),null));
        assertNotNull(world.ledger.snapshot(),"a save before the release commits");
        assertTrue(world.ledger.column(unit).leaving());
        double[] before=world.ledger.snapshot().destroyed().moles();
        world.runTo(3400);
        assertNull(world.ledger.column(unit),"the unit left with its ports");
        double[] after=world.ledger.snapshot().destroyed().moles();
        for(int i=0;i<after.length;i++)accounts+=after[i]-before[i];
        assertTrue(accounts>0,"its accounts are booked as destroyed: "+held.stations());
    }
    /** A Stop pressed while the Start's event is still queued queues behind it: the column stops once that Start has committed, and holds nothing. */
    @Test void aStopWhileTheStartIsQueuedTakesEffectBehindIt() {
        var r=rig(0);var world=r.world();long unit=r.unit();
        assertTrue(world.ledger.pending(r.feed()),"the Start is still queued");
        r.ops().stop(unit,false,edits->world.registry.submit(edits,world.ledger.nextIdentity(),null));
        assertTrue(world.ledger.column(unit).running(),"the unit runs until its ports leave");
        world.runTo(600);
        assertFalse(world.ledger.column(unit).running(),"it stopped: "+r.ops().status(unit));
        assertFalse(world.registry.registrations().containsKey(r.feed())||world.registry.registrations().containsKey(r.product()),"its ports left");
    }
    /** A dismantle in that window: the unit leaves too, not a running unit with no structure and its ports still splitting the lines. */
    @Test void aDismantleWhileTheStartIsQueuedTakesTheUnitWithIt() {
        var r=rig(0);var world=r.world();long unit=r.unit();
        assertTrue(world.ledger.pending(r.feed()),"the Start is still queued");
        r.ops().stop(unit,true,edits->world.registry.submit(edits,world.ledger.nextIdentity(),null));
        world.runTo(600);
        assertNull(world.ledger.column(unit),"the unit left with its ports");
        assertFalse(world.registry.registrations().containsKey(r.feed())||world.registry.registrations().containsKey(r.product()),"its ports left");
    }
    /** A second press changes nothing: the first Stop's event carries it, and a dismantle only takes the unit along. */
    @Test void aDismantleAfterAQueuedStopQueuesNothingMore() {
        var r=rig(0);var world=r.world();var ops=r.ops();long unit=r.unit();
        world.runTo(3000);
        ops.stop(unit,false,edits->world.registry.submit(edits,world.ledger.nextIdentity(),null));
        int queued=world.ledger.events().size();
        ops.stop(unit,true,edits->world.registry.submit(edits,world.ledger.nextIdentity(),null));
        assertEquals(queued,world.ledger.events().size(),"no second event");
        assertTrue(world.ledger.column(unit).running(),"the unit runs until its ports leave");
        world.runTo(3400);
        assertNull(world.ledger.column(unit),"the unit left with its ports");
    }
    /** A pump on the feed line, ahead of the boundary valve: the valve meters its small flow with the pump on its law near its relief rise, below its set flow, closed before the first demand (merge review, finding 2). */
    @Test void aPumpOnTheFeedLineRidesBehindItsValve() {
        pumped=true;var r=rig(0);var world=r.world();long unit=r.unit();
        world.runTo(3000);
        assertTrue(world.held.isEmpty(),"no island held: "+world.held);
        var u=world.ledger.column(unit);
        assertTrue(u.totals().taken().total()>40,"the column took its feed: "+u.totals().taken().total()+" mol, "+r.ops().status(unit));
        var island=world.coordinator.snapshots().stream().filter(s->s.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.Pump)).findFirst().orElseThrow();
        var result=island.lastResult().orElseThrow();
        for(int e=0;e<island.graph().pipes().size();e++)if(island.graph().pipes().get(e).control() instanceof FlowControl.Pump pump) {
            assertEquals(FlowControl.Mode.MOVER_LAW,result.endpointModes().get(e));
            var drawn=island.graph().reservoirs().get(island.graph().pipes().get(e).first()).state();
            assertTrue(result.averageMassFlows()[e]<.995*pump.targetVolumeFlow()*drawn.mass()/drawn.volume(),"the valve meters less than the pump's set flow");
        }
    }
    /** A hold tells nobody: an island whose job runs past the last wake and then holds books nothing, and a look a cycle later runs the overdue cycle (merge review, finding 14). */
    @Test void heldPortsDoNotFreezeTheColumn() {
        var r=rig(0);var world=r.world();long unit=r.unit();
        world.runTo(2000);
        world.lag=700;world.failing.add(world.registry.owner(r.feed()));world.failing.add(world.registry.owner(r.product()));
        world.runTo(5000);
        assertFalse(world.held.isEmpty(),"the islands held");
        assertTrue(r.ops().status(unit).startsWith("LIMITED: feed"),r.ops().status(unit));
    }
    /** A product line too narrow for the column's rate fills the outlet's buffer: the cut does not depend on when the jobs complete (merge review, finding 9). */
    @Test void aFullBufferDoesNotDependOnWhenItsIslandsJobsComplete() {
        for(double bore:new double[]{.003,.005}) {
            productBore=bore;var a=rig(0);var b=rig(0);
            b.world().runTo(100);b.world().lags.put(b.world().registry.owner(b.product()),60);
            a.world().runTo(6000);b.world().runTo(6000);
            // The slow world's cycles wait for its islands: the two are compared after the same cycle.
            long cycle=a.ops().run(a.unit()).nextCycle;while(b.ops().run(b.unit()).nextCycle<cycle)b.world().tick();
            var x=a.world().ledger.column(a.unit());var y=b.world().ledger.column(b.unit());
            assertTrue(a.ops().status(a.unit()).startsWith("LIMITED: outlet"),a.ops().status(a.unit()));
            assertArrayEquals(x.totals().taken().moles(),y.totals().taken().moles(),"what the column took at "+bore);
            assertArrayEquals(x.totals().produced().moles(),y.totals().produced().moles(),"what it made at "+bore);
        }
    }
    /** Every component and the energy of unit {@code u} close from port {@code in} through distillate port {@code out}: nothing was given that the account did not hold. */
    private static void closes(Counting counting,ColumnUnit u,long in,long out,int m) {
        var arrived=counting.moles.getOrDefault(in,new double[m]);var given=counting.moles.getOrDefault(out,new double[m]);
        var feed=u.station(Port.FEED,0).account();var made=u.station(Port.DISTILLATE,0).account();
        for(int i=0;i<m;i++) {
            double through=u.totals().taken().moles()[i];
            assertEquals(-arrived[i],feed.moles()[i]+through,1e-9*Math.max(1,through),"component "+i+": what arrived is in the feed account or taken");
            assertEquals(u.totals().produced().moles()[i],made.moles()[i]+given[i],1e-9*Math.max(1,through),"component "+i+": what was made is in the outlet account or given");
        }
        double took=-counting.energy.getOrDefault(in,0.),gave=counting.energy.getOrDefault(out,0.);
        assertEquals(gave,took-feed.energy()-made.energy()+u.totals().condenser(),1e-9*Math.max(1,Math.abs(took)),"the energy closes on the condenser");
    }
    /** What the island's last job solved of {@code port}'s line: each column-worked or player's flow valve as mode, flow and target. */
    private static List<String> valves(EventPathWorld world,long port) {
        var s=world.coordinator.snapshot(world.registry.owner(port));var result=s.lastResult().orElseThrow();var line=new ArrayList<String>();
        for(int e=0;e<s.graph().pipes().size();e++)if(s.graph().pipes().get(e).control() instanceof FlowControl.ControlValve c)line.add(result.endpointModes().get(e)+" "+result.endpointFlows()[e]+" of "+c.massTarget());

        return line;
    }
    /** A player's flow valve above the plan on a product line stands open: the plan's target holds and every component stays exact (owner 2026-11-02, "Lower target wins"; merge review, finding 1). */
    @Test void aSecondFlowValveAboveThePlanStandsOpen() {
        secondValve=1;var r=rig(0);var world=r.world();world.runTo(5000);int m=world.model.componentCount();
        assertTrue(world.held.isEmpty(),"no island held: "+world.held);
        closes(r.counting(),world.ledger.column(r.unit()),r.feed(),r.product(),m);
        var line=valves(world,r.product());
        assertTrue(line.get(0).startsWith("VALVE_REGULATING")&&line.get(1).startsWith("VALVE_OPEN"),line.toString());
        assertTrue(sum(r.counting().moles.get(r.product()))>15,"the outlet gave");
    }
    /** A player's flow valve below the plan regulates the line: the port stands open, what the outlet gives is the lower target, and its buffer fills. */
    @Test void aSecondFlowValveBelowThePlanRegulatesTheLine() {
        secondValve=.002;productBore=.01;var r=rig(0);var world=r.world();world.runTo(8000);int m=world.model.componentCount();
        assertTrue(world.held.isEmpty(),"no island held: "+world.held);
        closes(r.counting(),world.ledger.column(r.unit()),r.feed(),r.product(),m);
        var line=valves(world,r.product());
        assertTrue(line.get(0).startsWith("VALVE_OPEN")&&line.get(1).startsWith("VALVE_REGULATING"),line.toString());
        assertTrue(r.ops().status(r.unit()).startsWith("LIMITED: outlet"),r.ops().status(r.unit()));
    }
    private record Chain(EventPathWorld world,ColumnOperation ops,Counting counting,long first,long second,long feed,long outlet,long inlet,long product){}
    /** Column one's distillate into column two's feed, one nitrogen line through both: with {@code shared} through one valve that bounds both, else a valve of each. */
    private Chain chain(boolean shared,double firstRate,double secondRate) {
        var world=new EventPathWorld();var model=world.model;int m=model.componentCount();var n2=EventPathWorld.nitrogen().composition();
        world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,n2),.05);world.place(1,64,0,Kind.PIPE,Direction.EAST,.05);
        long feedValve=world.place(2,64,0,Kind.VALVE,Direction.EAST),outletValve=world.place(11,64,0,Kind.VALVE,Direction.EAST),inletValve=outletValve;int inletAt=12;
        if(!shared){world.place(12,64,0,Kind.PIPE,Direction.EAST,.05);inletValve=world.place(13,64,0,Kind.VALVE,Direction.EAST);inletAt=14;}
        long productValve=world.place(inletAt+7,64,0,Kind.VALVE,Direction.EAST);world.place(inletAt+8,64,0,Kind.PIPE,Direction.EAST,.05);world.place(inletAt+9,64,0,Kind.RESERVOIR,Direction.EAST);
        var ops=new ColumnOperation(world.ledger,world.registry,world.coordinator,model.components(),model.molecularWeights());var counting=new Counting(ops,world);world.coordinator.ports(counting);
        world.runTo(40);
        long first=world.ledger.reserve(2),second=first+1,feed=world.ledger.nextIdentity(),outlet=feed+1,inlet=feed+2,product=feed+3;
        var g=new PipeResistanceGeometry(world.registry.registrations().get(feedValve).device().geometry());var none=new double[0];
        var a=new Setpoints(firstRate,firstRate,330,1,0,none,none,none);var b=new Setpoints(secondRate,secondRate,330,1,0,none,none,none);
        world.ledger.column(new ColumnUnit(first,new PhysicalFluidTopology.Position(EventPathWorld.DIM,6,70,0),true,a,List.of(new ColumnUnit.Station(Port.FEED,0,feed,feedValve,Account.empty(m)),new ColumnUnit.Station(Port.DISTILLATE,0,outlet,outletValve,Account.empty(m))),ColumnUnit.Totals.empty(m),0));
        world.ledger.column(new ColumnUnit(second,new PhysicalFluidTopology.Position(EventPathWorld.DIM,inletAt+3,70,0),true,b,List.of(new ColumnUnit.Station(Port.FEED,0,inlet,inletValve,Account.empty(m)),new ColumnUnit.Station(Port.DISTILLATE,0,product,productValve,Account.empty(m))),ColumnUnit.Totals.empty(m),0));
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(feed,new WorldTopologyLedger.Registration(port(feed,3,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,330,150000,n2),0)),
                new WorldTopologyLedger.Edit(outlet,new WorldTopologyLedger.Registration(port(outlet,10,Kind.GENERATOR,Direction.EAST,g),new FluidDeviceSpec(1,330,250000,n2),0)),
                new WorldTopologyLedger.Edit(inlet,new WorldTopologyLedger.Registration(port(inlet,inletAt,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,330,150000,n2),0)),
                new WorldTopologyLedger.Edit(product,new WorldTopologyLedger.Registration(port(product,inletAt+6,Kind.GENERATOR,Direction.EAST,g),new FluidDeviceSpec(1,330,250000,n2),0))),product+1,null);
        ops.refresh();world.columns=ops;
        var s=table(model,a);ops.table(first,s.table(),s.enthalpy());s=table(model,b);ops.table(second,s.table(),s.enthalpy());
        return new Chain(world,ops,counting,first,second,feed,outlet,inlet,product);
    }
    /** One column's outlet into another's inlet: two port valves in series, or one valve bounding both. The lower target wins, each column's books stay exact, and neither gives what it does not hold (owner 2026-11-02, "Lower target wins"). */
    @Test void aColumnsOutletFeedsAnother() {
        for(boolean shared:new boolean[]{false,true}) {
            var c=chain(shared,.5,.3);var world=c.world();world.runTo(9000);int m=world.model.componentCount();
            assertTrue(world.held.isEmpty(),"no island held, shared "+shared+": "+world.held);
            closes(c.counting(),world.ledger.column(c.first()),c.feed(),c.outlet(),m);
            closes(c.counting(),world.ledger.column(c.second()),c.inlet(),c.product(),m);
            double given=sum(c.counting().moles.getOrDefault(c.outlet(),new double[m])),taken=-sum(c.counting().moles.getOrDefault(c.inlet(),new double[m]));
            assertTrue(given>100,"the line carried: "+given+" mol, "+c.ops().status(c.first())+" / "+c.ops().status(c.second()));
            assertEquals(given,taken,5,"what one gave the other took, but for the line's own holdup");
            var line=valves(world,c.inlet());
            assertTrue(c.ops().status(c.first()).startsWith("LIMITED: outlet"),"the slower column limits the faster: "+c.ops().status(c.first())+" "+line);
            if(!shared)assertEquals(1,line.stream().filter(v->v.startsWith("VALVE_OPEN")).count(),line.toString());
        }
    }
    /** Solids in the feed leave by the bottoms, every gram: what arrived is at the inlet, in the bottoms' account or given (merge review, finding 10). */
    @Test void solidsInTheFeedLeaveByTheBottoms() {
        // Lines sized for the 18 g/s: slower than its deposition velocity, a line plugs with the solid (SolidMobility).
        feedSolids=new SlurryFeed(1e-4,List.of(new SlurryFeed.Grade("icheme:demo_particle",com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("10"),1)));productPort=Port.BOTTOMS;lineBore=productBore=.005;
        var r=rig(0);var world=r.world();world.runTo(6000);
        var u=world.ledger.column(r.unit());
        double arrived=r.counting().solids.getOrDefault(r.feed(),0.),given=r.counting().solids.getOrDefault(r.product(),0.);
        double inlet=u.station(Port.FEED,0).solids().massKg(),bottoms=u.station(Port.BOTTOMS,0).solids().massKg();

        assertTrue(arrived>0,"solids reached the feed port");assertTrue(given>0,"the bottoms gave solids");
        assertEquals(arrived,inlet+bottoms+given,1e-9*arrived,"inlet "+inlet+", bottoms "+bottoms+", given "+given);
        var stock=0.0;for(var s:world.coordinator.snapshots())for(var n:s.graph().reservoirs())if(n.id()==r.tank())stock=n.inventory().solids().massKg();
        assertTrue(stock>0,"the tank downstream holds them");
    }
    /** A crystal melts as it is taken: its component, with its stored energy made true by its offset; an inert solid stays solid (plan section 11). */
    @Test void aCrystalMeltsAsTheColumnTakesIt() {
        var r=rig(0);var ops=r.ops();int m=r.world().model.componentCount();double[] weights=r.world().model.molecularWeights();
        var ice=new com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial("icheme:ice","1",917,2100,new com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial.Crystal(r.world().model.components().get(m-1),-3e5));
        var sand=new com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial("icheme:sand","1",2600,800,null);
        var size=com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100");
        var stock=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(ice,size,.5),new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(sand,size,2)));
        var held=new double[m];held[0]=3;
        var taken=ops.melted(held,100,stock);
        assertEquals(.5/weights[m-1],taken.moles()[m-1],1e-12);assertEquals(3,taken.moles()[0],0);
        assertEquals(100+.5*-3e5,taken.energy(),1e-9);
        assertEquals(1,stock.withoutCrystals().populations().size());
    }
    /** A port its design lost keeps its account and takes no part in the cycle (merge review, finding 16). */
    @Test void aStationItsDesignLostKeepsItsAccount() {
        var r=rig(0);var world=r.world();long unit=r.unit();int m=world.model.componentCount();
        var u=world.ledger.column(unit);var held=new double[m];held[0]=4;var lost=new ColumnUnit.Station(Port.DRAW,3,0,u.station(Port.FEED,0).valve(),new Account(held,-1e4));
        assertEquals(List.of(lost),new ColumnUnit(unit,u.master(),false,u.setpoints(),List.of(lost),u.totals()).lost(u.stations()));
        var stations=new ArrayList<>(u.stations());stations.add(lost);
        world.ledger.column(u.withStations(true,stations));r.ops().refresh();
        world.runTo(1500);
        assertEquals(4,world.ledger.column(unit).station(Port.DRAW,3).account().total());
        assertTrue(world.ledger.column(unit).totals().taken().total()>0,"the column runs");
        assertTrue(r.ops().view(unit,world.coordinator.now(),valve->.5).streams().stream().noneMatch(s->s.port()==Port.DRAW));
    }
    @Test void aCondenserTemperatureMustBeFinite() {
        assertThrows(IllegalArgumentException.class,()->new Setpoints(.5,.5,Double.POSITIVE_INFINITY,1,0,new double[0],new double[0],new double[0]));
    }
    /**
     * The Operation tab's view (C7, plan section 13) is a read of the run's last cycle: reading it again reads the same and
     * changes no fact or state; it gives each port's rate over that cycle, the feed's temperature on the table's slope, the
     * valve openings it is handed, and the condenser duty booked against the table's.
     */
    @Test void theOperationViewIsAReadOfTheLastCycle() {
        var r=rig(0);var world=r.world();var ops=r.ops();long unit=r.unit();
        world.runTo(3000);
        var before=world.ledger.column(unit);var last=ops.last(unit);long now=world.coordinator.now();
        var view=ops.view(unit,now,valve->.5);
        assertEquals(view,ops.view(unit,now,valve->.5),"a read again reads the same");
        assertSame(before,world.ledger.column(unit),"a read changes no fact or state");
        assertSame(last,ops.last(unit));
        assertTrue(view.running()&&view.editable()&&view.solve()!=null,view.toString());
        var feed=view.streams().stream().filter(s->s.port()==Port.FEED).findFirst().orElseThrow();
        assertEquals(last.feed().total()/5,feed.rate(),1e-12);
        assertEquals(.5,feed.opening());
        assertTrue(feed.temperature()>250&&feed.temperature()<450,"the feed's temperature on the table's slope: "+feed.temperature());
        var product=view.streams().stream().filter(s->s.port()==Port.DISTILLATE).findFirst().orElseThrow();
        assertEquals(last.products().getFirst().total()/5,product.rate(),1e-12);
        assertTrue(product.held()>0,"the outlet's buffer holds product: "+product.held());
        var condenser=view.duties().getFirst();
        assertEquals(com.wormzjl.icheme.science.column.operation.OperationView.DutyKind.CONDENSER,condenser.kind());
        assertEquals(last.condenser()/5,condenser.booked(),1e-9*Math.abs(condenser.booked()));
        world.ledger.column(before.withStations(false,before.stations().stream().map(s->s.withDevice(0)).toList()));
        assertEquals(com.wormzjl.icheme.science.column.operation.OperationView.Phase.STOPPED,ops.view(unit,now,valve->.5).phase(),"a stopped unit reads stopped");
    }
    /**
     * The determinism gate (plan section 9): the same world with its jobs completing three ticks later books the same feed,
     * the same products and the same duties, bit for bit, and gives the same product downstream.
     */
    @Test void aColumnsProductsDoNotDependOnWhenItsIslandsJobsComplete() {
        var a=rig(0);var b=rig(3);a.world().runTo(3000);b.world().runTo(3000);
        var ta=a.world().ledger.column(a.unit()).totals();var tb=b.world().ledger.column(b.unit()).totals();
        assertTrue(ta.taken().total()>40,"the column ran: "+ta.taken().total());
        assertArrayEquals(ta.taken().moles(),tb.taken().moles(),"the feed taken");
        assertArrayEquals(ta.produced().moles(),tb.produced().moles(),"the products");
        assertEquals(ta.condenser(),tb.condenser(),"the condenser duty");
        assertArrayEquals(a.counting().moles.get(a.product()),b.counting().moles.get(b.product()),"the product given downstream");
    }

    /** The cycle at {@code expected} ran at {@code actual}: a cycle runs once its inlets booked through it, a tick or so later. */
    private static void assertCycle(long expected,long actual,String message){assertTrue(Math.abs(actual-expected)<ColumnOperation.CYCLE_TICKS/2,message+": expected "+expected+", was "+actual);}
    /** A solve's table: everything to the distillate at 330 K, 1 kJ/mol below what the feed brings, so the condenser removes it. */
    static ColumnSolve.Solved table(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model,Setpoints setpoints){return table(model,setpoints,setpoints.steamPerFeed());}
    static ColumnSolve.Solved table(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model,Setpoints setpoints,double[] steam){return table(model,setpoints,steam,Port.DISTILLATE);}
    /** The table at the steam ratios {@code steam}: the water leaves at the steam's own enthalpy, so the closure holds at any ratio. */
    static ColumnSolve.Solved table(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model,Setpoints setpoints,double[] steam,Port port) {
        int m=model.componentCount();var n2=EventPathWorld.nitrogen().composition();
        double h=model.flashTP(340,300000,n2.clone(),()->{}).enthalpy()/sum(n2);
        var water=new double[m];water[m-1]=1;double hs=model.flashTP(450,300000,water,()->{}).enthalpy();
        var rows=new double[1][m];Arrays.fill(rows[0],h-1000);rows[0][m-1]=hs;
        var z=new double[m];for(int i=0;i<m;i++)z[i]=n2[i]/sum(n2);
        var all=new double[m];Arrays.fill(all,1);var held=new boolean[m];Arrays.fill(held,true);
        var table=new SplitTable(model.components(),List.of(new SplitTable.Product(port,0,all,330,250000)),0,new double[0],-1000,
                new SplitTable.Reference(z,h,30,steam,1,setpoints,330,new double[steam.length]),held);
        return new ColumnSolve.Solved(table,rows,null,-1000,"test","");
    }
    /** A solver answering every job {@code delay} ticks after it was asked (0: at once): {@link #table}, or a failure where {@code fail} holds. */
    static final class Fake implements ColumnOperation.Solves {
        static final com.wormzjl.icheme.science.column.v3.V3ColumnInput DESIGN;
        static{var c=MaterialCatalog.bundled();DESIGN=c.presets().column(c.presets().network().defaultColumnPreset()).input(c);}
        com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model;EventPathWorld world;final long delay;
        java.util.function.Predicate<ColumnSolve.Job> fail=j->false,unfinished=j->false;
        /** The hydraulics each table carries (W3): none by default. */
        java.util.function.Function<ColumnSolve.Job,SplitTable.Flood> flood=j->SplitTable.Flood.NONE;
        /** The duties each table carries beyond the condenser's (W4), or null for none. */
        java.util.function.UnaryOperator<SplitTable> duties;
        final List<ColumnSolve.Job> jobs=new ArrayList<>();final List<Long> asked=new ArrayList<>();
        final TreeMap<Long,List<Runnable>> due=new TreeMap<>();
        Fake(long delay){this.delay=delay;}
        public com.wormzjl.icheme.science.column.v3.V3ColumnInput design(ColumnUnit unit){return DESIGN;}
        public boolean submit(long unit,ColumnSolve.Job job,java.util.function.Consumer<ColumnSolve.Outcome> done) {
            jobs.add(job);long now=world.ledger.onlineTick();asked.add(now);
            var taken=job.taken();var ratios=new double[taken.steam().size()];for(int j=0;j<ratios.length;j++)ratios[j]=taken.feedRate()>0?taken.steam().get(j).rate()/taken.feedRate():job.setpoints().steamPerFeed()[j];
            ColumnSolve.Outcome o=fail.test(job)?new ColumnSolve.Failed("test refusal"):unfinished.test(job)?new ColumnSolve.Failed("DEADLINE",true):table(model,job.setpoints(),job.trigger()==DriftBands.Trigger.START?job.setpoints().steamPerFeed():ratios);
            if(o instanceof ColumnSolve.Solved s&&flood.apply(job).known())o=new ColumnSolve.Solved(s.table().withFlood(flood.apply(job)),s.enthalpy(),null,s.solvedCondenser(),"test","");
            if(o instanceof ColumnSolve.Solved s&&duties!=null)o=new ColumnSolve.Solved(duties.apply(s.table()),s.enthalpy(),null,s.solvedCondenser(),"test","");
            var answer=o;
            if(delay==0)done.accept(answer);else due.computeIfAbsent(now+delay,t->new ArrayList<>()).add(()->done.accept(answer));
            return true;
        }
        /** Runs the world tick by tick to {@code tick}, each answer at its tick. */
        void runTo(long tick) {
            while(world.ledger.onlineTick()<tick) {
                world.runTo(world.ledger.onlineTick()+1);
                var ready=due.headMap(world.ledger.onlineTick(),true);var answers=new ArrayList<Runnable>();ready.values().forEach(answers::addAll);ready.clear();
                answers.forEach(Runnable::run);
            }
        }
        List<Long> asked(DriftBands.Trigger trigger){var out=new ArrayList<Long>();for(int i=0;i<jobs.size();i++)if(jobs.get(i).trigger()==trigger)out.add(asked.get(i));return out;}
    }

    /**
     * Fixed application (owner Q5, plan section 9): a start asks its solve at the column's first cycle and its table applies at
     * the second cycle after, whether the solve answers at once, before that cycle, or after it (the cycle then waits); the
     * three worlds book the same feed, products and condenser duty bit for bit.
     */
    @Test void aTableAppliesAtTheSecondCycleAfterItsTriggerHoweverLongItsSolveTakes() {
        var worlds=new ArrayList<Rig>();var fakes=new ArrayList<Fake>();
        for(long delay:new long[]{0,150,350}){var f=new Fake(delay);var r=rig(0,f);f.world=r.world();worlds.add(r);fakes.add(f);}
        for(var f:fakes)while(f.asked.isEmpty())f.runTo(f.world.ledger.onlineTick()+1);
        long start=fakes.get(0).asked(DriftBands.Trigger.START).get(0),cycle=worlds.get(0).ops().run(worlds.get(0).unit()).nextCycle-ColumnOperation.CYCLE_TICKS;
        for(int k=0;k<3;k++) {
            var f=fakes.get(k);var r=worlds.get(k);
            assertEquals(List.of(start),f.asked(DriftBands.Trigger.START),"one start solve, at the first cycle");
            var run=r.ops().run(r.unit());
            if(f.delay>ColumnOperation.APPLY_TICKS){f.runTo(start+f.delay-1);assertNull(run.table,"its cycle waits for the solve");}
            while(run.table==null)f.runTo(f.world.ledger.onlineTick()+1);
            assertTrue(r.ops().status(r.unit()).startsWith("RUNNING")||r.ops().status(r.unit()).startsWith("LIMITED"),r.ops().status(r.unit()));
            assertEquals(cycle,run.tableTick,"the start solve was asked at the first cycle");
            assertEquals(run.tableTick+ColumnOperation.APPLY_TICKS,run.applied,"its table applied at the second cycle after");
            f.runTo(3000);
            assertNotNull(r.ops().run(r.unit()).table);
        }
        var ta=worlds.get(0).world().ledger.column(worlds.get(0).unit()).totals();
        assertTrue(ta.taken().total()>20,"the column ran: "+ta.taken().total());
        for(int k=1;k<3;k++) {
            var tb=worlds.get(k).world().ledger.column(worlds.get(k).unit()).totals();
            assertArrayEquals(ta.taken().moles(),tb.taken().moles(),"the feed taken, solve delay "+fakes.get(k).delay);
            assertArrayEquals(ta.produced().moles(),tb.produced().moles(),"the products, solve delay "+fakes.get(k).delay);
            assertEquals(ta.condenser(),tb.condenser(),"the condenser duty, solve delay "+fakes.get(k).delay);
        }
    }
    /**
     * A setpoint edit solves at the next cycle (plan section 6); a refused one asks that one solve and no second (W2): the
     * table stays, the cycle keeps the accepted steam ratio, the status names the refusal and the view carries the accepted
     * set points beside the requested ones. Applying other values solves once more.
     */
    @Test void aRefusedSetpointAsksOneSolveAndTheCycleKeepsTheAcceptedOnes() {
        var f=new Fake(0);var r=rig(0,f,true,false);f.world=r.world();var world=r.world();
        f.runTo(1000);
        var kept=r.ops().run(r.unit()).table;assertNotNull(kept);
        var u=world.ledger.column(r.unit());var s=u.setpoints();
        world.ledger.column(u.withSetpoints(new Setpoints(s.designFeedRate(),s.feedRate(),s.condenserTemperature(),2,s.reboilerPerFeed(),new double[]{.4},s.drawsPerFeed(),s.pumparoundsPerFeed())));
        f.fail=j->j.setpoints().refluxRatio()==2;
        f.runTo(5000);
        var asked=f.asked(DriftBands.Trigger.SETPOINT);
        assertEquals(1,asked.size(),"one solve, no retry: "+asked);
        assertTrue(asked.get(0)<=1000+ColumnOperation.CYCLE_TICKS,"at the next cycle: "+asked);
        var run=r.ops().run(r.unit());
        assertSame(kept,run.table,"the table stays");
        assertTrue(r.ops().status(r.unit()).contains("setpoint solve failed (test refusal)"),r.ops().status(r.unit()));
        assertNotNull(Condition.find(r.ops().conditions(r.unit()),Condition.SETPOINT_REFUSED));
        assertEquals(2,world.ledger.column(r.unit()).setpoints().refluxRatio(),"the requested set points stay the unit's fact");
        // The steam inlet is demanded at the accepted ratio, not the refused one.
        var level=run.levels.get(r.feed()+2).peekLast();
        assertEquals(s.feedRate()*.2,level.rate(),1e-12,"the accepted steam ratio");
        var view=r.ops().view(r.unit(),world.ledger.onlineTick(),v->Double.NaN);
        assertNotNull(view.accepted(),"the accepted set points beside the requested ones");
        assertEquals(1,view.accepted().refluxRatio());assertEquals(2,view.setpoints().refluxRatio());
        // Other values: one solve, which applies, and the refusal goes.
        f.fail=j->false;var v=world.ledger.column(r.unit());
        world.ledger.column(v.withSetpoints(new Setpoints(s.designFeedRate(),s.feedRate(),s.condenserTemperature(),3,s.reboilerPerFeed(),new double[]{.4},s.drawsPerFeed(),s.pumparoundsPerFeed())));
        f.runTo(world.ledger.onlineTick()+1000);
        assertEquals(2,f.asked(DriftBands.Trigger.SETPOINT).size(),"the new values solved once");
        assertEquals(3,r.ops().run(r.unit()).table.reference().setpoints().refluxRatio());
        assertFalse(r.ops().status(r.unit()).contains("failed"),r.ops().status(r.unit()));
        assertNull(r.ops().view(r.unit(),world.ledger.onlineTick(),x->Double.NaN).accepted(),"accepted and requested agree");
    }
    /**
     * A refused drift state is not solved again while the state taken stays inside its bands, and is solved once outside them
     * (W2): the feed rate cut to half asks a THROUGHPUT solve that is refused; the column runs on the old table for a long
     * time with no second solve; cut to a fifth, outside the refused state's band, it solves once.
     */
    @Test void aRefusedStateIsNotSolvedAgainInsideItsBands() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        f.runTo(1500);
        var kept=r.ops().run(r.unit()).table;var u=world.ledger.column(r.unit());
        world.ledger.column(u.withSetpoints(u.setpoints().withFeedRate(.25)));
        f.fail=j->j.trigger()==DriftBands.Trigger.THROUGHPUT;
        f.runTo(8000);
        assertEquals(1,f.asked(DriftBands.Trigger.THROUGHPUT).size(),"one solve of the state: "+f.asked(DriftBands.Trigger.THROUGHPUT));
        assertSame(kept,r.ops().run(r.unit()).table,"the old table runs");
        assertTrue(r.ops().status(r.unit()).contains("the throughput solve failed (test refusal)"),r.ops().status(r.unit()));
        assertTrue(EquipmentAlarm.column(r.ops().conditions(r.unit())).contains(EquipmentAlarm.OFF_TABLE),"the column reads OFF TABLE");
        f.fail=j->false;var v=world.ledger.column(r.unit());long moved=world.ledger.onlineTick();
        world.ledger.column(v.withSetpoints(v.setpoints().withFeedRate(.1)));
        f.runTo(moved+2000);
        // (The fake's tables stand at the design throughput, so its later tables cross THROUGHPUT again: only the first counts here.)
        var asked=f.asked(DriftBands.Trigger.THROUGHPUT);
        assertTrue(asked.size()>=2&&asked.get(1)>moved&&asked.get(1)<=moved+2*ColumnOperation.CYCLE_TICKS,"outside the refused state's band, at the next cycle: "+asked);
        assertNotSame(kept,r.ops().run(r.unit()).table);
        assertFalse(r.ops().status(r.unit()).contains("failed"),r.ops().status(r.unit()));
    }
    /**
     * A solve the worker did not finish (a deadline) is not a refusal (W2): it is asked once more 30 s after it applied; a
     * second such failure refuses the state.
     */
    @Test void anUnfinishedSolveIsAskedOnceMore() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        f.runTo(1500);
        var u=world.ledger.column(r.unit());
        world.ledger.column(u.withSetpoints(u.setpoints().withFeedRate(.25)));
        f.unfinished=j->j.trigger()==DriftBands.Trigger.THROUGHPUT;
        f.runTo(1600+ColumnOperation.APPLY_TICKS+ColumnOperation.TRANSIENT_WAIT+50);
        assertFalse(r.ops().status(r.unit()).contains("failed"),"the first was no refusal: "+r.ops().status(r.unit()));
        f.runTo(world.ledger.onlineTick()+6000);
        var asked=f.asked(DriftBands.Trigger.THROUGHPUT);
        assertEquals(2,asked.size(),"once more, then no more: "+asked);
        assertCycle(asked.get(0)+ColumnOperation.APPLY_TICKS+ColumnOperation.TRANSIENT_WAIT,asked.get(1),"30 s after the first applied");
        assertTrue(r.ops().status(r.unit()).contains("the throughput solve failed (DEADLINE)"),"the second refuses the state: "+r.ops().status(r.unit()));
    }
    /**
     * A trip (W1.7, F2): the cycle takes nothing and the column's channels still answer, its flows 0 and its pressures and
     * temperatures the table's; the status is UPSET, the binding the closure, the alarm UPSET.
     */
    @Test void aTrippedColumnsChannelsStillAnswer() {
        var r=rig(0);var world=r.world();var ops=r.ops();long unit=r.unit();
        world.runTo(1500);
        var run=ops.run(unit);var t=run.table;
        // A table whose solved condenser duty is a hundred times the closure: the next cycle departs and trips.
        ops.table(unit,new SplitTable(t.axis(),t.products(),t.reboilerPerFeed(),t.pumparoundPerFeed(),100*t.condenserPerFeed(),t.reference(),t.held()),run.enthalpy);
        run.probes=new ColumnUnit.Probes(150000,370,180000,430,145000,330,.15,.3);
        world.runTo(1800);
        assertTrue(ops.last(unit).tripped(),ops.status(unit));
        assertTrue(ops.status(unit).startsWith("UPSET: the condenser duty departs"),ops.status(unit));
        assertEquals(Condition.CLOSURE,ops.last(unit).binding().condition());
        assertTrue(EquipmentAlarm.column(ops.conditions(unit)).contains(EquipmentAlarm.UPSET));
        assertEquals(370,ops.instrument(unit,"COLUMN:top",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE).value(),"the table's temperature");
        assertEquals(180000,ops.instrument(unit,"COLUMN:bottom",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE).value(),"the table's pressure");
        assertEquals(0,ops.instrument(unit,"REFLUX",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW).value(),"no flow");
        assertEquals(0,ops.instrument(unit,"COLUMN_PORT:FEED:0",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW).value(),"no feed taken");
        assertNotNull(ops.instrument(unit,"COLUMN_PORT:DISTILLATE:0",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE),"a product's temperature, the table's");
    }
    /**
     * The table is state the topology unit saves (plan section 12): a world read from its checkpoint resumes the running
     * column on its saved table and windows, takes its feed on, and asks no start solve.
     */
    @Test void aReloadResumesOnTheSavedTableWithoutASolve() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        f.runTo(1500);
        var before=r.ops().run(r.unit());assertNotNull(before.table);
        var probes=new ColumnUnit.Probes(150000,370,180000,430,145000,330,.15,.3);before.probes=probes;
        f.runTo(1600);
        double taken=world.ledger.column(r.unit()).totals().taken().total();assertTrue(taken>0,"it ran before the save");
        var bytes=FluidCheckpointCodec.encodeTopology(world.ledger.snapshot());
        var saved=FluidCheckpointCodec.decodeTopology(bytes,world.ledger.onlineTick(),k->world.model);
        var state=saved.columns().get(r.unit()).state();
        assertNotNull(state,"the running unit's engine state is in the topology unit");
        assertArrayEquals(before.table.products().getFirst().recovery(),state.table().products().getFirst().recovery());
        assertArrayEquals(before.enthalpy,state.enthalpy());
        assertEquals(before.tableTick,state.tableTick());
        assertEquals(probes,state.probes(),"accepted probe readings round trip with rebuildable state");
        assertFalse(state.levels().isEmpty(),"the inlet's levels through the fence");
        var again=new EventPathWorld(1,saved,world.entries());
        var g=new Fake(0);g.world=again;g.model=again.model;
        var ops=new ColumnOperation(again.ledger,again.registry,again.coordinator,again.model.components(),again.model.molecularWeights());
        ops.solves(g);again.coordinator.ports(ops);ops.refresh();
        assertNotNull(ops.run(r.unit()).table,"the saved table at once");
        g.runTo(again.ledger.onlineTick()+1000);
        assertTrue(g.asked(DriftBands.Trigger.START).isEmpty(),"no start solve: "+g.jobs);
        assertTrue(again.ledger.column(r.unit()).totals().taken().total()>taken+10,"it takes its feed on after the reload");
        assertEquals(370,ops.instrument(r.unit(),"COLUMN:top",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE).value());
        assertEquals(.15*ops.run(r.unit()).last.feedMoles()/5,ops.instrument(r.unit(),"REFLUX",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW).value(),1e-12,"internal flow follows accepted throughput after reload");
        // A stopped unit keeps no engine state.
        var u=again.ledger.column(r.unit());assertNull(u.withStations(false,u.stations().stream().map(s->s.withDevice(0)).toList()).state());
    }
    /** A solver's hydraulics where the worst tray stands at 0.6 of flood per unit of reflux ratio at the design feed (0.5 mol/s): tray 12, vapour limited. */
    private static SplitTable.Flood floodByReflux(ColumnSolve.Job job){return new SplitTable.Flood(.6*job.setpoints().refluxRatio()/.5,12,true,1000,3000);}
    private void reflux(Rig r,double ratio){var u=r.world().ledger.column(r.unit());var s=u.setpoints();
        r.world().ledger.column(u.withSetpoints(new Setpoints(s.designFeedRate(),s.feedRate(),s.condenserTemperature(),ratio,s.reboilerPerFeed(),s.steamPerFeed(),s.drawsPerFeed(),s.pumparoundsPerFeed())));}
    /**
     * T5 and T7 (batch 2026-11-10-column-failure-modes, W3.1, W3.2): a reflux raised from 1 to 2 solves to 120 % of flood at
     * the set feed; the cycle takes 1/1.2 of it, the status reads the limit and the alarm FLOODING is raised, the channels
     * answer through it (the live drop, the feed). Lowered to 1.5 the feed comes back whole, at 90 % of flood: HIGH LOAD on
     * the line and no alarm. No solve beyond the two set-point solves.
     */
    @Test void aRefluxThatFloodsCutsTheFeedToItsLimitAndLoweringItLiftsIt() {
        var f=new Fake(0);f.flood=ColumnRunTest::floodByReflux;var r=rig(0,f);f.world=r.world();var world=r.world();var ops=r.ops();long unit=r.unit();
        f.runTo(1500);
        assertTrue(ops.status(unit).startsWith("RUNNING"),ops.status(unit));
        assertTrue(EquipmentAlarm.column(ops.conditions(unit)).isEmpty(),"60 % of flood: nothing");
        reflux(r,2);
        f.runTo(3000);
        assertEquals(1,f.asked(DriftBands.Trigger.SETPOINT).size());
        assertTrue(ops.status(unit).startsWith("LIMITED: flooding, 83 %"),ops.status(unit));
        assertTrue(ops.status(unit).contains("tray 13 at 100 % of flood"),ops.status(unit));
        var last=ops.last(unit);
        assertEquals(Condition.FLOODING,last.binding().condition());
        assertEquals(.5/1.2,last.feedMoles()/5,1e-9,"the feed taken falls to the cap");
        assertEquals(java.util.Set.of(EquipmentAlarm.FLOODING),EquipmentAlarm.column(ops.conditions(unit)),"FLOODING while the limit binds");
        var high=Condition.find(ops.conditions(unit),Condition.HIGH_LOAD);
        assertNotNull(high);assertEquals(1,high.value(),1e-9);
        // The channels answer during a limit: the live drop at the feed taken (the fake's tables stand at the design rate), the feed.
        double drop=ops.instrument(unit,ColumnOperation.DIFFERENTIAL,com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE).value();
        assertEquals(1000/1.44+3000,drop,1e-6,"the dry drop by the square of the feed, the liquid head as solved");
        assertTrue(ops.instrument(unit,"COLUMN_PORT:FEED:0",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW).value()>0);
        reflux(r,1.5);
        f.runTo(4500);
        assertEquals(2,f.asked(DriftBands.Trigger.SETPOINT).size(),"two set-point solves and no other: "+f.jobs.stream().map(ColumnSolve.Job::trigger).toList());
        assertEquals(1,f.asked(DriftBands.Trigger.START).size());
        assertEquals(3,f.jobs.size(),"no solve beyond the start and the two set points");
        assertTrue(ops.status(unit).startsWith("RUNNING; tray 13 at 90 % of flood"),ops.status(unit));
        assertEquals(.5,ops.last(unit).feedMoles()/5,1e-9,"the whole set feed");
        assertTrue(EquipmentAlarm.column(ops.conditions(unit)).isEmpty(),"HIGH LOAD raises no alarm");
    }
    /**
     * T8 (W3, step 6): the table's hydraulics are state the topology unit saves. A restored state limits as before the save,
     * with no solve; a state written without them (here: an empty flood record, which reads as absent) is dropped, and the
     * unit then solves once from its start.
     */
    /**
     * A feed-rate loop (W6.3): a planned feed below the set rate holds the feed the cycle takes at the plan; a plan above
     * the set rate changes nothing; with no plan (the loop off) the set rate returns. The unit's saved set points stay.
     */
    @Test void aFeedLoopsPlanHoldsTheFeedBelowTheSetRateAndOffReturnsIt() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        double[] plan={Double.NaN};
        r.ops().feedPlans(u->plan[0]);
        f.runTo(1500);
        var saved=world.ledger.column(r.unit()).setpoints();
        double seconds=ColumnOperation.CYCLE_TICKS/20.0;
        double free=r.ops().run(r.unit()).last.feedMoles()/seconds;
        assertTrue(free>0,"the column takes feed");
        plan[0]=free/2;
        f.runTo(3000);
        assertEquals(free/2,r.ops().run(r.unit()).last.feedMoles()/seconds,free*.02,"the feed held at the plan");
        assertEquals(free/2,r.ops().feedRate(world.ledger.column(r.unit())),1e-12);
        assertSame(saved,world.ledger.column(r.unit()).setpoints(),"the saved set points do not change");
        plan[0]=saved.feedRate()*2;
        assertEquals(saved.feedRate(),r.ops().feedRate(world.ledger.column(r.unit())),0,"a plan above the set rate: the set rate");
        plan[0]=Double.NaN;
        f.runTo(4500);
        assertEquals(free,r.ops().run(r.unit()).last.feedMoles()/seconds,free*.02,"no plan: the set rate returns");
        assertSame(saved,world.ledger.column(r.unit()).setpoints());
    }
    /**
     * Set points before the first run (W7): {@code editColumn} gives a column that never ran a stopped unit with no stations
     * on the edited set points. The facts carry it through a save, and the engine runs no cycle for it.
     */
    @Test void aStoppedUnitWithNoStationsSurvivesTheFacts() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        var design=world.ledger.column(r.unit()).setpoints();
        var edited=new Setpoints(design.designFeedRate(),design.feedRate()/2,design.condenserTemperature(),design.refluxRatio()*1.1,design.reboilerPerFeed(),
                design.steamPerFeed(),design.drawsPerFeed(),design.pumparoundsPerFeed());
        var master=new PhysicalFluidTopology.Position("minecraft:overworld",500,64,500);
        var stopped=new ColumnUnit(world.ledger.reserve(1),master,false,edited,List.of(),ColumnUnit.Totals.empty(world.model.componentCount()));
        world.ledger.column(stopped);r.ops().refresh();
        f.runTo(300);
        assertNull(r.ops().run(stopped.id()),"no run for a stopped unit");
        var saved=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),world.ledger.onlineTick(),k->world.model);
        var back=saved.columns().get(stopped.id());
        assertNotNull(back,"the stopped unit is a fact of the save");
        assertFalse(back.running());
        assertTrue(back.stations().isEmpty());
        assertEquals(master,back.master());
        assertFalse(back.setpoints().intensivelyDiffers(edited),"its set points round trip");
        assertEquals(edited.feedRate(),back.setpoints().feedRate(),0);
    }
    @Test void aSavedFloodLimitRestoresAndAStateWithoutItSolvesAgain() {
        var f=new Fake(0);f.flood=j->new SplitTable.Flood(1.2/.5,12,true,1000,3000);var r=rig(0,f);f.world=r.world();var world=r.world();
        f.runTo(1500);
        assertTrue(r.ops().status(r.unit()).startsWith("LIMITED: flooding, 83 %"),r.ops().status(r.unit()));
        // Accepted readings, as a real solve leaves them: a state without them rebuilds them by a solve.
        r.ops().run(r.unit()).probes=new ColumnUnit.Probes(150000,370,180000,430,145000,330,.15,.3);
        f.runTo(1600);
        var bytes=FluidCheckpointCodec.encodeTopology(world.ledger.snapshot());
        var saved=FluidCheckpointCodec.decodeTopology(bytes,world.ledger.onlineTick(),k->world.model);
        assertEquals(f.flood.apply(null),saved.columns().get(r.unit()).state().table().flood(),"the hydraulics round trip");
        var again=new EventPathWorld(1,saved,world.entries());
        var g=new Fake(0);g.world=again;g.model=again.model;
        var ops=new ColumnOperation(again.ledger,again.registry,again.coordinator,again.model.components(),again.model.molecularWeights());
        ops.solves(g);again.coordinator.ports(ops);ops.refresh();
        g.runTo(again.ledger.onlineTick()+1000);
        assertTrue(g.jobs.isEmpty(),"no solve: "+g.jobs);
        assertTrue(ops.status(r.unit()).startsWith("LIMITED: flooding, 83 %"),"it limits as before the save: "+ops.status(r.unit()));
        // The same state with its flood record empty: dropped by the reader, so the unit solves again.
        var u=world.ledger.column(r.unit());
        var state=FluidFacts.topologyState(List.of(),Map.of(u.id(),u));
        var flood=u.state().table().flood();
        var record=new java.io.ByteArrayOutputStream();record.write(5);
        for(double v:new double[]{flood.perFeed()}){long bits=Double.doubleToRawLongBits(v);for(int s=56;s>=0;s-=8)record.write((int)(bits>>>s));}
        record.write(flood.tray());record.write(flood.vapourLimited()?1:0);
        for(double v:new double[]{flood.dryPascal(),flood.liquidPascal()}){long bits=Double.doubleToRawLongBits(v);for(int s=56;s>=0;s-=8)record.write((int)(bits>>>s));}
        byte[] needle=record.toByteArray();int at=-1;
        for(int i=0;i+needle.length<=state.length&&at<0;i++)if(Arrays.equals(state,i,i+needle.length,needle,0,needle.length))at=i;
        assertTrue(at>0,"the flood record in the state");
        var older=new byte[state.length-needle.length+1];System.arraycopy(state,0,older,0,at);older[at]=0;System.arraycopy(state,at+needle.length,older,at+1,state.length-at-needle.length);
        assertNull(FluidFacts.topologyState(older,0,older.length).columns().get(u.id()),"a state without its hydraulics is dropped");
        var bare=new EventPathWorld(1,saved,world.entries());bare.ledger.column(bare.ledger.column(r.unit()).withState(null));
        var h=new Fake(0);h.world=bare;h.model=bare.model;h.flood=f.flood;
        var fresh=new ColumnOperation(bare.ledger,bare.registry,bare.coordinator,bare.model.components(),bare.model.molecularWeights());
        fresh.solves(h);bare.coordinator.ports(fresh);fresh.refresh();
        h.runTo(bare.ledger.onlineTick()+1000);
        assertEquals(1,h.asked(DriftBands.Trigger.START).size(),"one start solve");
        assertTrue(fresh.status(r.unit()).startsWith("LIMITED: flooding, 83 %"),fresh.status(r.unit()));
    }
    /**
     * A finding of stage 1 (W3, step 7b): the condenser's water reads its ceiling from the accepted condenser temperature, not
     * a requested one whose solve has not applied.
     */
    @Test void theWatersCeilingFollowsTheAcceptedCondenserTemperature() {
        var r=rig(0,null,false,false,true);var world=r.world();var ops=r.ops();
        world.runTo(1500);
        var u=world.ledger.column(r.unit());var s=u.setpoints();
        world.ledger.column(u.withSetpoints(new Setpoints(s.designFeedRate(),s.feedRate(),320,s.refluxRatio(),s.reboilerPerFeed(),s.steamPerFeed(),s.drawsPerFeed(),s.pumparoundsPerFeed())));
        world.runTo(1800);
        var read=ops.heatRead(PhysicalFluidTopology.sideIdentity(r.tube()),PhysicalFluidTopology.sideIdentity(r.shell()),world.ledger.onlineTick(),5,0);
        assertEquals(330-com.wormzjl.icheme.science.equipment.HeatSides.WATER_APPROACH,read.ceiling(),1e-12,"the accepted 330 K less the approach");
    }
    /**
     * A blocked product cuts the feed (plan section 7, owner Q1): with nothing downstream of the product valve the outlet's
     * buffer fills to 120 s of design production, the cycle's throughput falls to nothing, and the feed valve closes on
     * the inlet's full account.
     */
    @Test void aBlockedProductCutsTheFeed() {
        var r=rig(0,null,false,true);var world=r.world();
        world.runTo(5000);
        double arrived=-sum(r.counting().moles.getOrDefault(r.feed(),new double[1]));
        world.runTo(6000);
        var u=world.ledger.column(r.unit());
        assertTrue(r.ops().status(r.unit()).startsWith("LIMITED: outlet"),r.ops().status(r.unit()));
        double held=u.station(Port.DISTILLATE,0).account().total(),buffer=.5*ColumnOperation.BUFFER_SECONDS;
        assertEquals(com.wormzjl.icheme.science.column.operation.Condition.PRODUCT_FULL,r.ops().last(r.unit()).binding().condition());
        assertTrue(EquipmentAlarm.column(r.ops().conditions(r.unit())).contains(EquipmentAlarm.BLOCKED),"the column raises BLOCKED: "+r.ops().conditions(r.unit()));
        assertEquals(buffer,held,1e-6*buffer,"the outlet holds its full buffer");
        assertEquals(held,u.totals().taken().total(),1e-9,"the column took what its outlet holds, no more");
        double later=-sum(r.counting().moles.getOrDefault(r.feed(),new double[1]));
        assertTrue(later-arrived<.01,"the feed valve closed: "+(later-arrived)+" mol in the last 50 s");
    }
    /**
     * Rule B with A where a solve fails (owner Q1, C1): the steam supply lost, the column first takes its feed as it comes
     * and solves on the short steam's ratio; that solve refused (no steam, no reboiler), it cuts every input together, so the
     * feed stops, and says so. While the steam stays lost its shortage stays inside the refused state's bands: no solve is
     * asked again (W2). The steam back, rule A clears and rule B takes it.
     */
    @Test void underRuleBAShortSteamSolvesOnItsRatioAndAFailedSolveFallsBackToA() {
        var f=new Fake(0);var r=rig(0,f,true,false);f.world=r.world();var world=r.world();
        f.runTo(2500);
        assertTrue(world.ledger.column(r.unit()).totals().taken().total()>20,"it ran on its steam");
        int settled=f.asked(DriftBands.Trigger.STEAM_RATIO).size();
        f.fail=j->j.taken().steam().getFirst().rate()==0;
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(r.steamSource(),null)),world.ledger.nextIdentity(),null);
        long lost=world.ledger.onlineTick();
        while(r.ops().run(r.unit()).ruleA==false&&world.ledger.onlineTick()<lost+4000)f.runTo(world.ledger.onlineTick()+10);
        var run=r.ops().run(r.unit());
        assertTrue(run.ruleA,"a failed solve of the lost steam brought rule A: "+f.jobs.stream().map(j->j.trigger()+" "+j.taken().steam().getFirst().rate()).toList());
        var asked=f.asked(DriftBands.Trigger.STEAM_RATIO);
        assertTrue(asked.size()>settled,"it solved on the short steam");
        assertTrue(r.ops().status(r.unit()).contains("rule A")&&r.ops().status(r.unit()).contains("steam"),r.ops().status(r.unit()));
        double before=world.ledger.column(r.unit()).totals().taken().total();
        f.runTo(world.ledger.onlineTick()+500);
        assertEquals(before,world.ledger.column(r.unit()).totals().taken().total(),1e-9,"rule A: no steam, no feed taken");
        // The steam still lost: its shortage stays inside the refused state's bands, so no solve is asked again and A stays.
        int refused=f.asked(DriftBands.Trigger.STEAM_RATIO).size();
        f.runTo(world.ledger.onlineTick()+3000);
        assertEquals(refused,f.asked(DriftBands.Trigger.STEAM_RATIO).size(),"no solve of the refused state again: "+f.asked(DriftBands.Trigger.STEAM_RATIO));
        assertTrue(r.ops().run(r.unit()).ruleA,"rule A stays while the steam is lost");
        // The steam back: no longer short, rule A clears and the column takes its feed again under rule B.
        var water=new double[world.model.componentCount()];water[water.length-1]=1;
        world.place(0,64,2,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,450,300000,water),.05);
        double stopped=world.ledger.column(r.unit()).totals().taken().total();
        f.runTo(world.ledger.onlineTick()+3000);
        assertFalse(r.ops().run(r.unit()).ruleA,"rule A cleared: "+r.ops().status(r.unit()));
        assertTrue(world.ledger.column(r.unit()).totals().taken().total()>stopped+10,"the feed is taken again");
    }
    /**
     * A water-cooled condenser (C6b, plan section 10): its tubes stay in the cooling-water line while its shell is the
     * column's; the heat each cycle's condenser removes goes into transit to the water and the water takes it. Every joule
     * the column's condenser removed is in the water or still in transit.
     */
    @Test void aWaterCooledCondenserGivesItsDutyToItsCoolingWater() {
        var r=rig(0,null,false,false,true);var world=r.world();
        world.runTo(4000);
        var u=world.ledger.column(r.unit());
        assertEquals(r.tube(),u.condenser());
        double removed=-u.totals().condenser(),water=r.counting().heat.getOrDefault(PhysicalFluidTopology.sideIdentity(r.tube()),0.),owed=world.coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(r.tube()),PhysicalFluidTopology.sideIdentity(r.shell()),world.ledger.onlineTick());
        assertTrue(removed>10000,"the column's condenser removed heat: "+removed+" J");
        assertEquals(removed,water+owed,1e-6*removed,"the water took it or it is in transit: water "+water+" J, owed "+owed+" J");
        assertTrue(water>.5*removed,"the water took most of it: "+water+" of "+removed+" J");
        assertTrue(r.ops().status(r.unit()).startsWith("RUNNING")||r.ops().status(r.unit()).startsWith("LIMITED: outlet"),r.ops().status(r.unit()));
        assertTrue(world.held.isEmpty(),"no island held: "+world.held);
    }
    /**
     * An air-then-water condenser (COOLER_MODEL_PLAN.md section 6, C1b): its trim's water takes the table's share of each
     * cycle's duty, the air the rest; an outlet's booking difference goes by the temperature its port offered the product
     * at, to the water below the 40 °C split and to the air at or above it (the owner's "divide by temperature"). The totals
     * book every joule; the water took its part or holds it in transit.
     */
    @Test void anAirThenWaterCondenserDividesItsBookingDifferencesByTemperature() {
        waterShare=.4;
        for(double offered:new double[]{330,305}) {
            productTemperature=offered;
            var r=rig(0,null,false,false,true);var world=r.world();
            double cycles=-cycles(r,4000);
            var u=world.ledger.column(r.unit());
            double removed=-u.totals().condenser(),water=r.counting().heat.getOrDefault(PhysicalFluidTopology.sideIdentity(r.tube()),0.),owed=world.coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(r.tube()),PhysicalFluidTopology.sideIdentity(r.shell()),world.ledger.onlineTick());
            double differences=removed-cycles;boolean cold=offered<com.wormzjl.icheme.science.equipment.HeatSides.AIR_SPLIT_TEMPERATURE;
            System.out.printf(Locale.ROOT,"C1B_BOOKING offered=%.2f K cycles=%.1f J differences=%.1f J water=%.1f J owed=%.1f J%n",offered,cycles,differences,water,owed);
            assertTrue(cycles>10000,"the column's condenser removed heat: "+cycles+" J");
            assertTrue(Math.abs(differences)>1e-3*cycles,"the outlet's booking differs from its account: "+differences+" J");
            assertEquals(.4*cycles+(cold?differences:0),water+owed,1e-6*removed,"at "+offered+" K the water took its part or it is in transit: water "+water+" J, owed "+owed+" J, cycles "+cycles+" J, differences "+differences+" J");
            assertTrue(world.held.isEmpty(),"no island held: "+world.held);
            // The share and the temperature are state: the topology unit carries them through a save.
            var saved=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),world.ledger.onlineTick(),k->world.model);
            var table=saved.columns().get(r.unit()).state().table();
            assertEquals(.4*table.condenserPerFeed(),table.condenserWaterPerFeed(),1e-12*Math.abs(table.condenserPerFeed()));
            assertEquals(com.wormzjl.icheme.science.equipment.HeatSides.AIR_SPLIT_TEMPERATURE,table.waterBelow());
        }
    }
    /** An air-cooled condenser (C1b): its water takes no cycle's duty and no booking difference, as before. */
    @Test void anAirCooledCondensersWaterTakesNothing() {
        waterShare=0;productTemperature=305;
        var r=rig(0,null,false,false,true);var world=r.world();
        world.runTo(4000);
        double removed=-world.ledger.column(r.unit()).totals().condenser(),water=r.counting().heat.getOrDefault(PhysicalFluidTopology.sideIdentity(r.tube()),0.),owed=world.coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(r.tube()),PhysicalFluidTopology.sideIdentity(r.shell()),world.ledger.onlineTick());
        assertTrue(removed>10000,"the column's condenser removed heat: "+removed+" J");
        assertEquals(0,water,"the water took nothing");
        assertEquals(0,owed,"nothing is owed to it");
    }
    /**
     * U2 (COOLER_MODEL_PLAN.md section 8): a condenser's water side whose lines hold a feed valve the column sets takes the flow
     * that carries the heat it is owed, Q / (h(T_out) - h(T_in)), the water rising its 10 K range but never past the receiver
     * (330 K) less 5 K. Water at 300 K leaves at 310 K; at 318 K it reaches only the 325 K ceiling, so it takes some 10/7 of the
     * flow; at half the feed the duty halves and so does the flow. Every joule is in the water or in transit, and the status
     * names the flow and the outlet.
     */
    @Test void aCondensersWaterValveCarriesItsDutyOverTheWatersRise() {
        waterValveBore=.01;
        var flows=new ArrayList<Double>();
        for(double[] c:new double[][]{{300,.5},{318,.5},{300,.25}}) {
            waterTemperature=c[0];feedRate=c[1];
            var r=rig(0,null,false,false,true);var world=r.world();
            world.runTo(4000);
            var u=world.ledger.column(r.unit());var last=r.ops().last(r.unit());
            double q=-last.condenser()/CYCLE_SECONDS;var v=waterValve(r);
            double out=Math.min(v[1]+com.wormzjl.icheme.science.equipment.HeatSides.WATER_RANGE,330-com.wormzjl.icheme.science.equipment.HeatSides.WATER_APPROACH);
            double expected=q/(specific(world.model,out,v[2])-specific(world.model,v[1],v[2]));
            double removed=-u.totals().condenser(),water=r.counting().heat.getOrDefault(PhysicalFluidTopology.sideIdentity(r.tube()),0.),owed=world.coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(r.tube()),PhysicalFluidTopology.sideIdentity(r.shell()),world.ledger.onlineTick());
            String status=r.ops().status(r.unit());
            System.out.printf(Locale.ROOT,"U2_VALVE water=%.1f K feed=%.2f mol/s duty=%.1f W inlet=%.2f K outlet=%.2f K target=%.6f kg/s expected=%.6f kg/s | %s%n",c[0],c[1],q,v[1],out,v[0],expected,status);
            assertEquals(expected,v[0],.01*expected,"the valve carries the duty over the water's rise");
            assertEquals(removed,water+owed,1e-6*removed,"every joule is in the water or in transit");
            assertTrue(status.startsWith("RUNNING")||status.startsWith("LIMITED: outlet"),status);
            assertTrue(status.contains("cooling water")&&status.contains(String.format(Locale.ROOT,"out at %.1f °C",out-273.15)),status);
            assertTrue(world.held.isEmpty(),"no island held: "+world.held);
            flows.add(v[0]);
        }
        assertEquals(flows.get(0)*10/7.,flows.get(1),.03*flows.get(1),"hotter water, a 7 K rise: more flow");
        assertEquals(flows.get(0)/2,flows.get(2),.03*flows.get(2),"half the duty, half the flow");
    }
    /**
     * U2: without its valve (no lines complete) the condenser's water runs bit for bit as before U2: the same world with the
     * condenser's read as it was then (no heat rate) books the same heat, the same transit and the same totals.
     */
    @Test void withoutItsValveACondensersWaterRunsAsBefore() {
        var now=rig(0,null,false,false,true);now.world().runTo(3000);
        var before=rig(0,null,false,false,true);before.counting().before=true;before.world().runTo(3000);
        long side=PhysicalFluidTopology.sideIdentity(now.tube()),partner=PhysicalFluidTopology.sideIdentity(now.shell());
        assertEquals(before.counting().heat.get(side),now.counting().heat.get(side),0.,"the water's heat");
        assertEquals(before.world().coordinator.transitAsOf(side,partner,3000),now.world().coordinator.transitAsOf(side,partner,3000),0.,"the transit");
        var a=before.world().ledger.column(before.unit()).totals();var b=now.world().ledger.column(now.unit()).totals();
        assertEquals(a.condenser(),b.condenser(),0.,"the condenser's total");
        assertEquals(a.taken().energy(),b.taken().energy(),0.);assertArrayEquals(a.taken().moles(),b.taken().moles(),0.);
        assertEquals(a.produced().energy(),b.produced().energy(),0.);assertArrayEquals(a.produced().moles(),b.produced().moles(),0.);
        assertFalse(now.ops().status(now.unit()).contains("cooling water"),"no valve, no flow on the status");
    }
    /** U2: a water valve that cannot pass the flow, wide open on a line with no pressure behind it (the supply at the return's pressure), leaves the column short of its condenser. */
    @Test void aShortWaterValveLimitsTheColumn() {
        waterValveBore=.002;waterPressure=200000;
        var r=rig(0,null,false,false,true);
        r.world().runTo(4000);
        String status=r.ops().status(r.unit());
        System.out.printf(Locale.ROOT,"U2_SHORT valve=%.6f kg/s | %s%n",waterValve(r)[0],status);
        assertTrue(status.startsWith("LIMITED: condenser"),status);
        assertTrue(status.contains("cooling water"),"the status names the flow the column asks: "+status);
    }
    /**
     * U2: an air condenser's built banks run their fans at the least speed that holds the air's duty, the power by the cube of
     * the speed (HeatSides.fanPower). The table's surface per mol/s of feed falls from 100 m² at natural draught to 50 at full
     * fans; 40 m² of banks at 0.5 mol/s need 80 m² per mol/s, at 40 % of full speed. 12.5 m² hold 0.25 mol/s at full fans: the
     * column is limited to half its feed.
     */
    @Test void anAirCondensersFansHoldItsDutyAndAShortFaceLimitsTheColumn() {
        waterShare=0;airArea=new double[]{100,93.75,87.5,81.25,75,68.75,62.5,56.25,50};
        airSurface=40;
        var r=rig(0);r.world().runTo(3000);
        String status=r.ops().status(r.unit());var last=r.ops().last(r.unit());
        var fans=r.ops().view(r.unit(),r.world().ledger.onlineTick(),d->Double.NaN).duties().stream().filter(d->d.kind()==com.wormzjl.icheme.science.column.operation.OperationView.DutyKind.FANS).findFirst().orElseThrow();
        double speed=r.ops().run(r.unit()).table.fans(40,last.feedMoles()/CYCLE_SECONDS);
        System.out.printf(Locale.ROOT,"U2_FANS speed=%.4f booked=%.1f W solved=%.1f W | %s%n",speed,fans.booked(),fans.solved(),status);
        assertTrue(status.startsWith("RUNNING")||status.startsWith("LIMITED: outlet"),status);
        assertTrue(speed>0&&speed<1,"the fans run below full speed: "+speed);
        assertEquals(com.wormzjl.icheme.science.equipment.HeatSides.fanPower(40,speed),fans.booked(),1e-9*fans.booked());
        assertEquals(.4,r.ops().run(r.unit()).table.fans(40,.5),1e-12,"40 % of full speed at the set feed");
        assertEquals(com.wormzjl.icheme.science.equipment.HeatSides.fanPower(40,1)*.4*.4*.4,fans.solved(),1e-9*fans.solved(),"the power by the cube of the speed");
        assertTrue(status.contains(String.format(Locale.ROOT,"air fans %.0f %%",100*speed)),status);
        airSurface=12.5;
        var shortFace=rig(0);shortFace.world().runTo(3000);
        status=shortFace.ops().status(shortFace.unit());
        System.out.printf(Locale.ROOT,"U2_FANS_SHORT | %s%n",status);
        assertTrue(status.startsWith("LIMITED: condenser, 50 %"),status);
        assertTrue(status.contains("air fans 100 %"),status);
    }
    /** The water valve's mass flow target, kg/s, the water's inlet temperature (the generator's), K, and the pressure at the
     * water side's inlet node, Pa, as their islands stand. */
    private double[] waterValve(Rig r) {
        double target=Double.NaN,p=Double.NaN;long side=PhysicalFluidTopology.sideIdentity(r.tube());var seen=new ArrayList<String>();
        for(var island:r.world().coordinator.snapshots()) {
            var graph=island.graph();
            seen.add(island.id()+": nodes "+graph.reservoirs().stream().map(PassiveNetwork.Reservoir::id).toList()+" pipes "+graph.pipes().stream().map(PassiveNetwork.Pipe::id).toList());
            for(var pipe:graph.pipes()) {
                if(pipe.control() instanceof FlowControl.ControlValve law&&graph.reservoirs().get(pipe.first()).id()==r.waterValve())target=law.massTarget();
                if(pipe.id()==side)p=graph.reservoirs().get(pipe.first()).state().pressure();
            }
        }
        if(Double.isNaN(target)||Double.isNaN(p))throw new AssertionError("valve "+r.waterValve()+" target "+target+", side "+side+" pressure "+p+"; islands "+seen+"; held "+r.world().held);
        return new double[]{target,waterTemperature,p};
    }
    /** Water's specific enthalpy on the rig's model at {@code t} K and {@code p} Pa, J/kg. */
    private static double specific(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model,double t,double p) {
        var water=new double[model.componentCount()];water[water.length-1]=1;var s=model.flashTP(t,p,water,()->{});
        return s.enthalpy()/s.mass();
    }
    /** Runs the rig to online tick {@code end} a few ticks at a time and sums each cycle's condenser duty, J (column sign: removed, negative). */
    private static double cycles(Rig r,long end) {
        double sum=0;var seen=r.ops().last(r.unit());
        for(long t=r.world().ledger.onlineTick();t<end;) {
            t=Math.min(end,t+5);r.world().runTo(t);
            var step=r.ops().last(r.unit());
            if(step!=null&&step!=seen){if(!step.tripped())sum+=step.condenser();seen=step;}
        }
        return sum;
    }
    /** A column's cycle, s. */
    private static final double CYCLE_SECONDS=ColumnOperation.CYCLE_TICKS/20.0;
    /**
     * Condenser shells in parallel (S3b): identical shells split the overhead equally, so each takes its share of the duty.
     * A column with two shells at 0.5 mol/s holds in each shell's water what one shell's water holds from the same column at
     * 0.25 mol/s, the same flow per shell: the same share of its duty, within 1 %; every joule is in a water side or in
     * transit, and the shares are equal.
     */
    @Test void twoCondenserShellsEachHoldWhatOneShellHoldsAtHalfTheFeed() {
        shells=2;feedRate=.5;
        var two=rig(0,null,false,false,true);two.world().runTo(4000);
        shells=1;feedRate=.25;
        var one=rig(0,null,false,false,true);one.world().runTo(4000);
        var u=two.world().ledger.column(two.unit());
        assertEquals(List.of(two.tube(),two.tube()+2),u.condensers());
        double removed=-u.totals().condenser(),taken=0;var each=new ArrayList<Double>();
        for(long tube:u.condensers()) {
            double water=two.counting().heat.getOrDefault(PhysicalFluidTopology.sideIdentity(tube),0.);
            double owed=two.world().coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(tube),PhysicalFluidTopology.sideIdentity(tube+1),two.world().ledger.onlineTick());
            each.add(water);taken+=water+owed;
        }
        assertTrue(removed>10000,"the column's condenser removed heat: "+removed+" J");
        assertEquals(removed,taken,1e-6*removed,"every joule is in a water side or in transit");
        assertEquals(each.get(0),each.get(1),1e-9*each.get(0),"identical shells take equal shares");
        double single=one.counting().heat.getOrDefault(PhysicalFluidTopology.sideIdentity(one.tube()),0.);
        double oneRemoved=-one.world().ledger.column(one.unit()).totals().condenser(),oneTaken=one.world().ledger.column(one.unit()).totals().taken().total(),twoTaken=u.totals().taken().total();
        // A cycle waits until every water side has booked through its fence, so the two-shell column may run a 5 s cycle fewer
        // in the same time: its feed taken is twice the single shell's within one cycle's take.
        assertEquals(oneTaken,twoTaken/2,.25*CYCLE_SECONDS*(1+1e-9),"the feed taken per shell, within one cycle: "+twoTaken+" against "+oneTaken);
        // What a shell's water holds follows its duty: each shell of two takes the share of its duty one shell takes of the same duty.
        assertEquals(single/oneRemoved,each.get(0)/(removed/2),.01*single/oneRemoved,"a shell's water takes its share of its duty as one shell does: "+each+" of "+removed
                +" against "+single+" of "+oneRemoved);
        for(var r:List.of(one,two))assertTrue(r.ops().status(r.unit()).startsWith("RUNNING")||r.ops().status(r.unit()).startsWith("LIMITED: outlet"),r.ops().status(r.unit()));
        assertTrue(two.world().held.isEmpty()&&one.world().held.isEmpty(),"no island held");
    }
    /** A cooling-water shortage limits the column (C6b): the water lost, the heat owed fills the condenser's hold and the throughput falls to nothing. */
    @Test void aCoolingWaterShortageLimitsTheColumn() {
        var r=rig(0,null,false,false,true);var world=r.world();
        world.runTo(3000);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(r.waterSource(),null)),world.ledger.nextIdentity(),null);
        world.runTo(7000);
        double before=world.ledger.column(r.unit()).totals().taken().total();
        world.runTo(8000);
        var u=world.ledger.column(r.unit());
        assertTrue(r.ops().status(r.unit()).startsWith("LIMITED: condenser"),r.ops().status(r.unit()));
        assertEquals(before,u.totals().taken().total(),1e-9,"no feed taken while the condenser cannot reject heat");
        double hold=ColumnOperation.CONDENSER_HOLD_SECONDS*1000*.5,owed=world.coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(r.tube()),PhysicalFluidTopology.sideIdentity(r.shell()),world.ledger.onlineTick());
        assertTrue(owed<=hold*1.2,"the heat owed stays near the hold: "+owed+" J of "+hold+" J");
    }
    /** The heat the utility side of rig {@code r} took up, J (negative: it gave), and what is still in transit to it, J. */
    private static double[] utilityHeat(Rig r) {
        long side=PhysicalFluidTopology.sideIdentity(r.utilityTube()),partner=PhysicalFluidTopology.sideIdentity(r.utilityShell());
        return new double[]{r.counting().heat.getOrDefault(side,0.),r.world().coordinator.transitAsOf(side,partner,r.world().ledger.onlineTick())};
    }
    /** Every component and the energy of the rig's unit close with all its duties: what its ports gave is what they took, less the accounts, plus the duties booked. */
    private static void closesWithDuties(Rig r) {
        var u=r.world().ledger.column(r.unit());var c=r.counting();var t=u.totals();
        double took=-c.energy.getOrDefault(r.feed(),0.),gave=c.energy.getOrDefault(r.product(),0.);
        double held=u.station(Port.FEED,0).account().energy()+u.station(Port.DISTILLATE,0).account().energy();
        assertEquals(gave,took-held+t.condenser()+t.reboiler()+t.pumparounds(),1e-9*Math.max(1,Math.abs(took)),"the column's energy closes on its duties");
    }
    /**
     * W4: a reboiler with a heating utility (steam through an exchanger's tubes, its shell held by the column) is owed its duty
     * through the heat in transit, as the condenser's water is: the steam gave every joule the totals booked into the column,
     * or it is still owed, and the column runs at its set feed.
     */
    @Test void aReboilersHeatClosesAgainstItsUtilitysIsland() {
        utility=Utility.REBOILER;
        var r=rig(0);r.world().runTo(4000);
        var u=r.world().ledger.column(r.unit());var heat=utilityHeat(r);
        double booked=u.totals().reboiler();
        assertTrue(booked>10000,"the reboiler put heat into the column: "+booked+" J");
        assertEquals(booked,-(heat[0]+heat[1]),1e-6*booked,"the steam gave it or owes it: gave "+(-heat[0])+" J, owed "+(-heat[1])+" J");
        assertTrue(-heat[0]>.5*booked,"the steam gave most of it: "+(-heat[0])+" of "+booked+" J");
        closesWithDuties(r);
        String status=r.ops().status(r.unit());
        assertTrue(status.startsWith("RUNNING")||status.startsWith("LIMITED: outlet"),status);
        assertTrue(r.world().held.isEmpty(),"no island held: "+r.world().held);
    }
    /**
     * W4: a reboiler's tubes in the COLUMN mode with a complete flow station: the column sets the steam's valve to carry its
     * duty from the steam's inlet down to the floor (the bottoms plus the approach), the heat rate over h(inlet) − h(floor),
     * and the heat closes against the steam's island.
     */
    @Test void aReboilersSteamValveCarriesItsDutyDownToTheFloor() {
        utility=Utility.REBOILER;reboilerPerFeed=4e4;utilityValveBore=.005;
        var r=rig(0);var world=r.world();world.runTo(4000);
        var last=r.ops().last(r.unit());double q=last.reboiler()/CYCLE_SECONDS;
        double target=Double.NaN,p=Double.NaN;long side=PhysicalFluidTopology.sideIdentity(r.utilityTube());
        for(var island:world.coordinator.snapshots())for(var pipe:island.graph().pipes()) {
            if(pipe.control() instanceof FlowControl.ControlValve law&&island.graph().reservoirs().get(pipe.first()).id()==r.utilityValve())target=law.massTarget();
            if(pipe.id()==side)p=island.graph().reservoirs().get(pipe.first()).state().pressure();
        }
        double floor=330+com.wormzjl.icheme.science.equipment.HeatSides.WATER_APPROACH;
        double expected=q/(specific(world.model,utilityTemperature,p)-specific(world.model,floor,p));
        String status=r.ops().status(r.unit());
        System.out.printf(Locale.ROOT,"W4_VALVE steam=%.1f K at %.0f Pa duty=%.1f W floor=%.2f K target=%.6f kg/s expected=%.6f kg/s | %s%n",utilityTemperature,p,q,floor,target,expected,status);
        assertEquals(expected,target,.03*expected,"the valve carries the duty down to the floor");
        var heat=utilityHeat(r);double booked=world.ledger.column(r.unit()).totals().reboiler();
        assertEquals(booked,-(heat[0]+heat[1]),1e-6*booked,"the steam gave it or owes it");
        assertTrue(status.startsWith("RUNNING")||status.startsWith("LIMITED: outlet"),status);
        assertTrue(world.held.isEmpty(),"no island held: "+world.held);
    }
    /** W4, decision D1: a reboiler whose steam is lost cannot heat; the heat owed fills its hold and the feed is cut, HEATING LIMITED, no solve. */
    @Test void aShortReboilerUtilityLimitsTheColumn() {
        utility=Utility.REBOILER;
        var solves=new Fake(0);solves.duties=t->new SplitTable(t.axis(),t.products(),reboilerPerFeed,t.pumparoundPerFeed(),t.condenserPerFeed()-reboilerPerFeed,t.reference(),t.held());
        var r=rig(0,solves);solves.world=r.world();
        solves.runTo(3000);
        int asked=solves.jobs.size();
        r.world().registry.submit(List.of(new WorldTopologyLedger.Edit(r.utilitySource(),null)),r.world().ledger.nextIdentity(),null);
        solves.runTo(7000);
        double before=r.world().ledger.column(r.unit()).totals().taken().total();
        solves.runTo(8000);
        var u=r.world().ledger.column(r.unit());String status=r.ops().status(r.unit());
        assertTrue(status.startsWith("LIMITED: reboiler"),status);
        assertEquals(before,u.totals().taken().total(),1e-9,"no feed taken while the reboiler has no heat");
        assertTrue(EquipmentAlarm.column(r.ops().conditions(r.unit())).contains(EquipmentAlarm.HEATING_LIMITED),r.ops().conditions(r.unit()).toString());
        // The shortage asks no solve of its own: only the throughput band, as for any cut feed, while the column still takes some.
        assertTrue(solves.jobs.stream().skip(asked).allMatch(j->j.trigger()==DriftBands.Trigger.THROUGHPUT),"solves after the loss: "+solves.jobs.stream().skip(asked).map(ColumnSolve.Job::trigger).toList());
        double hold=ColumnOperation.CONDENSER_HOLD_SECONDS*reboilerPerFeed*.5,owed=-utilityHeat(r)[1];
        assertTrue(owed<=hold*1.2,"the heat owed stays near the hold: "+owed+" J of "+hold+" J");
    }
    /** W4, decision D1: a pumparound cooler whose cooling water is lost cuts the feed, PUMPAROUND short (COOLING LIMITED); with its water its heat closes. */
    @Test void aShortPumparoundCoolerLimitsTheColumn() {
        utility=Utility.PUMPAROUND;utilityTemperature=300;
        var r=rig(0);r.world().runTo(3000);
        var u=r.world().ledger.column(r.unit());var heat=utilityHeat(r);double removed=-u.totals().pumparounds();
        assertTrue(removed>5000,"the pumparound removed heat: "+removed+" J");
        assertEquals(removed,heat[0]+heat[1],1e-6*removed,"its water took it or it is in transit");
        closesWithDuties(r);
        r.world().registry.submit(List.of(new WorldTopologyLedger.Edit(r.utilitySource(),null)),r.world().ledger.nextIdentity(),null);
        r.world().runTo(7000);
        double before=r.world().ledger.column(r.unit()).totals().taken().total();
        r.world().runTo(8000);
        String status=r.ops().status(r.unit());
        assertTrue(status.startsWith("LIMITED: pumparound"),status);
        assertEquals(before,r.world().ledger.column(r.unit()).totals().taken().total(),1e-9,"no feed taken while the pumparound cannot be cooled");
        var binding=Condition.binding(r.ops().conditions(r.unit()));
        assertEquals(Condition.PUMPAROUND_SHORT,binding.condition());assertEquals(0,binding.index(),"the first pumparound");
        assertTrue(EquipmentAlarm.column(r.ops().conditions(r.unit())).contains(EquipmentAlarm.COOLING_LIMITED));
    }
    /**
     * W4: a unit saved before its utility sides (none named) keeps its reboiler's duty free until its next Start, as before
     * W4: the line's side, whose partner the column holds, moves no heat and is owed nothing, the duty is booked in the
     * totals alone and the column runs at its set feed.
     */
    @Test void aUnitSavedWithoutUtilitiesRunsAsBefore() {
        utility=Utility.REBOILER;utilitySaved=false;
        var old=rig(0);old.world().runTo(3000);
        var heat=utilityHeat(old);
        assertEquals(0,heat[0],0.,"the side moves no heat");assertEquals(0,heat[1],0.,"nothing is owed to it");
        var u=old.world().ledger.column(old.unit());
        assertTrue(u.utilities().none());
        assertTrue(u.totals().reboiler()>0,"the reboiler's duty is booked, free");
        closesWithDuties(old);
        String status=old.ops().status(old.unit());
        assertTrue(status.startsWith("RUNNING")||status.startsWith("LIMITED: outlet"),status);
        assertNull(Condition.find(old.ops().conditions(old.unit()),Condition.HEATING_SHORT),"a free duty limits nothing");
    }
}
