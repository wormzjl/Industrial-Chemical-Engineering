package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.ColumnSolve;
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
        final IslandCoordinator.Ports inner;final Map<Long,double[]> moles=new HashMap<>();
        Counting(IslandCoordinator.Ports inner){this.inner=inner;}
        public boolean ready(long island,PassiveNetwork graph,long start){return inner.ready(island,graph,start);}
        public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice){return inner.plans(island,graph,slice);}
        public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
            for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE){var sum=moles.computeIfAbsent(b.nodeId(),id->new double[b.moles().length]);var n=b.moles();for(int i=0;i<n.length;i++)sum[i]+=n[i];}
            inner.booked(island,graph,start,end,result);
        }
    }
    private static PhysicalFluidTopology.Device port(long id,int x,Kind kind,Direction toward,PipeResistanceGeometry g){return port(id,x,0,kind,toward,g);}
    private static PhysicalFluidTopology.Device port(long id,int x,int z,Kind kind,Direction toward,PipeResistanceGeometry g) {
        var at=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,64,z,PhysicalFluidTopology.PORT_LANE);
        return new PhysicalFluidTopology.Device(id,at,kind,toward,g.geometry,new FlowControl.Passive(),List.of(new PhysicalFluidTopology.Port(at,toward,PassiveNetwork.PhasePort.BULK)),
                true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
    }
    private record PipeResistanceGeometry(com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry geometry) {}
    private static double sum(double[] n){double s=0;for(double v:n)s+=v;return s;}

    private record Rig(EventPathWorld world,ColumnOperation ops,Counting counting,long unit,long feed,long product,long tank,long steamSource){}
    /** A 3 bar nitrogen feed line into a feed port, a distillate port out to a tank, a table sending everything to the distillate; jobs taking {@code lag} ticks. */
    private Rig rig(int lag){return rig(lag,null);}
    private Rig rig(int lag,Fake solves){return rig(lag,solves,false,false);}
    /**
     * The rig with its tables from {@code solves}, or installed at once where null; with {@code steam}, a 450 K steam line into
     * a steam port at 0.2 mol per mol of feed; with {@code blocked}, nothing downstream of the product valve.
     */
    private Rig rig(int lag,Fake solves,boolean steam,boolean blocked) {
        var world=new EventPathWorld();world.lag=lag;var model=world.model;int m=model.componentCount();
        var n2=EventPathWorld.nitrogen().composition();
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,n2),.05);
        world.place(1,64,0,Kind.PIPE,Direction.EAST,.05);long feedValve=world.place(2,64,0,Kind.VALVE,Direction.EAST);
        long productValve=world.place(11,64,0,Kind.VALVE,Direction.EAST),tank=0,steamSource=0,steamValve=0;
        if(!blocked){world.place(12,64,0,Kind.PIPE,Direction.EAST,.05);tank=world.place(13,64,0,Kind.RESERVOIR,Direction.EAST);}
        var water=new double[m];water[m-1]=1;
        // The steam line is sized for its 0.1 mol/s (1 cm): a 5 cm valve regulating 6 g/s from 3 to 1.5 bar holds its island (README, open items).
        if(steam){steamSource=world.place(0,64,2,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,450,300000,water),.05);world.place(1,64,2,Kind.PIPE,Direction.EAST,.01);steamValve=world.place(2,64,2,Kind.VALVE,Direction.EAST,FluidDeviceSpec.nitrogen(world.catalog),.01);}
        var ops=new ColumnOperation(world.ledger,world.registry,world.coordinator,model.components(),model.molecularWeights());var counting=new Counting(ops);world.coordinator.ports(counting);
        world.runTo(40);
        // Start: a feed port inside the feed valve, a distillate port inside the product valve.
        long unit=world.ledger.reserve(1);long feed=world.ledger.nextIdentity(),product=feed+1,steamPort=feed+2;
        var g=new PipeResistanceGeometry(world.registry.registrations().get(feedValve).device().geometry());
        var setpoints=new Setpoints(.5,.5,330,1,0,steam?new double[]{.2}:new double[0],new double[0],new double[0]);
        var stations=new ArrayList<>(List.of(new ColumnUnit.Station(Port.FEED,0,feed,feedValve,Account.empty(m)),new ColumnUnit.Station(Port.DISTILLATE,0,product,productValve,Account.empty(m))));
        if(steam)stations.add(new ColumnUnit.Station(Port.STEAM,0,steamPort,steamValve,Account.empty(m)));
        world.ledger.column(new ColumnUnit(unit,new PhysicalFluidTopology.Position(EventPathWorld.DIM,6,70,0),true,setpoints,stations,ColumnUnit.Totals.empty(m)));
        var edits=new ArrayList<>(List.of(new WorldTopologyLedger.Edit(feed,new WorldTopologyLedger.Registration(port(feed,3,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,330,150000,n2),0)),
                new WorldTopologyLedger.Edit(product,new WorldTopologyLedger.Registration(port(product,10,Kind.GENERATOR,Direction.EAST,g),new FluidDeviceSpec(1,330,250000,n2),0))));
        if(steam)edits.add(new WorldTopologyLedger.Edit(steamPort,new WorldTopologyLedger.Registration(port(steamPort,3,2,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,420,150000,n2),0)));
        world.registry.submit(edits,steamPort+1,null);
        if(solves!=null){solves.model=model;ops.solves(solves);}
        ops.refresh();
        if(solves==null){var s=table(model,setpoints);ops.table(unit,s.table(),s.enthalpy());}
        return new Rig(world,ops,counting,unit,feed,product,tank,steamSource);
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
    /** The table at the steam ratios {@code steam}: the water leaves at the steam's own enthalpy, so the closure holds at any ratio. */
    static ColumnSolve.Solved table(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model,Setpoints setpoints,double[] steam) {
        int m=model.componentCount();var n2=EventPathWorld.nitrogen().composition();
        double h=model.flashTP(340,300000,n2.clone(),()->{}).enthalpy()/sum(n2);
        var water=new double[m];water[m-1]=1;double hs=model.flashTP(450,300000,water,()->{}).enthalpy();
        var rows=new double[1][m];Arrays.fill(rows[0],h-1000);rows[0][m-1]=hs;
        var z=new double[m];for(int i=0;i<m;i++)z[i]=n2[i]/sum(n2);
        var all=new double[m];Arrays.fill(all,1);var held=new boolean[m];Arrays.fill(held,true);
        var table=new SplitTable(model.components(),List.of(new SplitTable.Product(Port.DISTILLATE,0,all,330,250000)),0,new double[0],-1000,
                new SplitTable.Reference(z,h,30,steam,1,setpoints),held);
        return new ColumnSolve.Solved(table,rows,null,-1000,"test","");
    }
    /** A solver answering every job {@code delay} ticks after it was asked (0: at once): {@link #table}, or a failure where {@code fail} holds. */
    static final class Fake implements ColumnOperation.Solves {
        static final com.wormzjl.icheme.science.column.v3.V3ColumnInput DESIGN;
        static{var c=MaterialCatalog.bundled();DESIGN=c.presets().column("tia_juana").input(c);}
        com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model;EventPathWorld world;final long delay;
        java.util.function.Predicate<ColumnSolve.Job> fail=j->false;
        final List<ColumnSolve.Job> jobs=new ArrayList<>();final List<Long> asked=new ArrayList<>();
        final TreeMap<Long,List<Runnable>> due=new TreeMap<>();
        Fake(long delay){this.delay=delay;}
        public com.wormzjl.icheme.science.column.v3.V3ColumnInput design(ColumnUnit unit){return DESIGN;}
        public boolean submit(long unit,ColumnSolve.Job job,java.util.function.Consumer<ColumnSolve.Outcome> done) {
            jobs.add(job);long now=world.ledger.onlineTick();asked.add(now);
            var taken=job.taken();var ratios=new double[taken.steam().size()];for(int j=0;j<ratios.length;j++)ratios[j]=taken.feedRate()>0?taken.steam().get(j).rate()/taken.feedRate():job.setpoints().steamPerFeed()[j];
            ColumnSolve.Outcome o=fail.test(job)?new ColumnSolve.Failed("test refusal"):table(model,job.setpoints(),job.trigger()==DriftBands.Trigger.START?job.setpoints().steamPerFeed():ratios);
            if(delay==0)done.accept(o);else due.computeIfAbsent(now+delay,t->new ArrayList<>()).add(()->done.accept(o));
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
     * A setpoint edit solves at the next cycle (plan section 6); a failed solve keeps the table, names it on the status line
     * and retries after 30 s, then 60 s (plan section 6).
     */
    @Test void aSetpointEditSolvesAtOnceAndAFailedSolveKeepsTheTable() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        f.runTo(1000);
        var kept=r.ops().run(r.unit()).table;assertNotNull(kept);
        var u=world.ledger.column(r.unit());var s=u.setpoints();
        world.ledger.column(u.withSetpoints(new Setpoints(s.designFeedRate(),s.feedRate(),s.condenserTemperature(),2,s.reboilerPerFeed(),s.steamPerFeed(),s.drawsPerFeed(),s.pumparoundsPerFeed())));
        f.fail=j->j.setpoints().refluxRatio()==2;
        f.runTo(4000);
        var asked=f.asked(DriftBands.Trigger.SETPOINT);
        assertTrue(asked.size()>=3,"asked "+asked);
        assertTrue(asked.get(0)<=1000+ColumnOperation.CYCLE_TICKS,"at the next cycle: "+asked);
        assertCycle(asked.get(0)+ColumnOperation.APPLY_TICKS+600,asked.get(1),"the first retry 30 s after the failure applied");
        assertCycle(asked.get(1)+ColumnOperation.APPLY_TICKS+1200,asked.get(2),"the second 60 s after");
        assertSame(kept,r.ops().run(r.unit()).table,"the table stays");
        assertTrue(r.ops().status(r.unit()).contains("setpoint solve failed (test refusal)"),r.ops().status(r.unit()));
        // The solve succeeds: the next retry's table applies and the note goes.
        f.fail=j->false;f.runTo(asked.get(2)+ColumnOperation.APPLY_TICKS+2400+ColumnOperation.APPLY_TICKS+1);
        assertNotSame(kept,r.ops().run(r.unit()).table);
        assertEquals(2,r.ops().run(r.unit()).table.reference().setpoints().refluxRatio());
        assertFalse(r.ops().status(r.unit()).contains("failed"),r.ops().status(r.unit()));
    }
    /**
     * The table is state the topology unit saves (plan section 12): a world read from its checkpoint resumes the running
     * column on its saved table and windows, takes its feed on, and asks no start solve.
     */
    @Test void aReloadResumesOnTheSavedTableWithoutASolve() {
        var f=new Fake(0);var r=rig(0,f);f.world=r.world();var world=r.world();
        f.runTo(1500);
        var before=r.ops().run(r.unit());assertNotNull(before.table);
        double taken=world.ledger.column(r.unit()).totals().taken().total();assertTrue(taken>0,"it ran before the save");
        var bytes=FluidCheckpointCodec.encodeTopology(world.ledger.snapshot());
        var saved=FluidCheckpointCodec.decodeTopology(bytes,world.ledger.onlineTick(),k->world.model);
        var state=saved.columns().get(r.unit()).state();
        assertNotNull(state,"the running unit's engine state is in the topology unit");
        assertArrayEquals(before.table.products().getFirst().recovery(),state.table().products().getFirst().recovery());
        assertArrayEquals(before.enthalpy,state.enthalpy());
        assertEquals(before.tableTick,state.tableTick());
        assertFalse(state.levels().isEmpty(),"the inlet's levels through the fence");
        var again=new EventPathWorld(1,saved,world.coordinator.snapshots());
        var g=new Fake(0);g.world=again;g.model=again.model;
        var ops=new ColumnOperation(again.ledger,again.registry,again.coordinator,again.model.components(),again.model.molecularWeights());
        ops.solves(g);again.coordinator.ports(ops);ops.refresh();
        assertNotNull(ops.run(r.unit()).table,"the saved table at once");
        g.runTo(again.ledger.onlineTick()+1000);
        assertTrue(g.asked(DriftBands.Trigger.START).isEmpty(),"no start solve: "+g.jobs);
        assertTrue(again.ledger.column(r.unit()).totals().taken().total()>taken+10,"it takes its feed on after the reload");
        // A stopped unit keeps no engine state.
        var u=again.ledger.column(r.unit());assertNull(u.withStations(false,u.stations().stream().map(s->s.withDevice(0)).toList()).state());
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
        assertEquals(buffer,held,1e-6*buffer,"the outlet holds its full buffer");
        assertEquals(held,u.totals().taken().total(),1e-9,"the column took what its outlet holds, no more");
        double later=-sum(r.counting().moles.getOrDefault(r.feed(),new double[1]));
        assertTrue(later-arrived<.01,"the feed valve closed: "+(later-arrived)+" mol in the last 50 s");
    }
    /**
     * Rule B with A where a solve fails (owner Q1, C1): the steam supply lost, the column first takes its feed as it comes
     * and solves on the short steam's ratio; that solve failing (no steam, no reboiler), it cuts every input together, so the
     * feed stops, and says so; at the retry it tries B again.
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
        // At the retry B is tried again: the steam still lost, it solves again, fails again, and A comes back.
        f.runTo(world.ledger.onlineTick()+1200);
        assertTrue(f.asked(DriftBands.Trigger.STEAM_RATIO).size()>asked.size(),"B tried again at the retry: "+f.asked(DriftBands.Trigger.STEAM_RATIO));
    }
}
