package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Trace phases are folded in the flash (owner decisions D33, D34; PHASE_PORTS_REVIEW.md "Group C: trace truncation in the
 * flash"): a fluid phase below {@link FluidThermodynamics#PHASE_TRACE_FRACTION} of its node's moles does not exist and its
 * material is in the node's other phase, nothing leaving and nothing booked. The rule itself, then two of the smallest
 * fuzz worlds it clears: before it, the 1e-12 crude entry trace a nitrogen vessel or junction is seeded with condensed into
 * a hydrocarbon liquid of a few 1e-12 of the node, a phase whose split the Newton cannot resolve.
 */
class TracePhaseFoldTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final int WATER=MaterialTestBasis.NETWORK,NITROGEN=MaterialTestBasis.NITROGEN;
    private static double[] crude(int components) {
        return Arrays.copyOf(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane_nitrogen").crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions(),components);
    }
    private static double sum(double[] values){double total=0;for(double value:values)total+=value;return total;}
    private static double[] totals(FluidThermodynamics.State s) {
        double[] n=new double[s.componentCount()];for(int c=0;c<n.length-1;c++)n[c]=s.liquidView()[c]+s.vaporView()[c];n[n.length-1]=s.waterLiquid()+s.waterVapor();return n;
    }

    /**
     * Humid nitrogen at its dew point with a condensate of 1e-12 of its moles (a drained tank's last film): the flash puts
     * the water in the gas; every component amount is unchanged. The same node with a condensate of 1e-6 keeps it. A
     * nitrogen bubble in a node of free water only has no phase to go to (the model's free water holds no dissolved gas):
     * it is a carried gas (decision D41), a dry gas at the node's pressure whose water vapour is in the free water, so it has
     * no saturation row; a bubble of 1e-6 of the node is a gas phase with its vapour. A hydrocarbon liquid trace in a gas
     * of water vapour alone stays.
     */
    @Test void theFlashFoldsATracePhaseIntoTheNodesOtherPhase() {
        int n=model.componentCount();
        double[] humid=new double[n];humid[NITROGEN]=40;humid[WATER]=2;
        double saturated=model.flashTP(298.15,101325,humid,()->{}).waterVapor();
        humid[WATER]=saturated+1e-12*41;
        var split=model.equilibriumTP(298.15,101325,humid,()->{},null);
        assertTrue(split.waterLiquid()>0&&split.waterLiquid()<FluidThermodynamics.PHASE_TRACE_FRACTION*sum(totals(split)),"the equilibrium holds a trace condensate: "+split.waterLiquid());
        var flash=model.flashTP(298.15,101325,humid,()->{});
        assertEquals(0,flash.waterLiquid(),0,"the flash folds the condensate into the gas");
        assertArrayEquals(totals(split),totals(flash),"nothing leaves the node");
        humid[WATER]=saturated+1e-6*41;
        assertTrue(model.flashTP(298.15,101325,humid,()->{}).waterLiquid()>0,"a condensate of 1e-6 of the node is a phase");

        double[] wet=new double[n];wet[WATER]=55;wet[NITROGEN]=55*1e-12;
        var bubble=model.flashTP(298.15,101325,wet,()->{});
        assertTrue(bubble.vaporVolume()>0&&bubble.vaporView()[NITROGEN]>0&&bubble.waterLiquid()>0,"a nitrogen bubble in free water has no receiving phase and is carried");
        assertEquals(0,bubble.waterVapor(),0,"the carried bubble holds no water vapour");
        assertEquals(101325,bubble.hydrocarbonPartialPressure(),0,"and is at the node's pressure");
        assertTrue(FluidThermodynamics.omittedTrace(bubble,true),"a junction's omitted trace");
        assertFalse(FluidThermodynamics.omittedTrace(bubble,false),"a vessel's wall resolves its 1.3e-12 m3");
        assertArrayEquals(totals(model.equilibriumTP(298.15,101325,wet,()->{},null)),totals(bubble),"nothing leaves the node");
        wet[NITROGEN]=55*1e-6;
        var gas=model.flashTP(298.15,101325,wet,()->{});
        assertTrue(gas.waterVapor()>0,"a gas of 1e-6 of the node is a phase with its vapour");
        var carried=model.folded(gas,true);
        assertEquals(0,carried.waterVapor(),0,"in a junction whose water fills more of it than the gas, the gas is carried, dry");
        assertEquals(101325,carried.hydrocarbonPartialPressure(),0,"at the junction's pressure");
        assertArrayEquals(totals(gas),totals(carried),"nothing leaves the junction");
        assertSame(gas,model.folded(gas,false),"a vessel's gas keeps its saturation: gravity gives it a gas space");

        var liquid=new double[n-1];var vapor=new double[n-1];liquid[0]=1e-12;double[] water={0,1};
        assertFalse(model.foldTracePhases(liquid,vapor,water),"a gas of water vapour alone does not receive a hydrocarbon liquid");

        double[] tank=crude(n);for(int c=0;c<n;c++)tank[c]*=41*1e-12;tank[NITROGEN]+=41;
        assertTrue(model.equilibriumTP(298.15,101325,tank,()->{},null).liquidVolume()>0,"a 1e-12 crude entry trace condenses in the equilibrium");
        assertEquals(0,model.flashTP(298.15,101325,tank,()->{}).liquidVolume(),0,"and the flash puts it in the gas");
    }

    /**
     * The smallest world of random fuzz seed 156 (hold class E5): a 349 kPa two-phase crude generator at 354 K feeds tank A
     * from below through a 0.01 m pipe; tank A's top runs through two 0.1 m pipe blocks into the side of tank B; both tanks
     * 1 m3 of nitrogen at 1 atm, every block placed in one tick. Crude flows into tank A (about 0.8 kg/s at 250 kPa across
     * one metre of 10 mm pipe, estimate), its light ends flash, and the gas it displaces is pushed into tank B, so both tanks
     * rise towards the generator's pressure less a metre of crude. Before the fold, tank B's seed held the crude entry
     * trace as a hydrocarbon liquid of 3.8e-12 of its moles and the first step stalled at every step size.
     */
    @Test void crudeIntoTwoNitrogenTanksKeepsUp() {
        int components=FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition().length;var passive=new FlowControl.Passive();
        var w=new EventPathWorld();w.runTo(397);
        w.place(0,0,2,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,353.5195514436079,348651.4015822029,crude(components)),.01);
        long b=w.place(0,3,0,Kind.RESERVOIR,NORTH,.02);w.place(0,0,1,Kind.PIPE,NORTH,.01);w.place(0,2,1,Kind.PIPE,NORTH,.1);
        long a=w.place(0,1,1,Kind.RESERVOIR,NORTH,.1);w.place(0,3,1,Kind.PIPE,NORTH,.1);
        w.runTo(4400);w.assertKeptUp("crude into two nitrogen tanks");
        var graph=w.coordinator.snapshots().getFirst().graph();double pa=pressure(graph,a),pb=pressure(graph,b);
        var tankA=graph.reservoirs().stream().filter(n->n.id()==a).findFirst().orElseThrow();
        System.out.println("TRACE_FOLD_E5 tankA P="+pa+" mass="+(tankA.empty()?0:tankA.state().mass())+" tankB P="+pb);
        // Decision D54: the tanks are placed empty, bags that fill at the ambient; tank A shows its filling by what it holds,
        // not by its pressure, and holds no gas to push into tank B.
        assertTrue(!tankA.empty()&&pa>=101325*(1-1e-9)&&pa<348651,"tank A fills from the generator: "+pa);
    }

    /**
     * The smallest world of random fuzz seed 762 (hold class E4): a 221 kPa two-phase crude generator at 325 K and a 317 kPa
     * nitrogen generator at 353 K feed a network of pipe junctions that drains into a 1 atm void, every block placed in one
     * tick. Both generators flow to the void for good. Before the fold, a junction whose nitrogen carried the crude's entry
     * trace held it as a hydrocarbon liquid of 3.7e-11 of its moles, and at tick 6959 the reconstruction's equation gate
     * refused every step size at 2.5e-7.
     */
    @Test void crudeAndNitrogenIntoAVoidKeepUp() {
        int components=FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition().length;var passive=new FlowControl.Passive();
        var w=new EventPathWorld();w.runTo(132);
        w.place(2,-1,-1,Kind.PIPE,NORTH,.05);w.place(1,-1,-1,Kind.PIPE,NORTH,.05);
        w.place(0,-1,-2,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,325.34769770654407,220708.87923773366,crude(components)),.01);
        w.place(2,1,2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(NITROGEN,352.55471397885265,316661.6882836341),.02);
        w.place(2,1,-1,Kind.PIPE,NORTH,.05);w.place(0,-1,0,Kind.PIPE,NORTH,.05);w.place(0,-1,-1,Kind.PIPE,NORTH,.01);w.place(2,0,-1,Kind.PIPE,NORTH,.05);
        w.place(0,0,0,Kind.VOID,NORTH,.02);w.place(2,1,0,Kind.PIPE,NORTH,.02);w.place(2,1,1,Kind.PIPE,NORTH,.02);
        w.runTo(8000);w.assertKeptUp("crude and nitrogen into a void");
    }
    /**
     * Decision D41 for vessels (seed-236 trace, 2026-10-27): crude in a water-full vessel, below what its wall resolves,
     * has no phase of its own in a step layout. 1e-21 mol of it in a 1 m3 tank at 350 K lays out the water, temperature
     * and pressure only and decodes no hydrocarbon, also where the flash holds it in two trace phases (150 kPa). A vessel
     * omits a trace only while its wall cannot see the trace's volume. A junction omits only a carried dry gas: its liquid
     * trace is what the vessels after it receive and resolve.
     */
    @Test void aCrudeTraceInAWaterFullVesselHasNoPhaseOfItsOwn() {
        int n=model.componentCount();boolean[] mask=new boolean[n-1];Arrays.fill(mask,true);
        double[] amounts=crude(n);double crude=sum(amounts);for(int c=0;c<n;c++)amounts[c]*=1e-21/crude;amounts[WATER]=54057;
        for(double pressure:new double[]{374220,150000}) {
            var tank=model.flashTP(350,pressure,amounts,()->{});
            assertTrue(tank.liquidVolume()+tank.vaporVolume()>0&&tank.waterLiquid()>0&&tank.waterVapor()==0,"a crude trace in free water at "+pressure);
            assertTrue(FluidThermodynamics.omittedTrace(tank,false),"a vessel's omitted trace at "+pressure);
            assertFalse(FluidThermodynamics.omittedTrace(tank,true),"a junction lays its liquid trace out at "+pressure);
            var support=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.support(model,tank,mask,null,false);
            for(var s:support)assertEquals(com.wormzjl.icheme.science.thermo.PhaseSupport.ABSENT,s,"no hydrocarbon is laid out at "+pressure);
            var layout=new com.wormzjl.icheme.science.fluid.solver.PhaseLayout(model,tank,mask,null,support,false);
            assertEquals(3,layout.size(),"free water, temperature and pressure");
            double[] x=layout.encode(tank);var decoded=layout.decode(x,0);
            assertEquals(0,decoded.liquidVolume()+decoded.vaporVolume(),0,"the layout decodes no hydrocarbon");
            double[] rows=new double[layout.size()];
            layout.residual(decoded,totals(tank),tank.internalEnergy(),tank.volume(),Double.NaN,rows,0,x);
            for(double row:rows)assertTrue(Math.abs(row)<1e-12,"the omitted trace is below every row: "+Arrays.toString(rows));
        }
        if(model.flashTP(350,150000,amounts,()->{}).vaporVolume()==0)fail("the 150 kPa trace was meant to hold two trace phases");
        double[] resolved=amounts.clone();for(int c=0;c<n-1;c++)resolved[c]*=1e15;
        var film=model.flashTP(350,374220,resolved,()->{});
        assertTrue(film.liquidVolume()>1e-15*film.volume(),"1e-6 mol of crude: "+film.liquidVolume()+" m3");
        assertFalse(FluidThermodynamics.omittedTrace(film,false),"a vessel's wall sees it, below the trace share as it is");
    }

    /**
     * The smallest world of bundled fuzz seed 236 (hold class E5, SEED236_TRACE_REVIEW.md): a 346 kPa water generator 3 m
     * up feeds a 1 m3 tank through 0.1 m pipe and a junction, which a 332 kPa crude generator also reaches through 0.02 m
     * pipe; the tank drains through its bottom (0.02 m) to a 1 atm void 2 m below, every block placed at tick 153. Water
     * runs through at 6.9 kg/s, and the junction's higher pressure keeps the crude out, so the crude it let in early is a
     * trace: 1e-19 mol per component in the junction, 7e-20 mol in the tank. Before decision D41 was generalised, the tank
     * was seeded with 1e-12 entry traces and laid that trace out as two trace phases: from tick 5580 its Jacobian had no
     * finite trial at 1/32 s and over, its closure no two-phase root below, and the island held.
     */
    @Test void waterThroughATankHoldingACrudeTraceKeepsUp() {
        int components=FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition().length;var passive=new FlowControl.Passive();
        var w=new EventPathWorld();w.runTo(153);
        w.place(0,-2,1,Kind.VOID,NORTH,.02);w.place(-1,1,0,Kind.PIPE,NORTH,.02);
        w.place(0,3,0,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(WATER,350.0194147271819,345788.01020170655),.1);
        w.place(0,1,0,Kind.PIPE,NORTH,.1);w.place(0,-1,1,Kind.PIPE,NORTH,.02);w.place(0,0,0,Kind.PIPE,NORTH,.1);
        w.place(-2,1,0,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,341.103670436814,332476.9998133756,crude(components)),.02);
        w.place(0,0,1,Kind.RESERVOIR,NORTH,.02);w.place(0,2,0,Kind.PIPE,NORTH,.1);
        w.runTo(7000);w.assertKeptUp("water through a tank holding a crude trace");
    }
    private static double pressure(PassiveNetwork graph,long id) {
        return graph.reservoirs().stream().filter(n->n.id()==id).findFirst().orElseThrow().state().pressure();
    }
}
