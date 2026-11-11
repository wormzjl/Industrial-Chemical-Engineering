package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.InletPhase;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * Owner decision D59 (4) of the phase-ports batch (A335): a pump cannot pull its suction below the vapour pressure of the
 * liquid it draws. There it cavitates and delivers only what its suction line gives with the suction standing at
 * {@code P_vapour(T)}: the row {@code P_suction = P_vapour(T_suction)} replaces the target-flow row while the target would
 * pull the suction below it, as the head limit replaces it while the target would need more head than the pump has.
 *
 * <p>Restated for owner decision D20 Q11 A (batch 2026-11-03-rotating-machinery, W10a): the floor is the liquid's
 * vapour or bubble pressure plus the pump's breakdown NPSH at its flow, {@code 0.85 ρ_l g NPSH3(Q, s)}; an in-line pump's
 * NPSH3 design is its set flow at 2950 rpm (owner decision D19). Each suction that stood at the vapour or bubble pressure
 * now stands that far above it ({@link #breakdown}), the pump delivering less.
 */
class PumpCavitationTest {
    /** Owner decision D20 Q11 A: how far above the floor of the liquid it draws the suction of pump connection {@code pump}
     * stands at its committed mass flow {@code massFlow} (kg/s): {@code 0.85 ρ_l g NPSH3(Q)} on its design
     * ({@link FlowControl.Mover#npsh}), its suction junction's volume flow and the liquid its supply delivers. */
    private static double breakdown(FluidThermodynamics model,PassiveNetwork graph,int pump,double massFlow) {
        var mover=(FlowControl.Mover)graph.pipes().get(pump).control();var suction=graph.reservoirs().get(graph.pipes().get(pump).first()).state();
        return mover.npsh().breakdownPressure(massFlow/(suction.mass()/suction.volume()),InletPhase.liquidDensity(model,graph,pump,mover.targetVolumeFlow(),5));
    }
    /**
     * The smallest world of world-defaults fuzz seed 696 (11 blocks, all placed at tick 143; minimized in
     * tools/phase-ports-probes/hold-classes logs cc04). A 1 atm water generator 2 m above the pump's suction feeds it through
     * a vertical line; the pump discharges towards a second 1 atm generator and a placed tank 3 m below. The head-limit trial
     * pulls the suction junction to the data's 100 Pa floor, where the water flashes to its triple point: without the suction
     * limit the island held at tick 1570 (water at 273.1599 K at the suction junction, the ice hold of the time; since WP4 of
     * the range-extrapolation plan such a flash freezes, 83 % ice and 17 % steam at 252.8 K from 25 °C at 100 Pa,
     * FreeWaterIceTest, which a step carries as the stand-in, supercooled liquid, until a vessel's refresh freezes it). With
     * it the failed trial puts the pump
     * on its suction limit, that pass converges with the suction line giving more than the target (22.5 kg/s), the pump
     * returns to its target and then its head limit as the full tank's pressure rises, and stands at its shutoff once the
     * tank is pressed to it; the island keeps up for 120 slices.
     */
    @Test void aHeadLimitTrialBelowTheVapourPressureIsSolvedOnTheSuctionLimit() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        var water=EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325);
        w.runTo(143);
        w.place(0,0,-3,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,-3,1,Kind.RESERVOIR,NORTH,.05);
        w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,2,-3,Kind.GENERATOR,NORTH,passive,water,.05);
        w.place(0,1,-3,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,-1,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,-2,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,0,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.05);
        w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,2,Kind.GENERATOR,NORTH,passive,water,.05);
        w.runTo(143+120*100);w.assertKeptUp("a head-limit trial below the vapour pressure");
        for(var s:w.coordinator.snapshots()) {
            var result=s.lastResult().orElseThrow();
            System.out.println("PUMP_CAVITATION_696 island "+s.id()+" modes="+result.endpointModes()+" flows="+Arrays.toString(result.averageMassFlows()));
            // At its shutoff a pump runs on its law with no net flow, its relief passing its whole displacement back (batch
            // 2026-11-03-rotating-machinery, form F2e), or stands closed.
            for(int e=0;e<s.graph().pipes().size();e++)if(s.graph().pipes().get(e).control() instanceof FlowControl.Pump) {
                var mode=result.endpointModes().get(e);
                assertTrue(mode==FlowControl.Mode.MOVER_LAW||mode==FlowControl.Mode.CLOSED,"the pump stands at its shutoff against the full tank: "+mode);
                assertEquals(0,result.averageMassFlows()[e],1e-6,"with no net flow");
            }
        }
    }

    /**
     * Cavitation as the steady state: a 1 atm water generator feeds a pump through two 1 m pipes of 30 mm bore, and the pump
     * discharges into a 1 atm void. The target, 0.01 m3/s (10 kg/s), would need about 134 kPa of suction-line loss, more than
     * the 98 kPa between the generator and the vapour pressure of water at 298 K (3.17 kPa): the pump stands on its suction
     * limit, its suction junction at the vapour pressure, and delivers what the line gives there, below its target.
     */
    @Test void aPumpWhoseSuctionLineCannotFeedItsTargetStandsOnItsSuctionLimit() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(100);
        w.place(0,0,-3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325),.05);
        w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.03);
        w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.03);
        w.place(0,0,0,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.05);
        w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,2,Kind.VOID,NORTH,passive,null,.05);
        w.runTo(100+20*100);w.assertKeptUp("a pump on a starved suction");
        var s=w.coordinator.snapshots().getFirst();var result=s.lastResult().orElseThrow();var graph=s.graph();
        int pump=-1;for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.Pump)pump=e;
        var suction=graph.reservoirs().get(graph.pipes().get(pump).first()).state();
        double vapour=w.model.saturationPressure(suction.temperature()),q=result.averageMassFlows()[pump],floor=vapour+breakdown(w.model,graph,pump,q);
        System.out.println("PUMP_CAVITATION_STEADY modes="+result.endpointModes()+" flows="+Arrays.toString(result.averageMassFlows())
                +" suctionP="+suction.pressure()+" vapourP="+vapour+" floor="+floor);
        assertEquals(FlowControl.Mode.PUMP_SUCTION_LIMIT,result.endpointModes().get(pump),"the pump cavitates");
        assertEquals(floor,suction.pressure(),1e-3*floor,"its suction stands at the vapour pressure plus its breakdown NPSH");
        assertTrue(q>0&&q<.01*suction.mass()/suction.volume(),"it delivers what the line gives, below its target: "+q);
    }

    /**
     * Owner decision on the t38 plan (2026-10-18, K6): a pump's suction limit reads the liquid it draws, and a two-phase
     * supplier's own pressure is its bubble pressure (no new thermodynamics). From cold fuzz seed 397's world: its LNG
     * (111.66 K, 89 % methane with ethane and CO2) boils at about 99 kPa; the seed's generator stood subcooled at 222 kPa,
     * and the pump [0.01 m3/s, 500 kPa] pulled the suction junction of its 10 mm line to 40 kPa, where the liquid flashed
     * and the two-phase junction's pass stalled (since owner decision D14 the subcooled case below holds the same floor). Here
     * the same LNG at 111.8 K fills a tank from a generator at 222 kPa and boils there: the tank fills to its wall and
     * stands two-phase at about 128 kPa and 115 K, two metres above the pump, which draws through the tank's bottom port
     * (liquid, so the pump is not refused). The available head is the column and the level head alone, the target would
     * need far more line loss, so the pump stands on its suction limit with the junction at the tank's pressure and
     * delivers what that head pushes through two metres of 10 mm line (0.086 kg/s against a target of 4.2 kg/s). Without
     * the rule (the water-only limit of A390) the junction is pulled below the bubble pressure and flashes: the island
     * holds at tick 1327 on a line-search stall.
     */
    @Test void anLngSupplierAtItsBubblePointHoldsItsPumpOnTheSuctionLimit() {
        var catalog=PilotCryogenicTestCatalog.catalog();var network=FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog,network,()->{
            var w=new EventPathWorld(catalog,FluidThermodynamics.forNetwork(catalog,network));var passive=new FlowControl.Passive();
            // Seed 397's LNG on the pilot basis [nitrogen, methane, ethane, CO2, water] at 111.8 K: subcooled in the generator
            // at 222 kPa, boiling in the tank. A placed tank holds nothing (owner direction D52): it fills from the generator
            // through its side, and the pump draws from its bottom port.
            var lng=new FluidDeviceSpec(1,111.8,222268.7652191971,new double[]{0.0019051322577280617,0.8927278421557977,0.06093423437004952,0.04443279121642472,0});
            w.runTo(100);
            w.place(2,2,-1,Kind.GENERATOR,NORTH,passive,lng,.01);
            w.place(1,2,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,2,-1,Kind.RESERVOIR,NORTH,.01);
            w.place(0,1,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,0,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.01);
            w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,2,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,new double[]{1,0,0,0,0}),.01);
            w.runTo(100+20*100);w.assertKeptUp("an LNG supplier at its bubble point feeding a pump");
            var s=w.coordinator.snapshots().stream().filter(sn->sn.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.Pump)).findFirst().orElseThrow();
            var result=s.lastResult().orElseThrow();var graph=s.graph();
            int pump=-1;for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.Pump)pump=e;
            var tank=graph.reservoirs().stream().filter(r->r.kind()==PassiveNetwork.NodeKind.RESERVOIR).findFirst().orElseThrow().state();
            var suction=graph.reservoirs().get(graph.pipes().get(pump).first()).state();double q=result.averageMassFlows()[pump];
            double floor=tank.pressure()+breakdown(w.model,graph,pump,q);
            System.out.println("PUMP_CAVITATION_LNG modes="+result.endpointModes()+" flows="+Arrays.toString(result.averageMassFlows())+" tankP="+tank.pressure()+" tankT="+tank.temperature()
                    +" tankLiquidV="+TestPhases.liquidVolume(tank)+" tankVapourV="+tank.vaporVolume()+" suctionP="+suction.pressure()+" suctionT="+suction.temperature()+" floor="+floor);
            assertTrue(TestPhases.liquidVolume(tank)>0&&tank.vaporVolume()>0,"the tank boils: two-phase");
            assertEquals(FlowControl.Mode.PUMP_SUCTION_LIMIT,result.endpointModes().get(pump),"the pump stands on its suction limit");
            assertEquals(floor,suction.pressure(),.01*tank.pressure(),"its suction stands at the tank's bubble pressure plus its breakdown NPSH");
            assertTrue(q>0&&q<.01*suction.mass()/suction.volume(),"it delivers what the head pushes through the line, below its target: "+q);
            return null;});
    }

    /**
     * Owner decision D14 (reading A13): the floor holds for a subcooled hydrocarbon liquid too, whose supplier holds no
     * vapour. Seed 397's LNG stands subcooled in its generator at 111.8 K and 222 kPa and feeds the pump [0.01 m3/s, 500 kPa]
     * through two metres of 10 mm line, which cannot carry the target (about 4.3 kg/s) on the 120 kPa between the generator
     * and the liquid's bubble pressure (about 100 kPa). The pump stands on its suction limit with its suction at that bubble
     * pressure, worked out at the generator liquid's composition and its own enthalpy (owner decision D16 C: the line
     * throttles the liquid, which keeps its enthalpy and warms by its Joule-Thomson effect), and delivers what the line
     * passes there.
     * Without the rule (two-phase suppliers only, K6) the junction was pulled to 40 kPa, where the liquid flashed and the
     * pass stalled (cold seed 397); bundled random seeds 952 and 575 held the same way on a subcooled light crude. The
     * bubble pressure is checked here on the engine's own PH equilibrium at the liquid's enthalpy, 0.1 % on either side of
     * the suction: the solver brackets it to 1e-6 and the suction row holds it to the Newton's tolerance, and a 10 mm
     * junction's port head is about 20 Pa, inside the 100 Pa of the band.
     */
    @Test void aSubcooledLngGeneratorHoldsItsPumpOnTheSuctionLimitAtTheBubblePressure() {
        var catalog=PilotCryogenicTestCatalog.catalog();var network=FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog,network,()->{
            var w=new EventPathWorld(catalog,FluidThermodynamics.forNetwork(catalog,network));var passive=new FlowControl.Passive();
            var lng=new FluidDeviceSpec(1,111.8,222268.7652191971,new double[]{0.0019051322577280617,0.8927278421557977,0.06093423437004952,0.04443279121642472,0});
            w.runTo(100);
            w.place(0,0,-3,Kind.GENERATOR,NORTH,passive,lng,.01);
            w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,0,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.01);
            w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,2,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,new double[]{1,0,0,0,0}),.01);
            w.runTo(100+20*100);w.assertKeptUp("a subcooled LNG generator feeding a pump through a narrow line");
            var s=w.coordinator.snapshots().stream().filter(sn->sn.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.Pump)).findFirst().orElseThrow();
            var result=s.lastResult().orElseThrow();var graph=s.graph();
            int pump=-1;for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.Pump)pump=e;
            var generator=graph.reservoirs().stream().filter(r->r.kind()==PassiveNetwork.NodeKind.GENERATOR).findFirst().orElseThrow().state();
            var suction=graph.reservoirs().get(graph.pipes().get(pump).first()).state();double q=result.averageMassFlows()[pump];
            double[] x=FluidThermodynamics.phaseMoles(generator,FluidThermodynamics.OIL);double t=generator.temperature(),moles=0;
            for(int c=0;c<x.length-1;c++)moles+=x[c];
            double h=moles*TestPhases.liquidProperties(generator).molarEnthalpy(),bubble=suction.pressure()-breakdown(w.model,graph,pump,q);
            var above=w.model.equilibriumPH(t,bubble*1.001,h,x.clone(),()->{});
            var below=w.model.equilibriumPH(t,bubble*.999,h,x.clone(),()->{});
            assertTrue(above!=null&&below!=null,"the PH equilibria on either side of the suction converge");
            System.out.println("PUMP_CAVITATION_SUBCOOLED modes="+result.endpointModes()+" flows="+Arrays.toString(result.averageMassFlows())+" generatorP="+generator.pressure()
                    +" generatorT="+t+" suctionP="+suction.pressure()+" suctionT="+suction.temperature());
            assertTrue(TestPhases.liquidVolume(generator)>0&&generator.vaporVolume()==0,"the generator holds a subcooled liquid, no vapour");
            assertEquals(FlowControl.Mode.PUMP_SUCTION_LIMIT,result.endpointModes().get(pump),"the pump stands on its suction limit");
            assertTrue(TestPhases.liquidVolume(above)>0&&above.vaporVolume()==0,"0.1 % above the suction less its breakdown NPSH the generator's liquid is one liquid");
            assertTrue(below.vaporVolume()>0,"0.1 % below it splits a vapour: the suction stands at its bubble pressure plus its breakdown NPSH");
            assertTrue(q>0&&q<.01*generator.mass()/generator.volume(),"it delivers what the line passes, below its target: "+q);
            for(int e=0;e<graph.pipes().size();e++)assertEquals(q,Math.abs(result.averageMassFlows()[e]),1e-3*q,"the series line carries the pump's flow");
            return null;});
    }

    /**
     * Owner decisions D15 and D16 A (D14_HOLDS_REVIEW.md, D15_HOLDS_REVIEW.md): a pump whose suction line delivers nothing
     * at its floor is vapour-locked and passes nothing. Seed 397's LNG stands in its generator at 111.8 K a millionth below
     * its bubble pressure (worked out here on the engine's own TP equilibrium; with no line between them the liquid's
     * enthalpy gives the same point), level with the pump [0.01 m3/s, 500 kPa], which draws through two metres of 10 mm
     * line. A supplier at its bubble point has no suction head over the floor at no flow (no NPSH available), so the pump
     * stands closed with no flow, not on its suction limit with none. Its discharge pipe is a tee that a second, subcooled
     * generator of the same LNG at 222 kPa feeds into the void, so the rest of the island runs, and the closed pump's rise
     * (about 215 kPa on LNG) beats its demand (about 60 kPa): only the vapour lock, one fact per solve on the step's start
     * states, keeps it closed. Anchored on its suction limit, bundled random 896 (a crude tee at its bubble point) had no
     * root at any step size, and closed without the lock cold random 181's pump reopened and cavitated again 46 times.
     */
    @Test void aPumpDrawingFromASupplierAtItsBubblePointIsVapourLockedAndClosed() {
        var catalog=PilotCryogenicTestCatalog.catalog();var network=FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog,network,()->{
            var w=new EventPathWorld(catalog,FluidThermodynamics.forNetwork(catalog,network));var passive=new FlowControl.Passive();
            double t=111.8,top=222268.7652191971;double[] x={0.0019051322577280617,0.8927278421557977,0.06093423437004952,0.04443279121642472,0};
            // The bubble pressure of the LNG at 111.8 K (about 100 kPa): the lowest pressure the TP equilibrium holds it as one liquid.
            double lo=80000,hi=top;
            for(int k=0;k<60;k++){double mid=Math.sqrt(lo*hi);boolean split;
                try{split=w.model.equilibriumTP(t,mid,x.clone(),()->{},null).vaporVolume()>0;}catch(FluidThermodynamics.UnsupportedPhases twoLiquids){split=true;}
                if(split)lo=mid;else hi=mid;}
            w.runTo(100);
            w.place(0,0,-3,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,t,hi*(1-1e-6),x.clone()),.01);
            w.place(0,0,-2,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(0,0,0,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.01);
            w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.01);
            w.place(1,0,1,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1,t,top,x.clone()),.01);
            w.place(0,0,2,Kind.VOID,NORTH,passive,new FluidDeviceSpec(1,298.15,101325,new double[]{1,0,0,0,0}),.01);
            w.runTo(100+20*100);w.assertKeptUp("a pump drawing from an LNG supplier at its bubble point");
            var s=w.coordinator.snapshots().stream().filter(sn->sn.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.Pump)).findFirst().orElseThrow();
            var result=s.lastResult().orElseThrow();var graph=s.graph();
            int pump=-1,feed=-1;
            for(int e=0;e<graph.pipes().size();e++) {
                var pipe=graph.pipes().get(e);
                if(pipe.control() instanceof FlowControl.Pump)pump=e;
                for(int node:new int[]{pipe.first(),pipe.second()})if(graph.reservoirs().get(node).kind()==PassiveNetwork.NodeKind.GENERATOR&&graph.reservoirs().get(node).state().pressure()>2e5)feed=e;
            }
            System.out.println("PUMP_CAVITATION_VAPOUR_LOCK bubbleP="+hi+" modes="+result.endpointModes()+" flows="+Arrays.toString(result.averageMassFlows()));
            assertEquals(FlowControl.Mode.CLOSED,result.endpointModes().get(pump),"the pump is vapour-locked: closed, not on its suction limit nor refused");
            assertEquals(0,result.averageMassFlows()[pump],1e-9,"it passes nothing");
            assertTrue(feed>=0&&Math.abs(result.averageMassFlows()[feed])>0,"the subcooled generator still feeds the void through the tee");
            return null;});
    }

    /**
     * The arriving-phase seed of a junction is its own split of the enthalpy that arrives (f983306b, 8fd61a13): bundled
     * fuzz seed 889 pair-minimized to 11 blocks (OD4_COLD285_GATE889_REVIEW.md, 2026-10-20). A 326.6 K, 398 kPa water
     * generator 3 m up feeds, through 20 and 10 mm lines, a junction beside a nitrogen tank and a 10 mm suction line into a
     * pump [0.001 m3/s, 1 MPa], which discharges through 10 mm into a second nitrogen tank. Its target needs more
     * suction-line loss than the 413 kPa the supply has above the water's vapour pressure (14.6 kPa), so it cavitates.
     * Water reaching the suction junction at its vapour pressure is liquid beside a trace of steam; the TP flash at the
     * mixed temperature, which leaves out the latent heat, made all of it steam, the suction limit's row had no vapour
     * pressure to stand at, and the island held at tick 2875 on a nonfinite trial residual.
     */
    @Test void waterArrivingAtACavitatingSuctionStaysLiquid() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        double[] n2=EventPathWorld.legacy(19);double[] water=EventPathWorld.legacy(20);
        w.runTo(152);w.place(2,0,0,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1.0,298.15,101325.0,n2),.01);
        w.runTo(194);w.place(-2,0,2,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(255);w.place(-1,0,1,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(312);w.place(-2,0,1,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(504);w.place(-2,2,2,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(1442);w.place(1,0,0,Kind.PIPE,NORTH,passive,null,.01);
        w.runTo(1496);w.place(0,0,1,Kind.RESERVOIR,NORTH,passive,new FluidDeviceSpec(1.0,298.15,101325.0,n2),.02);
        w.runTo(2341);w.place(-2,1,2,Kind.PIPE,NORTH,passive,null,.02);
        w.runTo(2642);w.place(-2,3,2,Kind.GENERATOR,NORTH,passive,new FluidDeviceSpec(1.0,326.5875545469788,397553.9461096364,water),.02);
        w.runTo(2873);w.place(-1,0,0,Kind.PIPE,NORTH,passive,null,.01);
        w.place(0,0,0,Kind.PUMP,EAST,new FlowControl.Pump(.001,1000000),null,.05);
        w.runTo(2873+120*100);w.assertKeptUp("water arriving at a cavitating pump suction");
        var s=w.coordinator.snapshots().stream().filter(sn->sn.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.Pump)).findFirst().orElseThrow();
        var result=s.lastResult().orElseThrow();var graph=s.graph();
        int pump=-1;for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.Pump)pump=e;
        var suction=graph.reservoirs().get(graph.pipes().get(pump).first()).state();
        double vapour=w.model.saturationPressure(suction.temperature()),q=result.averageMassFlows()[pump],floor=vapour+breakdown(w.model,graph,pump,q);
        double steam=TestPhases.waterVapor(suction)/(TestPhases.waterLiquid(suction)+TestPhases.waterVapor(suction));
        System.out.println("PUMP_CAVITATION_ARRIVAL modes="+result.endpointModes()+" flows="+Arrays.toString(result.averageMassFlows())+" suctionP="+suction.pressure()
                +" vapourP="+vapour+" suctionT="+suction.temperature()+" steamShare="+steam);
        // The cavitation is what the gate reads, not the mode's name: the pump stands where its head limit and its suction limit
        // both bind (owner direction 2026-10-21, BALANCE_CONVERGENCE_REVIEW.md), and either label holds the same suction at the
        // vapour pressure and the same flow (0.47887 kg/s on the head limit against 0.47882 on the suction limit).
        assertEquals(floor,suction.pressure(),1e-3*floor,"the pump cavitates: its suction stands at the vapour pressure plus its breakdown NPSH");
        assertTrue(TestPhases.waterLiquid(suction)>0&&steam<1e-3,"the suction junction holds liquid water beside a trace of steam: "+steam);
        assertTrue(q>0&&q<.001*suction.mass()/suction.volume(),"it delivers what the line gives, below its target: "+q);
    }
}
