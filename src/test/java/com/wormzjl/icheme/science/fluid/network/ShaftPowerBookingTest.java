package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.InlineMachines;
import com.wormzjl.icheme.science.machine.SpeedControl;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Code base review M-01 and M-07 (owner decisions A2 and B1): a driver pays the shaft power the solve's own function gives,
 * {@link InlineMachines#shaftPower} at the slice's average flow and booked head, on the suction's heat capacity ratio and wet
 * share as the solve reads them ({@link PassiveStepSolver#suctionKappa}, {@link PassiveStepSolver#wetShare}). On a settled
 * slice (a fixed suction into a fixed discharge: every step the same):
 * <ul>
 * <li>a gas mover's charged energy is the transport's booked work. Bound: 1e-9 relative for a frame, whose booking is the
 * same power function (the sum of the slice's equal steps against one product, and {@code 1/(V/m)} against {@code m/V});
 * 1e-5 for a centrifugal machine, whose booking reads the solved rise and whose charge reads the curve's head at the solved
 * flow: the Newton holds the law's row to 1e-6 of the rise ({@code MachineMoverTest}), and the polytropic work follows it;</li>
 * <li>a liquid machine's is the booked ideal work over its curve's efficiency at the point (0.7 at its rated point);</li>
 * <li>a set-flow pump's is the booked work over {@link InlineMachines#SET_FLOW_PUMP_EFFICIENCY}.</li>
 * </ul>
 * And a speed controller holding a discharge pressure on a gas at k 1.2 reads that k: it settles on its target.
 */
class ShaftPowerBookingTest extends PortFixture {
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private static final PipeResistance.Geometry FAT=new PipeResistance.Geometry(1,.1,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);

    /** A 1 m³ charge of pure {@code component} at {@code temperature} and {@code pressure}. */
    private FluidThermodynamics.State gas(String component,double temperature,double pressure) {
        var n=new double[mw.length];n[index(component)]=1;
        var unit=model.flashTP(temperature,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(temperature,pressure,n,NOOP);
    }
    private static PassiveNetwork line(FluidThermodynamics.State from,FluidThermodynamics.State to,FlowControl control,PipeResistance.Geometry geometry) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,from,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,to,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10,0,1,geometry,control)));
    }
    private PassiveIntervalSolver.Result slice(PassiveNetwork graph){return new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),NOOP);}
    /** The energy a driver is charged over the slice, J, read as the trains and the stores read it. */
    private double charged(PassiveIntervalSolver.Result result) {
        var graph=result.graph();var pipe=graph.pipes().getFirst();var suction=graph.reservoirs().get(pipe.first()).state();
        var mover=(FlowControl.Mover)pipe.control();double density=suction.mass()/suction.volume();
        double kappa=PassiveStepSolver.suctionKappa(model,mover,suction),wet=PassiveStepSolver.wetShare(model,graph,0,result.advancedSeconds());
        return InlineMachines.shaftPower(mover,result.averageMassFlows()[0],result.endpointHeads()[0],density,suction.pressure(),kappa,wet)*result.advancedSeconds();
    }

    /** A frame on propane (k about 1.13) at a ratio of 2.5: the charge is its booked work, where k 1.4 would charge 13 % more. */
    @Test void aFramesChargeIsItsBookedWorkOnPropane() {
        var suction=gas("Propane",300,100_000);
        var result=slice(line(suction,gas("Propane",300,250_000),new FlowControl.Compressor(.05,3),LINE));
        double booked=result.pumpWorkJoule(),kappa=PassiveStepSolver.suctionKappa(model,new FlowControl.Compressor(.05,3),suction);
        System.out.println("SHAFT_BOOKING frame propane k="+kappa+" booked="+booked+" J charged="+charged(result)+" J");
        assertTrue(booked>0,"the frame works");assertTrue(kappa<1.2,"propane's k: "+kappa);
        assertEquals(booked,charged(result),1e-9*booked,"the charge is the booked work");
    }
    /** A centrifugal compressor on nitrogen from 150 to 180 kPa, past its rated flow ({@code MachineMoverTest}'s case: no
     * recycle, a dry suction, wet share 1). */
    @Test void aCentrifugalCompressorsChargeIsItsBookedWork() {
        var law=new CentrifugalCompressor(.02,24400,CentrifugalCompressor.DEFAULT_EFFICIENCY);
        var result=slice(line(nitrogenAt(150_000),nitrogenAt(180_000),new FlowControl.Machine(law,1,true),LINE));
        double booked=result.pumpWorkJoule(),flow=result.averageMassFlows()[0];
        var suction=result.graph().reservoirs().getFirst().state();
        System.out.println("SHAFT_BOOKING machine nitrogen flow="+flow/(suction.mass()/suction.volume())+" m3/s booked="+booked+" J charged="+charged(result)+" J");
        assertTrue(booked>0&&flow/(suction.mass()/suction.volume())>.02,"past the rated flow");
        assertEquals(1,PassiveStepSolver.wetShare(model,result.graph(),0,result.advancedSeconds()),"a dry suction");
        assertEquals(booked,charged(result),1e-5*booked,"the charge is the booked work");
    }
    /** A centrifugal pump at its rated point: the solve books the ideal q Δp (simplification S1), the driver pays the shaft
     * power, that work over the curve's efficiency at the point: the design 0.7 at the rated point. */
    @Test void aLiquidMachinesChargeIsTheBookedWorkOverItsCurvesEfficiency() {
        var pump=new CentrifugalPump(.01,50,.7);var supply=waterSupply(101325);double rho=supply.mass()/supply.volume(),rated=rho*PassiveStepSolver.GRAVITY*50;
        var result=slice(line(supply,waterSupply(101325+rated),new FlowControl.Machine(pump,1,true),FAT));
        double booked=result.pumpWorkJoule(),charged=charged(result),q=result.averageMassFlows()[0]/rho;
        double efficiency=pump.fluidPower(q*rho,pump.rise(q,1,rho,101325,1.4),rho,101325,1.4)/pump.shaftPower(q,1,rho,101325,1.4);
        System.out.println("SHAFT_BOOKING pump booked="+booked+" J charged="+charged+" J efficiency="+efficiency);
        assertEquals(booked/efficiency,charged,1e-5*charged,"the booked work over the curve's efficiency at the point");
        assertEquals(.7,booked/charged,.005,"the design efficiency at the rated point");
    }
    /** A set-flow pump: the booked ideal work over the named 65 %. */
    @Test void aSetFlowPumpsChargeIsTheBookedWorkOverItsEfficiency() {
        var result=slice(line(waterSupply(101325),waterSupply(301325),new FlowControl.Pump(.001,500000),FAT));
        double booked=result.pumpWorkJoule();
        assertTrue(booked>0,"the pump works");
        assertEquals(booked/InlineMachines.SET_FLOW_PUMP_EFFICIENCY,charged(result),1e-9*charged(result),"over the set-flow pump's efficiency");
        assertEquals(.65,InlineMachines.SET_FLOW_PUMP_EFFICIENCY);
    }
    /** M-07: a compressor holding a discharge pressure at a ratio of 1.5 on ethane (k about 1.19; ratio 2 would need more than
     * the controller's 1.5 speed on this law) at the speed its controller finds with the suction's k delivers its target; the
     * speed found with 1.4 holds it about 4 % of the rise off. */
    @Test void aPressureControllerOnAKOfOnePointTwoGasSettlesOnItsTarget() {
        var law=new CentrifugalCompressor(.02,24400,CentrifugalCompressor.DEFAULT_EFFICIENCY);
        var suction=gas("Ethane",300,100_000);double rho=suction.mass()/suction.volume(),p=suction.pressure(),flow=.02,target=.5*p;
        double kappa=PassiveStepSolver.suctionKappa(model,new FlowControl.Machine(law,1,true),suction);
        assertEquals(1.19,kappa,.03,"ethane's k");
        double speed=SpeedControl.requiredSpeed(law,flow,target,rho,p,kappa,.1,1.5),stale=SpeedControl.requiredSpeed(law,flow,target,rho,p,1.4,.1,1.5);
        double held=law.rise(flow,speed,rho,p,kappa),off=law.rise(flow,stale,rho,p,kappa);
        System.out.println("SHAFT_BOOKING pressure control ethane k="+kappa+" speed="+speed+" (1.4: "+stale+") rise="+held+" (1.4: "+off+") target="+target);
        assertEquals(target,held,1e-4*target,"the speed found on the suction's k holds the target");
        assertTrue(Math.abs(off-target)>.01*target,"the speed found on 1.4 misses it: "+off);
    }
}
