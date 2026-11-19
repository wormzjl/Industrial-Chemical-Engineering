package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.HOLDS_NOTHING;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.boundaryAllowed;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.dry;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.hasSolids;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.holdsNoFluidVessel;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.incidence;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.outOfDryEnd;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.phaseCode;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.saturated;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PassStart;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.Seeded;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.StepPhaseRules;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * The start of a step solve's passes: the connection flows and actuator heads a pass starts from ({@link #startPoint}),
 * the node seeds of the first pass ({@link #initialPhaseSeeds}: each vessel's committed layout with what is about to
 * arrive, each junction laid out for its step's mixture, {@link #arrivingPhases}), and the junctions a vapour first
 * appears in seeded where their connections settle them ({@link #flashSeeds}). One per solver
 * ({@code PassiveStepSolver.seeding}), built with it; it reads the solver's carried point, balance law flows, solve
 * constants and laws through {@code solver} and holds no state of its own (S-01, batch 2026-11-13-codebase-review, D-S5:
 * moved out of the solver with every body unchanged but its qualifiers).
 */
final class StartSeeds {
    /** The solver whose passes this starts: its carried point, its balance law flows, its solve constants and its laws. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    StartSeeds(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /**
     * A trial point for one pass, per node. A vessel is seeded with its own state, its committed layout (owner decision
     * D40 A): a reachable component it lacks ({@link PassiveStepSolver#reachableComponents}) enters as an entry trace of {@code 1e-12} of
     * its amount, restated in the phases it holds ({@link ConservativeTransport#repartition}) and placed by the layout's
     * own split; a phase what is about to arrive carries and the layout lacks enters with what arrives of it
     * ({@link #arrivingInVessel}). The seed was the TP flash of the inventory with up to a quarter of its mass of what
     * arrives: for a pool of pure methane at saturation fed CO2 that put 99 % of the moles in the vapour, 183 m3 in a 1 m3
     * tank. A junction is seeded with its own state, its owned holdup a small stock the backward-Euler rows move.
     *
     * <p>W3b deleted decision D10's water trace in a junction and D41's omitted arrival in a vessel: water is a component
     * of the reachable mask like any other, so a junction's layout carries it wherever it can arrive, and a hydrocarbon
     * trace in a water-full vessel is a component of its liquid, not a phase of its own. A junction's first pass admits
     * the phase that arrives ({@link #arrivingPhases}).
     */
    Seeded initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint,boolean[][] reachable,
                                     List<FlowControl.Mode> modes,boolean[] boundaryClosed) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size();
        // The flows every pass reads its statements off ({@link #startPoint}: the balance's or the carried point's).
        double[] flows=new double[graph.pipes().size()];startPoint(graph,modes,boundaryClosed,solver.identitiesOf(graph),null,flows,new double[flows.length],new boolean[flows.length],PassStart.NONE);
        var seeds=new ArrayList<FluidThermodynamics.State>(nodes);
        for(int nodeIndex=0;nodeIndex<nodes;nodeIndex++) {
            var node=graph.reservoirs().get(nodeIndex);var available=reachable[nodeIndex];
            var state=node.state();
            // A vessel that holds no fluid keeps its own state, which lays it out dry (decision D54, see dry); what arrives
            // in it is laid out by arrivingPhases.
            if(node.fixed()||node.junction()||holdsNoFluidVessel(node)){seeds.add(state);continue;}
            // This is only a trial guess and a basis. The accumulation equations still start from the exact original inventory.
            var n=PhaseLayout.totalAmounts(state);double total=Arrays.stream(n).sum();boolean changed=false;
            for(int i=0;i<count;i++)if(available[i]&&n[i]==0){n[i]=total*1e-12;changed=true;}
            try{seeds.add(changed?arrivingInVessel(graph,nodeIndex,ConservativeTransport.repartition(model,state,n,node.inventory().rigid()),flows,dt):state);}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(graph.reservoirs().get(nodeIndex).id());}
        }
        return arrivingPhases(graph,seeds,modes,boundaryClosed,dt,checkpoint);
    }
    /**
     * Owner decision D40 A at a vessel's step start: its committed layout {@code seed}, and a phase that what is about to
     * arrive carries and the layout lacks laid out with what arrives of it, in its own phase: per inflow the stream its
     * donor end draws ({@link PortStreams#endStream}), at most a quarter of the vessel's mass over {@code dt}, at the vessel's
     * temperature and pressure: its gas into the vapour slot where the seed holds no gas, each of its liquids into a free
     * liquid slot where the seed holds no liquid of its label. The phases the vessel holds keep their committed amounts.
     * The seed itself where nothing arrives in a phase it lacks, or where its state refuses that phase (LNG let into a warm
     * gas boils). W3b deleted the steam the gas took beside arriving free water (Dalton at the free water's vapour
     * pressure): water is a component of the gas, and the layout seeds an empty slot's share at the equilibrium ratio
     * ({@link PhaseLayout#encode}).
     */
    private FluidThermodynamics.State arrivingInVessel(PassiveNetwork graph,int nodeIndex,FluidThermodynamics.State seed,double[] flows,double dt) {
        double[][] slots={seed.vaporAmounts(),seed.liquidAmounts(),seed.aqueousAmounts()};int n=slots[0].length;boolean added=false;
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);double flow=flows[edge];
            if(!(Math.abs(flow)>0)||(flow>=0?pipe.second():pipe.first())!=nodeIndex||!boundaryAllowed(graph,pipe,flow))continue;
            var stream=PortStreams.endStream(model,graph.reservoirs().get(flow>=0?pipe.first():pipe.second()).state(),pipe.drawPort(flow),pipe.volume());
            if(stream==null)continue;
            // The stream is per kilogram of fluid; the start flows are the law's, delivered at their saturation ({@link #transported}).
            double mass=Math.min(seed.mass()*.25,dt*Math.abs(transported(graph,pipe,flow)));
            for(int label:new int[]{FluidThermodynamics.GAS,FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}) {
                int from=stream.slot(label);if(from<0||FluidThermodynamics.holdsPhase(seed,label))continue;
                int to=label==FluidThermodynamics.GAS?FluidThermodynamics.State.VAPOR:freeLiquidSlot(slots);
                if(to<0||label!=FluidThermodynamics.GAS&&seed.slotVolume(to)>0)continue;
                double[] arriving=stream.amountsView(from);
                for(int c=0;c<n;c++)if(arriving[c]>0){slots[to][c]+=mass*arriving[c];added=true;}
            }
        }
        if(!added)return seed;
        try{return model.adoptingState(seed.temperature(),seed.pressure(),slots[0],slots[1],slots[2],null).withSolidState(seed.solids(),seed.solidMoments());}
        catch(IllegalArgumentException refused){return seed;}
    }
    /** A liquid slot of {@code slots} (vapour, liquid, aqueous) that holds nothing, -1 where both hold some. */
    private static int freeLiquidSlot(double[][] slots) {
        for(int slot:new int[]{FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS})if(!(PhaseLayout.sum(slots[slot])>0))return slot;
        return -1;
    }
    /**
     * Group L (A300-A303): {@code seeds} with every junction laid out for the phases the step's inflow leaves in it. A
     * fitting full of air that water reaches is full of water a few milliseconds later, and a pass is laid out for its
     * seeds' phases ({@link PhaseLayout}), so a junction seeded as its held gas has no unknown for the water its mixing
     * rows must hold: the residual sits in those rows, the trial temperatures leave the data and the step halves to its
     * floor (an 11 g air buffer receiving about 7 kg/s of water). Each junction's mixture over the step is its stock plus
     * what its connections deliver at the pass's start flows ({@link #startPoint}) over {@code dt}, each the stream its
     * donor end draws ({@link PortStreams#endMoles}), at the seed's pressure and the enthalpy of stock and deliveries, split as the
     * junction's own split is (D26, {@link StepPhaseRules#junctionSplit}: its rows closed from the engine's PH answer
     * at the mass-weighted temperature, fluid only, a starting basis booking no crystal, the seed keeping the junction's
     * own solids; from the TP flash there where the engine answers nothing, as for an empty bag; formed only where the
     * engine's answer holds a phase the seed lacks, and nothing taken where the closure fails or empties that phase) and
     * folded as the pass folds a junction (D34, D41). The stock is what
     * the junction's own rows mix (D46): its buffer's amounts and energy field, which is in enthalpy form, its solids'
     * enthalpy left out. Its property state can be another mixture (the balance states a junction as what is delivered
     * to it, {@code BalanceNet.seeded}), and that state's specific enthalpy over the buffer's amounts made a subcooled LNG buffer (134.35 K, 656 kPa, 27 kJ/kg apart) a
     * two-phase mixture at 139.97 K, 45 % vapour by volume, and no step converged (cold fuzz seed 583, E5). A junction
     * nothing reaches at the start flows is its stock alone: an island come to rest at its gas source's pressure had its
     * junctions stated as the 257 K CO2-rich gas the balance delivers to them, while each held 1.1 to 3.5 kg of the cold
     * two-phase mixture it had, and the pass laid out as a gas stalled on their enthalpy rows (cold fuzz seed 729). With the crystals
     * competing, the cold quick check's seeds 77 and 151 held E5 and six more ran out of wall time. The enthalpy carries
     * the latent heat a temperature mix leaves out: liquid water at 326.648 K reaching a pump suction held at its vapour
     * pressure (14.63 kPa, its saturation at 326.606 K) is liquid beside 6.6e-6 of its mass as steam, where the TP flash
     * at the mixed temperature made all of it steam, and the suction limit's row then had no vapour pressure to stand at
     * (bundled fuzz seed 889, hold E9). Where the seed is not laid out for that mixture ({@link #laidOutFor}), the seed
     * becomes the mixture at the seed's mass; a junction donor delivers its own updated seed, so a phase reaches a run of
     * junctions in one stage. A seed laid out for its mixture is kept to the bit; a rate-only solve holds a junction's
     * composition at its stock and this does nothing there.
     *
     * <p>A vessel that holds no fluid (decision D54) is laid out from what its connections deliver over {@code dt}, beside
     * its solids: the delivered amounts at the bag's own split (decision D26, {@link #emptyBagSplit}), at the ambient while
     * the bag is slack and at the capacity once what arrives overfills it, folded as a vessel; where the engine answers
     * nothing the network carries, the TP flash of the delivered amounts at their mass-weighted temperature and the
     * ambient (an isothermal let-down: LNG at 139 K and 1 atm is a vapour of several bag volumes beside CO2 frost, and a
     * pass laid out without the liquid cooled the vapour to its 90.69 K floor). One nothing reaches stays dry.
     */
    private Seeded arrivingPhases(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,List<FlowControl.Mode> modes,
                                  boolean[] boundaryClosed,double dt,Runnable checkpoint) {
        if(solver.rateOnly||graph.reservoirs().stream().noneMatch(node->node.junction()&&!node.fixed()||holdsNoFluidVessel(node)))return new Seeded(seeds,null);
        int edges=graph.pipes().size();double[] flows=new double[edges];
        startPoint(graph,modes,boundaryClosed,solver.identitiesOf(graph),null,flows,new double[edges],new boolean[edges],PassStart.NONE);
        return arrivingPhases(graph,seeds,flows,null,false,modes,boundaryClosed,dt,checkpoint);
    }
    /** {@link #arrivingPhases} at the law flows {@code flows}; {@code vesselsOnly} leaves the junctions, for a converged pass
     * whose junctions are solved; {@code seeds} itself where nothing is laid out. A connection the pass drew phases on
     * ({@code draws}, {@link StepEquations#draws}) delivers its drawn stream ({@link PortStreams#drawnStream}), what the booking books;
     * any other the stream of its end at a vanishing flow. The junctions {@link #flashSeeds} restated in the result travel
     * with it ({@link Seeded}). */
    Seeded arrivingPhases(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,double[] flows,double[][] draws,boolean vesselsOnly,List<FlowControl.Mode> modes,
                                  boolean[] boundaryClosed,double dt,Runnable checkpoint) {
        int nodes=graph.reservoirs().size();
        List<FluidThermodynamics.State> result=null;
        for(int round=0;round<=nodes;round++) {
            boolean changed=false;
            for(int j=0;j<nodes;j++) {
                var node=graph.reservoirs().get(j);boolean empty=holdsNoFluidVessel(node);if(!(node.junction()&&!node.fixed()&&!vesselsOnly||empty))continue;
                var current=result==null?seeds:result;var seed=current.get(j);
                if(empty&&!dry(node,seed))continue;
                var stockMoles=node.inventory().moles();
                double[] n=empty?stockMoles.clone():new double[stockMoles.length];
                double heldMass=solver.molarMass(stockMoles),mass=empty?heldMass:0,weighted=mass*node.state().temperature(),heat=0;boolean arrives=false;
                for(int edge=0;edge<flows.length;edge++) {
                    var pipe=graph.pipes().get(edge);double flow=flows[edge];
                    if(!(Math.abs(flow)>0)||(flow>=0?pipe.second():pipe.first())!=j||!boundaryAllowed(graph,pipe,flow))continue;
                    int donor=flow>=0?pipe.first():pipe.second();var stream=current.get(donor);var port=pipe.drawPort(flow);
                    var rates=draws==null?null:draws[edge];var drawn=rates==null?null:PortStreams.drawnStream(model,rates,stream,graph.reservoirs().get(donor).state());
                    double rate=Math.abs(transported(graph,pipe,flow)),delivered=dt*rate,streamMass=drawn!=null?1:PortStreams.endMass(model,stream,port,pipe.volume());double[] moles=drawn!=null?drawn:PortStreams.endMoles(model,stream,port,pipe.volume());
                    for(int c=0;c<n.length;c++)n[c]+=delivered*moles[c]/streamMass;
                    var through=solver.delivered(graph,pipe,drawn!=null?drawn[n.length]:PortStreams.endSpecificEnthalpy(model,stream,port,pipe.volume()),stream.temperature(),Math.copySign(rate,flow),checkpoint);
                    mass+=delivered;weighted+=delivered*through[1];arrives=true;
                    heat+=delivered*through[0];
                }
                if(empty) {
                    if(!arrives||!(mass>0))continue;
                    FluidThermodynamics.State delivered;
                    try{delivered=model.folded(model.flashTP(weighted/mass,PhaseLayout.AMBIENT_PRESSURE,n,checkpoint),false);}
                    catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){continue;}
                    // What arrives may itself be nothing at the bag's resolution: the vessel then stays dry.
                    if(PhaseLayout.bagVolume(delivered,node.inventory().wallCapacity())<=HOLDS_NOTHING*node.inventory().volume())continue;
                    var own=emptyBagSplit(node,weighted/mass,n,heat,checkpoint);
                    if(result==null)result=new ArrayList<>(seeds);
                    var arrived=own==null?delivered:own;var held=node.inventory().solids();
                    result.set(j,held.empty()?arrived:arrived.withSolids(held));changed=true;continue;
                }
                // Where nothing arrives the mixture is the stock alone, which the seed must hold as well.
                if(!(heldMass>0))continue;
                // The stock as the junction's rows mix it (D46): the buffer's amounts and its energy field (enthalpy form), fluid only.
                var buffer=node.inventory();var at=node.state();
                double share=JunctionMixing.share(buffer,heldMass+buffer.solids().massKg(),mass/dt,dt,model.time());
                for(int c=0;c<n.length;c++)n[c]+=share*stockMoles[c];
                heldMass*=share;
                mass+=heldMass;
                weighted+=heldMass*at.temperature();
                double stock=share*(buffer.internalEnergy()-buffer.solids().moments().enthalpy(at.temperature(),at.pressure()));
                // This flash carries only the fluid. The seed's solid moments are attached after the split.
                double seedFluidMass=seed.mass()-seed.solidMoments().mass();
                double scale=seedFluidMass>0?seedFluidMass/mass:1,enthalpy=(stock+heat)*scale;
                for(int c=0;c<n.length;c++)n[c]*=scale;
                FluidThermodynamics.State engine;
                try{var split=model.equilibriumPH(weighted/mass,seed.pressure(),enthalpy,n,checkpoint);engine=split!=null?split:model.flashTP(weighted/mass,seed.pressure(),n,checkpoint);}
                catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){continue;}
                // A cold solve's seed is the balance's upwind mixture (balanceStart), right for a junction the step flushes. One
                // that keeps at least half its stock over the step is stated by that stock: its seed is the stock's mixture
                // whatever it is laid out for (stocked).
                boolean stocked=stocked(j,seed,seeds,heldMass,mass);
                // The phase mask alone also fits an isothermal change of mixture. Start the amounts
                // on the same incoming composition as the implicit rows when that change is resolved.
                boolean front = false;
                var currentAmounts = PhaseLayout.totalAmounts(seed);
                for (int c = 0; c < n.length; c++) {
                    if (Math.abs(n[c]-currentAmounts[c])*model.molecularWeight(c) > 1e-6*seedFluidMass) {
                        front = true;
                        break;
                    }
                }
                if(!stocked && !front && Math.abs(engine.temperature()-seed.temperature()) <= .5 && laidOutFor(phaseCode(model.folded(engine,true)),phaseCode(seed)))continue;
                // The engine's answer is the start of the junction's own split, not its verdict (D26): its rows decide the phase.
                var own=StepPhaseRules.junctionSplit(model,n,enthalpy,solver.molarMass(n),seed.pressure(),engine,checkpoint);
                if(own==null)continue;
                var mixture=model.folded(own,true);
                if(!stocked && !front && Math.abs(mixture.temperature()-seed.temperature()) <= .5 && laidOutFor(phaseCode(mixture),phaseCode(seed)))continue;
                if(result==null)result=new ArrayList<>(seeds);
                result.set(j,mixture.withSolidState(seed.solids(),seed.solidMoments()));changed=true;
            }
            if(!changed)break;
        }
        return result==null?new Seeded(seeds,null):flashSeeds(graph,modes,boundaryClosed,seeds,result,checkpoint);
    }
    /**
     * Whether junction {@code j} of a cold solve is seeded by its stock: its {@code seed} is the balance's restatement
     * ({@link BalanceStart#balanceStart}), not its own state, and the step's start flows bring it no more than it holds
     * ({@code heldMass} of the mixture's {@code mass}). The balance states a junction as the mixture of what flows through
     * it; the backward-Euler rows state it as its stock and what arrives, and where the stock outweighs the arrivals the
     * two differ by the whole stock. The level loop's outlet junction (1 kg of water with 0.15 % of nitrogen, 72 % gas by
     * volume) stood behind a shut valve; the balance seeded it as the drum's liquid with 1e-7 of its volume as gas, its
     * rows asked for the stock back, and the Newton crossed to all vapour at every step size (batch
     * 2026-11-11-aqueous-solvents, FINAL_LANE_FIXES_REVIEW.md section 3). A seed starts a pass and decides nothing.
     */
    private boolean stocked(int j,FluidThermodynamics.State seed,List<FluidThermodynamics.State> seeds,double heldMass,double mass) {
        var supplyGraph=solver.constants.supplyGraph;
        return solver.balanceLawFlows!=null&&supplyGraph!=null&&j<supplyGraph.reservoirs().size()&&seed==seeds.get(j)
                &&seed!=supplyGraph.reservoirs().get(j).state()&&heldMass>=mass-heldMass;
    }
    /**
     * Decisions D54 and D26: an empty bag's own split of what arrives over a step, the amounts {@code n} with the enthalpy
     * {@code h} they bring (J, the network's datum). The engine's PH answer at the ambient pressure holds the phases; the
     * vessel's closure from it ({@link InventoryEquilibrium#split}, the wall's work booked from a zero volume) is the
     * equilibrium of that enthalpy at the ambient while the bag is slack and of its energy at the capacity once what
     * arrives overfills it; the PH answer itself where the closure leaves nothing. {@code null} where the engine answers
     * nothing the network carries.
     */
    private FluidThermodynamics.State emptyBagSplit(PassiveNetwork.Reservoir node,double t,double[] n,double h,Runnable checkpoint) {
        try {
            var answer=model.equilibriumPH(t,PhaseLayout.AMBIENT_PRESSURE,h,n,checkpoint);
            if(answer==null)return null;
            var start=model.folded(answer,false);
            var own=InventoryEquilibrium.split(model,start,node.inventory().with(n.clone(),h,SolidInventory.EMPTY),node.inventory().emptyBag(),checkpoint);
            return own==null?start:model.folded(own,false);
        }
        catch(IllegalArgumentException refused){return null;}
    }
    /**
     * The connection flows and actuator heads a pass with this active set starts from, the whole of
     * {@link StepEquations#buildInitial}'s edge half; it lives here because {@link OneWayClosures#closeIllegalStarts} reads it before the
     * equations exist. {@code seeds} may be {@code null} before this solve has any, which only a regulating valve reads.
     * {@code passStart}: what the pass before handed this one ({@link PassStart}); {@link PassStart#NONE} for every start
     * point but a pass's own.
     */
    void startPoint(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                            List<PassiveNetwork.Pipe.Identity> pipeIdentities,List<FluidThermodynamics.State> seeds,
                            double[] flows,double[] heads,boolean[] headSet,PassStart passStart) {
        boolean warmFlow=graph.reservoirs().stream().anyMatch(node->node.junction()||node.fixed())
                ||graph.pipes().stream().anyMatch(pipe->phaseCode(graph.reservoirs().get(pipe.first()).state())!=phaseCode(graph.reservoirs().get(pipe.second()).state()));
        // At a job start the previous point is the committed interval's endpoint; see {@link PassiveStepSolver#replayStart}.
        boolean previousAvailable=!passStart.lawFlowsFromBalance()&&solver.carriedPoint(graph,pipeIdentities);
        // The balance's law flows ({@link BalanceStart#balanceStart}) where this point has no history to start from (a solve without a
        // carried point balances first); each connection's own law on its ends' states on an island the balance leaves alone.
        double[] estimate=warmFlow&&!previousAvailable?(solver.balanceLawFlows!=null&&solver.balanceLawFlows.length==graph.pipes().size()?solver.balanceLawFlows:solver.lawFlows(graph)):null;
        for(int i=0;i<graph.pipes().size();i++) {
            var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED)continue;
            if(previousAvailable){
                // A flow the connection's boundary forbids (into a generator, out of a void, a blocked way) is no state to
                // start from, however small: the pass loop tolerates one below 1e-10 kg/s at a converged point, and carried
                // into the next step a Newton that converges without an iteration kept it there for good. It starts at rest.
                flows[i]=boundaryAllowed(graph,pipe,solver.carry.lawFlows()[i])?solver.carry.lawFlows()[i]:0;heads[i]=solver.carry.heads()[i]/1e5;headSet[i]=true;
                if(modes.get(i)==FlowControl.Mode.VALVE_REGULATING&&pipe.control() instanceof FlowControl.Valve valve&&valve.pressureControl()) {
                    double predictedDrop=a.state().pressure()-(seeds==null?a.state():seeds.get(pipe.first())).pressure();
                    if(predictedDrop>0)flows[i]*=Math.clamp((a.state().pressure()-valve.targetPressure())/predictedDrop,0,1);
                    double rho=PortStreams.endDensity(model,a.state(),pipe.firstPort(),pipe.volume());
                    double column=PortStreams.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume());
                    heads[i]=(PortStreams.columnEndPressure(model,a,pipe.firstPort(),a.state(),column)-PortStreams.columnEndPressure(model,b,pipe.secondPort(),b.state(),column)
                            -column*GRAVITY*(b.elevation()-a.elevation())
                            -pipe.pressureDrop(flows[i],rho,solver.endViscosity(a.state(),pipe.firstPort(),pipe.volume())))/1e5;
                }
                continue;
            }
            if(!warmFlow)continue;
            // Decision D54: a dry end gives nothing, so an estimate out of one starts at rest (A190's rule for a carried flow).
            flows[i]=outOfDryEnd(graph,pipe,estimate[i])?0:estimate[i];
        }
        if(passStart.flashRestated()!=null&&seeds!=null)restateFlashFlows(graph,modes,boundaryClosed,seeds,flows,passStart.flashRestated());
        if(!hasSolids(graph)&&graph.pipes().stream().noneMatch(pipe->pipe.filter()!=null))return;
        int nodes=graph.reservoirs().size();int[][] nodeEdges=incidence(graph);
        boolean[] known=new boolean[graph.pipes().size()];
        for(int i=0;i<known.length;i++)known[i]=boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED;
        for(int pass=0;pass<known.length;pass++) {
            boolean changed=false;
            for(int node=0;node<nodes;node++)if(graph.reservoirs().get(node).junction()) {
                int missing=-1;double net=0;boolean usable=true;
                for(int edge:nodeEdges[node]) {
                    if(!known[edge]){if(missing>=0){usable=false;break;}missing=edge;continue;}
                    var pipe=graph.pipes().get(edge);double q=flows[edge];boolean receiving=node==(q>=0?pipe.second():pipe.first());
                    double factor=receiving&&pipe.filter()!=null?1-PortStreams.solidShare(model,graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state(),pipe.drawPort(q),pipe.volume()):1;
                    net+=receiving?Math.abs(q)*factor:-Math.abs(q);
                }
                if(!usable||missing<0)continue;
                var pipe=graph.pipes().get(missing);double q=pipe.first()==node?net:-net;
                if(node==(q>=0?pipe.second():pipe.first())&&pipe.filter()!=null){var donor=graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state();q/=Math.max(1e-12,1-PortStreams.solidShare(model,donor,pipe.drawPort(q),pipe.volume()));}
                if(boundaryAllowed(graph,pipe,q)){flows[missing]=q;known[missing]=true;changed=true;}
            }
            if(!changed)break;
        }
    }
    /** The transported flow of connection {@code pipe} for the law's flow {@code lawFlow}: its saturation at the velocity
     * cap of the end it leaves, on that end's stream ({@link PassiveStepSolver#massFlowLimit}). */
    private double transported(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double lawFlow) {
        if(pipe.control() instanceof FlowControl.Mover)return lawFlow;
        var donor=graph.reservoirs().get(lawFlow>=0?pipe.first():pipe.second()).state();
        return saturated(lawFlow,solver.massFlowLimit(pipe,donor,pipe.drawPort(lawFlow)));
    }
    /** The vapour bit of {@link FluidThermodynamics#phaseBits}. */
    private static final int VAPOUR_BITS=2;
    /**
     * The seeds {@code corrected} with every junction where a vapour first appears ({@link #flashes}) seeded just under
     * the pressure its connections settle it at ({@link #settledFlash}), its connections restarted there
     * ({@link #restateFlashFlows}); {@code point} is what the seeds were formed from: a converged pass's point for the
     * phase check's proposal ({@link PassiveStepSolver#phaseCorrection}), the step's seeds for what arrives ({@link #arrivingPhases}).
     *
     * <p>A junction holds no pressure of its own; its connections set it. The pressure a vapour first appears at is the
     * answer of a layout without it. A liquid-only layout carries its outflow at a liquid's density and velocity cap, so a
     * junction that must flash is left far below its bubble pressure. With the vapour there, the outflow is the mixture's,
     * much lighter, and the junction backs up until what leaves passes what arrives. Cold fuzz seed 509: LNG at 4.34 MPa
     * and 160 K enters through 0.1 m at its cap, 311.9 kg/s. As a liquid (377 kg/m3) the junction drains to 170 kPa, a
     * tenth of its bubble pressure. As the mixture its two 0.1 m outlets pass 311.9 kg/s only at about 206 kg/m3, which it
     * is at 1.24 MPa. Seeded at 170 kPa every step stalled; seed 170 is the same at 449 kPa against 0.92 MPa.
     *
     * <p>Only a vapour appearing in a junction that held none is restated. A liquid condensing in a gas junction makes the
     * outflow heavier, and the point's own pressure is the better start (cold fuzz seed 118: 98 such proposals).
     */
    Seeded flashSeeds(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                              List<FluidThermodynamics.State> point,List<FluidThermodynamics.State> corrected,Runnable checkpoint) {
        List<FluidThermodynamics.State> result=null;boolean[] flashRestated=null;
        for(int j=0;j<corrected.size();j++) {
            if(!flashes(graph,point,corrected,j))continue;
            var settled=settledFlash(graph,modes,boundaryClosed,result==null?corrected:result,j,point.get(j),checkpoint);
            if(settled==null)continue;
            if(result==null)result=new ArrayList<>(corrected);
            result.set(j,settled);
            if(flashRestated==null)flashRestated=new boolean[corrected.size()];
            flashRestated[j]=true;
        }
        return new Seeded(result==null?corrected:result,flashRestated);
    }
    /**
     * The start flows of the connections of every junction {@link #flashSeeds} restated: each one's own law at the
     * seeded end pressures ({@link PassiveStepSolver#lawFlow}), nothing in a direction its boundary forbids. A carried flow belongs to the
     * pressure the junction had: cold fuzz seed 170's junction went from 450 to 674 kPa, and the pass after it stalled
     * on the hydraulic rows of its three connections.
     */
    private void restateFlashFlows(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,double[] flows,boolean[] flashRestated) {
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(int i=0;i<graph.reservoirs().size();i++){var r=graph.reservoirs().get(i);reservoirs.add(r.fixed()?r:new PassiveNetwork.Reservoir(r.id(),r.elevation(),seeds.get(i),r.kind(),r.inventory()));}
        var g=new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
        for(int e=0;e<flows.length;e++) {
            var pipe=g.pipes().get(e);
            if(!(flashRestated[pipe.first()]||flashRestated[pipe.second()])||pipe.control() instanceof FlowControl.Mover)continue;
            if(boundaryClosed[e]||modes.get(e)==FlowControl.Mode.CLOSED)continue;
            double law=solver.lawFlow(g,pipe,PortStreams.endPressure(model,g,pipe,pipe.first()),PortStreams.endPressure(model,g,pipe,pipe.second()));
            flows[e]=boundaryAllowed(g,pipe,law)?law:0;
        }
    }
    /** Whether {@code corrected} gives junction {@code j} a vapour ({@link #VAPOUR_BITS}) that {@code point} holds none of. */
    private static boolean flashes(PassiveNetwork graph,List<FluidThermodynamics.State> point,List<FluidThermodynamics.State> corrected,int j) {
        var node=graph.reservoirs().get(j);
        if(!node.junction()||node.fixed()||corrected.get(j)==point.get(j))return false;
        int before=FluidThermodynamics.phaseBits(point.get(j));
        return (before&VAPOUR_BITS)==0&&(FluidThermodynamics.phaseBits(corrected.get(j))&~before&VAPOUR_BITS)!=0;
    }
    /** The pressure ratio of the march up from the pressure a vapour appears at, and its most steps. */
    private static final double FLASH_STEP=1.5;private static final int FLASH_STEPS=12;
    /**
     * Junction {@code j}'s flashed seed under the pressure its own balance settles it at, every other node held at
     * {@code states}. The balance is where the net inflow of its connections ({@link #netInflow}) vanishes: each at its
     * own law, saturated at the cap of the end it leaves, the junction the donor of what leaves at its own split there
     * ({@link StepPhaseRules#junctionSplit}: its amounts and enthalpy, from the trial below it). The net inflow falls
     * as the pressure rises: less arrives at a smaller drive, and what leaves is denser. So the root lies above the seed's
     * pressure where the flashed junction takes in more than it passes, and below the pressure its vapour vanishes at.
     * The march goes up by {@link #FLASH_STEP} (never past the highest pressure of a neighbour) and keeps the last trial
     * that still takes in more than it passes: within one step under the root, on the side where the vapour holds.
     * At the root itself a junction that barely flashes sits just under its bubble point, and the two-phase layout ends
     * there. Cold fuzz seed 592: LNG must pass 298 kg/s through 9.8e-3 m2 of outlets at about 304 kg/m3, against 356 as a
     * liquid; the root is 1.78 MPa, the bubble point about 1.86 MPa. Every step from the root walked off to an all-vapour
     * iterate. Seeded at 1.24 MPa, the march's last trial, the island is clean in 4 266 Newton iterations (5 153 on
     * 0.10.0, whose junction stayed a liquid at 552 kPa). Null where the seed passes what arrives already, or where no
     * trial keeps the vapour.
     */
    private FluidThermodynamics.State settledFlash(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                                                   List<FluidThermodynamics.State> states,int j,FluidThermodynamics.State before,Runnable checkpoint) {
        var seed=states.get(j);
        if(!(netInflow(graph,modes,boundaryClosed,states,j,seed)>0))return null;
        double[] n=PhaseLayout.totalAmounts(seed);int added=FluidThermodynamics.phaseBits(seed)&~FluidThermodynamics.phaseBits(before);
        double ceiling=0;
        for(var pipe:graph.pipes())if(pipe.first()==j||pipe.second()==j){int other=pipe.first()==j?pipe.second():pipe.first();ceiling=Math.max(ceiling,states.get(other).pressure());}
        var low=seed;
        for(int k=0;k<FLASH_STEPS;k++) {
            double p=Math.min(low.pressure()*FLASH_STEP,ceiling);if(!(p>low.pressure()))break;
            var trial=flashAt(n,seed,p,low,added,checkpoint);
            if(trial==null||!(netInflow(graph,modes,boundaryClosed,states,j,trial)>0))break;
            low=trial;
        }
        return low==seed?null:low;
    }
    /** The junction's own split of {@code seed}'s amounts {@code n} and enthalpy at pressure {@code p}, from {@code start},
     * folded as the pass folds a junction; null where it fails or no longer holds the {@code added} phases. */
    private FluidThermodynamics.State flashAt(double[] n,FluidThermodynamics.State seed,double p,FluidThermodynamics.State start,int added,Runnable checkpoint) {
        FluidThermodynamics.State own;
        try{own=StepPhaseRules.junctionSplit(model,n,seed.enthalpy(),seed.mass(),p,start,checkpoint);}
        catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){return null;}
        if(own==null)return null;
        var folded=model.folded(own.withSolidState(seed.solids(),seed.solidMoments()),true);
        return (FluidThermodynamics.phaseBits(folded)&added)==added?folded:null;
    }
    /** The net mass inflow (kg/s) of junction {@code j} stated as {@code trial}, every other node as {@code states}: each open
     * connection at its law's flow on those end pressures ({@link PassiveStepSolver#lawFlow}), saturated at its donor's cap
     * ({@link #transported}), nothing in a direction its boundary forbids. */
    private double netInflow(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> states,int j,FluidThermodynamics.State trial) {
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(int i=0;i<graph.reservoirs().size();i++){var r=graph.reservoirs().get(i);reservoirs.add(r.fixed()?r:new PassiveNetwork.Reservoir(r.id(),r.elevation(),i==j?trial:states.get(i),r.kind(),r.inventory()));}
        var g=new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
        double net=0;
        for(int e=0;e<g.pipes().size();e++) {
            var pipe=g.pipes().get(e);if(pipe.first()!=j&&pipe.second()!=j)continue;
            var mode=modes.get(e);
            if(boundaryClosed[e]||mode==FlowControl.Mode.CLOSED||mode==FlowControl.Mode.INLET_WRONG_PHASE)continue;
            double law=solver.lawFlow(g,pipe,PortStreams.endPressure(model,g,pipe,pipe.first()),PortStreams.endPressure(model,g,pipe,pipe.second()));
            if(!boundaryAllowed(g,pipe,law))continue;
            double q=transported(g,pipe,law);
            net+=pipe.second()==j?q:-q;
        }
        return net;
    }
    /**
     * Whether a junction seed of phase code {@code seed} is laid out for its step's mixture of code {@code mixture}
     * ({@link #arrivingPhases}): the same hydrocarbon phases, liquid and gas, and every water phase of the mixture. A pass
     * cannot empty a phase, so a hydrocarbon phase the mixture lacks counts as much as one it holds and the seed does not:
     * the balance stated four junctions as the 215.5 K CO2-rich gas and liquid delivered to them while each held 3 to 14 g
     * of warm gas (stock enthalpy -10 to +44 kJ/kg against -131 to -136), and every step down to 1.1e-6 s started in a
     * layout its answer was not in (cold fuzz seed 994, E6). Water keeps the superset: a seed's water may be the entry
     * trace of decision D10, which a mixture nothing wet reaches lacks, and dropping it left the rows without the water the
     * reconstruction then booked (bundled fuzz seed 157, E4).
     */
    private static boolean laidOutFor(int mixture,int seed){return (mixture&2)==(seed&2)&&Integer.bitCount(mixture&5)==Integer.bitCount(seed&5);}
}
