package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.NetworkCrystals;
import com.wormzjl.icheme.science.fluid.transport.SlurryTransport;
import com.wormzjl.icheme.science.fluid.transport.SolidMobility;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Crystals as solids (milestone M3 of the unified thermo port, owner decisions D22 (5) and D23; plan
 * {@code documentation/2026-09-24-coolprop-low-temperature/PRECIPITATED_SOLIDS_PLAN.md}): the unit gates of the plan's
 * section 4 and the three gates M3 adds. A crystal is a population of main's {@code SolidInventory}; only a vessel's refresh
 * moves its mass; every stock holds one size per solid after its refresh; the outlet check reads that size; a slurry stops
 * at half solids; transport nodes never precipitate; and an island of unchanged solids rests. The design review's gates
 * (2026-10-16): the D26 amendment, D31 B with form 4 and D33.
 */
class PrecipitatedSolidsTest {
    static final String PILOT="icheme:pilot_cryogenic";
    static final int NITROGEN=0,METHANE=1,CO2=3;
    private final FluidThermodynamics pilot=FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(),PILOT);
    private final FluidThermodynamics water=FluidTestSupport.networkModel();
    private NetworkCrystals.Crystal co2(){return pilot.crystalSolids().all().getFirst();}

    /** {@code volume} m3 of {@code z} at (T, P) on {@code model}, its network TP answer (which moves no mass into a crystal). */
    private static FluidThermodynamics.State charge(FluidThermodynamics model,double t,double p,double[] z,double volume) {
        var unit=model.flashTP(t,p,z,()->{});
        double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=volume/unit.volume();
        return model.flashTP(t,p,n,()->{});
    }
    private FluidThermodynamics.State hotWater(double p){return charge(water,350,p,unit(water,water.componentCount()-1),1);}
    private static double[] unit(FluidThermodynamics model,int component){double[] n=new double[model.componentCount()];n[component]=1;return n;}
    private SolidMaterial demo(){return water.solids.require("icheme:demo_particle");}
    private static SolidInventory.Population grade(SolidMaterial material,String metres,double kg){return new SolidInventory.Population(material,new ParticleSize(metres),kg);}
    private static PassiveNetwork.Reservoir vessel(long id,FluidThermodynamics.State state,double capacity) {
        return new PassiveNetwork.Reservoir(id,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(capacity,PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()));
    }
    private static double crystalKg(PassiveNetwork.Reservoir node){double m=0;for(var p:node.inventory().solids().crystals())m+=p.massKg();return m;}

    // ---- Plan section 4, gate 1: phi to size ----

    /**
     * The size crystal mass forms at (D23 (1), (2)): {@code d = 0.25 mm max(1, 4.04 (phi / (1 - phi))^0.6)}, phi the CO2's
     * volume share of the vessel's liquids as crystal, capped at 0.5, and 0 from a gas. A gas and a dilute LNG give
     * 0.25 mm; a CO2-rich liquid freezes at the slush size, 1.01 mm; 30 % CO2 in liquid ethane, phi 0.17, 0.38 mm.
     */
    @Test void theSizeOfFormingCrystalGrowsWithItsComponentsShareOfTheLiquids() {
        var crystal=co2();
        var gas=charge(pilot,250,1e5,new double[]{.9,0,0,.1,0},1);
        assertNull(gas.liquidProperties(),"a gas");
        assertEquals(new ParticleSize("0.00025"),VesselCrystals.precipitateDiameter(gas,crystal),"frost from a gas: d_dilute");
        var dilute=charge(pilot,120,3e5,new double[]{0,.995,0,.005,0},1);
        assertEquals(new ParticleSize("0.00025"),VesselCrystals.precipitateDiameter(dilute,crystal),"0.5 % CO2 in LNG: phi "+phi(dilute,crystal));
        var between=charge(pilot,200,5e6,new double[]{0,0,.7,.3,0},1);
        double phi=phi(between,crystal);
        System.out.printf(Locale.ROOT,"phi to size: dilute LNG phi %.5f; 30 %% CO2 in liquid ethane at 200 K, 5 MPa phi %.5f, d %s m%n",phi(dilute,crystal),phi,
                VesselCrystals.precipitateDiameter(between,crystal).metres());
        assertTrue(phi>.09&&phi<.5,"between the dilute and the slush ends: "+phi);
        assertEquals(size(2.5e-4*4.04*Math.pow(phi/(1-phi),.6)),VesselCrystals.precipitateDiameter(between,crystal));
        var rich=charge(pilot,230,2e6,new double[]{0,0,0,1,0},1);
        assertNotNull(rich.liquidProperties(),"liquid CO2 above its triple point");
        assertTrue(phi(rich,crystal)>.5,"pure liquid CO2: its share as crystal exceeds the cap");
        assertEquals(new ParticleSize("0.00101"),VesselCrystals.precipitateDiameter(rich,crystal),"the slush: 0.25 mm times 4.04");
    }
    /**
     * The test world's F4: a refresh freezes crystal mass at the size of the liquid it froze from, not of the liquid it
     * leaves. Ten litres of methane with 30 % CO2 at 110 K in a slack 2 m3 bag at the ambient (the test world's vented
     * slush tank), the CO2 carried condensed in the liquid: its refresh freezes nearly all of the CO2 (its heat boils
     * methane at 1 atm), from a liquid whose CO2 share as crystal is about 0.24, so at about 0.5 mm. The liquid left beside
     * the crystals holds the CO2's solubility only and read 0.25 mm. A gas's frost leaves no liquid beside its crystals: it
     * forms from the gas, 0.25 mm, though the closure without crystals holds the gas's supersaturated CO2 as a metastable
     * liquid (nitrogen with 10 % CO2 at 170 K and 1 MPa: 0.2 L, phi 0.85), which would read as the slush, 1.01 mm.
     */
    @Test void aRefreshFreezesCrystalMassAtTheSizeOfTheLiquidItFrozeFrom() {
        var crystal=co2();
        var slush=charge(pilot,110,PhaseLayout.AMBIENT_PRESSURE,new double[]{0,.7,0,.3,0},.01);
        var tank=vessel(1,slush,2);
        var closed=InventoryEquilibrium.solve(pilot,slush,tank.inventory(),Double.NaN,()->{});
        double phi=Math.min(VesselCrystals.SLUSH_SHARE,phi(closed,crystal)),co2Kg=tank.inventory().moles()[CO2]*crystal.molarMass();
        var frozen=InventoryEquilibrium.refresh(new PassiveNetwork(List.of(tank),List.of()),pilot,()->{}).graph().reservoirs().getFirst();
        var size=frozen.inventory().solids().size(crystal.material().id());
        System.out.printf(Locale.ROOT,"30 %% CO2 in methane at 110 K, 1 atm: phi %.5f before the freeze, %.5f after; %.4f kg of %.4f kg CO2 froze at %s m (%.3f K)%n",phi,
                phi(frozen.state(),crystal),crystalKg(frozen),co2Kg,size.metres(),frozen.state().temperature());
        assertTrue(crystalKg(frozen)>.9*co2Kg,"nearly all of the CO2 freezes");
        assertTrue(phi>.15&&phi<.35,"the liquid it froze from: "+phi);
        assertEquals(size(2.5e-4*4.04*Math.pow(phi/(1-phi),.6)),size,"the size of the liquid it froze from");
        var gas=charge(pilot,170,1e6,new double[]{.9,0,0,.1,0},.1);
        var frostTank=new PassiveNetwork.Reservoir(1,0,gas);
        var condensed=InventoryEquilibrium.solve(pilot,gas,frostTank.inventory(),Double.NaN,()->{});
        var frost=InventoryEquilibrium.refresh(new PassiveNetwork(List.of(frostTank),List.of()),pilot,()->{}).graph().reservoirs().getFirst();
        System.out.printf(Locale.ROOT,"10 %% CO2 in nitrogen at 170 K, 1 MPa: before the freeze %.4e m3 of liquid (N2 %.4e mol, CO2 %.4e mol, the stand-in %s), phi %.4f;"
                +" after %.4e m3; %.4f kg of frost at %s m%n",condensed.liquidVolume(),condensed.liquidView()[NITROGEN],condensed.liquidView()[CO2],pilot.condensedShareOnly(condensed),
                phi(condensed,crystal),frost.state().liquidVolume(),crystalKg(frost),frost.inventory().solids().size(crystal.material().id()).metres());
        assertTrue(crystalKg(frost)>0);
        assertEquals(new ParticleSize("0.00025"),frost.inventory().solids().size(crystal.material().id()),"frost from a gas: d_dilute");
    }
    /** The CO2's volume share of the state's liquids as crystal, uncapped. */
    private static double phi(FluidThermodynamics.State state,NetworkCrystals.Crystal crystal) {
        double liquids=state.liquidVolume()+state.waterVolume();
        return liquids>0?state.liquidView()[crystal.component()]*crystal.molarMass()/crystal.material().density()/liquids:0;
    }
    private static ParticleSize size(double metres){return new ParticleSize(new BigDecimal(metres).round(new MathContext(SolidInventory.SIZE_DIGITS,RoundingMode.HALF_EVEN)).toString());}

    // ---- Gate 2: one size per solid in every stock ----

    /**
     * After a refresh every stock holds one population per solid (D23, amended 2026-10-15): a tank, a junction buffer
     * and a filter cake, each holding 90 % of a solid at 0.25 mm and 10 % at 1 mm, hold it at the mass-weighted mean,
     * 0.325 mm, with its mass kept exactly and its volume and heat capacity to rounding.
     */
    @Test void aRefreshLeavesOneSizePerSolidInEveryStock() {
        var demo=demo();
        var two=new SolidInventory(List.of(grade(demo,"0.00025",9),grade(demo,"0.001",1)));
        var tankState=hotWater(160000).withSolids(two.scale(10));
        var junctionState=hotWater(155000).withSolids(two);
        var junction=new PassiveNetwork.Reservoir(2,0,junctionState,PassiveNetwork.NodeKind.JUNCTION,PassiveNetwork.mintedJunction(junctionState,5));
        var run=new PipeResistance.Geometry(10,.05,.000045,0);
        var filtered=new PassiveNetwork.Pipe(11,1,2,run).withFilter(new InlineFilter(.01,1e6,two.scale(.1),-12.5));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,tankState),junction,new PassiveNetwork.Reservoir(3,0,hotWater(150000))),
                List.of(new PassiveNetwork.Pipe(10,0,1,run),filtered));
        var refreshed=InventoryEquilibrium.refresh(graph,water,()->{});
        assertTrue(refreshed.converted(),"a merge is a change of the stock");
        var after=refreshed.graph();
        var stocks=List.of(graph.reservoirs().get(0).inventory().solids(),graph.reservoirs().get(1).inventory().solids(),graph.pipes().get(1).filter().captured());
        var merged=List.of(after.reservoirs().get(0).inventory().solids(),after.reservoirs().get(1).inventory().solids(),after.pipes().get(1).filter().captured());
        for(int i=0;i<3;i++) {
            var before=stocks.get(i).moments();var stock=merged.get(i);
            assertEquals(1,stock.populations().size(),"stock "+i+": one population");
            assertEquals(new ParticleSize("0.000325"),stock.populations().getFirst().size(),"stock "+i+": the mass-weighted mean 0.9 x 0.25 + 0.1 x 1 mm");
            assertEquals(before.mass(),stock.moments().mass(),0,"stock "+i+": mass");
            assertEquals(before.volume(),stock.moments().volume(),1e-15*before.volume(),"stock "+i+": volume");
            assertEquals(before.heatCapacity(),stock.moments().heatCapacity(),1e-15*before.heatCapacity(),"stock "+i+": heat capacity");
        }
        // The junction's state carries its buffer's populations at its own amount scale.
        var state=after.reservoirs().get(1).state().solids();
        assertEquals(1,state.populations().size());assertEquals(new ParticleSize("0.000325"),state.populations().getFirst().size());
        assertEquals(junctionState.solids().massKg(),state.massKg(),1e-15*state.massKg());
    }

    // ---- Gate 3: the outlet reads the per-solid mean ----

    /**
     * Main's outlet check reads each solid at its stock's size, the mass-weighted mean (D23, amended): CO2 crystals in
     * liquid methane, 99 % at 0.25 mm and 1 % at 1 mm, average 0.2575 mm and stay suspended in a 1.5 m/s line (0.25 mm
     * needs about 1.0 m/s, 1 mm about 2.8 m/s). The largest population, main's rule before, closed it.
     */
    @Test void theOutletReadsEachSolidAtItsMeanSize() {
        var crystal=co2().material();
        var lng=charge(pilot,115,3e5,new double[]{0,1,0,0,0},1);
        assertNotNull(lng.liquidProperties());assertNull(lng.vaporProperties(),"subcooled liquid methane");
        double kg=.01*lng.liquidVolume()*crystal.density();
        var pipe=new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0));
        var mixed=lng.withSolids(new SolidInventory(List.of(grade(crystal,"0.00025",.99*kg),grade(crystal,"0.001",.01*kg))));
        var fine=lng.withSolids(new SolidInventory(List.of(grade(crystal,"0.00025",kg))));
        var coarse=lng.withSolids(new SolidInventory(List.of(grade(crystal,"0.001",kg))));
        double q=1.5*pipe.maximumArea()*mixed.mass()/(mixed.liquidVolume()+mixed.waterVolume());
        var check=SolidMobility.check(SolidMobility.donor(pilot,mixed),mixed,pipe,q,SolidMobility.Outlet.MIXED,mixed.mass());
        double fineSpeed=SolidMobility.check(SolidMobility.donor(pilot,fine),fine,pipe,0,SolidMobility.Outlet.MIXED,fine.mass()).minimumVelocity();
        double coarseSpeed=SolidMobility.check(SolidMobility.donor(pilot,coarse),coarse,pipe,0,SolidMobility.Outlet.MIXED,coarse.mass()).minimumVelocity();
        System.out.printf(Locale.ROOT,"outlet: line %.3f m/s; suspension 0.25 mm %.3f m/s, 1 mm %.3f m/s, the 99/1 stock %.3f m/s (%s)%n",check.velocity(),fineSpeed,coarseSpeed,
                check.minimumVelocity(),mixed.solids().size(crystal.id()).metres());
        assertEquals(1.5,check.velocity(),1e-12);
        assertTrue(fineSpeed<1.5&&coarseSpeed>1.5,"0.25 mm stays suspended at 1.5 m/s and 1 mm does not");
        assertEquals(SolidMobility.Reason.MOBILE,check.reason(),"the stock's mean, 0.2575 mm, keeps the line open");
        assertTrue(check.minimumVelocity()<1.1,"the mean's suspension speed: "+check.minimumVelocity());
    }

    // ---- Gate 4: slush at 0.5 ----

    /** A slurry whose solids fill half of its condensed volume is a slush and closes an outlet (D23 (3)); just below half it
     * flows. The slurry viscosity law keeps its 0.62 packing limit. */
    @Test void aSlurryStopsAtHalfSolids() {
        var demo=demo();var liquid=hotWater(160000);
        var pipe=new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0));
        for(double share:new double[]{.5*(1+1e-9),.5*(1-1e-9)}) {
            double kg=share/(1-share)*liquid.waterVolume()*demo.density();
            var slurry=liquid.withSolids(new SolidInventory(List.of(grade(demo,"0.00001",kg))));
            double q=3*pipe.maximumArea()*slurry.mass()/slurry.waterVolume();
            var check=SolidMobility.check(SolidMobility.donor(water,slurry),slurry,pipe,q,SolidMobility.Outlet.MIXED,slurry.mass());
            assertEquals(share>.5?SolidMobility.Reason.PACKED_SLURRY:SolidMobility.Reason.MOBILE,check.reason(),"solids "+share+" of the condensed volume");
        }
        assertEquals(.5,SlurryTransport.SLUSH_FRACTION);assertEquals(.62,SlurryTransport.MAXIMUM_PACKING_FRACTION);
        assertTrue(Double.isFinite(SlurryTransport.effectiveViscosity(1e-3,.55)),"the viscosity law still states 0.55");
    }

    // ---- Gate 5: precipitation only in reservoirs ----

    /**
     * A let-down that would freeze passes (D23 (5)): liquid CO2 at 240 K and 2 MPa flows into a slack tank at the ambient,
     * where it flashes far below CO2's 194.63 K sublimation point at 1 atm. Nothing is refused and nothing freezes in the
     * transport; the stream arrives supersaturated, and the tank's next refresh makes dry ice at the sublimation point.
     */
    @Test void aLetDownThatWouldFreezePassesAndTheReceivingTankPrecipitatesAtItsRefresh() {
        var liquid=charge(pilot,240,2e6,new double[]{0,0,0,1,0},1);
        assertNotNull(liquid.liquidProperties());
        var gas=charge(pilot,250,PhaseLayout.AMBIENT_PRESSURE,new double[]{0,0,0,1,0},.05);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,liquid,PassiveNetwork.NodeKind.GENERATOR),vessel(2,gas,1)),
                List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.004,.000045,0))));
        var solver=new PassiveIntervalSolver(pilot);
        var first=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},.05);
        var tank=first.graph().reservoirs().get(1);
        System.out.printf(Locale.ROOT,"let-down: %.6e kg/s for 5 s; the tank at %.4f K, %.1f Pa, %.4e m3 of liquid, %.4e m3 of vapour, crystal %.3e kg, closures %d%n",
                first.averageMassFlows()[0],tank.state().temperature(),tank.state().pressure(),tank.state().liquidVolume(),tank.state().vaporVolume(),crystalKg(tank),
                first.graph().pipes().getFirst().blockedDirections());
        assertTrue(first.averageMassFlows()[0]>0,"the let-down passes");
        assertEquals(0,first.graph().pipes().getFirst().blockedDirections(),"no closure for freezing");
        assertEquals(0,crystalKg(tank),"no crystal before the tank's refresh");
        assertTrue(tank.state().temperature()<194.6,"the stream arrived supersaturated: "+tank.state().temperature());
        var refreshed=InventoryEquilibrium.refresh(first.graph(),pilot,()->{}).graph().reservoirs().get(1);
        System.out.printf(Locale.ROOT,"let-down, the tank's refresh: %.6f K, %.1f Pa, crystal %.6f kg%n",refreshed.state().temperature(),refreshed.state().pressure(),crystalKg(refreshed));
        assertTrue(crystalKg(refreshed)>0,"dry ice at the tank's refresh");
        assertEquals(co2().referenceTemperature(),refreshed.state().temperature(),1e-6,"dry ice beside its gas at 1 atm: the sublimation point");
    }

    // ---- Gate 6: a stock with one size per solid and no crystal ----

    /** A refresh changes nothing of stocks holding one size per solid and no crystal: the tank, the junction buffer and the
     * filter cake keep their every bit, and the island is not converted (it may rest). */
    @Test void aStockOfOneSizePerSolidAndNoCrystalIsLeftAsItIs() {
        var demo=demo();var one=new SolidInventory(List.of(grade(demo,"0.0001",3)));
        var junctionState=hotWater(155000).withSolids(one);
        var run=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,hotWater(160000).withSolids(one.scale(10))),
                new PassiveNetwork.Reservoir(2,0,junctionState,PassiveNetwork.NodeKind.JUNCTION,PassiveNetwork.mintedJunction(junctionState,5)),new PassiveNetwork.Reservoir(3,0,hotWater(150000))),
                List.of(new PassiveNetwork.Pipe(10,0,1,run),new PassiveNetwork.Pipe(11,1,2,run).withFilter(new InlineFilter(.01,1e6,one.scale(.1),-12.5))));
        var refreshed=InventoryEquilibrium.refresh(graph,water,()->{});
        assertFalse(refreshed.converted());
        for(int i=0;i<3;i++)assertSame(graph.reservoirs().get(i).inventory(),refreshed.graph().reservoirs().get(i).inventory(),"node "+i);
        assertSame(graph.reservoirs().get(1),refreshed.graph().reservoirs().get(1),"the junction");
        assertSame(graph.pipes().get(1).filter(),refreshed.graph().pipes().get(1).filter(),"the filter cake");
        assertTrue(refreshed.boundaries().isEmpty());
    }

    // ---- M3 gates ----

    /**
     * A closed tank charged supersaturated (10 % CO2 in nitrogen at 170 K and 1 MPa, below its frost point) precipitates
     * before it rests: nothing can drive a flow in it, but its refresh runs before the rest test (D23 (6)), deposits CO2
     * and warms the gas with the latent heat; the next interval is the rest interval, every bit kept.
     */
    @Test void aSupersaturatedClosedTankPrecipitatesBeforeItRests() {
        var tank=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,charge(pilot,170,1e6,new double[]{.9,0,0,.1,0},.1))),List.of());
        double co2=tank.reservoirs().getFirst().inventory().moles()[CO2];
        var solver=new PassiveIntervalSolver(pilot);
        var first=solver.solve(tank,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        var node=first.graph().reservoirs().getFirst();
        System.out.printf(Locale.ROOT,"closed tank: %.6f K, %.1f Pa, crystal %.6f mol of %.6f mol CO2%n",node.state().temperature(),node.state().pressure(),
                crystalKg(node)/co2().molarMass(),co2);
        assertTrue(crystalKg(node)>0,"it precipitated");
        assertEquals(co2,node.inventory().moles()[CO2]+crystalKg(node)/co2().molarMass(),1e-12*co2,"CO2 conserved");
        var second=solver.solve(first.graph(),5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        assertSame(first.graph().reservoirs().getFirst().inventory(),second.graph().reservoirs().getFirst().inventory(),"then it rests");
    }

    /** An island whose solids nothing moves rests (D23 (6)): two tanks of the same slurry at one pressure, joined. Main
     * solved it every slice. */
    @Test void anIslandOfUnchangedSolidsRests() {
        var slurry=hotWater(160000).withSolids(new SolidInventory(List.of(grade(demo(),"0.0001",30))));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,slurry),new PassiveNetwork.Reservoir(2,0,slurry)),
                List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveIntervalSolver(water).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        for(int i=0;i<2;i++)assertSame(graph.reservoirs().get(i).inventory(),result.graph().reservoirs().get(i).inventory(),"node "+i+" kept to the bit: at rest");
    }

    /**
     * The refresh the rest test reads visits only a stock that may convert (D23 (6), with the free water's ice on every
     * package). Physics: free water freezes only at or below its melting line, T_m(P) (273.15 K at 1 atm); above it the
     * latent heat a freeze releases would raise the vessel further above, so a vessel of warm water holding no solids
     * converts nothing. 1 m3 of water at 350 K and 160 kPa is left as it is, with no closure and no checkpoint, as on a
     * package without crystals before the ice; the interval start's refresh still restates it. A tank of nitrogen with
     * 1 % water at 230 K and 1 atm, its condensed water carried as the stand-in, is below the line: the same refresh
     * freezes it, and the latent heat (about 0.5 mol at 6 to 8 kJ/mol into 53 mol of nitrogen) warms the tank by 2 K.
     */
    @Test void aWarmWaterVesselHasNothingToConvertAndAColdOneFreezes() {
        var warm=new PassiveNetwork(List.of(vessel(1,hotWater(160000),1)),List.of());
        int[] checkpoints={0};
        var rest=InventoryEquilibrium.conversions(warm,water,()->checkpoints[0]++);
        assertSame(warm,rest.graph(),"warm water above its melting line: nothing to convert");
        assertFalse(rest.converted());
        assertEquals(0,checkpoints[0],"no closure and no checkpoint");
        InventoryEquilibrium.refresh(warm,water,()->checkpoints[0]++);
        assertTrue(checkpoints[0]>0,"the interval start's refresh restates it");
        double[] z=new double[water.componentCount()];z[MaterialTestBasis.NITROGEN]=.99;z[z.length-1]=.01;
        var cold=new PassiveNetwork(List.of(vessel(1,charge(water,230,PhaseLayout.AMBIENT_PRESSURE,z,1),1)),List.of());
        var before=cold.reservoirs().getFirst();
        checkpoints[0]=0;
        var frozen=InventoryEquilibrium.conversions(cold,water,()->checkpoints[0]++);
        var tank=frozen.graph().reservoirs().getFirst();
        double melting=water.freeWaterIce().meltingTemperature(tank.state().pressure());
        System.out.printf(Locale.ROOT,"water below its melting line: %.4f K to %.4f K (T_m %.4f K at %.1f Pa), %.6f kg of ice of %.6f kg of water, %d checkpoints%n",
                before.state().temperature(),tank.state().temperature(),melting,tank.state().pressure(),crystalKg(tank),
                before.inventory().moles()[z.length-1]*water.waterMolecularWeight,checkpoints[0]);
        assertTrue(before.state().waterLiquid()>0,"the stand-in");
        assertTrue(frozen.converted(),"a freeze is a conversion");
        assertTrue(crystalKg(tank)>0,"water below its melting line freezes");
        assertTrue(tank.state().temperature()>before.state().temperature()&&tank.state().temperature()<melting,"the latent heat warms it, below its melting line");
    }

    /**
     * A taut vessel of nitrogen over water cooled through its freeze (the lead's direction of 2026-10-17): 1 m3 of
     * nitrogen over 0.83 kg of water at 275 K and 1 atm, filled from a generator of nitrogen at 150 K and 3 bar. Physics:
     * the vessel fills to 3 bar in about a second, and the cold nitrogen would take it to about 255 K (its 4.4 kJ/K and
     * 92 mol of nitrogen at 150 K); the water freezes instead, on its melting line, until the latent heat holds the vessel
     * there: about 0.4 of it, ice and liquid water at T_m(3 bar), 273.137 K. The steps carry the water as its stand-in, the
     * next interval's refresh freezes it at the vessel's fixed volume, and no interval fails (the refresh's crystal test
     * refused that freeze as a step of U(T) before).
     */
    @Test void aTautVesselOfNitrogenOverWaterCooledThroughItsFreezeFreezesOnTheMeltingLine() {
        double[] z=new double[water.componentCount()];z[MaterialTestBasis.NITROGEN]=.5;z[z.length-1]=.5;
        var cold=new PassiveNetwork.Reservoir(1,0,charge(water,150,3e5,unit(water,MaterialTestBasis.NITROGEN),1),PassiveNetwork.NodeKind.GENERATOR);
        var tank=vessel(2,charge(water,275,PhaseLayout.AMBIENT_PRESSURE,z,1),1);
        var graph=new PassiveNetwork(List.of(cold,tank),List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(1,.05,.000045,0))));
        double waterMoles=tank.inventory().moles()[z.length-1];
        var solver=new PassiveIntervalSolver(water);double step=.05;
        for(int k=0;k<4;k++) {
            var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},step);
            step=solver.nextStepEstimate();graph=result.graph();
            var node=graph.reservoirs().get(1);var s=node.state();
            System.out.printf(Locale.ROOT,"N2 over water cooled through its freeze, interval %d: %.6f K (T_m %.6f K) %.1f Pa, ice %.6f kg, stand-in %.4f mol of %.4f mol (%d substeps, %d rejected)%n",
                    k+1,s.temperature(),water.freeWaterIce().meltingTemperature(s.pressure()),s.pressure(),crystalKg(node),s.waterLiquid(),waterMoles,
                    result.acceptedSubsteps(),result.rejectedSubsteps());
        }
        var node=graph.reservoirs().get(1);var s=node.state();
        assertTrue(s.pressure()>PhaseLayout.AMBIENT_PRESSURE+1e5,"filled to about 3 bar: taut");
        assertTrue(crystalKg(node)>0,"the water froze");
        assertTrue(s.waterLiquid()>0,"and some stays liquid beside the ice");
        assertEquals(water.freeWaterIce().meltingTemperature(s.pressure()),s.temperature(),1e-6,"on the melting line at its pressure");
    }

    /** The trace flush never deletes crystal mass: a vessel at its crystal equilibrium holding a 1e-25 mol methane trace
     * steps, the flush removes the trace (booked at the vessel) and the crystal population is kept to the bit. */
    @Test void theTraceFlushKeepsCrystalMass() {
        var tank=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,charge(pilot,170,1e6,new double[]{.9,0,0,.1,0},.1))),List.of());
        var settled=InventoryEquilibrium.refresh(tank,pilot,()->{}).graph().reservoirs().getFirst();
        var crystals=settled.inventory().solids();assertFalse(crystals.crystals().isEmpty());
        double[] moles=settled.inventory().moles();moles[METHANE]=1e-25;
        var traced=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,settled.state(),PassiveNetwork.NodeKind.RESERVOIR,
                new PassiveNetwork.Inventory(settled.inventory().volume(),moles,settled.inventory().internalEnergy(),crystals))),List.of());
        var result=new PassiveIntervalSolver(pilot).solve(traced,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        var after=result.graph().reservoirs().getFirst();
        assertEquals(0,after.inventory().moles()[METHANE],"the trace is flushed");
        assertTrue(result.boundaries().stream().anyMatch(b->b.nodeId()==1&&b.moles()[METHANE]==-1e-25),"and booked at the vessel: "+result.boundaries());
        assertEquals(crystals,after.inventory().solids(),"the crystal is kept to the bit");
    }

    // ---- The design review's gates (2026-10-16): the D26 amendment, D31 B, D33 ----

    /** A vessel's own split ({@link InventoryEquilibrium#split}) of the point {@code state} in {@code capacity}, as a step's
     * phase check asks it, counting the calls in {@code calls}. */
    private UnaryOperator<FluidThermodynamics.State> ownSplit(FluidThermodynamics.State state,double capacity,int[] calls) {
        var inventory=new PassiveNetwork.Inventory(capacity,PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids());
        return start->{calls[0]++;return InventoryEquilibrium.split(pilot,start,inventory,PhaseLayout.bagVolume(state,capacity),()->{});};
    }

    /**
     * The D26 amendment (cold seed 48's tank at a step's point): a liquid-full LNG tank beside its dry ice at its bubble
     * point holds no vapour. 21 414 mol of LNG with 1.8 % CO2 dissolved at 157.39 K and 1.382 MPa beside 27.7 kg of dry ice,
     * liquid and solids filling the 1 m3. The tank has no room for a bubble: its liquid's own bubble pressure is 903 Pa below
     * the tank's. A step's TP answer carries the dissolved CO2 (D23), so it is that one liquid and proposes nothing. Under the
     * crystal competition it froze 33 mol of that CO2, and the rest of the liquid boiled: a proposal of 55 mol of vapour, which
     * the check had to refuse.
     */
    @Test void aLiquidFullLngTankBesideDryIceAtItsBubblePointHoldsNoVapour() {
        var dryIce=new SolidInventory(List.of(new SolidInventory.Population(co2().material(),new ParticleSize("0.00025"),27.682029902383036)));
        double t=157.39079577144585,p=1381705.5110999327;double[] lng={169.4018947211584,19565.23151661761,1285.9992875806702,393.1261466989467};
        var tank=pilot.state(t,p,lng,new double[lng.length],0,0,p).withSolids(dryIce);
        double capacity=tank.liquidVolume()+dryIce.volume();
        var answer=pilot.flashTP(t,p,PhaseLayout.totalAmounts(tank),()->{});
        System.out.printf(Locale.ROOT,"LNG beside dry ice: %.6f m3 of liquid and %.6f m3 of dry ice in %.6f m3; the TP answer: %.4e mol of vapour%n",
                tank.liquidVolume(),dryIce.volume(),capacity,Arrays.stream(answer.vaporView()).sum());
        assertNull(answer.vaporProperties(),"the TP answer proposes no vapour");
        int[] splits={0},record={0};var own=ownSplit(tank,capacity,splits);
        assertSame(tank,pilot.phaseCheck(tank,false,null,record,0,null,own,()->{}),"no vapour: the tank keeps its liquid");
        assertEquals(0,splits[0],"no crossing: no own split");
        assertEquals(FluidThermodynamics.phaseBits(answer)&~FluidThermodynamics.phaseBits(tank),record[0],"the vapour is refused for the solve");
        assertSame(tank,pilot.phaseCheck(tank,false,null,record,0,null,own,()->{}),"the solve's next pass");
        assertEquals(0,splits[0],"no own split in the solve");
    }

    /**
     * Form 4 (ii), the D26 amendment by direction: a liquid-full LNG tank beside dry ice whose own closure stops short of
     * nothing holds no vapour. Seed 48's tank a moment earlier (157.3903 K, 1.3811 MPa): under the crystal competition the
     * TP answer proposed 87 mol of vapour, and the tank's closure, with no room for it, had driven it down to 6.0e-5 mol (2.8e-9
     * of the moles, still falling) when its 24 iterations ended. That was above the trace share, which kept it and seeded it;
     * the next pass folded it, the pass key cycled and the step halved (seed 48's storm). A step's TP answer now carries the
     * dissolved CO2 (D23) and proposes nothing, its liquid's own bubble pressure being 280 Pa below the tank's: one step of the
     * tank fed its own LNG at its own pressure takes one pass and refuses nothing.
     */
    @Test void aLiquidFullLngTankWhoseClosureStopsShortOfNothingHoldsNoVapour() {
        var dryIce=new SolidInventory(List.of(new SolidInventory.Population(co2().material(),new ParticleSize("0.00025"),27.681970836122474)));
        double t=157.39032741149214,p=1381057.0648888762;double[] lng={169.40129908820836,19565.177187575668,1285.9959066479023,393.12480966757914};
        var state=pilot.state(t,p,lng,new double[lng.length],0,0,p).withSolids(dryIce);
        double capacity=state.liquidVolume()+dryIce.volume();
        var tank=new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(capacity,PhaseLayout.totalAmounts(state),state.internalEnergy(),dryIce));
        var feed=new PassiveNetwork.Reservoir(2,0,pilot.state(t,p,lng,new double[lng.length],0,0,p),PassiveNetwork.NodeKind.GENERATOR);
        var graph=new PassiveNetwork(List.of(tank,feed),List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.02,.000045,0))));
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;PassiveStepSolver.Result step;
        try{step=new PassiveStepSolver(pilot).solve(graph,.05,()->{});}finally{SolverDiagnostics.ENABLED=false;}
        assertNull(step.states().getFirst().vaporProperties(),"no vapour");
        assertEquals(1,SolverDiagnostics.activeSetPasses.sum(),"one pass: no cycle");
        assertEquals(0,SolverDiagnostics.phaseSeeds.sum(),"no crossing: no own split");
        assertEquals(0,SolverDiagnostics.phaseRefusals.sum(),"nothing to refuse");
    }

    /**
     * E8: a liquid-full tank whose closure folds its last bubble is closed again without it. Cold seed 48's tank under form
     * 4: 21 874 mol of LNG at 153.08 K and 1.1413 MPa beside 35.09 kg of dry ice, at its bubble point with a last bubble of
     * 2.19e-5 mol (1.001e-9 of its moles, the trace share 1e-9, but 2.0e-8 of its 1 m3), fed its own liquid 100 Pa above.
     * In a step of 1e-6 s, 2.3e-8 kg flow in; the reconstruction's closure condenses the bubble just under the trace share
     * and the restatement folds it (decision D34). The folded tank misses its volume by that bubble, 1.9e-8 against the 1e-8 check,
     * which refused the step at every size (10 224 times in the seed). Closed again without the bubble, the liquid takes
     * the 2e-8 m3 the bubble left (its pressure falls about 5 Pa), the step is accepted after one pass for the fold, and
     * the tank's state meets its inventory to the closure.
     */
    @Test void aLiquidFullTankWhoseClosureFoldsItsLastBubbleIsClosedAgainWithoutIt() {
        var dryIce=new SolidInventory(List.of(new SolidInventory.Population(co2().material(),new ParticleSize("0.00025"),35.09036214684682)));
        double t=153.07858326704772,p=1141313.268432868;
        double[] lng={127.94664170820322,20017.721696299337,1419.2160471398552,308.7325517796933};
        double[] bubble={8.685458618914788e-7,2.094236045700818e-5,4.4530891574445825e-8,3.930257238466825e-8};
        var state=pilot.state(t,p,lng,bubble,0,0,p).withSolids(dryIce);
        double capacity=state.liquidVolume()+state.vaporVolume()+dryIce.volume();
        var tank=new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(capacity,PhaseLayout.totalAmounts(state),state.internalEnergy(),dryIce));
        var feed=new PassiveNetwork.Reservoir(2,0,pilot.state(t,p+100,lng,new double[lng.length],0,0,p+100),PassiveNetwork.NodeKind.GENERATOR);
        var graph=new PassiveNetwork(List.of(tank,feed),List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.02,.000045,0))));
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;PassiveStepSolver.Result step;
        try{step=new PassiveStepSolver(pilot).solve(graph,1e-6,()->{});}finally{SolverDiagnostics.ENABLED=false;}
        var end=step.states().getFirst();
        System.out.printf(Locale.ROOT,"E8: the bubble %.4e mol (%.4e of the moles, %.4e m3); after the step %.9f K, %.3f Pa, %.4e kg/s in, vapour %s, %d fold pass(es)%n",
                Arrays.stream(bubble).sum(),Arrays.stream(bubble).sum()/Arrays.stream(PhaseLayout.totalAmounts(state)).sum(),state.vaporVolume(),end.temperature(),end.pressure(),
                -step.massFlows()[0],end.vaporProperties()==null?"none":"held",SolverDiagnostics.traceFoldPasses.sum());
        assertTrue(step.massFlows()[0]<0,"the feed flows in");
        assertEquals(1,SolverDiagnostics.traceFoldPasses.sum(),"the reconstruction folded the bubble once");
        assertNull(end.vaporProperties(),"no vapour");
        assertSame(end,InventoryEquilibrium.close(pilot,end,step.inventories().getFirst(),null,Double.NaN,()->{}),"the tank's state meets its inventory to the closure");
    }

    /**
     * Form 4 (i): a frosted gas tank proposes no stand-in. Nitrogen with 10 % CO2 at 1 MPa, frosted at its refresh, a kelvin
     * colder at a step's point (its outflow expands it): the gas is supersaturated again, and stays one vapour until the next
     * refresh, between its frost line and its metastable liquid's dew line (D23). The check adds nothing and runs no own
     * split. Under the crystal competition the TP answer froze more CO2 and proposed it as a stand-in, which the tank's own
     * closure could not close, so the check refused it. (Liquid CO2 let down into a tank of its gas keeps its stand-in: gate 5
     * above.)
     */
    @Test void aFrostedGasTanksStandInIsDecidedByItsOwnSplitAndRefused() {
        var tank=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,charge(pilot,170,1e6,new double[]{.9,0,0,.1,0},.1))),List.of());
        var frosted=InventoryEquilibrium.refresh(tank,pilot,()->{}).graph().reservoirs().getFirst();
        assertTrue(crystalKg(frosted)>0);assertNull(frosted.state().liquidProperties(),"a gas beside its frost");
        var gas=frosted.state();double t=gas.temperature()-1;
        var point=pilot.state(t,gas.pressure(),new double[4],gas.vaporView(),0,0,gas.pressure()).withSolids(gas.solids());
        var answer=pilot.flashTP(t,point.pressure(),PhaseLayout.totalAmounts(point),()->{});
        assertNull(answer.liquidProperties(),"the TP answer stays one vapour");
        int[] splits={0},record={0};var own=ownSplit(point,frosted.inventory().volume(),splits);
        assertSame(point,pilot.phaseCheck(point,false,null,record,0,null,own,()->{}),"no phase added");
        assertEquals(0,splits[0],"no own split");
        assertEquals(0,record[0],"nothing to refuse");
    }

    /**
     * D33: a frosting tank's side outlet passes gas and keeps the frost, and after sublimation no closure survives to a
     * rest certificate. The frosted tank above vents through its bulk end into the atmosphere: the gas leaves, every
     * crystal stays (solids ride the liquid, and it holds none). The same tank once its frost sublimed, at 300 kPa with
     * the closure the frost era left on its outlet: a closure stands for its interval only, so at the next interval start
     * the rest test reads it as open, and 199 kPa across it is a drive, not a rest. The interval reads it open on its path
     * too (D33's gap, M4c): an island of two gas tanks holds no filter, solids or liquid, so it is the plain integrate, where
     * nothing decides a closure again (a gas carries no solid). The physics: 0.1 m3 at 300 kPa into 0.1 m3 at 101 kPa through
     * 10 m of 4 mm line, about 1.5e-3 kg/s (isothermal line flow, A sqrt((P1^2 - P2^2) D / (f L R T / M)) with f near
     * 0.03): about 8 g in 5 s, which moves each tank's pressure about 7.7 kPa (adiabatic, dP/P = gamma dm/m, of 0.43 and
     * 0.14 kg), the start of an equalisation at about 200 kPa that takes minutes. The solve gives 1.56e-3 kg/s, 292.4 and
     * 108.5 kPa. Kept closed, nothing moves (the head's plain integrate: 0 kg/s, 300 and 101.3 kPa, the closure still on).
     */
    @Test void aFrostingTanksSideOutletPassesGasAndKeepsTheFrost() {
        var tank=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,charge(pilot,170,1e6,new double[]{.9,0,0,.1,0},.1))),List.of());
        var frosted=InventoryEquilibrium.refresh(tank,pilot,()->{}).graph().reservoirs().getFirst();
        var air=new PassiveNetwork.Reservoir(2,0,charge(pilot,250,PhaseLayout.AMBIENT_PRESSURE,new double[]{1,0,0,0,0},.05),PassiveNetwork.NodeKind.VOID);
        var outlet=new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.004,.000045,0));
        var vent=new PassiveIntervalSolver(pilot).solve(new PassiveNetwork(List.of(frosted,air),List.of(outlet)),5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        var after=vent.graph().reservoirs().getFirst();
        System.out.printf(Locale.ROOT,"frosted tank vents: %.6e kg/s for 5 s, %.1f Pa left, frost %.6f kg kept of %.6f kg, closures %d%n",vent.averageMassFlows()[0],
                after.state().pressure(),crystalKg(after),crystalKg(frosted),vent.graph().pipes().getFirst().blockedDirections());
        assertTrue(vent.averageMassFlows()[0]>0,"the gas leaves");
        // Every crystal stays: the same populations, their masses to the closure's roundoff (the frost stands in its
        // sublimation equilibrium with the gas, whose state the vent moves; 2026-10-18, the velocity cap in the law).
        var before=frosted.inventory().solids().populations();var kept=after.inventory().solids().populations();
        assertEquals(before.size(),kept.size(),"every crystal stays");
        for(int i=0;i<before.size();i++){assertEquals(before.get(i).material(),kept.get(i).material());assertEquals(before.get(i).size(),kept.get(i).size());
            assertEquals(before.get(i).massKg(),kept.get(i).massKg(),1e-12*before.get(i).massKg(),"every crystal stays");}
        assertTrue(vent.boundaries().stream().allMatch(b->b.solids().empty()),"none leaves with the gas");
        var warm=charge(pilot,250,3e5,new double[]{.9,0,0,.1,0},.1);var receiver=charge(pilot,250,101e3,new double[]{1,0,0,0,0},.1);
        var stuck=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,warm),new PassiveNetwork.Reservoir(2,0,receiver)),List.of(outlet.withBlockedDirections(3)));
        assertTrue(stuck.reservoirs().stream().allMatch(r->r.inventory().solids().empty()),"no solids left");
        assertFalse(NetworkReduction.atRest(stuck,pilot),"199 kPa across a closure is a drive at an interval start, not a rest");
        assertFalse(SolidMobility.monitored(stuck),"a gas island: the plain integrate solves it");
        var thaw=new PassiveIntervalSolver(pilot).solve(stuck,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        var donor=thaw.graph().reservoirs().get(0).state();var taker=thaw.graph().reservoirs().get(1).state();
        System.out.printf(Locale.ROOT,"the thawed outlet: %.6e kg/s for 5 s, %.1f Pa and %.1f Pa after, closures %d%n",thaw.averageMassFlows()[0],
                donor.pressure(),taker.pressure(),thaw.graph().pipes().getFirst().blockedDirections());
        assertTrue(thaw.averageMassFlows()[0]>0,"the gas flows through the outlet the frost era closed");
        assertTrue(donor.pressure()<warm.pressure()&&taker.pressure()>receiver.pressure(),"the tanks' pressures draw together");
        assertEquals(0,thaw.graph().pipes().getFirst().blockedDirections(),"no closure survives the interval");
    }
}
