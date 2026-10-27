package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.network.InlineFilter;

/** Read-only presentation of a committed interval; a null state denotes a zero-holdup pipe segment. */
public record FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,
                        double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                        double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,
                        List<Outlet> outlets,Tank tank,Heat heat) {
    /** A tank has six faces and each face one neighbour, so it has at most six connections. */
    public static final int MAXIMUM_OUTLETS=6;
    /** Source-side convenience for devices without pipe inspection, not a wire-format fallback. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter){
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,null,List.of(),null,null);
    }
    public FluidView withPipeInfo(PipeInfo info){
        return withPipeInfo(info,intervalSeconds);
    }
    public FluidView withPipeInfo(PipeInfo info,double seconds){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,seconds,pipeRoutes,filter,info,outlets,tank,heat);
    }
    public FluidView withOutlets(List<Outlet> list){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,list,tank,heat);
    }
    public FluidView withTank(Tank value){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,value,heat);
    }
    /**
     * A vessel's own geometry and what it gave the atmosphere over the last completed interval (batch
     * 2026-10-21-floating-roof-tanks, {@code fluid-11}): the height its level head stands over (its fill height, for an
     * open tank), whether it is open under a floating roof, and its vent and spill (kg/s, from its VENT and SPILL boundary
     * transfers). fluid-14 adds capacity and top/bottom absolute pressure. Both pressures are null only while empty;
     * capacity/geometry are still known. Null Tank for a node that is not a vessel.
     */
    public record Tank(double levelHeight,boolean open,double ventKgPerSecond,double spillKgPerSecond,
                       double capacity,Double topPressure,Double bottomPressure,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry) {
        public Tank(double levelHeight,boolean open,double ventKgPerSecond,double spillKgPerSecond,double capacity,Double topPressure,Double bottomPressure){this(levelHeight,open,ventKgPerSecond,spillKgPerSecond,capacity,topPressure,bottomPressure,com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(capacity,levelHeight));}
        public Tank {
            java.util.Objects.requireNonNull(geometry,"Invalid tank presentation: missing geometry");if(Math.abs(geometry.capacity()-capacity)>capacity*1e-8||Math.abs(geometry.height()-levelHeight)>levelHeight*1e-8)throw new IllegalArgumentException("Invalid tank presentation: geometry mismatch");
            if(!(levelHeight>0)||!Double.isFinite(levelHeight)||!(ventKgPerSecond>=0)||!Double.isFinite(ventKgPerSecond)||!(spillKgPerSecond>=0)||!Double.isFinite(spillKgPerSecond)||!(capacity>0)||!Double.isFinite(capacity))
                throw new IllegalArgumentException("Invalid tank presentation");
            if((topPressure==null)!=(bottomPressure==null)||topPressure!=null&&(!Double.isFinite(topPressure)||!(topPressure>0)||!Double.isFinite(bottomPressure)||bottomPressure<topPressure))
                throw new IllegalArgumentException("Invalid tank pressures");
        }
        /** The liquid's height over the bottom, m: its condensed volume (both liquids and the solids) over the footprint
         * {@code capacity / levelHeight}. */
        public double level(State state){return state==null?0:geometry.heightAt(state.phaseVolumes()[0]+state.phaseVolumes()[1]+state.solids().volume());}
    }
    /**
     * One connection of a tank, as its page lists it (plan 4.3 under decision D11): the port the connection's end at the
     * tank compiled to ({@code VAPOR} top, {@code BULK} side, {@code LIQUID} bottom), the net flow out of the tank through
     * it over the last completed interval (kg/s and mol/s; negative is inflow), the phase that led what it drew ({@link
     * #NONE} when it drew nothing, else the phase index of {@link PipeTransfer.Stream}: 0 hydrocarbon liquid, 1 free
     * water, 2 vapour), and, at a bottom port, the level head of the committed state (Pa; 0 elsewhere). {@code face} is
     * the face of the tank the connection leaves by, as the compile that made the graph recorded it (the page names each
     * row with it, so two side connections can be told apart); required on the wire since fluid-8. A one-block tank's
     * port is its face's (top VAPOR, bottom LIQUID, sides BULK); a vessel's nozzle states its own class whichever way it
     * faces (a drum's, a floating-roof tank's bottom LIQUID and top OVERFLOW nozzles on its front, a sphere's bottom
     * outlet routed to its front).
     */
    public record Outlet(long pipeId,PhysicalFluidTopology.Direction face,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort port,double massFlow,double moleFlow,int drawn,double head) {
        public static final int NONE=-1;
        public Outlet {
            Objects.requireNonNull(face,"Tank outlet without its face");Objects.requireNonNull(port);
            if(!Double.isFinite(massFlow)||!Double.isFinite(moleFlow)||drawn<NONE||drawn>2||!Double.isFinite(head)||head<0)throw new IllegalArgumentException("Invalid tank outlet");
        }
    }
    /** Nonnegative interval transport; a junction counts outgoing material once. A void aggregates incoming rates
     * from independent islands as one second of transport (intervalSeconds = 1), never its dummy boundary inventory. */
    public record PipeInfo(PipeTransfer.Stream contents,List<PipeConnection> connections,boolean junction,boolean changedDirection){
        public PipeInfo {
            Objects.requireNonNull(contents);connections=List.copyOf(connections);
            if(connections.size()>12||connections.stream().map(PipeConnection::pipeId).distinct().count()!=connections.size())
                throw new IllegalArgumentException("Invalid pipe inspection connections");
        }
        public static PipeInfo empty(int components){return new PipeInfo(new PipeTransfer.Stream(0,new double[3][components],new double[3]),List.of(),false,false);}
    }
    /** Speed is the previous-five-online-seconds bulk speed at this block's bore; gradient is endpoint pressure difference / path length. */
    public record PipeConnection(long pipeId,double massRateKgPerSecond,double velocityMetresPerSecond,double pressureDropPascalPerMetre,boolean reverse){
        public PipeConnection {
            for(double value:new double[]{massRateKgPerSecond,velocityMetresPerSecond,pressureDropPascalPerMetre})
                if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid pipe inspection metric");
        }
    }
    public record PipeRoute(long pipeId,String first,String second) {}
    /**
     * A unit's heat side over the last completed interval (batch 2026-10-27-heat-exchanger-design): which unit and side it
     * is, the temperature at its outlet nozzle's junction (K), the heat its stream took up (W, negative where it was
     * cooled, from the interval's {@code HEAT} boundaries), and an air cooler's fans: their share of full speed and their
     * power (W), null for a side without fans.
     */
    public record Heat(String unit,String side,double outletTemperature,double heatWatt,Double fans,Double fanPowerWatt) {
        public Heat {
            Objects.requireNonNull(unit);Objects.requireNonNull(side);
            if(!(outletTemperature>0)||!Double.isFinite(outletTemperature)||!Double.isFinite(heatWatt)||(fans==null)!=(fanPowerWatt==null)
                    ||fans!=null&&!(fans>=0&&fans<=1&&fanPowerWatt>=0&&Double.isFinite(fanPowerWatt)))throw new IllegalArgumentException("Invalid heat side presentation");
        }
    }
    /** Every field but the heat side's: a view of a device that is not one. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                     double intervalSeconds,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,List<Outlet> outlets,Tank tank) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,null);
    }
    public FluidView withHeat(Heat value){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,intervalSeconds,pipeRoutes,filter,pipeInfo,outlets,tank,value);
    }
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,null,0,List.of(),null);
    }
    public FluidView {
        Objects.requireNonNull(status);pipeHistory=List.copyOf(pipeHistory);pipeRoutes=List.copyOf(pipeRoutes);outlets=List.copyOf(outlets);
        if(outlets.size()>MAXIMUM_OUTLETS)throw new IllegalArgumentException("Too many tank outlets");
    }
    /** {@code volume} is what the node holds; {@code capacity} the volume its wall holds (decision D54: a vessel is a bag
     * whose contents may be smaller than its capacity), the contents' own volume for a node with no wall. */
    public record State(double pressure,double temperature,double volume,double mass,double[] phaseVolumes,double[][] phaseMoles,SolidInventory solids,double capacity) {
        public State {Objects.requireNonNull(solids);phaseVolumes=phaseVolumes.clone();phaseMoles=Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);
            if(!(capacity>0)||!Double.isFinite(capacity))throw new IllegalArgumentException("Invalid capacity");}
        @Override public double[] phaseVolumes(){return phaseVolumes.clone();}
        @Override public double[][] phaseMoles(){return Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);}
        /** The share of the capacity the contents take, 1 for a full (taut) vessel. */
        public double fill(){return Math.min(1,volume/capacity);}
        static State from(FluidThermodynamics.State s,double capacity) {
            double[][] n=new double[3][s.liquid().length+1];var l=s.liquid();var v=s.vapor();
            for(int c=0;c<l.length;c++){n[0][c]=l[c];n[2][c]=v[c];}n[1][l.length]=s.waterLiquid();n[2][l.length]=s.waterVapor();
            return new State(s.pressure(),s.temperature(),s.volume(),s.mass(),new double[]{s.liquidVolume(),s.waterVolume(),s.vaporVolume()},n,s.solids(),capacity);
        }
    }
    public interface Receiver {
        long fluidIdentity();
        void acceptFluidView(FluidView view);
    }
}
