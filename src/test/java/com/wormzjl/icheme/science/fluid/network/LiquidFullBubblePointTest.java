package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * P6 (batch 2026-09-24-coolprop-low-temperature): a liquid-full vessel of a mixture cooled through its bubble point.
 * Before P6 every mixture tested failed there (P5 section 6.3: "Phase/device active-set cycle" or "Newton iteration limit
 * ... active-set pass", about 500 accepted and 530 rejected substeps, no partial commit), while pure methane passed: the
 * outer check seeded the two-phase layout with the TP split at the single phase's overshot (T, P), several vessel volumes
 * of vapour a rigid vessel cannot hold, and the two-phase Newton's last iterate (a trace of vapour) was one phase again,
 * repeating the first pass's active set. On main the outer phase check of owner decisions D25 and D26 answers it
 * ({@link FluidThermodynamics#phaseCheck}): the vapour enters the liquid-full vessel at the vessel's own split, its closure
 * of what it holds ({@link InventoryEquilibrium#split}: the small vapour of its energy and volume beside the liquid it
 * keeps), and no TP verdict takes a phase away. Each vessel: 1 L
 * charged liquid-full at 5 MPa, cooled by -200 W through the network's energy input (a 1e-12 mol/s methane trace), 5 s
 * intervals; the rigid liquid's pressure falls about 0.9 to 1 MPa per interval and crosses the bubble pressure (about
 * 2.0 MPa at 166 K with 2 % C2H6 or CO2, 1.4 MPa at 154 K with 2 % N2) in the fourth.
 */
class LiquidFullBubblePointTest {
    static final String PILOT="icheme:pilot_cryogenic";
    private static final MaterialCatalog CATALOG=com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
    private final FluidThermodynamics model=FluidTestSupport.model(CATALOG,PILOT);

    private FluidThermodynamics.State charge(double t,double p,double[] z,double volume) {
        var unit=model.flashTP(t,p,z,()->{});
        double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=volume/unit.volume();
        return model.flashTP(t,p,n,()->{});
    }
    private PassiveIntervalSolver.Result cool(PassiveNetwork graph,double watts) {
        double[] rate=new double[5];rate[1]=1e-12;
        return new PassiveIntervalSolver(model).solve(new PassiveNetwork(graph.reservoirs(),graph.pipes(),List.of(new ScheduledTransfer.Injection(100,0,rate,watts))),
                5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
    }

    /** Cools the vessel for {@code intervals} intervals; every interval must commit; returns the last graph. */
    private PassiveNetwork crossBubblePoint(String name,double[] z,double t,int intervals) {
        var start=charge(t,5e6,z,1e-3);
        assertNotNull(TestPhases.liquidProperties(start));assertNull(TestPhases.vaporProperties(start),name+": liquid-full");
        PassiveNetwork graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,start)),List.of());
        double[] initial=graph.reservoirs().getFirst().inventory().moles();
        int firstVapour=-1;
        for(int k=0;k<intervals;k++) {
            var result=cool(graph,-200);
            graph=new PassiveNetwork(result.graph().reservoirs(),result.graph().pipes());
            var state=graph.reservoirs().getFirst().state();
            System.out.printf(Locale.ROOT,"%s interval %d: %.6f K %.1f Pa, vapour %.3e mol, substeps %d rejected %d%n",name,k+1,state.temperature(),state.pressure(),
                    Arrays.stream(TestPhases.vapor(state)).sum(),result.acceptedSubsteps(),result.rejectedSubsteps());
            if(firstVapour<0&&TestPhases.vaporProperties(state)!=null)firstVapour=k+1;
            assertTrue(result.acceptedSubsteps()<=12,name+": a handful of substeps, not the pre-P6 refinement to nothing");
        }
        assertTrue(firstVapour>1,name+": the vessel crossed its bubble point inside the run (first vapour in interval "+firstVapour+")");
        double[] last=graph.reservoirs().getFirst().inventory().moles();
        for(int c=0;c<last.length;c++)if(c!=1)assertEquals(initial[c],last[c],1e-12*Math.max(1,initial[c]),name+": component "+c+" conserved");
        assertEquals(initial[1]+intervals*5e-12,last[1],1e-12*initial[1],name+": methane plus its trace carrier");
        return graph;
    }

    @Test void aLiquidFullMethaneEthaneVesselCrossesItsBubblePointAndMatchesTheEnginesUvAnswer() {
        var graph=crossBubblePoint("CH4 + 2 % C2H6",new double[]{0,.98,.02,0,0},172,6);
        var node=graph.reservoirs().getFirst();var inventory=node.inventory();
        // The engine's own UV answer for the vessel's inventory (an independent service, fluid only): the same split.
        var engine=FluidTpEquilibrium.forPackage(CATALOG,PILOT);
        double[] totals=inventory.moles();double energy=inventory.internalEnergy();
        var formation=model.hydrocarbon.spine().orElseThrow().formationReference();
        for(int i=0;i<totals.length-1;i++)energy+=totals[i]*formation.offsetJoulesPerMole(i);
        var uv=engine.uv(EquilibriumRequest.uv(energy,inventory.volume(),engine.contract().components(),java.util.Arrays.copyOf(totals,totals.length-1),PhaseCompetition.FLUID_ONLY),engine.newWorkspace());
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID,uv.classification(),uv.toString());
        double t=uv.phases().getFirst().temperature(),p=uv.phases().getFirst().pressure();
        assertEquals(t,node.state().temperature(),1e-9*t,"temperature");
        assertEquals(p,node.state().pressure(),1e-9*p,"pressure");
        double vapour=0;for(var phase:uv.phases())if(phase.kind()==com.wormzjl.icheme.science.thermo.phase.PhaseKind.VAPOR)vapour=phase.total();
        assertEquals(vapour,Arrays.stream(TestPhases.vapor(node.state())).sum(),1e-9*Arrays.stream(totals).sum(),"vapour amount");
    }

    @Test void aLiquidFullMethaneNitrogenVesselCrossesItsBubblePoint() {
        crossBubblePoint("CH4 + 2 % N2",new double[]{.02,.98,0,0,0},160,6);
    }

    /** Under the pilot's crystal competition (CO2 below its triple point in the liquid, above its liquidus): the CO2 stays
     * in the fluid, conserved (main's network carries no crystal inventory). */
    @Test void aLiquidFullMethaneVesselWithDissolvedCo2CrossesItsBubblePointUnderTheCrystalCompetition() {
        crossBubblePoint("CH4 + 0.5 % CO2",new double[]{0,.995,0,.005,0},172,6);
    }
}
