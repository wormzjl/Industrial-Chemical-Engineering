package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The pump's suction (decisions D1, D4, D6 and A37 of documentation/2026-09-26-phase-ports-and-compressor; plan 3.6): the
 * supply walk and its measures ({@link InletPhase}), and the plan's fixtures 4 (pumps on gas and on a two-phase supply; a
 * swept supply), 5 (a pump draining a tank's bottom port) and 8 (certified replay). Restated for request 11 (batch
 * 2026-11-03-rotating-machinery, owner decisions D19 and D20): gas at a pump is no refusal, so the vapour refusal
 * {@link FlowControl.Mode#INLET_WRONG_PHASE} and its hysteresis these fixtures asserted are gone; a set-flow pump's law
 * counts the suction mixture's volume and moves what it is fed. A dry suction still refuses (DrySuctionTest). Every slice is a job as the island runtime runs it ({@link PassiveIntervalSolver#replayStart} from the committed
 * interval), commits whole, and closes the component ledger to 1e-12 and the energy ledger to 1e-10. Each fixture prints
 * an {@code INLET_PHASE_RUN} line; the numbers are recorded in PHASE_PORTS_REVIEW.md, section "WP3".
 */
class InletPhaseTest extends PortFixture {
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl control,PhasePort pa,PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(g),control,0,null,pa,pb);
    }
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl control){return pipe(id,a,b,g,control,PhasePort.BULK,PhasePort.BULK);}

    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen. */
    /** Vapour share of a state's fluid volume. */
    private static double share(FluidThermodynamics.State s){return s.vaporVolume()/(s.vaporVolume()+TestPhases.liquidVolume(s)+TestPhases.waterVolume(s));}
    /** Water with nitrogen at 298.15 K and {@code pressure} whose vapour fills {@code target} of its fluid volume (bisection
     * on the nitrogen amount; 1 m3 of water). */
    private FluidThermodynamics.State twoPhase(double target,double pressure) {
        double[] n=new double[mw.length];n[water]=997/mw[water];
        if(target==0)return model.flashTP(298.15,pressure,n,NOOP);
        double lo=0,hi=1;
        while(true){n[nitrogen]=hi;if(share(model.flashTP(298.15,pressure,n,NOOP))>target)break;hi*=2;}
        for(int i=0;i<80;i++){n[nitrogen]=.5*(lo+hi);if(share(model.flashTP(298.15,pressure,n,NOOP))>target)hi=n[nitrogen];else lo=n[nitrogen];}
        n[nitrogen]=.5*(lo+hi);return model.flashTP(298.15,pressure,n,NOOP);
    }

    /** Committed slices of one run. */
    private record Run(List<PassiveIntervalSolver.Result> slices,List<PassiveNetwork> graphs,Map<String,Integer> reasons,int accepted,int rejected) {
        PassiveNetwork last(){return graphs.getLast();}
    }
    private Run drive(String label,PassiveNetwork graph,double interval,int count){return drive(label,graph,interval,count,new PassiveIntervalSolver(model),null);}
    /** {@code count} slices of {@code interval}, each a job from the committed interval {@code committed}, ledgers checked. */
    private Run drive(String label,PassiveNetwork graph,double interval,int count,PassiveIntervalSolver solver,PassiveIntervalSolver.Result committed) {
        graph=PassiveNetwork.sizeJunctionHoldups(graph,model);
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0;
        var slices=new ArrayList<PassiveIntervalSolver.Result>();var graphs=new ArrayList<PassiveNetwork>();var reasons=new TreeMap<String,Integer>();int accepted=0,rejected=0;
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed);
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice+": the whole slice commits");
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            externalEnergy+=result.pumpWorkJoule();
            var now=owned(graph);
            for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);
                assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
            double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
            assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
            accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            slices.add(result);graphs.add(graph);
        }
        return new Run(slices,graphs,reasons,accepted,rejected);
    }
    private static void assertSameDoubles(String label,PassiveIntervalSolver.Result a,PassiveIntervalSolver.Result b,int nodes,int edges) {
        for(int e=0;e<edges;e++)assertEquals(Double.doubleToLongBits(a.averageMassFlows()[e]),Double.doubleToLongBits(b.averageMassFlows()[e]),label+" edge "+e+" flow");
        assertEquals(a.acceptedSubsteps(),b.acceptedSubsteps(),label+" accepted");
        for(int n=0;n<nodes;n++) {
            var x=a.graph().reservoirs().get(n);var y=b.graph().reservoirs().get(n);
            assertEquals(Double.doubleToLongBits(x.state().pressure()),Double.doubleToLongBits(y.state().pressure()),label+" node "+n+" pressure");
            assertEquals(Double.doubleToLongBits(x.state().temperature()),Double.doubleToLongBits(y.state().temperature()),label+" node "+n+" temperature");
            assertArrayEquals(x.inventory().moles(),y.inventory().moles(),label+" node "+n+" moles");
            assertEquals(Double.doubleToLongBits(x.inventory().internalEnergy()),Double.doubleToLongBits(y.inventory().internalEnergy()),label+" node "+n+" energy");
        }
    }

    // ---- The supply walk and its measures ---------------------------------------------------------------------------

    /**
     * The walk reads the source, not the pump's own junction: through its junction and every degree-two passive junction
     * to the vessel, whose BULK end supplies its bulk; a branch junction supplies its own mixture; a junction an actuator
     * touches ends the walk. A phase port supplies its priority draw at the pump's target over the slice (decision A37):
     * a bottom port on plenty of water reads water, a top port gas, a dry bottom port gas, a bottom port holding less
     * water than one slice of the target reads the gas it would break through to; with no target, the leading phase.
     */
    @Test void theSupplyWalkReadsTheSourceThroughDegreeTwoJunctionsAndAPortsDraw() {
        var vessel=waterUnderNitrogen(.5,150000);double vesselShare=share(vessel);
        var junctionSeed=waterSupply(150000);
        // vessel 0 - J1 - J2 - pump - void 3, and a second void 4 hanging off J1 when branched.
        for(boolean branched:new boolean[]{false,true}) {
            var nodes=new ArrayList<>(List.of(new PassiveNetwork.Reservoir(10,0,vessel),new PassiveNetwork.Reservoir(11,0,junctionSeed,PassiveNetwork.NodeKind.JUNCTION),
                    new PassiveNetwork.Reservoir(12,0,junctionSeed,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(13,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)));
            var pipes=new ArrayList<>(List.of(pipe(1,0,1,line(1,.05),new FlowControl.Passive()),pipe(2,2,1,line(1,.05),new FlowControl.Passive()),pipe(3,2,3,line(1,.05),new FlowControl.Pump(.001,500000))));
            if(branched){nodes.add(new PassiveNetwork.Reservoir(14,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));pipes.add(pipe(4,1,4,line(1,.05),new FlowControl.Passive()));}
            var graph=new PassiveNetwork(nodes,pipes);
            var supply=InletPhase.supply(model,graph,2,.001,5);
            if(branched){assertEquals(11,supply.nodeId(),"a branch junction ends the walk");assertEquals(0,supply.vapourVolumeShare(),"the junction's own (liquid) mixture");}
            else{assertEquals(10,supply.nodeId(),"the walk reaches the vessel");assertEquals(vesselShare,supply.vapourVolumeShare(),1e-15,"its bulk");}
            assertEquals(PhasePort.BULK,supply.port());
        }
        // A valve upstream of the pump's junction ends the walk there: it is not a passive link.
        var valved=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(10,0,vessel),new PassiveNetwork.Reservoir(11,0,junctionSeed,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(12,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(1,0,1,line(1,.05),new FlowControl.PressureValve(120000)),pipe(2,1,2,line(1,.05),new FlowControl.Pump(.001,500000))));
        assertEquals(11,InletPhase.supply(model,valved,1,.001,5).nodeId());
        // Ports on a vessel pumped directly: bottom on plenty of water, top, a dry bottom, a bottom short of one slice.
        var dry=nitrogenAt(150000);var shortOfWater=waterUnderNitrogen(.004,150000);
        for(var c:List.of(Map.entry("bottom",vessel),Map.entry("top",vessel),Map.entry("dry bottom",dry),Map.entry("short bottom",shortOfWater))) {
            var port=c.getKey().equals("top")?PhasePort.VAPOR:PhasePort.LIQUID;
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(10,0,c.getValue()),new PassiveNetwork.Reservoir(13,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                    List.of(pipe(3,0,1,line(1,.05),new FlowControl.Pump(.001,500000),port,PhasePort.BULK)));
            var supply=InletPhase.supply(model,graph,0,.001,5);var leading=InletPhase.supply(model,graph,0,.001,0);
            System.out.println("INLET_PHASE_RUN walk "+c.getKey()+" vapourShare="+supply.vapourVolumeShare()+" leading="+leading.vapourVolumeShare());
            switch(c.getKey()) {
                case "bottom"->{assertEquals(0,supply.vapourVolumeShare());assertEquals(0,leading.vapourVolumeShare());}
                case "top","dry bottom"->{assertEquals(1,supply.vapourVolumeShare());assertEquals(1,leading.vapourVolumeShare());}
                default->{
                    // 4 L of water against 5 L of target over the slice: the draw is 1 L of gas in 5 (by the fluid volumes).
                    double waterVolume=TestPhases.waterVolume(shortOfWater);
                    assertEquals((.005-waterVolume)/.005,supply.vapourVolumeShare(),1e-12,"the draw breaks through to gas");
                    assertEquals(0,leading.vapourVolumeShare(),"the leading phase alone reads water");
                }
            }
        }
    }

    /** Restated for request 11 (owner decisions D19 and D20): D6's thresholds (refuse above 2 % vapour by volume, resume
     * below 0.5 %) are gone. A fed pump, set-flow or a machine on its curve, refuses no vapour share, whether or not it
     * stood refused at the committed slice end; a refused pump is dry, and its reason says so. */
    @Test void aFedPumpRefusesNoVapourShare() {
        var pump=new FlowControl.Pump(.001,500000);
        var machine=new FlowControl.Machine(new com.wormzjl.icheme.science.machine.CentrifugalPump(.01,50,.7),1,true);
        for(double share:new double[]{0,.0049,.0051,.0199,.0201,.34,1})for(boolean before:new boolean[]{false,true}) {
            var supply=new InletPhase.Supply(0,7,PhasePort.BULK,share,0,0,Double.POSITIVE_INFINITY);
            assertFalse(pump.refuses(supply,before),share+" refused before "+before);assertFalse(machine.refuses(supply,before),"machine "+share+" refused before "+before);
        }
        assertEquals("ERROR: pump inlet dry (from node 7)",InletPhase.reason(pump,new InletPhase.Supply(0,7,PhasePort.BULK,.34,0,0,0)));
    }

    // ---- Fixture 4: pumps on gas and on a two-phase supply; a swept supply ------------------------------------------

    /**
     * Plan fixture 4. One island (one graph): an independent water line (a 300 kPa water generator filling a nitrogen
     * tank), a pump between two nitrogen tanks, and a pump on a two-phase generator (30 % vapour by volume) into a
     * nitrogen tank, at 5 s and 0.1 s. Restated for request 11 (owner decisions D19 and D20): both pumps were refused
     * ({@code INLET_WRONG_PHASE}), carried exactly zero and left the water line bitwise as a closed device does; gas at a
     * pump is no refusal now, so the comparison with a closed device is gone. Both pumps run on their laws, each moving
     * what its supply delivers (its set flow is a volume of the suction mixture), every slice commits whole with the
     * ledgers closed, and the water line still flows.
     */
    @Test void pumpsOnGasAndOnATwoPhaseSupplyRunAndTheIslandIntegrates() {
        for(double interval:new double[]{5,.1}) {
            int count=interval==5?6:60;
            var waterLine=List.of(new PassiveNetwork.Reservoir(1,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325)));
            var nodes=new ArrayList<>(waterLine);
            nodes.add(new PassiveNetwork.Reservoir(3,0,nitrogenAt(200000)));nodes.add(new PassiveNetwork.Reservoir(4,0,nitrogenAt(101325)));
            var twoPhase=twoPhase(.3,150000);
            nodes.add(new PassiveNetwork.Reservoir(5,0,twoPhase,PassiveNetwork.NodeKind.GENERATOR));nodes.add(new PassiveNetwork.Reservoir(6,0,nitrogenAt(101325)));
            var island=new PassiveNetwork(nodes,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive()),
                    pipe(11,2,3,line(1,.05),new FlowControl.Pump(.01,500000)),pipe(12,4,5,line(1,.05),new FlowControl.Pump(.001,500000))));
            var run=drive("island@"+interval,island,interval,count);
            for(int i=0;i<count;i++) {
                var s=run.slices().get(i);
                assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,s.endpointModes().get(1),"the nitrogen pump is not refused, slice "+i);
                assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,s.endpointModes().get(2),"the two-phase pump is not refused, slice "+i);
                assertTrue(s.pumpWorkJoule()>=0,"slice "+i);
            }
            var first=run.slices().getFirst();
            assertTrue(first.averageMassFlows()[1]>0,"the nitrogen pump moves the 200 kPa tank's gas");
            assertTrue(first.averageMassFlows()[2]>0,"the two-phase pump moves its supply");
            assertTrue(run.slices().getLast().averageMassFlows()[0]>0,"the water line flows");
            var n2=run.last().reservoirs().get(2).inventory().moles()[nitrogen];
            assertTrue(n2<nodes.get(2).inventory().moles()[nitrogen],"the suction tank gave up nitrogen");
            System.out.println("INLET_PHASE_RUN fixture4 interval="+interval+" slices="+count+" accepted="+run.accepted()+" rejected="+run.rejected()
                    +" reasons="+run.reasons()+" flows="+Arrays.toString(first.averageMassFlows())+" twoPhaseShare="+share(twoPhase));
        }
    }

    /**
     * Plan fixture 4, the swept supply: a pump on a generator whose supply is swept 0 % to 5 % vapour by volume and back,
     * one 5 s slice per step; the generator's state changes between slices (an input change) and each slice is a job from
     * the committed interval. Restated for request 11 (owner decisions D19 and D20): it refused first at 2.1 % and ran again
     * first at 0.4 % (D6's hysteresis); now it is never refused and runs at every step of the sweep.
     */
    @Test void aSweptSupplyIsNeverRefused() {
        double[] sweep={0,.01,.019,.021,.03,.05,.03,.01,.006,.004,0};
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        PassiveNetwork.Reservoir tank=new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.2,101325));
        var refused=new ArrayList<Boolean>();
        for(int i=0;i<sweep.length;i++) {
            var generator=new PassiveNetwork.Reservoir(1,0,twoPhase(sweep[i],150000),PassiveNetwork.NodeKind.GENERATOR);
            var graph=new PassiveNetwork(List.of(generator,tank),List.of(pipe(10,0,1,line(2,.03),new FlowControl.Pump(.0005,500000))));
            solver.replayStart(graph,committed);
            var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;tank=result.graph().reservoirs().get(1);
            boolean off=result.endpointModes().getFirst()==FlowControl.Mode.INLET_WRONG_PHASE;refused.add(off);
            if(off)assertEquals(0.0,result.averageMassFlows()[0]);else assertTrue(result.averageMassFlows()[0]>0,"runs at "+sweep[i]);
        }
        System.out.println("INLET_PHASE_RUN sweep="+Arrays.toString(sweep)+" refused="+refused);
        for(int i=0;i<sweep.length;i++)assertFalse(refused.get(i),"slice "+i+" at "+sweep[i]);
    }

    // ---- Fixture 5: a pump drains a tank's bottom port ---------------------------------------------------------------

    /**
     * Plan fixture 5 (under decision D11 and default A37): a pump draws water from a tank's bottom (LIQUID) port and lifts
     * it into a void 30 kPa above the tank. The tank holds 2.99 slices of the pump's target volume. The pump runs on its
     * target for two slices; the third starts with 0.99 of a slice of water (the draw over the slice is 1 % gas), and within
     * it the port's water runs out and the draw turns to gas; from the fourth slice on the supply is gas, with no held
     * slice. Restated for request 11 (owner decisions D19 and D20): the pump was refused there ({@code INLET_WRONG_PHASE});
     * now it is not, and its law on the gas moves next to nothing: a set-flow pump's relief rise is a head on its supply
     * (owner decision D11, A2), about 0.7 kPa on the tank's nitrogen against the void's 30 kPa, and a slice of its
     * displacement of gas would be 0.013 kg.
     */
    @Test void aPumpOnABottomPortRunsThenMeetsGasAndMovesNextToNothing() {
        double target=.002,interval=5;
        // The tank's water volume made exactly 2.99 slices of the target (the flash moves it from the charged volume).
        double charged=2.99*target*interval;var tankState=waterUnderNitrogen(charged,120000);
        for(int i=0;i<3;i++)tankState=waterUnderNitrogen(charged*=2.99*target*interval/TestPhases.waterVolume(tankState),120000);
        var start=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,tankState),
                new PassiveNetwork.Reservoir(2,0,nitrogenAt(150000),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(10,0,1,line(5,.025),new FlowControl.Pump(target,500000),PhasePort.LIQUID,PhasePort.BULK)));
        var run=drive("bottom drain",start,interval,8);
        var modes=new ArrayList<String>();var drawn=new ArrayList<Double>();
        for(var s:run.slices()){modes.add(String.valueOf(s.endpointModes().getFirst()));drawn.add(s.averageMassFlows()[0]*interval);}
        var tank=run.last().reservoirs().getFirst().state();
        System.out.println("INLET_PHASE_RUN fixture5 modes="+modes+" drawnKg="+drawn+" tankWater="+TestPhases.waterLiquid(tank)*mw[water]+"kg accepted="+run.accepted()+" rejected="+run.rejected()+" reasons="+run.reasons());
        assertEquals(FlowControl.Mode.MOVER_LAW,run.slices().get(0).endpointModes().getFirst());
        assertEquals(FlowControl.Mode.MOVER_LAW,run.slices().get(1).endpointModes().getFirst());
        for(int i=0;i<2;i++)assertEquals(target*interval*997,drawn.get(i),.01*target*interval*997,"slice "+i+" on target");
        for(int i=2;i<8;i++){assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,run.slices().get(i).endpointModes().getFirst(),"not refused: "+modes);
            if(i>=3)assertTrue(drawn.get(i)<.01*target*interval*997,"slice "+i+" draws next to nothing: "+drawn);}
        assertTrue(TestPhases.waterVolume(tank)<1e-6,"the port drew the water");
    }

    // ---- Fixture 8: certified replay with a pump on a gassy supply -----------------------------------------------------

    /**
     * Plan fixture 8: a pump on a vessel whose gas cushion a water generator compresses, so its supply's vapour share falls
     * from 3 %. A fresh solver started from the committed interval of slice k ({@link PassiveIntervalSolver#replayStart})
     * reproduces the island that solved every interval bit for bit. Restated for request 11 (owner decisions D19 and D20):
     * the pump was refused throughout, and in D6's hysteresis band (0.5 % to 2 %) only the committed mode kept it refused,
     * which a forgetful solver showed; gas at a pump is no refusal now, so the pump runs and the replay is of a running pump.
     */
    @Test void aFreshSolverReplaysAPumpOnAGassySupplyBitwise() {
        var vessel=new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.97,101325));
        var start=new PassiveNetwork(List.of(vessel,new PassiveNetwork.Reservoir(2,0,waterSupply(600000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(10,1,0,line(10,.01),new FlowControl.Passive()),pipe(11,0,2,line(2,.02),new FlowControl.Pump(.0001,500000))));
        var reference=drive("replay",start,5,12);
        var shares=new ArrayList<Double>();for(var g:reference.graphs())shares.add(share(g.reservoirs().getFirst().state()));
        System.out.println("INLET_PHASE_RUN fixture8 shares="+shares+" modes="+reference.slices().stream().map(s->s.endpointModes().get(1)).toList()+" accepted="+reference.accepted()+" rejected="+reference.rejected());
        for(int i=0;i<reference.slices().size();i++)
            assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,reference.slices().get(i).endpointModes().get(1),"never refused, slice "+i);
        for(int resume:new int[]{1,6}) {
            var resumed=drive("replay-from-"+resume,reference.graphs().get(resume-1),5,12-resume,new PassiveIntervalSolver(model),reference.slices().get(resume-1));
            for(int i=0;i<resumed.slices().size();i++)assertSameDoubles("resume "+resume+" slice "+(resume+i),reference.slices().get(resume+i),resumed.slices().get(i),3,2);
            for(int i=0;i<resumed.slices().size();i++)assertEquals(reference.slices().get(resume+i).endpointModes(),resumed.slices().get(i).endpointModes());
        }
    }
}
