package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.NEWTON_TOLERANCE;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.liquidEnthalpy;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.liquidMoles;
import static com.wormzjl.icheme.science.fluid.network.DeviceModes.shutoffBand;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.wetShare;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.SolveConstants;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.*;
import java.util.function.IntFunction;

/**
 * What a step solve reads of its movers' suctions, once per solve on the step's start states: each liquid pump's floor
 * ({@link #suctionFloors}: the vapour pressure of the liquid it draws, {@link #release}, and the clean zone's edge of what
 * its line delivers, {@link #zoneEdge(InletPhase.Drawn,Runnable)}), the vapour locks ({@link #vapourLocks}), the
 * readings of each mover's suction supply ({@link #readSuctions}: the head share, the wet share, the NPSH design and the
 * liquid's density), the suction limit the rows and the mode tests read ({@link #suctionLimit},
 * {@link #cavitationMargin}) and the density a mover's rise limit reads ({@link #riseDensity}). The readings live in the
 * solve's constants ({@link SolveConstants}), which the solve and the mint build; this writes their arrays. One per
 * solver ({@code PassiveStepSolver.suctions}), built with it; it reads the solve constants through {@code solver} and
 * holds no state of its own beyond its two caches of floors and zone edges, one pair per solver (finding P-09) (S-01, batch
 * 2026-11-13-codebase-review, D-S5: moved out of the solver with every body unchanged but its qualifiers).
 */
final class MoverSuction {
    /** The solver whose movers this reads: its solve constants. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    MoverSuction(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /**
     * Owner decisions D59 (4), D14 and D17: how far the suction end of connection {@code edge} stands above the pressure its
     * suction limit holds it at ({@link #suctionLimit}), read on {@code states} at suction volume flow {@code volumeFlow}
     * (m3/s) (Pa), so the mode test and the limit's row read one value; positive infinity where the connection is not a
     * liquid pump or its suction has no vapour pressure to stand at.
     */
    double cavitationMargin(StepEquations equations,int edge,List<FluidThermodynamics.State> states,double volumeFlow) {
        var pipe=equations.graph.pipes().get(edge);int a=pipe.first();
        double limit=suctionLimit(equations,edge,states::get,volumeFlow);
        return limit<Double.POSITIVE_INFINITY?PortStreams.endPressure(model,equations.graph.reservoirs().get(a),pipe.firstPort(),states.get(a))-limit:Double.POSITIVE_INFINITY;
    }
    /**
     * Owner decision D17 (bundled random 896): the pressure the suction end of pump connection {@code edge} stands at on its
     * suction limit, on the node states {@code states} and its suction volume flow {@code volumeFlow} (m3/s; the trial point
     * in the Newton's row): the lesser of its floor and what its supplier gives at no flow ({@link #suctionSupply}, the supplier
     * its suction walk ends at, {@link StepEquations#suctionSuppliers}). A suction limit never stands above its supplier: where
     * the supplier falls to its bubble point within the step (896's tee, flashing to pass its main stream), the suction
     * stands at the supplier less its static column, the suction line carries nothing and the pump delivers nothing,
     * continuously and with no mode change. Held at the floor alone, the suction stood above a tee that had to flash below
     * it, the line had to reverse and no point balanced the tee (D14_HOLDS_REVIEW.md). The floor where the pump has no
     * suction walk (it draws straight from its first node) or no floor.
     *
     * <p>The floor (owner decision D20 Q11 A, plan section 3.6) is the floor of the liquid it draws ({@link #vapourPressure})
     * plus its breakdown NPSH at its flow, {@code 0.85 ρ_l g NPSH3(Q, s)} ({@link #breakdown}): the suction pressure at which
     * its head breaks down, so it delivers what its suction can feed there; the floor rises with the flow while the suction
     * line's pressure falls with it, so they cross once, and the bubble floor is its {@code NPSH3 = 0} end. The row reads it on
     * its own flow column: the block sweep re-assembles the actuator row for the edge's flow, which the sparsity declares
     * ({@link StepEquations#buildSparsity}). Where the pump has no floor (infinite) the NPSH adds none.
     */
    double suctionLimit(StepEquations equations,int edge,IntFunction<FluidThermodynamics.State> states,double volumeFlow) {
        var pipe=equations.graph.pipes().get(edge);
        double floor=suctionLimitPressure(equations.graph,edge,breakdown(equations.graph,edge,volumeFlow));int supplier=equations.suctionSuppliers[edge];
        if(!(floor<Double.POSITIVE_INFINITY)||supplier<0)return floor;
        var s=states.apply(supplier);int link=equations.suctionLinks[edge];
        return Math.min(floor,suctionSupply(equations.graph,edge,supplier,link,PortStreams.endPressure(model,equations.graph.reservoirs().get(supplier),equations.graph.pipes().get(link).portAt(supplier),s),s));
    }
    /**
     * Owner decisions D15 and D17: the pressure the supplier {@code supplier} of pump connection {@code edge} gives at the
     * pump's first node with nothing flowing, its suction walk's end ({@link InletPhase#walk}, reached through its link
     * {@code link}): its end pressure {@code supplierEnd} less the static column of its stream {@code s} up to that node.
     */
    double suctionSupply(PassiveNetwork graph,int edge,int supplier,int link,double supplierEnd,FluidThermodynamics.State s) {
        var line=graph.pipes().get(link);var node=graph.reservoirs().get(supplier);
        double lift=graph.reservoirs().get(graph.pipes().get(edge).first()).elevation()-node.elevation();
        return supplierEnd-PortStreams.columnDensity(model,s,line.portAt(supplier),line.volume())*GRAVITY*lift;
    }
    /**
     * The vapour pressure of the liquid the pump on connection {@code edge} draws, for the whole solve
     * ({@link #suctionFloors}): its p_v
     * ({@link #release}). Its wear reads σ against it ({@link StepObservations#npshRatio}); its suction limit and vapour lock read it with
     * the zone edge ({@link #suctionLimitPressure}). Positive infinity where the suction has none (no pump, a VAPOR port, a supplier with no
     * liquid, a liquid that stays one liquid down to the numerical box's floor). The water saturation curve of owner
     * decision D59 (4) was lifted by the owner on 2026-11-11 (W3B_NETWORK_PLAN.md section 17): water is one more liquid.
     * {@code suction} is the trial state of the pump's first node, which the floor does not read.
     */
    double vapourPressure(StepEquations equations,int edge,FluidThermodynamics.State suction) {
        return vapourPressure(equations.graph,edge);
    }
    /** {@link #vapourPressure} on {@code graph}. */
    private double vapourPressure(PassiveNetwork graph,int edge) {
        var pipe=graph.pipes().get(edge);
        if(!(pipe.control() instanceof FlowControl.Mover liquid&&liquid.liquid())||pipe.firstPort()==PassiveNetwork.PhasePort.VAPOR)return Double.POSITIVE_INFINITY;
        return edge<solver.constants.suctionFloors.length?solver.constants.suctionFloors[edge]:Double.POSITIVE_INFINITY;
    }
    /**
     * W3B_NETWORK_PLAN.md section 17: the pressure the suction of the pump on connection {@code edge} of {@code graph} is
     * held at with {@code breakdown} (Pa) of breakdown NPSH: {@link ReleasePressure#limit} of its liquid's p_v ({@link #vapourPressure})
     * and the clean zone's edge E of what its line delivers ({@link #zoneEdge}). The suction limit reads it with the
     * breakdown at the pump's flow; the vapour lock, and every test of whether a pump has a limit, with none.
     */
    double suctionLimitPressure(PassiveNetwork graph,int edge,double breakdown) {
        return ReleasePressure.limit(vapourPressure(graph,edge),zoneEdge(edge),breakdown);
    }
    /**
     * The clean zone's edge E of what the suction line of the pump on connection {@code edge} delivers in this solve
     * ({@link SolveConstants}' drawn streams), worked out when first read ({@link #zoneEdge(InletPhase.Drawn,Runnable)}). Positive
     * infinity where the pump draws no liquid ({@link #suctionFloors}: its supplier holds none, or a VAPOR port).
     */
    private double zoneEdge(int edge) {
        var c=solver.constants;
        if(edge>=c.zoneEdges.length||edge>=c.drawnStreams.length||c.drawnStreams[edge]==null)return Double.POSITIVE_INFINITY;
        if(Double.isNaN(c.zoneEdges[edge]))c.zoneEdges[edge]=zoneEdge(c.drawnStreams[edge],c.suctionCheckpoint);
        return c.zoneEdges[edge];
    }
    /**
     * Owner decisions D15 and D16 A (batch 2026-11-03-rotating-machinery, D14_HOLDS_REVIEW.md, D15_HOLDS_REVIEW.md): per
     * connection, whether its pump is vapour-locked in this solve, worked out once on the step's start {@code states}, the
     * states its floor is read on ({@link #suctionFloors}). A liquid pump is vapour-locked where its suction head at no flow
     * ({@link #suctionHead}, the available NPSH) is at or below the band of its mode tests ({@link DeviceModes#shutoffBand} at the
     * larger end pressure, the Newton's tolerance): its line delivers nothing with the suction at the floor, so it passes
     * nothing and stands closed, not on its suction limit with no flow. Whether it is depends on its supplier at no flow,
     * never on a point its own law drained: one fact for the start modes ({@link DeviceModes#initialModes}), the carry, the balance
     * and the pass loop ({@link DeviceModes#nextMode}, {@link DeviceModes#cavitates}), so a locked pump stays closed for the whole solve and any
     * other pump the solve finds starved goes on its suction limit. Bundled random 896: a crude drawn through a 1 cm line
     * from a tee at its 214.78 kPa bubble point, anchored at the floor, had no root at any step size. Bundled random 575 (a
     * crude tee 99 to 117 kPa above its 124.18 kPa floor) and cold random 181 (LNG 400 Pa above its 101.32 kPa floor with its
     * drain flowing, none with it stopped) cycled while the test was read on each pass's point: the closed converged point
     * reopened the pump, its law pulled the supplier itself to 7 to 73 kPa and 14 to 28 kPa, and the failed iterate closed
     * it again. A pump whose supplier recovers stays closed for that one solve (181: 0.03 kg/s for one step). The lock (D15)
     * was lifted by the owner on 2026-11-11 subject to measurement; measured on form G and kept (W3B_NETWORK_PLAN.md section
     * 17: removed, cold random 181 stalled in a line search at tick 1086 and bundled iterations rose 5.1 %). The lock reads
     * the suction limit ({@link #suctionLimitPressure})
     * without the breakdown NPSH (plan section 3.6: it is the NPSHA ≤ 0 end): a supplier whose head at no flow lies between
     * zero and the breakdown NPSH at shutoff leaves the pump on its suction limit, where D17's lesser-of takes its delivery
     * to zero continuously ({@link #suctionLimit}). Written into {@code locked}, the solve's {@code vapourLocked}
     * ({@link SolveConstants}), whose zone edges and floors the lock reads.
     */
    void vapourLocks(PassiveNetwork graph,List<FluidThermodynamics.State> states,boolean[] locked) {
        for(int edge=0;edge<locked.length;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!(pipe.control() instanceof FlowControl.Mover pump&&pump.liquid())||pump.targetVolumeFlow()==0)continue;
            var a=states.get(pipe.first());double head;
            try{head=suctionHead(graph,edge,suctionLimitPressure(graph,edge,0),states);}catch(IllegalArgumentException outsideData){continue;}
            locked[edge]=!(head>shutoffBand(Math.max(a.pressure(),states.get(pipe.second()).pressure()),NEWTON_TOLERANCE));
        }
    }
    /** Whether the pump on connection {@code edge} is vapour-locked in this solve ({@link #vapourLocks}). */
    boolean vapourLocked(int edge){return edge<solver.constants.vapourLocked.length&&solver.constants.vapourLocked[edge];}
    /**
     * Owner decision D15: the suction head the line of liquid pump connection {@code edge} has at no flow over the floor
     * {@code floor} of the liquid it draws, on the node {@code states} (Pa), the step's start states ({@link #vapourLocks}):
     * the end pressure of the supplier its suction walk ends at ({@link InletPhase#walk}), less the floor and the static
     * column of the supplier's stream up to the pump's first node. Positive infinity where the pump has no floor
     * ({@code floor} infinite) or no suction walk (it draws straight from its first node: its line has no length over which
     * to lose its head at no flow).
     */
    private double suctionHead(PassiveNetwork graph,int edge,double floor,List<FluidThermodynamics.State> states) {
        if(!(floor<Double.POSITIVE_INFINITY))return Double.POSITIVE_INFINITY;
        var walk=InletPhase.walk(graph,edge);if(walk.via()<0)return Double.POSITIVE_INFINITY;
        var s=states.get(walk.node());var link=graph.pipes().get(walk.via());var supplier=graph.reservoirs().get(walk.node());
        return suctionSupply(graph,edge,walk.node(),walk.via(),PortStreams.endPressure(model,supplier,link.portAt(walk.node()),s),s)-floor;
    }
    /**
     * Owner decision D14 (reading A13; K6 of the t38 plan widened), as W3B_NETWORK_PLAN.md section 17 restates it: a pump's
     * suction limit reads the liquid it draws, at that liquid's own enthalpy ({@link #release}), whatever the liquid. Per
     * pump connection, for the whole solve, on the step's start states: the floor of the liquid of the supplier its suction
     * walk ends at ({@link InletPhase#walk}), or of its own first node where it draws straight from it; the clean zone's edge
     * is read when first needed ({@link #zoneEdge}), and only for a pump that draws a liquid here. Positive infinity where there is none, and where the
     * thermodynamics refuses the evaluation (no floor, never a hold). An LNG tank at 112 K and 222 kPa feeding a pump
     * through its line drew the junction below the bubble point, where the liquid flashed and the step stalled; with water at
     * that temperature the limit was nothing. A subcooled light crude at 310 K and 390 kPa, drawn by a 0.01 m3/s pump
     * through a 1 cm line that passes 3.04 kg/s, was pulled to the 1 Pa floor still laid out as liquid, and the line search
     * stalled (bundled random seeds 952 and 575). Written into {@code c} ({@link SolveConstants}: its floors and zone edges
     * start at positive infinity), on its checkpoint.
     */
    void suctionFloors(PassiveNetwork graph,List<FluidThermodynamics.State> states,SolveConstants c) {
        double[] floors=c.suctionFloors,zoneEdges=c.zoneEdges;Runnable checkpoint=c.suctionCheckpoint;
        // NaN: worked out when first read; infinite: the pump draws no liquid, so no edge.
        for(int edge=0;edge<floors.length;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!(pipe.control() instanceof FlowControl.Mover liquid&&liquid.liquid())||pipe.firstPort()==PassiveNetwork.PhasePort.VAPOR)continue;
            // Section 17 (owner, 2026-11-11): a pump drawing straight from its tank reads its own first node, as one drawing
            // through a suction walk reads the walk's end. The old rule ("a tank's own closure holds its bubble pressure")
            // left it with no NPSH, no cavitation and no wear.
            var walk=InletPhase.walk(graph,edge);boolean direct=walk.via()<0;
            int supplier=direct?pipe.first():walk.node();var s=states.get(supplier);
            int label=drawnLiquid(s,direct?pipe.firstPort():graph.pipes().get(walk.via()).portAt(supplier));
            if(label<0)continue;
            floors[edge]=release(s,checkpoint);zoneEdges[edge]=Double.NaN;
        }
    }
    /** The liquid label (OIL or AQUEOUS) a pump draws from supplier state {@code s} through its end {@code port}: the port's
     * leading liquid; the hydrocarbon liquid where it draws both or the bulk and the supplier holds one; -1 for none. */
    private int drawnLiquid(FluidThermodynamics.State s,PassiveNetwork.PhasePort port) {
        int leading=PhaseDraw.leading(model,s,port);
        if(leading==FluidThermodynamics.OIL||leading==FluidThermodynamics.AQUEOUS)return leading;
        if(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL))return FluidThermodynamics.OIL;
        return FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS)?FluidThermodynamics.AQUEOUS:-1;
    }
    /** {@link #zoneEdge(InletPhase.Drawn,Runnable)} worked out, per supplier state and the stream drawn from it (a
     * generator feeding two pumps through different ends has one E per end). One map per solver (finding P-09, batch
     * 2026-11-13-codebase-review): an edge is a function of its state, its stream and the model alone. */
    private final Map<FluidThermodynamics.State,Map<DrawKey,Double>> zoneEdgeCache=Collections.synchronizedMap(new WeakHashMap<>());
    /** A drawn stream as the cache tells streams of one state apart: its amounts and enthalpy. */
    private record DrawKey(double[] amounts,double enthalpy) {
        @Override public boolean equals(Object o){return o instanceof DrawKey k&&Double.compare(enthalpy,k.enthalpy)==0&&Arrays.equals(amounts,k.amounts);}
        @Override public int hashCode(){return 31*Arrays.hashCode(amounts)+Double.hashCode(enthalpy);}
    }
    /**
     * W3B_NETWORK_PLAN.md section 17 (owner, 2026-11-11), form G: the clean zone's edge E of the stream {@code drawn} a
     * suction end draws now ({@link SolveConstants}' drawn streams); none where it holds no liquid; the supplier's pressure where the
     * stream's vapour volume share is at or past {@link com.wormzjl.icheme.science.machine.GasEntrainment#cleanShare}
     * there; else the lowest pressure at which the stream, at its own fluid enthalpy (solids apart), holds a vapour volume
     * share at most the clean share there ({@link #releasePressure}); positive infinity where there is none. Cold random 181
     * and bundled random 6: a pump on a boiling two-phase junction, E read on its liquid alone 1.1 kPa under the junction's
     * pressure, was left open there and the junction's rows stalled; read on the junction's mixture, E is its pressure. A
     * liquid drawn through a vessel's liquid port from under its blanket keeps an E below the vessel's pressure. The limit
     * is max(E, p_v + 0.85 ρ g NPSH3(Q)) ({@link ReleasePressure#limit}); wear reads σ against p_v.
     */
    double zoneEdge(InletPhase.Drawn drawn,Runnable checkpoint) {
        // A phase port drawing gas now draws no liquid: no edge (the dry-suction refusal owns that case).
        if(!drawn.condensed())return Double.POSITIVE_INFINITY;
        var s=drawn.state();double p=s.pressure();
        if(drawn.supply().vapourVolumeShare()>=com.wormzjl.icheme.science.machine.GasEntrainment.cleanShare(p))return p;
        var key=new DrawKey(drawn.amounts(),drawn.enthalpy());
        var known=zoneEdgeCache.computeIfAbsent(s,k->Collections.synchronizedMap(new HashMap<>()));
        var e=known.get(key);if(e!=null)return e;
        double edge=PhaseLayout.sum(drawn.amounts())>0?releasePressure(s,drawn.amounts(),drawn.enthalpy(),com.wormzjl.icheme.science.machine.GasEntrainment::cleanShare,checkpoint):Double.POSITIVE_INFINITY;
        known.put(key,edge);
        return edge;
    }
    /** {@link #release} worked out, keyed by the supplier state (a generator keeps its state across solves). One map per
     * solver (finding P-09, batch 2026-11-13-codebase-review): a floor is a function of its state and the model alone. */
    private final Map<FluidThermodynamics.State,Double> releaseCache=Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * p_v of supplier state {@code s}'s liquids together (Pa, {@link #releasePressure}), once per state: the bubble
     * pressure of its condensable compounds, those above their critical temperature at the supplier's temperature left
     * out, at their own enthalpy at the supplier's pressure, every liquid (water alone at 298.15 K and 1 atm: about
     * 3175 Pa; nitrogen or air dissolved in it: none of it; CO2 dissolved in water at 298 K counts, its Tc is 304.1 K).
     * Positive infinity where there is none. The clean zone's edge is the drawn stream's ({@link #zoneEdge}).
     */
    double release(FluidThermodynamics.State s,Runnable checkpoint) {
        var known=releaseCache.get(s);if(known!=null)return known;
        double r=Double.POSITIVE_INFINITY;
        if(FluidThermodynamics.holdsLiquid(s)) {
            // W3b: the supplier's liquids together, water among their components: two liquids at equilibrium share one bubble
            // pressure, and a pump drawing either draws the same floor.
            double[] x=liquidMoles(s);double h=liquidEnthalpy(s),t=s.temperature();
            double[] c=x.clone();int kept=ReleasePressure.leaveOutGases(model,t,c);boolean removed=(kept&ReleasePressure.REMOVED)!=0;
            if((kept&ReleasePressure.LEFT)!=0) {
                double hc=h;
                if(removed)try{hc=model.flashTP(t,s.pressure(),c.clone(),checkpoint).enthalpy();}catch(IllegalArgumentException refused){hc=Double.NaN;}
                if(Double.isFinite(hc))r=releasePressure(s,c,hc,p->0,checkpoint);
            }
        }
        releaseCache.put(s,r);
        return r;
    }
    /** The relative width in pressure to which {@link #releasePressure} brackets its pressure. The floor is read from one
     * evaluation for a whole solve, so its rows and mode tests all read the same value; the width only places it against
     * the true pressure (0.1 Pa at 100 kPa), and it costs about 24 PH equilibria from the box's floor, once per supplier
     * state ({@link #releaseCache}). */
    private static final double BUBBLE_WIDTH=1e-6;
    /**
     * W3B_NETWORK_PLAN.md section 17, after owner decisions D14 (reading A13) and D16 C's first half: the lowest pressure,
     * between the numerical box's floor and supplier {@code s}'s pressure, at which the liquid {@code x} drawn from it, at
     * its own enthalpy {@code h} (J), holds a vapour volume share at most {@code share} at that pressure. The liquid reaches
     * the pump through its line as through a throttle, which keeps its enthalpy. The search is the shared one
     * ({@link ReleasePressure#lowest}, which the process simulator's sizing reads on its own trial): a bisection in ln P to
     * {@link #BUBBLE_WIDTH} on the engine's PH equilibrium ({@link FluidThermodynamics#equilibriumPH}, unfolded, so a trace
     * of vapour counts) that returns the high end, so a suction held there holds no more vapour than {@code share}; with
     * {@code share} 0 it is the bubble pressure. Midpoints are geometric means ({@link Math#sqrt}, correctly rounded, the
     * same on every runtime). A trial the engine cannot state as a network phase set (no convergence, a product proved
     * unstable, the cold end of a deep flash outside the box: {@link #releaseShare}) does not pass; the pressure stands
     * only where some trial below the end stated a vapour. The supplier's pressure where the liquid holds more there;
     * positive infinity where it passes at the box's floor, where the equilibrium at the supplier's own pressure is refused
     * and where no trial stated a vapour: no floor, never a hold (AGENTS.md, thermo validity).
     *
     * <p>At the liquid's temperature (D14's TP form) the floor stood below the pressure at which the throttled liquid,
     * warmed by its Joule-Thomson effect, boils: cold random 181's LNG has its floor at 101 324.09 Pa at its temperature and
     * 101 324.998 Pa at its enthalpy, and held at the lower one its suction flashed a trace and stalled at tick 1078; at its
     * enthalpy that tick passes (D15_HOLDS_REVIEW.md, probe C). The same for water: PumpCavitationTest's starved suction,
     * water at 298.15 K and 1 atm drawn down, is 91 J/kg over the saturated liquid at its stated temperature; held at that
     * temperature's saturation pressure 3.7e-5 of what arrived flashed, the pure-water junction held steam beside its water
     * and the suction limit's row was its own phase rule: the step's Jacobian was singular. At its own enthalpy it stands
     * liquid at about 298.17 K. That is why water's own curve (BalanceNet.waterFloor, D59 (4)) read its enthalpy; this
     * reads every liquid's.
     */
    private double releasePressure(FluidThermodynamics.State s,double[] x,double h,java.util.function.DoubleUnaryOperator share,Runnable checkpoint) {
        return ReleasePressure.lowest(p->releaseShare(s,x,h,p,checkpoint),share,NumericalBox.MINIMUM_PRESSURE,s.pressure(),BUBBLE_WIDTH);
    }
    /** The vapour volume share of the PH equilibrium of liquid {@code x} at enthalpy {@code h} (J) and pressure {@code p},
     * warm-started at supplier {@code s}'s temperature ({@link #releasePressure}): {@link ReleasePressure#vapourVolumeShare}
     * of that state, NaN too for anything the network cannot carry. */
    private double releaseShare(FluidThermodynamics.State s,double[] x,double h,double p,Runnable checkpoint) {
        FluidThermodynamics.State at;
        try{at=model.equilibriumPH(s.temperature(),p,h,x.clone(),checkpoint);}catch(IllegalArgumentException refused){return Double.NaN;}
        return ReleasePressure.vapourVolumeShare(at);
    }
    /**
     * The density the rise limit of the mover on connection {@code edge} reads ({@link PassiveStepSolver#riseLimit}). A set-flow pump's is its
     * supply's, the stream its supply walk ends at ({@link InletPhase#density} on this solve's input states; owner decision D11,
     * A2): one value for the whole solve, so its law row and every mode test read one relief rise, and a closed pump reopens
     * exactly where its law runs (read on the suction stream for the reopening alone, a dead-headed pump behind a junction
     * 1e-5 lighter than its supply reopened 5 Pa early at 5 bar and cycled). A pump whose suction line still holds the gas it
     * was built with pushes it out at its relief rise on the liquid behind it, as a positive-displacement pump primes
     * itself. Every other mover reads its suction stream {@code suction}: a compressor's ratio limit reads no density, and a
     * centrifugal machine does not prime. The start balance reads every mover on its suction stream ({@link BalanceStart#balanceStart}):
     * there a pump whose suction flashes yields as the tabled density falls, so a starved pump starts where it cavitates (an
     * LNG pump fed through a 1 cm line, balanced on its supply's relief, started liquid at its bubble point and every step
     * size stalled).
     */
    double riseDensity(int edge,double suction) {
        var c=solver.constants;
        if(!(c.supplyGraph.pipes().get(edge).control() instanceof FlowControl.Pump pump))return suction;
        if(Double.isNaN(c.supplyDensities[edge])){double d=InletPhase.density(model,c.supplyGraph,edge,pump.targetVolumeFlow(),c.supplyDuration);c.supplyDensities[edge]=d>0&&d<Double.POSITIVE_INFINITY?d:-1;}
        return c.supplyDensities[edge]>0?c.supplyDensities[edge]:suction;
    }
    /** Whether this solve's suction readings ({@link #readSuctions}) hold connection {@code edge} of {@code graph}: they were
     * read for the control it carries. */
    boolean read(PassiveNetwork graph,int edge) {
        var readControls=solver.constants.readControls;
        return edge<readControls.length&&readControls[edge]!=null&&readControls[edge]==graph.pipes().get(edge).control();
    }
    /** The mover on connection {@code edge} as its law runs this solve ({@link #readSuctions}): a centrifugal pump with its
     * head scaled by its gas share; else the connection's own control. */
    FlowControl.Mover mover(PassiveNetwork graph,int edge) {
        return read(graph,edge)?solver.constants.suctionMovers[edge]:(FlowControl.Mover)graph.pipes().get(edge).control();
    }
    /**
     * Owner decision D20 Q11 A: how far above the floor of the liquid it draws the suction of liquid pump connection
     * {@code edge} stands where its head breaks down at suction volume flow {@code volumeFlow} (m3/s),
     * {@code 0.85 ρ_l g NPSH3(Q, s)} ({@link com.wormzjl.icheme.science.machine.Npsh#breakdownPressure}) on this solve's NPSH
     * design and liquid density; zero where it has none (a gas mover, a stopped pump, a supply holding no liquid).
     */
    double breakdown(PassiveNetwork graph,int edge,double volumeFlow) {
        var c=solver.constants;
        if(!read(graph,edge)||c.npshDesigns[edge]==null||!(c.liquidDensities[edge]>0))return 0;
        return c.npshDesigns[edge].breakdownPressure(volumeFlow,c.liquidDensities[edge]);
    }
    /**
     * Request 11 (owner decisions D19 and D20, plan section 3.6): what each mover of {@code graph} reads of its suction for
     * the whole solve, on the step's start states and the slice {@code duration} (s; zero for a rate solve and the mint),
     * the supply its suction line delivers ({@link InletPhase#supply}), as the floor ({@link #suctionFloors}) and the vapour
     * lock ({@link #vapourLocks}) are read: numbers fixed for the solve, so every row and mode test reads one law. A
     * centrifugal pump's head is scaled by its gas share ({@link com.wormzjl.icheme.science.machine.GasEntrainment#headShare}
     * of the supply's vapour volume share at the suction node's pressure); its rise keeps the mixture density. A centrifugal
     * compressor's power is over its wet efficiency ({@link com.wormzjl.icheme.science.machine.CentrifugalCompressor#wetEfficiency}
     * of the supply's condensed mass share): its curve and ratio read the suction mixture already. A set-flow mover needs
     * neither: its law counts the suction mixture's volume, so it delivers {@code (1 − α)} of its displacement as liquid, or
     * its displacement less the liquid's volume as gas. Every liquid mover keeps its NPSH design ({@link FlowControl.Mover#npsh})
     * and the density of the liquid it draws ({@link InletPhase#liquidDensity}) for its floor ({@link #breakdown}).
     */
    void readSuctions(PassiveNetwork graph,double duration,SolveConstants c) {
        // Written into c's arrays ({@link SolveConstants#of}: empty, the condensed shares NaN).
        int edges=graph.pipes().size();
        var readControls=c.readControls;var suctionMovers=c.suctionMovers;var npshDesigns=c.npshDesigns;
        var liquidDensities=c.liquidDensities;var wetShares=c.wetShares;var vapourShares=c.vapourShares;var drawnStreams=c.drawnStreams;
        var condensedShares=c.condensedShares;
        for(int edge=0;edge<edges;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!(pipe.control() instanceof FlowControl.Mover mover))continue;
            readControls[edge]=mover;suctionMovers[edge]=mover;wetShares[edge]=1;
            if(mover.targetVolumeFlow()==0)continue;
            // E reads the liquid at the suction now (no slice); the head share reads the slice's stream, its later gas included.
            // Pulled for a whole slice to the edge of a stream that mixed the last water with the gas after it, a bottom-port
            // drain stood on its limit with the water still flowing (InletPhaseTest, fixture 5: 22 rejected steps).
            if(mover.liquid())drawnStreams[edge]=InletPhase.drawn(model,graph,edge,mover.targetVolumeFlow(),0);
            if(mover instanceof FlowControl.Machine machine) {
                var supply=mover.liquid()&&!(duration>0)?drawnStreams[edge].supply():InletPhase.supply(model,graph,edge,mover.targetVolumeFlow(),duration);
                if(machine.liquid())vapourShares[edge]=supply.vapourVolumeShare();
                if(machine.liquid())suctionMovers[edge]=machine.withHeadShare(com.wormzjl.icheme.science.machine.GasEntrainment.headShare(supply.vapourVolumeShare(),graph.reservoirs().get(pipe.first()).state().pressure()));
                // A vacuum pump (batch 2026-11-10-vacuum-pump) counts the suction mixture's volume as a set-flow mover does; a dry
                // screw keeps its vapour share for its wear and liquid noise (owner V6 B).
                else if(machine.law() instanceof com.wormzjl.icheme.science.machine.VacuumPump){if(com.wormzjl.icheme.science.machine.VacuumPump.dryScrew(machine.law()))vapourShares[edge]=supply.vapourVolumeShare();}
                // A centrifugal compressor keeps the condensed mass share its wet efficiency reads, for its wet gas wear (N2).
                else{wetShares[edge]=wetShare(supply);condensedShares[edge]=supply.condensedMassShare();}
            }
            // W10d: a set-flow pump's suction vapour share, for its page's gas reading only (its law counts the mixture already).
            else if(mover.liquid())vapourShares[edge]=(duration>0?InletPhase.supply(model,graph,edge,mover.targetVolumeFlow(),duration):drawnStreams[edge].supply()).vapourVolumeShare();
            if(mover.liquid()){npshDesigns[edge]=mover.npsh();liquidDensities[edge]=InletPhase.liquidDensity(model,graph,edge,mover.targetVolumeFlow(),duration);}
        }
    }
}
