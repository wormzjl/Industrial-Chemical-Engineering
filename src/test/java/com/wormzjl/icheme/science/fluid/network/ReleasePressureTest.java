package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared release rule (W3B_NETWORK_PLAN.md sections 17 and 44, {@link ReleasePressure}): the search's own answers, and
 * the process simulator's readings of it on its TP flash beside the world's on its constant-enthalpy equilibrium.
 */
final class ReleasePressureTest {
    private static final double NONE=Double.POSITIVE_INFINITY;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final FlowThermo sheet=new FlowThermo(model,MaterialCatalog.bundled(),FluidMaterialCatalog.NETWORK_PACKAGE,()->{});
    private final PassiveStepSolver world=new PassiveStepSolver(model);

    /** A liquid that boils under 80: its vapour share rises from 0 there to 1 at no pressure. */
    @Test void theSearchGivesTheLowestPressureThatHoldsTheShare() {
        DoubleUnaryOperator boils=p->p>=80?0:(80-p)/80;
        assertEquals(80,ReleasePressure.lowest(boils,p->0,1,1000,1e-6),80*2e-6,"no vapour: the bubble pressure, from above");
        assertTrue(ReleasePressure.lowest(boils,p->0,1,1000,1e-6)>=80,"the high end: held there it holds no vapour");
        assertEquals(40,ReleasePressure.lowest(boils,p->.5,1,1000,1e-6),40*2e-6,"half vapour");
        assertEquals(50,ReleasePressure.lowest(boils,p->0,1,50,1e-6),0,"more than the share at the top: the top");
        assertEquals(NONE,ReleasePressure.lowest(p->0,p->0,1,1000,1e-6),0,"it passes at the floor: no such pressure");
        assertEquals(NONE,ReleasePressure.lowest(p->Double.NaN,p->0,1,1000,1e-6),0,"the top trial says nothing");
        assertEquals(NONE,ReleasePressure.lowest(p->p>=80?0:Double.NaN,p->0,1,1000,1e-6),0,"no trial stated a vapour");
        assertEquals(NONE,ReleasePressure.lowest(boils,p->0,10,10,1e-6),0,"an empty bracket");
    }

    /** The liquid slot ({@code oil}) or the aqueous slot of SheetSizingTest's drum: wet crude with nitrogen at 340 K and 3 bar. */
    private FluidThermodynamics.State drumLiquid(boolean oil) {
        int n=model.componentCount(),water=n-1;
        double[] feed=Arrays.copyOf(TiaJuanaTestCatalog.thermo(FluidMaterialCatalog.NETWORK_PACKAGE).crudeFeed(TiaJuanaTestCatalog.ASSAY_ID).moleFractions(),n);
        feed[water]=0;double dry=Arrays.stream(feed).sum();
        for(int i=0;i<n;i++)feed[i]*=40/dry;
        feed[water]=12;feed[MaterialTestBasis.NITROGEN]=4;
        var drum=model.flashTP(340,3e5,feed,()->{});
        return model.flashTP(340,3e5,(oil?drum.liquidAmountsView():drum.aqueousAmountsView()).clone(),()->{});
    }
    private FluidThermodynamics.State pure(String compound,double temperature) {
        double[] one=new double[model.componentCount()];one[model.components().indexOf(compound)]=1;
        return model.flashTP(temperature,1.0005*sheet.bubblePressure(temperature,one),one,()->{});
    }
    /** Water that stood under nitrogen at 1 atm and 298.15 K, with what it dissolved. */
    private FluidThermodynamics.State nitrogenWater() {
        double[] wet=new double[model.componentCount()];wet[wet.length-1]=10;wet[MaterialTestBasis.NITROGEN]=1;
        var saturated=model.flashTP(298.15,PhaseLayout.AMBIENT_PRESSURE,wet,()->{});
        double[] liquid=saturated.liquidAmounts();for(int i=0;i<liquid.length;i++)liquid[i]+=saturated.aqueousAmountsView()[i];
        return model.flashTP(298.15,1.0005*PhaseLayout.AMBIENT_PRESSURE,liquid,()->{});
    }
    private void agree(String stream,FluidThermodynamics.State s,double edgeTolerance) {
        double[] amounts=PhaseLayout.totalAmounts(s);
        double worldVapour=world.release(s,()->{}),worldEdge=world.zoneEdge(InletPhase.of(model,s,null),()->{});
        double vapour=sheet.vapourPressure(s.temperature(),s.pressure(),amounts),edge=sheet.zoneEdge(s.temperature(),s.pressure(),amounts);
        assertTrue(worldVapour<NONE&&worldEdge<NONE,stream+": the world reads both, p_v "+worldVapour+" E "+worldEdge);
        assertEquals(worldVapour,vapour,.01*worldVapour,stream+": p_v");
        assertEquals(worldEdge,edge,edgeTolerance*worldEdge,stream+": E");
    }
    /**
     * The sheet reads p_v and E on a flash at the stream's temperature, the world on an equilibrium at its enthalpy
     * (16 TP flashes against 26 PH equilibria): on the two liquids of a 3 bar drum, on propane at its bubble point and on
     * water saturated with nitrogen the two agree to 1 %. One compound alone boils as a whole at one temperature, so the
     * sheet's E is its p_v; at its enthalpy the clean share takes 1.3 % of its pressure.
     */
    @Test void theSheetsReadingsStandWithinOnePerCentOfTheWorlds() {
        agree("the drum's water",drumLiquid(false),.01);
        agree("the drum's oil",drumLiquid(true),.01);
        agree("propane",pure("Propane",300),.015);
        agree("nitrogen-saturated water",nitrogenWater(),.01);
    }
}
