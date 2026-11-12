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
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

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
        double[] n=new double[s.componentCount()];for(int c=0;c<n.length-1;c++)n[c]=TestPhases.liquid(s)[c]+TestPhases.vapor(s)[c];n[n.length-1]=TestPhases.waterLiquid(s)+TestPhases.waterVapor(s);return n;
    }

    /**
     * Humid nitrogen at its dew point with a condensate of 1e-9 of its moles (1e-12 before W3b) (a drained tank's last film): the flash puts
     * the water in the gas; every component amount is unchanged. The same node with a condensate of 1e-6 keeps it. A 1e-12
     * crude entry trace in nitrogen condenses in the equilibrium and the flash puts it in the gas. (W3b: the carried gas of
     * decision D41 and the omitted trace were rules of the free water, which holds no dissolved gas; with water a component
     * of the cubic a nitrogen bubble in water dissolves into the aqueous phase, so both rules and their checks went.)
     */
    @Test void theFlashFoldsATracePhaseIntoTheNodesOtherPhase() {
        int n=model.componentCount();
        double[] humid=new double[n];humid[NITROGEN]=40;humid[WATER]=2;
        // W3b: the saturated gas is the flash's own (its water dissolved some of the nitrogen, which moves the gas's dew
        // point), and the condensate 0.99e-9 of the node, still a trace: the cubic's equilibrium resolves one from 1e-9 of the
        // node (exactly the excess) and answers none at 1e-10 and below.
        var wet=model.flashTP(298.15,101325,humid,()->{});
        double saturated=TestPhases.waterVapor(wet);humid[NITROGEN]=TestPhases.vapor(wet)[NITROGEN];
        humid[WATER]=saturated+1e-9*41;
        var split=model.equilibriumTP(298.15,101325,humid,()->{},null);
        assertTrue(TestPhases.waterLiquid(split)>0&&TestPhases.waterLiquid(split)<FluidThermodynamics.PHASE_TRACE_FRACTION*sum(totals(split)),"the equilibrium holds a trace condensate: "+TestPhases.waterLiquid(split));
        var flash=model.flashTP(298.15,101325,humid,()->{});
        assertEquals(0,TestPhases.waterLiquid(flash),0,"the flash folds the condensate into the gas");
        assertArrayEquals(totals(split),totals(flash),"nothing leaves the node");
        humid[WATER]=saturated+1e-6*41;
        assertTrue(TestPhases.waterLiquid(model.flashTP(298.15,101325,humid,()->{}))>0,"a condensate of 1e-6 of the node is a phase");

        double[] tank=crude(n);for(int c=0;c<n;c++)tank[c]*=41*1e-12;tank[NITROGEN]+=41;
        assertTrue(TestPhases.liquidVolume(model.equilibriumTP(298.15,101325,tank,()->{},null))>0,"a 1e-12 crude entry trace condenses in the equilibrium");
        assertEquals(0,TestPhases.liquidVolume(model.flashTP(298.15,101325,tank,()->{})),0,"and the flash puts it in the gas");
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
    // W3b: aCrudeTraceInAWaterFullVesselHasNoPhaseOfItsOwn (decision D41 for vessels: a vessel omits a crude trace its wall
    // cannot see) went with the omitted-trace rule; on the cubic the crude dissolves in the aqueous phase. Its world stays below.

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
