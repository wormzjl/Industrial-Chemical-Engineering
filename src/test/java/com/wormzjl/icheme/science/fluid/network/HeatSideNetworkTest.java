package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A heat-exchanging side in the step solver (batch 2026-10-27-heat-exchanger-design, work package W1): the heat enters
 * the receiver's energy row like a mover's work, the reconstruction books it as a HEAT boundary at the connection, and the
 * solver's own energy audit closes over it. */
class HeatSideNetworkTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PipeResistance.Geometry run=new PipeResistance.Geometry(10,.05,.000045,0);

    private FluidThermodynamics.State water(double temperature,double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(temperature,pressure,n,()->{}).volume();
        return model.flashTP(temperature,pressure,n,()->{});
    }
    /** Water from a generator through {@code side} into a junction, then a plain run into a void at {@code sink} Pa. */
    private PassiveNetwork line(FluidThermodynamics.State source,FluidThermodynamics.State sink,HeatSide side) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(5,0,1,run).withHeat(side),new PassiveNetwork.Pipe(6,1,2,run)));
    }
    private static double heat(PassiveStepSolver.Result result) {
        double sum=0;
        for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT){assertEquals(5,b.nodeId());sum+=b.totalEnergyJoule();}
        return sum;
    }

    @Test void aCooledSideBooksTheMarchOfItsDonorsCurveAndTheLedgerCloses() {
        var source=water(350,300_000);
        var side=new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300,300,300,300,300,300,300,300},0,0);
        double dt=.1;
        var result=new PassiveStepSolver(model).solve(line(source,water(300,101_325),side),dt,()->{});
        double flow=result.massFlows()[0];
        assertTrue(flow>0);
        var curve=HeatCurve.sample(model,PhaseLayout.totalAmounts(source),source.pressure(),290,360,HeatCurve.Films.DEFAULT,()->{});
        double expected=dt*side.heat(curve,source.enthalpy()/source.mass(),flow);
        assertTrue(expected<0,"cooled");
        assertEquals(expected,heat(result),1e-9*Math.abs(expected));
        // Liquid film 2000 W/m2K on 2 m2 against the stream's capacity: the outlet stands where the exponential puts it.
        double out=curve.temperatureAt(source.enthalpy()/source.mass()+expected/(dt*flow));
        double cp=(curve.enthalpy(curve.knots()-1)-curve.enthalpy(0))/(curve.temperature(curve.knots()-1)-curve.temperature(0));
        assertEquals(300+50*Math.exp(-2000*2/(flow*cp)),out,.05);
    }
    /** Liquid water at 10 bar and 420 K, 33 K below its saturation, heated on 22 m2 of wall at 540 K: the junction it reaches
     * boils. Over a 2 s step the junction's seed is laid out from the heated stream, and the step converges onto a two-phase
     * junction at saturation (452.9 K at 9.97 bar; the steam leaving through the plain run cuts the flow from 13.4 to 2.47
     * kg/s). Seeded from the unheated stream, the step ended on a liquid junction at 470.8 K, 22 K above its saturation.
     * Unheated, the junction (about 9 bar, saturation 449 K) stays liquid. The step's Newton starts from the liquid's
     * 13.4 kg/s whatever the seed, and near this point it stalls on the vapour's bound at some walls and areas (20 m2 at
     * 520 K with the virial steam since 2026-10-28, 16 to 18 m2 at 520 K with the ideal one): this point converges under
     * both, and with the virial so do 20 and 24 m2 at 540 K and 22 m2 at 520 K (WATER_STEAM_VIRIAL_REVIEW.md). */
    @Test void aJunctionTheHeatBoilsIsSeededWithItsVapour() {
        var source=water(420,1_000_000);
        var plain=new PassiveStepSolver(model).solve(line(source,water(300,800_000),new HeatSide(22,HeatCurve.Films.DEFAULT,new double[]{540},1,0)),.1,()->{});
        assertEquals(0,model.vaporMass(plain.states().get(1)),1e-12,"bypassed: the junction stays liquid");
        var result=new PassiveStepSolver(model).solve(line(source,water(300,800_000),new HeatSide(22,HeatCurve.Films.DEFAULT,new double[]{540},0,0)),2,()->{});
        assertTrue(heat(result)>0);
        var junction=result.states().get(1);
        assertTrue(model.vaporMass(junction)>0,"the junction boils");
        double[] water=new double[model.componentCount()];water[water.length-1]=1;
        var saturation=HeatCurve.sample(model,water,junction.pressure(),junction.temperature()-5,junction.temperature()+5,HeatCurve.Films.DEFAULT,()->{});
        double middle=Double.NaN;
        for(int k=0;k<saturation.knots()-1;k++)if(saturation.film(k)==5000&&saturation.film(k+1)==5000)middle=(saturation.enthalpy(k)+saturation.enthalpy(k+1))/2;
        assertEquals(saturation.temperatureAt(middle),junction.temperature(),.05);
    }
    /** A connection laid the other way round carries the same stream backwards: its side passes the cells from the last to
     * the first, so a rising wall profile reversed is the falling one passed forwards. */
    @Test void aReversedSidePassesItsCellsInTheOtherOrder() {
        var source=water(350,300_000);var sink=water(300,101_325);
        double[] rising={300,305,310,315,320,325,330,335},falling={335,330,325,320,315,310,305,300};
        var side=new HeatSide(2,HeatCurve.Films.DEFAULT,rising,0,0);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID));
        var reversed=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(5,1,0,run).withHeat(side),new PassiveNetwork.Pipe(6,1,2,run))),.1,()->{});
        var forward=new PassiveStepSolver(model).solve(line(source,sink,side.withInputs(falling,0,0)),.1,()->{});
        assertTrue(reversed.massFlows()[0]<0);
        assertEquals(-forward.massFlows()[0],reversed.massFlows()[0],1e-9*forward.massFlows()[0]);
        assertTrue(heat(forward)<0);
        assertEquals(heat(forward),heat(reversed),1e-9*Math.abs(heat(forward)));
    }
    @Test void withoutAFlowOrWithTheWholeFlowBypassedNothingCrossesTheWall() {
        var source=water(350,300_000);
        var shut=new PassiveStepSolver(model).solve(line(source,water(350,300_000),new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300},0,0)),.1,()->{});
        assertEquals(0,shut.massFlows()[0],1e-9);
        assertEquals(0,heat(shut),1e-9);
        var bypassed=new PassiveStepSolver(model).solve(line(source,water(300,101_325),new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300},1,0)),.1,()->{});
        assertTrue(bypassed.massFlows()[0]>0);
        assertEquals(0,heat(bypassed),1e-9);
    }
}
