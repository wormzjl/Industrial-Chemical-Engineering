package com.wormzjl.icheme.science.fluid.topology;

import com.wormzjl.icheme.science.fluid.network.NetworkReduction;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import java.util.*;

/** Deterministic compilation of persistent block identities; no Minecraft reads or loaded-chunk dependency. */
public final class TopologyCompiler {
    private TopologyCompiler() {}
    public enum Kind { RESERVOIR, PIPE, PUMP, VALVE, GENERATOR, VOID, FILTER, COMPRESSOR, HEAT_SIDE }
    public record Node(long id,Kind kind,double elevation) {
        public Node{Objects.requireNonNull(kind);if(!Double.isFinite(elevation))throw new IllegalArgumentException("Invalid elevation");}
    }
    public record Link(long id,long first,long second,PipeResistance.Geometry geometry) {
        public Link{Objects.requireNonNull(geometry);if(first==second)throw new IllegalArgumentException("Self-connected physical link");}
        long other(long id){if(first==id)return second;if(second==id)return first;throw new IllegalArgumentException("Not an endpoint");}
    }
    public record Segment(long linkId,boolean forward,PipeResistance.Geometry geometry) {}
    public record Run(long first,long second,List<Segment> segments) {
        public Run{segments=List.copyOf(segments);if(segments.isEmpty())throw new IllegalArgumentException("Empty pipe run");}
    }
    public record Compiled(List<Node> retainedNodes,List<Run> runs,Set<Long> deadEndLinks,
                           Set<Long> unanchoredLinks,Set<Long> invalidPumpCycles,List<Set<Long>> islands) {
        public Compiled {
            retainedNodes=List.copyOf(retainedNodes);runs=List.copyOf(runs);deadEndLinks=Set.copyOf(deadEndLinks);
            unanchoredLinks=Set.copyOf(unanchoredLinks);invalidPumpCycles=Set.copyOf(invalidPumpCycles);islands=islands.stream().map(Set::copyOf).toList();
        }
    }
    public static Compiled compile(List<Node> inputNodes,List<Link> inputLinks) {
        return compile(inputNodes,inputLinks,Set.of());
    }
    /** Inline directional fittings retain a pressure node even on an otherwise straight run. */
    public static Compiled compile(List<Node> inputNodes,List<Link> inputLinks,Set<Long> retainedPipes) {
        var nodes=new TreeMap<Long,Node>();var links=new TreeMap<Long,Link>();var adjacent=new HashMap<Long,List<Long>>();
        for(var node:inputNodes){if(nodes.putIfAbsent(node.id,node)!=null)throw new IllegalArgumentException("Duplicate node identity");adjacent.put(node.id,new ArrayList<>());}
        for(long id:retainedPipes)if(!nodes.containsKey(id)||nodes.get(id).kind!=Kind.PIPE)throw new IllegalArgumentException("Only a pipe can be retained as an inline fitting");
        for(var link:inputLinks) {
            if(!nodes.containsKey(link.first)||!nodes.containsKey(link.second)||links.putIfAbsent(link.id,link)!=null)throw new IllegalArgumentException("Invalid graph link");
            adjacent.get(link.first).add(link.id);adjacent.get(link.second).add(link.id);
        }
        for(var neighbors:adjacent.values())neighbors.sort(Long::compare);
        // The network reduction stage (NetworkReduction): step 1 is the graph above, step 2 the dead ends, step 3 the runs,
        // step 4 the islands.
        java.util.function.LongBinaryOperator other=(link,node)->links.get(link).other(node);
        var active=new HashSet<>(links.keySet());
        var dead=NetworkReduction.pruneDeadEnds(adjacent,other,id->destination(nodes.get(id).kind),active);
        var retained=new TreeMap<Long,Node>();
        for(var node:nodes.values())if(node.kind!=Kind.PIPE||retainedPipes.contains(node.id)||adjacent.get(node.id).stream().filter(active::contains).count()!=2)retained.put(node.id,node);
        var visited=new HashSet<Long>();var runs=new ArrayList<Run>();
        for(var start:retained.values())for(long firstLink:adjacent.get(start.id)) {
            if(!active.contains(firstLink)||visited.contains(firstLink))continue;
            long current=start.id,edgeId=firstLink;var segments=new ArrayList<Segment>();
            while(true) {
                var edge=links.get(edgeId);long next=edge.other(current);visited.add(edgeId);
                segments.add(new Segment(edge.id,edge.first==current,edge.geometry));
                if(retained.containsKey(next)) {
                    runs.add(new Run(start.id,next,segments));
                    break;
                }
                long previous=edgeId;current=next;
                edgeId=adjacent.get(current).stream().filter(active::contains).filter(id->id!=previous).findFirst().orElseThrow();
            }
        }
        var unanchored=new TreeSet<>(active);unanchored.removeAll(visited);
        var islands=NetworkReduction.splitAtFixedBoundaries(retained.keySet(),adjacent,other,id->fixed(nodes.get(id).kind),visited);
        return new Compiled(new ArrayList<>(retained.values()),runs,dead,unanchored,invalidPumps(nodes,links,adjacent),islands);
    }
    /** A boundary whose state nothing in the network changes: a generator or a void (a tank's state is its inventory). */
    public static boolean fixed(Kind kind){return kind==Kind.GENERATOR||kind==Kind.VOID;}
    /** Where a branch may lead flow (owner decision D18): a tank, a generator or a void. Every other block only passes it on. */
    public static boolean destination(Kind kind){return kind==Kind.RESERVOIR||fixed(kind);}
    /** Reservoir and boundary ports are disconnected for this structural test; valves remain traversable. A compressor is a
     * mover like the pump and is held to the same rule (plan 3.7). */
    private static Set<Long> invalidPumps(Map<Long,Node> nodes,Map<Long,Link> links,Map<Long,List<Long>> adjacent) {
        var invalid=new TreeSet<Long>();
        for(var pump:nodes.values())if(pump.kind==Kind.PUMP||pump.kind==Kind.COMPRESSOR) {
            var edges=adjacent.get(pump.id);if(edges.size()!=2){invalid.add(pump.id);continue;}
            long start=links.get(edges.get(0)).other(pump.id),target=links.get(edges.get(1)).other(pump.id);
            // Separate ports on one tank are still distinct and permit a tank-buffered recycle.
            if(boundary(nodes.get(start).kind)||boundary(nodes.get(target).kind))continue;
            var seen=new HashSet<Long>();var queue=new ArrayDeque<Long>();queue.add(start);seen.add(pump.id);seen.add(start);
            while(!queue.isEmpty()) {
                long current=queue.remove();if(current==target){invalid.add(pump.id);break;}
                for(long edge:adjacent.get(current)) {
                    long other=links.get(edge).other(current);
                    if(!boundary(nodes.get(other).kind)&&seen.add(other))queue.add(other);
                }
            }
        }
        return invalid;
    }
    private static boolean boundary(Kind kind){return kind==Kind.RESERVOIR||kind==Kind.GENERATOR||kind==Kind.VOID;}
}
