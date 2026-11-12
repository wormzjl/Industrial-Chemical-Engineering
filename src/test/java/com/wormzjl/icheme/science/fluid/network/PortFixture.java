package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The network model, its supplies and vessels, and the ledger of what a graph's tanks and junctions own: shared by
 * the port, level-head, inlet-phase and compressor tests, which check component and energy ledgers slice by slice.
 */
abstract class PortFixture {
    static final Runnable NOOP=()->{};
    final FluidThermodynamics model;
    final double[] mw;
    final int water,nitrogen;
    PortFixture(){this(FluidTestSupport.networkModel());}
    /** The fixture on {@code model}: the network model, or one of its views (an island's own compound list). */
    PortFixture(FluidThermodynamics model){this.model=model;mw=model.molecularWeights();water=index("Water");nitrogen=index("Nitrogen");}

    int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    /** A 1 m3 nitrogen charge at 298.15 K. */
    FluidThermodynamics.State nitrogenAt(double pressure){return model.initialNitrogenCharge(1,298.15,pressure,NOOP);}
    /** One mole of water flashed at 298.15 K: a generator's supply. */
    FluidThermodynamics.State waterSupply(double pressure){var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,pressure,n,NOOP);}
    /** A 1 m3 vessel at 298.15 K holding {@code waterVolume} m3 of water under nitrogen at {@code pressure}. */
    FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    double mass(double[] moles){double m=0;for(int c=0;c<moles.length;c++)m+=moles[c]*mw[c];return m;}
    /** The moles held by the graph's tanks and junctions. */
    double[] owned(PassiveNetwork graph) {
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    /** Their internal energy (no potential energy, owner decision D37 S1). */
    double ownedEnergy(PassiveNetwork graph) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction())
            energy+=node.inventory().internalEnergy();
        return energy;
    }
    /** Every double of {@code count} chained backward-Euler steps, each graph carrying the previous step's states and
     * owned inventories forward, as raw bits (see {@link #bits}). */
    List<Long> steps(PassiveNetwork graph,double dt,int count) {
        var bits=new ArrayList<Long>();var solver=new PassiveStepSolver(model);
        for(int step=0;step<count;step++) {
            var result=solver.solve(graph,dt,NOOP);
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();
            for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);
                nodes.add(node.fixed()?node:new PassiveNetwork.Reservoir(node.id(),node.elevation(),result.states().get(i),node.kind(),result.inventories().get(i)));}
            graph=new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
            for(var node:graph.reservoirs()){var s=node.state();
                for(double v:new double[]{s.temperature(),s.pressure(),s.mass(),s.volume(),TestPhases.liquidVolume(s),TestPhases.waterVolume(s),s.vaporVolume(),node.inventory().internalEnergy()})bits.add(bits(v));
                for(double n:node.inventory().moles())bits.add(bits(n));}
            for(double q:result.massFlows())bits.add(bits(q));
            for(var b:result.boundaries()){bits.add(b.nodeId());bits.add(bits(b.totalEnergyJoule()));for(double n:b.moles())bits.add(bits(n));}
            for(var t:result.pipeTransfers())for(var s:List.of(t.forward(),t.reverse())){bits.add(bits(s.massKg()));for(var p:s.phaseMoles())for(double n:p)bits.add(bits(n));for(double v:s.phaseVolumes())bits.add(bits(v));}
        }
        return bits;
    }
    /** The raw bits of a double, a zero of either sign read as +0 (a zero-flow line books {@code 0*h}, whose sign is
     * the sign of the enthalpy it multiplies). */
    static long bits(double v){return Double.doubleToRawLongBits(v==0?0:v);}
}
