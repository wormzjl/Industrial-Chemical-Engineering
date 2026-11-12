package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.network.ConservativeTransport.BoundaryTransfer.Kind;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The open vessel of batch 2026-10-21-floating-roof-tanks ({@link OpenVessel}, plan section 3.1, WP1): a floating-roof
 * tank's surface stands at the ambient pressure, its gas vents over the step after it arrives, the liquid above its fill
 * height leaves only through its OVERFLOW port, and the liquid above its shell top spills; vent and spill are rates (a
 * step releases dt / RELEASE_TIME of its start excess). Water carrying nitrogen, nitrogen alone and a stabilised crude
 * here; boiling feeds are in design too (owner decision of 2026-10-21) and are covered by the fuzz campaign. Every run
 * closes the component and energy ledgers over the vessels and the boundary transfers.
 */
class OpenTankTest extends PortFixture {
    private static final double COMPONENT_LEDGER=1e-10,ENERGY_LEDGER=1e-9,G=PassiveStepSolver.GRAVITY,P0=PhaseLayout.AMBIENT_PRESSURE;
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,PhasePort pa,PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(g),new FlowControl.Passive(),0,null,pa,pb);
    }
    /** An open tank of {@code volume} m3 at its fill height {@code height} holding {@code state}. */
    private static PassiveNetwork.Reservoir openTank(long id,double elevation,FluidThermodynamics.State state,double volume,double height) {
        return new PassiveNetwork.Reservoir(id,elevation,state,PassiveNetwork.NodeKind.RESERVOIR,
                new PassiveNetwork.Inventory(volume,PhaseLayout.totalAmounts(state),state.internalEnergy(),SolidInventory.EMPTY,height,true));
    }
    /** {@code cubicMetres} of water at 298.15 K and the ambient pressure. */
    private FluidThermodynamics.State water(double cubicMetres){var n=new double[mw.length];n[water]=cubicMetres*997/mw[water];return model.flashTP(298.15,P0,n,NOOP);}
    /** {@code cubicMetres} of a stabilised crude at 298.15 K and the ambient pressure: the network package's crude cuts from
     * the fifth on, in equal moles (no light ends). */
    private FluidThermodynamics.State stabilisedCrude(double cubicMetres) {
        var n=new double[mw.length];
        for(int c=0;c<n.length;c++)if(model.components().get(c).startsWith("crude_pc")&&Integer.parseInt(model.components().get(c).substring(8))>=5)n[c]=1;
        var unit=model.flashTP(298.15,P0,n,NOOP);double scale=cubicMetres/unit.volume();for(int c=0;c<n.length;c++)n[c]*=scale;
        return model.flashTP(298.15,P0,n,NOOP);
    }

    /** One interval run sliced as the runtime slices it, with the ledgers checked after every slice. */
    private record Run(List<PassiveIntervalSolver.Result> slices,List<PassiveNetwork> graphs) {
        PassiveNetwork last(){return graphs.getLast();}
        double booked(Kind kind,int slice,int component) {
            double sum=0;for(var b:slices.get(slice).boundaries())if(b.kind()==kind)sum+=b.moles()[component];
            return sum;
        }
        boolean any(Kind kind){for(var s:slices)for(var b:s.boundaries())if(b.kind()==kind)return true;return false;}
    }
    private Run drive(String label,PassiveNetwork graph,double interval,int count) {
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0;
        var slices=new ArrayList<PassiveIntervalSolver.Result>();var graphs=new ArrayList<PassiveNetwork>();
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed);
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice+": the whole slice commits");
            for(var b:result.boundaries()){var n=b.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=b.totalEnergyJoule();}
            externalEnergy+=result.pumpWorkJoule();
            var now=owned(graph);
            for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);
                assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
            double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
            assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
            slices.add(result);graphs.add(graph);
        }
        return new Run(slices,graphs);
    }
    private static FluidThermodynamics.State tank(PassiveNetwork graph){return graph.reservoirs().getFirst().state();}

    /**
     * Half a cubic metre of water under half a cubic metre of nitrogen in an open 1 m3 tank: the nitrogen gas vents over
     * the first step, booked as a VENT at the tank, and the water stands at the ambient pressure. The flag survives the
     * solve, and after the slice no gas phase is left beyond what the water's own dissolved nitrogen can form.
     */
    @Test void anOpenTankVentsItsNitrogenAndKeepsItsWaterAtTheAmbient() {
        var start=waterUnderNitrogen(.5,P0);double gas=FluidThermodynamics.phaseMoles(start,FluidThermodynamics.GAS)[nitrogen];
        assertTrue(gas>15,"the start holds nitrogen gas: "+gas+" mol");
        var graph=new PassiveNetwork(List.of(openTank(1,0,start,1,1)),List.of());
        var run=drive("vent",graph,5,3);
        var after=run.last().reservoirs().getFirst();
        assertTrue(after.inventory().open(),"a solve keeps the open flag");
        assertEquals(PhaseLayout.atmosphere(1),after.state().pressure(),1e-6*P0,"the surface stands at the atmosphere of its height (C1 B)");
        double vented=0;for(int s=0;s<run.slices().size();s++)vented-=run.booked(Kind.VENT,s,nitrogen);
        assertTrue(vented>=.99*gas,"the nitrogen gas vents: "+vented+" of "+gas+" mol");
        assertEquals(TestPhases.waterLiquid(start),TestPhases.waterLiquid(after.state())+TestPhases.waterVapor(after.state()),1e-3*TestPhases.waterLiquid(start),"the water stays");
        assertTrue(after.state().vaporVolume()<1e-4,"no gas is held after the slices: "+after.state().vaporVolume()+" m3");
        System.out.println("OPEN_TANK_VENT gas="+gas+" vented="+vented+" left="+after.inventory().moles()[nitrogen]+" vapour="+after.state().vaporVolume());
    }

    /**
     * Water carrying nitrogen fed from a generator at 1.5 bar into the bottom of an open tank: the tank stands at the
     * ambient pressure while it fills, and in the steady slices the nitrogen it vents is what arrives, within a step's lag.
     */
    @Test void aFeedOfWaterAndNitrogenVentsWhatArrives() {
        var n=new double[mw.length];n[water]=50;n[nitrogen]=.5;var feed=model.flashTP(298.15,1.5e5,n,NOOP);
        var nodes=List.of(openTank(1,0,water(2),10,4),new PassiveNetwork.Reservoir(2,0,feed,PassiveNetwork.NodeKind.GENERATOR));
        var graph=new PassiveNetwork(nodes,List.of(pipe(90,1,0,line(5,.025),PhasePort.BULK,PhasePort.LIQUID)));
        var run=drive("feed",graph,5,8);
        for(var g:run.graphs())assertEquals(PhaseLayout.atmosphere(4),tank(g).pressure(),1e-6*P0,"the open tank stays at the atmosphere of its height (C1 B)");
        var last=run.slices().getLast();int slice=run.slices().size()-1;
        var transfer=last.pipeTransfers().stream().filter(t->t.pipeId()==90).findFirst().orElseThrow().forward();
        double arrived=0;for(var p:transfer.phaseMoles())arrived+=p[nitrogen];
        double vented=-run.booked(Kind.VENT,slice,nitrogen);
        assertTrue(arrived>0,"nitrogen arrives");
        assertEquals(arrived,vented,.1*arrived,"the steady vent is the arriving nitrogen");
        System.out.println("OPEN_TANK_FEED arrived="+arrived+" vented="+vented+" level="+OpenVessel.condensedVolume(tank(run.last())));
    }

    /**
     * Owner rule of 2026-10-21 (seed 895): an open tank holding only gas keeps what arrives and vents what it started the
     * step with. With nothing arriving its nitrogen vents over the first step and it ends that step empty, at the ambient.
     */
    @Test void aGasOnlyOpenTankVentsItsGasAndEndsEmpty() {
        var start=nitrogenAt(P0);double held=TestPhases.vapor(start)[nitrogen];
        var run=drive("gas only",new PassiveNetwork(List.of(openTank(1,0,start,1,1)),List.of()),5,3);
        assertEquals(held,-run.booked(Kind.VENT,0,nitrogen),1e-9*held,"the first step vents all it held");
        for(var g:run.graphs())assertTrue(g.reservoirs().getFirst().empty(),"it ends every step empty");
    }

    /**
     * Owner rule of 2026-10-21 (seed 895): nitrogen fed at 1.5 bar into an empty open tank is held over the step it arrives
     * in and vented over the next, so the tank holds about one step's inflow and in the steady slices vents what arrives.
     */
    @Test void aGasOnlyOpenTankHoldsAStepsInflowAndVentsItNext() {
        var nodes=List.of(PassiveNetwork.emptyVessel(1,0,1,1,true,model),new PassiveNetwork.Reservoir(2,0,nitrogenAt(1.5e5),PassiveNetwork.NodeKind.GENERATOR));
        var run=drive("gas fed",new PassiveNetwork(nodes,List.of(pipe(90,1,0,line(5,.025),PhasePort.BULK,PhasePort.BULK))),5,4);
        int slice=run.slices().size()-1;
        var transfer=run.slices().get(slice).pipeTransfers().stream().filter(t->t.pipeId()==90).findFirst().orElseThrow().forward();
        double arrived=0;for(var p:transfer.phaseMoles())arrived+=p[nitrogen];
        double vented=-run.booked(Kind.VENT,slice,nitrogen),kept=run.last().reservoirs().getFirst().inventory().moles()[nitrogen];
        assertTrue(arrived>0&&kept>0,"nitrogen arrives and is held: "+arrived+", "+kept);
        assertEquals(arrived,vented,.1*arrived,"the steady vent is the arriving nitrogen");
        for(var g:run.graphs())assertEquals(PhaseLayout.atmosphere(1),tank(g).pressure(),1e-6*P0,"at the atmosphere of its height (C1 B)");
    }

    /** A stabilised crude (the network package's heavy cuts) in an open tank vents nothing and holds no gas. */
    @Test void aStabilisedCrudeVentsNothing() {
        var crude=stabilisedCrude(.6);assertFalse(FluidThermodynamics.holdsPhase(crude,FluidThermodynamics.GAS),"the crude holds no gas at 298 K and 1 atm");
        var nodes=List.of(openTank(1,0,crude,1,1),new PassiveNetwork.Reservoir(2,-2,nitrogenAt(P0),PassiveNetwork.NodeKind.VOID));
        var run=drive("crude",new PassiveNetwork(nodes,List.of(pipe(90,0,1,line(2,.025),PhasePort.LIQUID,PhasePort.BULK))),5,3);
        assertFalse(run.any(Kind.VENT),"no vent");
        assertFalse(FluidThermodynamics.holdsPhase(tank(run.last()),FluidThermodynamics.GAS),"no gas");
    }

    /**
     * The level head of an open tank over its fill height: {@code rho g h} with {@code h} the liquid's height over the
     * footprint {@code A = V/H}, above the fill height too (an overfilled tank), at its LIQUID and OVERFLOW ports alike.
     */
    @Test void theHeadIsTheLiquidsWeightOverTheFootprint() {
        for(double held:new double[]{.4,1.5}) {
            var tank=openTank(1,0,water(held),2,4);var s=tank.state();
            double rho=model.liquidMass(s)/FluidThermodynamics.liquidStreamVolume(s),h=FluidThermodynamics.liquidStreamVolume(s)/(2/4.);
            assertEquals(rho*G*h,PassiveStepSolver.portHead(model,tank,PhasePort.LIQUID,s),1e-9*rho*G*h);
            assertEquals(rho*G*h,PassiveStepSolver.portHead(model,tank,PhasePort.OVERFLOW,s),1e-9*rho*G*h);
        }
    }

    /**
     * An open tank held above its fill height drains the band through its OVERFLOW port down to the fill height, never
     * below it (the port is dry at the fill height), and the port draws nothing from a tank below its fill height.
     */
    @Test void theOverflowPortDrawsTheBandDownToTheFillHeight() {
        var nodes=List.of(openTank(1,0,water(1.4),1,1),new PassiveNetwork.Reservoir(2,-3,nitrogenAt(P0),PassiveNetwork.NodeKind.VOID));
        var graph=new PassiveNetwork(nodes,List.of(pipe(90,0,1,line(3,.05),PhasePort.OVERFLOW,PhasePort.BULK)));
        var run=drive("overflow",graph,5,12);
        double level=OpenVessel.condensedVolume(tank(run.last()));
        assertTrue(level>=1-1e-9&&level<1.01,"the band drains to the fill height and no further: "+level+" m3");
        assertFalse(run.any(Kind.SPILL),"nothing above the shell top, nothing spills");
        var below=new PassiveNetwork(List.of(openTank(1,0,water(.8),1,1),nodes.get(1)),graph.pipes());
        var still=drive("below",below,5,2);
        assertEquals(.8*997,model.liquidMass(tank(still.last())),1e-6*.8*997,"below the fill height the overflow port carries nothing");
        System.out.println("OPEN_TANK_OVERFLOW level="+level);
    }

    /**
     * An open tank fed with water and with no overflow line fills its band and then spills: the liquid above the shell
     * top leaves as a SPILL, and in the steady slices the spill is the inflow, the level standing within a step's inflow
     * of the shell top.
     */
    @Test void aTankWithoutAnOverflowLineSpillsItsInflowOverTheTop() {
        var feed=waterSupply(1.5e5);
        var nodes=List.of(openTank(1,0,water(1.95),1,1),new PassiveNetwork.Reservoir(2,0,feed,PassiveNetwork.NodeKind.GENERATOR));
        var graph=new PassiveNetwork(nodes,List.of(pipe(90,1,0,line(5,.025),PhasePort.BULK,PhasePort.LIQUID)));
        var run=drive("spill",graph,5,10);
        assertTrue(run.any(Kind.SPILL),"the tank spills");
        int slice=run.slices().size()-1;var last=run.slices().get(slice);
        var transfer=last.pipeTransfers().stream().filter(t->t.pipeId()==90).findFirst().orElseThrow().forward();
        double in=transfer.massKg(),spilled=-run.booked(Kind.SPILL,slice,water)*mw[water];
        assertEquals(in,spilled,.02*in,"the steady spill is the inflow");
        double level=OpenVessel.condensedVolume(tank(run.last()));double top=OpenVessel.topVolume(run.last().reservoirs().getFirst().inventory());
        assertTrue(level>=top-1e-9&&level<top+in/997,"the level stands at the shell top: "+level+" of "+top+" m3");
        assertEquals(PhaseLayout.atmosphere(1),tank(run.last()).pressure(),1e-6*P0,"an open tank is never pressurised (C1 B: the atmosphere of its height)");
        System.out.println("OPEN_TANK_SPILL in="+in+" spilled="+spilled+" level="+level+" top="+top);
    }
}
