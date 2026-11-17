package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * A tank's outlet lines (plan 4.3 under decision D11): read-only presentation of committed data, built in the view on the
 * engine's schedule. Nothing here solves or flashes: the ports are the committed graph's, the flows and the phase split of
 * what each connection drew are the last interval's pipe transfers (the solver samples every drawn phase into them,
 * {@link PipeTransfer}), and the bottom port's level head is the committed state's condensed mass times {@code g H / V}
 * (decision D9), arithmetic on stored amounts.
 */
public final class TankOutlets {
    private TankOutlets() {}
    /**
     * The outlets of node {@code node} of {@code graph}, one per connection with an end there, in connection order.
     * {@code result} (null: no interval completed) is the last interval's; a connection it has no transfer for reads as no
     * flow. {@code faces} is the tank's connections by face as the compile of {@code graph} recorded them
     * ({@link PhysicalFluidTopology.Compiled#tankFaces()}); a connection without one means the two disagree and is refused.
     */
    public static List<FluidView.Outlet> of(FluidThermodynamics model,PassiveNetwork graph,int node,PassiveIntervalSolver.Result result,
                                            Map<Long,PhysicalFluidTopology.Direction> faces) {
        var tank=graph.reservoirs().get(node);var outlets=new ArrayList<FluidView.Outlet>();
        double[] weights=model.molecularWeights();double seconds=result==null?0:result.advancedSeconds();
        for(var pipe:graph.pipes()) {
            for(int end=0;end<2;end++) {
                if((end==0?pipe.first():pipe.second())!=node)continue;
                var port=end==0?pipe.firstPort():pipe.secondPort();
                PipeTransfer transfer=null;
                if(result!=null&&seconds>0)for(var t:result.pipeTransfers())if(t.pipeId()==pipe.id()){transfer=t;break;}
                double massFlow=0,moleFlow=0;int drawn=FluidView.Outlet.NONE;
                if(transfer!=null) {
                    // Forward is first to second: out of the tank when the tank is the first end.
                    var out=end==0?transfer.forward():transfer.reverse();var in=end==0?transfer.reverse():transfer.forward();
                    massFlow=(out.massKg()-in.massKg())/seconds;moleFlow=(total(out)-total(in))/seconds;
                    if(massFlow>0)drawn=leading(out,weights);
                }
                double head=port==PassiveNetwork.PhasePort.LIQUID||port==PassiveNetwork.PhasePort.OVERFLOW?bottomHead(model,tank):0;
                var face=faces.get(pipe.id());
                if(face==null)throw new IllegalStateException("No compiled face for tank connection "+pipe.id());
                outlets.add(new FluidView.Outlet(pipe.id(),face,port,massFlow,moleFlow,drawn,head));
            }
        }
        return List.copyOf(outlets);
    }
    /** The vessel's geometry and what it gave the atmosphere over the last interval ({@link FluidView.Tank}): the mass of
     * the VENT and SPILL boundary transfers booked at it, over the interval's seconds; none without an interval. */
    public static FluidView.Tank tank(FluidThermodynamics model,PassiveNetwork.Reservoir tank,PassiveIntervalSolver.Result result) {
        var rates=released(model,result,tank.id());
        Double top=tank.empty()?null:tank.state().pressure();
        return new FluidView.Tank(tank.inventory().levelHeight(),tank.inventory().open(),rates[0],rates[1],tank.inventory().volume(),top,top==null?null:top+bottomHead(model,tank),tank.inventory().geometry());
    }
    /** {@link #tank(FluidThermodynamics,PassiveNetwork.Reservoir,PassiveIntervalSolver.Result)} for node {@code node} of
     * {@code graph}, with what its outlets carried over the last slice ({@link Separation.Carried}, a copy of the committed
     * inventory's) for each law that acts on it ({@link Separation#acts}); none where no law acts. */
    public static FluidView.Tank tank(FluidThermodynamics model,PassiveNetwork graph,int node,PassiveIntervalSolver.Result result) {
        var reservoir=graph.reservoirs().get(node);var base=tank(model,reservoir,result);
        var acts=Separation.acts(graph,node);
        if(!acts.any())return base;
        var c=reservoir.inventory().carried();
        var carried=new FluidView.CarryOver(acts.liquids()?c.waterInOil():null,acts.liquids()?c.oilInWater():null,acts.gas()?c.liquidInGas():null);
        return new FluidView.Tank(base.levelHeight(),base.open(),base.ventKgPerSecond(),base.spillKgPerSecond(),base.capacity(),base.topPressure(),base.bottomPressure(),base.geometry(),carried);
    }
    /** The same condensed head used by bottom/overflow hydraulic ports; no flash or process advancement. */
    private static double bottomHead(FluidThermodynamics model,PassiveNetwork.Reservoir tank){
        return tank.empty()?0:PortStreams.portHead(model,tank,PassiveNetwork.PhasePort.LIQUID,tank.state());
    }
    /** The mass rates (kg/s) the VENT and SPILL boundary transfers of {@code result} took to the atmosphere at node
     * {@code nodeId} (at every node when null), over the interval's seconds: {@code [vent, spill]}; zeros without an
     * interval. */
    static double[] released(FluidThermodynamics model,PassiveIntervalSolver.Result result,Long nodeId) {
        double[] weights=model.molecularWeights(),rates=new double[2];double seconds=result==null?0:result.advancedSeconds();
        if(!(seconds>0))return rates;
        for(var b:result.boundaries()) {
            boolean vent=b.kind()==ConservativeTransport.BoundaryTransfer.Kind.VENT;
            if(!vent&&b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.SPILL||nodeId!=null&&b.nodeId()!=nodeId)continue;
            double mass=b.solids().massKg();var n=b.moles();for(int c=0;c<n.length;c++)mass-=n[c]*weights[c];
            rates[vent?0:1]+=mass/seconds;
        }
        rates[0]=Math.max(0,rates[0]);rates[1]=Math.max(0,rates[1]);
        return rates;
    }
    /** The phase that carried the most mass in what a connection drew, or {@link FluidView.Outlet#NONE} if it drew none. */
    static int leading(PipeTransfer.Stream stream,double[] weights) {
        var n=stream.phaseMoles();int leading=FluidView.Outlet.NONE;double largest=0;
        for(int p=0;p<3;p++){double mass=0;for(int c=0;c<n[p].length;c++)mass+=n[p][c]*weights[c];if(mass>largest){largest=mass;leading=p;}}
        return leading;
    }
    private static double total(PipeTransfer.Stream stream){double sum=0;for(var phase:stream.phaseMoles())for(double amount:phase)sum+=amount;return sum;}
}
