package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A machine on its curve in the fluid network ({@code MOVER_LAW}, batch 2026-11-03-rotating-machinery, work package W2,
 * plan sections 3.1 to 3.3 and 4.4): the row holds the rise the machine's law gives at the suction volume flow, so a pump
 * runs where its curve meets the system's, closes on a reverse drive, and dead-headed settles at its shutoff rise; a
 * compressor runs on its speed line and books the polytropic work. Each case checks the row on the solved point itself.
 */
class MachineMoverTest extends PortFixture {
    private static final PipeResistance.Geometry FAT=new PipeResistance.Geometry(1,.1,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    /** The pump of the cases: 36 m³/h at 50 m, 70 %. Its rise at the rated point is ρ g H_r, at no flow 1.21 times that. */
    private static final CentrifugalPump PUMP=new CentrifugalPump(.01,50,.7);

    /** A fixed suction {@code from} and a fixed discharge {@code to} (a generator and a void) joined by {@code control}. */
    private static PassiveNetwork line(FluidThermodynamics.State from,FluidThermodynamics.State to,FlowControl control) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,from,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,to,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10,0,1,FAT,control)));
    }
    private static double density(FluidThermodynamics.State state){return state.mass()/state.volume();}

    /** Water from 1 atm into a void the pump's rated rise higher, through 1 m of 0.1 m line (161 Pa at the rated flow): the
     * pump runs at its rated point, 0.09 % short of Q_r by the line's loss, its head on its curve, booking the ideal work. */
    @Test void aPumpOnItsCurveRunsAtItsRatedPointAgainstItsRatedHead() {
        var supply=waterSupply(101325);double rho=density(supply),rated=rho*PassiveStepSolver.GRAVITY*50;
        var result=new PassiveStepSolver(model).solve(line(supply,waterSupply(101325+rated),new FlowControl.Machine(PUMP,1,true)),1,NOOP);
        double q=result.massFlows()[0],head=result.devicePressureChanges()[0];
        System.out.println("MACHINE_RUN rated flow="+q/rho+" m3/s head="+head+" Pa rated="+rated+" Pa work="+result.pumpWorkJoule()+" J");
        assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().getFirst());
        assertEquals(.01,q/rho,5e-5,"the rated flow, less what the line's 161 Pa costs on the curve");assertTrue(q/rho<.01);
        assertEquals(PUMP.rise(q/rho,1,rho,supply.pressure(),1.4),head,1e-6*rated,"the head is the curve's at the solved flow");
        assertEquals(q/rho*head,result.pumpWorkJoule(),1e-9*result.pumpWorkJoule(),"the ideal work, q head / rho, over the 1 s step");
    }
    /** At 80 % speed against its own curve's rise at 80 % of the rated flow, H_r (1.01 s² − 0.01 s), the pump runs at 80 %
     * of its rated flow: the speed scales the flow and the head as the affinity laws do, the linear term with s. */
    @Test void aPumpAtEightyPercentSpeedRunsAtEightyPercentFlowOnItsSpeedLine() {
        var supply=waterSupply(101325);double rho=density(supply);
        double rise=PUMP.rise(.008,.8,rho,supply.pressure(),1.4);
        assertEquals(rho*PassiveStepSolver.GRAVITY*50*(1.01*.64-.008),rise,1e-9*rise,"H_r (1.01 s² − 0.01 s) at q = s");
        var result=new PassiveStepSolver(model).solve(line(supply,waterSupply(101325+rise),new FlowControl.Machine(PUMP,.8,true)),1,NOOP);
        assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().getFirst());
        assertEquals(.008,result.massFlows()[0]/rho,5e-5,"80 % of the rated flow, less the line's loss");
    }
    /** Against 1.3 times its rated rise, above its shutoff (1.21), the pump cannot drive forward flow: it closes as the
     * discharge check valve of every real pump does, carrying nothing and booking no work. A stopped or switched-off
     * machine is closed. */
    @Test void aPumpAboveItsShutoffRiseCloses() {
        var supply=waterSupply(101325);double rated=density(supply)*PassiveStepSolver.GRAVITY*50;
        for(var machine:List.of(new FlowControl.Machine(PUMP,1,true),new FlowControl.Machine(PUMP,0,true),new FlowControl.Machine(PUMP,1,false))) {
            double discharge=machine.targetVolumeFlow()>0?101325+1.3*rated:101325+.5*rated;
            var result=new PassiveStepSolver(model).solve(line(supply,waterSupply(discharge),machine),1,NOOP);
            assertEquals(FlowControl.Mode.CLOSED,result.modes().getFirst(),machine.toString());
            assertEquals(0,result.massFlows()[0],1e-10,machine.toString());assertEquals(0,result.pumpWorkJoule(),1e-9,machine.toString());
        }
    }
    /**
     * Dead-headed: the pump fills a closed 1 m³ vessel holding 0.9 m³ of water under nitrogen at 1 atm. The cushion takes
     * 0.085 m³ to reach the shutoff rise (592 kPa); near it the flow falls as {@code (rise(0) − Δp)/|dRise/dQ|} with a
     * time constant of 0.06 s, so a minute of 5 s slices ends at the shutoff: no flow, the head at {@code 1.21 ρ g H_r}.
     * The pump's work enters the vessel as energy, the component and energy ledgers closing every slice.
     */
    @Test void aDeadHeadedPumpSettlesAtItsShutoffRise() {
        var supply=waterSupply(101325);double rho=density(supply),shutoff=1.21*rho*PassiveStepSolver.GRAVITY*50;
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,supply,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.9,101325))),
                List.of(new PassiveNetwork.Pipe(10,0,1,FAT,new FlowControl.Machine(PUMP,1,true))));
        var last=drive("dead-head",graph,5,12);
        double q=last.averageMassFlows()[0],head=last.endpointHeads()[0];
        System.out.println("MACHINE_RUN dead-head mode="+last.endpointModes().getFirst()+" flow="+q+" kg/s head="+head+" Pa shutoff="+shutoff+" Pa");
        assertEquals(0,q,1e-6*rho*.01,"no flow at the shutoff");
        assertEquals(shutoff,head,.005*shutoff,"the head at the shutoff rise");
    }
    /**
     * Nitrogen from a tank at 150 kPa into one at 180 kPa: a compressor rated 0.02 m³/s at 24.4 kJ/kg (ratio 1.3 on this
     * suction at η_p 0.78) runs on its speed line where its ratio meets the 1.2 the tanks and the line ask, past its rated
     * flow. Its row holds the law's rise on the suction it draws, k from the suction's start state, and it books the
     * polytropic work {@code ṁ σ (P_s/ρ_s)(r^(1/σ) − 1)/η_p}.
     */
    @Test void aCompressorOnItsSpeedLineHoldsItsLawsRiseAndBooksThePolytropicWork() {
        var law=new CentrifugalCompressor(.02,24400,CentrifugalCompressor.DEFAULT_EFFICIENCY);
        var start=nitrogenAt(150000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,start),new PassiveNetwork.Reservoir(2,0,nitrogenAt(180000))),
                List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Machine(law,1,true))));
        var result=new PassiveStepSolver(model).solve(graph,.5,NOOP);var suction=result.states().getFirst();
        double rho=density(suction),p=suction.pressure(),q=result.massFlows()[0],head=result.devicePressureChanges()[0];
        double eta=CentrifugalCompressor.DEFAULT_EFFICIENCY,k=model.heatCapacityRatio(start),sigma=eta*k/(k-1);
        System.out.println("MACHINE_RUN compressor flow="+q/rho+" m3/s ratio="+(1+head/p)+" k="+k+" work="+result.pumpWorkJoule()+" J");
        assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().getFirst());
        assertTrue(q/rho>.02&&q/rho<.036,"past the rated flow, short of the runout: "+q/rho);
        assertEquals(law.rise(q/rho,1,rho,p,k),head,1e-6*head,"the row holds the law's rise at the solved flow");
        double expected=.5*q*sigma*(p/rho)*(Math.pow(1+head/p,1/sigma)-1)/eta;
        assertEquals(expected,result.pumpWorkJoule(),1e-6*expected,"the polytropic work over the 0.5 s step");
    }
    /** The heat capacity ratio a compressor's law reads: nitrogen's ideal gas 1.40 at room temperature, steam's from Smith,
     * Van Ness and Abbott's {@code cp/R = 3.470 + 1.450e-3 T + 0.121e5/T²} (1.3199 at 400 K). */
    @Test void theHeatCapacityRatioIsTheSuctionGassIdealGasRatio() {
        assertEquals(1.40,model.heatCapacityRatio(nitrogenAt(101325)),.005,"nitrogen");
        var n=new double[mw.length];n[water]=1;
        double cp=3.470+1.450e-3*400+.121e5/(400.*400);
        // W3b: steam's ideal gas is the cubic water record's fit; its ratio at 400 K is 4.8e-4 over the correlation's.
        assertEquals(cp/(cp-1),model.heatCapacityRatio(model.flashTP(400,101325,n,NOOP)),1e-3,"steam at 400 K");
    }
    /** Form F2e (plan 3.3) as a set-flow mover states it: the set flow is the displacement and the rise limit the relief
     * rise, so the law holds the limit at no flow, 99.80 % of the set flow at half the limit and
     * 98.8 % at 90 %; its row is the flow form, zero on the law, with the limit over the set flow per m3/s of flow and the
     * slip per pascal of head at no rise. */
    @Test void aSetFlowMoversLawIsItsSetFlowLessItsSlipUpToItsRiseLimit() {
        double rho=model.pumpReferenceDensity();
        for(FlowControl.Mover mover:List.of(new FlowControl.Pump(.01,500000),new FlowControl.Compressor(.05,3))) {
            double limit=mover.riseLimit(rho,1e5,rho,1.4),flow=mover.targetVolumeFlow();
            assertEquals(mover.liquid()?500000:2e5,limit,1e-9*limit,mover.deviceName());
            assertEquals(limit,mover.lawRise(0,rho,1e5,rho,1.4),1e-12*limit,mover.deviceName()+": the limit is the law's rise at no flow");
            assertEquals(.5,mover.lawRise(.9979750133*flow,rho,1e5,rho,1.4)/limit,1e-6,mover.deviceName()+": 99.80 % at half the limit");
            assertEquals(.9,mover.lawRise(.9880804789*flow,rho,1e5,rho,1.4)/limit,1e-6,mover.deviceName()+": 98.8 % at 90 %");
            double rise=mover.lawRise(.5*flow,rho,1e5,rho,1.4);
            assertEquals(0,mover.lawResidual(.5*flow,rise,rho,1e5,rho,1.4),1e-9*limit,mover.deviceName());
            double atSetFlow=mover.lawResidual(flow,0,rho,1e5,rho,1.4);
            assertEquals(limit/flow,(mover.lawResidual(1.001*flow,0,rho,1e5,rho,1.4)-atSetFlow)/(.001*flow),1e-9*limit/flow,mover.deviceName()+": a flow derivative at no rise");
            assertEquals(com.wormzjl.icheme.science.machine.PositiveDisplacement.SLIP,(mover.lawResidual(flow,1e-3*limit,rho,1e5,rho,1.4)-atSetFlow)/(1e-3*limit),1e-4,
                    mover.deviceName()+": the slip per pascal of head at no rise");
        }
    }

    /** {@code count} slices of {@code interval}, each a job from the committed interval, the component and energy ledgers
     * checked every slice with the mover's work as the energy it adds; the last slice. */
    private PassiveIntervalSolver.Result drive(String label,PassiveNetwork graph,double interval,int count) {
        graph=PassiveNetwork.sizeJunctionHoldups(graph,model);
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0;
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed);
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice);
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            externalEnergy+=result.pumpWorkJoule();
            var now=owned(graph);
            for(int c=0;c<now.length;c++)assertEquals(initial[c]+external[c],now[c],1e-12*Math.max(1,initial[c]),label+" slice "+slice+": component "+c);
            assertEquals(initialEnergy+externalEnergy,ownedEnergy(graph),1e-10*Math.abs(initialEnergy),label+" slice "+slice+": energy");
        }
        return committed;
    }
}
