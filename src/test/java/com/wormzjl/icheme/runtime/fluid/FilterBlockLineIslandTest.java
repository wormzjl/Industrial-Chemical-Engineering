package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The block lines a player actually builds around an inline filter, compiled through the real
 * {@link PhysicalFluidTopology} and integrated by the same interval solver the island worker runs.
 *
 * <p>Every fixture that models a filter elsewhere joins a generator straight to a void with a
 * filter pipe, or hangs one junction off it. A placed filter block is nothing of the kind: it is
 * two zero-holdup junctions around a {@link PhysicalFluidTopology#filterIdentity} edge, with the
 * world's own device geometry, the world's nitrogen-charged reservoir and the capacity and clean
 * resistance the configuration supplies. These build exactly that.
 *
 * <p>They also run it the way the world runs it: consecutive five-second intervals on one retained
 * solver, starting from {@link com.wormzjl.icheme.fluid.support.FluidTestSupport#COLD_START_SECONDS}. Both defects these cover need
 * more than one interval or a settled island to appear at all, which is why a single-interval
 * fixture never saw them.
 */
class FilterBlockLineIslandTest extends BlockLineHarness {

    /** generator - pipe - filter - pipe - reservoir, the line that is HELD from creation. */
    private PassiveNetwork reservoirLine(double generatorPressure,double solidFraction) {
        var devices=List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),device(2,1,TopologyCompiler.Kind.PIPE),
                device(3,2,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.EAST),
                device(4,3,TopologyCompiler.Kind.PIPE),device(5,4,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure,solidFraction),5L,tankSpec(101325)));
    }
    /** generator - pipe - pump - pipe - filter - pipe - reservoir (a pipe block before the pump since only pipes connect, decision D20). */
    private PassiveNetwork pumpLine(double generatorPressure,double solidFraction) {
        var devices=List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),
                device(7,1,TopologyCompiler.Kind.PIPE),device(2,2,TopologyCompiler.Kind.PUMP,PhysicalFluidTopology.Direction.EAST),
                device(3,3,TopologyCompiler.Kind.PIPE),
                device(4,4,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.EAST),
                device(5,5,TopologyCompiler.Kind.PIPE),device(6,6,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure,solidFraction),6L,tankSpec(101325)));
    }
    /** generator - pipe - filter - pipe - void, the line that stayed healthy without solids. */
    private PassiveNetwork voidLine(double generatorPressure,double solidFraction) {
        var devices=List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),device(2,1,TopologyCompiler.Kind.PIPE),
                device(3,2,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.EAST),
                device(4,3,TopologyCompiler.Kind.PIPE),device(5,4,TopologyCompiler.Kind.VOID));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure,solidFraction),5L,tankSpec(101325)));
    }
    /** generator - pipe - pressure valve - two pipes - reservoir: a tank behind a device junction
     * with no filter in it, and the shape {@code documentation/PUMP_SHUTOFF_ACTIVE_SET.md} section
     * 5 recorded as holding at interval 6 with the tank at 387430 Pa. */
    private PassiveNetwork valveLine(double generatorPressure) {
        var devices=List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),device(2,1,TopologyCompiler.Kind.PIPE),
                device(3,2,TopologyCompiler.Kind.VALVE,PhysicalFluidTopology.Direction.EAST),
                device(4,3,TopologyCompiler.Kind.PIPE),device(5,4,TopologyCompiler.Kind.PIPE),
                device(6,5,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure,0),6L,tankSpec(101325)));
    }
    /** generator - pipe - pump - three pipes - reservoir: the pump control, with no filter in it (the pipe before the pump: decision D20). */
    private PassiveNetwork pumpControlLine(double generatorPressure,double solidFraction) {
        var devices=List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),
                device(7,1,TopologyCompiler.Kind.PIPE),device(2,2,TopologyCompiler.Kind.PUMP,PhysicalFluidTopology.Direction.EAST),
                device(3,3,TopologyCompiler.Kind.PIPE),device(4,4,TopologyCompiler.Kind.PIPE),
                device(5,5,TopologyCompiler.Kind.PIPE),device(6,6,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure,solidFraction),6L,tankSpec(101325)));
    }
    /** generator - four pipes - reservoir: the filter-free control that ran a whole session. */
    private PassiveNetwork clearLine(double generatorPressure,double solidFraction) {
        var devices=List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),device(2,1,TopologyCompiler.Kind.PIPE),
                device(3,2,TopologyCompiler.Kind.PIPE),device(4,3,TopologyCompiler.Kind.PIPE),
                device(5,4,TopologyCompiler.Kind.PIPE),device(6,5,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure,solidFraction),6L,tankSpec(101325)));
    }

    /** One island interval exactly as the worker requests it: 5 s at the shared defaults. */
    private String interval(PassiveNetwork graph) {
        return intervals(graph,1);
    }
    /**
     * {@code count} consecutive 5 s intervals on one retained solver, which is how a placed island
     * actually runs: the world hands the accepted graph back to the same worker every cadence.
     */
    private String intervals(PassiveNetwork graph,int count) {
        return run(graph,count).detail();
    }

    /**
     * The shape a filter block compiles to. Not an assertion about the defect: it is the statement
     * of what the fixtures elsewhere do not build, so a later change to the compiler that removes
     * the two junctions shows up here rather than silently retiring the regressions below.
     */
    @Test void aFilterBlockCompilesToTwoZeroHoldupJunctionsAroundItsOwnEdge() {
        var graph=reservoirLine(101325,0);
        System.out.println("reservoir-terminated filter line:"+shape(graph));
        assertEquals(4,graph.reservoirs().size(),"generator, two filter junctions and the tank");
        assertEquals(3,graph.pipes().size());
        var filter=graph.pipes().stream().filter(p->p.filter()!=null).findFirst().orElseThrow();
        assertEquals(PhysicalFluidTopology.filterIdentity(3),filter.id());
        assertTrue(filter.id()<0,"A filter edge identity is Long.MIN_VALUE + id");
        assertEquals(PassiveNetwork.NodeKind.JUNCTION,graph.reservoirs().get(filter.first()).kind());
        assertEquals(PassiveNetwork.NodeKind.JUNCTION,graph.reservoirs().get(filter.second()).kind());
        assertEquals(3,graph.reservoirs().get(filter.first()).id(),"The filter keeps its own identity on the inlet junction");
        assertTrue(graph.reservoirs().get(filter.second()).id()<0,"The outlet junction is compiler-minted");
    }

    @Test void filterLineToATankIntegratesItsFirstIntervalAtRest() {
        var graph=reservoirLine(101325,0);
        String outcome=intervals(graph,40);
        System.out.println("(i) generator-pipe-filter-pipe-tank, clear, at rest: "+outcome);
        assertTrue(outcome.startsWith("OK"),outcome);
    }

    /**
     * The same line with the generator raised to 400 kPa, which is what a player does to make a
     * filter carry something. It fills the tank in under a minute and then settles against it, and
     * at that point it stops - and it has now stopped for three different reasons in a row, each
     * uncovered by fixing the one in front of it.
     *
     * <p>At fca6a15 it died at interval 8 on the tolerance defect, at residual 1.018e-10. At
     * 90f16c7 that was fixed and the same line died at interval 8 on the hydraulic row's constant
     * 1e5 Pa scaling: the filter's two zero-holdup junctions settled 3.5e-4 Pa apart and a 1e-9
     * Newton tolerance is an absolute 1e-4 Pa demand whatever the island runs at. That one is fixed
     * too, by {@code PassiveStepSolver.Equations.pressureScales}; see
     * {@code documentation/HYDRAULIC_ROW_SCALE.md} for the measurement. The hydraulic rows are no
     * longer what stops this line at any pressure: at 400 kPa the filter edge's row now sits at
     * -2.4e-14 where it used to floor the solve, and at 600 kPa it went from 4.39e-9 (the binding
     * row) to 4.1e-12.
     *
     * <p>The stall those measurements recorded is fixed too, by
     * {@code PassiveStepSolver.Equations.junctionDonorFirst}; see
     * {@code documentation/TANK_NODE_BLOCK.md}. The two rows that floored at 1.0e-9 to 1.4e-9 were
     * the tank's own volume closure and water saturation (not its nitrogen equilibrium - the row
     * map in HYDRAULIC_ROW_SCALE section 7 mislabels them; this tank's six rows are nitrogen
     * material, water material, energy, volume closure, water saturation and partial-pressure
     * closure). What made them unclosable was not the tank: it was that closing them moves the
     * tank's pressure by about 1.5 mPa, which through the filter's resistance moves every flow in
     * the island by 1.6e-6 kg/s, and the junctions' mixing rows were a step function of those
     * flows. The same shape with a valve instead of a filter now runs to the end; see
     * {@code valveLineToATankKeepsIntegratingOnceTheTankIsFull}. This line gets further too - at
     * 400 kPa it reaches interval 8 with the tank settled at 399999.99971 Pa against the
     * filter-free control's 400000.00020 Pa, where before it stopped at interval 7 with the tank
     * still at 399998.24 Pa - but it does not finish.
     *
     * <p>What stopped it then was a second, filter-specific defect, measured at every swept
     * pressure and since fixed; see {@code documentation/JUNCTION_PHANTOM_TRACE.md}:
     *
     * <ul>
     * <li>The undirected reachability closure let the tank's nitrogen into both of the filter's
     *     junctions, and {@code initialPhaseSeeds} then seeded each of them with {@code 1e-12} of
     *     the node's own total - 5.53e-8 mol here. A junction owns no volume, so that trace
     *     flashed into a vapour phase of 2.7e-14 of the junction's scale, and its water-saturation
     *     and partial-pressure rows were then stated on a vapour that is numerically nothing. The
     *     junction block was rank five of six (sigma from 3.5e15 down to 2.4e-149, and still
     *     1.0e-139 using every column in the island).</li>
     * <li>Nothing delivered that trace, so the Newton's only root for it was exactly zero. The
     *     nonnegativity step limiter caps the step at 0.99 of the distance to the boundary, so the
     *     unknown fell by a factor 0.99 per iteration and the iteration limit arrived first, with
     *     the junction's water-saturation row left at 0.2979 and its partial-pressure closure at
     *     3.2e-3 - or, when a phase correction intervened first, at 65 to 70. Reproduced exactly:
     *     150 kPa held at interval 23 on 66.6965, 200 at 16 on 0.29765, 250 at 13 on 67.0521, 300
     *     at 10 on 0.29797, 350 at 9 on 65.1547, 400 at 8 on 0.29788 and 600 at 5 on 1.6094.</li>
     * </ul>
     *
     * <p>A zero-holdup junction now carries the species its donors deliver and no others, is
     * seeded as the mixture they deliver rather than as its stored guess with a trace invented in
     * it, and reads both from the point its pass starts at. The line runs to the end at every
     * pressure swept above, and the tank settles where the filter-free control's does.
     */
    @Test void filterLineToATankKeepsIntegratingOnceTheTankIsFull() {
        for(double pressure:new double[]{150000,200000,250000,300000,350000,400000,600000}) {
            var driven=run(reservoirLine(pressure,0),40);
            var control=run(clearLine(pressure,0),40);
            System.out.println("(i-b) generator at "+(long)pressure+" Pa-pipe-filter-pipe-tank, clear: "+driven.detail()
                    +"\n(control) generator at "+(long)pressure+" Pa-4 pipes-tank: "+control.detail()
                    +"\n(endpoint) filter tank P="+driven.tank().pressure()+" mass="+driven.tank().mass()
                    +"  control tank P="+control.tank().pressure()+" mass="+control.tank().mass());
            assertTrue(driven.detail().startsWith("OK"),driven.detail());
            assertTrue(control.detail().startsWith("OK"),control.detail());
            assertEquals(pressure,driven.tank().pressure(),1e-6*pressure,
                    "A tank behind a filter must settle on its generator");
            assertEquals(control.tank().mass(),driven.tank().mass(),1e-6*control.tank().mass(),
                    "A tank behind a filter must hold what the same tank on plain pipes holds");
        }
    }

    /**
     * A tank behind a pressure valve's zero-holdup junction, which is the same defect as (i-b)
     * without a filter anywhere in it: the valve's target is below every generator pressure swept
     * here, so the valve is never at a corner and its own active set is never the question.
     *
     * <p>At {@code ae3b37c} this line held at interval 6 with the tank at 387430 Pa - 187 kPa
     * above the valve's own 200 kPa target - on
     * {@code Newton line search stalled at residual 1.000000082240371E-9; active-set pass=0}, and
     * identically with the pump active-set rules disabled; see
     * {@code documentation/PUMP_SHUTOFF_ACTIVE_SET.md} section 5 and
     * {@code documentation/TANK_NODE_BLOCK.md}. The stall was the junction's mixing rows being a
     * step function of the flows that form them, so a settled tank's own last correction - which
     * moves its pressure, and through the line's resistance its flows - could not be taken at any
     * step length. It now runs to the end and the tank settles on the generator, which is the
     * physical answer and the one the filter-free control reaches.
     */
    @Test void valveLineToATankKeepsIntegratingOnceTheTankIsFull() {
        for(double pressure:new double[]{300000,400000,600000}) {
            var driven=run(valveLine(pressure),40);
            var control=run(clearLine(pressure,0),40);
            System.out.println("(i-c) generator at "+(long)pressure+" Pa-pipe-valve-2 pipes-tank: "+driven.detail()
                    +"\n(control) generator at "+(long)pressure+" Pa-4 pipes-tank: "+control.detail()
                    +"\n(endpoint) valve tank P="+driven.tank().pressure()+" mass="+driven.tank().mass()
                    +"  control tank P="+control.tank().pressure()+" mass="+control.tank().mass());
            assertTrue(driven.detail().startsWith("OK"),driven.detail());
            assertTrue(control.detail().startsWith("OK"),control.detail());
            assertEquals(pressure,driven.tank().pressure(),1e-6*pressure,
                    "A tank behind a valve must settle on its generator");
            assertEquals(control.tank().mass(),driven.tank().mass(),1e-6*control.tank().mass(),
                    "A tank behind a valve must hold what the same tank on plain pipes holds");
        }
    }

    /** The same driven line with the filter block taken out of it: the control for (i-b). */
    @Test void filterFreeLineToATankKeepsIntegratingOnceTheTankIsFull() {
        String outcome=intervals(clearLine(400000,0),40);
        System.out.println("(control) generator at 400 kPa-4 pipes-tank, clear: "+outcome);
        assertTrue(outcome.startsWith("OK"),outcome);
    }

    /**
     * The recorded pumped shape, against the same line with the filter block taken out of it, and
     * against the passive line held at the pump's own shutoff pressure.
     *
     * <p>Both pumped lines used to stop, for a reason that had nothing to do with filtration: a
     * pump driving a tank up to its own shutoff head was left holding its head limit at a flow the
     * solve could not resolve the sign of, and the step solver ran out of Newton iterations on the
     * pump's hydraulic row. The filter-free control reproduced it byte for byte, and so did the
     * tree at 3c27271, before any solid-phase code existed, while a passive generator held at the
     * same 601325 Pa filled the same tank over the same intervals and never stopped. That is fixed;
     * see {@code documentation/PUMP_SHUTOFF_ACTIVE_SET.md}.
     *
     * <p>The endpoint check is the part that says the fix converged to the physical answer rather
     * than merely stopped failing. A pump at shutoff is a 601325 Pa boundary, so after the same
     * forty intervals the pumped tank must sit at the pump's own shutoff pressure, and where the
     * passive line held at that pressure sits, to the declared regression tolerance of 1e-6
     * relative. It does: 601324.99 Pa against 601325.00, a relative 1.5e-8.
     *
     * <p>The inventory is held to 1e-4 rather than 1e-6, and the reason is physical rather than
     * numerical: the two lines do not reach that pressure by the same energy path. The reference is
     * fed 298.15 K water already at 601325 Pa; the pumped line is fed 298.15 K water at 101325 Pa
     * and the pump raises it, so the tank ends 0.077 K colder - 298.215 K against 298.292 K - and
     * at water's thermal expansion that is a few times 1e-5 of density on its own. The measured gap
     * is 0.065 kg in 829, i.e. 7.9e-5. A 1e-6 inventory match would be a statement about the two
     * feeds' enthalpies, not about the pump's active set.
     */
    @Test void aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn() {
        var filtered=run(pumpLine(101325,0),40);
        var control=run(pumpControlLine(101325,0),40);
        var shutoff=run(clearLine(601325,0),40);
        System.out.println("(ii) generator-pump-pipe-filter-pipe-tank, clear, at rest: "+filtered.detail());
        System.out.println("(control) generator-pump-3 pipes-tank at rest: "+control.detail());
        System.out.println("(reference) passive generator at 601325 Pa-4 pipes-tank: "+shutoff.detail());
        assertTrue(filtered.survived()>=control.survived(),
                "A filter block shortened a pumped line: filtered="+filtered.detail()+"\ncontrol="+control.detail());
        assertTrue(control.detail().startsWith("OK"),control.detail());
        assertTrue(filtered.detail().startsWith("OK"),filtered.detail());
        assertTrue(shutoff.detail().startsWith("OK"),shutoff.detail());
        var pumped=control.last();var reference=shutoff.last();
        System.out.println("(endpoint) pumped tank P="+pumped.pressure()+" mass="+pumped.mass()+" T="+pumped.temperature()
                +" liq="+(TestPhases.liquidVolume(pumped)+TestPhases.waterVolume(pumped))+" vap="+pumped.vaporVolume()
                +"\n(endpoint) passive tank P="+reference.pressure()+" mass="+reference.mass()+" T="+reference.temperature()
                +" liq="+(TestPhases.liquidVolume(reference)+TestPhases.waterVolume(reference))+" vap="+reference.vaporVolume());
        assertEquals(101325+500000,pumped.pressure(),1e-6*601325,
                "A pumped line must settle at its own shutoff pressure");
        assertEquals(reference.pressure(),pumped.pressure(),1e-6*reference.pressure(),
                "A pumped line at shutoff must settle where a passive line held at that pressure settles");
        assertEquals(reference.mass(),pumped.mass(),1e-4*reference.mass(),
                "A pumped line at shutoff must hold what a passive line held at that pressure holds");
    }

    @Test void filterLineToAVoidIntegratesWithSolidsAndNoDrivingPressure() {
        var graph=voidLine(101325,.05);
        String outcome=interval(graph);
        System.out.println("(iii) generator(5%/100um)-pipe-filter-pipe-void, no driving pressure: "+outcome);
        assertTrue(outcome.startsWith("OK"),outcome+shape(graph));
    }

    @Test void filterLineToAVoidIntegratesWithSolidsAndDrivingPressure() {
        var graph=voidLine(300000,.2);
        String outcome=interval(graph);
        System.out.println("(iv) generator(20%/100um at 300 kPa)-pipe-filter-pipe-void: "+outcome);
        assertTrue(outcome.startsWith("OK"),outcome+shape(graph));
    }

    @Test void filterLineToAVoidIntegratesWithModerateSolidsAndPressure() {
        var graph=voidLine(150000,.05);
        String outcome=interval(graph);
        System.out.println("(v) generator(5%/100um at 150 kPa)-pipe-filter-pipe-void: "+outcome);
        assertTrue(outcome.startsWith("OK"),outcome+shape(graph));
    }

    /** The control: the same line without a filter ran a whole session without a hold. */
    @Test void filterFreeLineToATankIntegratesItsFirstInterval() {
        String rest=intervals(clearLine(101325,0),40);
        String driven=intervals(clearLine(300000,.05),40);
        System.out.println("(control) generator-4 pipes-tank at rest: "+rest);
        System.out.println("(control) generator(5%/100um at 300 kPa)-4 pipes-tank: "+driven);
        assertTrue(rest.startsWith("OK"),rest);
        assertTrue(driven.startsWith("OK"),driven);
    }

    /**
     * A passive line held at the pump's own shutoff pressure fills the same tank to the same place
     * and never stops, which is what makes the pumped hold above a property of the pump's device
     * active set rather than of a nearly liquid-full tank.
     */
    @Test void aPassiveLineAtThePumpsShutoffPressureFillsTheSameTank() {
        String raised=intervals(clearLine(601325,0),40);
        String lower=intervals(clearLine(300000,0),40);
        System.out.println("(probe) generator at 601325 Pa-4 pipes-tank, no pump: "+raised);
        System.out.println("(probe) generator at 300000 Pa-4 pipes-tank, no pump: "+lower);
        assertTrue(raised.startsWith("OK"),raised);
        assertTrue(lower.startsWith("OK"),lower);
    }
}
