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

    /** The S8 probe on the clearance law (batch 2026-11-16-supercritical-followups, P1): the row delivers the law's flow at
     * its rise on the suction's isentropic exponent, the displacement less the slip and the clearance loss, and the fluid
     * takes the isentropic work. The share before P1 (the slip alone) is printed beside it. */
    @Test void itDeliversItsDisplacementLessTheSlipAndTheClearanceOnDenseCarbonDioxide() {
        double[] co2=co2();
        for(double delta:new double[]{.6,.8,1.0,1.6}) {
            var s=at(310,pressureAt(310,co2,delta),co2);var r=run(s,co2);var result=r.result();
            var suction=result.states().get(1);double rho=suction.mass()/suction.volume(),q=result.massFlows()[1]/rho,head=result.devicePressureChanges()[1];
            // The row reads the exponent of the suction node's state its last pass starts from, the junction's state at the
            // step's end here, as a compressor reads its k (PassiveStepSolver.kappa), and the trial's suction pressure.
            double n=PassiveStepSolver.suctionKappa(model,r.graph().pipes().get(1).control(),suction),p=suction.pressure();
            double slipOnly=1-PositiveDisplacement.SLIP*head/PlungerPump.RELIEF_RISE,etaV=PlungerPump.volumetricEfficiency(head,p,n);
            double centrifugal=GasEntrainment.headShare(1-FluidThermodynamics.pumpLiquidLikeWeight(s),s.pressure());
            System.out.printf(java.util.Locale.ROOT,"PLUNGER_RUN CO2 310 K delta=%.2f p=%.4f MPa rho=%.1f n=%.4f mode=%s flow=%.5f m3/h share=%.6f slipOnly=%.6f etaV=%.6f rise=%.4f MPa work=%.1f J workRatio=%.6f centrifugalHeadShare=%.4f limit=%s locked=%s%n",
                    delta,s.pressure()/1e6,rho,n,result.modes().get(1),3600*q,q/PP30.displacement(),slipOnly,etaV,head/1e6,result.pumpWorkJoule(),PlungerPump.isentropicWork(head,p,n)/head,centrifugal,
                    r.solver().suctions.suctionLimitPressure(r.graph(),1,0),r.solver().suctions.vapourLocked(1));
            assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().get(1),"on its law at "+delta);
            assertFalse(r.solver().suctions.vapourLocked(1),"not vapour-locked at "+delta);
            assertTrue(head>RISE&&head<1.01*RISE,"the rise is the system's at "+delta+": "+head);
            assertEquals(model.isentropicExponent(suction),n,0,"a plunger pump reads the suction's isentropic exponent at "+delta);
            assertEquals(model.isentropicExponent(s),n,2e-3*n,"near the supply's at "+delta);
            assertEquals(PP30.flow(head,1,p,n),q,1e-6*q,"the law's flow at its rise at "+delta);
            assertTrue(q<PP30.displacement()*slipOnly,"the clearance costs delivery at "+delta);
            assertEquals(result.massFlows()[1]/rho*PlungerPump.isentropicWork(head,p,n)*result.deltaTime(),result.pumpWorkJoule(),1e-6*result.pumpWorkJoule(),"the fluid takes Q w_s at "+delta);
            assertTrue(result.pumpWorkJoule()<result.massFlows()[1]/rho*head*result.deltaTime(),"below Q dP at "+delta);
        }
    }

    private double[] pure(String name){double[] n=new double[model.componentCount()];n[model.components().indexOf(name)]=1;return n;}

    /**
     * The six reference rows (FOLLOWUPS_PLAN.md section 3, from PD_PUMP_RESEARCH_REVIEW.md section 2: CoolProp, a clearance
     * of 0.1): the volumetric efficiency and the work over {@code ΔP} on the exponent of the model's own suction state.
     * The bounds are the plan's: at most 2.5 % of the delivery low on carbon dioxide at 310 K from 8.5 to 25 MPa, within
     * 0.5 % on every other row; the work ratios within the bound printed beside them.
     */
    @Test void theClearanceLossAndTheWorkMeetTheReferenceRows() {
        record Row(String name,String fluid,double t,double p,double discharge,double etaV,double lowBound,double work){}
        var rows=List.of(new Row("water 300 K 1 to 21 MPa","Water",300,1e6,21e6,.999,.005,.996),
                new Row("CO2 310 K 10 to 30 MPa","CarbonDioxide",310,10e6,30e6,.983,.005,.912),
                new Row("CO2 310 K 8.5 to 25 MPa","CarbonDioxide",310,8.5e6,25e6,.966,.025,.834),
                new Row("nitrogen 300 K 15 to 30 MPa","Nitrogen",300,15e6,30e6,.955,.005,.815),
                new Row("CO2 gas 300 K 1 to 3 MPa","CarbonDioxide",300,1e6,3e6,.862,.005,.619),
                new Row("nitrogen gas 300 K 0.1 to 0.4 MPa","Nitrogen",300,1e5,4e5,.831,.005,.567));
        for(var row:rows) {
            var s=at(row.t(),row.p(),pure(row.fluid()));double n=model.isentropicExponent(s),rise=row.discharge()-row.p();
            double etaV=PlungerPump.volumetricEfficiency(rise,row.p(),n),work=PlungerPump.isentropicWork(rise,row.p(),n)/rise;
            System.out.printf(java.util.Locale.ROOT,"PLUNGER_REFERENCE %s: n=%.4f etaV=%.4f (reference %.3f, error %+.4f) work=%.4f (reference %.3f, error %+.4f)%n",
                    row.name(),n,etaV,row.etaV(),etaV-row.etaV(),work,row.work(),work-row.work());
            assertTrue(etaV>=row.etaV()-row.lowBound()&&etaV<=row.etaV()+.005,row.name()+": eta_v "+etaV);
        }
    }

    /** An ideal gas's exponent is its heat capacity ratio; water's at 300 K and 1 MPa about 2,200 (FOLLOWUPS_PLAN.md
     * section 3, point 1). */
    @Test void theExponentIsGammaForAGasAndLargeForWater() {
        // At 1 kPa nitrogen is an ideal gas; at 1 bar its real-gas exponent stands 1.5e-3 above the ideal-gas ratio.
        var ideal=at(300,1e3,pure("Nitrogen"));var nitrogen=at(300,1e5,pure("Nitrogen"));
        assertEquals(model.heatCapacityRatio(ideal),model.isentropicExponent(ideal),1e-3,"nitrogen at 1 kPa");
        assertEquals(model.heatCapacityRatio(nitrogen),model.isentropicExponent(nitrogen),3e-3,"nitrogen at 1 bar");
        for(var probe:List.of(at(300,1e6,pure("Water")),at(310,8.5e6,pure("CarbonDioxide")),nitrogen)) {
            int slot=probe.slotVolume(FluidThermodynamics.State.LIQUID)>0?FluidThermodynamics.State.LIQUID:probe.slotVolume(FluidThermodynamics.State.AQUEOUS)>0?FluidThermodynamics.State.AQUEOUS:FluidThermodynamics.State.VAPOR;
            double n=model.isentropicExponent(probe),isothermal=1/(probe.phase(slot).isothermalCompressibility()*probe.pressure());
            System.out.printf(java.util.Locale.ROOT,"PLUNGER_EXPONENT %.0f K %.3f MPa slot=%d rho=%.2f kg/m3 reducedDensity=%.4f n=%.4f isothermal=%.4f ratio=%.4f%n",
                    probe.temperature(),probe.pressure()/1e6,slot,probe.mass()/probe.volume(),probe.reducedDensity(),n,isothermal,n/isothermal);
            assertTrue(n>=isothermal*(1-1e-9),"the isentropic exponent is at least the isothermal one");
        }
        double water=model.isentropicExponent(at(300,1e6,pure("Water")));
        assertTrue(water>1500&&water<3000,"water at 300 K, 1 MPa: "+water);
        // PR78's local exponent near the critical point, stiffer than CoolProp's (about 2.4); the isentrope at this constant
        // exponent meets CoolProp's density ratio to 25 MPa (an exponent of 3.72 fits 1.336), so the clearance loss is right.
        double co2=model.isentropicExponent(at(310,8.5e6,pure("CarbonDioxide")));
        assertTrue(co2>3&&co2<4.5,"carbon dioxide at 310 K, 8.5 MPa: "+co2);
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
