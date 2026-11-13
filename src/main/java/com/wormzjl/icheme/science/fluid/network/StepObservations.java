package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PH_LIQUID;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PH_VAPOUR;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.liquidEnthalpy;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.liquidMoles;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.Equations;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PipeObservation;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.*;

/**
 * What a converged step reports and the ledger it is accepted on: the valves' cavitation and the movers' suction readings
 * as results ({@link PipeObservation}), and the step's conservation check ({@link #checkConservation}). Observational only:
 * nothing here feeds back into the solve. One per solver ({@code PassiveStepSolver.observations}), built with it; it
 * reads the solve's constants and the solver's suction reads through {@code solver} and holds no state of its own (S-01,
 * batch 2026-11-13-codebase-review, D-S4: moved out of the solver with every body unchanged but its qualifiers).
 */
final class StepObservations {
    /** The solver whose converged points this reads: its solve constants and its suction and phase reads. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    StepObservations(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /** A control valve's incipient cavitation pressure ratio x_Fz (IEC 60534-8-4): it cavitates from ΔP = x_Fz (P1 − Pv). */
    static final double VALVE_INCIPIENT=.3;
    /**
     * A flowing valve's cavitation (owner 2026-11-10, option A: the liquid valve's loop by σ = (P1 − Pv) / (P1 − P2), Pv the
     * vapour pressure of the liquid it passes), from its donor node's state {@code s}, its inlet pressure {@code p1} and its
     * outlet pressure {@code p2}: FLASHING where p2 lies below Pv (the vapour stays), CAVITATING where σ ≤ 1 / x_Fz
     * ({@link #VALVE_INCIPIENT}: the vena contracta falls below Pv and the bubbles collapse downstream), else PLAIN. Water
     * reads its saturation pressure at the donor's temperature. A hydrocarbon liquid is throttled at its own enthalpy, as
     * {@link PassiveStepSolver#releasePressure} reads it, but tested at two pressures instead of bisected: it flashes if it holds vapour at
     * p2, and it cavitates if it holds vapour at p1 − (p1 − p2) / x_Fz, where σ = 1 / x_Fz (any Pv cavitates where that
     * pressure is below the box's floor); a liquid beside vapour stands at its bubble point, its node's pressure. The worse
     * of the two liquids counts. NONE with no liquid or where the thermodynamics refuses. Observational only.
     */
    PipeObservation.Cavitation cavitation(FluidThermodynamics.State s,double p1,double p2,Runnable checkpoint) {
        var none=PipeObservation.Cavitation.NONE;
        if(!(p1>p2)||!Double.isFinite(p1+p2))return FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS)?PipeObservation.Cavitation.PLAIN:none;
        double incipient=p1-(p1-p2)/VALVE_INCIPIENT;var worst=none;
        if(FluidThermodynamics.holdsLiquid(s)) {
            PipeObservation.Cavitation oil;
            if(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS))oil=regime(s.pressure(),p2,incipient);
            else {
                double[] x=liquidMoles(s);
                double h=liquidEnthalpy(s);int out=solver.liquidPH(s,x,h,Math.max(p2,NumericalBox.MINIMUM_PRESSURE),checkpoint);
                if(out==PH_VAPOUR)oil=PipeObservation.Cavitation.FLASHING;
                else if(out!=PH_LIQUID)oil=none;
                else if(incipient<=NumericalBox.MINIMUM_PRESSURE)oil=PipeObservation.Cavitation.CAVITATING;
                else oil=solver.liquidPH(s,x,h,incipient,checkpoint)==PH_VAPOUR?PipeObservation.Cavitation.CAVITATING:PipeObservation.Cavitation.PLAIN;
            }
            if(oil.ordinal()>worst.ordinal())worst=oil;
        }
        return worst;
    }
    /** The regime of a liquid of vapour pressure {@code pv} throttled to {@code p2}, cavitating from {@code incipient} (Pa). */
    static PipeObservation.Cavitation regime(double pv,double p2,double incipient) {
        if(!Double.isFinite(pv))return PipeObservation.Cavitation.NONE;
        return pv>p2?PipeObservation.Cavitation.FLASHING:pv>=incipient?PipeObservation.Cavitation.CAVITATING:PipeObservation.Cavitation.PLAIN;
    }
    /**
     * W10b (owner decision D34): a running liquid pump's σ = NPSHA / NPSH3 at the accepted point, which its wear reads
     * ({@link com.wormzjl.icheme.science.machine.Wear}): its suction end's pressure over the floor of the liquid it draws
     * ({@link PassiveStepSolver#vapourPressure}), as a head of that liquid ({@code liquidDensities}), over this solve's NPSH3 at its suction
     * flow ({@link com.wormzjl.icheme.science.machine.Npsh#required}). NaN where it has no NPSH design (a set-flow
     * constructed pump), no floor or no liquid. Observational: never fed back into the solve.
     */
    double npshRatio(Equations e,int edge,List<FluidThermodynamics.State> states,double massFlow) {
        double rho=solver.constants.liquidDensities[edge],required=npshRequired(edge,massFlow);
        if(!(required>0))return Double.NaN;
        var pipe=e.graph.pipes().get(edge);int a=pipe.first();double floor;
        try{floor=solver.vapourPressure(e,edge,states.get(a));}catch(IllegalArgumentException outsideData){return Double.NaN;}
        if(!(floor<Double.POSITIVE_INFINITY))return Double.NaN;
        return (PortStreams.endPressure(model,e.graph.reservoirs().get(a),pipe.firstPort(),states.get(a))-floor)/(rho*GRAVITY)/required;
    }
    /** W10d: a running liquid pump's NPSH3 (m) at the accepted mass flow, on this solve's NPSH design and liquid density; NaN where it has none. */
    double npshRequired(int edge,double massFlow) {
        var design=solver.constants.npshDesigns[edge];double rho=solver.constants.liquidDensities[edge];
        return design==null||!(rho>0)?Double.NaN:design.required(Math.max(0,massFlow)/rho);
    }
    /**
     * W10c (owner decision D35): a running centrifugal pump's suction vapour share in Turpin onsets, {@code r = α/α_T} at its
     * suction node's pressure ({@link com.wormzjl.icheme.science.machine.GasEntrainment#onset}), the share this solve read
     * ({@link PassiveStepSolver#readSuctions}); NaN for a set-flow pump, which has no head factor, or no suction pressure. Observational.
     */
    double gasRatio(int edge,FlowControl.Mover mover,double suctionPressure) {
        if(!(mover instanceof FlowControl.Machine)||!(suctionPressure>0))return Double.NaN;
        return solver.constants.vapourShares[edge]/com.wormzjl.icheme.science.machine.GasEntrainment.onset(suctionPressure);
    }
    /**
     * W10b (owner decision D34): whether a liquid pump whose driver is on runs dry at the accepted point: vapour-locked
     * ({@link PassiveStepSolver#vapourLocks}), refused for a dry suction ({@code INLET_WRONG_PHASE}, decision D58), or gas-locked (a
     * centrifugal pump passing nothing with its suction's vapour share past the onset, {@link com.wormzjl.icheme.science.machine.GasEntrainment#onset}).
     */
    boolean dry(int edge,FlowControl.Mover mover,FlowControl.Mode mode,double massFlow,double suctionPressure) {
        if(solver.vapourLocked(edge)||mode==FlowControl.Mode.INLET_WRONG_PHASE)return true;
        return mover instanceof FlowControl.Machine&&Math.abs(massFlow)<=1e-10&&suctionPressure>0&&solver.constants.vapourShares[edge]>com.wormzjl.icheme.science.machine.GasEntrainment.onset(suctionPressure);
    }
    /**
     * The step's ledger (owner decision D46): the change of the vessels and filter cakes equals the boundary transfers plus
     * the junction term, per component, per solid population and for energy (pump work included). The junction term is
     * the change of the junctions' buffers (a buffer is the state of its relaxation row, not inventory) plus what a rate
     * evaluation's frozen junctions held back ({@link ConservativeTransport.HeldJunctions}). Each tolerance is scaled by
     * the boundaries' turnover and the stocks held. The energy is {@code U + sum h dm}: no stream carries potential energy
     * (owner decision D37, simplification S1), so a pump's lift work stays in the fluid as heat (0.023 K per 10 m of water).
     */
    void checkConservation(PassiveNetwork graph,ConservativeTransport.Projection projection) {
        int count=model.hydrocarbon.componentCount()+1;double[] before=new double[count],after=new double[count],turnover=new double[count];double eb=0,ea=0,energyScale=0;
        double[] junction=projection.held().moles(),bufferedBefore=new double[count],bufferedAfter=new double[count];double junctionEnergy=projection.held().energyJoule();
        for(var boundary:projection.boundaries()){var n=boundary.moles();for(int c=0;c<count;c++)turnover[c]+=Math.abs(n[c]);energyScale+=Math.abs(boundary.totalEnergyJoule());}
        // A rate evaluation's held accumulation was a pseudo-boundary at each junction before D46 and counts in the turnover
        // as it did, junction by junction.
        var gross=projection.held().grossMoles();for(int c=0;c<count;c++)turnover[c]+=gross[c];energyScale+=projection.held().grossEnergyJoule();
        for(int i=0;i<graph.reservoirs().size();i++) {
            var reservoir=graph.reservoirs().get(i);var old=reservoir.inventory();var next=projection.inventories().get(i);
            if(reservoir.fixed())continue;
            var a=old.moles();var b=next.moles();double ebNode=old.internalEnergy(),eaNode=next.internalEnergy();
            if(reservoir.junction()){for(int j=0;j<count;j++){junction[j]+=b[j]-a[j];bufferedBefore[j]+=a[j];bufferedAfter[j]+=b[j];}junctionEnergy+=eaNode-ebNode;}
            else{for(int j=0;j<count;j++){before[j]+=a[j];after[j]+=b[j];}eb+=ebNode;ea+=eaNode;}
            energyScale+=Math.abs(old.internalEnergy());
        }
        // Per population: vessels and cakes before [0] and after [1], boundaries [2], buffers before [3] and after [4], and a
        // rate evaluation's held accumulation [5] (net, signed as the junction term).
        var solidBalance=new TreeMap<SolidInventory.Key,double[]>();
        for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()){
            int buffer=graph.reservoirs().get(i).junction()?3:0;
            for(var p:graph.reservoirs().get(i).inventory().solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[buffer]+=p.massKg();
            for(var p:projection.inventories().get(i).solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[buffer+1]+=p.massKg();
        }
        for(var p:projection.held().gained().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[5]+=p.massKg();
        for(var p:projection.held().lost().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[5]-=p.massKg();
        for(var pipe:graph.pipes())if(pipe.filter()!=null){
            var old=pipe.filter();var next=projection.filters().getOrDefault(pipe.id(),old);eb+=old.energyJoule();ea+=next.energyJoule();energyScale+=Math.abs(old.energyJoule())+Math.abs(next.energyJoule());
            for(var p:old.captured().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[0]+=p.massKg();
            for(var p:next.captured().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[1]+=p.massKg();
        }
        for(var boundary:projection.boundaries())for(var p:boundary.solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[2]+=boundary.solidDirection()*p.massKg();
        // What the junctions melted (owner decision D61) leaves the solid ledger and enters the component and energy ones.
        var melted=projection.melted();
        for(var p:melted.solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[2]-=p.massKg();
        for(var b:solidBalance.values())if(Math.abs(b[0]+b[2]-b[1]-(b[4]-b[3]+b[5]))>1e-10+1e-8*Math.max(Math.max(b[0]+b[3],b[1]+b[4]),Math.abs(b[2]-b[5])))throw new SparseNewton.Nonconvergence("Solid population balance failed");
        var external=projection.externalMoles();var meltedMoles=melted.moles();
        for(int i=0;i<count;i++)if(Math.abs(before[i]+external[i]+meltedMoles[i]-after[i]-junction[i])>1e-10+1e-8*Math.max(turnover[i],Math.max(before[i]+bufferedBefore[i],after[i]+bufferedAfter[i])))throw new SparseNewton.Nonconvergence("Component balance failed: "+i+" before="+before[i]+" after="+after[i]+" external="+external[i]+" junction="+junction[i]);
        if(Math.abs(ea-eb+junctionEnergy-projection.pumpWork()-projection.externalEnergy()-melted.energyJoule())>1e-4+1e-6*(energyScale+Math.abs(projection.pumpWork())+Math.abs(melted.energyJoule())))throw new SparseNewton.Nonconvergence("Total energy balance failed");
    }
}
