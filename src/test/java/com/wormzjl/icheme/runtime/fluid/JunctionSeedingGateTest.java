package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The junction seeding gates (owner decision of 2026-10-19, batch {@code documentation/2026-10-19-junction-seeding}): a
 * solve without a carried point starts from the island's quasi-steady balance ({@code PassiveStepSolver.balanceStart}),
 * every junction's net inflow zero under every connection's own law, saturated at the donor's cap. Each world below is
 * a traced placement-fuzz seed's world, the pair-minimal one where the seed held at {@code 32bf3fa4} (held there, clean
 * with the balance; {@code tools/junction-seeding/}), placed on the event path ({@link EventPathWorld}) and run to its
 * end tick. Rule-off proof: {@code tools/junction-seeding/patches/rule-off-balance.patch} (the old static start, no
 * balance) applied to a lane export fails each test (the README records the runs).
 */
class JunctionSeedingGateTest {
    private static final FluidDeviceSpec NITROGEN=EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325);
    /** Runs {@code body} on a world of the cold pilot package inside that catalog's context, as the cold placement fuzz
     * runs a seed; its specs are on the pilot basis [nitrogen, methane, ethane, CO2, water]. */
    private static void cold(java.util.function.Consumer<EventPathWorld> body) {
        var catalog=PilotCryogenicTestCatalog.catalog();var network=FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog,network,()->{body.accept(new EventPathWorld(catalog,FluidThermodynamics.forNetwork(catalog,network)));return null;});
    }
    /** A 1 m3 device's spec on the cold pilot basis: {@code moles} in basis order. */
    private static FluidDeviceSpec pilot(double temperature,double pressure,double... moles){return new FluidDeviceSpec(1,temperature,pressure,moles);}
    private static FluidDeviceSpec pilotNitrogen(){return pilot(298.15,101325,1,0,0,0,0);}
    /** Pure methane on the bundled network basis (its first component), a 1 m3 generator's spec. */
    private static FluidDeviceSpec methane(double temperature,double pressure) {
        double[] n=new double[FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition().length];n[0]=1;
        return new FluidDeviceSpec(1,temperature,pressure,n);
    }

    /**
     * Cold fuzz seed 15's world (13 blocks; {@code FORM_D3C_COLD_SEEDS_TRACE.md}, {@code START_MACHINERY_MAP.md}): an
     * LNG generator (153 K, 2.75 MPa, 89 % methane) joins a 1 atm network of empty tanks, filters, a 200 kPa valve and
     * 10 to 20 mm lines. Subcooled LNG floods the network: the 20 mm supply line runs at its cap (13 kg/s) while the
     * junction's outlets pass about 2 kg/s, so the junction stands just under the generator, and no junction can stand
     * above it. The old start minted the junction at 412 kPa with the supply line at its cap; the first Newton iterates
     * put junctions at 1.9 to 6.6 MPa, above the source, and the completed network's first interval refused six Newton
     * attempts before a step of 0.39 ms converged by luck (every raised cap tipped it into a hold). Balanced, the junction
     * starts at the pressure its outlets set and the first interval accepts its third attempt, 12.5 ms, after two refused.
     */
    @Test void aJunctionMintedUnderAnLngGeneratorStartsBalancedAndItsFirstIntervalKeepsATenMillisecondStep() {
        cold(w->{
            var passive=new FlowControl.Passive();
            w.runTo(492);w.place(0,0,2,Kind.RESERVOIR,NORTH,passive,pilotNitrogen(),.02);
            w.runTo(600);w.place(0,0,-2,Kind.VALVE,SOUTH,new FlowControl.PressureValve(200000),null,.01);
            w.runTo(880);w.place(0,1,-1,Kind.PIPE,NORTH,passive,null,.01);w.place(0,-1,-1,Kind.RESERVOIR,NORTH,passive,pilotNitrogen(),.01);
            w.runTo(1255);w.place(0,0,-3,Kind.PIPE,NORTH,passive,null,.01);w.place(0,0,0,Kind.FILTER,SOUTH,passive,null,.05);w.place(2,0,-1,Kind.FILTER,WEST,passive,null,.1);
            w.runTo(1262);
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;long[] refused=new long[5];int[] marks=new int[5];java.util.List<SolverDiagnostics.Attempt> list;
            try {
                w.place(0,-1,1,Kind.GENERATOR,NORTH,passive,pilot(153.4298831199327,2750494.941230718,0.006201944038199153,0.8887994010659398,0.06057615677002076,0.04442249812584026,0),.05);
                w.runTo(1338);marks[0]=SolverDiagnostics.attempts().size();w.place(0,2,-1,Kind.VOID,NORTH,passive,pilotNitrogen(),.01);w.place(1,0,-1,Kind.PIPE,NORTH,passive,null,.1);
                w.runTo(1355);marks[1]=SolverDiagnostics.attempts().size();w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
                w.runTo(1410);marks[2]=SolverDiagnostics.attempts().size();w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.02);
                w.runTo(1412);marks[3]=SolverDiagnostics.attempts().size();w.place(0,0,-4,Kind.RESERVOIR,NORTH,passive,pilotNitrogen(),.01);
                w.runTo(1412+40*100);list=SolverDiagnostics.attempts();marks[4]=list.size();
            } finally{SolverDiagnostics.ENABLED=false;}
            // Newton-refused attempts per window: after the generator (to 1338), to 1355, to 1410, to 1412, and the completed network's 40 intervals.
            for(int k=0,from=0;k<5;from=marks[k++])refused[k]=list.subList(from,marks[k]).stream().filter(a->!a.accepted()&&a.dominant().startsWith("newton")).count();
            // The completed network's first interval: the Newton attempts refused before its first accepted step.
            long first=0;double firstStep=0;var sb=new StringBuilder();
            for(var a:list.subList(marks[3],marks[4])){sb.append(a.accepted()?" ok":" "+a.dominant().replaceAll("\\|.*","")).append("@").append(a.step());if(a.accepted()){firstStep=a.step();break;}if(a.dominant().startsWith("newton"))first++;}
            System.out.println("JUNCTION_SEEDING_GATE seed=15 attempts="+list.size()+" newtonRefused="+java.util.Arrays.toString(refused)+" firstInterval="+first+" firstStep="+firstStep+" sequence="+sb);
            w.assertKeptUp("a junction minted under an LNG generator");
            // A start within the junctions' own settling (milliseconds) lets the first step stand at the 10 ms scale: balanced, the
            // controller's third attempt (12.5 ms) converges after two refused; the old start refused six and accepted 0.39 ms.
            assertTrue(firstStep>=.01,"the completed network's first interval accepts a step of at least 10 ms from its balanced start, not "+firstStep+" s after "+first+" refused Newton attempts");
        });
    }

    /**
     * Bundled fuzz seed 988's smallest world (11 blocks; {@code BUNDLED_HOLDS_TRACE.md}, t40): a 261 K, 148 kPa methane
     * generator pushes through a run of three 50 mm links and a filter into a tank and 1 atm lines. The old static
     * estimate contracted the run to one law flow on the run's whole drive, 0.052 kg/s, 1.5 to 1.65 times each link's
     * own cap, with the junctions 30 kPa off balance; the Newton spent 23 iterations crossing the cap law's knee and
     * crawled on a stale chord to the limit of 30, and the island held (E6). Balanced, each link starts at its own law
     * on balanced ends (0.034 and 0.037 kg/s and the bottleneck's) and the first interval converges in 56 iterations.
     */
    @Test void aRunOfThreeLinksPastItsCapsStartsAtEachLinksOwnLawAndKeepsUp() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(452);w.place(0,3,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(716);w.place(0,0,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(969);w.place(3,0,-1,Kind.PIPE,NORTH,passive,null,.05);w.place(0,2,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1118);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(1163);w.place(3,-1,-1,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(1576);w.place(0,1,0,Kind.FILTER,DOWN,passive,null,.02);w.place(0,4,0,Kind.RESERVOIR,NORTH,passive,NITROGEN,.05);
        w.place(3,-2,-1,Kind.GENERATOR,NORTH,passive,methane(260.81938174293856,148289.56081187422),.1);
        w.runTo(1677);w.place(1,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1944);w.place(2,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1944+40*100);w.assertKeptUp("a run of three links past its caps");
    }

    /**
     * Cold fuzz seed 19's smallest world (6 blocks; t38's form A trace, {@code START_MACHINERY_MAP.md}): a 300 K,
     * 5.4 MPa CO2-rich gas generator (81 % CO2, 15 % nitrogen) stands on four 50 to 100 mm lines that a 1 atm void joins
     * last, so every line of the rearranged network runs choked at its 100 m/s cap at once. The old start put the
     * junctions near the generator with every line at its cap, and the first pass stalled in its line search at a
     * residual of 961 (E5). Balanced, the junctions start where the choked lines' caps balance and the network keeps up.
     */
    @Test void anAllChokedVentNetworkRearrangedUnderAHighPressureGasGeneratorKeepsUp() {
        cold(w->{
            var passive=new FlowControl.Passive();
            w.runTo(977);w.place(1,-1,0,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(1192);w.place(0,-1,0,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(1445);w.place(1,-1,-1,Kind.PIPE,NORTH,passive,null,.05);
            w.runTo(1941);w.place(0,-1,-1,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(2612);w.place(2,-1,-1,Kind.GENERATOR,NORTH,passive,pilot(300.06930349496366,5409675.949934312,0.15249057355721887,0.033572557411243056,0,0.8139368690315381,0),.05);
            w.runTo(2824);w.place(0,0,0,Kind.VOID,EAST,passive,pilotNitrogen(),.02);
            w.runTo(2824+40*100);w.assertKeptUp("an all-choked vent network under a 5.4 MPa gas generator");
        });
    }

    /**
     * Bundled fuzz seed 483's smallest world (11 blocks; t46's trace in {@code FINAL_TEST_REVIEW.md}): a water generator
     * (321 kPa) delivers 6.8 kg/s through 20 mm, 3.0 kg/s into a tank and 3.9 kg/s into a 100 mm vent header; a methane
     * generator (294 kPa, 392 K) delivers 1.14 kg/s at its cap, half into the tank and half into the header, which vents
     * 4.4 kg/s of a 5.7 kg/m3 two-phase mixture at 127.5 kPa. At tick 1068 a filter is placed on a dead-end line beyond the
     * second tank: the header's island is recompiled with its ten edges unchanged. Re-minted, the balance's second
     * composition round re-mixed the header from its water line alone (992.6 kg/m3, its vent cap 3 to 780 kg/s) and
     * pulled it from 127 to 101 kPa; the rate seed failed and pass 0 stalled at |r| 102 (E5). A plant would not notice
     * the filter: the replaced island carries its committed junctions and endpoint, and a composition round that
     * reverses an edge is not kept.
     */
    @Test void aFilterPlacedBeyondATankLeavesAnUnchangedVentHeaderItsState() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var water=EventPathWorld.pure(MaterialTestBasis.NETWORK,312.1569321967112,320635.26861555193);
        w.runTo(100);w.place(-2,0,-2,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(131);w.place(-3,0,-2,Kind.VOID,NORTH,passive,NITROGEN,.1);
        w.runTo(172);w.place(-2,0,-1,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(612);w.place(-1,0,-2,Kind.PIPE,NORTH,passive,null,.1);
        w.runTo(782);w.place(-3,0,-1,Kind.GENERATOR,NORTH,passive,water,.02);
        w.runTo(928);w.place(-1,0,-1,Kind.RESERVOIR,NORTH,passive,NITROGEN,.05);
        w.runTo(970);w.place(0,0,0,Kind.RESERVOIR,NORTH,passive,NITROGEN,.02);
        w.runTo(1030);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(1039);w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.05);w.place(0,0,-2,Kind.GENERATOR,NORTH,passive,methane(392.11898966991953,294078.3425857688),.01);
        w.runTo(1068);w.place(-1,0,1,Kind.FILTER,WEST,passive,null,.1);
        w.runTo(1068+40*100);w.assertKeptUp("a vent header whose island a filter beyond its tank recompiled unchanged");
    }

    /**
     * Cold fuzz seed 271's smallest world (10 blocks; t46's trace in {@code FINAL_TEST_REVIEW.md}): a CO2-rich gas generator
     * (1.13 MPa, 270 K) feeds a 20 mm line choked at 0.685 kg/s and a 10 mm riser venting 0.137 kg/s; the 200 kPa valve
     * placed at tick 1852 is a back-pressure valve into a 1 atm tank. It opens, holds its inlet at 200 kPa and passes
     * 0.548 kg/s. The mint's balance had left the inlet at the tank's 100.9 kPa, so the valve started CLOSED, the closed
     * network balanced at 1 125 kPa, and pass 1 switched the valve to REGULATING 925 kPa above its anchor: singular at
     * every step size (E2). The balance now decides the valve's mode on its balanced pressures, as it does a pump's.
     */
    @Test void aBackPressureValvePlacedUnderAChokedGasSupplyOpensAtTheStart() {
        cold(w->{
            var passive=new FlowControl.Passive();
            w.runTo(148);w.place(0,1,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.runTo(182);w.place(0,0,-2,Kind.GENERATOR,NORTH,passive,pilot(269.81060085426174,1129422.6360429446,0.045457615852132614,0.11138617765944389,0,0.8431562064884235,0),.02);
            w.place(0,1,2,Kind.PIPE,NORTH,passive,null,.02);w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(502);w.place(0,1,3,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(1380);w.place(0,0,2,Kind.PIPE,NORTH,passive,null,.1);
            w.runTo(1385);w.place(0,0,3,Kind.RESERVOIR,NORTH,passive,pilotNitrogen(),.1);
            w.runTo(1391);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.02);
            w.runTo(1573);w.place(0,2,-1,Kind.VOID,NORTH,passive,pilotNitrogen(),.01);
            w.runTo(1852);w.place(0,0,0,Kind.VALVE,SOUTH,new FlowControl.PressureValve(200000),null,.1);
            w.runTo(1852+40*100);w.assertKeptUp("a back-pressure valve placed under a choked gas supply");
        });
    }

    /**
     * Bundled fuzz seed 145's smallest world (16 blocks; t48's trace in {@code FINAL_TEST_REVIEW.md}): a water generator
     * (354 K, 374 kPa) placed at tick 2114 fills a tank at 51.5 kg/s while 21.5 kg/s drain through a 50 mm bottom line
     * into a 1 atm void; a second tank beyond a closed 150 kPa valve stays empty. The island is minted before water reaches
     * the drain's junction, so that junction holds the nitrogen placeholder and its buffer is sized on gas, 0.011 kg. The
     * placements at 2152, 2164 (20 mm pipes on a dead-end line) and 2440 (a filter below the second tank) replace the island
     * with one of the same graph, which carries its junctions. Carried with their old buffers, the drain's junction kept
     * 0.011 kg while 21.5 kg/s of water passed (a 0.5 ms mixing lag where the mint's rule gives 0.44 s), each interval cost
     * 2 to 3.5 times the work and at 3767 the step failed 19 times on the junction inventory (E9). Sized on its carried
     * water by the mint's rule, the buffer holds 9.5 kg and the island keeps up.
     *
     * <p>Since rule OD4 (batch 2026-10-15-custom-equipment-rendering: an actuator's half links take the bore of the pipe
     * they join) the valve is 50 mm, not its own 10 mm, so the second tank fills too and then drains through the valve: at
     * the end 46.0 kg/s pass, 33.5 kg/s by the drain and 12.56 kg/s by the valve, wide open (154.6 kPa upstream, above
     * its 150 kPa set point), and the drain's junction holds 9.54 kg. When the second tank turns liquid-full at 3767, the
     * valve's junction still holds the placeholder gas, and its static column decides whether it stands above or below
     * the set point; the cycle key carries the columns (PassiveStepSolver.PassKey), else the island held E7 at 3867.
     */
    @Test void aJunctionCarriedAcrossAReplacementHasItsBufferSizedOnWhatItHolds() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(137);w.place(0,2,2,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(323);w.place(0,2,0,Kind.RESERVOIR,NORTH,passive,NITROGEN,.05);w.place(1,1,0,Kind.VALVE,WEST,new FlowControl.PressureValve(150000),null,.01);
        w.runTo(462);w.place(2,2,0,Kind.RESERVOIR,NORTH,passive,NITROGEN,.05);w.place(-2,2,0,Kind.PIPE,NORTH,passive,null,.05);w.place(-1,2,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(646);w.place(0,2,1,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(1041);w.place(0,2,4,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(1206);w.place(2,1,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1273);w.place(0,1,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1572);w.place(1,2,0,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(1945);w.place(0,0,0,Kind.VOID,NORTH,passive,NITROGEN,.05);
        w.runTo(2114);w.place(-3,2,0,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,353.7140998874968,374290.4801779348),.05);
        w.runTo(2152);w.place(0,2,3,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(2164);w.place(0,2,5,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(2440);w.place(3,1,0,Kind.FILTER,EAST,passive,null,.05);
        w.runTo(2440+40*100);w.assertKeptUp("a drain junction carried across three same-graph replacements");
    }

    /**
     * The water the junction buffers create, unbooked (owner decision D46 and its watch item, closed 2026-10-19 on t52's
     * D46_MINT_MASS_REVIEW.md): a buffer is the state of its junction's relaxation row, held at its mass m_J by the mass
     * pin, so total mass is exact, but each buffer life puts {@code m_J (w_install - w_end)} of composition into the vessels
     * unbooked: what it was minted with and what washes it at the end. Read as minus the sum of every committed change of
     * the buffers (the ledger's junction term), per buffer life; a replacement installs a new life, which creates nothing.
     * Bounded by physics: a buffer whose water falls gives up at most the water it was minted with, {@code m_J w_install}.
     * Bundled fuzz seed 188's world (21 blocks, t52's gate world): at 1740 a 20 mm junction is minted as liquid water from a
     * 271 kPa water generator, m_J = 0.05 s x 100 m/s x 994 kg/m3 x 3.1e-4 m2 = 1.56 kg; at 1782 a 332 kPa nitrogen generator
     * takes the line over and washes it for the rest of the run, so about 1.3 kg of water reach a 40.7 kg tank unbooked (a
     * real 20 mm fitting holds 0.31 kg and passes it on booked).
     */
    @Test void aBufferMintedOnWaterAndWashedByNitrogenCreatesNoMoreWaterThanItWasMintedWith() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();var buffers=new BufferWater(w);
        var water=EventPathWorld.pure(MaterialTestBasis.NETWORK,308.7667244941392,270611.50577975024);
        buffers.runTo(148);w.place(0,0,0,Kind.VALVE,SOUTH,new FlowControl.PressureValve(200000),null,.05);w.place(-2,0,-1,Kind.GENERATOR,NORTH,passive,water,.02);
        buffers.runTo(430);w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.05);w.place(-1,-3,-4,Kind.PIPE,NORTH,passive,null,.01);
        buffers.runTo(607);w.place(-1,-4,-1,Kind.PIPE,NORTH,passive,null,.05);
        buffers.runTo(857);w.place(0,1,-1,Kind.PIPE,NORTH,passive,null,.05);
        buffers.runTo(900);w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        buffers.runTo(1071);w.place(-1,0,-1,Kind.PIPE,NORTH,passive,null,.02);
        buffers.runTo(1079);w.place(-1,-3,1,Kind.COMPRESSOR,NORTH,new FlowControl.Compressor(.2,6),null,.02);w.place(-1,-3,-3,Kind.PIPE,NORTH,passive,null,.01);
        buffers.runTo(1232);w.place(-1,-2,-1,Kind.PIPE,NORTH,passive,null,.05);
        buffers.runTo(1302);w.place(-1,-3,-1,Kind.PIPE,NORTH,passive,null,.05);w.place(0,0,-3,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.05);
        buffers.runTo(1336);w.place(-1,-1,-1,Kind.PIPE,NORTH,passive,null,.05);
        buffers.runTo(1372);w.place(-1,-3,0,Kind.PIPE,NORTH,passive,null,.02);
        buffers.runTo(1680);w.place(-1,-3,-2,Kind.PIPE,NORTH,passive,null,.01);
        buffers.runTo(1740);w.place(0,-1,-2,Kind.PIPE,NORTH,passive,null,.05);w.place(0,-1,-1,Kind.RESERVOIR,NORTH,passive,NITROGEN,.02);
        w.place(0,2,-1,Kind.PIPE,NORTH,passive,null,.05);w.place(-1,-5,-1,Kind.PIPE,NORTH,passive,null,.05);
        buffers.runTo(1782);w.place(0,-1,-3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,354.1428219629714,332374.5805774033),.05);
        buffers.runTo(1782+120*100);
        System.out.printf(java.util.Locale.ROOT,"D46_GATE seed=188 createdWaterKg=%.6f washedBufferWaterKg=%.6f lives=%d%n",buffers.created,buffers.washed(),buffers.lives.size());
        w.assertKeptUp("a water-minted buffer washed by nitrogen");
        assertTrue(buffers.created>0,"the washed buffer gives up water unbooked: "+buffers.created+" kg");
        assertTrue(buffers.created<=buffers.washed(),"the washed buffers give up at most the water they were minted with, m_J w_install: "+buffers.created+" kg of "+buffers.washed()+" kg");
    }

    /** The water the junction buffers of a world create unbooked, read from the islands' committed graphs after every tick and
     * between a tick's topology events and its solves (a placed island's first solve runs in the tick it is installed):
     * per buffer life (a junction id within one island), minus the sum of its committed changes, and the water it was
     * minted with; {@link #washed} is the physical bound, the water minted into the buffers whose water fell: what they can
     * give up at most, {@code m_J w_install}. */
    private static final class BufferWater {
        final EventPathWorld w;final java.util.Map<Long,long[]> life=new java.util.HashMap<>();final java.util.List<double[]> lives=new java.util.ArrayList<>();
        double created;
        BufferWater(EventPathWorld w){this.w=w;w.beforeSolves=this::read;}
        void runTo(long tick){while(w.ledger.onlineTick()<tick){w.tick();read();}}
        void read() {
            for(var s:w.coordinator.snapshots())for(var node:s.graph().reservoirs()) {
                if(!node.junction())continue;var n=node.inventory().moles();double kg=n[n.length-1]*w.model.waterMolecularWeight;
                var owner=life.get(node.id());
                // A junction id in another island is a new buffer life (a placement replaced the island): minted, not committed.
                if(owner==null||owner[0]!=s.id()){life.put(node.id(),new long[]{s.id(),lives.size()});lives.add(new double[]{kg,kg});continue;}
                var buffer=lives.get((int)owner[1]);created+=buffer[1]-kg;buffer[1]=kg;
            }
        }
        /** The water minted into every buffer life whose water fell (installed, then current). */
        double washed(){double kg=0;for(var b:lives)if(b[1]<b[0])kg+=b[0];return kg;}
    }
}
