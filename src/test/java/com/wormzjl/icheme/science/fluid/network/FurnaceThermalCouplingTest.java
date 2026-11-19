package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The radiant budget and its local flue belong to the same point in the residual and in the energy ledger. */
class FurnaceThermalCouplingTest {
    private static final long CONVECTION=10,RADIANT=13;
    private static final double STEP=.25;
    private final FluidThermodynamics model=FluidTestSupport.networkModel().on(List.of("Nitrogen"),false);

    private PassiveNetwork heater(boolean local,boolean adjacent) {
        int radiantDonor=adjacent?1:3;
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        var passage=new PipeResistance.Geometry(30,.1,.000045,0);
        for(int i=0;i<=radiantDonor+2;i++) {
            var kind=i==0?PassiveNetwork.NodeKind.GENERATOR:i==radiantDonor+2?PassiveNetwork.NodeKind.VOID:PassiveNetwork.NodeKind.JUNCTION;
            var moles=new double[model.componentCount()];moles[0]=1;
            var state=model.flashTP(350,400000-25000*i,moles,()->{});
            reservoirs.add(new PassiveNetwork.Reservoir(i+1,0,state,kind));
            if(i>0&&i!=radiantDonor+1)pipes.add(new PassiveNetwork.Pipe(i==1?CONVECTION:20+i,i,i-1,passage));
        }
        var convection=pipes.getFirst().withHeat(new ConvectionCoil(2500,local?RADIANT:90,80));
        var radiant=new PassiveNetwork.Pipe(RADIANT,radiantDonor+1,radiantDonor,passage)
                .withHeat(new FiredCoil(435,2e6,1,local?CONVECTION:0));
        pipes.set(0,convection);
        pipes.add(radiantDonor,radiant);
        return new PassiveNetwork(reservoirs,pipes);
    }

    @Test void reversedLocalAndRemoteConvectionBookTheirOwnFlueSupply() {
        for(boolean local:new boolean[]{true,false}) {
            var solver=new PassiveStepSolver(model);
            var result=solver.solve(heater(local,false),STEP,()->{});
            var equations=solver.acceptedEquations();
            int radiant=3;
            double convectionFlow=result.massFlows()[0],radiantFlow=result.massFlows()[radiant];
            assertTrue(convectionFlow<-.01&&radiantFlow<-.01,"both passages flow against their registered direction");
            var coldPipe=equations.graph.pipes().getFirst();
            var coldLaw=(ConvectionCoil)coldPipe.heat();
            var donor=result.states().get(coldPipe.second());
            var curve=solver.sideCurve(equations.graph,coldPipe,coldPipe.second(),()->{});
            double radiantRate=heat(result,RADIANT)/STEP;
            assertTrue(radiantRate>0,"the radiant coil is drawing fuel heat");
            double flue=local?ConvectionCoil.flueOf(radiantRate):coldLaw.flue();
            assertEquals(coldLaw.heatAtFlue(curve,donor.enthalpy()/donor.mass(),convectionFlow,flue)*STEP,
                    heat(result,CONVECTION),1e-7,"the ledger reads the reconstructed donor and actual radiant heat");
        }
    }

    @Test void theBlockAndColoredSweepsSeeRadiantColumnsInTheConvectionBalance() {
        for(boolean adjacent:new boolean[]{true,false}) {
            var solver=new PassiveStepSolver(model);
            solver.solve(heater(true,adjacent),STEP,()->{});
            var equations=solver.acceptedEquations();
            var point=solver.acceptedPoint();
            var base=equations.residual(point);
            int[][] pattern=equations.columnRows();
            int[] offsets=new int[point.length+1];
            for(int column=0;column<point.length;column++)offsets[column+1]=offsets[column]+pattern[column].length;
            int[] rows=new int[offsets[point.length]];
            for(int column=0;column<point.length;column++)System.arraycopy(pattern[column],0,rows,offsets[column],pattern[column].length);
            double[] block=new double[rows.length];
            assertEquals(point.length,equations.differentiateEntries(point,base,1e-6,offsets,rows,block,()->{},false));
            int recipient=1,donor=adjacent?1:3;
            int convectionEnergy=equations.offsets[recipient]+equations.layout[recipient].componentBalanceCount();
            int radiantFlow=equations.edgeOffset+donor;
            int donorTemperature=equations.offsets[donor]+equations.layout[donor].pressureVariable()-1;
            for(int column:new int[]{radiantFlow,donorTemperature}) {
                // With coloring, a missing dependency would put simultaneous changes into this nonzero derivative.
                int position=Arrays.binarySearch(pattern[column],convectionEnergy);
                assertTrue(position>=0,"the numerical sparsity must include the convection energy row");
                var moved=point.clone();
                double step=1e-6*equations.differenceScale(column,point[column]);
                moved[column]+=step;
                double slope=(equations.residual(moved)[convectionEnergy]-base[convectionEnergy])/step;
                assertTrue(Math.abs(slope)>1e-6,"the radiant change must move this residual");
                assertEquals(slope,block[offsets[column]+position],1e-9*Math.max(1,Math.abs(slope)));
            }
            var perturbed=point.clone();
            perturbed[radiantFlow]*=1.002;
            perturbed[donorTemperature]+=1e-4;
            var numeric=SparseNewton.solve(new SparseNewton.Equations() {
                @Override public int size(){return equations.size();}
                @Override public double[] residual(double[] x){return equations.residual(x);}
                @Override public int[][] columnRows(){return pattern;}
                @Override public double differenceScale(int column,double value){return equations.differenceScale(column,value);}
            },perturbed,SparseNewton.Settings.defaults(),()->{});
            assertEquals(point[radiantFlow],numeric.variables()[radiantFlow],1e-6);
            assertEquals(point[donorTemperature],numeric.variables()[donorTemperature],1e-6);
        }
    }

    private static double heat(PassiveStepSolver.Result result,long pipe) {
        return result.boundaries().stream().filter(transfer->transfer.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT
                &&transfer.nodeId()==pipe).findFirst().orElseThrow().totalEnergyJoule();
    }
}
