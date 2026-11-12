package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import java.util.function.BiConsumer;

import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.GAS;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS;

/**
 * The inlet phase check of a pump or compressor (decisions D1, D2, D6): what the device's suction line delivers,
 * measured on a committed state, and whether the device is refused ({@link FlowControl.Mode#INLET_WRONG_PHASE}) for the
 * slice that starts there: a dry suction, or a compressor's solids. The measures also set a centrifugal machine's laws for
 * a solve (request 11, owner decisions D19 and D20): a pump's head reads the vapour share, a compressor's efficiency the
 * condensed share ({@code PassiveStepSolver.readSuctions}); gas at a pump and liquid at a compressor refuse nothing. Every method is a pure function of the graph, the slice duration and, for the decision, the
 * committed endpoint modes, so the solver and the device view ({@code FluidWorldAuthority}) read the same answer.
 *
 * <p>The supply walk: from the device edge's first node, a vessel, generator, void or port there supplies the stream of
 * that end; a junction is walked through (the device's own junction if it has exactly one other, passive, connection,
 * and every following junction with two connections neither of which carries an actuator), and the walk ends at the
 * first node that is not such a junction: a vessel, generator, void or port supplies the stream of the last link's end
 * there, a junction its state mixture. So the check reads the source, not the device's own junction: flashing at the
 * inlet does not refuse a device drawing a saturated liquid, it only lowers the suction density. What a phase port
 * supplies (decision A37) is the priority draw of decision D11 at the device's target volume flow over the slice: the
 * target volume {@code Q T} is filled from the port's priority order, each phase up to its share of the vessel
 * ({@code V_phase / N}), the last phase the vessel holds with the rest, so a bottom port holding less liquid than the
 * device would move in one slice reads the gas it would break through to; with no target volume it is the port's
 * leading phase. A BULK end supplies the node's bulk. Measures: for a pump the vapour share of the supply's fluid
 * volume (solids excluded); for a compressor the condensed share of its mass and the largest solid population's share
 * of its volume.</p>
 *
 * <p>A dry suction (owner decision D58, A1): a pump or compressor with nothing at its suction runs dry. What can feed
 * the suction is what stands upstream of it ({@link #held}): the device's first node if it is not a junction, else
 * every node reached from it through junctions by a connection that may carry flow towards the device
 * ({@link PassiveStepSolver#boundaryAllowed}). A generator always feeds; a void, a dry end and a junction never do; a
 * vessel feeds by the share of its capacity its contents fill. The suction is dry when the largest such share is below
 * {@link #DRY_REFUSE}, the share a drained port's throttle lands on, and a device refused on it runs again once that
 * share reaches {@link #DRY_RESUME}. A slice decides on its committed graph with the transport closures read as open
 * (owner decision D33): a closure is the solid events' to decide again, so it never leaves a suction dry by itself.</p>
 */
public final class InletPhase {
    private InletPhase() {}

    /** The share of its capacity below which what feeds a suction leaves it dry (the port throttle's landing share, A4). */
    public static final double DRY_REFUSE=.005;
    /** The share of its capacity what feeds a dry suction must reach before the device runs again (the port opening share, A4). */
    public static final double DRY_RESUME=.01;

    /**
     * What a device's suction line delivers: the node the walk ended at ({@code node} its index, {@code nodeId} its id),
     * the end's port there (BULK for a junction mixture and for a generator), the three measures, and {@code heldShare},
     * the largest share of its capacity held by what can feed the suction ({@link #held}; infinite for a generator).
     */
    public record Supply(int node,long nodeId,PassiveNetwork.PhasePort port,double vapourVolumeShare,double condensedMassShare,double largestSolidVolumeFraction,double heldShare) {
        /** Whether the suction is dry for the next slice, given whether the device stood refused at the committed slice end. */
        public boolean dry(boolean refused){return heldShare<DRY_REFUSE||refused&&!(heldShare>=DRY_RESUME);}
    }

    /**
     * The walk from a device edge's first node to what supplies it: {@code links} the connections crossed, in walk
     * order, with {@code donors} the node each is drawn from (the end away from the device); {@code node} the node the
     * walk ended at and {@code via} the link it arrived through, -1 when it ended at the device's own first node.
     */
    record Walk(int[] links,int[] donors,int node,int via) {}

    /** The suction walk of connection {@code edge}; see the class comment. */
    static Walk walk(PassiveNetwork graph,int edge) {
        int nodes=graph.reservoirs().size();int[] degree=new int[nodes];boolean[] actuated=new boolean[nodes];
        for(var pipe:graph.pipes()) {
            degree[pipe.first()]++;degree[pipe.second()]++;
            if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
        }
        var links=new ArrayList<Integer>();var donors=new ArrayList<Integer>();
        int at=graph.pipes().get(edge).first(),from=edge,via=-1;
        boolean[] seen=new boolean[nodes];seen[at]=true;
        while(graph.reservoirs().get(at).junction()&&degree[at]==2) {
            // The device's own junction is crossed whatever its actuator; every later one only if no actuator touches it.
            if(via>=0&&actuated[at])break;
            int next=-1;
            for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(e!=from&&(p.first()==at||p.second()==at)){next=e;break;}}
            if(next<0)break;
            var link=graph.pipes().get(next);
            if(!(link.control() instanceof FlowControl.Passive))break;
            int beyond=link.first()==at?link.second():link.first();
            if(beyond==at||seen[beyond])break;
            links.add(next);donors.add(beyond);seen[beyond]=true;at=beyond;from=next;via=next;
        }
        return new Walk(links.stream().mapToInt(Integer::intValue).toArray(),donors.stream().mapToInt(Integer::intValue).toArray(),at,via);
    }

    /**
     * What connection {@code edge}'s suction line delivers on the graph's states, for a device whose target is
     * {@code targetVolumeFlow} (m3/s) over a slice of {@code duration} seconds (zero or less: no slice, the leading
     * phase of a phase port).
     */
    public static Supply supply(FluidThermodynamics model,PassiveNetwork graph,int edge,double targetVolumeFlow,double duration) {
        var source=source(model,graph,edge,targetVolumeFlow,duration);var node=graph.reservoirs().get(source.node());
        return measure(model,node.state(),source.drawn(),source.node(),node.id(),source.port(),held(graph,edge));
    }
    /**
     * {@link #supply} with the stream it measures: the supplier's state, and the drawn phases' fluid amounts (mol,
     * water last) and fluid enthalpy (J) together, its solids apart; at a BULK end the supplier's whole fluid.
     * {@code condensed}: whether the stream holds a liquid phase (a BULK end: whether the supplier holds one).
     */
    public record Drawn(Supply supply,FluidThermodynamics.State state,double[] amounts,double enthalpy,boolean condensed) {}
    /** {@link #supply} and its stream ({@link Drawn}), on the same arguments. */
    public static Drawn drawn(FluidThermodynamics model,PassiveNetwork graph,int edge,double targetVolumeFlow,double duration) {
        var source=source(model,graph,edge,targetVolumeFlow,duration);var node=graph.reservoirs().get(source.node());
        var supply=measure(model,node.state(),source.drawn(),source.node(),node.id(),source.port(),held(graph,edge));
        return stream(supply,node.state(),source.drawn());
    }
    /** What a device drawing phase volumes {@code drawn} (m3 by phase; null for the bulk) from {@code state} receives, as
     * {@link #drawn} states it for a node that is no network's (never dry). */
    static Drawn of(FluidThermodynamics model,FluidThermodynamics.State state,double[] drawn) {
        var port=drawn==null?PassiveNetwork.PhasePort.BULK:PassiveNetwork.PhasePort.LIQUID;
        return stream(measure(model,state,drawn,-1,-1,port,Double.POSITIVE_INFINITY),state,drawn);
    }
    /** The stream of {@code state} that phase volumes {@code drawn} (m3 by phase, {@link FluidThermodynamics#phaseVolume};
     * null for the bulk) take, as {@link Drawn} states it. */
    private static Drawn stream(Supply supply,FluidThermodynamics.State state,double[] drawn) {
        if(drawn==null)return new Drawn(supply,state,PhaseLayout.totalAmounts(state),state.fluidEnthalpy(),FluidThermodynamics.holdsLiquid(state));
        double[] amounts=new double[state.componentCount()];double enthalpy=0;
        for(int phase=0;phase<3;phase++) {
            if(!(drawn[phase]>0))continue;
            int slot=state.slot(phase);double volume=FluidThermodynamics.phaseVolume(state,phase);
            if(slot<0||!(volume>0)||state.phase(slot)==null)continue;
            double share=drawn[phase]/volume,moles=0;double[] own=state.amountsView(slot);
            for(int c=0;c<amounts.length;c++){amounts[c]+=share*own[c];moles+=share*own[c];}
            enthalpy+=moles*state.phase(slot).molarEnthalpy();
        }
        return new Drawn(supply,state,amounts,enthalpy,drawn[OIL]>0||drawn[AQUEOUS]>0);
    }
    /**
     * The density (kg/m3) of what connection {@code edge}'s suction line delivers, {@link #supply}'s stream on the same
     * arguments: the drawn phase streams' mass over their volume, each at the density a connection's end draws it with
     * ({@code PassiveStepSolver.endDensity}), the bulk's at a BULK end. What a set-flow pump's relief rise reads (owner
     * decision D11, A2).
     */
    public static double density(FluidThermodynamics model,PassiveNetwork graph,int edge,double targetVolumeFlow,double duration) {
        var source=source(model,graph,edge,targetVolumeFlow,duration);var state=graph.reservoirs().get(source.node()).state();
        double mass=0,volume=0;
        if(source.drawn()!=null)for(int phase=0;phase<3;phase++)if(source.drawn()[phase]>0){mass+=source.drawn()[phase]*PassiveStepSolver.phaseDensity(model,state,phase);volume+=source.drawn()[phase];}
        return volume>0?mass/volume:state.mass()/state.volume();
    }
    /**
     * The density (kg/m3) of the liquid in what connection {@code edge}'s suction line delivers, {@link #supply}'s stream on
     * the same arguments: its hydrocarbon liquid and free water streams' mass over their volume, at the densities a
     * connection's end draws them with; where the stream holds no liquid, the liquid of the node it is drawn from; NaN where
     * that holds none. What a pump's NPSH floor reads ({@code PassiveStepSolver.readSuctions}, owner decision D20 Q11 A).
     */
    public static double liquidDensity(FluidThermodynamics model,PassiveNetwork graph,int edge,double targetVolumeFlow,double duration) {
        var source=source(model,graph,edge,targetVolumeFlow,duration);var state=graph.reservoirs().get(source.node()).state();
        double mass=0,volume=0;
        if(source.drawn()!=null)for(int phase:new int[]{OIL,AQUEOUS})if(source.drawn()[phase]>0){mass+=source.drawn()[phase]*PassiveStepSolver.phaseDensity(model,state,phase);volume+=source.drawn()[phase];}
        if(!(volume>0))for(int phase:new int[]{OIL,AQUEOUS}){double v=FluidThermodynamics.phaseVolume(state,phase);if(v>0){mass+=v*PassiveStepSolver.phaseDensity(model,state,phase);volume+=v;}}
        return volume>0?mass/volume:Double.NaN;
    }
    /** Where a suction walk ends: the node, its end's port there (BULK at a junction) and the phase stream volumes drawn
     * through it ({@link #draw}; null for the bulk). */
    private record Source(int node,PassiveNetwork.PhasePort port,double[] drawn) {}
    private static Source source(FluidThermodynamics model,PassiveNetwork graph,int edge,double targetVolumeFlow,double duration) {
        var walk=walk(graph,edge);var node=graph.reservoirs().get(walk.node());
        PassiveNetwork.PhasePort port;
        if(node.junction())port=PassiveNetwork.PhasePort.BULK;
        else port=walk.via()<0?graph.pipes().get(edge).firstPort():graph.pipes().get(walk.via()).portAt(walk.node());
        return new Source(walk.node(),port,draw(model,graph,walk.node(),port,targetVolumeFlow*Math.max(duration,0)));
    }

    /**
     * The largest share of its capacity held by what can feed connection {@code edge}'s suction (see the class comment):
     * the device's first node through its first port, or, from a first node that is a junction, every node reached through
     * junctions by a connection that may carry flow towards it. Infinite when a generator feeds it; zero when nothing does.
     */
    static double held(PassiveNetwork graph,int edge) {
        var device=graph.pipes().get(edge);var first=graph.reservoirs().get(device.first());
        if(!first.junction())return heldShare(first,device.firstPort());
        double[] best={0};feeders(graph,device.first(),edge,(node,port)->best[0]=Math.max(best[0],heldShare(node,port)));
        return best[0];
    }
    /**
     * Whether nothing that can feed connection {@code edge} ({@link #held}'s nodes) holds stock past this pass: each is a
     * dry end, a void or a junction ({@link #heldShare} zero), or a vessel {@code draining} names (one the pass runs dry
     * within its step, decision D54). A generator never is. What reaches the device then is only what passes through those
     * nodes within the step (feedback FEED_STEP_HOLD_REVIEW.md, owner option 1).
     */
    static boolean starved(PassiveNetwork graph,int edge,java.util.function.IntPredicate draining) {
        var device=graph.pipes().get(edge);var first=graph.reservoirs().get(device.first());
        if(!first.junction())return heldShare(first,device.firstPort())==0||draining.test(device.first());
        boolean[] fed={false};
        feeders(graph,device.first(),edge,(node,port)->{if(!(heldShare(node,port)==0||node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&draining.test(graph.reservoirs().indexOf(node))))fed[0]=true;});
        return !fed[0];
    }
    /** Every node reached from junction {@code start} through junctions by a connection that may carry flow towards it, with its port; connection {@code edge} apart. */
    private static void feeders(PassiveNetwork graph,int start,int edge,BiConsumer<PassiveNetwork.Reservoir,PassiveNetwork.PhasePort> visit) {
        boolean[] seen=new boolean[graph.reservoirs().size()];var queue=new ArrayDeque<Integer>();
        seen[start]=true;queue.add(start);
        while(!queue.isEmpty()) {
            int at=queue.remove();
            for(int e=0;e<graph.pipes().size();e++) {
                var pipe=graph.pipes().get(e);if(e==edge||pipe.first()==pipe.second()||pipe.first()!=at&&pipe.second()!=at)continue;
                int from=pipe.first()==at?pipe.second():pipe.first();double towards=pipe.second()==at?1:-1;
                // A pump, compressor or valve passes only from its first end to its second.
                if(!(pipe.control() instanceof FlowControl.Passive)&&towards<0||!PassiveStepSolver.boundaryAllowed(graph,pipe,towards))continue;
                var node=graph.reservoirs().get(from);
                if(!node.junction())visit.accept(node,pipe.portAt(from));
                else if(!seen[from]){seen[from]=true;queue.add(from);}
            }
        }
    }
    /** The share of its capacity {@code node} feeds through {@code port}: a vessel's contents over its capacity (zero at a
     * dry end), infinite for a generator, zero for a void or a junction. */
    private static double heldShare(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port) {
        return switch(node.kind()) {
            case GENERATOR->Double.POSITIVE_INFINITY;
            case VOID,JUNCTION->0;
            case RESERVOIR,PORT->PassiveStepSolver.dryEnd(node,port)?0:PhaseLayout.bagVolume(node.state(),node.inventory().wallCapacity())/node.inventory().volume();
        };
    }

    /**
     * The volume (m3) of each phase stream ({@link FluidThermodynamics#phaseVolume}, solids with their liquid) drawn from
     * {@code node} through {@code port} for a target volume {@code volume}, the port's priority fill shared among the
     * vessel's phase-port outlets ({@link PhaseDraw#fill}); null for the bulk (a BULK end, or a vessel holding none of the
     * phases). With no target volume, the leading phase alone (a unit volume).
     */
    private static double[] draw(FluidThermodynamics model,PassiveNetwork graph,int node,PassiveNetwork.PhasePort port,double volume) {
        if(port==PassiveNetwork.PhasePort.BULK)return null;
        var state=graph.reservoirs().get(node).state();
        if(!(volume>0)) {
            if(PhaseDraw.mixedLiquids(state,port)){var sample=new double[3];sample[FluidThermodynamics.OIL]=.5;sample[FluidThermodynamics.AQUEOUS]=.5;return sample;}
            int leading=PhaseDraw.leading(model,state,port);if(leading<0)return null;
            double[] drawn=new double[3];drawn[leading]=1;return drawn;
        }
        // The vessel's phase-port ends that may carry outflow from it by the static rule: each phase is shared among them.
        int ends=0;
        for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
            if((end==0?pipe.first():pipe.second())!=node||(end==0?pipe.firstPort():pipe.secondPort())==PassiveNetwork.PhasePort.BULK)continue;
            if(PassiveStepSolver.boundaryAllowed(graph,pipe,end==0?1:-1))ends++;
        }
        return port==PassiveNetwork.PhasePort.LIQUID?PhaseDraw.fillMixed(state,volume,Math.max(1,ends)):PhaseDraw.fill(state,PhaseDraw.order(model,state,port),volume,Math.max(1,ends));
    }

    /** What a stream delivers to a device that draws all of it: the three measures on its bulk, never dry, as from a
     * generator (batch 2026-11-02-process-simulator). */
    public static Supply bulk(FluidThermodynamics model,FluidThermodynamics.State state) {
        return measure(model,state,null,-1,-1,PassiveNetwork.PhasePort.BULK,Double.POSITIVE_INFINITY);
    }

    private static Supply measure(FluidThermodynamics model,FluidThermodynamics.State state,double[] drawn,int node,long id,PassiveNetwork.PhasePort port,double held) {
        double[] fluidVolume={state.vaporVolume(),state.labelVolume(OIL),state.labelVolume(AQUEOUS)};
        double gas,oil,water,gasMass,condensedMass,solidScale,total;
        if(drawn==null) {
            gas=fluidVolume[GAS];oil=fluidVolume[OIL];water=fluidVolume[AQUEOUS];
            gasMass=model.vaporMass(state);condensedMass=model.liquidMass(state);
            solidScale=1;total=gas+oil+water+state.solidMoments().volume();
        } else {
            double[] volume=new double[3],mass=new double[3];solidScale=0;total=0;
            for(int phase=0;phase<3;phase++) {
                if(!(drawn[phase]>0))continue;
                double stream=FluidThermodynamics.phaseVolume(state,phase);
                volume[phase]=drawn[phase]*fluidVolume[phase]/stream;
                mass[phase]=drawn[phase]*model.phaseMass(state,phase)/stream;
                solidScale+=drawn[phase]/stream*FluidThermodynamics.phaseSolidShare(state,phase);
                total+=drawn[phase];
            }
            gas=volume[GAS];oil=volume[OIL];water=volume[AQUEOUS];gasMass=mass[GAS];condensedMass=mass[OIL]+mass[AQUEOUS];
        }
        double fluid=gas+oil+water;
        double largestSolid=0;
        if(total>0)for(var population:state.solids().populations())largestSolid=Math.max(largestSolid,population.volume()*solidScale/total);
        return new Supply(node,id,port,fluid>0?gas/fluid:0,gasMass+condensedMass>0?condensedMass/(gasMass+condensedMass):0,largestSolid,held);
    }

    /**
     * Which connections of {@code graph} are refused for the slice that starts on its states: every mover whose supply
     * ({@link #supply}, over {@code duration}) it refuses ({@link FlowControl.Mover#refuses}: a dry suction, or a
     * compressor's solids), with its hysteresis input the committed endpoint mode {@code committedModes} (null, or of another length:
     * none refused before). One flag per connection.
     */
    public static boolean[] decide(FluidThermodynamics model,PassiveNetwork graph,List<FlowControl.Mode> committedModes,double duration) {
        boolean[] refused=new boolean[graph.pipes().size()];
        boolean carried=committedModes!=null&&committedModes.size()==refused.length;
        for(int edge=0;edge<refused.length;edge++) {
            if(!(graph.pipes().get(edge).control() instanceof FlowControl.Mover mover))continue;
            boolean before=carried&&committedModes.get(edge)==FlowControl.Mode.INLET_WRONG_PHASE;
            refused[edge]=mover.refuses(supply(model,graph,edge,mover.targetVolumeFlow(),duration),before);
        }
        return refused;
    }

    /**
     * The reason a refused mover shows (decision D6), which the device view shows with the node named as a device: a
     * suction that what feeds it fills below the opening share ({@link #DRY_RESUME}) is dry, "ERROR: pump inlet dry (from
     * node 12)"; a compressor's solids, "ERROR: compressor inlet not gas (solids 1.0e-03 by volume, from node 12)". Gas at a
     * pump and liquid at a compressor refuse nothing (request 11, owner decision D19), so a refused pump is dry.
     * {@code node} is the id of the node the supply walk ended at.
     */
    public static String reason(FlowControl.Mover mover,Supply supply) {
        if(mover.liquid()||supply.heldShare()<DRY_RESUME)return String.format(Locale.ROOT,"ERROR: %s inlet dry (from node %d)",mover.deviceName(),supply.nodeId());
        return String.format(Locale.ROOT,"ERROR: compressor inlet not gas (solids %.1e by volume, from node %d)",supply.largestSolidVolumeFraction(),supply.nodeId());
    }
    /** {@link #reason} of connection {@code edge} of {@code graph} (a mover) over a slice of {@code duration} seconds. */
    public static String reason(FluidThermodynamics model,PassiveNetwork graph,int edge,double duration) {
        var mover=(FlowControl.Mover)graph.pipes().get(edge).control();
        return reason(mover,supply(model,graph,edge,mover.targetVolumeFlow(),duration));
    }
}
