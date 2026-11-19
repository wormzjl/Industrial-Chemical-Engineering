package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.forwardForbidden;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * The device active set of a step solve: each pump's and valve's mode at the solve's start ({@link #initialModes}, the
 * carry's tests {@link #shutoffMargin} and {@link #moverBounds}), the one statement of the transitions at a converged
 * point ({@link #nextMode}, with the band its tests read, {@link #shutoffBand}, and the head a closed mover must beat,
 * {@link #demand}), the series rule of a pressure valve beside a flow regulator ({@link #regulatesInSeries},
 * {@link #regulating}), and the changes a failed pass makes ({@link #cavitates}, {@link #openStarved}, {@link #openFailedRegulators}). One per solver
 * ({@code PassiveStepSolver.devices}), built with it; it reads the solve constants, the suction readings and the movers'
 * laws through {@code solver} and holds no state of its own: the modes are the solve's (S-01, batch
 * 2026-11-13-codebase-review, D-S5: moved out of the solver with every body unchanged but its qualifiers, and two
 * {@code StepEquations.Transport} density reads by the record's accessor).
 */
final class DeviceModes {
    /** The solver whose devices these are: its solve constants, its suction readings and its movers' laws. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    DeviceModes(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /**
     * The device mode the pass loop moves connection {@code i} to from {@code mode} at a converged point ({@code flow},
     * {@code head}, the node {@code states}, the first node's state {@code up} and what its first end draws, {@code suction});
     * {@code mode} itself when the point holds it. The one statement of the pump and valve transitions, read by the pass
     * loop and by {@link PassiveStepSolver#polishRefusedPoint}, which must accept a point only where the pass loop would.
     */
    /**
     * Owner decision of 2026-11-02 ("Lower target wins") where a pressure valve and a flow regulator carry one flow ({@link
     * ControlConstraints#series}; batch 2026-11-11-valve-followups, item 3): with both regulating, the pressure valve holding
     * its first node and the flow regulator the flow, their junction's pressure is in no row and the pass is singular. So
     * the lower flow wins one change at a time (decision A272): where connection {@code edge} would move to {@code next} and
     * regulate while a connection of the other kind in its series regulates, that one stands open instead. A pressure valve
     * then holds its node and the flow valve passes less than its target, or the flow valve holds its target and the
     * pressure valve stands wide open, its node rising above its set pressure. Returns the valve to open, {@code edge}
     * itself where that is a pressure valve beside a running set-flow mover (a mover cannot stand open), or -1.
     */
    int regulatesInSeries(PassiveNetwork graph,List<FlowControl.Mode> modes,int edge,FlowControl.Mode next) {
        int[] series=solver.constants.series;
        if(series==null||series.length!=modes.size()||series[edge]<0||!regulating(graph,edge,next)||regulating(graph,edge,modes.get(edge)))return -1;
        boolean pressure=ControlConstraints.pressureRegulator(graph,edge);
        for(int e=0;e<modes.size();e++) {
            if(e==edge||series[e]!=series[edge]||ControlConstraints.pressureRegulator(graph,e)==pressure||!regulating(graph,e,modes.get(e)))continue;
            return graph.pipes().get(e).control() instanceof FlowControl.Valve?e:pressure?edge:-1;
        }
        return -1;
    }
    /**
     * Option E1 of START_HOLD_REVIEW.md (batch 2026-11-16-review-plant-sizing), beside {@link #regulatesInSeries}: the pump
     * that returns to its law in the same change as flow valve {@code edge} goes to {@code next}, or -1. A pump's suction
     * limit, {@code max(E, pv + bd(Q))} ({@link MoverSuction#suctionLimit}), does not fall as its flow falls, and its suction
     * pressure does not fall either (its suction line's loss shrinks, and no other device changes mode in the same pass,
     * decision A272). So below the flow at which a pump sits on its suction limit its suction stands at or above its floor
     * and the pump is on its law. The pump is the one on {@code PUMP_SUCTION_LIMIT} that carries one flow with the valve by
     * the junction balances ({@link ControlConstraints#inSeries}), where the valve's change to {@code VALVE_REGULATING} is due
     * and its target, as a mass flow at {@code density}, lies below the pump's converged flow {@code flows}. A valve set to
     * 167 kg/s behind a crude pump on its limit at 216 kg/s otherwise regulated with the pump still on its limit, a pass with
     * no root (its limit 138 kPa, its suction line 149.85 kPa), and the valve opened, regulated and cycled.
     */
    static int lawBelowSuctionLimit(PassiveNetwork graph,List<FlowControl.Mode> modes,int edge,FlowControl.Mode next,double[] flows,double density) {
        if(next!=FlowControl.Mode.VALVE_REGULATING||!(graph.pipes().get(edge).control() instanceof FlowControl.Valve valve)||valve.pressureControl()||valve.bare())return -1;
        double target=valve.targetMassFlow(density);
        for(int e=0;e<modes.size();e++)
            if(modes.get(e)==FlowControl.Mode.PUMP_SUCTION_LIMIT&&target<flows[e]&&ControlConstraints.inSeries(graph,e,edge))return e;
        return -1;
    }
    /** Whether connection {@code e} in {@code mode} fixes what it regulates: a valve regulating, or a set-flow mover on its law (its displacement on its supply's density, {@link MoverSuction#riseDensity}) or closed (no flow). */
    static boolean regulating(PassiveNetwork graph,int e,FlowControl.Mode mode) {
        return graph.pipes().get(e).control() instanceof FlowControl.Valve?mode==FlowControl.Mode.VALVE_REGULATING:mode==FlowControl.Mode.CLOSED||mode==FlowControl.Mode.INLET_WRONG_PHASE;
    }
    FlowControl.Mode nextMode(StepEquations equations,int i,FlowControl.Mode mode,double flow,double head,List<FluidThermodynamics.State> states,
                                      FluidThermodynamics.State up,StepEquations.Transport suction,double tolerance) {
        var graph=equations.graph;var pipe=graph.pipes().get(i);double rho=suction.density();var next=mode;
        // Owner decision of 2026-11-10 (option A, batch 2026-11-10-custom-shader-system): a closed device never reopens toward
        // a flow its own end forbids, a discharge into a generator or a suction from a void: it can never deliver there, so it
        // stands dead-headed. Bundled random 575's pump into a 254 kPa gas generator reopened on its rise every step, stalled
        // its Newton on the law (its suction junction pulled to 75 kPa) and closed again, 4 to 8 passes a step, and the step
        // controller cut the steps to 6 ms until the island held on its wall deadline.
        if(mode==FlowControl.Mode.CLOSED&&forwardForbidden(graph,pipe))return FlowControl.Mode.CLOSED;
        if(pipe.control() instanceof FlowControl.Mover pump) {
            double band=shutoffBand(equations.pressureScales[i],tolerance);
            // A mover on its own law (batch 2026-11-03-rotating-machinery): it closes on a reverse drive (its check valve; the
            // curve itself reaches zero flow at its shutoff, owner decision D12), opens where its rise at
            // no flow beats the demand (a set-flow pump's rise on its supply, {@link MoverSuction#riseDensity}), goes on its suction limit
            // where its law holds its suction below its floor at its flow, the vapour pressure of the liquid it draws and its
            // breakdown NPSH (owner decisions D59 (4), D14 and D20 Q11 A, {@link MoverSuction#suctionLimit}), and
            // leaves it where its law cannot hold the point the suction line delivers.
            // Owner decision D16 A: a vapour-locked pump ({@link MoverSuction#vapourLocks}, one fact per solve on the start states) is
            // closed, whatever its mode and its rise, and never reopens in the solve; any other pump the converged point holds
            // below its floor goes on its suction limit, never closed for vapour lock. Read on the pass's point, D15's test
            // disagreed with itself: bundled random 575 and cold random 181 reopened a pump on its closed converged point
            // (+117 kPa and +400 Pa of suction head) and closed it again on the failed iterate its own law drained (the
            // supplier pulled to 7 to 73 kPa and 14 to 28 kPa), an active-set cycle (D15_HOLDS_REVIEW.md).
            if(pump.targetVolumeFlow()==0||solver.suctions.vapourLocked(i))next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.MOVER_LAW&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.CLOSED&&solver.riseLimit(graph,i,solver.suctions.riseDensity(i,rho),states.get(pipe.first()).pressure())-demand(graph,pipe,states,rho)>band)next=FlowControl.Mode.MOVER_LAW;
            else if(mode==FlowControl.Mode.MOVER_LAW&&pump.liquid()&&solver.suctions.cavitationMargin(equations,i,states,flow/rho)<-band)next=FlowControl.Mode.PUMP_SUCTION_LIMIT;
            else if(mode==FlowControl.Mode.PUMP_SUCTION_LIMIT&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.PUMP_SUCTION_LIMIT&&solver.lawResidual(graph,i,flow/rho,head,solver.suctions.riseDensity(i,rho),states.get(pipe.first()).pressure())>band)next=FlowControl.Mode.MOVER_LAW;
        }else if(pipe.control() instanceof FlowControl.Valve valve) {
            if(!Double.isNaN(equations.fixedOpenings[i]))return equations.fixedOpenings[i]==0?FlowControl.Mode.CLOSED:FlowControl.Mode.VALVE_CONFLICT;
            if(valve.bare())return FlowControl.Mode.VALVE_OPEN;
            if(!valve.pressureControl()) {
                double target=valve.targetMassFlow(rho),band=Math.max(1e-10,1e-8*target);boolean stands=solver.constants.standing.get(i);
                if(target==0&&!stands)return FlowControl.Mode.CLOSED;
                if(mode==FlowControl.Mode.VALVE_REGULATING) {
                    if(flow<-band)return FlowControl.Mode.CLOSED;
                    if(head<-.01||target>equations.capLimit(i,0,equations.cachedTransport[pipe.first()])*(1+1e-8))return FlowControl.Mode.VALVE_OPEN;
                } else if(mode==FlowControl.Mode.VALVE_OPEN) {
                    if(flow<-band)return FlowControl.Mode.CLOSED;
                    if(!stands&&flow>target+band)return FlowControl.Mode.VALVE_REGULATING;
                } else if(mode==FlowControl.Mode.CLOSED&&head>.01)return FlowControl.Mode.VALVE_OPEN;
                return next;
            }
            if(mode==FlowControl.Mode.VALVE_REGULATING&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.VALVE_REGULATING&&flow>equations.capLimit(i,0,equations.cachedTransport[pipe.first()])*(1+1e-8))next=FlowControl.Mode.VALVE_OPEN;
            else if(mode==FlowControl.Mode.VALVE_REGULATING&&head<-.01)next=FlowControl.Mode.VALVE_OPEN;
            else if(mode==FlowControl.Mode.VALVE_OPEN&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.VALVE_OPEN&&!graph.reservoirs().get(pipe.first()).fixed()&&up.pressure()<valve.targetPressure()-.01&&flow>1e-10)next=FlowControl.Mode.VALVE_REGULATING;
            else if(mode==FlowControl.Mode.CLOSED&&up.pressure()>valve.targetPressure()+.01&&head>.01)next=FlowControl.Mode.VALVE_REGULATING;
        }
        return next;
    }
    /**
     * Owner decision D59 (4) on a failed Newton: the first pump on its law whose suction the last iterate
     * holds below the vapour pressure ({@link MoverSuction#cavitationMargin}, by more than {@link #shutoffBand}) is put on its suction
     * limit; true if one was. The mode is part of the cycle key, so the sequence stays finite. Owner decision D16 A: the
     * iterate is never read for vapour lock, a point the pump's own law drained (575's and 181's failed iterates pulled the
     * supplier below its floor and closed a pump the closed point then reopened); a vapour-locked pump ({@link MoverSuction#vapourLocks})
     * is closed for the whole solve and never on its law here.
     */
    boolean cavitates(StepEquations equations,double[] last,List<FlowControl.Mode> modes,boolean[] boundaryClosed,double tolerance) {
        List<FluidThermodynamics.State> states=null;
        for(int i=0;i<modes.size();i++) {
            var mode=modes.get(i);
            if(boundaryClosed[i]||mode!=FlowControl.Mode.MOVER_LAW||!(equations.graph.pipes().get(i).control() instanceof FlowControl.Mover liquid&&liquid.liquid()))continue;
            if(states==null)try{states=equations.states(last);}catch(IllegalArgumentException outsideDomain){return false;}
            // The iterate's own suction volume flow, as the row reads it (owner decision D20 Q11 A, {@link MoverSuction#suctionLimit}).
            int a=equations.graph.pipes().get(i).first();double flow=equations.flow(i,last[equations.edgeOffset+i],equations.cachedTransport);
            if(solver.suctions.cavitationMargin(equations,i,states,flow/equations.drawn(i,0,equations.cachedTransport[a],flow).density())<-shutoffBand(equations.pressureScales[i],tolerance))
                {modes.set(i,FlowControl.Mode.PUMP_SUCTION_LIMIT);return true;}
        }
        return false;
    }
    /** How far the closed mover on connection {@code edge} out-rises, at no flow, what its ends ask on the step's start
     * states: its rise limit ({@link MoverSuction#riseDensity}) less the discharge end's pressure over the suction end's and the static
     * column between them (Pa). */
    double shutoffMargin(PassiveNetwork graph,int edge) {
        var pipe=graph.pipes().get(edge);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double suction=PortStreams.endDensity(model,a.state(),pipe.firstPort(),pipe.volume());
        return solver.riseLimit(graph,edge,solver.suctions.riseDensity(edge,suction),a.state().pressure())-(PortStreams.columnEndPressure(model,b,pipe.secondPort(),b.state(),suction)-PortStreams.columnEndPressure(model,a,pipe.firstPort(),a.state(),suction)
                +suction*GRAVITY*(b.elevation()-a.elevation()));
    }
    /** What each connection's series passes at most on its set-flow movers' laws ({@link ControlConstraints#moverBounds}):
     * a mover's set flow at its suction stream's density on {@code graph}'s states (kg/s). */
    double[] moverBounds(PassiveNetwork graph) {
        return ControlConstraints.moverBounds(graph,e->{var pipe=graph.pipes().get(e);
            return ((FlowControl.Mover)pipe.control()).targetVolumeFlow()*PortStreams.endDensity(model,graph.reservoirs().get(pipe.first()).state(),pipe.firstPort(),pipe.volume());});
    }
    /** Sets every regulating flow valve that {@link InletPhase#starved} finds without stock on {@code graph} (the solve's
     * input graph; {@code emptying} the vessels this pass runs dry, or null) to open; whether any was. */
    static boolean openStarved(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] emptying) {
        boolean changed=false;
        for(int edge=0;edge<modes.size();edge++)
            if(modes.get(edge)==FlowControl.Mode.VALVE_REGULATING&&graph.pipes().get(edge).control() instanceof FlowControl.Valve valve&&!valve.pressureControl()
                    &&InletPhase.starved(graph,edge,node->emptying!=null&&emptying[node])){modes.set(edge,FlowControl.Mode.VALVE_OPEN);changed=true;}
        return changed;
    }
    /**
     * A failed pass's last change (batch 2026-11-16-review-plant-sizing, VALVE_HOLD_TRACE.md, option B): every flow valve
     * regulating in it that this rule has not opened yet in this solve ({@code opened}, per connection, set here) is set to
     * open; whether any was. A flow valve asked for more than its line passes stands open and the line carries what it can,
     * but a pass that states it regulating has no root: a valve set to 60 kg/s on a pump's line that passes 51 drove the
     * pump's discharge to the property box's floor at every step size. The open pass that follows regulates again where it
     * passes more than the target, as after {@link #openStarved}, whose rule this is without its condition. Once per valve
     * per solve, so a valve that fails regulating after it was opened ends the solve.
     */
    static boolean openFailedRegulators(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] opened) {
        boolean changed=false;
        for(int edge=0;edge<modes.size();edge++)
            if(!opened[edge]&&modes.get(edge)==FlowControl.Mode.VALVE_REGULATING&&graph.pipes().get(edge).control() instanceof FlowControl.Valve valve&&!valve.pressureControl())
                {modes.set(edge,FlowControl.Mode.VALVE_OPEN);opened[edge]=true;changed=true;}
        return changed;
    }
    /**
     * The head this connection demands of its device at the point one active-set pass converged to: the discharge
     * pressure less the suction pressure, plus the static column between them. A closed mover reopens where its rise at no
     * flow exceeds it by more than {@link #shutoffBand}: in {@code CLOSED} the actuator row pins the flow to zero, so the
     * free head unknown is this demand and the margin is how much head the mover has in hand.
     */
    private double demand(PassiveNetwork graph,PassiveNetwork.Pipe pipe,List<FluidThermodynamics.State> states,double density) {
        return PortStreams.columnEndPressure(model,graph.reservoirs().get(pipe.second()),pipe.secondPort(),states.get(pipe.second()),density)
                -PortStreams.columnEndPressure(model,graph.reservoirs().get(pipe.first()),pipe.firstPort(),states.get(pipe.first()),density)
                +density*GRAVITY*(graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation());
    }
    /**
     * How much head margin this edge's own rows can resolve, and so the band around the shutoff corner inside which a
     * device's mode may not be decided by the sign of a flow. The pressure-dimensioned rows of an edge are stated in
     * {@link StepEquations#pressureScales}, so a solve converged to {@code tolerance} has decided them to
     * {@code tolerance*scale} Pa (1e-4 Pa on an atmospheric island, 6e-4 Pa at 6 bar), a flow resolution of order
     * 1e-5 kg/s. The floor of 0.01 Pa keeps the reopening test bit for bit what it was at or below 1e5 Pa (every island
     * of the regression reference). A mover's suction limit reads the same band, against its law's residual and its
     * cavitation margin.
     */
    static double shutoffBand(double pressureScale,double tolerance) {
        return Math.max(.01,tolerance*Math.max(1e5,pressureScale));
    }
    /** The initial device modes of a solve on {@code graph}'s states (the mint reads them too): the switch of {@link PassiveStepSolver#solve}.
     * A vapour-locked pump ({@link MoverSuction#vapourLocks}, owner decision D16 A) starts closed, as one set to no flow does. A flow
     * valve set to no flow starts closed too, unless it stands open behind a lower target ({@link ControlConstraints#standing}):
     * the fact the carry and a converged pass already state ({@link #nextMode}). Started open, it closed only after an open
     * pass converged, and a cold solve of the level loop's drum had none: the level valve, set to zero by its controller,
     * drew 790 kg/s down the outlet of a drum holding 7.9 L at every step size (batch 2026-11-11-aqueous-solvents,
     * FINAL_LANE_FIXES_REVIEW.md section 3). */
    List<FlowControl.Mode> initialModes(PassiveNetwork graph) {
        var modes=new ArrayList<FlowControl.Mode>();var stands=ControlConstraints.standing(graph);var heldOpenings=solver.constants.heldOpenings;
        for(var pipe:graph.pipes())modes.add(switch(pipe.control()) {
            case FlowControl.Passive ignored->FlowControl.Mode.PASSIVE;
            case FlowControl.Mover pump->pump.targetVolumeFlow()==0||solver.suctions.vapourLocked(modes.size())?FlowControl.Mode.CLOSED:FlowControl.Mode.MOVER_LAW;
            case FlowControl.Valve valve->heldOpenings.length==graph.pipes().size()&&!Double.isNaN(heldOpenings[modes.size()])?(heldOpenings[modes.size()]==0?FlowControl.Mode.CLOSED:FlowControl.Mode.VALVE_CONFLICT):!valve.bare()&&!valve.pressureControl()&&valve.targetMassFlow(1)==0&&!stands.get(modes.size())?FlowControl.Mode.CLOSED
                    :valve.bare()||!valve.pressureControl()||graph.reservoirs().get(pipe.first()).state().pressure()>valve.targetPressure()+.01
                    ?FlowControl.Mode.VALVE_OPEN:FlowControl.Mode.CLOSED;
        });
        return modes;
    }
}
