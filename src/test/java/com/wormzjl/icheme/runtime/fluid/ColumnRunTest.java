package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
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
        final IslandCoordinator.Ports inner;final Map<Long,double[]> moles=new HashMap<>();
        Counting(IslandCoordinator.Ports inner){this.inner=inner;}
        public boolean ready(long island,PassiveNetwork graph,long start){return inner.ready(island,graph,start);}
        public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice){return inner.plans(island,graph,slice);}
        public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
            for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE){var sum=moles.computeIfAbsent(b.nodeId(),id->new double[b.moles().length]);var n=b.moles();for(int i=0;i<n.length;i++)sum[i]+=n[i];}
            inner.booked(island,graph,start,end,result);
        }
    }
    private static PhysicalFluidTopology.Device port(long id,int x,Kind kind,Direction toward,PipeResistanceGeometry g) {
        var at=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,64,0,PhysicalFluidTopology.PORT_LANE);
        return new PhysicalFluidTopology.Device(id,at,kind,toward,g.geometry,new FlowControl.Passive(),List.of(new PhysicalFluidTopology.Port(at,toward,PassiveNetwork.PhasePort.BULK)),
                true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
    }
    private record PipeResistanceGeometry(com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry geometry) {}
    private static double sum(double[] n){double s=0;for(double v:n)s+=v;return s;}

    private record Rig(EventPathWorld world,ColumnOperation ops,Counting counting,long unit,long feed,long product,long tank){}
    /** A 3 bar nitrogen feed line into a feed port, a distillate port out to a tank, a table sending everything to the distillate; jobs taking {@code lag} ticks. */
    private Rig rig(int lag) {
        var world=new EventPathWorld();world.lag=lag;var model=world.model;int m=model.componentCount();
        var n2=EventPathWorld.nitrogen().composition();
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,n2),.05);
        world.place(1,64,0,Kind.PIPE,Direction.EAST,.05);long feedValve=world.place(2,64,0,Kind.VALVE,Direction.EAST);
        long productValve=world.place(11,64,0,Kind.VALVE,Direction.EAST);world.place(12,64,0,Kind.PIPE,Direction.EAST,.05);
        long tank=world.place(13,64,0,Kind.RESERVOIR,Direction.EAST);
        var ops=new ColumnOperation(world.ledger,world.registry,world.coordinator,model.molecularWeights());var counting=new Counting(ops);world.coordinator.ports(counting);
        world.runTo(40);
        // Start: a feed port inside the feed valve, a distillate port inside the product valve.
        long unit=world.ledger.reserve(1);long feed=world.ledger.nextIdentity(),product=feed+1;
        var g=new PipeResistanceGeometry(world.registry.registrations().get(feedValve).device().geometry());
        var setpoints=new Setpoints(.5,.5,330,1,0,new double[0],new double[0],new double[0]);
        world.ledger.column(new ColumnUnit(unit,new PhysicalFluidTopology.Position(EventPathWorld.DIM,6,70,0),true,setpoints,
                List.of(new ColumnUnit.Station(Port.FEED,0,feed,feedValve,Account.empty(m)),new ColumnUnit.Station(Port.DISTILLATE,0,product,productValve,Account.empty(m))),ColumnUnit.Totals.empty(m)));
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(feed,new WorldTopologyLedger.Registration(port(feed,3,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,330,150000,n2),0)),
                new WorldTopologyLedger.Edit(product,new WorldTopologyLedger.Registration(port(product,10,Kind.GENERATOR,Direction.EAST,g),new FluidDeviceSpec(1,330,250000,n2),0))),product+1,null);
        ops.refresh();
        // The table: everything to the distillate at 330 K, 1 kJ/mol below what the feed brings, so the condenser removes it.
        double h=model.flashTP(340,300000,n2.clone(),()->{}).enthalpy()/sum(n2);
        var z=new double[m];for(int i=0;i<m;i++)z[i]=n2[i]/sum(n2);
        var all=new double[m];Arrays.fill(all,1);var held=new boolean[m];Arrays.fill(held,true);
        var table=new SplitTable(model.components(),List.of(new SplitTable.Product(Port.DISTILLATE,0,all,330,250000)),0,new double[0],-1000,
                new SplitTable.Reference(z,h,30,new double[0],1,setpoints),held);
        ops.table(unit,table,new double[]{h-1000});
        return new Rig(world,ops,counting,unit,feed,product,tank);
    }

    @Test void aRunningColumnTakesItsFeedAndGivesItsProductDownstreamConservingEveryComponent() {
        var r=rig(0);var world=r.world();var ops=r.ops();var counting=r.counting();long unit=r.unit(),feed=r.feed(),product=r.product(),tank=r.tank();int m=world.model.componentCount();
        world.runTo(3000);
        var u=world.ledger.column(unit);var totals=u.totals();
        var arrived=counting.moles.getOrDefault(feed,new double[m]);var given=counting.moles.getOrDefault(product,new double[m]);
        assertTrue(totals.taken().total()>40,"the column took its feed at its set rate, 0.5 mol/s from the first cycle's demand a fence later, 121 s: "+totals.taken().total()+" mol, status "+ops.status(unit));
        for(int i=0;i<m;i++) {
            double in=-arrived[i],inlet=u.station(Port.FEED,0).account().moles()[i],taken=totals.taken().moles()[i];
            assertEquals(in,inlet+taken+pending(ops,unit,feed,i),1e-9*Math.max(1,in),"component "+i+": what arrived is in the feed account or taken");
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
    /** Moles of component {@code i} still in the feed port's spans (booked, not yet taken into the account). */
    private static double pending(ColumnOperation ops,long unit,long device,int i) {
        var r=ops.run(unit);var spans=r==null?null:r.spans.get(device);if(spans==null)return 0;
        double s=0;for(var span:spans)s+=span.arrived().moles()[i];return s;
    }
}
