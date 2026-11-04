package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.state.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.transport.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolidTransportAcceptanceTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    /** The mobility check of a bulk withdrawal from {@code donor}, as the solid event integrator makes it. */
    private SolidMobility.Check check(FluidThermodynamics.State donor,PassiveNetwork.Pipe pipe,double massFlow,SolidMobility.Outlet outlet){return SolidMobility.check(SolidMobility.donor(model,donor),donor,pipe,massFlow,outlet,donor.mass());}
    private SolidInventory stock(int count,double total) {
        var entries=new ArrayList<SolidInventory.Population>();
        for(int i=0;i<count;i++)entries.add(new SolidInventory.Population(model.solids.require("icheme:demo_particle"),ParticleSize.micrometres(Integer.toString(i+1)),total/count));
        return new SolidInventory(entries);
    }
    private FluidThermodynamics.State water(double p) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(350,p,n,()->{}).volume();
        return model.flashTP(350,p,n,()->{});
    }
    private PassiveNetwork.Pipe pipe(){return new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0));}
    @Test void suspensionBoundaryIsStrictAndReversalUsesTheSameLiquidVelocity() {
        var state=water(160000).withSolids(stock(1,100));var pipe=pipe();
        double dep=check(state,pipe,0,SolidMobility.Outlet.MIXED).minimumVelocity();
        double q=dep*pipe.maximumArea()*state.mass()/state.waterVolume();
        assertFalse(check(state,pipe,q*(1-1e-10),SolidMobility.Outlet.MIXED).allowed());
        assertTrue(check(state,pipe,q*(1+1e-10),SolidMobility.Outlet.MIXED).allowed());
        assertTrue(check(state,pipe,-q*(1+1e-10),SolidMobility.Outlet.MIXED).allowed());
        assertEquals(SolidMobility.Reason.DEPOSITION,check(state,pipe,0,SolidMobility.Outlet.MIXED).reason());
    }
    /**
     * Owner decision D33: solids ride the liquid. A gas holding dry particles has nothing to carry them: its bulk end is the
     * GAS outlet, always mobile (it used to close both ways, NO_LIQUID_CARRIER), the gas leaves and every particle stays,
     * trace populations keeping their slots; a dry gas without solids draws its bulk, the stream it always was.
     */
    @Test void aBulkEndOnDryParticlesPassesTheGasAndKeepsTheParticles() {
        var gas=model.initialNitrogenCharge(1,298.15,101325,()->{});var dusty=gas.withSolids(stock(1,1));
        assertTrue(FluidThermodynamics.bulkDrawsGas(dusty));assertFalse(FluidThermodynamics.bulkDrawsGas(gas));
        assertEquals(FluidThermodynamics.GAS,PhaseDraw.leading(model,dusty,PassiveNetwork.PhasePort.BULK),"the bulk end is the GAS outlet");
        assertTrue(check(dusty,pipe(),1,SolidMobility.Outlet.GAS).allowed());
        var traces=gas.withSolids(stock(64,1e-12));
        assertEquals(FluidThermodynamics.GAS,PhaseDraw.leading(model,traces,PassiveNetwork.PhasePort.BULK));
        assertEquals(64,traces.solids().populations().size());assertTrue(traces.mass()>gas.mass());
        // One step into a void at half the pressure: the gas leaves through the bulk end, the particles stay in the tank.
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,dusty),
                new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,50000,()->{}),PassiveNetwork.NodeKind.VOID)),List.of(pipe()));
        var result=new PassiveStepSolver(model).solve(graph,.001,()->{});
        assertTrue(result.massFlows()[0]>0,"the gas leaves");
        assertEquals(1,result.inventories().getFirst().solids().massKg(),1e-12,"every particle stays");
        assertTrue(result.boundaries().stream().allMatch(b->b.solids().massKg()==0),"none reaches the void");
    }
    @Test void reverseFiltrationCapturesTheOtherDonorAndConservesEnergy() {
        var source=water(160000).withSolids(stock(8,100));var sink=water(150000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,sink),new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.GENERATOR)),
                List.of(pipe().withFilter(InlineFilter.empty())));
        var result=new PassiveStepSolver(model).solve(graph,.001,()->{});
        assertTrue(result.massFlows()[0]<0);assertTrue(result.inventories().getFirst().solids().empty());
        var captured=result.filters().get(3L);assertEquals(8,captured.captured().populations().size());
        double mass=result.boundaries().stream().mapToDouble(b->b.solids().massKg()*b.solidDirection()).sum();
        assertEquals(mass,captured.captured().massKg(),1e-9);
        double before=sink.internalEnergy(),after=result.inventories().getFirst().internalEnergy()+captured.energyJoule();
        assertEquals(result.externalEnergyJoule(),after-before,1e-5);
    }
    @Test void drainingSlurryClosesAtAReproducibleInteriorEvent() {
        var solids=new SolidInventory(List.of(new SolidInventory.Population(model.solids.require("icheme:demo_particle"),ParticleSize.micrometres("100"),100)));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,water(160000).withSolids(solids)),new PassiveNetwork.Reservoir(2,0,water(150000))),List.of(pipe()));
        var first=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var second=new PassiveIntervalSolver(model).solve(graph,.05,new PassiveIntervalSolver.Settings(.0005,.001,1024),()->{});
        assertNotEquals(0,first.graph().pipes().getFirst().blockedDirections());
        assertTrue(first.averageMassFlows()[0]>0);
        assertTrue(first.rejectionReasons().keySet().stream().anyMatch(s->s.contains("DEPOSITION")));
        // A deposition closes at the end of the step that shows it, late by at most D/v_s (owner decision of 2026-11-03,
        // form G), so the two step grids differ by at most the solids a flow at the deposition velocity carries in between.
        var slurry=graph.reservoirs().getFirst().state();double deposition=deposition(first),late=Math.abs(closure(first)-closure(second));
        assertTrue(late<=Math.sqrt(4*pipe().maximumArea()/Math.PI)*model.solidSettings.suspensionMultiplier()/deposition,"closures "+closure(first)+" and "+closure(second));
        double carried=slurry.solids().massKg()/slurry.waterVolume()*pipe().maximumArea()*deposition*late;
        assertEquals(first.graph().reservoirs().get(1).inventory().solids().massKg(),second.graph().reservoirs().get(1).inventory().solids().massKg(),carried+2e-6,"carried "+carried+" in "+late+" s");
        double after=first.graph().reservoirs().stream().mapToDouble(r->r.inventory().solids().massKg()).sum();
        assertEquals(100,after,1e-9);
    }
    /** The value after {@code name=} in the key of an interval's deposition closure, as the event integrator records it. */
    private static double closureValue(PassiveIntervalSolver.Result result,String name,String end) {
        for(String key:result.rejectionReasons().keySet())if(key.startsWith("blocked with solid: DEPOSITION")){int at=key.lastIndexOf(name)+name.length();return Double.parseDouble(end==null?key.substring(at):key.substring(at,key.indexOf(end,at)));}
        throw new AssertionError("No deposition closure among "+result.rejectionReasons().keySet());
    }
    private static double closure(PassiveIntervalSolver.Result result){return closureValue(result,"; t=",null);}
    private static double deposition(PassiveIntervalSolver.Result result){return closureValue(result,"deposition=",";");}
    @Test void equalParallelBranchesChooseTheSameClosuresAcrossReplays() {
        var source=water(150001).withSolids(stock(64,100));
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,water(150000),PassiveNetwork.NodeKind.VOID));
        var pipes=List.of(pipe(),new PassiveNetwork.Pipe(4,0,1,pipe().sections(),new FlowControl.Passive()));
        var graph=new PassiveNetwork(nodes,pipes);
        var a=new PassiveIntervalSolver(model).solve(graph,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        var b=new PassiveIntervalSolver(model).solve(graph,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(a.graph().pipes(),b.graph().pipes());assertArrayEquals(a.averageMassFlows(),b.averageMassFlows());
        assertTrue(a.graph().pipes().stream().allMatch(p->p.blockedDirections()!=0));
    }
    @Test void highViscosityBlocksTheMixedOutletBeforeSlurryThickening() {
        // Either side of crude_pc12's 100 Pa s crossing, 382.4 K on its Twu 1985 table since O4 of batch
        // 2026-11-04-open-thermo-data (330 and 340 K about the Dalia table's 333.9 K before).
        var n=new double[model.componentCount()];n[model.components().indexOf("crude_pc12")]=1;
        var cold=model.flashTP(380,150000,n,()->{});var hot=model.flashTP(390,150000,n,()->{});
        assertEquals(SolidMobility.Reason.IMMOBILE_LIQUID,check(cold,pipe(),1,SolidMobility.Outlet.MIXED).reason());
        assertTrue(check(hot,pipe(),1,SolidMobility.Outlet.MIXED).allowed());
        assertTrue(check(cold,pipe(),1,SolidMobility.Outlet.GAS).allowed());
        assertTrue(SolidMobility.closable(model,new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,cold)),List.of())));
    }
    @Test void populationCountDoesNotIncreaseTheNonlinearDimension() throws Exception {
        int dimension=-1,unknowns=-1,colors=-1;
        var report=new ArrayList<Map<String,Object>>();
        for(int count:new int[]{1,8,64}) {
            var source=water(160000).withSolids(stock(count,100));
            var layout=new PhaseLayout(model,source);
            if(dimension<0)dimension=layout.size();else assertEquals(dimension,layout.size());
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,water(150000))),List.of(pipe()));
            var result=new PassiveStepSolver(model).solve(graph,.001,()->{});
            var times=new ArrayList<Double>();for(int repeat=0;repeat<5;repeat++){long start=System.nanoTime();result=new PassiveStepSolver(model).solve(graph,.001,()->{});times.add((System.nanoTime()-start)/1e6);}
            if(unknowns<0){unknowns=result.numerical().variables().length;colors=result.numerical().colors();}
            assertEquals(unknowns,result.numerical().variables().length);assertEquals(colors,result.numerical().colors());
            assertEquals(count,result.inventories().get(1).solids().populations().size());
            assertEquals(result.inventories().get(1).solids().moments(),result.states().get(1).solidMoments());
            report.add(Map.of("populations",count,"layout",dimension,"nonlinearUnknowns",unknowns,"jacobianColors",colors,"warmMilliseconds",times));
        }
    }
    /** {@code count} distinct grades from {@code from} micrometres up, each so light that its
     * volume fraction is orders below the trace cutoff: conserved and transported, never active
     * for blockage, so what closes here can only be the population limit. */
    private SolidInventory traceGrades(int from,int count) {
        var entries=new ArrayList<SolidInventory.Population>();
        for(int i=0;i<count;i++)entries.add(new SolidInventory.Population(model.solids.require("icheme:demo_particle"),
                ParticleSize.micrometres(Integer.toString(from+i)),1e-12));
        return new SolidInventory(entries);
    }
    @Test void aSixtyFifthPopulationClosesAFeedInsteadOfStallingTheInterval() {
        // Two feeds of 64 grades each into one reservoir. Their union is 128 and a conserved stock
        // holds 64, so the reconstruction refused every step of every interval and no smaller step
        // could ever make one work: the island was held forever. It is a feasibility statement
        // about the connection, so it closes one like any other transport failure does.
        var run=new PipeResistance.Geometry(100,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,water(200000).withSolids(traceGrades(1,64)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(190000).withSolids(traceGrades(65,64)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,water(150000)),
                new PassiveNetwork.Reservoir(4,0,water(100000),PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(10,0,2,run),new PassiveNetwork.Pipe(11,1,2,run),new PassiveNetwork.Pipe(12,2,3,run)));
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});

        assertEquals(5,result.advancedSeconds());
        assertTrue(result.rejectionReasons().keySet().stream().anyMatch(k->k.contains("POPULATION_LIMIT")),
                "The limit must be declared as a closure: "+result.rejectionReasons());
        var receiver=result.graph().reservoirs().get(2).inventory().solids();
        assertTrue(receiver.populations().size()<=SolidInventory.MAXIMUM_POPULATIONS,
                "The receiver holds "+receiver.populations().size()+" populations");
        assertFalse(receiver.empty(),"The surviving feed must still deliver");
        assertEquals(1,result.graph().pipes().stream().filter(p->p.blockedDirections()!=0).count(),
                "Only the one feed the deterministic rule names may close: "+result.graph().pipes());
        System.out.println("solid populations: "+receiver.populations().size()+" held, reasons="+result.rejectionReasons());
    }
    @Test void theFlaggedFeedThatCarriesTheKeysNobodyElseSuppliesIsTheOneThatCloses() {
        // Three feeds into one receiver: four grades by one connection, ten by another and sixty by a third. The union
        // is 74 and a conserved stock holds 64, so every feed is flagged; but closing the four-grade feed leaves 70,
        // still over the limit, and the ten-grade feed then closes on the next pass - two closures, both for deliveries
        // that always fit. Only the feed whose keys nobody else supplies has to stop.
        // Owner decision D23 (amended 2026-10-15): a stock holds one size per solid after its refresh, so a receiver
        // can no longer start an interval holding sixty grades of one solid (it held them here before); the limit is met
        // by what arrives within one interval, and the sixty grades now arrive from their own source.
        var run=new PipeResistance.Geometry(100,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,water(200000).withSolids(traceGrades(1,4)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(200000).withSolids(traceGrades(5,10)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,water(150000)),
                new PassiveNetwork.Reservoir(4,0,water(100000),PassiveNetwork.NodeKind.VOID),
                new PassiveNetwork.Reservoir(5,0,water(200000).withSolids(traceGrades(101,60)),PassiveNetwork.NodeKind.GENERATOR)),List.of(
                new PassiveNetwork.Pipe(10,0,2,run),new PassiveNetwork.Pipe(11,1,2,run),new PassiveNetwork.Pipe(12,2,3,run),new PassiveNetwork.Pipe(13,4,2,run)));
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});

        assertEquals(5,result.advancedSeconds());
        var closed=result.graph().pipes().stream().filter(p->p.blockedDirections()!=0).toList();
        assertEquals(1,closed.size(),"Only one feed may close: "+result.graph().pipes());
        assertEquals(13,closed.getFirst().id(),"The sixty-grade feed is the one whose keys nobody else supplies");
        assertTrue(result.averageMassFlows()[0]>0,"The four-grade feed still delivers");
        assertTrue(result.averageMassFlows()[1]>0,"The ten-grade feed still delivers");
        assertTrue(result.rejectionReasons().keySet().stream().anyMatch(k->k.contains("POPULATION_LIMIT")&&k.contains("pipe=13")),
                "The closure names the connection it closed: "+result.rejectionReasons());
        var receiver=result.graph().reservoirs().get(2).inventory().solids();
        assertEquals(14,receiver.populations().size(),"Four and ten grades delivered, each its own population until the receiver's next refresh");
    }
    @Test void closedParticlesRestartWhenDrivingPressureIncreases() {
        var source=water(150001).withSolids(stock(64,100));var sink=water(150000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID)),List.of(pipe()));
        var stopped=new PassiveIntervalSolver(model).solve(graph,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,stopped.averageMassFlows()[0],1e-10);
        assertNotEquals(0,stopped.graph().pipes().getFirst().blockedDirections());
        var driven=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,water(250000).withSolids(source.solids()),PassiveNetwork.NodeKind.GENERATOR),graph.reservoirs().get(1)),stopped.graph().pipes());
        var restarted=new PassiveIntervalSolver(model).solve(driven,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(restarted.averageMassFlows()[0]>0);assertEquals(0,restarted.graph().pipes().getFirst().blockedDirections());
    }
}
