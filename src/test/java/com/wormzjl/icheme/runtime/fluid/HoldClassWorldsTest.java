package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The hold-class worlds of the phase-ports batch (PHASE_PORTS_REVIEW.md, "Hold class ..." sections) and the later
 * junction-start and closure batches: each world is the smallest the placement-sequence fuzz or an in-game run found for
 * one way an island held (tools/phase-ports-probes/hold-fuzz and hold-classes). Every world is placed on the event path
 * ({@link EventPathWorld}), run to its end tick and must keep up: no hold, every placement applied, every island within
 * one cadence of the online tick. Each section below keeps the original test class's account of its class.
 */
class HoldClassWorldsTest {
    private static final FluidDeviceSpec NITROGEN=EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325),WATER=EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325);
    private static final int METHANE=EventPathWorld.CATALOG.requirePackage("icheme:tjl20_methane_nitrogen").components().indexOf("Methane");
    /** The Tia Juana light crude with methane, on the network basis, as a 1 m3 generator's spec. */
    private static FluidDeviceSpec crude(double temperature,double pressure) {
        double[] n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),
                MaterialTestBasis.NETWORK+1);
        return new FluidDeviceSpec(1,temperature,pressure,n);
    }
    /** Runs {@code body} on a world of the cold pilot package, inside that catalog's context, as the cold placement fuzz runs
     * a seed; its specs are on the pilot basis [nitrogen, methane, ethane, CO2, water]. */
    private static void cold(java.util.function.Consumer<EventPathWorld> body) {
        var catalog=PilotCryogenicTestCatalog.catalog();var network=FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog,network,()->{body.accept(new EventPathWorld(catalog,FluidThermodynamics.forNetwork(catalog,network)));return null;});
    }
    /** A 1 m3 generator's spec on the cold pilot basis: {@code moles} in basis order. */
    private static FluidDeviceSpec pilot(double temperature,double pressure,double... moles){return new FluidDeviceSpec(1,temperature,pressure,moles);}

    // ---------------- ActuatedIslandGeneratorClosure ----------------
    /**
     * Hold class E2 (PHASE_PORTS_REVIEW.md, 2026-09-28; decision D12): the cold-start closure of receiving generators
     * also covers islands with an actuator out. Fuzz seed 4's smallest world: a 472 kPa methane generator and a 261 kPa
     * two-phase crude generator feeding a junction network with two pressure control valves (120 and 200 kPa) and a void,
     * placed in one tick (rebuilt for D18-D20: a pipe between the crude generator and the 200 kPa valve, a pipe and a void
     * below the 120 kPa valve). Without the closure in actuated islands (a3d3a789) it held on a singular step at every
     * step size.
     */
    @Test void aLowCrudeGeneratorInAValveIslandDoesNotHoldTheSteps() {
        var catalog=EventPathWorld.CATALOG;int components=FluidDeviceSpec.nitrogen(catalog).composition().length;
        double[] crude=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),components);
        int methane=catalog.requirePackage("icheme:tjl20_methane_nitrogen").components().indexOf("Methane");
        var w=new EventPathWorld();w.runTo(105);var passive=new FlowControl.Passive();
        w.place(-1,-2,1,Kind.PIPE,NORTH,.05);w.place(2,0,0,Kind.PIPE,NORTH,.05);w.place(-1,-1,1,Kind.PIPE,NORTH,.02);w.place(-1,-2,3,Kind.PIPE,NORTH,.05);
        w.place(0,-2,3,Kind.PIPE,NORTH,.02);w.place(1,-2,3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(methane,352.1261664904419,472036.05064761586),.05);
        w.place(-1,-3,0,Kind.VALVE,DOWN,new FlowControl.PressureValve(120000),null,.02);w.place(-1,-2,0,Kind.PIPE,NORTH,.02);
        w.place(-1,-4,0,Kind.PIPE,NORTH,.02);w.place(-1,-5,0,Kind.VOID,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325),.02);
        w.place(0,0,0,Kind.PIPE,NORTH,.05);w.place(1,0,0,Kind.VALVE,EAST,new FlowControl.PressureValve(200000),null,.05);
        w.place(-1,0,0,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,368.8747936542612,261354.27900741925,crude),.02);
        w.place(-1,-2,2,Kind.PIPE,NORTH,.05);w.place(3,0,0,Kind.PIPE,NORTH,.05);
        w.place(4,0,0,Kind.VOID,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325),.05);
        w.place(-1,-1,0,Kind.PIPE,NORTH,.02);w.place(-1,0,1,Kind.PIPE,NORTH,.02);
        w.runTo(4200);w.assertKeptUp("a low crude generator in a valve island");
    }

    // ---------------- FailedIterateIsNoState ----------------
    /**
     * A failed pass's last iterate is not a state (owner decisions D28 and D39, M8_CAMPAIGN_WORSE_WORLDS_TRACE.md): its
     * check folds what the iterate empties and proposes nothing, so no TP answer forms there and no capability hold is
     * raised about a point the network never reaches. Cold fuzz seed 430, pair-minimized to 8 blocks on the cold pilot
     * package: at tick 404 two pipes join an empty tank to a junction fed by a CO2-rich gas generator (4.89 MPa, 293 K)
     * and an LNG generator (884 kPa, 128 K); nothing can cool below about 110 K. Before the rule a junction's failed
     * iterates on the 1 Pa box floor were flashed and the island held at tick 406 on "a third fluid phase ... at 17.35 K"
     * (class C). The world still holds a step stall, so it is not asserted to keep up: it pins that no hold is a
     * capability hold. The stall was E5, and from a6b14b22 on it is E6 at 406: the mint's balance ends unconverged on flat
     * (choked or one-way) rows (COLD430_889_REVIEW.md; the sweep-first form that cleared it was dropped by the owner on
     * 2026-10-21 for the worse worlds it made, FINAL_LANE_8D69949D_REVIEW.md).
     */
    @Test void aFailedIterateRaisesNoPhaseHold() {
        cold(w->{
            w.runTo(100);w.place(-3,0,0,Kind.PIPE,NORTH,.05);w.place(-3,-1,0,Kind.PIPE,NORTH,.02);
            w.runTo(122);w.place(-5,-1,0,Kind.RESERVOIR,NORTH,.1);
            w.runTo(277);w.place(-5,0,0,Kind.PIPE,NORTH,.05);
            w.runTo(314);w.place(-3,-2,0,Kind.GENERATOR,NORTH,pilot(293.3622295561016,4890311.622961059,0.08614517149650655,0.07056407215385954,0,0.8432907563496339,0),.1);
            w.runTo(364);w.place(-2,-1,0,Kind.GENERATOR,NORTH,pilot(128.26444287849995,884476.301554819,0.0015460461379406321,0.9680838334702262,0.010807740847289705,0.01956237954454346,0),.02);
            w.runTo(404);w.place(-4,-1,0,Kind.PIPE,NORTH,.02);w.place(-4,0,0,Kind.PIPE,NORTH,.05);
            w.runTo(404+100);
            for(var h:w.held)System.out.println("FAILED_ITERATE_HELD "+h);
            assertTrue(w.held.stream().noneMatch(h->h.status().startsWith(com.wormzjl.icheme.runtime.ProcessSolveServices.PHASE_HOLD)),
                    "no capability hold about a failed iterate: "+w.held);
        });
    }

    // ---------------- FilterIslandGeneratorClosure ----------------
    /**
     * Hold class E1 (PHASE_PORTS_REVIEW.md, 2026-09-28; decision D12): the cold-start closure of receiving generators
     * also covers filter islands, whose interval-start rate solve otherwise held on a generator that can only receive.
     * Fuzz seed 6's smallest world: a 130 kPa water generator three blocks below a junction pipe joining two 1 atm
     * nitrogen voids and an inline filter facing down, with a pipe and a void past it (rebuilt for D18-D20), placed in one
     * tick. Without the filter in the cold closure (bbb79790) it held at its first slice on "Newton line search stalled".
     */
    @Test void aWaterGeneratorBelowAFilterIslandsJunctionDoesNotHoldTheRatePass() {
        var w=new EventPathWorld();w.runTo(662);var passive=new FlowControl.Passive();
        var nitrogen=EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325);
        w.place(0,0,-2,Kind.VOID,NORTH,passive,nitrogen,.05);w.place(0,0,-1,Kind.PIPE,NORTH,.05);w.place(0,1,1,Kind.PIPE,NORTH,.05);
        w.place(-1,-3,1,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325),.05);
        w.place(-1,0,1,Kind.PIPE,NORTH,.05);w.place(-1,-1,1,Kind.PIPE,NORTH,.05);w.place(0,2,1,Kind.FILTER,DOWN,.05);
        w.place(0,0,0,Kind.VOID,NORTH,passive,nitrogen,.05);w.place(-1,-2,1,Kind.PIPE,NORTH,.05);w.place(0,0,1,Kind.PIPE,NORTH,.05);
        w.place(-1,0,0,Kind.PIPE,NORTH,.05);
        // Rebuilt for decisions D18-D20: the filter leads on to a void (a pipe and a void above it), and a 130 kPa water
        // generator feeds the junction through one pipe block, so the island flows and is solved.
        w.place(0,3,1,Kind.PIPE,NORTH,.05);w.place(0,4,1,Kind.VOID,NORTH,passive,nitrogen,.05);
        w.place(1,0,1,Kind.PIPE,NORTH,.05);w.place(2,0,1,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,130000),.05);
        w.runTo(4700);w.assertKeptUp("a water generator below a filter island's junction");
        long filter=w.registry.at(new PhysicalFluidTopology.Position(EventPathWorld.DIM,0,2,1)).orElseThrow().device().id();
        org.junit.jupiter.api.Assertions.assertTrue(w.registry.members(w.registry.owner(filter)).size()>1,"the filter is on a solved run, not dormant");
    }

    // ---------------- CappedJunction ----------------
    /**
     * Owner decision D59 (5), the capped gas junctions (A276, A341, t28): where every line of a junction group is choked
     * and every cap enters it (or every cap leaves it), nothing places its pressure; the line whose drive exceeds its
     * cap's pressure drop by the least is put on its law for the pass. Random fuzz seed 529's smallest world (7 blocks,
     * logs cc34): a 496 kPa methane generator feeds pipes around a placed tank and a pipe added at tick 162 closes a
     * loop. Without the rule the first Jacobian was singular at every step size (held at tick 164).
     */
    @Test void aChokedJunctionThatCannotBalanceKeepsOneLineOnItsLaw() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(100);
        w.place(1,-1,-2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(0,311.67397547863067,495884.91340665857),.1);
        w.place(0,-1,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,-1,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,-2,Kind.RESERVOIR,NORTH,.05);
        w.place(0,0,0,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(162);
        w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(162+120*100);w.assertKeptUp("a junction whose caps all enter it");
    }

    // ---------------- ChordPressureSlope ----------------
    /**
     * Thread t29: a chord step reuses the Jacobian only while it still closes at least half of every row's gap, the
     * rows' slopes in their ends' pressures included (a row's slope in {@code ln P} is the pressure itself). Random fuzz
     * seed 886's smallest world (18 blocks at tick 239, logs fx16): a 490 kPa methane generator feeding two placed tanks
     * and, through a filter, a void, every run 10 mm at its narrowest. The filter's junctions walked from 143 to 222 kPa
     * while the chord steps stated the filter row's pressure columns at the Jacobian's 143 kPa; the Newton ran out of
     * iterations at every step size (held at tick 240).
     */
    @Test void aFilterBetweenTwoWanderingJunctionsConverges() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        double[] water=new double[21];water[19]=1;double[] methane=new double[21];methane[0]=1;
        w.runTo(239);
        w.place(0,0,0,Kind.FILTER,SOUTH,passive,null,.01);
        w.place(0,0,4,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,1,4,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,-3,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.01);
        w.place(0,-3,-2,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.02);
        w.place(0,1,5,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,2,Kind.PIPE,NORTH,passive,null,.01);
        w.place(-3,0,3,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,372.0615276045455,490111.61641710874,methane),.01);
        w.place(-2,0,3,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,-2,-2,Kind.PIPE,NORTH,passive,null,.02);
        w.place(0,1,6,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,3,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,1,7,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.05);
        w.place(0,-1,-2,Kind.PIPE,NORTH,passive,null,.02);
        w.place(0,0,3,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(239+120*100);w.assertKeptUp("a filter between two wandering junctions");
    }

    // ---------------- ConnectionRowScale ----------------
    /**
     * Hold class E6, option (a) (PHASE_PORTS_REVIEW.md 2026-10-12, owner decision D35): a plain passive connection's
     * hydraulic row is divided by a scale taken from the velocity-cap drops of both its ends, since at a zero-flow root
     * neither end is the donor; taken from the donor picked by the driving pressure's sign, the scale jumped by orders at
     * that root wherever the two ends held different fluids, and the step Newton stopped at its iteration limit at every
     * step size. Both worlds are world-default blocks (0.05 m pipes, 1 atm water generators at 298.15 K, 1 atm nitrogen
     * voids) rebuilt on the junction start rule (D37) and minimized with the donor's scale (logs k05).
     */
    /**
     * World-default fuzz seed 724 (4 blocks at tick 110): two 1 atm water generators level with a pipe between them, and a
     * 1 atm nitrogen void one block below that pipe. With the donor's scale the first interval held at tick 113 on
     * "Newton iteration limit at residual 1.13e-8".
     */
    @Test void twoLevelWaterGeneratorsDrainingIntoAVoidBelowKeepUp() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(110);
        w.place(1,0,0,Kind.PIPE,NORTH,.05);w.place(2,0,0,Kind.GENERATOR,NORTH,passive,WATER,.05);
        w.place(1,-1,0,Kind.VOID,NORTH,passive,NITROGEN,.05);w.place(0,0,0,Kind.GENERATOR,EAST,passive,WATER,.05);
        w.runTo(110+120*100);w.assertKeptUp("two level water generators draining into a void below");
    }

    /**
     * World-default fuzz seed 247 (5 blocks at tick 140): a 1 atm water generator two pipe blocks from a junction with a
     * 1 atm nitrogen void one block above and one below. With the donor's scale the island held at tick 203 on "Newton
     * iteration limit at residual 0.357".
     */
    @Test void aWaterGeneratorDrainingBetweenAVoidAboveAndAVoidBelowKeepsUp() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(140);
        w.place(2,1,0,Kind.PIPE,NORTH,.05);w.place(2,1,-1,Kind.PIPE,NORTH,.05);w.place(2,1,-2,Kind.GENERATOR,NORTH,passive,WATER,.05);
        w.place(2,2,0,Kind.VOID,NORTH,passive,NITROGEN,.05);w.place(2,0,0,Kind.VOID,NORTH,passive,NITROGEN,.05);
        w.runTo(140+120*100);w.assertKeptUp("a water generator draining between a void above and a void below");
    }

    // ---------------- DryEndOutflow ----------------
    /**
     * Thread t29: a vessel that starts a step holding nothing gives nothing in that step (decision D54's dry end); an
     * outflow from a dry end within the forbidden-direction tolerance reads as no flow. Random fuzz seed 708's smallest
     * world (8 blocks at tick 360, logs fx03): a 366 K crude generator at 172 kPa feeding a 200 kPa relief valve, an
     * empty placed tank looped by two lines to one junction, and a void. A pass reached the empty tank with 1.6e-22 kg/s
     * in by one line and out by the other, and the booking ended it at exactly zero: "Candidate overdraws a reservoir"
     * at every step size (held at tick 361).
     */
    @Test void anEmptyTankLoopedToOneJunctionGivesNothing() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        double[] water=new double[21];water[19]=1;
        double[] crude={0.005000000000000001,0.0011908995509034748,0.010591918105298616,0.006510980717557732,0.0222592523684086,0.01663176428669243,
                0.024555314957131154,0.16629362769970432,0.12314847499525976,0.1177490756868832,0.10469103210175804,0.08701659237073239,0.07336455325619681,
                0.06154184102834706,0.06057002423058549,0.06607465545777229,0.02967631275527244,0.015001342402485261,0.008132338029011052,0.0,0.0};
        w.runTo(360);
        w.place(0,0,4,Kind.PIPE,NORTH,passive,null,.1);
        w.place(0,0,6,Kind.VALVE,NORTH,new FlowControl.PressureValve(200000),null,.01);
        w.place(-1,0,5,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.05);
        w.place(-1,0,7,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.1);
        w.place(0,0,5,Kind.PIPE,NORTH,passive,null,.1);
        w.place(1,0,7,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,365.5491586603084,172266.18504004818,crude),.05);
        w.place(0,0,7,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,4,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(360+120*100);w.assertKeptUp("an empty tank looped to one junction");
    }

    // ---------------- EmptyingVessel ----------------
    /**
     * A vessel that runs dry within a step (decision D54, A313) is solved as a junction holding its stock from the step's
     * own start, with the same carried flows as every other pass (PHASE_PORTS_REVIEW.md "Active-set cycles and the flush
     * family (2026-10-14)", B1). Random fuzz seed 257 minimized to 7 blocks (logs cf10): a 384 kPa, 350 K water
     * generator pushes through a 1 cm pipe into a junction feeding an empty 1 m3 tank's top and, round a loop, a
     * junction at the tank's side draining 1 m down into a 1 atm void; the tank's drain carries ten times what reaches
     * it, so it runs dry within each step. Started from the static estimate the emptying pass read the void's air as
     * the drain's column and the pass sequence cycled at every step size: held at tick 154 on "Phase/device active-set
     * cycle".
     */
    @Test void aTankThatWaterPassesThroughRunsDryAtEachStep() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(151);
        w.place(0,-1,-1,Kind.PIPE,NORTH,.01);
        w.place(0,0,-1,Kind.PIPE,NORTH,.05);
        w.place(0,0,0,Kind.GENERATOR,SOUTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,350.1538205969658,384088.6831723288),.02);
        w.place(0,-3,-2,Kind.VOID,NORTH,.05);
        w.place(0,-2,-1,Kind.RESERVOIR,NORTH,.01);
        w.place(0,-2,-2,Kind.PIPE,NORTH,.05);
        w.place(0,-1,-2,Kind.PIPE,NORTH,.05);
        w.runTo(151+120*100);w.assertKeptUp("a tank that water passes through");
    }

    // ---------------- HoldingNothingBooking ----------------
    /**
     * Thread t32, owner decision D59 (2): a vessel that holds nothing at the bag's resolution (1e-12 of its capacity) after
     * a step's booking keeps its residue in its inventory and is stated at its flash; no closure is solved for it. Random
     * fuzz seed 707's smallest world (12 blocks, logs cw06): a 327 K crude generator at 371 kPa and a 311 K water generator
     * at 363 kPa filling three empty tanks, the water line joined at tick 165. The booking closed a tank holding 1e-42 mol
     * of crude and 1e-84 mol of water vapour by a Newton whose water column found no finite difference trial (held at tick
     * 167).
     */
    @Test void aResidueIsBookedWithoutAClosure() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        double[] water=new double[21];water[20]=1;
        double[] air=new double[21];air[19]=1;
        double[] crude={0.005000000000000001,0.0011908995509034748,0.010591918105298616,0.006510980717557732,0.0222592523684086,0.01663176428669243,
                0.024555314957131154,0.16629362769970432,0.12314847499525976,0.1177490756868832,0.10469103210175804,0.08701659237073239,0.07336455325619681,
                0.06154184102834706,0.06057002423058549,0.06607465545777229,0.02967631275527244,0.015001342402485261,0.008132338029011052,0.0,0.0};
        w.runTo(142);
        w.place(0,-3,0,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,326.6101199519573,371434.1948684311,crude),.02);
        w.place(0,0,2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,-2,-2,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,311.4239803319713,362954.9489303839,water),.05);
        w.place(0,-2,0,Kind.PIPE,NORTH,passive,null,.02);
        w.place(0,-2,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-2,0,2,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,air),.01);
        w.place(0,1,1,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,air),.05);
        w.place(-1,0,2,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,0,Kind.RESERVOIR,WEST,passive,new FluidDeviceSpec(1,298.15,101325,air),.05);
        w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(165);
        w.place(0,-2,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(165+120*100);w.assertKeptUp("a residue booked into an empty tank");
    }

    // ---------------- HoldingNothingLayout ----------------
    /**
     * Thread t29, owner decision D59 (2): a vessel that holds nothing at the bag's resolution is laid out dry, and a
     * roundoff that reaches it is not an arrival: it stays booked in the inventory and the vessel stays dry. Random fuzz
     * seed 708 after its first hold was cured (17 blocks at tick 332, logs fx30): a 366 K crude generator at 172 kPa, a
     * 200 kPa relief valve, two placed tanks and two voids. A pass reached an empty tank with 2e-29 kg of crude, the tank
     * was laid out from it as two phases and its fugacity rows stalled the line search at every step size (held at tick
     * 333).
     */
    @Test void aRoundoffArrivalLeavesAnEmptyTankDry() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        double[] water=new double[21];water[19]=1;
        double[] crude={0.005000000000000001,0.0011908995509034748,0.010591918105298616,0.006510980717557732,0.0222592523684086,0.01663176428669243,
                0.024555314957131154,0.16629362769970432,0.12314847499525976,0.1177490756868832,0.10469103210175804,0.08701659237073239,0.07336455325619681,
                0.06154184102834706,0.06057002423058549,0.06607465545777229,0.02967631275527244,0.015001342402485261,0.008132338029011052,0.0,0.0};
        w.runTo(332);
        w.place(0,0,0,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,4,Kind.PIPE,NORTH,passive,null,.1);
        w.place(0,0,6,Kind.VALVE,NORTH,new FlowControl.PressureValve(200000),null,.01);
        w.place(0,-1,1,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.05);
        w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,2,Kind.PIPE,NORTH,passive,null,.1);
        w.place(-1,0,5,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.05);
        w.place(2,0,2,Kind.PIPE,NORTH,passive,null,.02);
        w.place(3,0,2,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.02);
        w.place(-1,0,7,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,water),.1);
        w.place(1,0,2,Kind.PIPE,NORTH,passive,null,.02);
        w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.1);
        w.place(0,0,5,Kind.PIPE,NORTH,passive,null,.1);
        w.place(1,0,7,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,365.5491586603084,172266.18504004818,crude),.05);
        w.place(0,0,7,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,4,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,3,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(332+120*100);w.assertKeptUp("a roundoff arrival in an empty tank");
    }

    // ---------------- JunctionStartMixture ----------------
    /**
     * The junction start of form (m') (PHASE_PORTS_REVIEW.md 2026-10-12, owner decision D39): a junction several
     * suppliers drive into starts as the flow-weighted mixture still flowing in at the pressure that balances its
     * suppliers against its drains, the strongest supplier alone where the drain backs up. The start chain is retired
     * by owner decision D54 (a minted junction starts from the island's fixed point, PassiveNetwork.mintJunctions); its
     * worlds stay as regressions: each keeps up.
     */
    /**
     * Random fuzz seed 30's smallest world in which the three start rules differ (8 blocks, logs m09, m10): a 361 kPa
     * water generator at 356 K and a 366 kPa nitrogen generator at 333 K feed a junction pipe level with them, which
     * vents up into a 1 atm nitrogen void; at tick 756 a second void makes a second junction. At rest the junction holds
     * about 2.4 % nitrogen at 332 kPa. Started as the nearest supplier's water the second island held at tick 758 on
     * "Newton iteration limit at residual 0.0041"; started as the strongest supplier's nitrogen the first island held at
     * tick 104 on the water-vapour range.
     */
    @Test void twoGeneratorsVentingIntoAVoidStartTheirJunctionAsTheirMixture() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var water=EventPathWorld.pure(MaterialTestBasis.NETWORK,355.6753347596607,361077.84297592135);
        var nitrogen=EventPathWorld.pure(MaterialTestBasis.NITROGEN,333.2954119245145,366182.8050784859);
        w.runTo(103);
        w.place(0,0,1,Kind.PIPE,NORTH,.02);w.place(-1,0,0,Kind.PIPE,NORTH,.01);w.place(0,0,2,Kind.PIPE,NORTH,.02);
        w.place(0,0,0,Kind.GENERATOR,EAST,passive,water,.01);w.place(0,1,1,Kind.VOID,NORTH,passive,NITROGEN,.1);
        w.place(-1,0,1,Kind.PIPE,NORTH,.1);w.place(0,0,3,Kind.GENERATOR,NORTH,passive,nitrogen,.02);
        w.runTo(756);
        w.place(-2,0,1,Kind.VOID,NORTH,passive,NITROGEN,.1);
        w.runTo(756+120*100);w.assertKeptUp("two generators venting into a void through one junction");
    }

    /**
     * The start pressure of a junction one supplier reaches (PHASE_PORTS_REVIEW.md 2026-10-13): random fuzz seed 17's
     * smallest world in which the two starts differ (9 blocks, logs n09, n10). A 195 kPa nitrogen generator at 288 K
     * feeds, through two 0.01 m pipe blocks at their velocity cap, three junctions draining into two 1 atm nitrogen
     * tanks, so the junctions stand near the tanks (109.27, 101.33, 101.33 kPa). Started at the generator's own 195 kPa
     * the world held at tick 101 on "Newton iteration limit at residual 2.9e-7".
     */
    @Test void aLoneSupplierThroughANarrowPipeStartsItsJunctionsAtTheirBalancePressure() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var nitrogen=EventPathWorld.pure(MaterialTestBasis.NITROGEN,288.29426437198714,194962.1520197138);
        w.runTo(100);
        w.place(4,1,0,Kind.PIPE,NORTH,.01);w.place(3,0,0,Kind.RESERVOIR,NORTH,.05);w.place(2,0,-1,Kind.RESERVOIR,NORTH,.1);
        w.place(3,1,0,Kind.PIPE,NORTH,.05);w.place(5,1,0,Kind.PIPE,NORTH,.01);w.place(2,0,0,Kind.PIPE,NORTH,.05);
        w.place(4,0,0,Kind.PIPE,NORTH,.05);w.place(2,1,0,Kind.PIPE,NORTH,.05);w.place(6,1,0,Kind.GENERATOR,NORTH,passive,nitrogen,.01);
        w.runTo(100+120*100);w.assertKeptUp("a lone generator feeding junctions through a narrow pipe");
    }

    /**
     * The drain-side gas column (PHASE_PORTS_REVIEW.md 2026-10-13): world-default fuzz seed 992's smallest world in which
     * the start with and without it differ (11 blocks, logs o07, o08). Two 1 atm nitrogen tanks at y 0 and a 1 atm water
     * generator at y -1 meet at junctions at y 0 and y -1; at tick 165 a pipe re-forms the island. The lower junction
     * stands under 1 m of the tanks' nitrogen, 11 Pa above the generator's head, so it starts as the tanks' nitrogen and
     * nothing flows. Without the column the bound was the tanks' pressure, a few 1e-11 Pa below the generator's head, the
     * junction started as water and held at tick 168 on "Newton iteration limit at residual 0.0012".
     */
    @Test void aJunctionUnderTheTanksNitrogenStandsAboveALevelWaterGenerator() {
        var w=new EventPathWorld();
        var water=EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325);
        w.runTo(132);
        w.place(-3,-1,0,Kind.PIPE,NORTH,.05);w.place(-3,0,0,Kind.PIPE,NORTH,.05);w.place(-2,-1,-1,Kind.PIPE,NORTH,.05);
        w.place(-1,0,0,Kind.PIPE,NORTH,.05);w.place(0,0,0,Kind.RESERVOIR,NORTH,.05);w.place(-1,0,-2,Kind.RESERVOIR,NORTH,.05);
        w.place(-2,0,0,Kind.PIPE,NORTH,.05);w.place(-2,-1,0,Kind.PIPE,NORTH,.05);w.place(-2,-1,-2,Kind.GENERATOR,NORTH,new FlowControl.Passive(),water,.05);
        w.place(-1,0,-1,Kind.PIPE,NORTH,.05);
        w.runTo(165);
        w.place(-1,0,-3,Kind.PIPE,NORTH,.05);
        w.runTo(165+120*100);w.assertKeptUp("a junction under the tanks' nitrogen beside a level water generator");
    }

    // ---------------- JunctionStartState ----------------
    /**
     * Hold class E4, the junction start from reachability (PHASE_PORTS_REVIEW.md 2026-10-12, owner decision D37): a
     * junction of an island formed from placed blocks starts as the stream of the nearest boundary that can drive fluid
     * into it, or, when none can, as the fluid standing highest among the boundaries in contact with it, not as the
     * island's first boundary by identity (the start chain is retired by D54; the world stays as a regression).
     * World-default fuzz seed 438 with the start rule reverted (6 blocks at tick 510, logs i17): a 1 atm water generator
     * feeds, through two level pipe blocks, a junction with one void a block below it and another beside it. Started as
     * the lower void's nitrogen, the island held at tick 573 on "Conservative reconstruction fails equation gate:
     * 1.16e-4" at every step size.
     */
    @Test void aJunctionOnlyWaterReachesStartsAsWater() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(510);
        w.place(0,1,0,Kind.PIPE,NORTH,.05);w.place(0,0,0,Kind.VOID,NORTH,passive,NITROGEN,.05);w.place(0,1,2,Kind.PIPE,NORTH,.05);
        w.place(0,1,3,Kind.GENERATOR,NORTH,passive,WATER,.05);w.place(0,1,1,Kind.PIPE,NORTH,.05);w.place(1,1,0,Kind.VOID,NORTH,passive,NITROGEN,.05);
        w.runTo(510+120*100);w.assertKeptUp("a junction only water reaches");
    }

    // ---------------- PartitionDraw ----------------
    /**
     * Nothing is drawn that is not there (PHASE_PORTS_REVIEW.md "Active-set cycles and the flush family (2026-10-14)",
     * B3): where the explicit booking of a frozen phase stream leaves a component negative, that component's streams
     * are booked at the candidate's partition against the donor's own end fraction
     * ({@code ConservativeTransport.reconstruct0}). Random fuzz seed 322 minimized to 10 blocks (logs cf21): a 276 kPa
     * crude generator and a 431 kPa methane generator feed a tank and a riser to a 1 atm void; the tank drains into a
     * second tank below. The upper tank's gas stream drew 1e-34 to 5e-24 mol per step of a component the tank never held
     * and the explicit booking held at tick 513 on "Negative transport reconstruction".
     */
    @Test void aGasStreamDrawsNoComponentItsTankNeverHeld() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(498);
        w.place(1,0,-1,Kind.RESERVOIR,NORTH,.1);
        w.place(1,2,-2,Kind.PIPE,NORTH,.1);
        w.place(2,0,-1,Kind.PIPE,NORTH,.1);
        w.place(0,0,-2,Kind.GENERATOR,NORTH,passive,crude(362.6583839483169,276473.9715734725),.02);
        w.place(2,0,-2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(METHANE,357.0575469354789,431096.16156985326),.1);
        w.place(1,3,-2,Kind.VOID,NORTH,.1);
        w.place(1,1,-2,Kind.PIPE,NORTH,.1);
        w.place(1,-1,-1,Kind.PIPE,NORTH,.05);
        w.place(1,0,-2,Kind.PIPE,NORTH,.1);
        w.place(1,-2,-1,Kind.RESERVOIR,NORTH,.05);
        w.runTo(498+120*100);w.assertKeptUp("a gas stream from a tank fed with crude and methane");
    }
    /**
     * Random fuzz seed 720 minimized to 10 blocks (logs cf21): a 316.2 kPa methane generator fills two stacked tanks, the
     * upper one's top joined through 2 cm pipes to a 315.9 kPa nitrogen generator that cannot receive. The upper tank's
     * nitrogen washes out to 4.8e-24 of its amount, just above the flush fraction (D16); its frozen gas stream booked
     * twelve times what it held and the explicit booking held at tick 145 on "Negative transport reconstruction".
     */
    @Test void aTraceJustAboveTheFlushFractionIsDrawnInProportionToWhatIsHeld() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(142);
        w.place(1,3,-2,Kind.PIPE,NORTH,.02);
        w.place(0,2,0,Kind.RESERVOIR,NORTH,.05);
        w.place(0,3,0,Kind.PIPE,NORTH,.05);
        w.place(1,3,0,Kind.PIPE,NORTH,.05);
        w.place(1,3,-1,Kind.PIPE,NORTH,.02);
        w.place(0,0,0,Kind.RESERVOIR,WEST,.05);
        w.place(0,1,0,Kind.PIPE,NORTH,.05);
        w.place(0,0,2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(METHANE,364.6040996772146,316227.12347029406),.05);
        w.place(1,3,-3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,299.0689920722667,315871.77173495316),.02);
        w.place(0,0,1,Kind.PIPE,NORTH,.05);
        w.runTo(142+120*100);w.assertKeptUp("a nitrogen trace washed out of a methane tank");
    }

    // ---------------- PolishedClosure ----------------
    /**
     * Thread t32: a point the D13 polish re-solves from the reconstruction may show a drive the converged point did not,
     * a one-way end that would receive; the pass loop closes that boundary and solves the pass again instead of refusing
     * the step. Random fuzz seed 71's smallest world (9 blocks at tick 207, logs cw05): a 317 K water generator at
     * 334 kPa fills an empty 1 m3 tank 3 m below it, beside a 146 kPa generator its lines may not feed; full, the tank
     * stands at 363.4 kPa and nothing flows. The booking stated the full tank 0.01 Pa above what the generator holds, the
     * polish converged with that pushing water back into the generator, and the step was refused at every step size
     * (held at tick 734).
     */
    @Test void aFullTankAboveItsGeneratorClosesTheGeneratorsLine() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        double[] water=new double[21];water[20]=1;
        double[] air=new double[21];air[19]=1;
        w.runTo(207);
        w.place(1,-2,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,1,-1,Kind.PIPE,NORTH,passive,null,.1);
        w.place(1,-1,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
        w.place(2,1,-1,Kind.PIPE,NORTH,passive,null,.1);
        w.place(2,0,-1,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,297.6508444710189,146277.4155068182,water),.1);
        w.place(0,1,-1,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,317.097467877553,334274.03661019984,water),.02);
        w.place(0,-2,-1,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,air),.01);
        w.runTo(207+120*100);w.assertKeptUp("a tank filled full of water by a generator");
    }

    // ---------------- RefutedProposal ----------------
    /**
     * A proposal the next pass refutes is refused (owner decision D39 B, 2026-10-18; D39_FAILED_ITERATE_AND_REFUTED_PROPOSAL.md):
     * a phase the check of a converged point added to a node, which the next pass drives under
     * FluidThermodynamics.VANISHING_SHARE and below the share it was added at, fails that pass; the pass is solved again
     * without it and the refusal holds for the solve (A85). Cold fuzz seed 48, pair-minimized to 6 blocks
     * (M8_CAMPAIGN_WORSE_WORLDS_TRACE.md), on the cold pilot package: at tick 1254 a pipe joins a 1.95 MPa, 149 K LNG
     * generator to an empty tank whose bottom drains into a second empty tank 2 m below; while both bags are slack the
     * lower tank holds the flashed liquid exactly on its 113.07 K bubble point, with no vapour. Before the rule the check
     * proposed 3.4e-6 of its moles as vapour, the two-phase pass drove it to 6e-9 and stalled, and the island held at tick
     * 1257 (class E5, residual 4.04e-8).
     */
    @Test void aBubbleTheNextPassDrivesToNothingIsRefused() {
        cold(w->{
            w.runTo(166);w.place(0,0,0,Kind.GENERATOR,SOUTH,pilot(149.1281962169163,1953857.8831369104,0.006488146200716343,0.8830893755602761,0.06294213595499426,0.047480342284013224,0),.02);
            w.runTo(323);w.place(0,-1,-3,Kind.PIPE,NORTH,.1);
            w.runTo(598);w.place(0,0,-3,Kind.RESERVOIR,NORTH,.1);
            w.runTo(1185);w.place(0,0,-1,Kind.PIPE,NORTH,.1);
            w.runTo(1207);w.place(0,-2,-3,Kind.RESERVOIR,NORTH,.1);
            w.runTo(1254);w.place(0,0,-2,Kind.PIPE,NORTH,.1);
            w.runTo(1254+5*100);
            for(var s:w.coordinator.snapshots())for(var node:s.graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)
                System.out.println("REFUTED_PROPOSAL island "+s.id()+" node "+node.id()+" z="+node.elevation()+" P="+node.state().pressure()+" T="+node.state().temperature()
                        +" m="+node.state().mass()+" liq="+node.state().liquidVolume()+" vap="+node.state().vaporVolume()+" solidsKg="+node.state().solids().massKg());
            w.assertKeptUp("a boiling tank draining into an empty tank below");
            for(var s:w.coordinator.snapshots())for(var node:s.graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)
                assertEquals(0,node.state().vaporVolume(),"tank "+node.id()+" at "+node.state().pressure()+" Pa, "+node.state().temperature()+" K holds no vapour");
        });
    }

    // ---------------- ResidueTank ----------------
    /**
     * Owner decision D59 (2), t28: a residue a junction's buffer leaves in an emptied tank is nothing (the bag's
     * resolution, 1e-12 of its capacity), so a tank holding it gives nothing. World-defaults fuzz seed 322's smallest
     * world (13 blocks at tick 498, logs cc40): two 1 atm water generators, two voids, pipes, and two placed tanks, one
     * 2 m above the other. The upper tank was left holding about 1e-71 kg of water, the line from it stated a 2 m water
     * column (19.5 kPa) with nothing to move, and the step stalled at 4.2106 at every step size (held at tick 501).
     */
    @Test void aTankHoldingAResidueGivesNothing() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var water=EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325);
        w.runTo(498);
        w.place(1,0,-1,Kind.RESERVOIR,NORTH,.05);
        w.place(0,1,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,2,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(2,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,0,0,Kind.VOID,NORTH,.05);
        w.place(0,0,-2,Kind.GENERATOR,NORTH,passive,water,.05);
        w.place(2,0,-2,Kind.GENERATOR,NORTH,passive,water,.05);
        w.place(1,3,-2,Kind.VOID,NORTH,.05);
        w.place(1,1,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,-1,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,-2,-1,Kind.RESERVOIR,NORTH,.05);
        w.place(2,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(498+120*100);w.assertKeptUp("a tank holding a residue");
    }

    // ---------------- StartCheckRateFailure ----------------
    /**
     * Hold class E1, R1 on the E6 rule (PHASE_PORTS_REVIEW.md 2026-10-12, owner decision D37): a filter island's
     * start-of-interval rate solve only pre-empts a closure the steps would find, and on the island's first interval the
     * junctions it holds fixed are the mint, so its failure is no evidence: it declares no closure and the steps decide
     * (option R1). Random fuzz seed 866 with R1 reverted (13 blocks at tick 289, logs i07): a 348 kPa methane generator
     * feeds a junction whose only ways out are a 120 kPa back-pressure valve joined upstream to a 1 atm void (shut) and a
     * line through an inline filter to an 88 kPa methane generator (supplies only); nothing flows and the island rests.
     * Without R1 the check stopped at its iteration limit (residual 0.0231 on pass 0) and held the island at tick 290.
     * This world fails its check from the old junction start only; the next test guards R1 under the start rule (D37).
     */
    @Test void aFailedStartCheckOfAFilterIslandLetsTheStepsDecide() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var nitrogen=EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325.0);
        w.runTo(289);
        w.place(2,0,-2,Kind.PIPE,NORTH,passive,null,.01);
        w.place(1,2,0,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(METHANE,359.6449449966632,348152.3443461481),.02);
        w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(2,0,-1,Kind.PIPE,NORTH,passive,null,.01);
        w.place(-2,0,-2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(METHANE,366.5658834936904,88458.07705694108),.01);
        w.place(-1,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,0,Kind.VALVE,EAST,new FlowControl.PressureValve(120000.0),null,.05);
        w.place(1,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.place(2,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,1,0,Kind.PIPE,NORTH,passive,null,.02);
        w.place(-2,0,0,Kind.VOID,NORTH,passive,nitrogen,.01);
        w.place(1,0,-2,Kind.FILTER,EAST,passive,null,.05);
        w.place(-1,0,0,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(289+120*100);w.assertKeptUp("a filter island whose start check fails at the mint");
    }

    /**
     * The same guard under the junction start rule (D37): random fuzz seed 122 with R1 reverted (9 blocks at tick 217,
     * logs i34). A 273 kPa nitrogen generator feeds, through a 0.01 m pipe, a junction draining into a 1 atm void; a
     * branch rises through an inline filter to a 91 kPa nitrogen generator that only supplies. Without R1 the check
     * stopped at its iteration limit (residual 2.51 on pass 0) and held the island at tick 218.
     */
    @Test void aFailedStartCheckUnderTheJunctionStartRuleLetsTheStepsDecide() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var nitrogen=EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325.0);
        w.runTo(217);
        w.place(-1,-1,0,Kind.PIPE,NORTH,passive,null,.1);
        w.place(-4,0,0,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,291.0013701419287,90886.84532252274),.05);
        w.place(-1,-1,1,Kind.PIPE,NORTH,passive,null,.01);
        w.place(1,-1,0,Kind.VOID,NORTH,passive,nitrogen,.05);
        w.place(-1,-1,2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,311.53760283311857,272641.22182337544),.01);
        w.place(-2,0,0,Kind.FILTER,WEST,passive,null,.05);
        w.place(-3,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(217+120*100);w.assertKeptUp("a filter island whose start check fails under the start rule");
    }

    // ---------------- StaticColumn ----------------
    /**
     * Owner decision D34 (M5_WORSE_WORLDS_TRACE.md "Seed 322 (E6)", 2026-10-17): a phase port's static column is filled
     * with the port's phases in priority order by volume over the connection, so a trace in a long line stands as a gas
     * column; the draw still reads the port's leading phase. Random fuzz seed 322's smallest world (13 blocks,
     * tools/unified-thermo-port-m5/trace logs 09): a 431 kPa methane generator vents to two 1 atm voids; the upper of two
     * 1 m3 tanks drains through 1 m of 0.05 m line (2 L) into the top of the lower tank 2 m down. About 0.9 mL of crude
     * vapour condenses in the upper tank, and the drop line holds its gas (2.29 kg/m3, 44.9 Pa over 2 m). Read from the
     * leading phase the line was the condensate's 17.3 kPa column, which milligrams could not fill: drawn alone at a
     * carried flow of about zero it had no root, and the island held at tick 1097 at every step size.
     *
     * <p>Owner decision of 2026-10-20, amending D38 C (OD4_HOLDS_REVIEW.md, round 3): a shrinking hydrocarbon liquid
     * vanishes on its volume share alone. The upper tank sits at its dew point and condenses 0.077 mL a step: 1.5e-6 of
     * its moles but 7.7e-8 of its volume. Under the mole test, the first step accepted with that film made every later
     * pass lose its root above 3.7e-4 s, so the island held at tick 4497 on any 1e-12 change in the junction's seed.
     */
    @Test void aLiquidTraceAtATanksBottomPortLeavesItsDropLineAGasColumn() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(498);
        long upper=w.place(1,0,-1,Kind.RESERVOIR,NORTH,passive,NITROGEN,.1);
        w.place(0,1,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,2,-2,Kind.PIPE,NORTH,passive,null,.1);
        w.place(2,0,-1,Kind.PIPE,NORTH,passive,null,.1);
        w.place(1,0,0,Kind.VOID,NORTH,passive,NITROGEN,.05);
        w.place(0,0,-2,Kind.GENERATOR,NORTH,passive,crude(362.6583839483169,276473.9715734725),.02);
        w.place(2,0,-2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(METHANE,357.0575469354789,431096.16156985326),.1);
        w.place(1,3,-2,Kind.VOID,NORTH,passive,NITROGEN,.1);
        w.place(1,1,-2,Kind.PIPE,NORTH,passive,null,.1);
        w.place(1,-1,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(680);w.place(1,0,-2,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(729);long lower=w.place(1,-2,-1,Kind.RESERVOIR,NORTH,passive,NITROGEN,.05);
        w.runTo(770);w.place(2,0,0,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(770+40*100);w.assertKeptUp("a liquid trace at a tank's bottom port");
        var island=w.coordinator.snapshots().stream().filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==upper)).findFirst().orElseThrow();
        var nodes=island.graph().reservoirs();int a=-1,b=-1,line=-1;
        for(int i=0;i<nodes.size();i++){if(nodes.get(i).id()==upper)a=i;if(nodes.get(i).id()==lower)b=i;}
        for(int i=0;i<island.graph().pipes().size();i++){var p=island.graph().pipes().get(i);if(p.first()==a&&p.second()==b||p.first()==b&&p.second()==a)line=i;}
        double flow=island.lastResult().orElseThrow().averageMassFlows()[line];
        var top=nodes.get(a).state();var bottom=nodes.get(b).state();
        double dz=nodes.get(a).elevation()-nodes.get(b).elevation(),column=bottom.pressure()-top.pressure();
        double upperColumn=w.model.vaporDensity(top)*PassiveStepSolver.GRAVITY*dz,lowerColumn=w.model.vaporDensity(bottom)*PassiveStepSolver.GRAVITY*dz;
        System.out.println("STATIC_COLUMN_WORLD upper P="+top.pressure()+" liquid="+top.liquidVolume()+" lower P="+bottom.pressure()+" liquid="+bottom.liquidVolume()
                +" gas column="+column+" Pa (the upper tank's gas "+upperColumn+", the lower tank's "+lowerColumn+") drop line="+flow+" kg/s");
        assertEquals(0,top.liquidVolume(),"the upper tank is dry: the condensate ran down");
        // Owner decision of 2026-10-19 (decision 2 of FORM_D_GATES_TRACE.md): the line stands as a gas column, any between the two
        // tanks' gas columns (it holds the upper tank's gas, which cools towards the lower tank's), never the 17.3 kPa condensate one.
        assertTrue(column>=Math.min(upperColumn,lowerColumn)-.1&&column<=Math.max(upperColumn,lowerColumn)+.1,
                "the drop line stands as a gas column between the two tanks' columns: "+column+" Pa against "+upperColumn+" and "+lowerColumn);
        // A standing column, not a drain: over the run's 40 intervals (200 s) the drop line's flow does not exchange the gas it
        // holds (the lower tank follows the upper one's cooling gas; the 17.3 kPa condensate column would have drained in seconds).
        double held=w.model.vaporDensity(bottom)*island.graph().pipes().get(line).volume();
        assertTrue(Math.abs(flow)*200<held,"the drop line stands: its flow "+flow+" kg/s would exchange its "+held+" kg of gas within the run");
    }

    // ---------------- StepStartFlows ----------------
    /**
     * The step starts from the committed flows (PHASE_PORTS_REVIEW.md 2026-10-13, card E6-S): an island job's first step
     * starts from the flows its committed interval ended with (PassiveIntervalSolver.Result.endpointFlows), as every
     * later step starts from the step before. Card E6-S's world (world-default fuzz seed 786, 8 blocks, logs y18, sf09):
     * a 1 atm water generator feeds a junction beside it that drains 1 m down into a void; both rise 1 m to a junction
     * joined to a nitrogen tank near 90.9 kPa, so about 4.8 kg/s circulates up one riser and down the other. With the old
     * job start (the static per-connection estimate) every job restarted the circulation and the world held at tick 3210
     * on "Newton iteration limit at residual 0.0789" at every step size.
     */
    @Test void aCirculationBetweenTwoRisersContinuesFromItsCommittedFlows() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(158);w.place(0,0,2,Kind.PIPE,NORTH,.05);
        w.runTo(473);w.place(-3,0,2,Kind.PIPE,NORTH,.05);
        w.runTo(504);w.place(-1,-1,2,Kind.GENERATOR,NORTH,passive,WATER,.05);
        w.runTo(553);w.place(0,-1,2,Kind.PIPE,NORTH,.05);
        w.runTo(625);w.place(-1,0,2,Kind.PIPE,NORTH,.05);
        w.runTo(643);w.place(0,-2,2,Kind.VOID,NORTH,passive,NITROGEN,.05);
        w.runTo(694);w.place(-3,0,1,Kind.RESERVOIR,NORTH,passive,NITROGEN,.05);
        w.runTo(1283);w.place(-2,0,2,Kind.PIPE,NORTH,.05);
        w.runTo(1283+120*100);w.assertKeptUp("a circulation between two risers");
    }

    // ---------------- TopologyEventFence ----------------
    /**
     * Hold class X (PHASE_PORTS_REVIEW.md 2026-09-27): {@code PhysicalRegistry.apply} threw "Topology event reached
     * boundary/filter ... outside the islands it fenced" on the server tick where an event's fence missed an island its
     * component walk reaches. Seed 28: a pipe placed beside a lone void mid-slice, the next pipe beside it a tick later;
     * the second event's walk stopped at the first pipe, a dead end no island owned when the first event applied.
     * Seed 234: two inline filters facing each other with no boundary, then a tank beside one of them; the tank's walk
     * stopped at the first filter, whose island does not hold the second (pipe blocks between, decision D20).
     */
    @Test void anEventBehindADeadEndPipeAQueuedEventPlacedIsFencedOnTheIslandBeyond() {
        var w=new EventPathWorld();
        // A tank a generator keeps filling, not the fuzz's lone void: since decision D17 a void stops an event's walk and is
        // fenced for no island, so it no longer holds the second event back; the flowing tank's island is mid-slice at 272.
        w.runTo(105);w.place(0,0,-3,Kind.GENERATOR,NORTH,new com.wormzjl.icheme.science.fluid.network.FlowControl.Passive(),EventPathWorld.pure(com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK,298.15,150000),.05);
        w.place(0,0,-2,Kind.PIPE,NORTH,.05);w.place(0,0,-1,Kind.RESERVOIR,NORTH,.05);
        w.runTo(272);w.place(1,0,-1,Kind.PIPE,NORTH,.1);
        w.runTo(273);w.place(2,0,-1,Kind.PIPE,NORTH,.1);
        w.runTo(1000);w.assertKeptUp("pipes beside a tank a tick apart");
    }
    @Test void aWalkGoesOnThroughAFilterInADormantIslandOfItsOwn() {
        var w=new EventPathWorld();
        // A pipe and a void past the second filter (decisions D18-D20): the chain is a dead end, each filter dormant, until
        // the tank gives it its other end; the tank's event must reach the second filter through the first.
        w.runTo(517);w.place(4,0,0,Kind.VOID,NORTH,.05);w.place(3,0,0,Kind.PIPE,NORTH,.05);w.place(2,0,0,Kind.FILTER,WEST,.05);w.place(1,0,0,Kind.PIPE,NORTH,.05);
        w.runTo(718);w.place(0,0,0,Kind.FILTER,EAST,.05);w.place(-1,0,0,Kind.PIPE,NORTH,.05);
        w.runTo(776);w.place(-2,0,0,Kind.RESERVOIR,NORTH,.05);
        w.runTo(1200);w.assertKeptUp("a tank beside a chain of two dormant filters");
    }

    // ---------------- TurbulentWalk ----------------
    /**
     * The turbulent walk on gas lines (B2, decision A371; PHASE_PORTS_REVIEW.md 2026-10-14): a chord step reuses a
     * Jacobian only while every connection's loss slope stays within a factor two of the slope that Jacobian states.
     * Random fuzz seed 498 minimized to 9 blocks (logs tw08), all placed at tick 100: a 480 kPa nitrogen generator feeds,
     * through two 1 cm pipes, a dead-end ladder of 1, 2 and 5 cm pipes that fills and then rests. The first solve starts
     * turbulent (Re up to 2e5) for a laminar root; the chord steps kept the resistance of flows up to twice as large,
     * closed only part of each gap (contractions 0.75 to 0.8) and the first step ran out of its 20 iterations at every step
     * size: held at tick 101 on "Newton iteration limit at residual 1.5497028030015203E-5" (logs tw09).
     */
    @Test void aGeneratorFillingADeadEndLadderOfGasLinesSettlesWithinTheNewton() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(100);
        w.place(0,3,-3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,272.72073259613035,480371.272983223),.01);
        w.place(0,3,-2,Kind.PIPE,NORTH,.01);
        w.place(0,1,0,Kind.PIPE,NORTH,.01);
        w.place(0,2,-1,Kind.PIPE,NORTH,.02);
        w.place(0,0,-1,Kind.PIPE,NORTH,.02);
        w.place(0,1,-1,Kind.PIPE,NORTH,.02);
        w.place(0,3,-1,Kind.PIPE,NORTH,.01);
        w.place(0,0,0,Kind.PIPE,NORTH,.05);
        w.place(0,2,0,Kind.PIPE,NORTH,.01);
        w.runTo(100+120*100);w.assertKeptUp("a generator filling a dead-end ladder of gas lines");
    }

    // ---------------- VanishingPhase ----------------
    /**
     * One vanishing rule for every node (owner decision D38 C, 2026-10-17; D38_REVERT_D36_AND_VANISHING_RULE.md): a phase
     * holding less than FluidThermodynamics.VANISHING_SHARE (1e-6) of its node's fluid moles, and a smaller share than
     * where the solve judging it started, leaves at a converged pass and at a failed iterate, in a junction as in a
     * vessel. Cold fuzz seed 30, pair-minimized to 11 blocks (M7_NEW_COLD_HOLDS_TRACE.md), on the cold pilot package: an
     * LNG generator (6 MPa, 169.7 K) pours about 72 kg/s into a junction (n5) that feeds a 1 atm void and, through 1 cm
     * lines, a 1 m3 tank; at tick 1593 a pipe joins a CO2-gas generator (2.03 MPa, 292 K) to the tank and that line. The
     * re-minted start states n5 flashed at 2.03 MPa with 4.5 % vapour that the solve must remove (n5 goes back to about
     * 3.49 MPa, all liquid); the rate solve's failed iterate left 1.0e-8 of its moles as vapour, above the trace share,
     * and the trial layout kept it: held at tick 1595 (class E5, a line-search stall at residual 1.47e-9).
     */
    @Test void aJunctionsVapourCollapsingInASubcooledLiquidLeavesAtAFailedIterate() {
        cold(w->{
            w.runTo(100);w.place(2,0,-1,Kind.PIPE,NORTH,.1);
            w.runTo(466);w.place(-2,0,0,Kind.RESERVOIR,NORTH,.05);
            w.runTo(470);w.place(0,0,0,Kind.GENERATOR,EAST,pilot(291.9601168397745,2031523.2199300588,0.009971399772847691,0.06435484220406093,0,0.9256737580230914,0),.01);
            w.place(0,0,-2,Kind.PIPE,NORTH,.05);w.place(-1,0,-1,Kind.PIPE,NORTH,.01);
            w.runTo(599);w.place(-2,0,-1,Kind.PIPE,NORTH,.01);
            w.runTo(647);w.place(1,0,-1,Kind.PIPE,NORTH,.1);
            w.runTo(696);w.place(2,1,-1,Kind.GENERATOR,NORTH,pilot(169.6783671876938,6000000.0,0.005965999241527276,0.9300951052162594,0.027900495572427266,0.03603839996978608,0),.05);
            w.runTo(971);w.place(0,0,-3,Kind.VOID,NORTH,.05);
            w.runTo(1283);w.place(0,0,-1,Kind.PIPE,NORTH,.05);
            w.runTo(1593);w.place(-1,0,0,Kind.PIPE,NORTH,.05);
            w.runTo(1593+5*100);
            for(var s:w.coordinator.snapshots())for(var node:s.graph().reservoirs())
                System.out.println("VANISHING_RULE island "+s.id()+" node "+node.id()+" "+node.kind()+" z="+node.elevation()+" P="+node.state().pressure()+" T="+node.state().temperature()
                        +" m="+node.state().mass()+" liq="+node.state().liquidVolume()+" vap="+node.state().vaporVolume()+" solidsKg="+node.state().solids().massKg());
            w.assertKeptUp("a junction's vapour collapsing in a subcooled liquid");
            // The junctions carry the LNG at 2.4 to 3.8 MPa and 168.5 to 169.1 K, above its bubble pressure: liquid only.
            for(var s:w.coordinator.snapshots())for(var node:s.graph().reservoirs())if(node.junction())
                assertEquals(0,node.state().vaporVolume(),"junction "+node.id()+" at "+node.state().pressure()+" Pa, "+node.state().temperature()+" K holds liquid only");
        });
    }

    // ---------------- VelocityCapMode ----------------
    /**
     * The velocity cap is an active-set mode of the connection (PHASE_PORTS_REVIEW.md 2026-10-13, card E6-C): a line at
     * its cap states the cap flow of the end it leaves, as a pump at its target states its flow, and the pass loop decides
     * when a line enters and leaves the mode. Card E6-C's world (random fuzz seed 529, 7 blocks, logs y19, y25): a
     * 496 kPa methane generator fills tank B (nitrogen, 441 kPa by tick 1157) through a junction and two pipes at their
     * cap, and the last block joins tank B to tank A (1 atm) through a 2 cm pipe. With the cap as a branch of the flow
     * row the junction's pressure jumped about 2e5 Pa per iteration and it held at tick 1159 on "Newton iteration limit
     * at residual 0.65" at every step size.
     */
    @Test void aJunctionBetweenALineAtItsCapAndItsOutletsFindsItsPressure() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(100);w.place(1,-1,-2,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(0,311.67397547863067,495884.91340665857),.1);
        w.runTo(113);w.place(0,-1,-2,Kind.PIPE,NORTH,.05);
        w.runTo(458);w.place(-2,0,-2,Kind.RESERVOIR,NORTH,.02);
        w.runTo(869);w.place(0,-1,-1,Kind.PIPE,NORTH,.05);w.place(0,0,-1,Kind.PIPE,NORTH,.05);
        w.runTo(1104);w.place(0,0,-2,Kind.RESERVOIR,NORTH,.05);
        w.runTo(1157);w.place(-1,0,-2,Kind.PIPE,NORTH,.02);
        w.runTo(1157+120*100);w.assertKeptUp("a junction between a capped inflow and its outlets");
    }

    // ---------------- VesselClosureEquilibrium ----------------
    /**
     * G2 follow-up (PHASE_PORTS_REVIEW.md 2026-10-12): every reconstruction closes each vessel to its booked inventory
     * (decision D36, InventoryEquilibrium.close) in the equilibrium the step solved it with, a trace component held in
     * one phase included. Closed in full equilibrium instead, a crude tank at rest stood 0.71 Pa (3.2e-6) above the
     * step's solution, the equation gate refused every reconstruction and the island held on "Interval substep limit".
     * Both worlds are the smallest of the random fuzz seeds that held this way (logs j01, j04), with the seeds' exact
     * doubles.
     */
    /**
     * Random seed 400, 3 blocks: a light crude generator (339.33 K, 220.17 kPa, liquid) feeds a 1 m3 nitrogen tank at
     * 1 atm through one 50 mm pipe at one height; the tank fills to the generator's pressure and rests 0.222 m3 liquid
     * (log j03). Before the fix the closure moved the resting tank 0.71 Pa up and the island held at tick 1289.
     */
    @Test void aCrudeTankAtRestIsClosedInTheStepsEquilibrium() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(762);
        w.place(0,2,0,Kind.RESERVOIR,NORTH,passive,NITROGEN,.1);w.place(0,2,1,Kind.PIPE,NORTH,.05);
        w.place(0,2,2,Kind.GENERATOR,NORTH,passive,crude(339.3308315223712,220170.99007955682),.05);
        w.runTo(762+40*100);w.assertKeptUp("a crude tank at rest");
    }

    /**
     * Random seed 737, 5 blocks: a junction pipe joins a liquid crude generator (299.34 K, 326.9 kPa, 10 mm), a two-phase
     * one (391.7 K, 248.8 kPa, 20 mm, which closes) and, one block up through a 100 mm pipe, a 1 m3 nitrogen tank that
     * rests 71 % liquid at 318.48 kPa. Before the fix the closure moved it 0.032 Pa up, no polish passed the gate (520 of
     * 520 refused) and the island held at tick 978.
     */
    @Test void aCrudeTankFedThroughAJunctionIsClosedInTheStepsEquilibrium() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(151);
        w.place(1,0,0,Kind.PIPE,NORTH,.05);
        w.place(1,0,1,Kind.GENERATOR,NORTH,passive,crude(391.7410790736319,248773.00966064972),.02);
        w.place(0,0,0,Kind.GENERATOR,NORTH,passive,crude(299.34412272252007,326916.0769202787),.01);
        w.place(1,1,0,Kind.PIPE,NORTH,.1);w.place(0,1,0,Kind.RESERVOIR,NORTH,passive,NITROGEN,.01);
        w.runTo(151+40*100);w.assertKeptUp("a crude tank fed through a junction");
    }

    /**
     * Owner decision of 2026-10-18 on t40's trace (D38 C amended): a shrinking phase is nothing only below VANISHING_SHARE
     * of its node's fluid both by moles and by volume. Bundled fuzz seed 407, pair-minimized to 3 blocks: a tank placed
     * with a 0.05 m pipe at tick 291, and at tick 1208 a crude generator (Tia Juana light with methane, 311.16 K,
     * 315.67 kPa) at the pipe's other end. The crude fills the tank; its nitrogen pad and the light ends dissolve as the
     * pressure rises, the last bubble (21 mL, 2.6e-7 of the moles) closes, and the liquid is pressed to the supply:
     * liquid-full at 316 kPa, 177 kPa above its bubble pressure, no flow. With the mole share alone the bubble was folded,
     * the stretched liquid stood 16 % under its bubble pressure, the converged point's check proposed the bubble back, and
     * the interval halved its step on every jump down to 1e-15 s: held at its 1024-attempt cap in [1435,1535] (class E8).
     */
    @Test void aCrudeGeneratorFillsATankToLiquidFullAndRests() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(291);
        w.place(1,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,0,Kind.RESERVOIR,WEST,.05);
        w.runTo(1208);
        w.place(2,0,0,Kind.GENERATOR,NORTH,passive,crude(311.1640418552006,315670.8797145338),.05);
        w.runTo(1208+30*100);w.assertKeptUp("a crude generator filling a tank to liquid-full");
        var s=w.coordinator.snapshots().stream().filter(sn->sn.graph().reservoirs().stream().anyMatch(r->r.kind()==PassiveNetwork.NodeKind.RESERVOIR)).findFirst().orElseThrow();
        var tank=s.graph().reservoirs().stream().filter(r->r.kind()==PassiveNetwork.NodeKind.RESERVOIR).findFirst().orElseThrow().state();
        var result=s.lastResult().orElseThrow();
        System.out.println("LIQUID_FULL_CRUDE status="+s.status()+" P="+tank.pressure()+" T="+tank.temperature()+" liqV="+tank.liquidVolume()+" vapV="+tank.vaporVolume()+" flows="+Arrays.toString(result.averageMassFlows()));
        assertEquals(0,tank.vaporVolume(),"liquid-full: no vapour");
        assertEquals(315670.8797145338,tank.pressure(),.01*315670.8797145338,"pressed to the supply");
        assertTrue(s.status().startsWith("STEADY"),"at rest: "+s.status());
    }

    /**
     * Owner decision of 2026-10-19 on t40's trace (bundled seed 678): a proposed phase that the node's own split holds below
     * VANISHING_SHARE of its fluid both by moles and by volume is refused, whatever its direction, and the two direction
     * tests of the D26 amendment go with it. Seed 678 pair-minimized to 9 blocks: crude at 310.37 K and 331.5 kPa joins at
     * tick 1700 through a 10 mm line into an empty tank at z=-2, whose bottom port drains three metres down a 50 mm line
     * to a second empty tank. The crude flashes in the upper slack bag at 1 atm; its saturated liquid lands in the lower
     * bag exactly at its bubble point, so no vapour forms there. The converged check proposed vapour anyway (the TP answer
     * on the bubble point holds some), the own split seeded 5.8e-11 m3, 6.5e-7 of the fluid volume, which the closure grew
     * slightly, so no direction test refused it; the pass laid out with it had no root (the ln(V/L) split a near-null
     * direction) and the line search stalled at every step size: held at tick 1704 (class E5). With the rule the island
     * keeps up; both tanks end liquid-full at the supply pressure plus their heads, with no flow.
     */
    @Test void aReceiverLandingOnItsBubblePointIsNotProposedAVapourBelowItsResolution() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(335);w.place(0,-4,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(336);w.place(0,-3,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(386);w.place(0,-1,-1,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(605);w.place(1,-5,-1,Kind.RESERVOIR,NORTH,.05);
        w.runTo(620);w.place(0,-5,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1218);w.place(1,-5,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1683);w.place(0,-2,0,Kind.RESERVOIR,NORTH,.01);
        w.runTo(1696);w.place(0,-1,-2,Kind.GENERATOR,NORTH,passive,crude(310.37149417674493,331510.7850719945),.1);
        w.runTo(1700);w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(1700+40*100);w.assertKeptUp("a receiver landing on its bubble point");
    }

    /**
     * Owner direction of 2026-10-19 on t48's trace (cold seed 850): a pipe at rest carries no solids, so it deposits
     * nothing. A roundoff flow at the interval's start (within the Newton's tolerance, {@code SolidEventIntegrator.still})
     * is no flow, and such a pipe keeps its state for the interval, its steps included (owner decision on t53's final test,
     * which replaced D42's resolution flow as the bound). Seed 850 pair-minimized to
     * 20 blocks: a CO2-rich gas generator (65 mol % CO2, 270 K, 2.83 MPa) and two LNG generators (128 K at 0.83 MPa, 152 K at
     * 2.06 MPa) share a 1 m3 tank. The gas sets 2.83 MPa, the tank fills with LNG at about 156 K and goes full, the LNG
     * generators are pushed back and nothing flows; the CO2 that met the cold liquid froze, 2.9 kg in the tank. At 3652 a
     * 400 kPa valve on a dead-end 10 mm line replaces the island with one of the same graph. The still 20 mm pipe from a
     * junction to the tank then carried 1.3e-23 m/s, and its sign picked the donor: the tank, holding solids, read DEPOSITION
     * against a 1.275 m/s suspension velocity. The intervals at 4279 and 5179 each took 40 solid-transport rejections, and at
     * 9079 the line search stalled after 162 active-set passes (E5). The hold itself rests on the roundoff history (the
     * placement fuzz holds this world, this transcription keeps up without the rule), so the gate reads the mechanism: from
     * the valve to 9652 the world without the rule refuses 1146 solid transitions, with it none, and it keeps up at rest.
     */
    @Test void aStillPipeBesideATankHoldingCrystalsDepositsNothing() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            w.runTo(121);w.place(3,0,2,Kind.PIPE,NORTH,passive,null,.01);w.place(4,-1,2,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(203);w.place(3,-1,3,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(597);w.place(-2,0,2,Kind.GENERATOR,NORTH,passive,pilot(128.4829346763447,827435.2919859318,0.008989418166292172,0.91427133230824,0.04173624669812814,0.03500300282733968,0),.1);
            w.runTo(645);w.place(0,0,2,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(704);w.place(0,0,0,Kind.RESERVOIR,WEST,passive,nitrogen,.05);
            w.runTo(1259);w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(1293);w.place(3,-1,4,Kind.GENERATOR,NORTH,passive,pilot(152.0591282854399,2054830.4045629618,0.0017950219332677254,0.9159935083362025,0.06778793175434208,0.014423537976187718,0),.05);
            w.runTo(1332);w.place(2,-1,2,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(1354);w.place(0,-1,2,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(1903);w.place(1,-1,2,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(1949);w.place(5,-1,1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(2087);w.place(3,-1,2,Kind.PIPE,NORTH,passive,null,.05);
            // The seed's pump hung DOWN below (0,-1,2), a dead end; an in-line mover lies on its side (batch
            // 2026-10-28-old-mover-removal), so an elbow takes its cell and the pump hangs off it, still discharging away.
            w.runTo(2449);w.place(0,-2,2,Kind.PIPE,NORTH,passive,null,.02);w.place(1,-2,2,Kind.PUMP,EAST,new FlowControl.Pump(.01,500000),null,.02);
            w.runTo(2641);w.place(5,-1,0,Kind.GENERATOR,NORTH,passive,pilot(269.91164644552856,2832869.246130703,0.1315093967488998,0.2208042977245737,0,0.6476863055265265,0),.01);
            w.runTo(2857);w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(3251);w.place(-1,0,2,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(3440);w.place(5,-1,2,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(3489);w.place(4,0,2,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(3652);w.place(3,0,3,Kind.VALVE,NORTH,new FlowControl.PressureValve(400000),null,.01);
            // Past the hold at 9079 to 9179, counting the solid transitions refused at rest.
            long transitions;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            try{w.runTo(3652+60*100);transitions=SolverDiagnostics.solidTransitionRejections.sum();}finally{SolverDiagnostics.ENABLED=false;}
            System.out.println("STILL_PIPE_GATE seed=850 solidTransitionRejections="+transitions);
            w.assertKeptUp("a still pipe beside a tank holding crystals");
            assertEquals(0,transitions,"a network at rest refuses no solid transition on its still pipes");
        });
    }

    /**
     * Owner decision of 2026-10-19 on t53's final test (cold seed 829): still is roundoff, decided once at the interval's
     * start. Seed 829 pair-minimized to 8 blocks: an LNG generator (126 K, 733 kPa) fills three 1 atm tanks through a 100 mm
     * line, and the CO2 it carries freezes in the tank above the junction (8.6 kg). At 796 a 10 mm pipe opens the junction
     * further, so that tank starts to drain down its 100 mm line at about 0.38 m/s (1.3 kg/s), below the suspension velocity:
     * the line deposits and closes, at the interval's start. D42's resolution flow, read as still before, is about 1 kg/s
     * in 1 m of 100 mm LNG line (it scales as d^4/mu): the draining flow sat at that threshold, the transition fired inside
     * the steps 14 times and the line search stalled (E5 at 799). A real flow is no roundoff, so the closure is declared at
     * the start and the island keeps up.
     */
    @Test void aTankDrainingDownAWideLineBelowItsSuspensionVelocityClosesItAtTheIntervalStart() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            w.runTo(100);w.place(-1,0,0,Kind.GENERATOR,NORTH,passive,pilot(125.76292312053825,732797.2457513055,0.0016664056166807318,0.9674404636551707,0.020971372577656206,0.009921758150492371,0),.02);
            w.runTo(103);w.place(0,1,0,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(341);w.place(-1,1,0,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(569);w.place(0,2,0,Kind.RESERVOIR,NORTH,passive,nitrogen,.1);
            w.runTo(618);w.place(1,0,0,Kind.RESERVOIR,NORTH,passive,nitrogen,.05);
            w.runTo(644);w.place(0,0,-2,Kind.RESERVOIR,NORTH,passive,nitrogen,.01);
            w.runTo(743);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(796);w.place(0,0,0,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(796+40*100);w.assertKeptUp("a tank draining down a wide line below its suspension velocity");
        });
    }

    /**
     * t56b's trace of the whole cold seed 743 world (22 blocks; HOLDS_PLAN.md section 2, round 2): a junction the balance puts
     * at a new pressure is stated as what its own row mixes there. In the interval from 13658 the carried plugs leave junction
     * 0 (2.25 kg of LNG and CO2 at 4.96 MPa, 200.4 K) with only its 485 kPa LNG supply line, which it stands above; the
     * balance's hull is that supply's pressure, so the junction is put there. Restated at its old temperature it was vapour at
     * 200.4 K (0.34 m3), holding about 1 MJ more than its buffer, and no step converged (E5 at 13758). Let down at its own
     * enthalpy it is a two-phase mixture at 165 K, as the real liquid flashes, and the island keeps up. With the rule off
     * (rule-off-row-seed.patch) it holds E5 at 13758.
     */
    @Test void aJunctionPutAtANewPressureIsStatedAsWhatItsRowMixes() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            // The seed's compressor hung DOWN below (1,0,0), a dead end; an in-line mover lies on its side (batch
            // 2026-10-28-old-mover-removal), so an elbow takes its cell and the compressor hangs off it, still discharging away.
            w.runTo(470);w.place(1,-1,0,Kind.PIPE,NORTH,passive,null,0.05);w.place(2,-1,0,Kind.COMPRESSOR,EAST,new FlowControl.Compressor(0.05,1.5),null,0.05);
            w.runTo(739);w.place(-3,0,0,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(753);w.place(-7,0,-3,Kind.VOID,NORTH,passive,nitrogen,0.05);
            w.runTo(784);w.place(-4,0,0,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(839);w.place(-2,0,0,Kind.RESERVOIR,NORTH,passive,nitrogen,0.01);
            w.runTo(840);w.place(-4,0,-3,Kind.VOID,NORTH,passive,nitrogen,0.01);
            w.runTo(894);w.place(-5,0,0,Kind.GENERATOR,NORTH,passive,pilot(119.80548180791456,485157.7267545908,0.0016846589022311764,0.8840774652856096,0.0764619183986177,0.03777595741354157,0.0),0.05);
            w.runTo(1222);w.place(-4,0,-1,Kind.PIPE,NORTH,passive,null,0.01);
            w.runTo(1247);w.place(2,0,0,Kind.VALVE,WEST,new FlowControl.PressureValve(200000.0),null,0.1);
            w.runTo(1547);w.place(-2,0,2,Kind.GENERATOR,NORTH,passive,pilot(275.3598487506343,3771940.112658649,0.0787727097062485,0.3660299987338409,0.0,0.5551972915599106,0.0),0.01);
            w.runTo(1548);w.place(1,0,0,Kind.PIPE,NORTH,passive,null,0.1);
            w.runTo(1548);w.place(-1,0,0,Kind.PIPE,NORTH,passive,null,0.01);
            w.runTo(1580);w.place(-6,0,-3,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(1738);w.place(-2,0,-3,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(1767);w.place(-2,0,1,Kind.PIPE,NORTH,passive,null,0.01);
            w.runTo(1775);w.place(-5,0,-3,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(2161);w.place(-3,0,-3,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(2179);w.place(1,0,2,Kind.GENERATOR,NORTH,passive,pilot(291.39197998306963,4956691.435211645,0.24867900979351562,0.01755247919259541,0.0,0.733768511013889,0.0),0.05);
            w.runTo(2198);w.place(-4,0,-2,Kind.PIPE,NORTH,passive,null,0.01);
            w.runTo(2567);w.place(-1,0,-3,Kind.VOID,NORTH,passive,nitrogen,0.05);
            w.runTo(2626);w.place(0,0,0,Kind.FILTER,WEST,passive,null,0.1);
            w.runTo(2631);w.place(1,0,1,Kind.PIPE,NORTH,passive,null,0.05);
            w.runTo(13758+10*100);w.assertKeptUp("a junction put at a new pressure, stated as what its row mixes");
        });
    }

    /**
     * Owner decision D62 of 2026-10-19 (batch 2026-10-20-crystal-junction-holds, D33 amended): a closure nothing moves
     * carries across an interval start, because what closed it is still in it. Cold seed 183 pair-minimized to 10 blocks:
     * an LNG generator (159 K, 4.07 MPa, 3.7 mol % CO2) fills a 1 atm tank through 10 to 50 mm lines; the tank drains to a
     * void through a 10 mm and a 100 mm line, and the CO2 the boiling liquid leaves freezes in it. Its 10 mm lines close
     * by DEPOSITION (the tank, holding crystals, is their donor, against 0.8 to 1.25 m/s suspension velocities) and end
     * their interval with no flow. D33 reopened them at the next start, and the steps stalled at a residual of 74 at
     * every step size (E5 at 2379). Now one carries where the interval-start rate solve leaves it roundoff-still, the
     * other where no rate solve exists and no mover draws through it, and the island keeps up. With the rule off
     * (rule-off-closure-carry.patch) it holds E5 at 2379.
     */
    @Test void aClosureNothingMovesCarriesAcrossTheIntervalStartInATankDrainingToAVoid() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            w.runTo(285);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(349);w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(682);w.place(0,0,-3,Kind.RESERVOIR,NORTH,passive,nitrogen,.02);
            w.runTo(997);w.place(0,-1,-2,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(1015);w.place(0,-2,-3,Kind.VOID,NORTH,passive,nitrogen,.1);
            w.runTo(1440);w.place(0,-2,-2,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(1809);w.place(0,-1,-3,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(1816);w.place(0,0,0,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(1852);w.place(0,0,2,Kind.GENERATOR,NORTH,passive,pilot(158.63533042151192,4070973.226775625,0.0026188357969988553,0.9141419986451405,0.0459783449996244,0.0372608205582363,0),.01);
            w.runTo(2348);w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(2348+40*100);w.assertKeptUp("a closure nothing moves in a tank draining to a void");
        });
    }
    /**
     * D62, cold seed 15's world, pair-minimized to 11 blocks by t51: an LNG generator (153 K, 2.75 MPa, 4.4 mol % CO2) fills three 1 atm tanks through a filter, a 200 kPa valve
     * and 10 to 20 mm lines to a void; the CO2 the cold liquid meets freezes in the tanks. Once they are full the lines
     * nearly rest. At 10839 + 4.5 s a line out of a tank holding crystals carried 0.038 m/s against a 1.2 m/s suspension
     * velocity: DEPOSITION closed it in its interval, and it ended with no flow. D33 reopened it at the next interval's
     * start; the port graph had no rate solve with it open, so nothing was declared, and the steps, started with it open,
     * stalled at 0.637 at every step size from 10939 (E5). The closure now carries while an end holds the crystals, and
     * the island keeps up. Since 235d69bb (still is roundoff) this world keeps up without the rule as well (23 refused
     * transitions either way): it stays as the regression of its old hold.
     */
    @Test void aClosureNothingMovesCarriesAcrossTheIntervalStart() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            w.runTo(492);w.place(0,0,2,Kind.RESERVOIR,NORTH,passive,nitrogen,.02);
            w.runTo(600);w.place(0,0,-2,Kind.VALVE,SOUTH,new FlowControl.PressureValve(200000),null,.01);
            w.runTo(880);w.place(0,1,-1,Kind.PIPE,NORTH,passive,null,.01);w.place(0,-1,-1,Kind.RESERVOIR,NORTH,passive,nitrogen,.01);
            w.runTo(1255);w.place(0,0,-3,Kind.PIPE,NORTH,passive,null,.01);w.place(0,0,0,Kind.FILTER,SOUTH,passive,null,.05);
            w.runTo(1262);w.place(0,-1,1,Kind.GENERATOR,NORTH,passive,pilot(153.4298831199327,2750494.941230718,0.006201944038199153,0.8887994010659398,0.06057615677002076,0.04442249812584026,0),.05);
            w.runTo(1338);w.place(0,2,-1,Kind.VOID,NORTH,passive,nitrogen,.01);
            w.runTo(1355);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(1410);w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(1412);w.place(0,0,-4,Kind.RESERVOIR,NORTH,passive,nitrogen,.01);
            // Past the hold from 10939 to 11539.
            w.runTo(1412+110*100);
            w.assertKeptUp("a closure nothing moves carries across the interval start");
        });
    }

    /**
     * t56's trace of cold seed 743 (batch 2026-10-20-crystal-junction-holds, HOLDS_PLAN.md section 2): the balance moves
     * only a junction it has not balanced. Seed 743 pair-minimized to 14 blocks: a 1 m3 tank of two-phase LNG (4.96 MPa,
     * 200.7 K, 37 kg of CO2 crystal) pressurised by a CO2 generator, over junction 0, which joins it (50 mm), a 10 mm vent
     * to air and an LNG supply line (120 K, 485 kPa). Each interval the reopened tank line and vent plug again within
     * milliseconds, and the interval's last segment starts with junction 0 sealed: liquid-full at 4.96 MPa between two
     * plugs and a supply it stands above, with no inflow at any pressure above 485 kPa. At 11358 the balance fell back to its
     * Gauss-Seidel sweep for the CO2 make-up line's junctions, and the sweep's bisection put junction 0 on the edge of its
     * flat range, at the supply's 485 kPa as vapour; no step converged from there (E5). A balanced junction now keeps its
     * pressure, and the island keeps up.
     */
    @Test void aSealedJunctionTheBalanceHasBalancedKeepsItsPressure() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            w.runTo(739);w.place(-3,0,0,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(784);w.place(-4,0,0,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(839);w.place(-2,0,0,Kind.RESERVOIR,NORTH,passive,nitrogen,.01);
            w.runTo(840);w.place(-4,0,-3,Kind.VOID,NORTH,passive,nitrogen,.01);
            w.runTo(894);w.place(-5,0,0,Kind.GENERATOR,NORTH,passive,pilot(119.80548180791456,485157.7267545908,0.0016846589022311764,0.8840774652856096,0.0764619183986177,0.03777595741354157,0),.05);
            w.runTo(1222);w.place(-4,0,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(1547);w.place(-2,0,2,Kind.GENERATOR,NORTH,passive,pilot(275.3598487506343,3771940.112658649,0.0787727097062485,0.3660299987338409,0,0.5551972915599106,0),.01);
            w.runTo(1548);w.place(1,0,0,Kind.PIPE,NORTH,passive,null,.1);w.place(-1,0,0,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(1767);w.place(-2,0,1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(2179);w.place(1,0,2,Kind.GENERATOR,NORTH,passive,pilot(291.39197998306963,4956691.435211645,0.24867900979351562,0.01755247919259541,0,0.733768511013889,0),.05);
            w.runTo(2198);w.place(-4,0,-2,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(2626);w.place(0,0,0,Kind.FILTER,WEST,passive,null,.1);
            w.runTo(2631);w.place(1,0,1,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(11358+10*100);w.assertKeptUp("a sealed junction the balance has balanced");
        });
    }

    /**
     * Owner decision D61 of 2026-10-19 (batch 2026-10-20-crystal-junction-holds, amends D23 for junctions): a crystal above
     * its melting point melts on arrival in a junction. Cold seed 778 pair-minimized to 7 blocks: a 1 m3 tank liquid-full of
     * LNG at 165 K and 4.16 MPa holding 19 kg of CO2 crystal stands on a junction through which a CO2 header (312 K,
     * 4.43 MPa) blows to air at its 100 m/s cap, 2.39 kg/s; the LNG generator (3.38 MPa) is below the tank. At rest the tank
     * gives nothing. A step's large-step root drains tank liquid into the 307 K junction, and its crystal share (5.3 %)
     * arrived as an inert solid 90 K above the CO2 melting point (about 217 K at 4.1 MPa): the reconstruction booked a crystal
     * rate the Newton's junction rows did not state, every step size failed the equation gate on the junction's solid and
     * temperature rows (E4 at 1185). The crystal now melts in the junction, booked as CO2 with the crystal's enthalpy, and
     * the island keeps up with the tank back at rest.
     */
    @Test void aCrystalArrivingInAWarmJunctionMeltsThere() {
        cold(w->{
            var passive=new FlowControl.Passive();var nitrogen=pilot(298.15,101325,1,0,0,0,0);
            w.runTo(100);w.place(-1,2,0,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(131);w.place(0,2,0,Kind.RESERVOIR,NORTH,passive,nitrogen,.02);
            w.runTo(153);w.place(0,1,-1,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(181);w.place(0,0,0,Kind.VOID,SOUTH,passive,nitrogen,.1);
            w.runTo(232);w.place(-2,2,0,Kind.GENERATOR,NORTH,passive,pilot(158.1143422191448,3382180.9834845206,0.0049772535753268695,0.9507968063578207,2.531343550757903E-5,0.04420062663134479,0),.05);
            w.runTo(435);w.place(0,1,0,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(458);w.place(0,1,-2,Kind.GENERATOR,NORTH,passive,pilot(311.8387507147899,4434735.037139751,0.07356367343142986,0.13346290931189256,0,0.7929734172566776,0),.05);
            w.runTo(458+40*100);w.assertKeptUp("a crystal arriving in a warm junction");
            // The tank is back at rest: its lines carry nothing over the last interval.
            for(var s:w.coordinator.snapshots()) {
                var g=s.graph();var flows=s.lastResult().map(r->r.averageMassFlows()).orElse(null);if(flows==null)continue;
                for(int e=0;e<g.pipes().size();e++){var p=g.pipes().get(e);
                    if(g.reservoirs().get(p.first()).kind()!=PassiveNetwork.NodeKind.RESERVOIR&&g.reservoirs().get(p.second()).kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                    System.out.println("MELT_GATE seed=778 tankLine="+e+" flow="+flows[e]);
                    assertTrue(Math.abs(flows[e])<1e-3,"the tank's line "+e+" is at rest: "+flows[e]+" kg/s");}
            }
        });
    }
}
