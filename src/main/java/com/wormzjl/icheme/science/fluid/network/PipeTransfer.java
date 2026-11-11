package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;

/** Gross transport history: reversals retain two nonnegative compositions. This is never owned stock. */
public record PipeTransfer(long pipeId, Stream forward, Stream reverse) {
    public PipeTransfer {Objects.requireNonNull(forward);Objects.requireNonNull(reverse);}

    /** Phase order is hydrocarbon liquid, free water, mixed vapor. Water is the final component. */
    public record Stream(double massKg, double[][] phaseMoles, double[] phaseVolumes,SolidInventory solids) {
        public Stream(double massKg,double[][] phaseMoles,double[] phaseVolumes){this(massKg,phaseMoles,phaseVolumes,SolidInventory.EMPTY);}
        public Stream {
            Objects.requireNonNull(solids);
            phaseMoles=copy(phaseMoles);phaseVolumes=phaseVolumes.clone();
            if(!Double.isFinite(massKg)||massKg<0||phaseMoles.length!=3||phaseVolumes.length!=3)throw new IllegalArgumentException("Invalid pipe history");
            int count=phaseMoles[0].length;
            for(int p=0;p<3;p++) {
                if(phaseMoles[p].length!=count||!Double.isFinite(phaseVolumes[p])||phaseVolumes[p]<0)throw new IllegalArgumentException("Invalid phase history");
                for(double n:phaseMoles[p])if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid transported amount");
            }
        }
        @Override public double[][] phaseMoles(){return copy(phaseMoles);}
        @Override public double[] phaseVolumes(){return phaseVolumes.clone();}
        public double[] componentMoles(){double[] n=new double[phaseMoles[0].length];for(var phase:phaseMoles)for(int c=0;c<n.length;c++)n[c]+=phase[c];return n;}
        private static double[][] copy(double[][] source){return Arrays.stream(source).map(double[]::clone).toArray(double[][]::new);}
    }

    /** One immutable empty stream per basis size instead of one per pipe and step; a Stream's arrays are copied in and
     * out, so sharing it is invisible. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer,Stream> ZERO=new java.util.concurrent.ConcurrentHashMap<>();
    private static Stream zero(int count){return ZERO.computeIfAbsent(count,c->new Stream(0,new double[3][c],new double[3]));}
    /** {@link #sample(FluidThermodynamics,PassiveNetwork,List,double[],double,double[][])} of a graph none of whose
     * connections drew phases, which needs no model. */
    static List<PipeTransfer> sample(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] rates,double duration) {
        return sample(null,graph,states,rates,duration,null);
    }
    /**
     * What each connection moved over {@code duration} at the given rates, in the phase split of the stream it drew: the
     * donor's whole state where it drew the bulk (every phase in proportion, solids included), and where a phase port drew
     * phases (decision D11: {@code draws[i]}, the mass flow each phase supplied, adding up to the rate) each drawn phase
     * scaled by its moved mass over its stream's mass: the vapour and water vapour, the hydrocarbon liquid, the free water,
     * a liquid with its share of the solids. {@code draws} (null: every connection drew the bulk) needs the model for the
     * stream masses.
     */
    static List<PipeTransfer> sample(FluidThermodynamics model,PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] rates,double duration,double[][] draws) {
        if(draws!=null&&model==null)throw new IllegalArgumentException("A phase port's transfer needs the model");
        var result=new ArrayList<PipeTransfer>();
        for(int i=0;i<rates.length;i++) {
            var pipe=graph.pipes().get(i);var state=states.get(rates[i]>=0?pipe.first():pipe.second());
            double[] drawn=draws==null?null:draws[i];var start=graph.reservoirs().get(rates[i]>=0?pipe.first():pipe.second()).state();
            // Entries 0-2 are drawn at the step's end state (these states), 3-5 at its start state (the graph's): a phase
            // pinned at its capacity (PhaseDraw).
            if(drawn!=null)for(int k=0;k<drawn.length;k++)if(drawn[k]>0&&!FluidThermodynamics.holdsPhase(k<3?state:start,k%3))drawn=null;
            double mass=Math.abs(rates[i])*duration;
            int count=state.componentCount();
            double[][] n=new double[3][count];
            Stream moved;
            if(drawn!=null) {
                double[] volumes=new double[3];var solids=SolidInventory.EMPTY;
                for(int k=0;k<drawn.length;k++) {
                    if(!(drawn[k]>0))continue;
                    int phase=k%3;var from=k<3?state:start;
                    double fraction=drawn[k]*duration/model.phaseMass(from,phase);
                    // The stream's rows: the oil (0), the aqueous liquid (1) and the gas (2), each a whole phase of the donor.
                    int row=phase==FluidThermodynamics.GAS?2:phase==FluidThermodynamics.OIL?0:1,slot=from.slot(phase);
                    double[] amounts=from.amountsView(slot);
                    for(int c=0;c<count;c++)n[row][c]+=fraction*amounts[c];
                    volumes[row]+=fraction*from.slotVolume(slot);
                    if(phase!=FluidThermodynamics.GAS){var carried=from.solids().scale(fraction*FluidThermodynamics.phaseSolidShare(from,phase));solids=solids.empty()?carried:solids.plus(carried);}
                }
                moved=new Stream(mass,n,volumes,solids);
            } else {
                double fraction=mass/state.mass();
                double[] volumes=new double[3];
                for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS,FluidThermodynamics.GAS}) {
                    int slot=state.slot(phase),row=phase==FluidThermodynamics.GAS?2:phase==FluidThermodynamics.OIL?0:1;if(slot<0)continue;
                    double[] amounts=state.amountsView(slot);
                    for(int c=0;c<count;c++)n[row][c]=fraction*amounts[c];
                    volumes[row]=fraction*state.slotVolume(slot);
                }
                moved=new Stream(mass,n,volumes,state.solids().scale(fraction));
            }
            var zero=zero(count);
            result.add(new PipeTransfer(pipe.id(),rates[i]>=0?moved:zero,rates[i]>=0?zero:moved));
        }
        return List.copyOf(result);
    }

    /** Bounded accumulator: one record per compiled pipe, regardless of accepted substep count. */
    static final class Accumulator {
        private final Map<Long,Mutable> pipes=new LinkedHashMap<>();
        void add(List<PipeTransfer> values,double weight) {
            if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("History quadrature weights must be nonnegative");
            for(var value:values) {
                var entry=pipes.computeIfAbsent(value.pipeId(),id->new Mutable(value.forward.phaseMoles[0].length));
                entry.add(value.forward,0,weight);entry.add(value.reverse,1,weight);
            }
        }
        List<PipeTransfer> snapshot(){return pipes.entrySet().stream().map(e->new PipeTransfer(e.getKey(),e.getValue().stream(0),e.getValue().stream(1))).toList();}
    }
    private static final class Mutable {
        private final double[] mass=new double[2];
        private final SolidInventory.Accumulator[] solids={new SolidInventory.Accumulator(),new SolidInventory.Accumulator()};
        private final double[][][] n;
        private final double[][] volumes=new double[2][3];
        private Mutable(int components){n=new double[2][3][components];}
        private void add(Stream value,int direction,double weight) {
            mass[direction]+=weight*value.massKg;solids[direction].add(value.solids,weight);
            for(int p=0;p<3;p++) {
                volumes[direction][p]+=weight*value.phaseVolumes[p];
                for(int c=0;c<n[direction][p].length;c++)n[direction][p][c]+=weight*value.phaseMoles[p][c];
            }
        }
        private Stream stream(int direction){return new Stream(mass[direction],n[direction],volumes[direction],solids[direction].finish());}
    }
}
