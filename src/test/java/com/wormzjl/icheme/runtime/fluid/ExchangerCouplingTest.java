package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * W3's gates (batch 2026-10-27-heat-exchanger-design, plan sections 5.2 and 10): an exchanger's two sides in their own
 * islands, coupled through the relay on the coordinator's clock behind the coupling fence (owner Q16, form C).
 */
class ExchangerCouplingTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB=new PipeResistance.Geometry(1,.05,.000045,0);
    /** A 0.5 m shell over 3 m tubes: 56 m² of tube, UA about 56 kW/K with liquid on both sides. */
    private static final double SHELL=.5,TUBES=3,AREA=com.wormzjl.icheme.science.equipment.ProcessUnit.tubeSurface(SHELL,TUBES);
    private static final long HOT=10,COLD=11;
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z);}
    private static PhysicalFluidTopology.Device plain(long id,int x,int y,int z,Kind kind){return new PhysicalFluidTopology.Device(id,at(x,y,z),kind,EAST,STUB,new FlowControl.Passive());}
    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell,PhysicalFluidTopology.Direction face,PhysicalFluidTopology.ConnectionRole role){return new PhysicalFluidTopology.Port(cell,face,PassiveNetwork.PhasePort.BULK,role);}
    private static PhysicalFluidTopology.Device side(long id,PhysicalFluidTopology.Position in,PhysicalFluidTopology.Position out,PipeResistance.Geometry passage,HeatUnit heat) {
        var ports=List.of(port(in,WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(out,EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id,in,Kind.HEAT_SIDE,NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }
    private FluidThermodynamics.State water(double temperature,double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(temperature,pressure,n,()->{}).volume();
        return model.flashTP(temperature,pressure,n,()->{});
    }
    private PassiveNetwork.Reservoir source(long id,FluidThermodynamics.State state){return new PassiveNetwork.Reservoir(id,0,state,PassiveNetwork.NodeKind.GENERATOR);}
    private PassiveNetwork.Reservoir sink(long id,double pressure){return new PassiveNetwork.Reservoir(id,0,water(300,pressure),PassiveNetwork.NodeKind.VOID);}

    /** A line from generator {@code from} through side {@code id} into void {@code to}, along row {@code z}. */
    private static List<PhysicalFluidTopology.Device> line(long from,long id,long to,int z,PipeResistance.Geometry passage,HeatUnit heat) {
        return List.of(plain(from,0,0,z,Kind.GENERATOR),plain(100+id,1,0,z,Kind.PIPE),side(id,at(2,0,z),at(4,0,z),passage,heat),plain(200+id,5,0,z,Kind.PIPE),plain(to,6,0,z,Kind.VOID));
    }
    /** The exchanger's tube side (hot) and shell side (cold), each in its own line: two islands. */
    private List<PhysicalFluidTopology.Device> exchanger(){return exchanger(HeatUnit.Control.OFF);}
    /** The same with the cold side under {@code cold} (W4). */
    private List<PhysicalFluidTopology.Device> exchanger(HeatUnit.Control cold) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.addAll(line(1,HOT,2,0,HeatSides.tubePassage(SHELL,TUBES),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,AREA,COLD)));
        devices.addAll(line(3,COLD,4,5,HeatSides.shellPassage(SHELL,TUBES),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.SHELL,AREA,HOT).withControl(cold)));
        return devices;
    }

    /**
     * A coordinator on its own epoch whose worker solves each admitted slice at once, as {@link CertifyingRig} does; an
     * island in {@code failing} has its completions fail (held) while the predicate holds. Every committed or replayed
     * span's heat at each side is summed for the energy check.
     */
    private final class Rig {
        final long[] epoch={0};final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final Map<Long,Long> committed=new HashMap<>();final Map<Long,Double> heat=new HashMap<>();
        final IslandCoordinator coordinator;long requests,hotIsland,coldIsland;
        java.util.function.LongPredicate failing=id->false;
        final CertificatePolicy policy;
        Rig(CertificatePolicy policy) {
            this.policy=policy;
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 64-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){throw new AssertionError("no deadlines here");}
            },this::book,()->0L,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false,100,policy),()->epoch[0]);
            coordinator.onReplayed(this::book);
        }
        /** Each island's heat at its sides over the spans its clock advanced, J (a held publication advances nothing). */
        void book(List<IslandCoordinator.Snapshot> changed) {
            for(var s:changed) {
                long to=s.clock().committedTick(),from=committed.getOrDefault(s.id(),to);committed.put(s.id(),to);
                if(to==from||s.lastResult().isEmpty())continue;
                var result=s.lastResult().orElseThrow();double scale=result.advancedSeconds()>0?(to-from)/20.0/result.advancedSeconds():0;
                for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat.merge(b.nodeId(),b.totalEnergyJoule()*(Math.abs(scale-1)<1e-12?1:scale),Double::sum);
            }
        }
        /** A save and load: a new coordinator at this one's epoch, its islands decoded from this one's checkpoint image and
         * the heat in transit as the facts carry it (the transit as booked). */
        Rig reloaded() {
            var saved=coordinator.snapshots();
            var image=FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(saved.stream().map(x->new FluidCheckpointCodec.IslandEntry("minecraft:overworld",model.domain().packageId(),x)).toList()),key->model);
            var copy=new Rig(policy);copy.epoch[0]=epoch[0];copy.hotIsland=hotIsland;copy.coldIsland=coldIsland;copy.heat.putAll(heat);
            for(var entry:FluidCheckpointCodec.decode(image,key->model).islands()){copy.coordinator.register(entry.snapshot(),model);copy.committed.put(entry.snapshot().id(),entry.snapshot().clock().committedTick());}
            copy.coordinator.transit(PhysicalFluidTopology.sideIdentity(Math.min(HOT,COLD)),booked());
            return copy;
        }
        void register(long id,PassiveNetwork graph,int cadence) {
            coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(epoch[0],epoch[0],0,cadence),Optional.empty(),"READY"),model);
            committed.put(id,epoch[0]);
        }
        void drain() {
            while(!attempts.isEmpty()) {
                for(long request:List.copyOf(attempts.keySet())) {
                    var attempt=attempts.remove(request);var command=commands.remove(request);
                    if(failing.test(attempt.islandId()))coordinator.completed(attempt,Optional.empty());
                    else coordinator.completed(attempt,Optional.of((ProcessSolveServices.FluidIslandSolveResult)command.solve(FluidTestSupport.NEVER)));
                }
                coordinator.pump();
            }
        }
        void run(double seconds){long ticks=Math.round(seconds*20);for(long i=0;i<ticks;i++){epoch[0]++;coordinator.tick();drain();}}
        double seconds(){return epoch[0]/20.0;}
        /** An inlet step (W4): island {@code id} replaced at its committed tick, through a topology event, by its own graph
         * with generator {@code node} delivering {@code state}. */
        void step(long id,long node,FluidThermodynamics.State state) {
            var island=coordinator.snapshot(id);var event=UUID.randomUUID();long tick=island.clock().committedTick();
            coordinator.fence(event,tick,Set.of(id));
            var nodes=new ArrayList<>(island.graph().reservoirs());
            for(int i=0;i<nodes.size();i++)if(nodes.get(i).id()==node)nodes.set(i,new PassiveNetwork.Reservoir(node,nodes.get(i).elevation(),state,PassiveNetwork.NodeKind.GENERATOR));
            var graph=new PassiveNetwork(nodes,island.graph().pipes(),island.graph().scheduledTransfers());
            coordinator.topology(Set.of(event),Set.of(id),List.of(new IslandCoordinator.Replacement(id,graph)),model,tick,island.clock().onlineTick(),Map.of(),Set.of(),Map.of(),()->{});
        }
        IslandCoordinator.Snapshot island(long id){return coordinator.observe(id);}
        /** The heat in transit as of the side further behind (what the give-backs return). */
        double transit(){return coordinator.settledTransit(PhysicalFluidTopology.sideIdentity(Math.min(HOT,COLD)));}
        /** The transit as booked: it also holds the heat the side ahead booked past the other's committed tick. */
        double booked(){return coordinator.transits().getOrDefault(PhysicalFluidTopology.sideIdentity(Math.min(HOT,COLD)),0.);}
    }
    private static PassiveNetwork.Pipe pipe(PassiveNetwork graph,long side){return graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(side)).findFirst().orElseThrow();}
    private static long islandOf(PhysicalFluidTopology.Compiled compiled,long side) {
        for(int i=0;i<compiled.islands().size();i++){var graph=compiled.islands().get(i).graph();if(graph.pipes().stream().anyMatch(p->p.id()==PhysicalFluidTopology.sideIdentity(side)))return i+1;}
        throw new AssertionError("side "+side+" in no island");
    }
    /** A side as it stands in its island now: inlet and outlet node states, its last step's flow (kg/s) and its law. */
    private record Seen(FluidThermodynamics.State in,FluidThermodynamics.State out,double flow,HeatSide law) {}
    private static Seen seen(IslandCoordinator.Snapshot island,long side) {
        var graph=island.graph();var p=pipe(graph,side);int index=graph.pipes().indexOf(p);
        return new Seen(graph.reservoirs().get(p.first()).state(),graph.reservoirs().get(p.second()).state(),island.lastResult().orElseThrow().endpointFlows()[index],(HeatSide)p.heat());
    }
    /** The 8-cell countercurrent steady state of the two sides as they stand (plan section 5.1): each outlet's temperature, K. */
    private double[] steady(Seen hot,Seen cold) {
        double low=Math.min(hot.in.temperature(),cold.in.temperature())-10,high=Math.max(hot.in.temperature(),cold.in.temperature())+10;
        var a=new ExchangerRelay.Stream(curve(hot.in,low,high),hot.in.enthalpy()/hot.in.mass(),hot.flow,hot.law,true);
        var b=new ExchangerRelay.Stream(curve(cold.in,low,high),cold.in.enthalpy()/cold.in.mass(),cold.flow,cold.law,false);
        var wall=ExchangerRelay.wall(a,b,null);assertTrue(wall.converged(),"the steady wall converges");
        double[] reversed=wall.temperatures().clone();for(int k=0;k<reversed.length/2;k++){double t=reversed[k];reversed[k]=reversed[reversed.length-1-k];reversed[reversed.length-1-k]=t;}
        var bAsOwn=new ExchangerRelay.Stream(b.curve(),b.inlet(),b.massFlow(),b.law(),true);
        return new double[]{a.curve().temperatureAt(ExchangerRelay.outlet(a,wall.temperatures())),b.curve().temperatureAt(ExchangerRelay.outlet(bAsOwn,reversed))};
    }
    /** The side's duty as it stands, W. */
    private static double duty(Seen s){return Math.abs(s.flow*(s.out.enthalpy()/s.out.mass()-s.in.enthalpy()/s.in.mass()));}
    /** Decision D4's bound: a settled transit below a thousandth of a 5 s slice's heat wakes nothing. */
    private static void assertSettled(Rig rig,Seen side,String label){assertEquals(0,rig.transit(),1e-3*duty(side)*5,label+": the heat in transit returns to 0");}
    private HeatCurve curve(FluidThermodynamics.State s,double low,double high){return HeatCurve.sample(model,PhaseLayout.totalAmounts(s),s.pressure(),low,high,HeatCurve.Films.DEFAULT,()->{});}

    /** Hot water at 90 °C from 2 bar and cold water at 20 °C from 1.5 bar, each through its side into a 1 atm void. */
    private Rig waterPair(int hotCadence,int coldCadence,CertificatePolicy policy){return waterPair(hotCadence,coldCadence,policy,HeatUnit.Control.OFF);}
    private Rig waterPair(int hotCadence,int coldCadence,CertificatePolicy policy,HeatUnit.Control cold) {
        var compiled=PhysicalFluidTopology.compile(exchanger(cold),Map.of(1L,source(1,water(363.15,200_000)),2L,sink(2,101_325),3L,source(3,water(293.15,150_000)),4L,sink(4,101_325)));
        assertEquals(2,compiled.islands().size(),"each side in its own island");
        var rig=new Rig(policy);rig.hotIsland=islandOf(compiled,HOT);rig.coldIsland=islandOf(compiled,COLD);
        rig.register(rig.hotIsland,compiled.islands().get((int)rig.hotIsland-1).graph(),hotCadence);
        rig.register(rig.coldIsland,compiled.islands().get((int)rig.coldIsland-1).graph(),coldCadence);
        return rig;
    }
    private void assertSteady(Rig rig,double tolerance,String label){assertSteady(rig,HOT,COLD,tolerance,label);}
    private void assertSteady(Rig rig,long hotSide,long coldSide,double tolerance,String label) {
        var hot=seen(rig.island(rig.hotIsland),hotSide);var cold=seen(rig.island(rig.coldIsland),coldSide);
        double[] expected=steady(hot,cold);
        System.out.printf(Locale.ROOT,"%s: hot %.3f kg/s %.3f -> %.3f K (steady %.3f), cold %.3f kg/s %.3f -> %.3f K (steady %.3f), transit %.3g J%n",label,
                hot.flow,hot.in.temperature(),hot.out.temperature(),expected[0],cold.flow,cold.in.temperature(),cold.out.temperature(),expected[1],rig.transit());
        assertEquals(expected[0],hot.out.temperature(),tolerance,label+": the hot outlet at the countercurrent steady state");
        assertEquals(expected[1],cold.out.temperature(),tolerance,label+": the cold outlet at the countercurrent steady state");
    }
    /** Energy (plan section 5.2): every joule a side booked is in the other side's stream or in transit. */
    private static void assertLedger(Rig rig,double start,String label){assertLedger(rig,HOT,COLD,start,label);}
    private static void assertLedger(Rig rig,long hotSide,long coldSide,double start,String label) {
        double hot=rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(hotSide),0.),cold=rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(coldSide),0.);
        assertTrue(hot<0&&cold>0,label+": the hot side gave heat, the cold side took it");
        assertEquals(0,hot+cold+rig.booked()-start,1e-9*Math.abs(hot),label+": the sides' heat and the transit balance");
    }

    @Test void twoIslandsReachTheCountercurrentSteadyStateAtEqualAndUnequalCadences() {
        for(int[] cadences:new int[][]{{100,100},{20,400},{400,20}}) {
            var rig=waterPair(cadences[0],cadences[1],CertificatePolicy.disabled());String label="cadences "+cadences[0]+"/"+cadences[1]+" ticks";
            rig.run(600);
            assertSteady(rig,.05,label);
            assertSettled(rig,seen(rig.island(rig.hotIsland),HOT),label);
            assertLedger(rig,0,label);
        }
    }
    /**
     * A held side counts as stopped (plan section 5.2): its partner moves no heat while it is held, and the held side sits
     * that span out when it catches up; both settle back to the steady state, energy exact.
     */
    @Test void aHeldSideStopsTheExchangerAndItsPartnerSitsTheHoldOut() {
        var rig=waterPair(100,100,CertificatePolicy.disabled());
        rig.run(300);
        rig.failing=id->id==rig.coldIsland;rig.run(60);
        assertTrue(rig.island(rig.coldIsland).clock().committedTick()<rig.epoch[0]-200,"the cold side is held behind");
        var during=seen(rig.island(rig.hotIsland),HOT);
        System.out.printf(Locale.ROOT,"held: hot %.3f -> %.3f K, cold committed %.1f s of %.1f s%n",during.in.temperature(),during.out.temperature(),rig.island(rig.coldIsland).clock().committedTick()/20.0,rig.seconds());
        assertEquals(during.in.temperature(),during.out.temperature(),.5,"the hot side moves no heat while its partner is held");
        rig.failing=id->false;rig.run(600);
        assertSteady(rig,.05,"after a 60 s hold");assertSettled(rig,seen(rig.island(rig.hotIsland),HOT),"after a 60 s hold");assertLedger(rig,0,"after a 60 s hold");
    }
    /**
     * A feed/effluent exchanger (plan section 5.2): the feed at 20 C passes the tube side, a radiant coil fires it to 90 C,
     * and the effluent passes the shell side back against the feed, all in one island. The loop closes across slices.
     */
    @Test void aFeedEffluentLoopInOneIslandSettles() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>(List.of(plain(1,0,0,0,Kind.GENERATOR),plain(110,1,0,0,Kind.PIPE),
                side(COLD,at(2,0,0),at(4,0,0),HeatSides.tubePassage(SHELL,TUBES),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,AREA,HOT)),plain(210,5,0,0,Kind.PIPE),
                side(12,at(6,0,0),at(8,0,0),HeatSides.heaterPassage(10,1),new HeatUnit(HeatSides.Unit.FIRED_HEATER,HeatSides.Side.RADIANT,200,0).withControl(new HeatUnit.Control(HeatUnit.Mode.FIRING,363.15,0,1))),
                plain(212,9,0,0,Kind.PIPE),side(HOT,at(10,0,0),at(12,0,0),HeatSides.shellPassage(SHELL,TUBES),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.SHELL,AREA,COLD)),
                plain(211,13,0,0,Kind.PIPE),plain(2,14,0,0,Kind.VOID)));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,source(1,water(293.15,300_000)),2L,sink(2,101_325)));
        assertEquals(1,compiled.islands().size(),"the loop is one island");
        var rig=new Rig(CertificatePolicy.disabled());rig.hotIsland=1;rig.coldIsland=1;rig.register(1,compiled.islands().getFirst().graph(),100);
        rig.run(600);
        assertSteady(rig,.05,"feed/effluent");
        var hot=seen(rig.island(1),HOT);
        assertEquals(363.15,hot.in.temperature(),.05,"the effluent arrives at the coil's set point");
        assertSettled(rig,hot,"feed/effluent");
        assertLedger(rig,0,"feed/effluent");
    }
    /** A gas of {@code water} mole fraction steam, the rest methane, at {@code temperature} K and {@code pressure} Pa, 1 m3. */
    private FluidThermodynamics.State mixture(double temperature,double pressure,double water) {
        double[] n=new double[model.componentCount()];int methane=model.components().indexOf("Methane");
        assertTrue(methane>=0,"the network basis has methane: "+model.components());
        n[methane]=1-water;n[n.length-1]=water;
        double volume=model.flashTP(temperature,pressure,n,()->{}).volume();for(int c=0;c<n.length;c++)n[c]/=volume;
        return model.flashTP(temperature,pressure,n,()->{});
    }
    /**
     * A condensing mixture against water at 20 s slices (plan sections 5.2 and 13): 70 % steam in methane at 3 bar and
     * 140 C, its dew point near 122 C, cooled by water at 20 C. The quasi-steady wall on the two phase-change curves
     * converges, and the islands settle on it.
     */
    @Test void aCondensingMixtureCoolsAgainstWaterAtTwentySecondSlices() {
        var compiled=PhysicalFluidTopology.compile(exchanger(),Map.of(1L,source(1,mixture(413.15,300_000,.7)),2L,sink(2,101_325),3L,source(3,water(293.15,150_000)),4L,sink(4,101_325)));
        var rig=new Rig(CertificatePolicy.disabled());rig.hotIsland=islandOf(compiled,HOT);rig.coldIsland=islandOf(compiled,COLD);
        rig.register(rig.hotIsland,compiled.islands().get((int)rig.hotIsland-1).graph(),400);
        rig.register(rig.coldIsland,compiled.islands().get((int)rig.coldIsland-1).graph(),400);
        rig.run(1200);
        assertSteady(rig,.05,"condensing at 20 s");assertLedger(rig,0,"condensing at 20 s");
        // The wall solve's cost on these curves (plan section 13): sweeps to the tolerance, and the time of one.
        var hot=seen(rig.island(rig.hotIsland),HOT);var cold=seen(rig.island(rig.coldIsland),COLD);
        double low=Math.min(hot.in.temperature(),cold.in.temperature())-10,high=Math.max(hot.in.temperature(),cold.in.temperature())+10;
        var a=new ExchangerRelay.Stream(curve(hot.in,low,high),hot.in.enthalpy()/hot.in.mass(),hot.flow,hot.law,true);
        var b=new ExchangerRelay.Stream(curve(cold.in,low,high),cold.in.enthalpy()/cold.in.mass(),cold.flow,cold.law,false);
        var cold0=ExchangerRelay.wall(a,b,null);assertTrue(cold0.converged(),"the wall converges from the inlets' mean");
        long start=System.nanoTime();int runs=50;for(int i=0;i<runs;i++)ExchangerRelay.wall(a,b,null);double ms=(System.nanoTime()-start)/1e6/runs;
        System.out.printf(Locale.ROOT,"condensing wall: %d sweeps from the mean, %.3f ms each; warm start %d sweep%n",cold0.sweeps(),ms,ExchangerRelay.wall(a,b,cold0.temperatures()).sweeps());
        assertTrue(cold0.sweeps()<=60,"the wall converges in "+cold0.sweeps()+" sweeps");
        assertEquals(1,ExchangerRelay.wall(a,b,cold0.temperatures()).sweeps(),"a steady side keeps its wall");
    }
    /**
     * A side without flow takes none of the heat in transit and books exactly 0 J (plan section 5.2); its flowing partner
     * gives the transit back within its inlet temperatures.
     */
    @Test void aSideWithoutFlowTakesNoneOfTheTransit() {
        var compiled=PhysicalFluidTopology.compile(exchanger(),Map.of(1L,source(1,water(363.15,200_000)),2L,sink(2,101_325),3L,source(3,water(293.15,101_325)),4L,sink(4,101_325)));
        var rig=new Rig(CertificatePolicy.disabled());rig.hotIsland=islandOf(compiled,HOT);rig.coldIsland=islandOf(compiled,COLD);
        rig.register(rig.hotIsland,compiled.islands().get((int)rig.hotIsland-1).graph(),100);
        rig.register(rig.coldIsland,compiled.islands().get((int)rig.coldIsland-1).graph(),100);
        rig.run(120);
        assertEquals(0.0,seen(rig.island(rig.coldIsland),COLD).flow,"the cold side stands still");
        long pair=PhysicalFluidTopology.sideIdentity(Math.min(HOT,COLD));
        double cold=rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(COLD),0.),hot=rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(HOT),0.),before=rig.booked();
        System.out.printf(Locale.ROOT,"still: hot %.4g J, cold %.4g J, transit %.4g J before%n",hot,cold,before);
        rig.coordinator.transit(pair,before-1e6);rig.run(120);
        assertEquals(cold,rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(COLD),0.),"the still side books exactly 0 J");
        assertEquals(0,rig.transit(),1e3,"the flowing side gives the transit back, but for a thousandth");
        assertEquals(before-1e6-rig.booked(),rig.heat.getOrDefault(PhysicalFluidTopology.sideIdentity(HOT),0.)-hot,1e-6,"all of it, as heat into its stream");
    }
    /**
     * A save and load while the sides stand apart (plan section 9, decision D1): at 290 s the 5 s side has booked to 285 s
     * and the 20 s side to 280 s, the 5 s side's last span lying past the other's committed tick. The checkpoint carries the
     * islands, the facts the transit as booked; the side behind reads the other's last span from its saved interval. The run
     * after the load follows the run without it.
     */
    @Test void aLoadWhileTheSidesStandApartContinuesTheRun() {
        var run=waterPair(100,400,CertificatePolicy.disabled());run.run(290);
        assertEquals(285*20,run.island(run.hotIsland).clock().committedTick(),"the 5 s side waits one slice ahead");
        assertEquals(280*20,run.island(run.coldIsland).clock().committedTick(),"the 20 s side stands behind");
        assertTrue(Math.abs(run.booked()-run.transit())>1e6,"the transit as booked holds the side ahead's last span");
        var loaded=run.reloaded();
        assertEquals(run.transit(),loaded.transit(),1e-6*Math.abs(run.booked()),"the side behind reads the same heat in transit after the load");
        double worst=0;
        for(int i=0;i<30;i++) {
            run.run(2);loaded.run(2);
            for(long[] side:new long[][]{{run.hotIsland,HOT},{run.coldIsland,COLD}})
                worst=Math.max(worst,Math.abs(seen(run.island(side[0]),side[1]).out.temperature()-seen(loaded.island(side[0]),side[1]).out.temperature()));
        }
        System.out.printf(Locale.ROOT,"load at 290 s: outlets within %.4f K of the run without it over 60 s%n",worst);
        assertTrue(worst<.05,"the outlets follow the run without the load: "+worst+" K");
        assertLedger(loaded,0,"after the load");
    }
    /**
     * Certificates (plan section 5.2, the wake): two steady sides certify; heat put in transit wakes the certified other
     * side at the first side's next booking, and both settle back.
     */
    /**
     * W4's gate (plan section 6 and its evidence): the cold side's bypass holds its outlet at 45 °C, where the exchanger
     * without it heats the water to 52.8 °C; then the hot inlet steps from 90 to 105 °C. The controller plans the bypass with
     * the quasi-steady wall at each cold slice start, from the hot side as read, so the step reaches the cold outlet one
     * slice late (nothing damps it) and is taken out over the next slices as the hot side's new record arrives. A set point
     * the exchanger cannot reach closes the bypass and the status says so. Energy stays exact throughout.
     */
    @Test void aBypassHoldsTheColdOutletAfterAHotInletStep() {
        double target=318.15;
        var rig=waterPair(100,100,CertificatePolicy.disabled(),new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,target,0,0));
        rig.run(300);
        var cold=seen(rig.island(rig.coldIsland),COLD);
        assertEquals(target,cold.out.temperature(),.1,"the bypass holds 45 °C");
        assertTrue(cold.law.bypass()>0&&cold.law.bypass()<1,"bypass "+cold.law.bypass());assertEquals(HeatControl.Limit.NONE,cold.law.limit());
        double before=cold.law.bypass();
        rig.step(rig.hotIsland,1,water(378.15,200_000));
        double worst=0,last=0;
        for(int i=1;i<=300;i++){rig.run(1);double error=Math.abs(seen(rig.island(rig.coldIsland),COLD).out.temperature()-target);worst=Math.max(worst,error);if(error>.1)last=i;}
        cold=seen(rig.island(rig.coldIsland),COLD);
        System.out.printf(Locale.ROOT,"hot inlet +15 K at 5 s slices: cold outlet off by at most %.2f K, within 0.1 K from %.0f s; bypass %.3f -> %.3f; transit %.3g J%n",worst,last,before,cold.law.bypass(),rig.transit());
        assertEquals(target,cold.out.temperature(),.1,"held again after the step");
        assertTrue(cold.law.bypass()>before,"the bypass opens to the hotter inlet");
        assertTrue(last<=120,"within 0.1 K after "+last+" s");
        assertLedger(rig,0,"under control");
        var short_=waterPair(100,100,CertificatePolicy.disabled(),new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,353.15,0,.2));
        short_.run(120);
        var closed=seen(short_.island(short_.coldIsland),COLD);
        assertEquals(0,closed.law.bypass());assertEquals(HeatControl.Limit.HIGHEST,closed.law.limit());
        assertEquals("LIMITED: the bypass is closed / ",FluidWorldAuthority.controlStatus(closed.law,null));
        assertSteady(short_,.05,"80 °C out of reach");
    }
    @Test void certifiedSidesWakeWhenTheTransitMoves() {
        var rig=waterPair(100,100,CertificatePolicy.defaults());
        for(int i=0;i<240&&!(rig.island(rig.hotIsland).certificate().isPresent()&&rig.island(rig.coldIsland).certificate().isPresent());i++)rig.run(5);
        System.out.printf(Locale.ROOT,"certified at %.0f s: hot %s, cold %s; refusals %s / %s%n",rig.seconds(),rig.island(rig.hotIsland).certificate().isPresent(),rig.island(rig.coldIsland).certificate().isPresent(),
                rig.coordinator.certificationRefusal(rig.hotIsland),rig.coordinator.certificationRefusal(rig.coldIsland));
        assertTrue(rig.island(rig.hotIsland).certificate().isPresent()&&rig.island(rig.coldIsland).certificate().isPresent(),"both steady sides certify");
        boolean enabled=FluidRuntimeDiagnostics.ENABLED;FluidRuntimeDiagnostics.ENABLED=true;long wakes=FluidRuntimeDiagnostics.couplingWakes.sum();
        long pair=PhysicalFluidTopology.sideIdentity(Math.min(HOT,COLD));double before=rig.transit();
        rig.coordinator.transit(pair,before-5e5);
        rig.run(10);rig.coordinator.snapshot(rig.hotIsland);rig.coordinator.snapshot(rig.coldIsland);rig.run(1);
        long woken=FluidRuntimeDiagnostics.couplingWakes.sum()-wakes;FluidRuntimeDiagnostics.ENABLED=enabled;
        assertTrue(woken>0,"the transit woke a certified side");
        rig.run(900);
        assertSteady(rig,.05,"after the wake");
        assertSettled(rig,seen(rig.island(rig.hotIsland),HOT),"after the wake");
        assertLedger(rig,-5e5,"after the wake");
    }
}
