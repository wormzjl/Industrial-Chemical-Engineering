package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.SolverOwnership;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.linalg.*;
import com.wormzjl.icheme.science.fluid.solver.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.NetworkCrystals;
import com.wormzjl.icheme.science.thermo.PhaseSupport;
import java.util.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;

/** Conservative reconstruction for a candidate's already-solved flows; it does not solve hydraulics or flash. */
public final class ConservativeTransport {
    private ConservativeTransport() {}
    /**
     * One island's transport linear algebra, retained across its reconstructions as {@code SparseNewton.Workspace.Shared}
     * is retained across a fork family: one EJML storage every reconstruction refills in place, and the fill-reducing
     * ordering of each structure the island produces (a step solve and a rate solve of the port graph do not share a
     * sparsity pattern, so the orderings are a small keyed cache). Without it each reconstruction built its own
     * {@link SparseLuSolver.Storage} and ordering, three or four times per interval. Reuse is bitwise: the storage clears
     * every buffer it reads, an ordering is only reused for a matrix with the identical structure, and each factorization
     * is consumed inside the reconstruction that produced it.
     */
    public static final class Workspace {
        private static final int RETAINED_ORDERINGS=4;
        private final SparseLuSolver.Storage factors;
        private final LinkedHashMap<Structure,SparseLuSolver.Ordering> orderings=new LinkedHashMap<>();
        public Workspace(SolverOwnership ownership){factors=new SparseLuSolver.Storage(ownership);}
        private SparseLuSolver.Factorization factor(SparseMatrix matrix) {
            var structure=Structure.of(matrix);
            var ordering=orderings.get(structure);
            if(ordering==null) {
                if(orderings.size()>=RETAINED_ORDERINGS)orderings.remove(orderings.keySet().iterator().next());
                ordering=SparseLuSolver.prepareOrdering(matrix);orderings.put(structure,ordering);
            }
            return factors.factor(matrix,ordering);
        }
    }
    /** Everything the ordering depends on: the sparsity pattern, and which stored entries are
     * exactly zero, because the reverse Cuthill-McKee graph skips a stored zero. */
    private record Structure(int size,int[] offsets,int[] rows,long[] zeros) {
        private static Structure of(SparseMatrix matrix) {
            int size=matrix.size(),count=matrix.nonzeroCount();
            int[] offsets=new int[size+1],rows=new int[count];long[] zeros=new long[(count+63)/64];
            for(int column=0;column<size;column++)offsets[column]=matrix.columnStart(column);
            offsets[size]=count;
            for(int entry=0;entry<count;entry++) {
                rows[entry]=matrix.rowAt(entry);
                if(matrix.valueAt(entry)==0)zeros[entry>>6]|=1L<<(entry&63);
            }
            return new Structure(size,offsets,rows,zeros);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Structure key&&size==key.size&&Arrays.equals(offsets,key.offsets)
                    &&Arrays.equals(rows,key.rows)&&Arrays.equals(zeros,key.zeros);
        }
        @Override public int hashCode() {
            return 31*(31*(31*size+Arrays.hashCode(offsets))+Arrays.hashCode(rows))+Arrays.hashCode(zeros);
        }
    }
    /**
     * What crossed the island's boundary at one node over a step. {@code kind} says what crossed it: an {@link Kind#EXCHANGE}
     * with a fixed node, a module or the ambient (the wall's work), a one-time {@link Kind#FLUSH} of a vessel's trace or
     * gas, or an open vessel's {@link Kind#VENT} and {@link Kind#SPILL} to the atmosphere ({@link OpenVessel}), which are
     * rates a steady island repeats.
     */
    public record BoundaryTransfer(long nodeId,double[] moles,double totalEnergyJoule,SolidInventory solids,int solidDirection,Kind kind) {
        /** {@code HEAT}: the heat a heat-exchanging connection ({@link HeatSide}) took up over the step, energy alone,
         * booked at the connection's id: what crossed its wall (batch 2026-10-27-heat-exchanger-design). */
        public enum Kind { EXCHANGE, FLUSH, VENT, SPILL, HEAT }
        public BoundaryTransfer(long nodeId,double[] moles,double totalEnergyJoule){this(nodeId,moles,totalEnergyJoule,SolidInventory.EMPTY,0);}
        public BoundaryTransfer(long nodeId,double[] moles,double totalEnergyJoule,SolidInventory solids,int solidDirection){this(nodeId,moles,totalEnergyJoule,solids,solidDirection,Kind.EXCHANGE);}
        public BoundaryTransfer{moles=moles.clone();Objects.requireNonNull(solids);Objects.requireNonNull(kind);if(solidDirection < -1||solidDirection > 1||!solids.empty()&&solidDirection==0)throw new IllegalArgumentException("Invalid solid boundary direction");}
        @Override public double[] moles(){return moles.clone();}
    }
    /**
     * What a rate evaluation's frozen junctions held back (owner decision D46): the amounts, energy and solids their rows
     * would have mixed into their buffers over dt, had the buffers not been held. Part of the junction term of
     * {@code PassiveStepSolver.checkConservation}, beside the buffers' change; empty for a step solve, whose buffers take
     * what their rows mix. {@code grossMoles} and {@code grossEnergyJoule} sum each junction's magnitudes: the turnover
     * that scales the ledger's tolerance, as each junction's pseudo-boundary carried it before D46.
     */
    public record HeldJunctions(double[] moles,double energyJoule,SolidInventory gained,SolidInventory lost,double[] grossMoles,double grossEnergyJoule) {
        public HeldJunctions{moles=moles.clone();grossMoles=grossMoles.clone();Objects.requireNonNull(gained);Objects.requireNonNull(lost);}
        @Override public double[] moles(){return moles.clone();}
        @Override public double[] grossMoles(){return grossMoles.clone();}
        static HeldJunctions none(int components){return new HeldJunctions(new double[components],0,SolidInventory.EMPTY,SolidInventory.EMPTY,new double[components],0);}
    }
    /**
     * What the step's junctions melted (owner decision D61, {@link JunctionCrystals}): the crystal mass {@code solids} that
     * left the solid ledger, the component amounts {@code moles} it became, and its offsets {@code energyJoule}, which the
     * stored energy gains (the crystal's enthalpy is its stored part plus its offset). An internal conversion of the step's
     * ledger ({@code PassiveStepSolver.checkConservation}); none in a rate solve.
     */
    public record Melted(double[] moles,double energyJoule,SolidInventory solids) {
        public Melted{moles=moles.clone();Objects.requireNonNull(solids);}
        @Override public double[] moles(){return moles.clone();}
        static Melted none(int components){return new Melted(new double[components],0,SolidInventory.EMPTY);}
    }
    public record Projection(List<PassiveNetwork.Inventory> inventories,List<FluidThermodynamics.State> states,
                             List<BoundaryTransfer> boundaries,double[] externalMoles,double externalEnergy,double pumpWork,Map<Long,InlineFilter> filters,HeldJunctions held,
                             Melted melted) {
        public Projection{filters=Map.copyOf(filters);inventories=List.copyOf(inventories);states=List.copyOf(states);boundaries=List.copyOf(boundaries);externalMoles=externalMoles.clone();Objects.requireNonNull(held);Objects.requireNonNull(melted);}
        @Override public double[] externalMoles(){return externalMoles.clone();}
    }
    /** One-shot: a caller with no retained island workspace builds fresh linear algebra, as before. */
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint) {
        return reconstruct(graph,candidate,flows,heads,dt,model,checkpoint,null,false,null,null,null,null);
    }
    /** A vessel that runs dry within the step (decision D54): its stock's mass {@code drain} and the ambient's {@code work}. */
    public record Emptying(double drain,double work) {}
    /** The heat (W, into the stream) heat-exchanging connection {@code edge} takes up at the donor specific enthalpy it
     * books and its flow: the Newton's own function ({@code PassiveStepSolver.Equations}), so the booking and the equation
     * gate read one number, as a mover's work does. */
    @FunctionalInterface public interface EdgeHeat {double heat(int edge,double specificEnthalpy,double massFlow);}
    /**
     * {@code frozenJunctions} (rate evaluations only): every junction is held at its buffer's composition, as the
     * rate-only rows of {@code PassiveStepSolver.Equations.junctionInflow} hold it, and its accumulation over dt is held
     * back in the projection's {@link HeldJunctions} (owner decision D46: a junction's buffer is not inventory).
     * {@code draws}: per connection, the mass flow each phase ({@link FluidThermodynamics#GAS}, {@link FluidThermodynamics#OIL},
     * {@link FluidThermodynamics#WATER}) supplied to the outflow of a phase-port end at the solved flow (decision D11),
     * null for a connection that drew the bulk, null altogether for a graph none of whose outflows drew phases.
     * {@code supports}: per node, the frozen phase support the step solved it with; each vessel is closed to its booked
     * inventory in that equilibrium ({@link InventoryEquilibrium#close}). {@code emptying} (null: none): per node, a
     * vessel that runs dry within the step and stands in {@code graph} as a junction holding its stock (decision D54):
     * {@link Emptying#drain} its stock's mass over dt and {@link Emptying#work} the ambient's work on its collapsing bag;
     * its booking pins its end mass to zero, and its energy's residual leaves to the ambient boundary at the vessel.
     * {@code releases} (null: none): per node, what an open vessel gives the atmosphere over the step ({@link OpenVessel}),
     * booked out of it as fixed amounts and as {@link BoundaryTransfer.Kind#VENT} and {@link BoundaryTransfer.Kind#SPILL}
     * boundaries at it.
     */
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace,boolean frozenJunctions,double[][] draws,
                                         PhaseSupport[][] supports,Emptying[] emptying,OpenVessel.Releases[] releases) {
        return reconstruct(graph,candidate,flows,heads,dt,model,checkpoint,workspace,frozenJunctions,draws,supports,emptying,releases,null);
    }
    /** As above, with the heat the heat-exchanging connections take up ({@link EdgeHeat}; null where the graph has none),
     * booked into each receiver and as a {@link BoundaryTransfer.Kind#HEAT} boundary at the connection. */
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace,boolean frozenJunctions,double[][] draws,
                                         PhaseSupport[][] supports,Emptying[] emptying,OpenVessel.Releases[] releases,EdgeHeat heat) {
        if(draws!=null&&draws.length!=flows.length)throw new IllegalArgumentException("Invalid phase draws");
        if(releases!=null&&releases.length!=graph.reservoirs().size())throw new IllegalArgumentException("Invalid open-vessel releases");
        if(!SolverDiagnostics.ENABLED)return reconstruct0(graph,candidate,flows,heads,dt,model,checkpoint,workspace,frozenJunctions,draws,supports,emptying,releases,heat);
        long started=System.nanoTime();boolean previous=SolverDiagnostics.enterReconstruct();
        try{return reconstruct0(graph,candidate,flows,heads,dt,model,checkpoint,workspace,frozenJunctions,draws,supports,emptying,releases,heat);}
        finally {
            SolverDiagnostics.leaveReconstruct(previous);
            SolverDiagnostics.reconstructNanos.add(System.nanoTime()-started);SolverDiagnostics.reconstructCalls.increment();
        }
    }
    /**
     * The mass fractions of a node whose composition this reconstruction does not solve for: a prescribed boundary. It
     * is a composition, not a conserved amount, so every entry is read off one state, the candidate, and the stored
     * inventory supplies nothing but the split of the candidate's aggregate solid mass across its populations (a Newton
     * candidate carries its solids as the three aggregate moments and keeps the seed's population list unscaled,
     * {@link com.wormzjl.icheme.science.fluid.solver.PhaseLayout#decode}). Reading the fluid half off the candidate
     * and the solid half off the stored inventory, both over the candidate's total mass, left a junction's fractions
     * summing to 1.000115804 after the Newton had moved its free total by 0.099 %. Where candidate and inventory agree
     * the scale is exactly 1.0 and the result is bit for bit what the two reads produced.
     */
    private static void storedFractions(double[] result,FluidThermodynamics.State state,PassiveNetwork.Reservoir reservoir,
                                        List<SolidInventory.Key> populationKeys,double[] molecularWeight,int components) {
        var amounts=PhaseLayout.totalAmounts(state);
        for(int c=0;c<components;c++)result[c]=amounts[c]*molecularWeight[c]/state.mass();
        var stored=reservoir.inventory().solids();double storedMass=stored.massKg();
        double scale=storedMass>0?state.solidMoments().mass()/storedMass:0;
        for(int c=0;c<populationKeys.size();c++)result[components+c]=scale*stored.mass(populationKeys.get(c))/state.mass();
    }
    /*
     * A draw vector (decision D11; PhaseDraw.Segment.rates) has six entries: the mass flow of each phase (GAS, OIL, WATER)
     * drawn at the step's end state, then of each drawn at the step's start state (a phase pinned at its capacity). An
     * entry k is phase k % 3 of the end state (the candidate, or the reconstructed donor for the energy) for k < 3 and of
     * the start state (the donor's state in the graph the step starts from) for k >= 3.
     */
    /** The entries a draw vector supplies, in entry order. */
    private static int[] drawnPhases(double[] rates) {
        int count=0;for(double rate:rates)if(rate>0)count++;
        int[] phases=new int[count];int at=0;for(int k=0;k<rates.length;k++)if(rates[k]>0)phases[at++]=k;
        return phases;
    }
    /** The weight of entry {@code k} in its draw's stream, {@code r_k/|q|}; exactly 1 for a draw of one phase. */
    private static double weight(double[] rates,int[] phases,int k) {
        if(phases.length==1)return 1;
        double total=0;for(int p:phases)total+=rates[p];
        return rates[k]/total;
    }
    /** The state entry {@code k} of a draw is read on: {@code end} for an end-state entry, {@code start} for a pinned one. */
    private static FluidThermodynamics.State at(int k,FluidThermodynamics.State end,FluidThermodynamics.State start){return k<3?end:start;}
    /** The mass share of solids in what a connection draws out of its donor: the bulk share {@code solids/mass} of
     * {@code donor} (the double it always was) where it draws the bulk, and otherwise its phases' solid shares weighted by
     * their mass flows (none in the gas; a liquid's share of the solids over its stream's mass). */
    private static double drawnSolidShare(FluidThermodynamics model,double[] rates,FluidThermodynamics.State donor,FluidThermodynamics.State start) {
        if(rates==null)return donor.solidMoments().mass()/donor.mass();
        var phases=drawnPhases(rates);double share=0;
        for(int k:phases){int phase=k%3;var state=at(k,donor,start);
            if(phase!=FluidThermodynamics.GAS)share+=weight(rates,phases,k)*(FluidThermodynamics.phaseSolidShare(state,phase)*state.solidMoments().mass()/model.phaseMass(state,phase));}
        return share;
    }
    /**
     * The mass fractions of one phase stream of {@code donor} (fluid components, then solid populations in
     * {@code populationKeys} order), each over the stream's own mass: the gas's (no solid) or a liquid's with its volume
     * share of every solid; the solid split across populations is the stored inventory's, scaled to the candidate's
     * aggregate solid mass, exactly as {@link #storedFractions} splits a fixed node's. The entries sum to 1 to rounding.
     */
    private static double[] phaseFractions(FluidThermodynamics model,int phase,FluidThermodynamics.State donor,
                                           PassiveNetwork.Reservoir reservoir,List<SolidInventory.Key> populationKeys,double[] molecularWeight,int components) {
        var result=new double[components+populationKeys.size()];
        double[] n=FluidThermodynamics.phaseMoles(donor,phase);double mass=model.phaseMass(donor,phase);
        for(int c=0;c<components;c++)result[c]=n[c]*molecularWeight[c]/mass;
        if(phase!=FluidThermodynamics.GAS) {
            var stored=reservoir.inventory().solids();double storedMass=stored.massKg();
            double scale=FluidThermodynamics.phaseSolidShare(donor,phase)*(storedMass>0?donor.solidMoments().mass()/storedMass:0);
            for(int c=0;c<populationKeys.size();c++)result[components+c]=scale*stored.mass(populationKeys.get(c))/mass;
        }
        return result;
    }
    /**
     * The mass fractions of what a phase-port outflow draws (decision D11: the priority stream): its phases' fractions
     * ({@link #phaseFractions}) weighted by their mass flows at the solved flow, the end-state ones frozen from the
     * Newton's converged candidate and the pinned ones read on the step's start state, exactly one phase's where it draws
     * one. Booked as a fixed donor on the pinned flow, so {@code dt q w} leaves the donor and reaches the receiver and mass
     * is conserved whatever the weights.
     */
    private static double[] streamFractions(FluidThermodynamics model,double[] rates,FluidThermodynamics.State donor,FluidThermodynamics.State start,
                                            PassiveNetwork.Reservoir reservoir,List<SolidInventory.Key> populationKeys,double[] molecularWeight,int components) {
        var phases=drawnPhases(rates);
        if(phases.length==1)return phaseFractions(model,phases[0]%3,at(phases[0],donor,start),reservoir,populationKeys,molecularWeight,components);
        var result=new double[components+populationKeys.size()];
        for(int k:phases){var w=phaseFractions(model,k%3,at(k,donor,start),reservoir,populationKeys,molecularWeight,components);double weight=weight(rates,phases,k);
            for(int c=0;c<result.length;c++)result[c]+=weight*w[c];}
        return result;
    }
    private static Projection reconstruct0(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] solvedFlows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace,boolean frozen,double[][] draws,
                                         PhaseSupport[][] supports,Emptying[] emptying,OpenVessel.Releases[] releases,EdgeHeat heat) {
        int nodes=graph.reservoirs().size(),components=model.hydrocarbon.componentCount()+1;
        if(candidate.size()!=nodes||solvedFlows.length!=graph.pipes().size()||heads.length!=solvedFlows.length)throw new IllegalArgumentException("Invalid transport reconstruction");
        for(double q:solvedFlows)if(!Double.isFinite(q))throw new IllegalArgumentException("Nonfinite candidate flow/head");
        // The flows this reconstruction books: the solved flows with every junction's mass held (an emptying vessel's taken
        // to zero); see pinnedFlows.
        final double[] flows=pinnedFlows(graph,candidate,solvedFlows,model,draws,emptying);
        var populationBasis=new TreeMap<SolidInventory.Key,SolidInventory.Population>();
        for(var node:graph.reservoirs())for(var population:node.inventory().solids().populations())populationBasis.merge(population.key(),population,(a,b)->{if(!a.material().equals(b.material()))throw new IllegalArgumentException("Conflicting solid material definitions");return a;});
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in)for(var population:in.solidsPerSecond().populations())populationBasis.merge(population.key(),population,(a,b)->{if(!a.material().equals(b.material()))throw new IllegalArgumentException("Conflicting solid material definitions");return a;});
        var populationKeys=List.copyOf(populationBasis.keySet());int conserved=components+populationKeys.size();
        int[] index=new int[nodes];Arrays.fill(index,-1);int count=0;
        double[] molecularWeight=model.molecularWeights();
        double[][] old=new double[nodes][],fractions=new double[nodes][conserved];
        double[] endMass=new double[nodes],incoming=new double[nodes],outgoing=new double[nodes];
        // Decision D54: a vessel holding no fluid (at the bag's resolution, D59 (2)) that nothing reaches in this step keeps
        // what it holds, its solids included (it has no fluid to restate, and solids without one do not change, D33), as a
        // fixed node keeps its inventory; one that receives is booked like every vessel.
        boolean[] reached=new boolean[nodes],kept=new boolean[nodes];
        for(int edge=0;edge<flows.length;edge++)if(flows[edge]!=0){var pipe=graph.pipes().get(edge);reached[flows[edge]>0?pipe.second():pipe.first()]=true;}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection)reached[transfer.node()]=true;
        // Owner rule of 2026-10-21 (seed 895): an open vessel keeps the gas it receives and vents what it started with. One whose
        // releases take all it holds and that no connection reaches or draws from ends the step with nothing (decision D54), a
        // kept node: what it held leaves as its vent and spill, and the ambient's work on its collapsing bag at the vessel.
        boolean[] drawnFrom=new boolean[nodes],ventedEmpty=new boolean[nodes];
        for(int edge=0;edge<flows.length;edge++)if(flows[edge]!=0){var pipe=graph.pipes().get(edge);drawnFrom[flows[edge]>0?pipe.first():pipe.second()]=true;}
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);old[node]=reservoir.inventory().moles();
            if(PassiveStepSolver.holdsNoFluidVessel(reservoir)&&!reached[node]){kept[node]=true;continue;}
            if(releases!=null&&!reached[node]&&!drawnFrom[node]&&OpenVessel.releasesAll(reservoir,releases[node])){kept[node]=ventedEmpty[node]=true;continue;}
            for(int c=0;c<components;c++)endMass[node]+=old[node][c]*molecularWeight[c];
            endMass[node]+=reservoir.inventory().solids().massKg();
            if(frozen&&reservoir.junction()) {
                // Frozen rate: a known composition, the owned inventory's (fluid and solid mass fractions).
                var solids=reservoir.inventory().solids();
                for(int c=0;c<components;c++)fractions[node][c]=old[node][c]*molecularWeight[c]/endMass[node];
                for(int c=0;c<populationKeys.size();c++)fractions[node][components+c]=solids.mass(populationKeys.get(c))/endMass[node];
            }
            else if(!reservoir.fixed())index[node]=count++;
            else storedFractions(fractions[node],candidate.get(node),reservoir,populationKeys,molecularWeight,components);
        }
        // A phase-port outflow (a draw) is booked as a fixed donor with its stream fractions frozen from the candidate
        // (plan 3.3 item 2; decision D11: the priority stream at the solved flow): it leaves its donor's diagonal (outgoing)
        // and enters the donor's right-hand side as -dt q w_stream and the receiver's as +dt q w_stream, on the pinned flow
        // like every other booking. The matrix stays one matrix for every component, so one factorization still serves them
        // all.
        double[][] drawn=new double[flows.length][];
        // Which nodes receive anything in this step (a connection's inflow, a scheduled injection): a frozen stream may not
        // book out of its donor a component the donor neither holds nor receives (see unbacked below).
        boolean[] receives=new boolean[nodes];
        for(int edge=0;edge<flows.length;edge++)if(flows[edge]!=0){var pipe=graph.pipes().get(edge);receives[flows[edge]>0?pipe.second():pipe.first()]=true;}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection)receives[transfer.node()]=true;
        for(int edge=0;edge<flows.length;edge++) {
            if(!Double.isFinite(flows[edge])||!Double.isFinite(heads[edge]))throw new IllegalArgumentException("Nonfinite candidate flow/head");
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();double massRate=Math.abs(flows[edge]);
            var rates=draws==null?null:draws[edge];boolean phase=rates!=null;
            if(phase) {
                drawn[edge]=streamFractions(model,rates,candidate.get(donor),graph.reservoirs().get(donor).state(),graph.reservoirs().get(donor),populationKeys,molecularWeight,components);
                // Unbacked traces. A vessel's Newton seed carries an entry trace (1e-12 of its amount) of every component the
                // island can bring it, and the candidate keeps some of it; the implicit bulk booking lands such a component
                // at exactly zero in a donor whose inventory lacks it, but a frozen stream would book the candidate's trace
                // out of an inventory of zero and refuse the step as a negative reconstruction at every step size (5e-25 mol
                // of nitrogen in a methane tank drawn through a VAPOR port). A donor that neither holds a component nor
                // receives anything in the step cannot deliver it, so its stream books none of it; the ledger stays exact.
                if(!receives[donor]&&!graph.reservoirs().get(donor).fixed()) {
                    var reservoir=graph.reservoirs().get(donor);
                    for(int c=0;c<components;c++)if(old[donor][c]==0)drawn[edge][c]=0;
                    for(int c=0;c<populationKeys.size();c++)if(reservoir.inventory().solids().mass(populationKeys.get(c))==0)drawn[edge][components+c]=0;
                }
            }
            double deliveredRate=pipe.filter()==null?massRate:massRate*(1-drawnSolidShare(model,rates,candidate.get(donor),graph.reservoirs().get(donor).state()));
            if(!phase)outgoing[donor]+=massRate;
            incoming[receiver]+=deliveredRate;endMass[donor]-=dt*massRate;endMass[receiver]+=dt*deliveredRate;
        }
        for(var transfer:graph.scheduledTransfers()) {
            int node=transfer.node();
            if(transfer instanceof ScheduledTransfer.Withdrawal out){outgoing[node]+=out.massKgPerSecond();endMass[node]-=dt*out.massKgPerSecond();}
            else if(transfer instanceof ScheduledTransfer.Injection in){var n=in.molesPerSecond();for(int c=0;c<components;c++)endMass[node]+=dt*n[c]*molecularWeight[c];endMass[node]+=dt*in.solidsPerSecond().massKg();}
        }
        // An open vessel's vent and spill leave it as fixed amounts (OpenVessel), off its right-hand side below.
        if(releases!=null)for(int node=0;node<nodes;node++)if(releases[node]!=null)for(var released:releases[node].each())endMass[node]-=released.mass(molecularWeight);
        // Owner decision D61 (JunctionCrystals): per solved junction, the crystal each population is if it melts there (else
        // null), and the mass of it melted over the step, booked as its component's; only where the booked flows can bring
        // the population (its holders' reach), since elsewhere the junction holds none of it exactly and the booking is the
        // one it always was. A rate booking melts nothing.
        var melting=frozen||populationKeys.isEmpty()?null:JunctionCrystals.of(graph,model);
        NetworkCrystals.Crystal[][] meltAt=new NetworkCrystals.Crystal[nodes][];double[][] melted=new double[nodes][];
        if(melting!=null)for(int k=0;k<populationKeys.size();k++) {
            var material=populationBasis.get(populationKeys.get(k)).material();if(material.crystal()==null)continue;
            boolean[] reach=null;
            for(int node=0;node<nodes;node++) {
                if(index[node]<0||!graph.reservoirs().get(node).junction())continue;
                var crystal=melting.melts(node,material);if(crystal==null)continue;
                if(reach==null)reach=reach(graph,flows,populationKeys.get(k),drawn,fractions,index,components+k);
                if(!reach[node])continue;
                if(meltAt[node]==null){meltAt[node]=new NetworkCrystals.Crystal[populationKeys.size()];melted[node]=new double[populationKeys.size()];}
                meltAt[node][k]=crystal;
            }
        }
        // Every vessel and every junction takes the row below. A junction's diagonal endMass+dt*outgoing = m_old+dt*incoming,
        // so its row is (m_old/dt+Q) w = (m_old/dt) w_old + sum q w_e, exactly the Newton junction row, and its buffer
        // is endMass*w: the relaxation state the next step's row reads (owner decision D46: a buffer, not inventory).
        var columns=new ArrayList<TreeMap<Integer,Double>>();for(int c=0;c<count;c++)columns.add(new TreeMap<>());
        double[][] rhs=new double[conserved][count];
        for(int node=0;node<nodes;node++)if(index[node]>=0) {
            checkpoint.run();int row=index[node];var reservoir=graph.reservoirs().get(node);
            // An emptying vessel's pinned flows take exactly what it held and what reached it: it ends with nothing.
            if(emptying!=null&&emptying[node]!=null)endMass[node]=0;
            else if(!(endMass[node]>0)||!Double.isFinite(endMass[node]))throw new SparseNewton.Nonconvergence("Candidate overdraws a reservoir");
            add(columns,row,row,endMass[node]+dt*outgoing[node]);
            for(int c=0;c<components;c++)rhs[c][row]=old[node][c]*molecularWeight[c];
            for(int c=0;c<populationKeys.size();c++)rhs[components+c][row]=reservoir.inventory().solids().mass(populationKeys.get(c));
            // What a melting junction holds of the crystal melts with what arrives (D61).
            if(meltAt[node]!=null)for(int k=0;k<populationKeys.size();k++)if(meltAt[node][k]!=null)melt(rhs,components,row,k,meltAt[node][k],rhs[components+k][row],melted[node],true);
        }
        var diagonal=new ArrayList<TreeMap<Integer,Double>>();for(var column:columns)diagonal.add(new TreeMap<>(column));
        var solidColumns=new ArrayList<TreeMap<Integer,Double>>();for(var column:columns)solidColumns.add(new TreeMap<>(column));
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            if(drawn[edge]!=null&&flows[edge]!=0) {
                double weight=dt*Math.abs(flows[edge]);var w=drawn[edge];
                // The donor loses the stream, solids included whether or not a filter then captures them.
                if(index[donor]>=0)for(int c=0;c<conserved;c++)rhs[c][index[donor]]-=weight*w[c];
                if(index[receiver]>=0)for(int c=0;c<conserved;c++)if(c<components||pipe.filter()==null) {
                    if(c>=components&&meltAt[receiver]!=null&&meltAt[receiver][c-components]!=null)melt(rhs,components,index[receiver],c-components,meltAt[receiver][c-components],weight*w[c],melted[receiver],false);
                    else rhs[c][index[receiver]]+=weight*w[c];
                }
                continue;
            }
            if(index[receiver]<0||flows[edge]==0)continue;
            double weight=dt*Math.abs(flows[edge]);
            if(index[donor]>=0){add(columns,index[donor],index[receiver],-weight);if(pipe.filter()==null)add(solidColumns,index[donor],index[receiver],-weight);}
            else for(int c=0;c<conserved;c++)if(c<components||pipe.filter()==null) {
                if(c>=components&&meltAt[receiver]!=null&&meltAt[receiver][c-components]!=null)melt(rhs,components,index[receiver],c-components,meltAt[receiver][c-components],weight*fractions[donor][c],melted[receiver],false);
                else rhs[c][index[receiver]]+=weight*fractions[donor][c];
            }
        }
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in&&index[in.node()]>=0) {
            var n=in.molesPerSecond();for(int c=0;c<components;c++)rhs[c][index[in.node()]]+=dt*n[c]*molecularWeight[c];
            for(int c=0;c<populationKeys.size();c++)rhs[components+c][index[in.node()]]+=dt*in.solidsPerSecond().mass(populationKeys.get(c));
        }
        if(releases!=null)for(int node=0;node<nodes;node++)if(releases[node]!=null&&index[node]>=0)for(var released:releases[node].each()) {
            var n=released.moles();for(int c=0;c<components;c++)rhs[c][index[node]]-=n[c]*molecularWeight[c];
            for(int c=0;c<populationKeys.size();c++)rhs[components+c][index[node]]-=released.solids().mass(populationKeys.get(c));
        }
        double[][] solved;
        // One backward-error check on the first component vector qualifies this factorization; the
        // remaining component columns are bounded by the junction continuity and conservation
        // checks below, which are the quantities this solve exists to produce.
        boolean melts=false;for(var at:meltAt)melts|=at!=null;
        try{var system=matrix(columns);
            if(melts)solved=meltingSolve(graph,flows,drawn,index,dt,components,populationKeys.size(),meltAt,melted,rhs,system,diagonal,solidColumns,workspace);
            else if(populationKeys.isEmpty()||graph.pipes().stream().noneMatch(p->p.filter()!=null))solved=(workspace==null?SparseLuSolver.factor(system):workspace.factor(system)).solveMultiple(rhs,SparseLuSolver.Verification.UNTIL_VERIFIED);
            else {
                solved=new double[conserved][];
                var fluid=(workspace==null?SparseLuSolver.factor(system):workspace.factor(system)).solveMultiple(Arrays.copyOf(rhs,components),SparseLuSolver.Verification.UNTIL_VERIFIED);
                var solidSystem=matrix(solidColumns);var solids=(workspace==null?SparseLuSolver.factor(solidSystem):workspace.factor(solidSystem)).solveMultiple(Arrays.copyOfRange(rhs,components,conserved),SparseLuSolver.Verification.UNTIL_VERIFIED);
                System.arraycopy(fluid,0,solved,0,components);System.arraycopy(solids,0,solved,components,solids.length);
            }}
        catch(SparseLuSolver.SolveFailure failure){throw new SparseNewton.Nonconvergence("Transport reconstruction failed: "+failure.getMessage());}
        // Nothing is drawn that is not there (B3). A phase stream's fractions are frozen from the Newton's candidate, whose
        // balance rows resolve a node's amounts to the tolerance of its total, so a trace the candidate states in the drawn
        // phase can exceed what the node holds and receives over the step (a tank holding nitrogen at 4.8e-24 of its amount
        // drew twelve times its 4.9e-22 mol through its gas stream), and the explicit booking left the component negative.
        // In real life a phase carries a component in proportion to what the node holds of it. So where the explicit
        // booking leaves a component negative anywhere, that component's streams out of solved nodes are booked implicitly
        // at the candidate's partition, r = w_stream / w_bulk, times the donor's own end fraction: the donor's column gains
        // dt q r on its diagonal and -dt q r at its receiver, so it stays diagonally dominant and the solution cannot be
        // negative; donor and receiver book the same amount (the ledger stays exact) and the energy booking is unchanged.
        // Only a component that came out negative is re-solved, so every step accepted before is unchanged to the bit.
        for(int c=0;c<components;c++) {
            boolean negative=false;for(int node=0;node<nodes;node++)if(index[node]>=0&&solved[c][index[node]]<0){negative=true;break;}
            if(!negative)continue;
            var partition=new ArrayList<TreeMap<Integer,Double>>();for(var column:columns)partition.add(new TreeMap<>(column));
            double[] b=rhs[c].clone(),ratio=new double[flows.length];int[] from=new int[flows.length];Arrays.fill(from,-1);
            for(int edge=0;edge<flows.length;edge++) {
                if(drawn[edge]==null||flows[edge]==0)continue;
                var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
                if(index[donor]<0)continue;
                from[edge]=index[donor];double weight=dt*Math.abs(flows[edge]);var bulk=candidate.get(donor);
                double share=PhaseLayout.totalAmounts(bulk)[c]*molecularWeight[c]/bulk.mass();
                ratio[edge]=share>0?drawn[edge][c]/share:0;
                b[index[donor]]+=weight*drawn[edge][c];if(index[receiver]>=0)b[index[receiver]]-=weight*drawn[edge][c];
                add(partition,index[donor],index[donor],weight*ratio[edge]);if(index[receiver]>=0)add(partition,index[donor],index[receiver],-weight*ratio[edge]);
            }
            try{solved[c]=SparseLuSolver.factor(matrix(partition)).solveMultiple(new double[][]{b},SparseLuSolver.Verification.UNTIL_VERIFIED)[0];}
            catch(SparseLuSolver.SolveFailure failure){throw new SparseNewton.Nonconvergence("Transport reconstruction failed: "+failure.getMessage());}
            // The streams now carry what the implicit booking moved, which the boundary and junction bookings below read.
            for(int edge=0;edge<flows.length;edge++)if(from[edge]>=0)drawn[edge][c]=ratio[edge]*solved[c][from[edge]];
        }
        double[][] moles=new double[nodes][];var states=new ArrayList<FluidThermodynamics.State>();
        // A junction's buffer (endMass*w) and its solids; its state keeps the Newton's free amount scale, which the
        // junction's amount row pins.
        double[][] ownedMoles=new double[nodes][];var ownedSolids=new SolidInventory[nodes];
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);
            if(index[node]<0) {
                moles[node]=old[node];if(ventedEmpty[node])moles[node]=new double[components];
                // A kept vessel is stated with the solids it holds, not with the candidate's. A pass
                // that laid it out from an arrival the pinned flows then booked as nothing leaves the candidate a roundoff of
                // that arrival, solids included, and the vessel's inventory takes its state's solids below: cold fuzz seed 85
                // put 3.4e-93 kg of CO2-I and no fluid in an empty tank, which then stood as a dry-solids vessel whose bulk
                // end had no fluid to carry anything (a viscosity of 0/0) and refused every step.
                var state=candidate.get(node);var held=reservoir.inventory().solids();
                states.add(kept[node]&&!state.solids().equals(held)?state.withSolids(held):state);
                continue;
            }
            double total=0,molesPerKg=0;
            for(int c=0;c<components;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative transport reconstruction");fractions[node][c]=w;total+=w;molesPerKg+=w/molecularWeight[c];}
            for(int c=components;c<conserved;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative solid reconstruction");fractions[node][c]=w;total+=w;}
            if(Math.abs(total-1)>1e-8)throw new SparseNewton.Nonconvergence("Junction mass continuity does not close");
            if(reservoir.junction()) {
                ownedMoles[node]=new double[components];for(int c=0;c<components;c++)ownedMoles[node][c]=endMass[node]*fractions[node][c]/molecularWeight[c];
                var owned=new ArrayList<SolidInventory.Population>();
                for(int c=0;c<populationKeys.size();c++){var source=populationBasis.get(populationKeys.get(c));owned.add(new SolidInventory.Population(source.material(),source.size(),endMass[node]*fractions[node][components+c]));}
                ownedSolids[node]=new SolidInventory(owned);
            }
            double mass=reservoir.junction()?PhaseLayout.sum(PhaseLayout.totalAmounts(candidate.get(node)))/molesPerKg:endMass[node];
            moles[node]=new double[components];for(int c=0;c<components;c++)moles[node][c]=mass*fractions[node][c]/molecularWeight[c];
            var populations=new ArrayList<SolidInventory.Population>();
            for(int c=0;c<populationKeys.size();c++){var source=populationBasis.get(populationKeys.get(c));populations.add(new SolidInventory.Population(source.material(),source.size(),mass*fractions[node][components+c]));}
            // A junction is restated with its carried gas (decision D41) as every pass seeds it (PassiveStepSolver.foldedSeeds),
            // so the reconstruction, the pass loop's phase tests and the next pass read one statement of its phases.
            // A vessel the pass left dry (it held no fluid and nothing was laid out for it, decision D54) has no phases to
            // restate the booked amounts in: they are flashed where its state stands (the ambient, or its solids' temperature),
            // and its closure below solves the rest.
            try{var restated=dryVessel(reservoir,supports,node)?model.flashTP(candidate.get(node).temperature(),candidate.get(node).pressure(),moles[node],checkpoint)
                    :repartition(model,candidate.get(node),moles[node]);if(reservoir.junction())restated=model.folded(restated,true);
                states.add(restated.withSolids(new SolidInventory(populations)));}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(reservoir.id());}
        }
        double[] energy=new double[nodes];for(int node=0;node<nodes;node++)energy[node]=graph.reservoirs().get(node).inventory().internalEnergy();
        // D61: a melting junction's energy gains the offsets of what it melted, so the crystal enters with its own enthalpy.
        var meltedRecord=Melted.none(components);
        if(melts) {
            var meltedMoles=new double[components];double meltedEnergy=0;var meltedSolids=new ArrayList<SolidInventory.Population>();
            for(int node=0;node<nodes;node++)if(melted[node]!=null)for(int k=0;k<populationKeys.size();k++)if(melted[node][k]>0) {
                var crystal=meltAt[node][k];var source=populationBasis.get(populationKeys.get(k));double offset=melted[node][k]*source.material().crystal().energyOffset();
                energy[node]+=offset;meltedEnergy+=offset;meltedMoles[crystal.component()]+=melted[node][k]/molecularWeight[crystal.component()];
                meltedSolids.add(new SolidInventory.Population(source.material(),source.size(),melted[node][k]));
            }
            meltedRecord=new Melted(meltedMoles,meltedEnergy,new SolidInventory(meltedSolids));
        }
        double[] external=new double[components];double externalEnergy=0,pumpWork=0;var boundaries=new ArrayList<BoundaryTransfer>();
        for(var transfer:graph.scheduledTransfers()) {
            checkpoint.run();int node=transfer.node();var state=states.get(node);double[] moved=new double[components];double movedEnergy,mass=0;
            if(transfer instanceof ScheduledTransfer.Withdrawal out) {
                mass=-dt*out.massKgPerSecond();
                for(int c=0;c<components;c++)moved[c]=mass*fractions[node][c]/molecularWeight[c];
                movedEnergy=mass*(state.enthalpy()/state.mass());
            } else {
                var in=(ScheduledTransfer.Injection)transfer;var n=in.molesPerSecond();
                for(int c=0;c<components;c++){moved[c]=dt*n[c];mass+=moved[c]*molecularWeight[c];}
                movedEnergy=dt*in.totalEnergyPerSecond();
            }
            SolidInventory movedSolids;int solidDirection;
            if(transfer instanceof ScheduledTransfer.Withdrawal out){movedSolids=state.solids().scale(dt*out.massKgPerSecond()/state.mass());solidDirection=-1;}
            else{movedSolids=((ScheduledTransfer.Injection)transfer).solidsPerSecond().scale(dt);solidDirection=1;mass+=movedSolids.massKg();}
            energy[node]+=movedEnergy;
            for(int c=0;c<components;c++)external[c]+=moved[c];externalEnergy+=movedEnergy;
            boundaries.add(new BoundaryTransfer(transfer.id(),moved,movedEnergy,movedSolids,solidDirection));
        }
        if(releases!=null)for(int node=0;node<nodes;node++)if(releases[node]!=null)for(var kind:new BoundaryTransfer.Kind[]{BoundaryTransfer.Kind.VENT,BoundaryTransfer.Kind.SPILL}) {
            var released=kind==BoundaryTransfer.Kind.VENT?releases[node].vent():releases[node].spill();if(released==null)continue;
            var moved=released.moles();for(int c=0;c<components;c++){moved[c]=-moved[c];external[c]+=moved[c];}
            energy[node]-=released.energyJoule();externalEnergy-=released.energyJoule();
            boundaries.add(new BoundaryTransfer(graph.reservoirs().get(node).id(),moved,-released.energyJoule(),released.solids(),released.solids().empty()?0:-1,kind));
        }
        for(int node=0;node<nodes;node++)if(ventedEmpty[node]){double ambient=-energy[node];energy[node]=0;externalEnergy+=ambient;boundaries.add(new BoundaryTransfer(graph.reservoirs().get(node).id(),new double[components],ambient));}
        var filters=new HashMap<Long,InlineFilter>();
        // A frozen booking tallies what each junction gains and loses over its connections, to form its pseudo-boundary
        // below, solids included.
        double[][] accMoles=frozen?new double[nodes][components]:null;SignedSolids[] accSolids=frozen?new SignedSolids[nodes]:null;
        if(frozen)for(int node=0;node<nodes;node++)accSolids[node]=new SignedSolids();
        for(int edge=0;edge<flows.length;edge++) {
            checkpoint.run();var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            var upstream=states.get(donor);double moved=dt*Math.abs(flows[edge]);
            // A phase-port outflow carries the frozen stream fractions booked above, and the stream's specific enthalpy on
            // the reconstructed donor (whatever number is booked leaves the donor and reaches the receiver, so the energy
            // ledger closes exactly either way); so does a bulk end drawing the gas of a donor holding solids but no liquid
            // (owner decision D33: its draw is that gas, the solids stay); any other bulk one its donor's end composition and
            // enthalpy, as always.
            var w=drawn[edge]!=null?drawn[edge]:fractions[donor];var rates=drawn[edge]==null?null:draws[edge];var phases=rates==null?null:drawnPhases(rates);
            var startState=graph.reservoirs().get(donor).state();
            double specificEnthalpy;
            if(rates==null)specificEnthalpy=upstream.enthalpy()/upstream.mass();
            else{specificEnthalpy=0;for(int k:phases)specificEnthalpy+=weight(rates,phases,k)*model.phaseSpecificEnthalpy(at(k,upstream,startState),k%3,null);}
            double movedEnergy=moved*specificEnthalpy;
            energy[donor]-=movedEnergy;
            SolidInventory movedSolids;
            if(drawn[edge]==null)movedSolids=upstream.solids().scale(moved/upstream.mass());
            else{var moving=new ArrayList<SolidInventory.Population>();
                for(int c=0;c<populationKeys.size();c++){var source=populationBasis.get(populationKeys.get(c));moving.add(new SolidInventory.Population(source.material(),source.size(),moved*w[components+c]));}
                movedSolids=new SolidInventory(moving);}
            double deliveredEnergy=movedEnergy;
            if(pipe.filter()!=null){
                double capturedEnergy;
                if(drawn[edge]==null)capturedEnergy=moved*(upstream.solidMoments().enthalpy(upstream.temperature(),upstream.pressure())/upstream.mass());
                else{double perMass=0;
                    // Each liquid phase's share of the solids, over its stream's mass, weighted by its mass flow (none in the gas).
                    for(int k:phases)if(k%3!=FluidThermodynamics.GAS){var state=at(k,upstream,startState);int phase=k%3;double share=FluidThermodynamics.phaseSolidShare(state,phase),streamMass=model.phaseMass(state,phase);
                        perMass+=weight(rates,phases,k)*(share*state.solidMoments().enthalpy(state.temperature(),state.pressure())/streamMass);}
                    capturedEnergy=moved*perMass;}
                filters.put(pipe.id(),pipe.filter().add(movedSolids,capturedEnergy));deliveredEnergy-=capturedEnergy;}
            energy[receiver]+=deliveredEnergy;
            double work=0;
            if(pipe.control() instanceof FlowControl.Mover mover&&flows[edge]>0){var suction=states.get(pipe.first());
                // Metered on the suction stream's specific volume (the suction's bulk at a BULK end) and, for a compressor,
                // the suction node's pressure (decision D8: ideal isothermal work on suction properties); the Newton's
                // energy rows book the same power (PassiveStepSolver.Equations.nodeAccumulate).
                double specificVolume;
                if(rates==null)specificVolume=suction.volume()/suction.mass();
                else{specificVolume=0;for(int k:phases){var state=at(k,suction,graph.reservoirs().get(pipe.first()).state());specificVolume+=weight(rates,phases,k)*(FluidThermodynamics.phaseVolume(state,k%3)/model.phaseMass(state,k%3));}}
                work=switch(mover) {
                    case FlowControl.Pump pump->rates==null?dt*flows[edge]*suction.volume()/suction.mass()*Math.max(0,heads[edge])
                            :dt*flows[edge]*specificVolume*Math.max(0,heads[edge]);
                    case FlowControl.Compressor compressor->dt*compressor.power(flows[edge],heads[edge],1/specificVolume,suction.pressure());
                };
                energy[receiver]+=work;pumpWork+=work;}
            // A heat-exchanging side: the heat its stream took up crossing the wall, on the donor enthalpy booked above, into
            // the receiver, and out of the island at the connection (batch 2026-10-27-heat-exchanger-design).
            double heated=0;
            if(heat!=null&&pipe.heat()!=null&&flows[edge]!=0) {
                heated=dt*heat.heat(edge,specificEnthalpy,flows[edge]);
                energy[receiver]+=heated;externalEnergy+=heated;
                boundaries.add(new BoundaryTransfer(pipe.id(),new double[components],heated,SolidInventory.EMPTY,0,BoundaryTransfer.Kind.HEAT));
            }
            if(frozen) {
                var species=new double[components];for(int c=0;c<components;c++)species[c]=moved*w[c]/molecularWeight[c];
                var delivered=pipe.filter()==null?movedSolids:SolidInventory.EMPTY;
                if(graph.reservoirs().get(receiver).junction()){for(int c=0;c<components;c++)accMoles[receiver][c]+=species[c];accSolids[receiver].add(delivered,1);}
                if(graph.reservoirs().get(donor).junction()){for(int c=0;c<components;c++)accMoles[donor][c]-=species[c];accSolids[donor].add(movedSolids,-1);}
            }
            if(graph.reservoirs().get(donor).fixed()||graph.reservoirs().get(receiver).fixed()) {
                var transferred=new double[components];for(int c=0;c<components;c++)transferred[c]=moved*w[c]/molecularWeight[c];
                if(graph.reservoirs().get(donor).fixed()){boundaries.add(new BoundaryTransfer(graph.reservoirs().get(donor).id(),transferred,movedEnergy,drawn[edge]==null?upstream.solids().scale(moved/upstream.mass()):movedSolids,1));for(int c=0;c<components;c++)external[c]+=transferred[c];externalEnergy+=movedEnergy;}
                if(graph.reservoirs().get(receiver).fixed()){var removed=transferred.clone();for(int c=0;c<components;c++){removed[c]=-removed[c];external[c]+=removed[c];}boundaries.add(new BoundaryTransfer(graph.reservoirs().get(receiver).id(),removed,-deliveredEnergy-work-heated,pipe.filter()==null?movedSolids:SolidInventory.EMPTY,-1));externalEnergy-=deliveredEnergy+work+heated;}
            }
        }
        // An emptying vessel (decision D54) held its stock and the ambient's work on its bag, W = P_ambient V_bag (its energy
        // field); what it booked out exceeds what it booked in by that to the Newton's tolerance. It ends with nothing: the
        // residual e left after its connections' bookings leaves with W - e as the ambient boundary's energy at the vessel
        // (W - e is exactly the work when the rows hold exactly), so the ledger closes exactly over vessels and boundaries.
        if(emptying!=null)for(int node=0;node<nodes;node++)if(emptying[node]!=null) {
            double ambient=emptying[node].work()-energy[node];energy[node]=0;externalEnergy+=ambient;
            boundaries.add(new BoundaryTransfer(graph.reservoirs().get(node).id(),new double[components],ambient));
        }
        // Frozen rate: each junction keeps its buffer, and what its rows would have mixed into it over dt, formed from the
        // amounts the pipe loop actually booked (solids the junction gained and lost, and its energy field's change), is held
        // back: it is the rate evaluation's junction term (owner decision D46), not a boundary.
        var held=HeldJunctions.none(components);
        if(frozen) {
            var heldMoles=new double[components];var grossMoles=new double[components];double heldEnergy=0,grossEnergy=0;var heldSolids=new SignedSolids();
            for(int node=0;node<nodes;node++)if(graph.reservoirs().get(node).junction()) {
                checkpoint.run();
                for(int c=0;c<components;c++){heldMoles[c]+=accMoles[node][c];grossMoles[c]+=Math.abs(accMoles[node][c]);}
                var gained=accSolids[node].part(1);var lost=accSolids[node].part(-1);
                heldSolids.add(gained,1);heldSolids.add(lost,-1);
                double nodeHeld=energy[node]-graph.reservoirs().get(node).inventory().internalEnergy();heldEnergy+=nodeHeld;grossEnergy+=Math.abs(nodeHeld);
            }
            held=new HeldJunctions(heldMoles,heldEnergy,heldSolids.part(1),heldSolids.part(-1),grossMoles,grossEnergy);
        }
        // Decision D36, G2: a vessel is stated at what it holds. Its amounts and energy are booked above, exactly; its
        // temperature and pressure follow from them at its volume (InventoryEquilibrium.close), not from the candidate's,
        // so no booked inflow is ever held in a state that does not show it. A junction keeps its state: it has no volume,
        // and its mass is pinned. The equilibrium a vessel is closed to is the one the step solved it with (its phase
        // support), which the equation gate then checks it against. Decision D54: the wall moves its bag volume at the
        // ambient pressure, so the energy booked above is what the vessel holds plus the work it did on the ambient,
        // P_ambient (V_bag,end - V_bag,start), which leaves the island as the ambient boundary's energy, booked at the vessel.
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);if(index[node]<0||reservoir.junction())continue;
            checkpoint.run();var state=states.get(node);double capacity=reservoir.inventory().wallCapacity();
            double workFrom=reservoir.empty()?0:PhaseLayout.bagVolume(reservoir.state(),capacity);
            var booked=reservoir.inventory().with(moles[node],energy[node],state.solids());
            try{states.set(node,dryVessel(reservoir,supports,node)?InventoryEquilibrium.solve(model,state,booked,workFrom,checkpoint)
                    :InventoryEquilibrium.close(model,state,booked,supports==null?null:supports[node],workFrom,checkpoint));}
            // A phase hold the closure meets names the vessel, directly or through the failed solve it stopped (a phase set
            // the network cannot carry refused the closure's line search).
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(reservoir.id());}
            catch(SparseNewton.Nonconvergence failure){throw failure.at(reservoir.id());}
            double moved=PhaseLayout.bagVolume(states.get(node),capacity)-workFrom;
            if(moved!=0) {
                double work=PhaseLayout.AMBIENT_PRESSURE*moved;energy[node]-=work;externalEnergy-=work;
                boundaries.add(new BoundaryTransfer(reservoir.id(),new double[components],-work));
            }
        }
        var inventories=new ArrayList<PassiveNetwork.Inventory>();
        for(int node=0;node<nodes;node++) {
            var oldNode=graph.reservoirs().get(node);var state=states.get(node);
            if(frozen&&oldNode.junction()){inventories.add(oldNode.inventory());continue;}
            // A junction's buffer record holds endMass*w and its energy field in enthalpy form (m*h).
            if(oldNode.junction()){inventories.add(oldNode.inventory().with(ownedMoles[node],energy[node],ownedSolids[node]));continue;}
            inventories.add(oldNode.fixed()?oldNode.inventory():oldNode.inventory().with(moles[node],energy[node],state.solids()));
        }
        return new Projection(inventories,states,boundaries,external,externalEnergy,pumpWork,filters,held,meltedRecord);
    }
    /**
     * The nodes population {@code key} can be booked into over this step: the solved nodes holding it, those an injection or
     * a fixed or drawn donor delivers it to (entry {@code column} of its fractions), and every node the booked flows carry
     * on from them, a filter stopping it. A node outside it holds none of the population after the booking.
     */
    private static boolean[] reach(PassiveNetwork graph,double[] flows,SolidInventory.Key key,double[][] drawn,double[][] fractions,int[] index,int column) {
        int nodes=graph.reservoirs().size();boolean[] reach=new boolean[nodes];var queue=new ArrayDeque<Integer>();
        for(int node=0;node<nodes;node++)if(index[node]>=0&&graph.reservoirs().get(node).inventory().solids().mass(key)>0){reach[node]=true;queue.add(node);}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in&&in.solidsPerSecond().mass(key)>0&&!reach[in.node()]){reach[in.node()]=true;queue.add(in.node());}
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            if(flows[edge]==0||pipe.filter()!=null||reach[receiver])continue;
            double w=drawn[edge]!=null?drawn[edge][column]:index[donor]<0?fractions[donor][column]:0;
            if(w>0){reach[receiver]=true;queue.add(receiver);}
        }
        while(!queue.isEmpty()){int at=queue.poll();
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);if(flows[edge]==0||pipe.filter()!=null||(flows[edge]>=0?pipe.first():pipe.second())!=at)continue;
                int receiver=flows[edge]>=0?pipe.second():pipe.first();if(!reach[receiver]){reach[receiver]=true;queue.add(receiver);}
            }}
        return reach;
    }
    /**
     * Books {@code mass} kg of population {@code k}, which melts at the junction of row {@code row} (D61), as its crystal's
     * component there: into that component's right-hand side, and into the junction's melted tally; {@code held}: the mass
     * is already on the population's right-hand side (the junction's own stock), which it leaves.
     */
    private static void melt(double[][] rhs,int components,int row,int k,NetworkCrystals.Crystal crystal,double mass,double[] melted,boolean held) {
        if(held)rhs[components+k][row]=0;
        rhs[crystal.component()][row]+=mass;melted[k]+=mass;
    }
    /**
     * The reconstruction's solve where a junction melts crystals (D61). Each population is solved first, on a matrix in which
     * the junctions it melts in receive none of it from a solved donor (their rows keep only the diagonal, and their
     * right-hand side holds nothing of it), grouped by those junctions; populations that melt nowhere take the matrix they
     * always had ({@code solidColumns}, which is {@code system}'s without a filter). What a solved donor delivers of a melting
     * population into such a junction is then booked as its component there, and the fluid components are solved last on
     * {@code system}. Every quantity's solution is linear in its own right-hand side, so the booking stays exact.
     */
    private static double[][] meltingSolve(PassiveNetwork graph,double[] flows,double[][] drawn,int[] index,double dt,int components,int populations,
                                           NetworkCrystals.Crystal[][] meltAt,double[][] melted,double[][] rhs,SparseMatrix system,
                                           List<TreeMap<Integer,Double>> diagonal,List<TreeMap<Integer,Double>> solidColumns,Workspace workspace) {
        int nodes=graph.reservoirs().size();var solved=new double[components+populations][];
        boolean filters=graph.pipes().stream().anyMatch(p->p.filter()!=null);
        var groups=new LinkedHashMap<BitSet,List<Integer>>();
        for(int k=0;k<populations;k++){var at=new BitSet(nodes);for(int node=0;node<nodes;node++)if(meltAt[node]!=null&&meltAt[node][k]!=null)at.set(node);groups.computeIfAbsent(at,x->new ArrayList<>()).add(k);}
        for(var group:groups.entrySet()) {
            var at=group.getKey();SparseMatrix matrix;
            if(at.isEmpty())matrix=filters?matrix(solidColumns):system;
            else {
                var columns=new ArrayList<TreeMap<Integer,Double>>();for(var column:diagonal)columns.add(new TreeMap<>(column));
                for(int edge=0;edge<flows.length;edge++) {
                    var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
                    if(drawn[edge]!=null||flows[edge]==0||index[receiver]<0||index[donor]<0||pipe.filter()!=null||at.get(receiver))continue;
                    add(columns,index[donor],index[receiver],-dt*Math.abs(flows[edge]));
                }
                matrix=matrix(columns);
            }
            var b=new double[group.getValue().size()][];for(int i=0;i<b.length;i++)b[i]=rhs[components+group.getValue().get(i)];
            var x=(workspace==null?SparseLuSolver.factor(matrix):workspace.factor(matrix)).solveMultiple(b,SparseLuSolver.Verification.UNTIL_VERIFIED);
            for(int i=0;i<b.length;i++)solved[components+group.getValue().get(i)]=x[i];
        }
        // What solved donors deliver of a melting population into the junction it melts in.
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            if(drawn[edge]!=null||flows[edge]==0||index[receiver]<0||index[donor]<0||pipe.filter()!=null||meltAt[receiver]==null)continue;
            for(int k=0;k<populations;k++)if(meltAt[receiver][k]!=null)
                melt(rhs,components,index[receiver],k,meltAt[receiver][k],dt*Math.abs(flows[edge])*solved[components+k][index[donor]],melted[receiver],false);
        }
        var fluid=(workspace==null?SparseLuSolver.factor(system):workspace.factor(system)).solveMultiple(Arrays.copyOf(rhs,components),SparseLuSolver.Verification.UNTIL_VERIFIED);
        System.arraycopy(fluid,0,solved,0,components);
        return solved;
    }
    /**
     * The flows a reconstruction books so that every junction passes exactly what it receives and its buffer keeps the
     * mass m_J it was sized with ({@link PassiveNetwork#sizeJunctionHoldups}): a junction is a fitting full of fluid, its
     * mass does not change (the fitting's mass continuity, owner decision D46). The Newton's net-mass row at a junction
     * closes only to its tolerance, so {@code dt (In - Out) != 0} by that residual, which a buffer with no restoring term
     * would integrate into m. The residual is booked onto the junction's own connections, per junction J in flow order:
     * with a positive outflow its outflows are scaled by {@code f_J = In'/Out}, In' what its inflows deliver as booked (an
     * upstream junction's factor applied, filter capture removed), so the excess leaves with the junction's own
     * composition; with no outflow its inflows from non-junction donors are scaled by
     * {@code g_J = (Out - In'_junction)/In'_other} when that lies in [0, 1] (an inflow is only ever reduced, since
     * amplifying one moves the junction's composition off the Newton's); otherwise (a junction cycle, or nothing to
     * scale) it stays unpinned. Applies to every reconstruction. Exact: every edge still books one amount at both ends,
     * so the change of the vessels plus the junction term equals the boundaries per component, solid population and
     * energy; only the booking moves, the flows, states and pipe histories are unchanged.
     */
    private static double[] pinnedFlows(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,FluidThermodynamics model,double[][] draws,
                                        Emptying[] emptying) {
        int nodes=graph.reservoirs().size();boolean[] junction=new boolean[nodes];boolean any=false;
        for(int node=0;node<nodes;node++)any|=junction[node]=graph.reservoirs().get(node).junction();
        if(!any)return flows;
        int[] indegree=new int[nodes];
        for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();if(junction[donor]&&junction[receiver])indegree[receiver]++;}
        var queue=new ArrayDeque<Integer>();for(int node=0;node<nodes;node++)if(junction[node]&&indegree[node]==0)queue.add(node);
        double[] factor=new double[flows.length];Arrays.fill(factor,1);
        while(!queue.isEmpty()) {
            int node=queue.poll();double fromJunctions=0,fromOthers=0,out=0;
            for(int edge=0;edge<flows.length;edge++) {
                if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();
                double rate=Math.abs(flows[edge]);
                if(receiver==node){double delivered=rate*factor[edge];if(pipe.filter()!=null)delivered*=1-drawnSolidShare(model,draws==null?null:draws[edge],candidate.get(donor),graph.reservoirs().get(donor).state());if(junction[donor])fromJunctions+=delivered;else fromOthers+=delivered;}
                if(donor==node)out+=rate;
            }
            // An emptying vessel (decision D54) passes what reaches it and its whole stock: dt (In' - Out') = -m_stock.
            double outflow=emptying!=null&&emptying[node]!=null?fromJunctions+fromOthers+emptying[node].drain():fromJunctions+fromOthers,inflow=out-fromJunctions;
            if(out>0&&Double.isFinite(outflow)){double f=outflow/out;for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);if((flows[edge]>0?pipe.first():pipe.second())==node)factor[edge]=f;}}
            else if(fromOthers>0&&inflow>=0&&inflow<=fromOthers){double g=inflow/fromOthers;for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();if(receiver==node&&!junction[donor])factor[edge]=g;}}
            for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();if(donor==node&&junction[receiver]&&--indegree[receiver]==0)queue.add(receiver);}
        }
        var booked=flows.clone();for(int edge=0;edge<flows.length;edge++)booked[edge]*=factor[edge];
        return booked;
    }
    /** A signed per-population solid tally of a junction's booked flows. */
    private static final class SignedSolids {
        private final TreeMap<SolidInventory.Key,SolidInventory.Population> basis=new TreeMap<>();
        private final TreeMap<SolidInventory.Key,Double> mass=new TreeMap<>();
        void add(SolidInventory stock,double factor){for(var p:stock.populations()){basis.putIfAbsent(p.key(),p);mass.merge(p.key(),factor*p.massKg(),Double::sum);}}
        /** The positive part of sign * tally, as a stock. */
        SolidInventory part(int sign) {
            var result=new ArrayList<SolidInventory.Population>();
            for(var entry:mass.entrySet()){double m=sign*entry.getValue();if(m>0){var p=basis.get(entry.getKey());result.add(new SolidInventory.Population(p.material(),p.size(),m));}}
            return new SolidInventory(result);
        }
    }
    /**
     * The node restated with the booked amounts in the phases it already holds, each component split as it was. A
     * hydrocarbon booked into a node that holds no hydrocarbon phase at all (a junction seeded as pure water that the
     * step's flows deliver nitrogen to, hold class E3) has no phase to go to and no partial pressure to be evaluated at,
     * so such a node is flashed at its temperature and pressure instead, the phase set the booked amounts form; it is
     * never committed as it stands, the pass loop reseeds the node from it and solves the pass again. Every other node is
     * restated exactly as before, with the flash's trace-phase rule applied to the split (decision D34,
     * {@link FluidThermodynamics#foldTracePhases}): a node is never committed with a phase its flash would not give it.
     */
    static FluidThermodynamics.State repartition(FluidThermodynamics model,FluidThermodynamics.State state,double[] moles) {
        double[] oldL=state.liquidView(),oldV=state.vaporView(),l=new double[oldL.length],v=new double[l.length];
        if(appearsWithoutPhase(state,moles))return model.flashTP(state.temperature(),state.pressure(),moles,()->{}).withSolidState(state.solids(),state.solidMoments());
        for(int c=0;c<l.length;c++) {
            double total=oldL[c]+oldV[c];
            if(total==0){if(state.liquidVolume()>0)l[c]=moles[c];else v[c]=moles[c];}
            else if(oldL[c]<oldV[c]){l[c]=moles[c]*(oldL[c]/total);v[c]=moles[c]-l[c];}
            else{v[c]=moles[c]*(oldV[c]/total);l[c]=moles[c]-v[c];}
        }
        double water=state.waterLiquid()+state.waterVapor(),wl,wv;
        if(water==0){wl=state.vaporVolume()>0?0:moles[l.length];wv=moles[l.length]-wl;}
        else if(state.waterLiquid()<state.waterVapor()){wl=moles[l.length]*(state.waterLiquid()/water);wv=moles[l.length]-wl;}
        else{wv=moles[l.length]*(state.waterVapor()/water);wl=moles[l.length]-wv;}
        double[] split={wl,wv};double pc=state.hydrocarbonPartialPressure();
        if(model.foldTracePhases(l,v,split)){SolverDiagnostics.count(SolverDiagnostics.traceFolds);pc=FluidThermodynamics.foldedHydrocarbonPressure(state.pressure(),pc,v,split[1]);}
        return model.adoptingState(state.temperature(),state.pressure(),l,v,split[0],split[1],pc,null).withSolidState(state.solids(),state.solidMoments());
    }
    /** A vessel that held no fluid at the step start and that the step solved with no phase support: no unknowns, its state
     * the ambient or its solids' (decision D54, {@code PassiveStepSolver.dry}). One the step laid out holds its own phases. */
    private static boolean dryVessel(PassiveNetwork.Reservoir reservoir,PhaseSupport[][] supports,int node) {
        return PassiveStepSolver.holdsNoFluidVessel(reservoir)&&(supports==null||supports[node]==null);
    }
    /** Whether {@code moles} book a hydrocarbon into a state that holds no hydrocarbon phase (see {@link #repartition}). */
    static boolean appearsWithoutPhase(FluidThermodynamics.State state,double[] moles) {
        if(state.liquidVolume()>0||state.vaporProperties()!=null)return false;
        double[] l=state.liquidView(),v=state.vaporView();
        for(int c=0;c<l.length;c++)if(l[c]+v[c]==0&&moles[c]>0)return true;
        return false;
    }
    private static void add(List<TreeMap<Integer,Double>> columns,int column,int row,double value){columns.get(column).merge(row,value,Double::sum);}
    private static SparseMatrix matrix(List<TreeMap<Integer,Double>> columns) {
        int count=columns.size();int[] offsets=new int[count+1];for(int c=0;c<count;c++)offsets[c+1]=offsets[c]+columns.get(c).size();
        int[] rows=new int[offsets[count]];double[] values=new double[rows.length];int at=0;
        for(var column:columns)for(var entry:column.entrySet()){rows[at]=entry.getKey();values[at++]=entry.getValue();}
        return SparseMatrix.adopting(count,offsets,rows,values);
    }
}
