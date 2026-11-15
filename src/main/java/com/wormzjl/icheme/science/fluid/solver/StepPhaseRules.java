package com.wormzjl.icheme.science.fluid.solver;

import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.nothing;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.phaseBits;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.sum;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.vanishing;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.Prepared;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State;
import java.util.function.UnaryOperator;

/**
 * The phase rules of a step (finding F8 of batch 2026-11-13-codebase-review): the outer phase check of a node
 * ({@link #phaseCheck}) and a pipe junction's own split ({@link #junctionSplit}), which closes the junction's rows on a
 * Newton solve of this package. They read the model they judge ({@code model}, an argument of each) and its public
 * rules (the trace fold, the vanishing share, the engine's answers); the thermodynamics port imports nothing of the
 * solver. Stateless: every member is static.
 */
public final class StepPhaseRules {
    private StepPhaseRules(){}
    /**
     * The outer phase check of one node of the fluid network (owner decisions D25 and D26), never inside a residual. A
     * node at fixed amounts, energy and volume has one equilibrium, which its own rows find once its layout holds every
     * phase that equilibrium needs; a TP answer at the node's temperature and pressure tells which phases are missing but
     * not how much of each the node holds. So the check never swaps one populated phase set for another on a TP verdict,
     * and an added phase starts at the node's own split:
     * <ul>
     * <li>a phase leaves only when the solve empties it: the trace fold ({@link FluidThermodynamics#folded}, D34), and a phase held below
     * {@link FluidThermodynamics#VANISHING_SHARE} and at a smaller share than at {@code from} (owner decision D38 C, {@link FluidThermodynamics#vanishing});</li>
     * <li>a node holding every phase its material can form has none to add, and no TP answer is formed: a vapour and a
     * liquid on a list without water, all three slots with water (W3b); a node holding no fluid has none. A stand-in liquid ({@link FluidThermodynamics#standIn}, owner
     * decision D31 B) is no liquid of the node's own and never makes it complete;</li>
     * <li>where the TP answer ({@link FluidThermodynamics#flashTP}) holds a phase the node lacks that is not in the solve's record
     * ({@code record}, A85), the node's own split decides it (D26): a vessel's {@code vesselSplit} closes its inventory
     * from the vessel's own state at the crossing (owner decision D40 A, {@link #crossing}); a pipe junction's
     * {@link #junctionSplit} closes its own rows at its pressure and enthalpy from the engine's PH answer there
     * ({@link FluidThermodynamics#equilibriumPH}; the TP answer with the junction's populated phases, {@link #tpSeed}, where that fails). A
     * node whose own split has no start, fails or drives a phase to nothing on its way proposes nothing;</li>
     * <li>the own split decides by where its closure went, converged or not: a proposed phase it drove under the vanishing
     * share is refused ({@link FluidThermodynamics#nothing}), and the refusal is written to the record so the TP answer does not propose it
     * again in this solve. A proposed phase that stands seeds the node.</li>
     * </ul>
     *
     * @return {@code state} itself when it keeps its phases and gains none; otherwise the node's new seed
     */
    public static State phaseCheck(FluidThermodynamics model,State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,Runnable checkpoint) {
        return phaseCheck(model,state,junction,from,record,at,prepared,vesselSplit,false,false,checkpoint);
    }
    /**
     * {@link #phaseCheck}; {@code rigid}: a rigid vessel's (batch 2026-11-10-rigid-reservoir), whose crossing holds its volume
     * at any pressure; {@code open}: an open vessel's, whose wall is never taut, so its crossing is at its pressure (its ambient).
     */
    public static State phaseCheck(FluidThermodynamics model,State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,boolean rigid,boolean open,Runnable checkpoint) {
        int shrinking=from==null?0:vanishing(from,state,rigid);
        var kept=model.folded(state,rigid&&!junction,shrinking);var checked=kept;
        int excluded=(record==null?0:record[at])|shrinking&phaseBits(state)&~phaseBits(kept),refused=0;
        double[] totals=kept.totals();
        int own=ownPhaseBits(model,kept);
        // A node holding no fluid (a bed of solids alone) has no phase to add.
        boolean complete=!(sum(totals)>0)||(totals[totals.length-1]>0?own==7:(own&3)==3);
        if(!complete) {
            if(prepared!=null&&prepared.temperature()!=kept.temperature())prepared=null;
            var equilibrium=model.folded(model.equilibriumTP(kept.temperature(),kept.pressure(),totals,checkpoint,prepared).withSolidState(kept.solids(),kept.solidMoments()),junction);
            int proposed=lacking(model,kept,equilibrium)&~excluded;
            if(proposed!=0) {
                State start,split;
                if(junction) {
                    var engine=model.equilibriumPH(kept.temperature(),kept.pressure(),state.enthalpy()-state.solidMoments().enthalpy(state.temperature(),state.pressure()),totals,checkpoint);
                    start=engine!=null?engine.withSolidState(kept.solids(),kept.solidMoments()):tpSeed(model,kept,equilibrium,prepared);
                    split=junctionSplit(model,totals,state.enthalpy(),state.mass(),kept.pressure(),start,checkpoint);
                }
                else{start=crossing(model,state,kept,proposed,totals,rigid,open,checkpoint);split=start==null||vesselSplit==null?null:vesselSplit.apply(start);}
                if(split!=null) {
                    SolverDiagnostics.count(SolverDiagnostics.phaseSeeds);
                    var seed=model.folded(split.withSolidState(kept.solids(),kept.solidMoments()),junction,rigid);
                    refused=proposed&(~lacking(model,kept,seed)|nothing(split,rigid));
                    int added=lacking(model,kept,seed)&~excluded;
                    if(added!=0&&(added&refused)==0)checked=seed;
                }
                else refused=proposed;
                if(refused!=0)SolverDiagnostics.count(SolverDiagnostics.phaseRefusals);
            }
        }
        if(record!=null)record[at]|=phaseBits(state)&~phaseBits(checked)|refused;
        return checked;
    }
    /** {@link FluidThermodynamics#phaseBits} of the phases a node holds as its own (owner decision D31 B): a stand-in liquid ({@link FluidThermodynamics#standIn}) is
     * a crystal's condensed share, no liquid of the node's own. */
    private static int ownPhaseBits(FluidThermodynamics model,State state){return phaseBits(state)&~model.standIn(state);}
    /** The phases ({@link FluidThermodynamics#phaseBits}) of {@code answer} that {@code node} lacks: an own phase the node holds none of, and a
     * stand-in where the node holds none in that slot. */
    private static int lacking(FluidThermodynamics model,State node,State answer){return ownPhaseBits(model,answer)&~ownPhaseBits(model,node)|phaseBits(answer)&~phaseBits(node);}
    /**
     * The TP-based state of {@link #phaseCheck} (decision D26): the TP answer {@code equilibrium} at {@code kept}'s
     * temperature and pressure, for {@code kept}'s totals, with the populated phases of {@code kept} it lacks, so that no
     * TP verdict takes a populated phase away (D25): each component shared between the answer's phases and those kept
     * phases in proportion to what each holds of it. A junction's seed where its PH answer fails; never a vessel's.
     */
    private static State tpSeed(FluidThermodynamics model,State kept,State equilibrium,Prepared prepared) {
        int answer=phaseBits(equilibrium),lacking=phaseBits(kept)&~answer;
        if(lacking==0)return equilibrium;
        int n=model.componentCount();double[][] out=new double[3][n];int[] bit={2,1,4};
        for(int c=0;c<n;c++) {
            double[] share=new double[3];double sum=0;
            for(int slot=0;slot<3;slot++) {
                share[slot]=(answer&bit[slot])!=0?equilibrium.amountsView(slot)[c]:(lacking&bit[slot])!=0?kept.amountsView(slot)[c]:0;
                sum+=share[slot];
            }
            double total=kept.vaporAmountsView()[c]+kept.liquidAmountsView()[c]+kept.aqueousAmountsView()[c];
            if(!(sum>0)){for(int slot=0;slot<3;slot++)if((phaseBits(kept)&bit[slot])!=0){out[slot][c]=total;break;}continue;}
            for(int slot=0;slot<3;slot++)out[slot][c]=total*(share[slot]/sum);
        }
        try{return model.adoptingState(kept.temperature(),kept.pressure(),out[0],out[1],out[2],prepared).withSolidState(kept.solids(),kept.solidMoments());}
        catch(IllegalArgumentException unevaluable){return equilibrium;}
    }
    /**
     * A pipe junction's own split (owner decision D26, {@link #phaseCheck}; the arriving-phase seed of
     * {@code PassiveStepSolver.arrivingPhases}): its own rows ({@link PhaseLayout#junctionResidual}), isolated at the
     * pressure {@code p} it holds, with the amounts {@code amounts} (the conserved basis, water last), the enthalpy
     * {@code enthalpy} (J, the network's datum) and the mass {@code mass} its rows weigh them by, closed from {@code start}
     * in {@code start}'s phase layout. The closed point is restated at exactly {@code p} and {@code amounts}. {@code null}
     * where the closure fails or drives a phase of {@code start} to nothing on its way ({@link FluidThermodynamics#vanishing}, D38 C).
     */
    public static State junctionSplit(FluidThermodynamics model,double[] amounts,double enthalpy,double mass,double p,State start,Runnable checkpoint) {
        try {
            var layout=new PhaseLayout(model,start,null,null,PhaseLayout.support(model,start,null,null));
            int n=model.componentCount();double[] fractions=new double[n];
            for(int i=0;i<n;i++)fractions[i]=amounts[i]*model.molecularWeight(i)/mass;
            var solved=SparseNewton.solve(layout.junctionClosure(fractions,enthalpy/mass,p,start),layout.encode(start),
                    new SparseNewton.Settings(SparseNewton.ITERATIONS,1e-10,1e-6,24),checkpoint);
            var closed=layout.decode(solved.variables(),0);
            // Exactly what the junction holds: each component split as the closure split it, at exactly the retained pressure.
            double[][] out=new double[3][n];
            for(int i=0;i<n;i++) {
                double v=closed.vaporAmountsView()[i],l=closed.liquidAmountsView()[i],a=closed.aqueousAmountsView()[i],total=v+l+a;
                if(!(total>0)){int slot=closed.phase(State.LIQUID)!=null?State.LIQUID:closed.phase(State.AQUEOUS)!=null?State.AQUEOUS:State.VAPOR;out[slot][i]=amounts[i];continue;}
                // The rest of the rounding goes to the slot holding the most of it: put in the liquid slot of a gas and an
                // aqueous liquid, it was a 1e-15 mol liquid of dissolved nitrogen with no liquid root, or a negative amount,
                // and the junction's condensate was refused (W3b stage A, a cooling tower's suction line).
                double[] share={v,l,a};int most=0;for(int k=1;k<3;k++)if(share[k]>share[most])most=k;
                double placed=0;for(int k=0;k<3;k++)if(k!=most){out[k][i]=amounts[i]*(share[k]/total);placed+=out[k][i];}
                out[most][i]=amounts[i]-placed;
            }
            return model.adoptingState(closed.temperature(),p,out[0],out[1],out[2],null).withSolidState(start.solids(),start.solidMoments());
        }
        catch(PhaseLayout.Vanished|SparseNewton.Nonconvergence|IllegalArgumentException failed){return null;}
    }
    /**
     * A vessel's own state at a crossing (owner decision D40 A), the start of its own split in {@link #phaseCheck}: the
     * engine's equilibrium of the fluid it holds under its wall's law, the problem its own closure solves
     * ({@code InventoryEquilibrium}), as a network state ({@link FluidThermodynamics#adopt}) folded as a vessel: a slack bag at its fluid
     * enthalpy and the ambient pressure ({@link FluidThermodynamics#equilibriumPH}), a taut vessel at its fluid's internal energy and volume
     * ({@link FluidThermodynamics#equilibriumAtVolume}), warm-started at its temperature and pressure, both under the step's competition. The TP
     * answer says only which phase is missing: its amounts ignore the vessel's volume and are never the seed. {@code null}
     * where the engine does not converge, answers a phase set the network cannot carry, or answers none of the phases
     * {@code proposed} adds (no crossing).
     */
    private static State crossing(FluidThermodynamics model,State state,State kept,int proposed,double[] totals,boolean rigid,boolean open,Runnable checkpoint) {
        double t=kept.temperature(),p=kept.pressure();var solids=state.solidMoments();
        // An open vessel's wall is never taut (PhaseLayout.wallRow): its surface stands at its own ambient, which falls with
        // height (batch 2026-11-10-chimney), so the slack branch is its only one at any pressure. A closed bag's slack branch
        // holds it at the sea-level ambient.
        var own=!rigid&&(open||p<=PhaseLayout.AMBIENT_PRESSURE*(1+SLACK_PRESSURE))?model.equilibriumPH(t,p,state.enthalpy()-solids.enthalpy(state.temperature(),state.pressure()),totals,checkpoint)
                :model.equilibriumAtVolume(t,p,state.internalEnergy()-solids.internalEnergy(state.temperature()),state.volume()-solids.volume(),totals,checkpoint);
        if(own==null)return null;
        var seed=model.folded(own.withSolidState(kept.solids(),kept.solidMoments()),false,rigid);
        return (lacking(model,kept,seed)&proposed)!=0?seed:null;
    }
    /** How far above the ambient pressure, relatively, a closed vessel's state still reads as its wall's slack branch in
     * {@link #crossing}: the slack row holds it at the ambient to the solve's tolerance (a vessel's closure, 1e-11). */
    private static final double SLACK_PRESSURE=1e-9;
}
