package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hold F8 of the phase-ports batch (2026-09-27): in the GUI-layout check's first dev-client run a line was placed block by
 * block at z = 40 (tools/phase-ports-probes/gui-layout/line.txt, client log logs/03): a water generator, a junction pipe
 * with a branch pipe to a void, a pump facing east, a pipe, an inline filter facing east, a pipe, a pressure control valve
 * facing east, a pipe and a void. 60 ms after the filter was placed the island logged {@code fluid_island=46
 * committed_tick=11283 status=HELD: Singular Newton Jacobian: Sparse LU rejected a singular matrix; active-set pass=0}, and
 * it stayed held at that tick for the rest of the run (9 lines; the later placements queued behind it). The pump
 * discharged at its target flow into the filter's two junctions, which nothing drained: a sealed pocket with a prescribed
 * inflow has no root (PassiveStepSolver.relieveSealedPockets). The same start held every pump discharging into a pressure
 * control valve, which starts closed.
 *
 * <p>Replayed on the world's event path: the real {@link PhysicalRegistry} and {@link IslandCoordinator} (a synchronous
 * worker), every block placed as {@code FluidWorldAuthority.place} places it (its default control and spec) at the online
 * tick the client log gives it (the filter at 11283, the others from the log's wall-clock stamps at 20 TPS), and the world
 * run tick by tick as {@code FluidWorldAuthority.tick} runs it. On the tree the hold was found on the run reproduces the
 * client log's nine held lines at committed tick 11283. Every island must commit to the end without a hold.
 */
class InlineFilterLineHoldTest {
    static final int Y=-59,Z=40;
    final MaterialCatalog catalog=MaterialCatalog.bundled();

    /** The client log's line in its order and at its ticks (the filter at 11283, the committed tick of every held line):
     * tick, x, kind, facing, z offset. */
    static final Object[][] LINE={
            {11058,0,Kind.GENERATOR,PhysicalFluidTopology.Direction.EAST,0},
            {11093,1,Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,0},
            {11127,1,Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,1},
            {11162,1,Kind.VOID,PhysicalFluidTopology.Direction.NORTH,2},
            {11204,2,Kind.PUMP,PhysicalFluidTopology.Direction.EAST,0},
            {11239,3,Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,0},
            {11283,4,Kind.FILTER,PhysicalFluidTopology.Direction.EAST,0},
            {11317,5,Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,0},
            {11368,6,Kind.VALVE,PhysicalFluidTopology.Direction.EAST,0},
            {11402,7,Kind.PIPE,PhysicalFluidTopology.Direction.NORTH,0},
            {11436,8,Kind.VOID,PhysicalFluidTopology.Direction.NORTH,0}};

    /** Places the first {@code blocks} of the line at their ticks and runs {@code after} ticks past the last one. */
    void run(EventPathWorld w,int blocks,long after) {
        long end=0;
        for(int i=0;i<blocks;i++) {
            var b=LINE[i];long tick=((Integer)b[0]).longValue();w.runTo(tick);
            w.place((Integer)b[1],Y,Z+(Integer)b[4],(Kind)b[2],(PhysicalFluidTopology.Direction)b[3]);end=tick;
        }
        w.runTo(end+after);
    }

    /** The whole line, in the client's order: no hold, and the pump runs on its law through the regulating valve. */
    @Test void theInGameLineNeverHolds() {
        var w=new EventPathWorld();run(w,LINE.length,2400);w.assertKeptUp("in-game line");
        var island=w.coordinator.snapshots().stream().filter(s->s.graph().pipes().size()>=6).findFirst().orElseThrow();
        var modes=island.lastResult().orElseThrow().endpointModes();
        System.out.println("F8_END "+w.describe(island.id())+" modes="+modes);
        assertTrue(modes.contains(FlowControl.Mode.MOVER_LAW)&&modes.contains(FlowControl.Mode.VALVE_REGULATING),"the pump delivers through the valve: "+modes);
    }
    /** Every intermediate dead end the line passes through (the pump into one pipe, into the filter alone, into the
     * filter and a pipe, into the valve with nothing past it), each run for two minutes on its own. */
    @Test void everyPrefixOfTheLineNeverHolds() {
        for(int blocks=1;blocks<=LINE.length;blocks++){var w=new EventPathWorld();run(w,blocks,2400);w.assertKeptUp(blocks+" blocks");}
    }
    /**
     * Straight lines along +x, every block placed in one tick with the world's defaults and run 3000 ticks. Letters: G
     * generator, P pipe, M pump (east), F filter (east), V valve (east), O void, T tank. On the tree the hold was found on,
     * every line with a pump discharging into a valve or into a filter with nothing past it held at its first slice
     * (GPMPVPO, GPMPF, GPMPV, GPMPVP, GPMPFPVPO, GPMPFP, GPMPVPT, GPMPFPVPT).
     */
    @Test void pumpLinesIntoValvesAndFiltersNeverHold() {
        var ends=new LinkedHashMap<String,List<FlowControl.Mode>>();var pumpOwners=new LinkedHashMap<String,Long>();
        for(String layout:List.of("GPMPVPO","GPMPFPO","GPMPF","GPMPV","GPMPVP","GPMPFPVPO","GPMPFP","GPMP","GPMPPO","GPMPFPT","GPMPVPT","GPMPFPVPT")) {
            var w=new EventPathWorld();w.runTo(100);long pump=0;
            for(int i=0;i<layout.length();i++) {
                var kind=switch(layout.charAt(i)){case 'G'->Kind.GENERATOR;case 'P'->Kind.PIPE;case 'M'->Kind.PUMP;case 'F'->Kind.FILTER;case 'V'->Kind.VALVE;case 'O'->Kind.VOID;case 'T'->Kind.RESERVOIR;default->throw new IllegalArgumentException(layout);};
                long id=w.place(i,0,0,kind,kind==Kind.PUMP||kind==Kind.FILTER||kind==Kind.VALVE?PhysicalFluidTopology.Direction.EAST:PhysicalFluidTopology.Direction.NORTH);
                if(kind==Kind.PUMP)pump=id;
            }
            w.runTo(3100);w.assertKeptUp(layout);
            var island=w.coordinator.snapshots().stream().max(Comparator.comparingInt(s->s.graph().pipes().size())).orElseThrow();
            ends.put(layout,island.lastResult().map(PassiveIntervalSolver.Result::endpointModes).orElse(List.of()));pumpOwners.put(layout,w.registry.owner(pump));
        }
        ends.forEach((layout,modes)->System.out.println("F8_LAYOUT "+layout+" "+modes));
        // A pump that can deliver runs on its law: through the valve regulating at its set point, or through the filter.
        for(String layout:List.of("GPMPVPO","GPMPFPVPO"))assertTrue(ends.get(layout).containsAll(List.of(FlowControl.Mode.MOVER_LAW,FlowControl.Mode.VALVE_REGULATING)),layout+": "+ends.get(layout));
        assertTrue(ends.get("GPMPFPO").contains(FlowControl.Mode.MOVER_LAW),"GPMPFPO: "+ends.get("GPMPFPO"));
        // A pump discharging into a dead end carries nothing: owner decision D18 (2026-09-28) leaves the whole dead-end branch,
        // the pump included, out of the solve (it stood at its shutoff, closed, while it was solved; restated for the owner
        // to confirm, PHASE_PORTS_REVIEW.md "Boundary split, dead ends and rest").
        for(String layout:List.of("GPMPF","GPMPFP","GPMPV","GPMPVP","GPMP"))assertNull(pumpOwners.get(layout),layout+": the pump is in no island");
    }
    /**
     * The same pocket entered from the other side (hold search seed 61, tools/phase-ports-probes/hold-fuzz): a nitrogen
     * generator at 406 kPa, placed first, so every junction is minted with its gas; a pump on its gas supply (refused, closed, until request 11; now on its law)
     * discharging into an inline filter; and a water generator at 215 kPa feeding the filter's outlet through a 0.01 m pipe.
     * The filter's two junctions form a pocket whose only way in or out is that pipe, and at the minted 406 kPa its row
     * started on the velocity cap's branch, which has no pressure column: the rate pass at every interval's start was
     * singular. The pocket forces that pipe's flow to zero, so the pipe is a dead end for the cap, like a single junction's.
     */
    @Test void aFilterPocketFedThroughASmallPipeNeverHolds() {
        var w=new EventPathWorld();w.runTo(100);var east=PhysicalFluidTopology.Direction.EAST;var north=PhysicalFluidTopology.Direction.NORTH;
        double[] n2=new double[FluidDeviceSpec.nitrogen(catalog).composition().length];n2[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        double[] water=FluidDeviceSpec.water(catalog).composition();
        w.place(0,0,0,Kind.GENERATOR,north,new FluidDeviceSpec(1,272.66,405737.6,n2),.05);
        w.place(1,0,0,Kind.PIPE,north,FluidDeviceSpec.nitrogen(catalog),.05);
        w.place(2,0,0,Kind.PUMP,east,FluidDeviceSpec.nitrogen(catalog),.05);
        w.place(3,0,0,Kind.PIPE,north,FluidDeviceSpec.nitrogen(catalog),.01);
        w.place(4,0,0,Kind.FILTER,east,FluidDeviceSpec.nitrogen(catalog),.01);
        w.place(5,0,0,Kind.PIPE,north,FluidDeviceSpec.nitrogen(catalog),.01);
        w.place(6,0,0,Kind.GENERATOR,north,new FluidDeviceSpec(1,282.45,215395.3,water),.05);
        w.runTo(3100);w.assertKeptUp("filter pocket fed through a small pipe");
    }
}
