package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.machine.PlungerPump;
import com.wormzjl.icheme.science.machine.PositiveDisplacement;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A plunger pump on its row in the fluid network (batch 2026-11-15-supercritical-fluids, stage S8, PLUNGER_PUMP_PLAN.md
 * section 6): on carbon dioxide at 310 K it delivers its displacement less the slip (0.4 % per relief rise: 0.16 % at a
 * rise of 10 MPa) at reduced densities 0.6, 0.8, 1.0 and 1.6, where a centrifugal pump's head share is 0.60, 0.99, 1 and 1
 * (S4b). It is not refused, not suction-limited and not vapour-locked there, and the fluid takes {@code Q ΔP}.
 */
class PlungerPumpMoverTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PlungerPump PP30=PlungerPump.of(PlungerPump.model(3));
    private static final double RISE=10e6;

    private double[] co2(){double[] n=new double[model.componentCount()];n[model.components().indexOf("CarbonDioxide")]=1;return n;}
    private FluidThermodynamics.State at(double t,double p,double[] n){return model.flashTP(t,p,n,()->{});}
    private double pressureAt(double t,double[] n,double target) {
        double lo=1e5,hi=8e7;
        for(int i=0;i<200&&hi-lo>1e-13*hi;i++){double mid=(lo+hi)/2;if(at(t,mid,n).reducedDensity()<target)lo=mid;else hi=mid;}
        return (lo+hi)/2;
    }
    /** A vessel at {@code s}, a 10 m suction line, the pump at full speed, a vessel {@link #RISE} higher at 310 K. */
    private record Run(PassiveStepSolver solver,PassiveNetwork graph,PassiveStepSolver.Result result){}
    private Run run(FluidThermodynamics.State s,double[] n) {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,s,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,at(310,s.pressure()+RISE,n),PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.05,.000045,0),new FlowControl.Passive()),
                        new PassiveNetwork.Pipe(11,1,2,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Machine(PP30,1,true))));
        var solver=new PassiveStepSolver(model);return new Run(solver,graph,solver.solve(graph,1,()->{}));
    }

    @Test void itDeliversItsDisplacementLessTheSlipOnDenseCarbonDioxide() {
        double[] co2=co2();
        for(double delta:new double[]{.6,.8,1.0,1.6}) {
            var s=at(310,pressureAt(310,co2,delta),co2);var r=run(s,co2);var result=r.result();
            var suction=result.states().get(1);double rho=suction.mass()/suction.volume(),q=result.massFlows()[1]/rho,head=result.devicePressureChanges()[1];
            double centrifugal=GasEntrainment.headShare(1-FluidThermodynamics.pumpLiquidLikeWeight(s),s.pressure());
            System.out.printf(java.util.Locale.ROOT,"PLUNGER_RUN CO2 310 K delta=%.2f p=%.4f MPa rho=%.1f mode=%s flow=%.5f m3/h share=%.6f rise=%.4f MPa work=%.1f J fluidPower=%.1f W centrifugalHeadShare=%.4f limit=%s locked=%s%n",
                    delta,s.pressure()/1e6,rho,result.modes().get(1),3600*q,q/PP30.displacement(),head/1e6,result.pumpWorkJoule(),PP30.fluidPower(result.massFlows()[1],head,rho,suction.pressure(),1.3),centrifugal,
                    r.solver().suctions.suctionLimitPressure(r.graph(),1,0),r.solver().suctions.vapourLocked(1));
            assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().get(1),"on its law at "+delta);
            assertFalse(r.solver().suctions.vapourLocked(1),"not vapour-locked at "+delta);
            assertTrue(head>RISE&&head<1.01*RISE,"the rise is the system's at "+delta+": "+head);
            assertEquals(PP30.row(1).flow(head,1),q,1e-6*q,"the row's flow at its rise at "+delta);
            assertEquals(PP30.displacement()*(1-PositiveDisplacement.SLIP*head/PlungerPump.RELIEF_RISE),q,5e-5*PP30.displacement(),"the displacement less the slip at "+delta);
            assertEquals(result.massFlows()[1]/rho*head*result.deltaTime(),result.pumpWorkJoule(),1e-6*result.pumpWorkJoule(),"the fluid takes Q dP at "+delta);
        }
    }

    /** Below reduced density 0.5 the fluid is a gas for every pump ({@code pumpLiquidLikeWeight} 0): reported, not asserted
     * beyond that it stays on its law or is refused by the pump rules (an owner item of S8). */
    @Test void onTheGasSideThePumpRulesDecide() {
        double[] co2=co2();
        for(double delta:new double[]{.3,.45}) {
            var s=at(310,pressureAt(310,co2,delta),co2);var r=run(s,co2);var result=r.result();
            var suction=result.states().get(1);double rho=suction.mass()/suction.volume(),q=result.massFlows()[1]/rho;
            System.out.printf(java.util.Locale.ROOT,"PLUNGER_GAS CO2 310 K delta=%.2f p=%.4f MPa rho=%.1f mode=%s flow=%.5f m3/h share=%.6f rise=%.4f MPa limit=%s locked=%s%n",
                    delta,s.pressure()/1e6,rho,result.modes().get(1),3600*q,q/PP30.displacement(),result.devicePressureChanges()[1]/1e6,r.solver().suctions.suctionLimitPressure(r.graph(),1,0),r.solver().suctions.vapourLocked(1));
            assertTrue(Double.isFinite(q),"a finite answer at "+delta);
        }
    }
}
