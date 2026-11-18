package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.boreArea;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.boundaryAllowed;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.cakeResistance;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.holdsNoFluid;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.incidence;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.reopenBand;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.sameStates;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.saturated;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.saturationSlope;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.scaled;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.*;

/**
 * The balance start of a step solve (owner decision of 2026-10-19, batch 2026-10-19-junction-seeding): the quasi-steady
 * balance of the island on its start states ({@link #balanceStart}, {@link BalanceNet} with its banded solve) and the
 * mint's round 0, the supplier composition ({@link #supplierSeeds}). One per solver ({@code PassiveStepSolver.balance}),
 * built with it; a {@link BalanceNet} is built per start. It reads the solver's constants, suction readings and laws through
 * {@code solver}; it holds no state of its own (S-01, batch 2026-11-13-codebase-review, D-S4: moved out of the solver
 * with every body unchanged but its qualifiers).
 */
final class BalanceStart {
    /** The solver this start belongs to: its solve constants and the laws and suction readings the balance states. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    BalanceStart(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /**
     * Composition rounds of {@link #balanceStart}, at most: the junctions' upwind mixtures at the balanced flows move their
     * densities and caps, so the balance is solved once more on them; a round that moves no junction pressure by more than
     * 1e-6 of itself ends the rounds early.
     */
    static final int BALANCE_ROUNDS=2;
    /** The balance's result for one solve: the graph with its junctions at the balanced states, the law flows of every
     * open connection at the balanced ends, the passive connections the one-way law closes, the device modes it decided, and whether it converged. */
    record Start(PassiveNetwork graph,double[] lawFlows,boolean[] closed,List<FlowControl.Mode> modes,boolean converged) {}
    /**
     * The mint's round 0 (owner decision of 2026-10-19 on t45's trace, {@code BRANCH_SELECTION_TRACE.md}): a minted
     * junction's first state is what reaches it, decision D55's supplier composition, not the placeholder (the island's
     * lowest-id boundary state, which put crude in a nitrogen header and water in a nitrogen loop, sized buffers 38 to 570
     * times too dense, and left the balance's two rounds on an iterate the cold rate seed then froze). A supplier is a node
     * that is not a junction and holds something; a void and a dry end send nothing ({@link PassiveStepSolver#boundaryAllowed}). Each
     * supplier joined to a junction sends the stream its end draws ({@link PortStreams#endMoles}) at the flow its static drive gives
     * against the ambient ({@link #supplyFlow}); the junction is the flow-weighted mixture of what reaches it, flashed at the
     * flow-weighted pressure of its suppliers' ends ({@link #seedAt}; the ambient put a liquid CO2 let-down below its triple point): the balance solves the pressures and restates every mixture at its balanced pressure
     * ({@link BalanceNet#seeded}), so D55's pressure bisection is not needed. A junction no supplier drives into takes it hop
     * by hop from the junctions that have it (a breadth-first sweep weighted by the flow that seeded them); one neither
     * reaches takes the streams of the suppliers joined to it, one share each, and the sweep runs again. A junction no
     * source reaches keeps the state it was built with ({@link NetworkReduction#atRest}).
     *
     * <p>A mover's suction line is seeded first, from the stream it draws (owner's fix A of F2): the junctions its supply
     * walk crosses ({@link InletPhase#walk}) take the stream of the supplier the walk ends at, as that end draws it
     * ({@link PortStreams#endStream}), neither flashed nor moved. A mover's target row converts its volume flow at its suction
     * junction's density, so that junction must hold what the mover draws (a pump under a vented LNG tank kept the 298 K
     * nitrogen placeholder, 1.1 kg/m3 for 447 kg/m3, and moved 0.006 kg/s for 2.24 kg/s).
     */
    List<FluidThermodynamics.State> supplierSeeds(PassiveNetwork graph,Runnable checkpoint) {
        int nodes=graph.reservoirs().size(),count=model.hydrocarbon.componentCount()+1;
        var states=new ArrayList<FluidThermodynamics.State>(nodes);
        for(var node:graph.reservoirs())states.add(node.junction()?node.state():null);
        double[][] amounts=new double[nodes][count];double[] mass=new double[nodes],heat=new double[nodes],tLow=new double[nodes],tHigh=new double[nodes],pressureSum=new double[nodes];
        boolean[] seeded=new boolean[nodes],drawn=new boolean[nodes];double[] weight=new double[nodes];
        for(int e=0;e<graph.pipes().size();e++) {
            if(!(graph.pipes().get(e).control() instanceof FlowControl.Mover))continue;
            var walk=InletPhase.walk(graph,e);
            if(walk.via()<0||graph.reservoirs().get(walk.node()).junction())continue;
            boolean supplies=true;
            for(int k=0;k<walk.links().length&&supplies;k++){var link=graph.pipes().get(walk.links()[k]);supplies=boundaryAllowed(graph,link,link.first()==walk.donors()[k]?1:-1);}
            var supplier=graph.reservoirs().get(walk.node());var last=graph.pipes().get(walk.via());
            var seed=supplies?PortStreams.endStream(model,supplier.state(),last.portAt(walk.node()),last.volume()):null;
            if(seed==null)continue;
            double q=supplyFlow(graph,last,walk.node());if(!(q>0))q=1;
            for(int k=-1;k<walk.links().length-1;k++) {
                int j=k<0?graph.pipes().get(e).first():walk.donors()[k];
                if(seeded[j])continue;
                states.set(j,seed);seeded[j]=drawn[j]=true;weight[j]=q;
            }
        }
        for(boolean driven:new boolean[]{true,false}) {
            Arrays.fill(mass,0);Arrays.fill(heat,0);Arrays.fill(pressureSum,0);Arrays.fill(tLow,Double.POSITIVE_INFINITY);Arrays.fill(tHigh,Double.NEGATIVE_INFINITY);
            for(var row:amounts)Arrays.fill(row,0);
            for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
                int supplier=end==0?pipe.first():pipe.second(),junction=end==0?pipe.second():pipe.first();
                var s=graph.reservoirs().get(supplier);
                // A supplier whose end is dry (one holding no fluid, dryEnd) sends nothing: both flows below are zero.
                if(!graph.reservoirs().get(junction).junction()||seeded[junction]||s.junction())continue;
                double q=driven?supplyFlow(graph,pipe,supplier):boundaryAllowed(graph,pipe,end==0?1:-1)?1:0;
                if(!(q>0))continue;
                var port=pipe.portAt(supplier);double[] n=PortStreams.endMoles(model,s.state(),port,pipe.volume());double m=PortStreams.endMass(model,s.state(),port,pipe.volume());
                for(int c=0;c<count;c++)amounts[junction][c]+=q*n[c]/m;
                var through=solver.delivered(graph,pipe,PortStreams.endSpecificEnthalpy(model,s.state(),port,pipe.volume()),s.state().temperature(),end==0?q:-q,checkpoint);
                heat[junction]+=q*through[0];pressureSum[junction]+=q*PortStreams.endPressure(model,s,port,s.state());
                mass[junction]+=q;tLow[junction]=Math.min(tLow[junction],through[1]);tHigh[junction]=Math.max(tHigh[junction],through[1]);
            }
            // Flashed at the flow-weighted pressure of what reaches it (a liquid CO2 let-down's junction stands above the triple
            // point, not at the ambient); the balance then solves the pressure and restates the mixture there.
            for(int j=0;j<nodes;j++)if(mass[j]>0&&seedAt(states,j,amounts[j],mass[j],heat[j],tLow[j],tHigh[j],pressureSum[j]/mass[j],checkpoint)){seeded[j]=true;weight[j]=mass[j];}
            // The sweep: hop by hop from the junctions seeded so far; a vessel that holds no fluid passes it through.
            for(boolean grew=true;grew;) {
                grew=false;var next=new boolean[nodes];
                Arrays.fill(mass,0);Arrays.fill(heat,0);Arrays.fill(pressureSum,0);Arrays.fill(tLow,Double.POSITIVE_INFINITY);Arrays.fill(tHigh,Double.NEGATIVE_INFINITY);
                for(var row:amounts)Arrays.fill(row,0);
                for(int j=0;j<nodes;j++) {
                    if(!graph.reservoirs().get(j).junction()||seeded[j])continue;
                    for(int k:sweepNeighbours(graph,j)) {
                        if(!seeded[k])continue;var s=states.get(k);double w=weight[k];
                        double[] n=PhaseLayout.totalAmounts(s);for(int c=0;c<count;c++)amounts[j][c]+=w*n[c]/s.mass();
                        heat[j]+=w*(s.enthalpy()/s.mass());pressureSum[j]+=w*s.pressure();mass[j]+=w;
                        tLow[j]=Math.min(tLow[j],s.temperature());tHigh[j]=Math.max(tHigh[j],s.temperature());
                    }
                }
                for(int j=0;j<nodes;j++)if(mass[j]>0&&seedAt(states,j,amounts[j],mass[j],heat[j],tLow[j],tHigh[j],pressureSum[j]/mass[j],checkpoint)){next[j]=grew=true;weight[j]=mass[j];}
                for(int j=0;j<nodes;j++)seeded[j]|=next[j];
            }
        }
        return states;
    }
    /** The junctions one hop from junction {@code j} for {@link #supplierSeeds}' sweep: joined to it by a connection, or
     * through one or more vessels that hold no fluid ({@link PassiveStepSolver#holdsNoFluid}: none of their own to send). */
    private static List<Integer> sweepNeighbours(PassiveNetwork graph,int j) {
        var found=new ArrayList<Integer>();var seen=new HashSet<Integer>();var open=new ArrayDeque<Integer>();open.add(j);seen.add(j);
        while(!open.isEmpty()) {
            int at=open.poll();
            for(var pipe:graph.pipes()) {
                int other=pipe.first()==at?pipe.second():pipe.second()==at?pipe.first():-1;
                if(other<0||!seen.add(other))continue;
                var node=graph.reservoirs().get(other);
                if(node.junction())found.add(other);else if(holdsNoFluid(node))open.add(other);
            }
        }
        Collections.sort(found);return found;
    }
    /** {@link #supplierSeeds}' flash of junction {@code j}'s mixture (amounts and enthalpy summed over {@code mass}) at
     * {@code pressure}, per kilogram: the temperature at which the mixture has the mixed enthalpy, bisected within 20 K of
     * its streams' temperatures inside the numerical box (no data range narrows the window, owner rule of 2026-10-16).
     * False, the junction left as it was, where a flash is refused. */
    private boolean seedAt(List<FluidThermodynamics.State> states,int j,double[] amounts,double mass,double heat,double tLow,double tHigh,double pressure,Runnable checkpoint) {
        double[] y=new double[amounts.length];for(int c=0;c<y.length;c++)y[c]=amounts[c]/mass;
        try {
            var bracket=mixingBracket(model,y,heat,mass,tLow,tHigh,pressure,checkpoint);
            states.set(j,model.flashTP(.5*(bracket[0]+bracket[1]),pressure,y.clone(),checkpoint));
        } catch(IllegalArgumentException refused){return false;} // a rejected state: the numerical box, a phase hold
        return true;
    }
    /**
     * The mixing-enthalpy bisection, the one statement of it (S-08): the temperature bracket {lower, upper} (K) within 20 K of
     * the streams' temperatures {@code tLow}, {@code tHigh}, inside the numerical box, in which the mixture {@code y} (moles per
     * kilogram) at {@code pressure} has the specific enthalpy {@code heat/mass}: 60 halvings at most, to 1e-9 K. Each reader
     * ends it its own way: {@link #seedAt} flashes the midpoint, {@link BalanceNet#mixed} the nearer side. A refused flash
     * throws through.
     */
    private static double[] mixingBracket(FluidThermodynamics model,double[] y,double heat,double mass,double tLow,double tHigh,double pressure,Runnable checkpoint) {
        double lower=Math.max(tLow-20,NumericalBox.MINIMUM_TEMPERATURE),upper=Math.min(tHigh+20,NumericalBox.MAXIMUM_TEMPERATURE);
        for(int k=0;k<60&&upper-lower>1e-9;k++) {
            double t=.5*(lower+upper);var point=model.flashTP(t,pressure,y.clone(),checkpoint);
            if(point.enthalpy()/point.mass()<heat/mass)lower=t;else upper=t;
        }
        return new double[]{lower,upper};
    }
    /**
     * The flow (kg/s, at least zero) supplier {@code supplier} sends into the junction at the other end of {@code pipe}
     * before anything is solved, as {@link PassiveStepSolver#initialMassFlow} reads a connection: a mover's suction flow out of its suction
     * end; otherwise the supplier's end pressure over the ambient, less its own end's column up to the junction, through
     * the connection's loss in its end stream, capped by the velocity limit. Zero where that direction is refused.
     */
    private double supplyFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int supplier) {
        boolean first=pipe.first()==supplier;
        if(!boundaryAllowed(graph,pipe,first?1:-1))return 0;
        var s=graph.reservoirs().get(supplier);var port=pipe.portAt(supplier);
        if(pipe.control() instanceof FlowControl.Mover pump)
            return first?Math.min(PortStreams.suctionMassFlow(model,pump.targetVolumeFlow(),s.state(),port,pipe.volume()),solver.massFlowLimit(pipe,s.state(),port)):0;
        double rho=PortStreams.endDensity(model,s.state(),port,pipe.volume()),mu=solver.endViscosity(s.state(),port,pipe.volume());
        double drive=PortStreams.endPressure(model,s,port,s.state())-PhaseLayout.AMBIENT_PRESSURE
                -PortStreams.columnDensity(model,s.state(),port,pipe.volume())*GRAVITY*(graph.reservoirs().get(first?pipe.second():pipe.first()).elevation()-s.elevation());
        if(!(drive>0))return 0;
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<drive&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>drive)hi=mid;else lo=mid;}
        return saturated((lo+hi)/2,solver.massFlowLimit(pipe,s.state(),port));
    }
    /**
     * The balance start of one solve: rounds of (1) the quasi-steady balance of the island on its start states, the
     * minimum of the co-content (every junction's net inflow zero under every connection's own law: the loss inverted on
     * the drive in the donor's column, saturated at the donor's cap, nothing in a refused direction, a mover's rise or
     * prescribed flow, a filter's cake law) and (2) each junction's state as the upwind mixture of what the balanced flows
     * deliver, flashed at its balanced pressure (PH); then the law flow of every open connection at the balanced ends and,
     * where the balance converged, the passive connections the one-way law leaves nothing to carry. A starting point only:
     * no inventory moves.
     *
     * <p>A closure is read only off ends that balance. Cold fuzz seed 654 (B): a void placed at 101.3 kPa became the only
     * outlet of a run of three junctions at 99.0 kPa that two choked lines fed 0.10 kg/s, so the run must stand just over
     * the void and discharge into it. The balance ended unconverged with those 0.10 kg/s left in the run and its last
     * iterate still at 99.0 kPa, and the one-way law read off that iterate closed the void line for the solve. The run
     * then had no outlet, only inflows deep in saturation (slope about 1e-3) set its pressure, and every pass went to 13
     * to 15 MPa.
     */
    Start balanceStart(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep,Runnable checkpoint) {return balanceStart(graph,modes,boundaryClosed,keep,checkpoint,graph);}
    /** {@link #balanceStart} with the junctions' buffers {@code buffers}: the solve's input graph; null for the mint, which has none yet. */
    Start balanceStart(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep,Runnable checkpoint,PassiveNetwork buffers) {
        int edges=graph.pipes().size();
        var current=graph;boolean converged=true;BalanceNet net=null;double[] p=null;
        for(int round=0;round<Math.max(1,BALANCE_ROUNDS);round++) {
            net=new BalanceNet(current,buffers,modes,boundaryClosed,checkpoint,solver.constants.heldOpenings);
            p=net.solve(checkpoint);converged=net.converged;
            if(net.count==0&&net.anchored==0)break;
            var next=net.seeded(p,checkpoint);
            if(next==null)break;
            boolean moved=false;
            for(int i=0;i<next.reservoirs().size()&&!moved;i++)if(next.reservoirs().get(i).junction())moved=Math.abs(next.reservoirs().get(i).state().pressure()-current.reservoirs().get(i).state().pressure())>1e-6*current.reservoirs().get(i).state().pressure();
            current=next;
            if(!moved)break;
        }
        double[] law=new double[edges];boolean[] closed=new boolean[edges];
        for(int e=0;e<edges;e++) {
            var pipe=current.pipes().get(e);
            if(boundaryClosed[e]||modes.get(e)==FlowControl.Mode.CLOSED||modes.get(e)==FlowControl.Mode.INLET_WRONG_PHASE)continue;
            double pa=PortStreams.endPressure(model,current,pipe,pipe.first()),pb=PortStreams.endPressure(model,current,pipe,pipe.second());
            // A valve whose flow or opening the balance states starts at what the balance gave it, not at its pipe's unthrottled law.
            boolean stated=pipe.control() instanceof FlowControl.Valve&&(!Double.isNaN(net.prescribed[e])||!Double.isNaN(net.fixedOpenings[e]));
            law[e]=net.suctionLimited[e]?net.prescribed[e]:stated?net.flow(e,pa,pb):solver.lawFlow(current,pipe,pa,pb);
            if(!(pipe.control() instanceof FlowControl.Passive)||keep!=null&&keep[e]||!converged)continue;
            // A passive connection the balanced ends can only drive the way its boundary forbids carries nothing this solve
            // (the closure the one-way estimate and the start closures stated by hand). Only ends that balance state it: an
            // unconverged balance's last iterate is no state of the island, and the closure holds for the whole solve.
            var a=current.reservoirs().get(pipe.first());var b=current.reservoirs().get(pipe.second());
            double dz=b.elevation()-a.elevation(),difference=pa-pb,band=reopenBand(pa,pb);
            double forward=difference-PortStreams.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume())*GRAVITY*dz,reverse=difference-PortStreams.columnDensity(model,b.state(),pipe.secondPort(),pipe.volume())*GRAVITY*dz;
            boolean fwd=boundaryAllowed(current,pipe,1),rev=boundaryAllowed(current,pipe,-1);
            if(forward>band&&!fwd&&!(reverse<-band&&rev)||reverse<-band&&!rev&&!(forward>band&&fwd)){closed[e]=true;law[e]=0;}
        }
        return new Start(current,law,closed,List.copyOf(modes),converged);
    }
    /** The balance of one island on its start states (decision D12's one-way estimate promoted, with every connection's mode): the junction pressures
     * at which every junction's net inflow vanishes, and the junctions' upwind mixtures at those pressures. */
    final class BalanceNet {
        final PassiveNetwork graph;final List<FlowControl.Mode> modes;final boolean[] closed;final int edges,nodes;final double[] fixedOpenings;
        /** The junctions' buffers, the solve's input graph ({@link #seeded}); null for the mint, which has none yet. */
        final PassiveNetwork buffers;
        final int[] unknown;final double[] anchor;int count,anchored,iterations,sweeps;boolean converged;double finalResidual;
        final double[][] rho,mu,cap,column,endP,filterR;final boolean[][] allowed;final boolean[] gas,mover;final double[] reference,prescribed;
        /** Per connection, its pump's suction floor once worked out ({@link #suctionFloor}); NaN before. */
        final double[] floors;
        /** Per unknown junction, its bulk density and density times velocity limit as a function of its own pressure: the
         * PH equilibrium of what it holds at TABLE points spaced in ln P over the hull (a flashing LNG junction is vapour at
         * 1 atm and liquid at its bubble pressure, and its caps follow); null where the engine answers nothing. A junction
         * stated as one hydrocarbon liquid has a table only where it flashes inside the hull, from the hull's low end up to
         * its stated pressure, and reads it only below {@link #flashFrom}. */
        final double[][] tableP,tableRho,tableRhoV;static final int TABLE=5;
        /** Per unknown junction, the pressure from which it reads its stated data and not its table: the first table point
         * of a liquid junction that holds no vapour; infinite for a table of a junction stated two-phase or still flashing at
         * its stated pressure. A junction that stays there balances bit for bit as it would with no table. */
        final double[] flashFrom;
        /** The tables keyed by the junction state they were built for (a mint's state is the first step's), over one hull
         * ({low, top, flashFrom} in the fourth row); a liquid junction that does not flash keeps an entry with no table. */
        static final Map<FluidThermodynamics.State,double[][]> TABLE_CACHE=Collections.synchronizedMap(new WeakHashMap<>());
        /** Per pump connection, whether the balance put it on its suction limit: its suction junction is anchored at the
         * liquid's bubble pressure ({@link MoverSuction#suctionFloors}) and the pump passes what the suction line delivers there. */
        final boolean[] suctionLimited,onLaw;final int[][] nodeEdges;final double[][] lastQ;
        /** Closed for this solve by a start closure, a blocked way or a refused inlet, never to reopen; {@code closed} also holds
         * the device modes the balance may revise (a closed valve). */
        final boolean[] hardClosed;double rhoMax,rises,zLow=Double.POSITIVE_INFINITY,zHigh=Double.NEGATIVE_INFINITY;
        double low,high,scale;int band;
        BalanceNet(PassiveNetwork graph,PassiveNetwork buffers,List<FlowControl.Mode> modes,boolean[] boundaryClosed,Runnable checkpoint,double[] fixedOpenings) {
            this.fixedOpenings=fixedOpenings.length==graph.pipes().size()?fixedOpenings.clone():new double[graph.pipes().size()];if(fixedOpenings.length!=graph.pipes().size())Arrays.fill(this.fixedOpenings,Double.NaN);
            this.buffers=buffers;
            tableP=new double[graph.reservoirs().size()][];tableRho=new double[graph.reservoirs().size()][];tableRhoV=new double[graph.reservoirs().size()][];
            flashFrom=new double[graph.reservoirs().size()];Arrays.fill(flashFrom,Double.POSITIVE_INFINITY);
            suctionLimited=new boolean[graph.pipes().size()];onLaw=new boolean[graph.pipes().size()];nodeEdges=incidence(graph);lastQ=new double[graph.pipes().size()][2];
            this.graph=graph;this.modes=modes;edges=graph.pipes().size();nodes=graph.reservoirs().size();
            closed=new boolean[edges];hardClosed=new boolean[edges];unknown=new int[nodes];Arrays.fill(unknown,-1);anchor=new double[nodes];Arrays.fill(anchor,Double.NaN);
            rho=new double[edges][2];mu=new double[edges][2];cap=new double[edges][2];column=new double[edges][2];endP=new double[edges][2];filterR=new double[edges][2];
            allowed=new boolean[edges][2];gas=new boolean[nodes];mover=new boolean[edges];reference=new double[nodes];prescribed=new double[edges];Arrays.fill(prescribed,Double.NaN);floors=new double[edges];Arrays.fill(floors,Double.NaN);
            for(int i=0;i<nodes;i++){var node=graph.reservoirs().get(i);var s=node.state();reference[i]=s.pressure();gas[i]=node.junction()&&s.condensedVolume()==0&&s.pressure()>0;}
            low=Double.POSITIVE_INFINITY;high=Double.NEGATIVE_INFINITY;scale=0;
            for(int i=0;i<nodes;i++){zLow=Math.min(zLow,graph.reservoirs().get(i).elevation());zHigh=Math.max(zHigh,graph.reservoirs().get(i).elevation());}
            for(int e=0;e<edges;e++) {
                var pipe=graph.pipes().get(e);var mode=modes.get(e);
                hardClosed[e]=boundaryClosed[e]||pipe.effectiveBlockedDirections()==3||mode==FlowControl.Mode.INLET_WRONG_PHASE;
                closed[e]=hardClosed[e]||mode==FlowControl.Mode.CLOSED;
                if(!closed[e])prepare(e,checkpoint);
            }
            // The unknowns: junctions a node that holds its pressure reaches through pressure-coupling connections (a
            // prescribed flow couples nothing), numbered breadth first so the Jacobian is banded; an anchored junction is a
            // held node; a junction nothing reaches (a sealed pocket) keeps its pressure.
            number();
            for(int i=0;i<nodes;i++)if(!Double.isNaN(anchor[i])){low=Math.min(low,anchor[i]);high=Math.max(high,anchor[i]);}
            if(!(high>=low)){low=Double.NaN;high=Double.NaN;return;}
            double margin=rhoMax*GRAVITY*(zHigh-zLow)+1;low=Math.max(low-margin-rises,1e-3*high);high+=margin+rises;
            for(int j=0;j<nodes;j++) {
                if(unknown[j]<0)continue;var s=graph.reservoirs().get(j).state();if(!FluidThermodynamics.holdsFluid(s))continue;
                // W3b: any liquid, water's among them (a single liquid of half water or more is labelled aqueous).
                boolean vapour=FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS),anyLiquid=FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS),liquid=!vapour&&anyLiquid;
                if(!liquid&&!(vapour&&anyLiquid))continue;
                // A liquid is a liquid from its stated pressure up. Below its bubble pressure it flashes and its density falls
                // sixty times, which the balance must see: read as a liquid, the junctions under a 1.35 MPa LNG line with a
                // 1.25 MPa bubble point fell to 1 atm still liquid and 40 K superheated, and flashing them one by one stalled
                // Newton (cold seed 907, whose start the crystal competition's 2 K of heat had made two-phase).
                double top=liquid?Math.min(high,s.pressure()):high;if(!(top>low))continue;
                var cached=TABLE_CACHE.get(s);
                if(cached!=null&&cached[3][0]==low&&cached[3][1]==top){if(cached[1]!=null){tableP[j]=cached[0];tableRho[j]=cached[1];tableRhoV[j]=cached[2];flashFrom[j]=cached[3][2];}continue;}
                double[] held=PhaseLayout.totalAmounts(s);double h=s.enthalpy();double[] tp=new double[TABLE],tr=new double[TABLE],tv=new double[TABLE];boolean ok=true;double flash=Double.POSITIVE_INFINITY;
                for(int k=0;k<TABLE;k++)tp[k]=low*DeterministicMath.exp(DeterministicMath.log(top/low)*k/(TABLE-1));
                for(int k=0;k<TABLE&&ok&&flash==Double.POSITIVE_INFINITY;k++) {
                    // A two-phase junction reads the fluid phases first: with the crystals carried at every point cold seed 244
                    // held 20 times with a 12 s job, on the rows of junctions holding vapour and near-pure carbon dioxide at 1 atm
                    // (0 holds, 422 ms fluid-only). A point they refuse, the two-liquid window every LNG flash ends in, is carried:
                    // with no table a junction read its stated density at every pressure, and under a line opened to a void it
                    // fell from 980 to 225 kPa where its density carries the flow at about 1 MPa (cold seed 651's job 88 stalled).
                    try{var at=liquid?null:model.fluidPH(s.temperature(),tp[k],h,held.clone(),checkpoint);
                        if(at==null)at=model.equilibriumPH(s.temperature(),tp[k],h,held.clone(),checkpoint);
                        if(at==null||!FluidThermodynamics.holdsFluid(at)){ok=false;break;}
                        tr[k]=at.mass()/at.volume();tv[k]=tr[k]*model.velocityLimit(at);
                        if(liquid&&!FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS)){flash=tp[k];for(int i=k+1;i<TABLE;i++){tr[i]=tr[k];tv[i]=tv[k];}}}
                    catch(IllegalArgumentException refused){ok=false;}
                }
                if(flash==tp[0]){TABLE_CACHE.put(s,new double[][]{tp,null,null,{low,top,flash}});continue;}
                if(ok){tableP[j]=tp;tableRho[j]=tr;tableRhoV[j]=tv;flashFrom[j]=flash;TABLE_CACHE.put(s,new double[][]{tp,tr,tv,{low,top,flash}});}
            }
        }
        /** The held data of open connection {@code e}: each end's density, viscosity, cap, column and end pressure, its one-way
         * rules and filter law, a mover's prescribed flow or rise, a regulating valve's anchor; the hull and scale grow with it. */
        private void prepare(int e,Runnable checkpoint) {
            var pipe=graph.pipes().get(e);var mode=modes.get(e);
            for(int dir=0;dir<2;dir++) {
                int donor=dir==0?pipe.first():pipe.second();var node=graph.reservoirs().get(donor);var port=pipe.portAt(donor);var s=node.state();
                rho[e][dir]=PortStreams.endDensity(model,s,port,pipe.volume());mu[e][dir]=solver.endViscosity(s,port,pipe.volume());cap[e][dir]=solver.massFlowLimit(pipe,s,port);
                column[e][dir]=PortStreams.columnDensity(model,s,port,pipe.volume());allowed[e][dir]=boundaryAllowed(graph,pipe,dir==0?1:-1);
                endP[e][dir]=PortStreams.endPressure(model,graph,pipe,donor);
                if(pipe.filter()!=null){double loading=pipe.filter().loading();filterR[e][dir]=cakeResistance(pipe.filter(),solver.carrierViscosity(s),loading)/rho[e][dir];}
                rhoMax=Math.max(rhoMax,column[e][dir]);scale=Math.max(scale,cap[e][dir]);
                if(!node.junction()){low=Math.min(low,endP[e][dir]);high=Math.max(high,endP[e][dir]);}
            }
            if(pipe.control() instanceof FlowControl.Mover pump) {
                allowed[e][1]=false;var a=graph.reservoirs().get(pipe.first());
                // A pump that arrives on its suction limit is stated as the balance puts one there: its suction junction anchored at
                // the floor, the pump passing what arrives ({@link #solve} sets the flow once the island balances around it). Anchored
                // on the hydrocarbon floor alone, a water pump's suction was free again in the next round, the pump drew 72.6 kg/s
                // on its rise law and the junction fell to the hull's 101 Pa, stated as steam (PumpCavitationTest, starved suction).
                if(mode==FlowControl.Mode.PUMP_SUCTION_LIMIT&&a.junction()&&suctionFloor(e,checkpoint)<Double.POSITIVE_INFINITY){anchor[pipe.first()]=cavitationFloor(e,0);suctionLimited[e]=true;prescribed[e]=0;}
                else{mover[e]=true;onLaw[e]=mode==FlowControl.Mode.MOVER_LAW;}
                rises+=solver.riseLimit(graph,e,rho[e][0],a.state().pressure());
            } else if(pipe.control() instanceof FlowControl.Valve valve) {
                if(!valve.bare())allowed[e][1]=false;
                if(mode==FlowControl.Mode.VALVE_REGULATING&&valve.pressureControl()&&graph.reservoirs().get(pipe.first()).junction())anchor[pipe.first()]=valve.targetPressure();
                else if(mode==FlowControl.Mode.VALVE_REGULATING&&!valve.pressureControl())prescribed[e]=valve.targetMassFlow(rho[e][0]);
            }
        }
        /** The suction limit of the pump on connection {@code e} with no flow ({@link MoverSuction#suctionLimitPressure},
         * owner decision D14 as W3B_NETWORK_PLAN.md section 17 restates it: p_v and the clean zone's edge); infinite where it
         * has none. One statement for a pump the balance puts on its suction limit and for one that arrives there, worked out
         * once per connection. The NPSH the pump's flow adds is {@link #cavitationFloor}'s. The water curve D59 (4) put beside it where
         * the junction held free water (BalanceNet.waterFloor) was lifted by the owner on 2026-11-11: every liquid's floor
         * is at its own enthalpy ({@link MoverSuction#releasePressure}), so water's is no longer a second one. */
        private double suctionFloor(int e,Runnable checkpoint) {
            if(!Double.isNaN(floors[e]))return floors[e];
            return floors[e]=solver.suctions.suctionLimitPressure(graph,e,0);
        }
        /** Owner decision D20 Q11 A in the balance: the suction limit of pump connection {@code e}
         * ({@link MoverSuction#suctionLimitPressure}) with its breakdown NPSH ({@link MoverSuction#breakdown}) at mass flow {@code massFlow}
         * (kg/s) on its suction end's density: the flow the balance gives it, its law's at the balanced pressures where it
         * decides the mode, its prescribed flow on its suction limit, none where it arrives there. The Newton's row reads the
         * same floor at its trial flow ({@link MoverSuction#suctionLimit}). */
        private double cavitationFloor(int e,double massFlow) {
            return solver.suctions.suctionLimitPressure(graph,e,solver.suctions.breakdown(graph,e,Math.max(0,massFlow)/rho[e][0]));
        }
        /** Whether junction {@code node} reads its table at {@code pressure}, not its stated data. */
        private boolean tabled(int node,double pressure){return tableP[node]!=null&&pressure<flashFrom[node];}
        /** Linear interpolation of {@code table} in ln P at {@code pressure}, clamped to the table's range. */
        private double at(int node,double[] table,double pressure) {
            double[] tp=tableP[node];if(pressure<=tp[0])return table[0];if(pressure>=tp[TABLE-1])return table[TABLE-1];
            int k=1;while(k<TABLE-1&&tp[k]<pressure)k++;
            double w=DeterministicMath.log(pressure/tp[k-1])/DeterministicMath.log(tp[k]/tp[k-1]);return table[k-1]+w*(table[k]-table[k-1]);
        }
        /** The unknowns: junctions a node that holds its pressure reaches through pressure-coupling connections, numbered breadth
         * first (a banded Jacobian); an anchored junction is a held node; a junction nothing reaches keeps its pressure. */
        private void number() {
            Arrays.fill(unknown,-1);anchored=0;band=0;
            var queue=new ArrayDeque<Integer>();boolean[] seen=new boolean[nodes];int n=0;
            for(int i=0;i<nodes;i++)if(!graph.reservoirs().get(i).junction()||!Double.isNaN(anchor[i])){seen[i]=true;queue.add(i);if(!Double.isNaN(anchor[i]))anchored++;}
            while(!queue.isEmpty()){int at=queue.remove();
                for(int e:nodeEdges[at]){if(closed[e]||!Double.isNaN(prescribed[e]))continue;var pipe=graph.pipes().get(e);int other=pipe.first()==at?pipe.second():pipe.first();
                    if(!seen[other]){seen[other]=true;unknown[other]=n++;queue.add(other);}}}
            count=n;
            for(int e=0;e<edges;e++){var pipe=graph.pipes().get(e);if(!closed[e]&&unknown[pipe.first()]>=0&&unknown[pipe.second()]>=0)band=Math.max(band,Math.abs(unknown[pipe.first()]-unknown[pipe.second()]));}
        }
        /** The net inflow of junction {@code node} through every open connection but {@code except}, at {@code p}. */
        private double inflowExcept(int node,int except,double[] p) {
            double net=0;
            for(int e:nodeEdges[node]){if(e==except||closed[e])continue;var pipe=graph.pipes().get(e);double q=flow(e,endAt(e,0,p),endAt(e,1,p));net+=pipe.second()==node?q:-q;}
            return net;
        }
        private double scale(int node,double pressure){return unknown[node]>=0&&gas[node]?pressure/reference[node]:1;}
        /** The connection's flow at end pressures {@code pa}, {@code pb} (positive first to second): its law in the donor's
         * column and stream, saturated at the donor's cap, nothing in a refused direction; a mover's prescribed flow, or its
         * law with its rise one way. */
        double flow(int e,double pa,double pb) {
            if(closed[e]||!Double.isNaN(fixedOpenings[e])&&fixedOpenings[e]==0)return 0;
            if(!Double.isNaN(prescribed[e]))return prescribed[e];
            var pipe=graph.pipes().get(e);
            double dz=graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation();
            int a=pipe.first(),b=pipe.second();double area=pipe.minimumArea();
            boolean tableA=tabled(a,pa);
            double rhoA=tableA?at(a,tableRho[a],pa):rho[e][0]*scale(a,pa),capA=tableA?at(a,tableRhoV[a],pa)*area:cap[e][0]*scale(a,pa),colA=tableA?rhoA:column[e][0]*scale(a,pa);
            double rise=mover[e]?solver.riseLimit(graph,e,rhoA,pa):0;
            double forward=pa-pb-colA*GRAVITY*dz+rise;
            if(forward>0&&mover[e]&&onLaw[e])return allowed[e][0]?curveFlow(e,forward-rise,rhoA,pa,mu[e][0],capA):0;
            if(forward>0)return allowed[e][0]?runFlow(e,0,forward,rhoA,mu[e][0],capA):0;
            if(mover[e])return 0;
            boolean tableB=tabled(b,pb);
            double rhoB=tableB?at(b,tableRho[b],pb):rho[e][1]*scale(b,pb),capB=tableB?at(b,tableRhoV[b],pb)*area:cap[e][1]*scale(b,pb),colB=tableB?rhoB:column[e][1]*scale(b,pb);
            double reverse=pa-pb-colB*GRAVITY*dz;
            if(reverse<0)return allowed[e][1]?-runFlow(e,1,-reverse,rhoB,mu[e][1],capB):0;
            return 0;
        }
        private double runFlow(int e,int dir,double driving,double rho,double mu,double cap) {
            if(!(cap>0))return 0;
            if(filterR[e][dir]>0)return saturated(driving/filterR[e][dir],cap);
            var pipe=graph.pipes().get(e);
            // Newton on the law from the last flow found on this end (a bracket grows from it), bisection where a step leaves the bracket.
            // The loss rises strictly with the flow, so the root is unique and the start only saves iterations. The bracket ends
            // the search only once it is closed: while it is open above, 1e-14 of an infinite bracket is no width, and the test
            // ended every search started under its root after one laminar step (a 1 cm line under 2.57 MPa read 1 484 kg/s where
            // its law gives 1.536, so the balance's residual at fixed pressures moved with the history of starts, cold seed 743).
            double lo=0,hi=Double.POSITIVE_INFINITY,q=lastQ[e][dir]>0?lastQ[e][dir]:cap;
            for(int k=0;k<60;k++) {
                var loss=pipe.loss(q,rho,mu);double restriction=Double.isNaN(fixedOpenings[e])?0:.5/(rho*boreArea(pipe)*boreArea(pipe))*(1/(fixedOpenings[e]*fixedOpenings[e])-1);double f=loss.pressureDrop()+restriction*q*q-driving;
                if(Math.abs(f)<=1e-12*driving)break;
                if(f>0)hi=q;else lo=q;
                double next=q-f/(loss.massFlowDerivative()+2*restriction*q);
                if(hi==Double.POSITIVE_INFINITY)q=next>lo?next:2*q;else q=next>lo&&next<hi?next:.5*(lo+hi);
                if(hi<Double.POSITIVE_INFINITY&&hi-lo<=1e-14*hi)break;
            }
            lastQ[e][dir]=q;
            return saturated(q,cap);
        }
        /**
         * The forward flow of mover connection {@code e} on its own law ({@code MOVER_LAW}, batch 2026-11-03-rotating-machinery)
         * at static drive {@code drive} (Pa: the end pressures less the column), with the suction at {@code rho} and
         * {@code pressure}: the law flow q of {@code loss(q) - drive - rise(saturated(q)) = 0}, the Newton's row, whose rise
         * reads the flow the velocity cap lets through. Unique because the loss rises with the law flow and the rise falls.
         * Newton from the last flow found, bisection where a step leaves the bracket, as {@link #runFlow}.
         */
        private double curveFlow(int e,double drive,double rho,double pressure,double mu,double cap) {
            if(!(cap>0))return 0;
            var pipe=graph.pipes().get(e);
            double nominal=rho*((FlowControl.Mover)pipe.control()).targetVolumeFlow();
            double lo=0,hi=Double.POSITIVE_INFINITY,q=lastQ[e][0]>0?lastQ[e][0]:nominal>0?Math.min(cap,nominal):cap;
            for(int k=0;k<80;k++) {
                double moved=saturated(q,cap),rise=solver.lawRise(graph,e,moved/rho,rho,pressure);
                var loss=pipe.loss(q,rho,mu);double f=loss.pressureDrop()-drive-rise;
                if(Math.abs(f)<=1e-12*Math.max(Math.abs(drive),Math.abs(rise)))break;
                if(f>0)hi=q;else lo=q;
                double next=q-f/(loss.massFlowDerivative()-solver.lawRiseSlope(graph,e,moved/rho,rho,pressure)*saturationSlope(q,cap)/rho);
                if(hi==Double.POSITIVE_INFINITY)q=next>lo?next:2*q;else q=next>lo&&next<hi?next:.5*(lo+hi);
                if(hi<Double.POSITIVE_INFINITY&&hi-lo<=1e-14*hi)break;
            }
            lastQ[e][0]=q;
            return saturated(q,cap);
        }
        private double endAt(int e,int end,double[] p){var pipe=graph.pipes().get(e);int node=end==0?pipe.first():pipe.second();return unknown[node]>=0?p[node]:!Double.isNaN(anchor[node])?anchor[node]:endP[e][end];}
        double residual(double[] p,double[] residual,double[][] jacobian) {
            Arrays.fill(residual,0);
            for(int e=0;e<edges;e++) {
                if(closed[e])continue;var pipe=graph.pipes().get(e);int a=pipe.first(),b=pipe.second();
                double pa=endAt(e,0,p),pb=endAt(e,1,p),q=flow(e,pa,pb);
                if(unknown[a]>=0)residual[unknown[a]]-=q;
                if(unknown[b]>=0)residual[unknown[b]]+=q;
                if(jacobian==null)continue;
                for(int end=0;end<2;end++) {
                    int node=end==0?a:b,col=unknown[node];if(col<0)continue;
                    // The slope over 1e-9 of the end pressure: over 1e-7 (0.32 Pa at 3.16 MPa) a line between neighbouring junctions carrying
                    // 2 g/s across 6 mPa was crossed through its zero flow, so its slope was a secant across the column's dead band and no
                    // Newton step descended (cold seed 729: 124 balances ended unconverged, 117 on the sweep budget; 7 over 1e-9). The
                    // inversion resolves 1e-12 of a drive, so 1e-9 of the pressure is still a thousand times its noise.
                    double at=end==0?pa:pb,delta=1e-9*Math.max(1,Math.abs(at));
                    double dq=((end==0?flow(e,pa+delta,pb):flow(e,pa,pb+delta))-q)/delta;
                    if(unknown[a]>=0)jacobian[unknown[a]][col-unknown[a]+band]-=dq;
                    if(unknown[b]>=0)jacobian[unknown[b]][col-unknown[b]+band]+=dq;
                }
            }
            double merit=0;for(double r:residual)merit+=r*r;
            return .5*merit;
        }
        /**
         * One Gauss-Seidel sweep: each junction is bisected on the sign of its net inflow inside the hull, except a sealed one
         * ({@link #still}), which keeps its pressure (t56's trace of cold seed 743): a junction between closed lines and a
         * one-way supply it stands above has no inflow at any pressure above that supply's, and the bisection put it on the
         * edge of that flat range, a liquid-full fitting at 4.96 MPa seeded as vapour at the supply's 485 kPa, where no step
         * converged. A sealed fitting holds its fluid and its pressure.
         */
        void sweep(double[] p) {
            int[][] nodeEdges=incidence(graph);
            for(int node=0;node<nodes;node++) {
                if(unknown[node]<0||still(node,p))continue;
                double lo=low,hi=high;
                for(int k=0;k<30&&hi-lo>1e-9*high;k++) {
                    double mid=.5*(lo+hi),net=0;p[node]=mid;
                    for(int e:nodeEdges[node]){if(closed[e])continue;var pipe=graph.pipes().get(e);double q=flow(e,endAt(e,0,p),endAt(e,1,p));net+=pipe.second()==node?q:-q;}
                    if(net>0)lo=mid;else hi=mid;
                }
                p[node]=.5*(lo+hi);
            }
        }
        /** The junction pressures: a damped Newton on the banded Jacobian inside the hull, a Gauss-Seidel sweep where a
         * step does not descend; the last iterate when the budget runs out ({@code converged} false). */
        double[] solve(Runnable checkpoint) {
            double[] p=solveOnce(checkpoint);
            // The balance decides no pump's mode (option F2 of START_HOLD_REVIEW.md, batch 2026-11-16-review-plant-sizing): a pump
            // starts on its law, or closed where it is vapour-locked (owner decision D16 A, {@link MoverSuction#vapourLocks}), and
            // the pass loop puts it on its suction limit ({@link DeviceModes#cavitates}, {@link DeviceModes#nextMode}, the row of
            // option D). Deciding it here anchored the suction at the floor and iterated the pump's delivery as what arrived there:
            // on a short crude suction line that swung between 0 and 1070 kg/s, and a round ended on a discharge junction drained
            // to the hull's 0.25 kPa, where a one-way valve passes nothing. The rounds below decide pressure valves only.
            for(int round=0;round<4;round++) {
                boolean changed=false;
                for(int e=0;e<edges;e++) {
                    var pipe=graph.pipes().get(e);int a=pipe.first(),b=pipe.second();var mode=modes.get(e);
                    // Owner decision of 2026-10-19 (t46's trace of cold 271): the balance decides a pressure valve's mode on its balanced
                    // pressures as it decides a pump's, the pass loop's own transitions taken at the start. A closed back-pressure valve
                    // whose balanced inlet stands above its target with a forward drive regulates, its inlet anchored at the target (271:
                    // minted at 100.9 kPa the valve started closed, the closed network balanced at 1 125 kPa and pass 1 was singular);
                    // one that nothing reaches closes; one the open valve cannot relieve at its target opens; an open one whose inlet
                    // falls under the target regulates again.
                    if(pipe.control() instanceof FlowControl.Valve valve&&valve.pressureControl()&&graph.reservoirs().get(a).junction()&&!hardClosed[e]) {
                        double target=valve.targetPressure();
                        if(mode==FlowControl.Mode.CLOSED) {
                            double pa=endAt(e,0,p),pb=endAt(e,1,p),dz=graph.reservoirs().get(b).elevation()-graph.reservoirs().get(a).elevation();
                            double drive=pa-pb-PortStreams.columnDensity(model,graph.reservoirs().get(a).state(),pipe.firstPort(),pipe.volume())*GRAVITY*dz;
                            if(pa>target+.01&&drive>.01){closed[e]=false;modes.set(e,FlowControl.Mode.VALVE_REGULATING);prepare(e,checkpoint);anchor[a]=target;changed=true;}
                        } else if(mode==FlowControl.Mode.VALVE_REGULATING) {
                            double inflow=inflowExcept(a,e,p),pass=flow(e,target,endAt(e,1,p));
                            if(inflow<=0){modes.set(e,FlowControl.Mode.CLOSED);closed[e]=true;anchor[a]=Double.NaN;changed=true;}
                            else if(inflow>pass*(1+1e-9)){modes.set(e,FlowControl.Mode.VALVE_OPEN);anchor[a]=Double.NaN;changed=true;}
                        } else if(mode==FlowControl.Mode.VALVE_OPEN) {
                            double pa=endAt(e,0,p);
                            if(pa<target-.01&&flow(e,pa,endAt(e,1,p))>1e-10){modes.set(e,FlowControl.Mode.VALVE_REGULATING);anchor[a]=target;changed=true;}
                        }
                        continue;
                    }
                }
                if(!changed)break;
                number();p=solveOnce(checkpoint);
            }
            return p;
        }
        private double[] solveOnce(Runnable checkpoint) {
            double[] p=new double[nodes];
            for(int i=0;i<nodes;i++)p[i]=!Double.isNaN(anchor[i])?anchor[i]:graph.reservoirs().get(i).state().pressure();
            if(count==0||Double.isNaN(low)){converged=true;return p;}
            for(int i=0;i<nodes;i++)if(unknown[i]>=0)p[i]=Math.clamp(p[i],low,high);
            double[] residual=new double[count];double merit=residual(p,residual,null);
            // 1e-6 of the island's largest cap flow: a start, above the floor of a Newton on finite-difference slopes (1e-9 relative).
            double tolerance=1e-6*Math.max(1,scale);
            for(int iteration=0;iteration<30;iteration++) {
                checkpoint.run();finalResidual=maximum(residual);
                if(finalResidual<=tolerance){converged=true;break;}
                iterations++;
                double[][] jacobian=new double[count][2*band+1];residual(p,new double[count],jacobian);
                double floor=0;for(int i=0;i<count;i++)floor=Math.max(floor,Math.abs(jacobian[i][band]));floor=Math.max(floor*1e-12,Double.MIN_NORMAL);
                for(int i=0;i<count;i++)if(Math.abs(jacobian[i][band])<floor)jacobian[i][band]=-floor;
                double[] step=solveBanded(jacobian,band,residual);boolean accepted=false;double largest=0,took=0;
                if(step!=null) {
                    for(double s:step)largest=Math.max(largest,Math.abs(s));
                    double limit=Math.min(1,.5*(high-low)/Math.max(largest,Double.MIN_NORMAL));
                    double[] trial=p.clone(),trialResidual=new double[count];
                    for(double fraction=limit;fraction>1e-3*limit;fraction*=.5) {
                        for(int i=0;i<nodes;i++)if(unknown[i]>=0)trial[i]=Math.clamp(p[i]-fraction*step[unknown[i]],low,high);
                        double trialMerit=residual(trial,trialResidual,null);
                        if(trialMerit<(1-1e-4*fraction)*merit){System.arraycopy(trial,0,p,0,nodes);System.arraycopy(trialResidual,0,residual,0,count);merit=trialMerit;accepted=true;took=fraction;break;}
                    }
                }
                // Where the Newton step does not descend (a cap or a one-way kink), one Gauss-Seidel sweep, kept only where it
                // improves on the Newton's point: a sweep across a choked junction's flat range can walk away from a root the
                // Newton has all but reached, and then neither method improves on the other.
                if(!accepted){if(++sweeps>12)break;double[] was=p.clone(),wasResidual=residual.clone();double wasMerit=merit;sweep(p);merit=residual(p,residual,null);
                    if(!(merit<wasMerit)){System.arraycopy(was,0,p,0,nodes);System.arraycopy(wasResidual,0,residual,0,count);merit=wasMerit;break;}
                    double moved=0;for(int i=0;i<nodes;i++)if(unknown[i]>=0)moved=Math.max(moved,Math.abs(p[i]-was[i])/Math.max(1,Math.abs(was[i])));
                    if(moved<1e-12)break;}
            }
            finalResidual=maximum(residual);converged=finalResidual<=tolerance;
            rest(p);
            return p;
        }
        /**
         * A junction the hull's clamp holds on its bound with nothing flowing through it has no balance there: the bound is
         * the search's box, not a pressure. It moves back toward its own pressure as far as its connections still carry
         * nothing: to its own pressure, or to the edge of its no-flow range nearest it. Its flows stay zero, so no row changes.
         * A no-flow range has a zero merit at every point, so the Newton's first step into it is accepted where it lands, the
         * clamp included (defect 3 of batch 2026-11-11-drum-operation-holds: a leg's foot at 300 Pa, the hull's floor,
         * instead of its column's 52 kPa). A junction inside a no-flow band is a balance anywhere in it and stays: moved to the
         * band's edge, a cryogenic pocket two-phase near its dew point stalled the step (cold seed 65).
         */
        private void rest(double[] p) {
            for(int node=0;node<nodes;node++) {
                if(unknown[node]<0||p[node]>low&&p[node]<high||!still(node,p))continue;
                double own=Math.clamp(reference[node],low,high),near=p[node],far=own;
                p[node]=own;if(still(node,p))continue;
                for(int k=0;k<60&&Math.abs(far-near)>1e-12*Math.max(1,Math.abs(own));k++){double mid=.5*(near+far);p[node]=mid;if(still(node,p))near=mid;else far=mid;}
                p[node]=near;
            }
        }
        /** Whether no open connection of junction {@code node} carries anything at {@code p}: each is refused the way its drive
         * points, or has none (a sealed fitting, whose balance holds at its own pressure). */
        private boolean still(int node,double[] p) {
            for(int e:nodeEdges[node]){if(closed[e])continue;if(flow(e,endAt(e,0,p),endAt(e,1,p))!=0)return false;}
            return true;
        }
        /**
         * The graph with every balanced junction at its pressure and the upwind mixture of what the balanced flows deliver (PH
         * at that pressure); null when nothing moved. A junction nothing reaches mixes only its own buffer in its row, so it is
         * stated as that buffer let down at its own enthalpy (PH; {@link #held}); at the mint, which has no buffer yet, at its
         * temperature. t56b's trace of the whole cold seed 743 world: a sealed junction holding 2.25 kg of LNG and CO2 at
         * 4.96 MPa and 200.4 K, put on its 485 kPa supply's pressure, was restated at its old temperature, as vapour holding
         * about 1 MJ more than its buffer (0.34 m3), so its energy row had no root and every step stalled; at its own enthalpy
         * it is a two-phase mixture at 165 K, as the real liquid flashes.
         */
        PassiveNetwork seeded(double[] p,Runnable checkpoint) {
            double[] q=new double[edges];
            for(int e=0;e<edges;e++)q[e]=closed[e]?0:flow(e,endAt(e,0,p),endAt(e,1,p));
            var states=new ArrayList<FluidThermodynamics.State>(nodes);for(var node:graph.reservoirs())states.add(node.state());
            int count=model.hydrocarbon.componentCount()+1;boolean any=false;
            for(int sweep=0;sweep<=nodes;sweep++) {
                boolean changed=false;
                for(int j=0;j<nodes;j++) {
                    var node=graph.reservoirs().get(j);if(!node.junction()||unknown[j]<0&&Double.isNaN(anchor[j]))continue;
                    double pressure=!Double.isNaN(anchor[j])?anchor[j]:p[j];
                    double[] amounts=new double[count];double mass=0,heat=0,weighted=0,tLow=Double.POSITIVE_INFINITY,tHigh=Double.NEGATIVE_INFINITY;
                    for(int e=0;e<edges;e++) {
                        var pipe=graph.pipes().get(e);double flow=q[e];
                        if(!(Math.abs(flow)>0)||(flow>=0?pipe.second():pipe.first())!=j)continue;
                        int donor=flow>=0?pipe.first():pipe.second();var stream=states.get(donor);var port=pipe.drawPort(flow);
                        double rate=Math.abs(flow),streamMass=PortStreams.endMass(model,stream,port,pipe.volume());double[] moles=PortStreams.endMoles(model,stream,port,pipe.volume());
                        if(!(streamMass>0))continue;
                        for(int c=0;c<count;c++)amounts[c]+=rate*moles[c]/streamMass;
                        var through=solver.delivered(graph,pipe,PortStreams.endSpecificEnthalpy(model,stream,port,pipe.volume()),stream.temperature(),flow,checkpoint);
                        mass+=rate;heat+=rate*through[0];weighted+=rate*through[1];
                        tLow=Math.min(tLow,through[1]);tHigh=Math.max(tHigh,through[1]);
                    }
                    var old=states.get(j);FluidThermodynamics.State next;
                    try {
                        // A junction that already holds the delivered mixture at that pressure is not flashed again (the second
                        // round's unchanged junctions, a structure balanced once more): the flashes were the balance's cost.
                        if(mass>0) {
                            next=holdsMixture(old,amounts,mass,heat,pressure)?null:mixed(amounts,mass,heat,weighted/mass,tLow,tHigh,pressure,checkpoint);
                            // At the junction's own scale (the mixture is stated per kilogram): the solid event integrator reads a junction's
                            // populations against its state's volume, and the rate seed writes the state back into the step's graph.
                            if(next!=null){double oldFluid=old.mass()-old.solidMoments().mass(),newFluid=next.mass()-next.solidMoments().mass();if(oldFluid>0&&newFluid>0)next=scaled(next,oldFluid/newFluid);}
                        }
                        else if(!(Math.abs(pressure-old.pressure())>1e-9*old.pressure()))next=null;
                        else if(buffers==null)next=model.flashTP(old.temperature(),pressure,PhaseLayout.totalAmounts(old),checkpoint);
                        else{next=held(buffers.reservoirs().get(j).state(),pressure,checkpoint);
                            if(next!=null){double oldFluid=old.mass()-old.solidMoments().mass(),newFluid=next.mass()-next.solidMoments().mass();if(oldFluid>0&&newFluid>0)next=scaled(next,oldFluid/newFluid);}}
                    } catch(IllegalArgumentException refused){next=null;}
                    if(next==null)continue;
                    // The junction's solids ride its fluid: the moments scale with the fluid mass the mixture is stated for.
                    double oldFluid=old.mass()-old.solidMoments().mass(),ratio=oldFluid>0?next.mass()/oldFluid:1;var m=old.solidMoments();
                    next=next.withSolidState(old.solids(),new SolidInventory.Moments(m.mass()*ratio,m.volume()*ratio,m.heatCapacity()*ratio));
                    if(!sameStates(List.of(old),List.of(next))){states.set(j,next);changed=true;any=true;}
                }
                if(!changed)break;
            }
            if(!any)return null;
            var reservoirs=new ArrayList<PassiveNetwork.Reservoir>(nodes);
            for(int i=0;i<nodes;i++){var node=graph.reservoirs().get(i);reservoirs.add(states.get(i)==node.state()?node:new PassiveNetwork.Reservoir(node.id(),node.elevation(),states.get(i),node.kind(),node.inventory()));}
            return new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
        }
        /** {@code buffer}'s fluid at {@code pressure} at its own enthalpy (PH), per kilogram; null where it holds no fluid. */
        private FluidThermodynamics.State held(FluidThermodynamics.State buffer,double pressure,Runnable checkpoint) {
            double mass=buffer.mass()-buffer.solidMoments().mass();if(!(mass>0))return null;
            double heat=buffer.enthalpy()-buffer.solidMoments().enthalpy(buffer.temperature(),buffer.pressure());
            return mixed(PhaseLayout.totalAmounts(buffer),mass,heat,buffer.temperature(),buffer.temperature(),buffer.temperature(),pressure,checkpoint);
        }
        /** Whether {@code old} already is the delivered mixture ({@code amounts} and {@code heat} over {@code mass}) at {@code pressure},
         * within 1e-6 of each per-kilogram amount, the specific enthalpy and the pressure: a start guess needs no closer a flash. */
        private boolean holdsMixture(FluidThermodynamics.State old,double[] amounts,double mass,double heat,double pressure) {
            double oldMass=old.mass()-old.solidMoments().mass();if(!(oldMass>0))return false;
            if(Math.abs(old.pressure()-pressure)>1e-6*pressure||Math.abs(old.enthalpy()/oldMass-heat/mass)>1e-6*Math.max(1,Math.abs(heat/mass)))return false;
            double[] n=PhaseLayout.totalAmounts(old);double total=0;for(int c=0;c<amounts.length;c++)total+=amounts[c]/mass;
            for(int c=0;c<amounts.length;c++)if(Math.abs(n[c]/oldMass-amounts[c]/mass)>1e-6*total)return false;
            return true;
        }
        /** The mixture per kilogram at {@code pressure} with the delivered specific enthalpy: the engine's PH answer, else a
         * temperature bisection inside the streams' window (20 K either side, inside the numerical box: no data range narrows a seed, owner rule of 2026-10-16).
         * The bisection ends on the bracket side whose state is nearer the delivered enthalpy. Water alone (no engine answer) has
         * no two-phase TP state at its own vapour pressure: its enthalpy jumps by the latent heat across the saturation, and the
         * midpoint's side is round-off. A pump suction the balance holds at its own liquid's vapour pressure ({@link MoverSuction#releasePressure})
         * is delivered the saturated liquid's enthalpy to round-off: the liquid side is that round-off away, the steam side the
         * latent heat. Bundled fuzz seed 889, while the floor read the junction's stated temperature: water arriving 0.06 K
         * warmer from the friction of its 309 kPa descent (1e-4 of it flashes there) was stated all steam, and the step's
         * junction held a steam stock no pass could empty. */
        private FluidThermodynamics.State mixed(double[] amounts,double mass,double heat,double tMix,double tLow,double tHigh,double pressure,Runnable checkpoint) {
            double[] y=new double[amounts.length];for(int c=0;c<y.length;c++)y[c]=amounts[c]/mass;
            double hydrocarbon=0;for(int c=0;c<y.length-1;c++)hydrocarbon+=y[c];
            var answer=hydrocarbon>0?model.equilibriumPH(tMix,pressure,heat/mass,y,checkpoint):null;
            if(answer!=null&&FluidThermodynamics.holdsFluid(answer))return model.folded(answer,true);
            var bracket=mixingBracket(model,y,heat,mass,tLow,tHigh,pressure,checkpoint);double lower=bracket[0],upper=bracket[1];
            var below=model.flashTP(lower,pressure,y.clone(),checkpoint);var above=model.flashTP(upper,pressure,y.clone(),checkpoint);
            double target=heat/mass;
            return model.folded(Math.abs(below.enthalpy()/below.mass()-target)<=Math.abs(above.enthalpy()/above.mass()-target)?below:above,true);
        }
    }
    private static double maximum(double[] values){double m=0;for(double v:values)m=Math.max(m,Math.abs(v));return m;}
    /** Banded Gaussian elimination without pivoting ({@code a} in band storage of half-width {@code band}, overwritten):
     * the solution of {@code a x = b}, or null at a pivot that vanishes against its row. The balance's Jacobian is a
     * weighted graph Laplacian (diagonally dominant up to the gas scaling), which needs no pivoting. */
    private static double[] solveBanded(double[][] a,int band,double[] rightHandSide) {
        int n=rightHandSide.length;double[] b=rightHandSide.clone();
        for(int column=0;column<n;column++) {
            double pivot=a[column][band],size=0;
            for(int k=0;k<=2*band;k++)size=Math.max(size,Math.abs(a[column][k]));
            if(!(Math.abs(pivot)>1e-14*size))return null;
            for(int row=column+1;row<=Math.min(n-1,column+band);row++) {
                double factor=a[row][column-row+band]/pivot;if(factor==0)continue;
                for(int k=column;k<=Math.min(n-1,column+band);k++)a[row][k-row+band]-=factor*a[column][k-column+band];
                b[row]-=factor*b[column];
            }
        }
        double[] x=new double[n];
        for(int row=n-1;row>=0;row--){double sum=b[row];for(int k=row+1;k<=Math.min(n-1,row+band);k++)sum-=a[row][k-row+band]*x[k];x[row]=sum/a[row][band];}
        return x;
    }
}
