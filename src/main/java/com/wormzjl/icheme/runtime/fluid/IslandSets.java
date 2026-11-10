package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.network.ScheduledTransfer;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import java.util.*;

/**
 * Island compound sets (batch 2026-11-04-refinery-compounds, V2, review option B): an island's model is the network
 * package on the island's own compound list ({@link FluidThermodynamics#on}), in the world list's order, water last on
 * every axis. This class moves an island's arrays from one list to another by compound id. A compound at exactly zero is
 * inert in every sum of the model (the review's section 1, V1), so padding a zero or dropping one is exact:
 * <ul>
 * <li>to a list without some of the compounds (a split's part), every array is cut, the states' molar properties and
 * fugacities kept bit for bit;</li>
 * <li>to a list with more (a merge, a growth), every amount is padded with zeros and each state is evaluated again on the
 * new model from its own temperature, pressure and phase amounts ({@link FluidThermodynamics#state}, the checkpoint's own
 * reading), which gives the arriving compounds their fugacities at infinite dilution and every other number as before.</li>
 * </ul>
 * A nonzero amount of a compound the target list lacks is a remap bug: refused, never dropped.
 */
public final class IslandSets {
    /** The most compounds an island carries besides water (owner decision 2026-11-04: 64 + water). */
    public static final int MAXIMUM_COMPOUNDS=64;
    private IslandSets() {}

    /** Where each entry of {@code from}'s axis lands on {@code to}'s (water last on both), -1 where {@code to} has no slot. */
    static int[] map(FluidThermodynamics from,FluidThermodynamics to) {
        var source=from.compounds();var target=to.compounds();var index=new HashMap<String,Integer>();
        for(int i=0;i<target.size();i++)index.put(target.get(i),i);
        int[] map=new int[source.size()+1];
        for(int i=0;i<source.size();i++)map[i]=index.getOrDefault(source.get(i),-1);
        map[source.size()]=target.size();
        return map;
    }
    private static boolean same(FluidThermodynamics from,FluidThermodynamics to){return from==to||from.compounds().equals(to.compounds());}
    /** {@code to} holds every compound of {@code from}. */
    static boolean covers(FluidThermodynamics to,FluidThermodynamics from){return new HashSet<>(to.compounds()).containsAll(from.compounds());}

    /** Amounts on {@code from}'s axis (length n+1, water last) on {@code to}'s, by id. */
    public static double[] amounts(double[] values,FluidThermodynamics from,FluidThermodynamics to) {
        if(same(from,to))return values.clone();
        return amounts(values,map(from,to),to.componentCount(),"amounts");
    }
    private static double[] amounts(double[] values,int[] map,int width,String what) {
        if(values.length!=map.length)throw new IllegalArgumentException("A "+what+" vector of "+values.length+" entries on an axis of "+map.length);
        var result=new double[width];
        for(int i=0;i<values.length;i++) {
            if(map[i]>=0)result[map[i]]=values[i];
            else if(Double.doubleToRawLongBits(values[i])!=0)throw new IllegalStateException("Island compound set: "+what+" hold "+values[i]+" of a compound the new list does not carry");
        }
        return result;
    }
    /** Hydrocarbon amounts (length n, no water) on {@code to}'s hydrocarbon axis, by id. */
    private static double[] hydrocarbons(double[] values,int[] map,int width) {
        var result=new double[width];
        for(int i=0;i<values.length;i++) {
            if(map[i]>=0)result[map[i]]=values[i];
            else if(Double.doubleToRawLongBits(values[i])!=0)throw new IllegalStateException("Island compound set: a state holds "+values[i]+" of a compound the new list does not carry");
        }
        return result;
    }

    /**
     * {@code s} on {@code to}: cut when {@code to} lacks only compounds it does not hold (its own numbers kept), evaluated
     * again on {@code to} otherwise ({@link FluidThermodynamics#state}, or {@link FluidThermodynamics#solidState} for a state
     * holding only solids).
     */
    public static FluidThermodynamics.State state(FluidThermodynamics.State s,FluidThermodynamics from,FluidThermodynamics to) {
        if(same(from,to))return s;
        int[] map=map(from,to);int width=to.compounds().size();
        var liquid=hydrocarbons(s.liquidView(),map,width);var vapor=hydrocarbons(s.vaporView(),map,width);
        if(covers(from,to)) {
            // A cut: every number of the state stays, the absent compounds' fugacities go with their slots.
            return new FluidThermodynamics.State(s.temperature(),s.pressure(),liquid,vapor,s.waterLiquid(),s.waterVapor(),s.hydrocarbonPartialPressure(),s.waterPartialPressure(),
                    s.volume(),s.enthalpy(),s.internalEnergy(),s.mass(),s.liquidVolume(),s.waterVolume(),s.vaporVolume(),phase(s.liquidProperties(),map,width),phase(s.vaporProperties(),map,width),
                    s.solids(),s.solidMoments(),s.waterCompressibility());
        }
        if(!FluidThermodynamics.holdsFluid(s)&&!s.solids().empty())return to.solidState(s.temperature(),s.pressure(),s.solids());
        return to.state(s.temperature(),s.pressure(),liquid,vapor,s.waterLiquid(),s.waterVapor(),s.hydrocarbonPartialPressure()).withSolids(s.solids());
    }
    private static HydrocarbonModel.Phase phase(HydrocarbonModel.Phase p,int[] map,int width) {
        if(p==null)return null;
        var source=p.logFugacityView();var cut=new double[width];
        for(int i=0;i<source.length;i++)if(map[i]>=0)cut[map[i]]=source[i];
        return new HydrocarbonModel.Phase(p.molarVolume(),p.molarEnthalpy(),p.molarInternalEnergy(),p.volumePressureDerivative(),cut,p.vaporLike());
    }
    /**
     * {@code pool} on {@code to}'s list: a still's head (batch 2026-11-09-batch-distiller, W3) is a slice input on the island's
     * list, so on another list it is a plain pot's until the still plans the next slice; every other pool as it is.
     */
    private static com.wormzjl.icheme.science.fluid.network.Pool onList(com.wormzjl.icheme.science.fluid.network.Pool pool,FluidThermodynamics to) {
        if(pool==null||pool.head()==null||pool.head().components()==to.componentCount())return pool;
        return pool.withHead(com.wormzjl.icheme.science.fluid.network.Pool.Head.plain(to.componentCount()));
    }
    /** A node on {@code to}: its state ({@link #state}) and its inventory's amounts by id; everything else as it is. */
    public static PassiveNetwork.Reservoir node(PassiveNetwork.Reservoir n,FluidThermodynamics from,FluidThermodynamics to) {
        if(same(from,to))return n;
        var i=n.inventory();
        var inventory=new PassiveNetwork.Inventory(i.volume(),amounts(i.moles(),from,to),i.internalEnergy(),i.solids(),i.levelHeight(),i.open(),i.geometry(),onList(i.pool(),to),i.tower());
        return new PassiveNetwork.Reservoir(n.id(),n.elevation(),state(n.state(),from,to),n.kind(),inventory);
    }
    /** A graph on {@code to}: every node ({@link #node}); its connections carry no amounts. */
    public static PassiveNetwork graph(PassiveNetwork g,FluidThermodynamics from,FluidThermodynamics to) {
        if(same(from,to))return g;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>(g.reservoirs().size());
        for(var n:g.reservoirs())nodes.add(node(n,from,to));
        var transfers=new ArrayList<ScheduledTransfer>(g.scheduledTransfers().size());
        for(var t:g.scheduledTransfers()) {
            if(t instanceof ScheduledTransfer.Injection injection)throw new IllegalStateException("A scheduled injection ("+injection.id()+") cannot move to another compound list");
            transfers.add(t);
        }
        return new PassiveNetwork(nodes,g.pipes(),transfers);
    }
    /** A boundary booking on {@code to}. */
    static ConservativeTransport.BoundaryTransfer boundary(ConservativeTransport.BoundaryTransfer b,FluidThermodynamics from,FluidThermodynamics to) {
        return new ConservativeTransport.BoundaryTransfer(b.nodeId(),amounts(b.moles(),from,to),b.totalEnergyJoule(),b.solids(),b.solidDirection(),b.kind());
    }
    /** A pipe history stream on {@code to}. */
    static PipeTransfer.Stream stream(PipeTransfer.Stream s,FluidThermodynamics from,FluidThermodynamics to) {
        var phases=s.phaseMoles();var moved=new double[phases.length][];
        for(int p=0;p<phases.length;p++)moved[p]=amounts(phases[p],from,to);
        return new PipeTransfer.Stream(s.massKg(),moved,s.phaseVolumes(),s.solids());
    }
    /** A solved interval on {@code to}: its graph, its boundary bookings and its pipe histories by id. */
    public static PassiveIntervalSolver.Result result(PassiveIntervalSolver.Result r,FluidThermodynamics from,FluidThermodynamics to) {
        if(same(from,to))return r;
        var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>(r.boundaries().size());for(var b:r.boundaries())boundaries.add(boundary(b,from,to));
        var transfers=new ArrayList<PipeTransfer>(r.pipeTransfers().size());for(var t:r.pipeTransfers())transfers.add(new PipeTransfer(t.pipeId(),stream(t.forward(),from,to),stream(t.reverse(),from,to)));
        return new PassiveIntervalSolver.Result(graph(r.graph(),from,to),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),boundaries,
                r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.endpointFlows(),transfers,r.observations(),r.towers());
    }

    // ---------------- presentation: the world list ----------------

    /**
     * {@code s} padded onto {@code world}'s list for a presentation read: the amounts and fugacities padded with zeros, every
     * other number kept, nothing evaluated. Only what a view reads (amounts, masses, volumes, temperatures, pressures) is
     * meaningful on it; it never enters a solve.
     */
    static FluidThermodynamics.State padded(FluidThermodynamics.State s,FluidThermodynamics from,FluidThermodynamics world) {
        if(same(from,world))return s;
        int[] map=map(from,world);int width=world.compounds().size();
        return new FluidThermodynamics.State(s.temperature(),s.pressure(),hydrocarbons(s.liquidView(),map,width),hydrocarbons(s.vaporView(),map,width),s.waterLiquid(),s.waterVapor(),
                s.hydrocarbonPartialPressure(),s.waterPartialPressure(),s.volume(),s.enthalpy(),s.internalEnergy(),s.mass(),s.liquidVolume(),s.waterVolume(),s.vaporVolume(),
                phase(s.liquidProperties(),map,width),phase(s.vaporProperties(),map,width),s.solids(),s.solidMoments(),s.waterCompressibility());
    }
    /** A node padded onto {@code world}'s list ({@link #padded}): what the ledger books and a view reads, never solved. */
    static PassiveNetwork.Reservoir paddedNode(PassiveNetwork.Reservoir n,FluidThermodynamics from,FluidThermodynamics world) {
        if(same(from,world))return n;
        var i=n.inventory();
        return new PassiveNetwork.Reservoir(n.id(),n.elevation(),padded(n.state(),from,world),n.kind(),
                new PassiveNetwork.Inventory(i.volume(),amounts(i.moles(),from,world),i.internalEnergy(),i.solids(),i.levelHeight(),i.open(),i.geometry(),onList(i.pool(),world),i.tower()));
    }
    private static PassiveNetwork paddedGraph(PassiveNetwork g,FluidThermodynamics from,FluidThermodynamics world) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>(g.reservoirs().size());
        for(var n:g.reservoirs())nodes.add(paddedNode(n,from,world));
        return new PassiveNetwork(nodes,g.pipes(),g.scheduledTransfers());
    }
    /** A node's stock as it was held, and the model it was held on. */
    record Origin(PassiveNetwork.Reservoir node,FluidThermodynamics model) {}
    /**
     * The list a compiled island starts from (owner decisions 2026-11-04): nitrogen (the ambient and the junction
     * placeholder), every compound a node's stock holds (an origin's on the model it was held on, any other node's on the
     * world model it was compiled on) and every crystal of a filter cake. Water is on every list.
     */
    static Set<String> held(PassiveNetwork graph,FluidThermodynamics world,Map<Long,Origin> origins) {
        var set=new HashSet<String>();
        if(world.whole().compounds().contains(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.NITROGEN))set.add(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.NITROGEN);
        for(var n:graph.reservoirs()){if(n.junction())continue;var o=origins.get(n.id());if(o!=null)held(o.node(),o.model(),set);else held(n,world,set);}
        for(var p:graph.pipes())if(p.filter()!=null)crystals(p.filter().captured(),set);
        return set;
    }
    /** {@code graph}, compiled on {@code world}, on {@code own}: each node with an origin from its stock as it was held, every other node cut from the world list. */
    static PassiveNetwork onto(PassiveNetwork graph,FluidThermodynamics world,FluidThermodynamics own,Map<Long,Origin> origins) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(var n:graph.reservoirs()) {
            var o=n.junction()?null:origins.get(n.id());
            if(o==null){nodes.add(node(n,world,own));continue;}
            var moved=node(o.node(),o.model(),own);var held=moved.inventory();
            nodes.add(new PassiveNetwork.Reservoir(n.id(),n.elevation(),moved.state(),n.kind(),n.inventory().with(held.moles(),held.internalEnergy(),held.solids())));
        }
        return new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }
    /**
     * The compounds {@code graph} holds on {@code model}: every nonzero amount of a node's inventory or state, every crystal's
     * compound in a node's or a filter's solids; water never (it is on every list).
     */
    static void held(PassiveNetwork graph,FluidThermodynamics model,Set<String> into) {
        for(var n:graph.reservoirs())held(n,model,into);
        for(var p:graph.pipes())if(p.filter()!=null)crystals(p.filter().captured(),into);
    }
    static void held(PassiveNetwork.Reservoir n,FluidThermodynamics model,Set<String> into) {
        var names=model.compounds();var moles=n.inventory().moles();var l=n.state().liquidView();var v=n.state().vaporView();
        for(int i=0;i<names.size();i++)if(moles[i]!=0||l[i]!=0||v[i]!=0)into.add(names.get(i));
        crystals(n.inventory().solids(),into);crystals(n.state().solids(),into);
    }
    static void crystals(com.wormzjl.icheme.science.fluid.state.SolidInventory solids,Set<String> into) {
        for(var p:solids.populations()){var crystal=p.material().crystal();if(crystal!=null&&!crystal.component().equals("Water"))into.add(crystal.component());}
    }
    /**
     * An island snapshot on the world list for presentation (V2 stage 5): the views, sight glasses, tank displays, heat
     * presentation and instruments read every island on the world list, so the wire format (fluid-17) and the pickers do not
     * change. The island itself never sees it.
     */
    static IslandCoordinator.Snapshot presented(IslandCoordinator.Snapshot s,FluidThermodynamics from,FluidThermodynamics world) {
        if(same(from,world))return s;
        var graph=paddedGraph(s.graph(),from,world);
        var result=s.lastResult().map(r->{
            var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>(r.boundaries().size());for(var b:r.boundaries())boundaries.add(boundary(b,from,world));
            var transfers=new ArrayList<PipeTransfer>(r.pipeTransfers().size());for(var t:r.pipeTransfers())transfers.add(new PipeTransfer(t.pipeId(),stream(t.forward(),from,world),stream(t.reverse(),from,world)));
            return new PassiveIntervalSolver.Result(paddedGraph(r.graph(),from,world),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),boundaries,
                    r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.endpointFlows(),transfers,r.observations(),r.towers());
        });
        return new IslandCoordinator.Snapshot(s.id(),s.revision(),graph,s.clock(),result,s.status(),s.fences(),s.certificate(),s.payloadGeneration());
    }
}
