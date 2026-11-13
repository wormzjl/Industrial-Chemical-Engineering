package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;

/**
 * What a connection's end at a node's port draws and stands at, as functions of the island's model, the node, its state,
 * the port and the connection's volume: the end's pressure and level head, the densities of what it draws and of the
 * column it stands in the connection, and the stream it draws (amounts, mass, specific enthalpy, solids, as a state).
 * Each reads only its arguments and calls the model in the order the step solver always did: the step solver
 * ({@link PassiveStepSolver}), the network reduction ({@link NetworkReduction}), the inlet phase rule ({@link InletPhase}) and
 * the runtime's port pressures call the same functions (batch 2026-11-13-codebase-review, S-07).
 */
public final class PortStreams {
    private PortStreams() {}
    /**
     * The pressure a pipe end at {@code port} of {@code node} stands at: the node's pressure and its port's level head. A
     * spray inlet's end is its riser's foot: the atmosphere at the nozzle and the column arriving in the line
     * ({@link #columnEndPressure}); a reader of one end alone, a supplier or a suction end, never stands at a spray inlet,
     * and there the riser is weighed with the tower's own liquid.
     */
    static double endPressure(FluidThermodynamics model,PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state) {
        if(spray(node,port))return sprayPressure(node,state.condensedVolume()>0?model.liquidDensity(state):state.mass()/state.volume());
        return state.pressure()+portHead(model,node,port,state);
    }
    /** {@link #endPressure} of a line's end, a spray inlet's riser at the column {@code arriving} sends in from the line's other end. */
    static double endPressure(FluidThermodynamics model,PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state,java.util.function.DoubleSupplier arriving) {
        return spray(node,port)?sprayPressure(node,arriving.getAsDouble()):state.pressure()+portHead(model,node,port,state);
    }
    /** A spray inlet discharges to the atmosphere at its nozzle; a tower's own end is no other kind of port. */
    static boolean spray(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port){return port==PassiveNetwork.PhasePort.SPRAY&&node.inventory().tower()!=null;}
    private static double sprayPressure(PassiveNetwork.Reservoir node,double column){return CoolingTower.PRESSURE+column*PassiveStepSolver.GRAVITY*node.inventory().tower().distributorHeight();}
    /** {@link #endPressure} with a spray end at the line's column {@code column}, so a basin temperature cannot create an inlet head. */
    static double columnEndPressure(FluidThermodynamics model,PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state,double column) {
        return endPressure(model,node,port,state,()->column);
    }
    /** {@link #endPressure} of the end of {@code pipe} at {@code node}, on the nodes' own states in {@code graph}. */
    static double endPressure(FluidThermodynamics model,PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node) {
        var reservoir=graph.reservoirs().get(node);int other=pipe.first()==node?pipe.second():pipe.first();
        return endPressure(model,reservoir,pipe.portAt(node),reservoir.state(),()->columnDensity(model,graph.reservoirs().get(other).state(),pipe.portAt(other),pipe.volume()));
    }
    /**
     * The static head of a port over its node's pressure: the level head of a bottom outlet (decision D9, option B), zero
     * everywhere else. At a LIQUID port of a vessel (RESERVOIR, or its rate-solve copy PORT) the node's pressure is the
     * headspace's, and the bottom stands below the weight of the condensed phases over the footprint {@code A = V/H}:
     * {@code h = g * m_c * H / V}, {@code m_c} the condensed mass of {@code state} ({@link FluidThermodynamics#liquidMass}),
     * {@code H} the vessel's level height and {@code V} its inventory volume (both its identity,
     * {@link PassiveNetwork.Inventory}, not the trial state's EOS volume). Linear in the decoded condensed
     * amounts, evaluated by every caller on the state it is stating a pressure for (so the block Jacobian carries its
     * derivative with no new structural entry); a pressure, so inflow through a bottom port works against it; not in the
     * port's capacities nor the step controller. A VAPOR or BULK port, any other node kind and a state with no condensed
     * mass get exactly {@code 0.0}, so gas-only and all-BULK islands are the doubles they were. An OVERFLOW port
     * ({@link OpenVessel}) states the LIQUID port's head: its nozzle stands at the fill height, and the line below it full of
     * the same liquid adds back the head the nozzle stands above the bottom.
     */
    public static double portHead(FluidThermodynamics model,PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state) {
        if(port!=PassiveNetwork.PhasePort.LIQUID&&port!=PassiveNetwork.PhasePort.OVERFLOW&&port!=PassiveNetwork.PhasePort.BOOT_WATER&&port!=PassiveNetwork.PhasePort.BOOT_HC||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR&&node.kind()!=PassiveNetwork.NodeKind.PORT)return 0;
        double condensed=model.liquidMass(state);
        if(condensed<=0)return 0;
        var geometry=node.inventory().geometry();
        if(geometry.form()==VesselGeometry.Form.PRISM)return PassiveStepSolver.GRAVITY*condensed*node.inventory().levelHeight()/node.inventory().volume();
        int lower=model.heavierLiquid(state),upper=lower==FluidThermodynamics.AQUEOUS?FluidThermodynamics.OIL:FluidThermodynamics.AQUEOUS;
        // Each layer weighs its liquid and the solids it carries (its volume share, as phaseVolume counts them), as the
        // prismatic law's liquidMass does; a vessel holding solids and no liquid stands them as one bed.
        var solids=state.solidMoments();double lowerVolume=FluidThermodynamics.phaseVolume(state,lower),upperVolume=FluidThermodynamics.phaseVolume(state,upper);
        if(lowerVolume+upperVolume==0)return geometry.head(solids.volume(),solids.mass(),0,0);
        return geometry.head(lowerVolume,model.phaseMass(state,lower)+FluidThermodynamics.phaseSolidShare(state,lower)*solids.mass(),
                upperVolume,model.phaseMass(state,upper)+FluidThermodynamics.phaseSolidShare(state,upper)*solids.mass());
    }
    /**
     * The density of what leaves a node through {@code port} at a vanishing flow: the bulk {@code mass/volume} at a BULK
     * end, and at a phase port the stream of the first phase of its priority order the state holds
     * ({@link PhaseDraw#leading}, decision D11); the bulk's when the state holds none of the three. The draw reads an
     * end's density here (a start flow's loss and cap, a mover's suction, rise limit and column); what stands in the
     * connection is {@link #columnDensity}. {@code volume} is the connection's (m3). It is read only at a LIQUID end
     * holding both liquids, whose stream is {@link PhaseDraw#mixedShares} of it; every end function takes it for that.
     */
    static double endDensity(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port)){var shares=PhaseDraw.mixedShares(s,volume);
            return shares[FluidThermodynamics.OIL]*phaseDensity(model,s,FluidThermodynamics.OIL)+shares[FluidThermodynamics.AQUEOUS]*phaseDensity(model,s,FluidThermodynamics.AQUEOUS);}
        int phase=PhaseDraw.leading(model,s,port);
        return phase<0?s.mass()/s.volume():phaseDensity(model,s,phase);
    }
    /** The density of one phase stream of {@code s}: the vapour's ({@link FluidThermodynamics#vaporDensity}) or a liquid's
     * with its solid share ({@link FluidThermodynamics#phaseMass} over {@link FluidThermodynamics#phaseVolume}). */
    static double phaseDensity(FluidThermodynamics model,FluidThermodynamics.State s,int phase) {
        return phase==FluidThermodynamics.GAS?model.vaporDensity(s):model.phaseMass(s,phase)/FluidThermodynamics.phaseVolume(s,phase);
    }
    /**
     * Owner decision D34: the density of the static column a node's end {@code port} stands in a connection of its own
     * {@code volume} (m3; a contracted run's summed): the connection filled with the port's phases in priority order by
     * volume ({@link PhaseDraw#fill}), each up to what the state holds of it, the last phase it holds taking the rest.
     * Every static column reads an end's density here (the hydraulic row and the pass's column choice, the start drives,
     * the sign tests and the rest gauge's drives); a mover's column is its suction stream instead (decision D6). A phase
     * that fills the connection alone gives its own density to the bit; a BULK end, and a state holding none of the
     * port's phases, the bulk's; in between the column is continuous in the phase volumes. Read from the leading phase
     * instead, 1.83e-8 m3 of condensate (880 kg/m3) at a bottom port over a 2 m drop of 2 L line stood as a 17.3 kPa
     * column no step could drain; as the line's gas plus the condensate's share it is 44.5 Pa. Read on the trial state,
     * so the Jacobian differentiates it.
     */
    static double columnDensity(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(port==PassiveNetwork.PhasePort.BULK)return endDensity(model,s,port,volume);
        if(PhaseDraw.mixedLiquids(s,port)){var filled=PhaseDraw.fillMixed(s,volume,1);double mass=0;for(int phase=0;phase<3;phase++)if(filled[phase]>0)mass+=phaseDensity(model,s,phase)*filled[phase];return mass/volume;}
        int leading=PhaseDraw.leading(model,s,port);
        if(leading<0)return s.mass()/s.volume();
        if(!(FluidThermodynamics.phaseVolume(s,leading)<volume))return phaseDensity(model,s,leading);
        int[] order=PhaseDraw.order(model,s,port);var filled=PhaseDraw.fill(s,order,volume,1);
        double mass=0;
        for(int phase:order) {
            if(!(filled[phase]>0))continue;
            if(filled[phase]==volume)return phaseDensity(model,s,phase);
            mass+=phaseDensity(model,s,phase)*filled[phase];
        }
        return mass/volume;
    }
    /** {@link #columnDensity} of a contracted run's end: the run {@code chain}'s links' volumes summed. */
    static double runColumnDensity(FluidThermodynamics model,PassiveNetwork graph,List<Integer> chain,FluidThermodynamics.State s,PassiveNetwork.PhasePort port) {
        double volume=0;for(int link:chain)volume+=graph.pipes().get(link).volume();
        return columnDensity(model,s,port,volume);
    }
    /** The velocity limit of what leaves a node through {@code port} at a vanishing flow ({@link #endDensity}'s stream);
     * the bulk's at a BULK end and when the state holds none of the phases. */
    static double endVelocityLimit(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port) {
        if(PhaseDraw.mixedLiquids(s,port))return Math.min(model.phaseVelocityLimit(s,FluidThermodynamics.OIL),model.phaseVelocityLimit(s,FluidThermodynamics.AQUEOUS));
        int phase=PhaseDraw.leading(model,s,port);
        return phase<0?model.velocityLimit(s):model.phaseVelocityLimit(s,phase);
    }
    /** The viscosity of a {@link PhaseDraw#mixedLiquids} end's stream: its liquids' own, weighted by their
     * {@link PhaseDraw#mixedShares} of a connection of {@code volume}. */
    static double mixedViscosity(FluidThermodynamics model,FluidThermodynamics.State s,double volume,com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace scratch) {
        var shares=PhaseDraw.mixedShares(s,volume);
        return shares[FluidThermodynamics.OIL]*model.phaseViscosity(s,FluidThermodynamics.OIL,scratch,null)+shares[FluidThermodynamics.AQUEOUS]*model.phaseViscosity(s,FluidThermodynamics.AQUEOUS,scratch,null);
    }
    /** The solid mass share of what leaves a node through {@code port} at a vanishing flow: none in the gas, a liquid's share
     * of the solids over its stream's mass, the bulk share at a BULK end and when the state holds none of the phases. A
     * LIQUID end holding both liquids carries each one's solids in its {@link PhaseDraw#mixedShares} of the stream. */
    static double solidShare(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port)){
            var shares=PhaseDraw.mixedShares(s,volume);double solids=0,fluid=0;
            for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}){double held=FluidThermodynamics.phaseVolume(s,phase);
                solids+=shares[phase]*FluidThermodynamics.phaseSolidShare(s,phase)*s.solidMoments().mass()/held;fluid+=shares[phase]*model.phaseMass(s,phase)/held;}
            return solids/fluid;
        }
        int phase=PhaseDraw.leading(model,s,port);
        if(phase<0)return s.solidMoments().mass()/s.mass();
        return phase==FluidThermodynamics.GAS?0:FluidThermodynamics.phaseSolidShare(s,phase)*s.solidMoments().mass()/model.phaseMass(s,phase);
    }
    /** The mass flow of a suction volume flow of what leaves through {@code port}, {@code Q m / V}, multiplied and divided
     * in the order the bulk start flows always were ({@code Q*mass/volume}, not {@code Q*(mass/volume)}). */
    static double suctionMassFlow(FluidThermodynamics model,double volumeFlow,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return volumeFlow*endDensity(model,s,port,volume);
        int phase=PhaseDraw.leading(model,s,port);
        if(phase<0)return volumeFlow*s.mass()/s.volume();
        return volumeFlow*model.phaseMass(s,phase)/FluidThermodynamics.phaseVolume(s,phase);
    }
    /** The conserved amounts (water last) of what leaves through {@code port} at a vanishing flow; the node's total amounts
     * at a BULK end and when the state holds none of the phases. Over {@link #endMass} they are the moles per kilogram of
     * the fluid the connection carries: the solids a liquid or bulk end also draws are left behind, as {@link #endStream}
     * leaves them (LNG drawn with 1.3 % of dry ice at its stored energy flashed 1.3 % of its methane to vapour). */
    static double[] endMoles(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return PhaseLayout.totalAmounts(mixedLiquidStream(model,s,volume));
        return fluidMoles(s,PhaseDraw.leading(model,s,port));
    }
    /** The fluid mass of {@link #endMoles}' stream, kg: the phase's (or the node's) mass without its solids; a dry end, which
     * draws no fluid ({@link #endStream} is null there), keeps its whole mass. */
    static double endMass(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return mixedLiquidStream(model,s,volume).mass();
        return fluidMass(model,s,PhaseDraw.leading(model,s,port));
    }
    /** The fluid's specific enthalpy of {@link #endMoles}' stream, J/kg, its solids left behind (see {@link #endMoles}). */
    static double endSpecificEnthalpy(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port)){var mixed=mixedLiquidStream(model,s,volume);return mixed.enthalpy()/mixed.mass();}
        return fluidSpecificEnthalpy(model,s,PhaseDraw.leading(model,s,port));
    }
    /**
     * What a phase-port outflow the pass drew carries per kilogram of fluid ({@code rates}, the six entries of
     * {@link PassiveStepSolver.Equations#draws}): each drawn phase's {@link #fluidMoles} over its {@link #fluidMass}, and its
     * {@link #fluidSpecificEnthalpy}, weighted by its mass flow and read where the booking reads it, on {@code end} or, for a
     * pinned entry, on the step's start state {@code start}; the specific enthalpy (J/kg) is the last entry. A full tank
     * holding a 1.2e-22 m3 nitrogen bubble draws water through its top port, though the bubble leads the port at a vanishing
     * flow: a dry tank above it laid out from the leading phase held 77 g of overflowing water as 0.081 m3 of vapour, and
     * the next pass ran its temperature to the 10 K floor (bundled fuzz seed 301).
     */
    static double[] drawnStream(FluidThermodynamics model,double[] rates,FluidThermodynamics.State end,FluidThermodynamics.State start) {
        double[] stream=null;double total=0;
        for(double rate:rates)if(rate>0)total+=rate;
        for(int k=0;k<rates.length;k++) {
            if(!(rates[k]>0))continue;
            var at=k<3?end:start;int phase=k%3;double weight=rates[k]/total,fluid=fluidMass(model,at,phase);double[] moles=fluidMoles(at,phase);
            if(stream==null)stream=new double[moles.length+1];
            for(int c=0;c<moles.length;c++)stream[c]+=weight*moles[c]/fluid;
            stream[moles.length]+=weight*fluidSpecificEnthalpy(model,at,phase);
        }
        return stream;
    }
    /** The conserved amounts of phase {@code phase} of {@code s} (water last); the node's total amounts for {@code -1}. */
    private static double[] fluidMoles(FluidThermodynamics.State s,int phase){return phase<0?PhaseLayout.totalAmounts(s):FluidThermodynamics.phaseMoles(s,phase);}
    /** The mass of phase {@code phase} of {@code s} (the node's for {@code -1}) without its solids, kg; its whole mass where
     * the solids would leave none. */
    private static double fluidMass(FluidThermodynamics model,FluidThermodynamics.State s,int phase) {
        double mass=phase<0?s.mass():model.phaseMass(s,phase),solids=s.solidMoments().mass();
        if(solids==0)return mass;
        double fluid=mass-solidShare(s,phase)*solids;
        return fluid>0?fluid:mass;
    }
    /** The specific enthalpy of phase {@code phase} of {@code s} (the node's for {@code -1}) without its solids, J/kg. */
    private static double fluidSpecificEnthalpy(FluidThermodynamics model,FluidThermodynamics.State s,int phase) {
        double whole=phase<0?s.enthalpy()/s.mass():model.phaseSpecificEnthalpy(s,phase,null),solids=s.solidMoments().mass();
        if(solids==0)return whole;
        double mass=phase<0?s.mass():model.phaseMass(s,phase),fluid=mass-solidShare(s,phase)*solids;
        if(!(fluid>0))return whole;
        return (whole*mass-solidShare(s,phase)*s.solidMoments().enthalpy(s.temperature(),s.pressure()))/fluid;
    }
    /** The share of the node's solids a stream of {@code phase} draws: all of them at a BULK end ({@code phase < 0}). */
    private static double solidShare(FluidThermodynamics.State s,int phase){return phase<0?1:FluidThermodynamics.phaseSolidShare(s,phase);}
    /**
     * The fluid of {@link #endMoles}' stream as a state: the phase amounts {@code port} draws from {@code s} at a vanishing
     * flow (all of them at a BULK end and when the state holds none of the phases), per kilogram of fluid, at the node's
     * own temperature and pressure, its solids left behind. Built from the node's own amounts ({@link FluidThermodynamics#state}):
     * no flash, so nothing a flash's window or answer could refuse. Null when the stream carries no fluid (dry solids).
     */
    static FluidThermodynamics.State endStream(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return mixedLiquidStream(model,s,volume);
        int phase=PhaseDraw.leading(model,s,port),n=s.componentCount();
        double[][] out=new double[3][n];double mass=0;
        for(int slot=0;slot<3;slot++) {
            // A BULK end draws every slot; a phase end the slot holding its phase.
            if(phase>=0&&s.slot(phase)!=slot)continue;
            double[] held=s.amountsView(slot);
            for(int c=0;c<n;c++){out[slot][c]=held[c];mass+=held[c]*model.molecularWeight(c);}
        }
        if(!(mass>0))return null;
        for(double[] row:out)for(int c=0;c<n;c++)row[c]/=mass;
        return model.adoptingState(s.temperature(),s.pressure(),out[0],out[1],out[2],null);
    }
    /** {@link #endStream} of a {@link PhaseDraw#mixedLiquids} end: each liquid in its {@link PhaseDraw#mixedShares} of a
     * connection of {@code volume}, per kilogram of fluid, its solids left behind. */
    private static FluidThermodynamics.State mixedLiquidStream(FluidThermodynamics model,FluidThermodynamics.State s,double volume){
        var shares=PhaseDraw.mixedShares(s,volume);
        double oil=shares[FluidThermodynamics.OIL]/FluidThermodynamics.phaseVolume(s,FluidThermodynamics.OIL),water=shares[FluidThermodynamics.AQUEOUS]/FluidThermodynamics.phaseVolume(s,FluidThermodynamics.AQUEOUS);
        int n=s.componentCount();double[][] out=new double[3][n];double mass=0;
        for(int label:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}) {
            int slot=s.slot(label);if(slot<0)continue;double share=label==FluidThermodynamics.OIL?oil:water;
            double[] held=s.amountsView(slot);
            for(int c=0;c<n;c++){out[slot][c]=held[c]*share;mass+=out[slot][c]*model.molecularWeight(c);}
        }
        for(double[] row:out)for(int c=0;c<n;c++)row[c]/=mass;
        return model.adoptingState(s.temperature(),s.pressure(),out[0],out[1],out[2],null);
    }
}
