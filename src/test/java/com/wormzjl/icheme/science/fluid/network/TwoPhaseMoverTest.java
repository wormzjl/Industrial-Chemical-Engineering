package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Request 11 in the fluid network (batch 2026-11-03-rotating-machinery, work package W10a, plan section 3.6, owner decisions
 * D19 and D20): no inlet-phase refusal; a centrifugal pump's head scaled by its gas share, read once per solve on the
 * step's start states; a pump's suction floor raised by its breakdown NPSH at its flow; a centrifugal compressor on a wet
 * suction booking its work over its wet efficiency. Each case checks the row on the solved point itself.
 */
class TwoPhaseMoverTest extends PortFixture {
    private static final PipeResistance.Geometry FAT=new PipeResistance.Geometry(1,.1,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    /** The pump of MachineMoverTest: 36 m³/h at 50 m, 70 %, 2950 rpm. */
    private static final CentrifugalPump PUMP=new CentrifugalPump(.01,50,.7);

    private static PassiveNetwork line(FluidThermodynamics.State from,FluidThermodynamics.State to,FlowControl control) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,from,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,to,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10,0,1,FAT,control)));
    }
    private static double density(FluidThermodynamics.State state){return state.mass()/state.volume();}
    private static double share(FluidThermodynamics.State s){return s.vaporVolume()/(s.vaporVolume()+s.liquidVolume()+s.waterVolume());}
    /** Water with nitrogen at 298.15 K and {@code pressure} whose vapour fills {@code target} of its fluid volume (bisection
     * on the nitrogen amount; 1 m3 of water), as InletPhaseTest builds it. */
    private FluidThermodynamics.State twoPhase(double target,double pressure) {
        double[] n=new double[mw.length];n[water]=997/mw[water];
        double lo=0,hi=1;
        while(true){n[nitrogen]=hi;if(share(model.flashTP(298.15,pressure,n,NOOP))>target)break;hi*=2;}
        for(int i=0;i<80;i++){n[nitrogen]=.5*(lo+hi);if(share(model.flashTP(298.15,pressure,n,NOOP))>target)hi=n[nitrogen];else lo=n[nitrogen];}
        n[nitrogen]=.5*(lo+hi);return model.flashTP(298.15,pressure,n,NOOP);
    }

    /**
     * A centrifugal pump lifting water 30 % of its rated head from 1 atm: on liquid it runs near 2.15 Q_r. With 3 % gas by
     * volume at its suction its head is {@code M(α) = 1/(1 + (α/α_50)^4)} of the liquid curve's, 0.64 at 1 atm (α_T 2.2 %,
     * α_50 3.5 %), on the mixture's density: the row holds that rise at the solved flow, the pump is not refused and moves
     * less. With 10 % gas M is 0.014, its shutoff rise below the lift: gas lock, which no rule states: the curve meets zero
     * flow and the check valve closes the pump.
     */
    @Test void aCentrifugalPumpWithGasAtItsSuctionMakesItsGasShareOfTheHead() {
        var liquid=waterSupply(101325);double lift=.3*density(liquid)*PassiveStepSolver.GRAVITY*PUMP.ratedHead();
        var machine=new FlowControl.Machine(PUMP,1,true);
        var dry=new PassiveStepSolver(model).solve(line(liquid,waterSupply(101325+lift),machine),1,NOOP);
        assertEquals(FlowControl.Mode.MOVER_LAW,dry.modes().getFirst());
        double dryFlow=dry.massFlows()[0]/density(liquid);
        var gassy=twoPhase(.03,101325);var graph=line(gassy,waterSupply(101325+lift),machine);
        var supply=InletPhase.supply(model,graph,0,machine.targetVolumeFlow(),1);
        double alpha=supply.vapourVolumeShare(),m=GasEntrainment.headShare(alpha,gassy.pressure()),rho=density(gassy);
        var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
        double q=result.massFlows()[0],head=result.devicePressureChanges()[0];
        System.out.println("TWO_PHASE_PUMP alpha="+alpha+" M="+m+" dryFlow="+dryFlow+" flow="+q/rho+" head="+head+" lift="+lift+" mode="+result.modes().getFirst());
        assertEquals(.03,alpha,1e-6,"the supply's vapour share");assertEquals(.64,m,.01,"M at 3 % and 1 atm");
        assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().getFirst(),"gas at a pump is no refusal");
        assertEquals(m*PUMP.rise(q/rho,1,rho,gassy.pressure(),1.4),head,1e-6*lift,"the row holds M times the curve's rise on the mixture");
        assertTrue(q/rho<dryFlow,"less head, less flow: "+q/rho+" against "+dryFlow);
        var locked=new PassiveStepSolver(model).solve(line(twoPhase(.10,101325),waterSupply(101325+lift),machine),1,NOOP);
        assertEquals(FlowControl.Mode.CLOSED,locked.modes().getFirst(),"10 % gas: gas lock, the check valve closes it");
        assertEquals(0,locked.massFlows()[0],1e-10);
    }

    /**
     * A pump [0.01 m³/s, 500 kPa] drawing water at 298.15 K through 1 m of 0.1 m line from a generator at 11 kPa, into a 1 atm
     * void. On the bubble floor alone (3.17 kPa, the water curve) its suction would stand near 11 kPa and the pump at its set
     * flow. Its breakdown NPSH (owner decision D20 Q11 A; the in-line design, 1.68 m at 2950 rpm, D19) is
     * {@code 0.85 ρ g NPSH3} = 5.6 kPa at shutoff and 14 kPa at the set flow, so the floor binds above the bubble floor: the
     * pump stands on its suction limit, its suction at the water's vapour pressure plus the breakdown NPSH at its flow,
     * and delivers about half its set flow, where the floor meets what the line gives.
     */
    @Test void aPumpsNpshFloorBindsAboveTheBubbleFloor() {
        var supply=waterSupply(11000);var pump=new FlowControl.Pump(.01,500000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,supply,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,supply,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,waterSupply(101325),PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10,0,1,FAT,new FlowControl.Passive()),new PassiveNetwork.Pipe(11,1,2,FAT,pump)));
        var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
        var suction=result.states().get(1);double q=result.massFlows()[1],volume=q/density(suction);
        double vapour=model.saturationPressure(suction.temperature());
        double floor=vapour+pump.npsh().breakdownPressure(volume,InletPhase.liquidDensity(model,graph,1,pump.targetVolumeFlow(),1));
        System.out.println("NPSH_FLOOR mode="+result.modes().get(1)+" flow="+volume+" m3/s suctionP="+suction.pressure()+" vapourP="+vapour+" floor="+floor);
        assertEquals(FlowControl.Mode.PUMP_SUCTION_LIMIT,result.modes().get(1),"the pump cavitates on its NPSH floor");
        assertEquals(floor,suction.pressure(),1e-3*floor,"its suction stands at the vapour pressure plus its breakdown NPSH at its flow");
        assertTrue(suction.pressure()>2*vapour,"well above the bubble floor, where it would have run at its set flow");
        assertTrue(volume>.3*pump.targetVolumeFlow()&&volume<.7*pump.targetVolumeFlow(),"about half its set flow: "+volume);
    }

    /**
     * A centrifugal compressor rated 0.02 m³/s at 24.4 kJ/kg (MachineMoverTest's) drawing wet nitrogen, 0.2 L of water in each
     * cubic metre at 150 kPa (about 10 % liquid by mass), into a 180 kPa void. Its suction was refused above 1 % condensed by
     * mass; now it runs: its curve reads the mixture's volume, its ratio the mixture's density, and its work is
     * {@code ṁ H_p/η_wet}, {@code η_wet = η_p (1 − 0.5 w_l)}, the one number the energy rows and the transport's booking read.
     */
    @Test void aCentrifugalCompressorTakesAWetSuctionAndBooksItsWorkOverItsWetEfficiency() {
        var law=new CentrifugalCompressor(.02,24400,CentrifugalCompressor.DEFAULT_EFFICIENCY);var machine=new FlowControl.Machine(law,1,true);
        var wet=waterUnderNitrogen(2e-4,150000);var graph=line(wet,nitrogenAt(180000),machine);
        double wl=InletPhase.supply(model,graph,0,machine.targetVolumeFlow(),.5).condensedMassShare();
        assertFalse(machine.refuses(InletPhase.supply(model,graph,0,machine.targetVolumeFlow(),.5),false),"a wet suction is no refusal");
        var result=new PassiveStepSolver(model).solve(graph,.5,NOOP);
        double rho=density(wet),p=wet.pressure(),q=result.massFlows()[0],head=result.devicePressureChanges()[0];
        double k=model.heatCapacityRatio(wet),sigma=CentrifugalCompressor.sigma(k,law.efficiency()),eta=CentrifugalCompressor.wetEfficiency(law.efficiency(),wl);
        System.out.println("WET_COMPRESSOR liquidMassShare="+wl+" etaWet="+eta+" flow="+q/rho+" m3/s ratio="+(1+head/p)+" work="+result.pumpWorkJoule()+" J mode="+result.modes().getFirst());
        assertTrue(wl>.05&&wl<.2,"about 10 % liquid by mass: "+wl);
        assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().getFirst());assertTrue(q>0);
        assertEquals(law.rise(q/rho,1,rho,p,k),head,1e-6*head,"the curve's rise on the suction mixture's volume and density");
        double expected=.5*q*sigma*(p/rho)*(Math.pow(1+head/p,1/sigma)-1)/eta;
        assertEquals(expected,result.pumpWorkJoule(),1e-6*expected,"the polytropic work over the wet efficiency, over the 0.5 s step");
    }
}
