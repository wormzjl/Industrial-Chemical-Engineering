package com.wormzjl.icheme.science.fluid.network;

import java.util.*;

/**
 * Which regulation rows a connection's junction mass balances leave free, without presentation data. Flow valves are
 * settled by {@link #standing} and bounded by the set-flow movers in their series ({@link #moverBounds}), pressure valves
 * on one node by {@link #dependent}.
 */
public final class ControlConstraints {
    private ControlConstraints(){}
    /** A plain series: the flow valves that junction balances make one flow, and that flow's row. */
    private record Line(double[] row,List<Integer> valves){}
    /** The pressure valves that share a node: each would set that node, so they hold their accepted openings. */
    public static BitSet dependent(PassiveNetwork graph){
        var result=new BitSet(graph.pipes().size());var shared=new HashMap<Integer,List<Integer>>();
        for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(p.control() instanceof FlowControl.Valve valve&&!valve.bare()&&valve.pressureControl())shared.computeIfAbsent(p.first(),ignored->new ArrayList<>()).add(e);}
        for(var group:shared.values())if(group.size()>1)for(int e:group)result.set(e);
        return result;
    }
    /**
     * The flow valves that stand fully open (owner decision 2026-11-02, "Lower target wins"). Flow valves in series carry one
     * flow, so the lowest target regulates and the others stand open; a target of 0 is the lowest, and closes the line. The
     * series' rows then go in ranked: a series holding a column's outlet port, one holding an inlet port, the others, each
     * ascending by target. A row its predecessors already fix stands open too, and only that one, so in a branched group a
     * port keeps its row. An outlet ranks first because only its valve bounds what its account gives; an inlet given more
     * only holds more. Actual-volume rows depend on the local density, so they fix no constant mass flow and stay out.
     */
    public static BitSet standing(PassiveNetwork graph){
        int edges=graph.pipes().size();var result=new BitSet(edges);var valves=new ArrayList<Integer>();
        for(int e=0;e<edges;e++)if(graph.pipes().get(e).control() instanceof FlowControl.ControlValve v&&!v.bare()&&v.source().mass())valves.add(e);
        if(valves.size()<2)return result;
        var basis=junctionBasis(graph);
        var lines=new ArrayList<Line>();
        for(int e:valves){
            double[] row=reduced(basis,unit(edges,e));Line line=null;
            for(var l:lines)if(same(l.row(),row)){line=l;break;}
            if(line==null)lines.add(line=new Line(row,new ArrayList<>()));
            line.valves().add(e);
        }
        Comparator<Integer> lowest=Comparator.comparingDouble(e->target(graph,e));lowest=lowest.thenComparingInt(e->rank(graph,e)).thenComparingInt(e->e);
        var regulators=new ArrayList<Integer>();var ranks=new HashMap<Integer,Integer>();
        for(var line:lines){
            int regulator=Collections.min(line.valves(),lowest);regulators.add(regulator);
            ranks.put(regulator,line.valves().stream().mapToInt(e->rank(graph,e)).min().orElseThrow());
            for(int e:line.valves())if(e!=regulator)result.set(e);
        }
        regulators.sort(Comparator.<Integer>comparingInt(ranks::get).thenComparing(lowest));
        for(int e:regulators)if(!insert(basis,unit(edges,e)))result.set(e);
        return result;
    }
    /**
     * What each connection's series passes at most on its movers' laws (owner decision D11, A1): the lowest
     * {@code setFlow} (kg/s, of the mover's connection) of a set-flow pump or compressor that junction balances make one
     * flow with it; positive infinity where there is none. Form F2e delivers the displacement less its slip at every
     * positive rise, so a flow valve whose target lies above this bound cannot regulate. A machine on its curve passes
     * more than its rated flow and bounds nothing.
     */
    public static double[] moverBounds(PassiveNetwork graph,java.util.function.IntToDoubleFunction setFlow){
        int edges=graph.pipes().size();double[] bound=new double[edges];Arrays.fill(bound,Double.POSITIVE_INFINITY);
        var movers=new ArrayList<Integer>();
        for(int e=0;e<edges;e++)if(graph.pipes().get(e).control() instanceof FlowControl.Mover m&&!(m instanceof FlowControl.Machine)&&m.targetVolumeFlow()>0)movers.add(e);
        if(movers.isEmpty())return bound;
        var basis=junctionBasis(graph);var rows=new double[movers.size()][];var flows=new double[movers.size()];
        for(int k=0;k<rows.length;k++){rows[k]=reduced(basis,unit(edges,movers.get(k)));flows[k]=setFlow.applyAsDouble(movers.get(k));}
        for(int e=0;e<edges;e++){
            if(!(graph.pipes().get(e).control() instanceof FlowControl.Valve v)||v.bare()||v.pressureControl())continue;
            // A row the balances take out whole is a flow they fix at zero, one with nothing in series.
            double[] row=reduced(basis,unit(edges,e));if(same(row,new double[edges]))continue;
            for(int k=0;k<rows.length;k++)if(same(rows[k],row))bound[e]=Math.min(bound[e],flows[k]);
        }
        return bound;
    }
    /** The junction mass balances, eliminated: each junction's row of its connections' flows, in by its second end. */
    private static double[][] junctionBasis(PassiveNetwork graph){
        int edges=graph.pipes().size();var basis=new double[edges][];
        for(int node=0;node<graph.reservoirs().size();node++)if(graph.reservoirs().get(node).junction()){
            double[] row=new double[edges];
            for(int e=0;e<edges;e++){var p=graph.pipes().get(e);if(p.first()==node)row[e]--;if(p.second()==node)row[e]++;}
            insert(basis,row);
        }
        return basis;
    }
    private static double target(PassiveNetwork graph,int e){return ((FlowControl.ControlValve)graph.pipes().get(e).control()).massTarget();}
    /** 0 a column's outlet valve (a generator feeds the node it stands on), 1 its inlet valve, 2 any other. */
    private static int rank(PassiveNetwork graph,int e){
        var pipe=graph.pipes().get(e);
        if(!(pipe.control() instanceof FlowControl.ControlValve v)||!PortPlan.COLUMN_SENSOR.equals(v.sensor()))return 2;
        for(var p:graph.pipes())if(p.second()==pipe.first()&&graph.reservoirs().get(p.first()).kind()==PassiveNetwork.NodeKind.GENERATOR)return 0;
        return 1;
    }
    private static double[] unit(int edges,int e){double[] row=new double[edges];row[e]=1;return row;}
    /** Puts {@code row} in the basis. False: the basis already fixes it. */
    private static boolean insert(double[][] basis,double[] row){
        for(int col=0;col<row.length;col++){
            if(Math.abs(row[col])<1e-10)continue;
            if(basis[col]==null){double scale=row[col];for(int j=col;j<row.length;j++)row[j]/=scale;basis[col]=row;return true;}
            double factor=row[col];for(int j=col;j<row.length;j++)row[j]-=factor*basis[col][j];
        }
        return false;
    }
    /** {@code row} with the basis taken out, its sign fixed: rows equal here carry one flow. */
    private static double[] reduced(double[][] basis,double[] row){
        for(int col=0;col<row.length;col++)if(basis[col]!=null&&Math.abs(row[col])>=1e-10){double factor=row[col];for(int j=col;j<row.length;j++)row[j]-=factor*basis[col][j];}
        for(double value:row)if(Math.abs(value)>=1e-10){if(value<0)for(int j=0;j<row.length;j++)row[j]=-row[j];break;}
        return row;
    }
    private static boolean same(double[] a,double[] b){for(int j=0;j<a.length;j++)if(Math.abs(a[j]-b[j])>=1e-10)return false;return true;}
}
