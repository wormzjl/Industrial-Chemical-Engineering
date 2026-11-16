package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PH_LIQUID;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PH_VAPOUR;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.liquidEnthalpy;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.liquidMoles;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PipeObservation;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.FlowSample;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.*;

/**
 * What a converged step reports and the ledger it is accepted on: the valves' cavitation and the movers' suction readings
 * as results ({@link #observe}: a {@link PipeObservation} per connection, a turbine's exhaust moisture among them), and
 * the step's conservation check ({@link #checkConservation}). Observational only: nothing here feeds back into the solve.
 * One per solver ({@code PassiveStepSolver.observations}), built with it; it reads the solve's constants and the solver's
 * suction reads through {@code solver} and holds no state of its own (S-01, batch 2026-11-13-codebase-review, D-S4: moved
 * out of the solver with every body unchanged but its qualifiers, and a Transport field read by its accessor in
 * {@link #observe} and {@link #exhaustMoisture}).
 */
final class StepObservations {
    /** The solver whose converged points this reads: its solve constants and its suction and phase reads. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    StepObservations(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /**
     * The condensed mass share of a steam turbine's own exhaust (code base review N4): the stream it draws ({@code carried},
     * its amounts and mass in one proportion), at its exhaust node's pressure and at its inlet enthalpy less the shaft work it
     * gave ({@code work}, J/kg, negative: {@link PassiveStepSolver#moverPower} over the mass flow), by one PH equilibrium of the fluid phases. What the exhaust node
     * holds does not enter: a turbine on a hotwell full of condensate reads its own steam. NaN where the equilibrium does
     * not answer (no read, no wear).
     */
    private double exhaustMoisture(StepEquations.Transport carried,double work,FluidThermodynamics.State exhaust,Runnable checkpoint) {
        double enthalpy=carried.mass()*(carried.specificEnthalpy()+work);
        try {
            var state=model.fluidPH(exhaust.temperature(),exhaust.pressure(),enthalpy,carried.moles(),checkpoint);
            // Droplets only: one dense fluid has none (S4b of batch 2026-11-15-supercritical-fluids).
            return state==null?Double.NaN:InletPhase.bulk(model,state).dropletMassShare();
        }catch(IllegalArgumentException unavailable){return Double.NaN;}
    }
    List<PipeObservation> observe(StepEquations e,List<FluidThermodynamics.State> states,double[] flows,double[] lawFlows,double[] heads,List<FlowControl.Mode> modes,Runnable checkpoint) {
        var samples=new ArrayList<PipeObservation>();
        for(int edge=0;edge<e.graph.pipes().size();edge++) {
            var pipe=e.graph.pipes().get(edge);int a=pipe.first(),b=pipe.second(),donor=flows[edge]>=0?a:b;
            var carried=e.drawn(edge,flows[edge]>=0?0:1,e.cachedTransport[donor],flows[edge]);
            var suction=e.drawn(edge,0,e.cachedTransport[a],flows[edge]);
            double column=pipe.control() instanceof FlowControl.Mover?suction.density():e.columnEnds[edge]==0?PortStreams.columnDensity(model,states.get(a),pipe.firstPort(),pipe.volume()):PortStreams.columnDensity(model,states.get(b),pipe.secondPort(),pipe.volume());
            double head=pipe.control() instanceof FlowControl.Mover?heads[edge]:-heads[edge];
            var lengths=new ArrayList<Double>();var losses=new ArrayList<Double>();
            for(var section:pipe.sections()){lengths.add(section.length());losses.add(PipeResistance.pressureDrop(section,lawFlows[edge],carried.density(),carried.viscosity()));}
            var observed=new PipeObservation(pipe.id(),PortStreams.columnEndPressure(model,e.graph.reservoirs().get(a),pipe.firstPort(),states.get(a),column)+head,PortStreams.columnEndPressure(model,e.graph.reservoirs().get(b),pipe.secondPort(),states.get(b),column),e.graph.reservoirs().get(a).elevation(),e.graph.reservoirs().get(b).elevation(),column,states.get(donor).temperature(),Math.abs(flows[edge])>1e-10,lengths,losses,flows[edge],e.drawn(edge,flows[edge]>=0?0:1,e.cachedTransport[donor],flows[edge]).density());
            if(!pipe.observationPoints().isEmpty()&&!solver.rateOnly){
                var meters=new ArrayList<FlowSample>();
                for(var point:pipe.observationPoints()){
                    boolean inlet=pipe.control() instanceof FlowControl.Valve&&point.fraction()==0;
                    double pressure=inlet?states.get(a).pressure():observed.pressure(point.fraction(),point.elevation(),point.reversed());
                    double q=flows[edge]*(point.reversed()?-1:1);Double volume=null;
                    if(q==0)volume=0.0;
                    else if(inlet)volume=q/carried.density();
                    else try{
                        checkpoint.run();var local=model.flashTP(states.get(donor).temperature(),pressure,carried.moles(),checkpoint);
                        volume=q/carried.mass()*(local.volume()+carried.solids().volume());
                    }catch(IllegalArgumentException unavailable){ /* The measurement is unavailable; the accepted process remains valid. */ }
                    meters.add(new FlowSample(point.host(),pressure,q,volume));
                }
                observed=observed.withFlowSamples(meters);
            }
            // A running liquid pump's suction: its gas ratio reads bubbles, none in one dense fluid (S5); its page's share and its gas
            // lock read the head share's vapour share, which follows the density (the page names it by the client's dense label).
            if(pipe.control() instanceof FlowControl.Mover mover&&mover.liquid()&&mover.targetVolumeFlow()>0&&solver.suctions.read(e.graph,edge))
                observed=observed.withSuction(npshRatio(e,edge,states,flows[edge]),dry(edge,mover,modes.get(edge),flows[edge],states.get(a).pressure()),gasRatio(edge,mover,states.get(a).pressure()),
                        npshRequired(edge,flows[edge]),solver.constants.vapourShares[edge]);
            // A running dry screw's suction vapour share (owner V6 B): its wear and liquid noise read its liquid share.
            else if(pipe.control() instanceof FlowControl.Machine screw&&com.wormzjl.icheme.science.machine.VacuumPump.dryScrew(screw.law())&&screw.targetVolumeFlow()>0&&solver.suctions.read(e.graph,edge))
                observed=observed.withSuction(Double.NaN,false,Double.NaN,Double.NaN,solver.constants.vapourShares[edge]);
            // A running centrifugal compressor's suction condensed mass share (N2): its wet gas wear reads it.
            else if(pipe.control() instanceof FlowControl.Machine compressor&&compressor.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalCompressor&&compressor.targetVolumeFlow()>0&&solver.suctions.read(e.graph,edge))
                observed=observed.withCondensed(solver.constants.condensedShares[edge]);
            // A steam turbine passing steam forward: its own exhaust moisture (N4), which its wet exhaust wear reads.
            else if(pipe.control() instanceof FlowControl.Turbine&&flows[edge]>0&&carried.mass()>0)
                observed=observed.withCondensed(exhaustMoisture(carried,solver.moverPower(e.graph,edge,flows[edge],heads[edge],suction.density(),states.get(a).pressure(),states.get(b).pressure())/flows[edge],states.get(b),checkpoint));
            if(pipe.control() instanceof FlowControl.PressureValve||pipe.control() instanceof FlowControl.ControlValve) {
                // The valve stands at the line's first end: its node side is that end's pressure, its line side the row's
                // first pressure, which includes the valve's drop (the control head).
                double node=PortStreams.columnEndPressure(model,e.graph.reservoirs().get(a),pipe.firstPort(),states.get(a),column),line=observed.firstPressure();
                var read=Math.abs(flows[edge])<=1e-10?PipeObservation.Cavitation.NONE:flows[edge]>=0?cavitation(states.get(donor),node,line,checkpoint):cavitation(states.get(donor),line,node,checkpoint);
                observed=observed.withValve(Math.abs(node-line),read);
            }
            samples.add(observed);
        }
        return List.copyOf(samples);
    }
    /** A control valve's incipient cavitation pressure ratio x_Fz (IEC 60534-8-4): it cavitates from ΔP = x_Fz (P1 − Pv). */
    static final double VALVE_INCIPIENT=.3;
    /**
     * A flowing valve's cavitation (owner 2026-11-10, option A: the liquid valve's loop by σ = (P1 − Pv) / (P1 − P2), Pv the
     * vapour pressure of the liquid it passes), from its donor node's state {@code s}, its inlet pressure {@code p1} and its
     * outlet pressure {@code p2}: FLASHING where p2 lies below Pv (the vapour stays), CAVITATING where σ ≤ 1 / x_Fz
     * ({@link #VALVE_INCIPIENT}: the vena contracta falls below Pv and the bubbles collapse downstream), else PLAIN. Water
     * reads its saturation pressure at the donor's temperature. A hydrocarbon liquid is throttled at its own enthalpy, as
     * {@link MoverSuction#releasePressure} reads it, but tested at two pressures instead of bisected: it flashes if it holds vapour at
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
     * ({@link MoverSuction#vapourPressure}), as a head of that liquid ({@code liquidDensities}), over this solve's NPSH3 at its suction
     * flow ({@link com.wormzjl.icheme.science.machine.Npsh#required}). NaN where it has no NPSH design (a set-flow
     * constructed pump), no floor or no liquid. Observational: never fed back into the solve.
     */
    double npshRatio(StepEquations e,int edge,List<FluidThermodynamics.State> states,double massFlow) {
        double rho=solver.constants.liquidDensities[edge],required=npshRequired(edge,massFlow);
        if(!(required>0))return Double.NaN;
        var pipe=e.graph.pipes().get(edge);int a=pipe.first();double floor;
        try{floor=solver.suctions.vapourPressure(e,edge,states.get(a));}catch(IllegalArgumentException outsideData){return Double.NaN;}
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
     * ({@link MoverSuction#readSuctions}), of bubbles ({@code bubbleShares}); NaN for a set-flow pump, which has no head factor,
     * no suction pressure, or one dense fluid, which holds no bubbles (S5). Observational.
     */
    double gasRatio(int edge,FlowControl.Mover mover,double suctionPressure) {
        if(!(mover instanceof FlowControl.Machine)||!(suctionPressure>0))return Double.NaN;
        return solver.constants.bubbleShares[edge]/com.wormzjl.icheme.science.machine.GasEntrainment.onset(suctionPressure);
    }
    /**
     * W10b (owner decision D34): whether a liquid pump whose driver is on runs dry at the accepted point: vapour-locked
     * ({@link MoverSuction#vapourLocks}), refused for a dry suction ({@code INLET_WRONG_PHASE}, decision D58), or gas-locked (a
     * centrifugal pump passing nothing with its suction's vapour share past the onset, {@link com.wormzjl.icheme.science.machine.GasEntrainment#onset}).
     */
    boolean dry(int edge,FlowControl.Mover mover,FlowControl.Mode mode,double massFlow,double suctionPressure) {
        if(solver.suctions.vapourLocked(edge)||mode==FlowControl.Mode.INLET_WRONG_PHASE)return true;
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
