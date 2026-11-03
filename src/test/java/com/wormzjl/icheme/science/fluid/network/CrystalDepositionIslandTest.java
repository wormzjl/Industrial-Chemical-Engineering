package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.NetworkCrystals;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid network on the crystal competition of the pilot package {@code icheme:pilot_cryogenic} (N2, CH4, C2H6, CO2
 * with the CO2-I crystal; basis N2, CH4, C2H6, CO2, water), crystals as solids (milestone M3 of the unified thermo port,
 * owner decisions D22 (5) and D23). A vessel's CO2-I is a population of its {@code SolidInventory}; only the vessel's
 * refresh at an interval start moves its mass, from the engine's crystal test and a closure with the crystal's amount
 * unknown; inside a step it is inert. Re-homed from the branch's P4/P5 scenarios, whose end states are functions of the
 * inventory and are kept; what moved is that a crystal appears at the refresh after the interval that supersaturated
 * the vessel, not within it, and that the crystal's energy and volume are the record's constants (taken at CO2-I's
 * normal sublimation point), so the engine's own UV answer is reproduced to the constants' departure from its crystal,
 * not to 1e-9.
 *
 * <ul>
 * <li>(a) a closed vessel of N2 + CO2 charged below its frost point deposits at its first refresh, CO2 conserved to
 * 1e-12, and is a fixed point afterwards;</li>
 * <li>(b) cooled and warmed back through the network's energy input, it deposits at the refresh after the cooling and
 * sublimes completely at the refresh after the warming, returning to its start to 1e-9;</li>
 * <li>a bulk withdrawal takes the crystal at its share of the vessel's mass, as main's withdrawal takes every solid;</li>
 * <li>(c) a pipe chain carrying CO2-laden N2 into a cold vessel deposits there only;</li>
 * <li>(d) a CO2-rich charge below the triple point freezes CO2 beside the ethane-rich liquid it leaves; liquid-full and
 * rigid, it passes the package's 10 MPa envelope, carried with a notice, not a hold (owner rule of 2026-10-16);</li>
 * <li>(h) liquid methane with dissolved CO2, with its vapour and liquid-full, freezes when cooled and dissolves when
 * warmed;</li>
 * <li>a crystal beside two liquids is the typed CRYSTAL_PHASES hold naming its node, a phase hold like any other.</li>
 * </ul>
 */
class CrystalDepositionIslandTest {
    static final String PILOT="icheme:pilot_cryogenic";
    static final String CO2_I="icheme:crystal_carbon_dioxide_i";
    static final int NITROGEN=0,METHANE=1,CO2=3;
    private static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidTestSupport.model(CATALOG,PILOT);
    private NetworkCrystals.Crystal co2(){return model.crystalSolids().all().getFirst();}

    /** {@code volume} m3 of {@code z} (basis N2, CH4, C2H6, CO2, water) at (T, P), stated by the network's own TP adapter. */
    private FluidThermodynamics.State charge(double t,double p,double[] z,double volume) {
        var unit=model.flashTP(t,p,z,()->{});
        double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=volume/unit.volume();
        return model.flashTP(t,p,n,()->{});
    }
    private static PassiveNetwork closed(FluidThermodynamics.State state){return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state)),List.of());}
    private PassiveIntervalSolver.Result interval(PassiveNetwork graph) {
        return new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
    }
    /** The island after its refresh: what the next interval starts from. */
    private PassiveNetwork refreshed(PassiveNetwork graph){return InventoryEquilibrium.refresh(graph,model,()->{}).graph();}
    /** Moles of CO2 in the inventory's crystal. */
    private double crystal(PassiveNetwork.Inventory inventory){double kg=0;for(var p:inventory.solids().crystals())kg+=p.massKg();return co2().moles(kg);}
    /** The inventory's species, the crystal's CO2 included. */
    private double[] species(PassiveNetwork.Inventory inventory){double[] n=inventory.moles();n[CO2]+=crystal(inventory);return n;}
    /** The inventory's energy on the network's datum: its stored energy and the crystal's offset (D23 (1)). */
    private static double trueEnergy(PassiveNetwork.Inventory inventory) {
        double energy=inventory.internalEnergy();for(var p:inventory.solids().crystals())energy+=p.massKg()*p.material().crystal().energyOffset();return energy;
    }
    /** The engine's UV answer of an inventory under the crystal competition: an independent service, its own crystal model. */
    private EquilibriumResult engineUv(PassiveNetwork.Inventory inventory) {
        var engine=FluidTpEquilibrium.forPackage(CATALOG,PILOT);
        double[] totals=species(inventory);
        var formation=model.hydrocarbon.spine().orElseThrow().formationReference();
        double energy=EnergyReference.sensible(formation.components()).rebase(trueEnergy(inventory),Arrays.copyOf(totals,totals.length-1),formation);
        return engine.uv(EquilibriumRequest.uv(energy,inventory.volume(),engine.contract().components(),totals,PhaseCompetition.FLUID_AND_CRYSTALS),engine.newWorkspace());
    }
    private static double solid(EquilibriumResult result) {
        double solid=0;for(var phase:result.phases())if(phase.kind()==PhaseKind.SOLID)solid+=phase.amount(CO2);return solid;
    }
    /** Prints the network's vessel against the engine's own answer and returns |dT|. */
    private double againstTheEngine(String label,PassiveNetwork.Reservoir node,EquilibriumResult uv) {
        var state=node.state();double crystal=crystal(node.inventory());
        double t=uv.phases().getFirst().temperature(),p=uv.phases().getFirst().pressure();
        System.out.printf(Locale.ROOT,"%s: network %.6f K %.1f Pa crystal %.9f mol | engine %s %.6f K %.1f Pa crystal %.9f mol%n",label,state.temperature(),state.pressure(),
                crystal,uv.classification(),t,p,solid(uv));
        return Math.abs(state.temperature()-t);
    }

    @Test void theCrystalsAreThePilotsSolidAndTheIceAndTheBundledNetworkPackageHasTheIceOnly() {
        var crystals=model.crystalSolids().all();assertEquals(2,crystals.size(),"CO2-I and, last, the free water's ice (WP4)");
        assertTrue(crystals.getLast().water());assertEquals("icheme:crystal_ice_ih",crystals.getLast().material().id());
        var crystal=crystals.getFirst();
        assertEquals(CO2_I,crystal.material().id());assertEquals(CO2,crystal.component());assertNotNull(crystal.material().crystal());
        assertEquals(194.627788,crystal.referenceTemperature(),1e-6,"its constants are taken at CO2-I's normal sublimation point");
        assertTrue(model.crystalsCompete(150,new double[]{0,.98,0,.02,0}),"a dry request carrying CO2 below the crystals' ceiling");
        assertFalse(model.crystalsCompete(150,new double[]{0,1,0,0,0}),"no CO2, no competition");
        assertTrue(model.flashTP(150,2e6,new double[]{0,.98,0,.02,0},()->{}).liquidVolume()>0,"CO2 dissolved in liquid methane below its triple point is evaluated");
        var bundled=FluidTestSupport.networkModel().crystalSolids().all();
        assertEquals(1,bundled.size(),"the bundled network package has no crystal competition: its one crystal is the free water's ice");
        assertTrue(bundled.getFirst().water());
    }

    /**
     * D10: the crystal's energy on the network datum is its model's less the formation enthalpy, so the sublimation enthalpy
     * is the family's (25,200.6 J/mol at the pure sublimation point, P4 stage 2a). The record's constants reproduce the
     * crystal's model at its reference temperature and depart from it by the integral of the heat capacity's change
     * elsewhere.
     */
    @Test void theCrystalsEnergyOnTheNetworkDatumCarriesTheFamilysSublimationEnthalpy() {
        var crystal=co2();var material=crystal.material();
        double offset=model.hydrocarbon.spine().orElseThrow().formationReference().offsetJoulesPerMole(CO2);
        double t=194.466712376,p=1e5;double[] none=new double[4];
        var vapour=model.state(t,p,none,new double[]{0,0,0,1},0,0,p);
        double sublimation=vapour.enthalpy()-enthalpy(material,crystal.molarMass(),t,p);
        System.out.printf(Locale.ROOT,"sublimation enthalpy on the network datum at %.9f K, %.0f Pa: %.4f J/mol; crystal %.4f kg/m3, %.4f J/(kg K)%n",t,p,sublimation,
                material.density(),material.heatCapacity());
        assertEquals(25200.6,sublimation,1.0,"P4 stage 2a: 25,200.6 J/mol from the engine's balance at the pure sublimation point");
        // At the reference, at the triple point, 150 K and 110 K: 0, 41, 169 and 594 J/mol (0.5 % of the fusion enthalpy at the
        // triple point, 0.7 and 2.3 % of the sublimation enthalpy at 150 and 110 K).
        for(double[] point:new double[][]{{194.627788,101325,1e-6},{216.59,518000,50},{150,1e5,200},{110,1e5,650}}) {
            double model=crystal.anchored().properties(point[0],point[1]).enthalpy()-offset,constants=enthalpy(material,crystal.molarMass(),point[0],point[1]);
            System.out.printf(Locale.ROOT,"crystal enthalpy at %.3f K: model %.3f J/mol, record's constants %+.3f J/mol from it%n",point[0],model,constants-model);
            assertEquals(model,constants,point[2],"the constants' departure from the crystal's model at "+point[0]+" K");
        }
    }
    private static double enthalpy(SolidMaterial material,double molarMass,double t,double p) {
        return molarMass*(material.crystal().energyOffset()+material.heatCapacity()*(t-SolidMaterial.REFERENCE_TEMPERATURE)+p/material.density());
    }

    /** (a) The first solve of a closed vessel charged below its frost point deposits; CO2 conserved to 1e-12. */
    @Test void aClosedVesselChargedBelowItsFrostPointDepositsAtItsFirstRefresh() {
        double[] z={.9,0,0,.1,0};
        var charged=charge(170,1e6,z,.1);
        assertTrue(charged.solids().empty());
        var island=closed(charged);var before=island.reservoirs().getFirst().inventory();
        var result=interval(island);
        var node=result.graph().reservoirs().getFirst();var after=node.inventory();var state=node.state();
        double crystal=crystal(after),co2=before.moles()[CO2];
        System.out.printf(Locale.ROOT,"(a) 10%% CO2 in N2, 0.1 m3 charged at 170 K, 1 MPa: %.6f K, %.3f Pa, crystal %.9f mol of %.9f mol CO2 (%.3f %%), gas y_CO2 %.6e, size %s m%n",
                state.temperature(),state.pressure(),crystal,co2,100*crystal/co2,state.vaporView()[CO2]/Arrays.stream(state.vaporView()).sum(),after.solids().size(CO2_I).metres());
        assertTrue(crystal>0,"deposited at the first refresh");
        assertEquals(co2,species(after)[CO2],1e-12*co2,"CO2 conserved");
        assertEquals(before.moles()[NITROGEN],after.moles()[NITROGEN],0,"the nitrogen never moves");
        assertEquals(trueEnergy(before),trueEnergy(after),1e-12*Math.abs(trueEnergy(before)),"a closed adiabatic vessel keeps its energy");
        assertEquals(before.volume(),after.volume(),0);
        assertEquals(after.solids(),state.solids(),"the state carries the inventory's crystal");
        assertTrue(state.temperature()>170&&state.temperature()<192.754773,"the latent heat warms the vessel toward its frost point: "+state.temperature());
        // The engine's own UV answer for the vessel's inventory, from an independent service: the same split, to the
        // departure of the record's constants from the engine's crystal (here 9 K below their reference).
        var uv=engineUv(after);
        double dt=againstTheEngine("(a)",node,uv);
        assertEquals(EquilibriumResult.Classification.VAPOR_SOLID,uv.classification());
        assertTrue(dt<.05,"temperature within 0.05 K of the engine's: "+dt);
        assertEquals(solid(uv),crystal,2e-3*co2,"crystal amount");
        // Rest: the next interval changes nothing, bit for bit.
        var again=interval(result.graph());
        assertEquals(after,again.graph().reservoirs().getFirst().inventory(),"an equilibrated vessel is a fixed point");
    }

    /**
     * (b) The vessel cooled by 3000 J and warmed back by 3000 J through a scheduled injection (a 1e-12 mol/s nitrogen
     * trace with a stated energy rate of -600 and +600 W over 5 s). One mole of 10 % CO2 in N2 at 200 K and 1 MPa, the
     * P4 stage 2a fixed-volume reversibility case (163.922352 K, 0.742346 MPa, 0.09265646 mol of crystal on the engine
     * alone). The record's constants put the crystal 0.13 kJ/mol below the engine's at 164 K: 12 J over the 0.09 mol of
     * crystal, which in a vessel of one mole of gas is 0.17 K, 0.9 kPa and 0.1 % of the crystal (the gas ends warmer). The
     * cooled gas holds its CO2 through the cooling interval and deposits at the next refresh; the warmed
     * vessel keeps its crystal through the warming interval, inert, and the next refresh sublimes it completely: the
     * vessel returns to its start to 1e-9.
     */
    @Test void theVesselCooledAndWarmedBackByTheNetworksEnergyInputSublimesItsCrystalBack() {
        double[] z={.9,0,0,.1,0};
        var start=closed(model.flashTP(200,1e6,z,()->{}));var initial=start.reservoirs().getFirst();
        double trace=1e-12;
        var cold=heat(start,-600,trace,NITROGEN);
        var cooling=cold.graph().reservoirs().getFirst();
        assertTrue(cooling.inventory().solids().empty(),"no crystal inside the cooling interval: only a refresh moves crystal mass");
        assertEquals(initial.inventory().internalEnergy()-3000,cooling.inventory().internalEnergy(),1e-9*3000);
        var settled=refreshed(cold.graph());var cooled=settled.reservoirs().getFirst();double crystal=crystal(cooled.inventory());
        System.out.printf(Locale.ROOT,"(b) cooled by 3000 J: %.6f K supersaturated; at the refresh %.6f K, %.3f Pa, crystal %.8f mol (%d substeps, %d rejected)%n",
                cooling.state().temperature(),cooled.state().temperature(),cooled.state().pressure(),crystal,cold.acceptedSubsteps(),cold.rejectedSubsteps());
        assertTrue(crystal>0,"deposited");
        assertEquals(trueEnergy(cooling.inventory()),trueEnergy(cooled.inventory()),1e-12*Math.abs(trueEnergy(cooling.inventory())));
        assertEquals(163.922352,cooled.state().temperature(),.25,"the engine's fixed-volume answer (P4 stage 2a), to the record's constants");
        assertTrue(cooled.state().temperature()>163.922352,"the constants' larger latent heat leaves the gas warmer");
        assertEquals(742346,cooled.state().pressure(),2000,"the engine's fixed-volume answer");
        assertEquals(.09265646,crystal,5e-4,"the engine's fixed-volume answer");
        var warm=heat(settled,600,trace,NITROGEN);
        var warming=warm.graph().reservoirs().getFirst();
        assertEquals(cooled.inventory().solids(),warming.inventory().solids(),"the crystal is inert inside the warming interval");
        var warmed=refreshed(warm.graph()).reservoirs().getFirst();
        System.out.printf(Locale.ROOT,"(b) warmed back by 3000 J: %.6f K with the crystal; at the refresh %.12f K, %.6f Pa, solids %s%n",warming.state().temperature(),
                warmed.state().temperature(),warmed.state().pressure(),warmed.inventory().solids());
        assertTrue(warmed.inventory().solids().empty(),"the crystal sublimed back completely");
        double co2=initial.inventory().moles()[CO2];
        assertEquals(co2,warmed.inventory().moles()[CO2],1e-12*co2,"CO2 conserved through deposition and sublimation");
        assertEquals(initial.inventory().internalEnergy(),warmed.inventory().internalEnergy(),1e-9*3000);
        assertEquals(initial.state().temperature(),warmed.state().temperature(),1e-9*initial.state().temperature(),"reversible: temperature");
        assertEquals(initial.state().pressure(),warmed.state().pressure(),1e-9*initial.state().pressure(),"reversible: pressure");
    }
    /** One 5 s interval with a scheduled injection of a trace of species {@code carrier} carrying {@code watts} into node 0;
     * the returned graph has no transfer. */
    private PassiveIntervalSolver.Result heat(PassiveNetwork graph,double watts,double trace,int carrier) {
        double[] rate=new double[5];rate[carrier]=trace;
        var result=interval(new PassiveNetwork(graph.reservoirs(),graph.pipes(),List.of(new ScheduledTransfer.Injection(100,0,rate,watts))));
        return new PassiveIntervalSolver.Result(new PassiveNetwork(result.graph().reservoirs(),result.graph().pipes()),result.advancedSeconds(),result.averageMassFlows(),
                result.acceptedSubsteps(),result.rejectedSubsteps(),result.pumpWorkJoule(),result.boundaries(),result.rejectionReasons(),result.endpointModes(),
                result.endpointHeads(),result.endpointFlows(),result.pipeTransfers());
    }

    /**
     * A bulk withdrawal (a module's scheduled withdrawal) from a vessel holding crystals takes them at their share of the
     * vessel's mass, as main's withdrawal takes every solid (D23 (6): one inventory). CO2 in the vessel, its crystal and the
     * withdrawn stream balance to 1e-12. The gas left behind expanded and cooled: the next refresh deposits more.
     */
    @Test void aBulkWithdrawalTakesTheCrystalAtItsShareOfTheVesselsMass() {
        var deposited=interval(closed(charge(170,1e6,new double[]{.9,0,0,.1,0},.1))).graph();
        var before=deposited.reservoirs().getFirst();double crystal=crystal(before.inventory()),mass=before.state().mass();
        var result=interval(new PassiveNetwork(deposited.reservoirs(),List.of(),List.of(new ScheduledTransfer.Withdrawal(100,0,1e-2))));
        var after=result.graph().reservoirs().getFirst();
        double[] out=new double[5];double crystalOut=0,massOut=0;
        for(var boundary:result.boundaries()) {
            var n=boundary.moles();for(int c=0;c<5;c++){out[c]+=n[c];massOut-=n[c]*model.molecularWeights()[c];}
            for(var p:boundary.solids().crystals()){crystalOut+=boundary.solidDirection()*co2().moles(p.massKg());massOut-=boundary.solidDirection()*p.massKg();}
        }
        var next=refreshed(result.graph()).reservoirs().getFirst();
        System.out.printf(Locale.ROOT,"withdrawal: %.6f kg out (%.6f of the vessel's %.6f kg), crystal out %.9f mol of %.9f; vessel crystal %.9f -> %.9f mol, %.9f at the next refresh%n",
                massOut,massOut/mass,mass,-crystalOut,crystal,crystal,crystal(after.inventory()),crystal(next.inventory()));
        assertTrue(massOut>0,"material left the vessel");
        assertEquals(crystal*massOut/mass,-crystalOut,1e-6*crystal,"the crystal leaves at its share of the vessel's mass");
        assertEquals(crystal+crystalOut,crystal(after.inventory()),1e-12*crystal,"inside the interval the crystal only leaves");
        double co2=species(before.inventory())[CO2];
        assertEquals(co2,species(after.inventory())[CO2]-out[CO2]-crystalOut,1e-12*co2,"CO2 of the vessel, its crystal and the stream");
        assertTrue(crystal(next.inventory())>crystal(after.inventory()),"the expanded gas deposits at the next refresh");
    }

    /** (c) CO2-laden N2 flows from a warm vessel through a junction into a cold one and deposits there only. */
    @Test void aPipeChainCarryingCo2LadenNitrogenIntoAColdVesselDepositsOnlyThere() {
        var warm=charge(250,1.3e6,new double[]{.9,0,0,.1,0},.1);
        var cold=charge(140,1e6,new double[]{1,0,0,0,0},.1);
        var pipe=new PipeResistance.Geometry(1,.004,.000045,0);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,warm),new PassiveNetwork.Reservoir(2,0,warm,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,cold)),
                List.of(new PassiveNetwork.Pipe(10,0,1,pipe),new PassiveNetwork.Pipe(11,1,2,pipe)));
        var solver=new PassiveIntervalSolver(model);double step=1;double co2=-1;
        for(int k=0;k<6;k++) {
            var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},step);step=solver.nextStepEstimate();
            // The junction's buffer is sized at the first solve; from there on it is inventory of the island (D46).
            if(co2<0)co2=total(PassiveNetwork.sizeJunctionHoldups(graph,model));
            graph=result.graph();
            var a=graph.reservoirs().get(0);var j=graph.reservoirs().get(1);var b=graph.reservoirs().get(2);
            System.out.printf(Locale.ROOT,"(c) interval %d: warm %.3f K %.1f Pa crystal %.3e | junction %.3f K | cold %.3f K %.1f Pa crystal %.6e mol, gas y_CO2 %.3e%n",k+1,
                    a.state().temperature(),a.state().pressure(),crystal(a.inventory()),j.state().temperature(),b.state().temperature(),b.state().pressure(),crystal(b.inventory()),
                    b.state().vaporView()[CO2]/Arrays.stream(b.state().vaporView()).sum());
            assertTrue(a.inventory().solids().empty(),"no deposition in the warm vessel");
            assertTrue(j.inventory().solids().empty()&&j.state().solids().empty(),"a junction never precipitates (D23 (5))");
            assertEquals(co2,total(graph),1e-10*co2,"CO2 conserved over the island (vessels, their crystals and the junction's buffer)");
        }
        assertTrue(crystal(graph.reservoirs().get(2).inventory())>0,"the cold vessel deposits");
    }
    private double total(PassiveNetwork graph){double co2=0;for(var node:graph.reservoirs())co2+=species(node.inventory())[CO2];return co2;}

    /**
     * (d) A CO2-rich charge below CO2's triple point whose deposition leaves a liquid (70 % CO2 in ethane at 200 K: the
     * fluid the crystal leaves is ethane-rich, a liquid at 200 K). Charged at 2 MPa the rigid liquid-full vessel's answer
     * lies above the package's 10 MPa envelope (the latent heat of the freezing CO2 warms a nearly incompressible liquid):
     * carried on the equation of state's continuation, a notice and not a hold (owner rule of 2026-10-16); charged at 0.5 MPa the vessel freezes CO2 beside the liquid at its first refresh: CO2
     * conserved to 1e-12, the engine's UV answer reproduced to the record's constants, and the next interval changes
     * nothing.
     */
    @Test void aCo2RichChargeBelowTheTriplePointFreezesBesideTheLiquidItLeaves() {
        double[] feed={0,0,.3,.7,0};
        var direct=model.flashTP(200,2e6,feed,()->{});
        assertNotNull(direct.liquidProperties(),"the TP adapter states the supersaturated liquid in the liquid slot");
        var rigid=interval(closed(charge(200,2e6,feed,1e-3))).graph().reservoirs().getFirst();
        System.out.printf(Locale.ROOT,"(d) charged at 2 MPa: %.6f K, %.0f Pa, crystal %.9f mol%n",rigid.state().temperature(),rigid.state().pressure(),crystal(rigid.inventory()));
        assertTrue(rigid.state().pressure()>1e7,"above the 10 MPa envelope, carried: "+rigid.state().pressure());
        assertTrue(crystal(rigid.inventory())>0,"CO2 froze beside the liquid");
        var island=closed(charge(200,5e5,feed,1e-3));var before=island.reservoirs().getFirst().inventory();
        var result=interval(island);
        var node=result.graph().reservoirs().getFirst();var after=node.inventory();var state=node.state();
        double crystal=crystal(after),co2=before.moles()[CO2];
        System.out.printf(Locale.ROOT,"(d) 70%% CO2 in ethane charged at 200 K, 0.5 MPa: %.6f K, %.3f Pa, crystal %.9f of %.9f mol CO2, liquid x_CO2 %.6f, size %s m%n",
                state.temperature(),state.pressure(),crystal,co2,state.liquidView()[CO2]/Arrays.stream(state.liquidView()).sum(),after.solids().size(CO2_I).metres());
        assertTrue(crystal>0,"CO2 froze beside the liquid");
        assertNotNull(state.liquidProperties());
        assertEquals(co2,species(after)[CO2],1e-12*co2,"CO2 conserved");
        assertEquals(trueEnergy(before),trueEnergy(after),1e-12*Math.abs(trueEnergy(before)));assertEquals(before.volume(),after.volume(),0);
        var uv=engineUv(after);
        double dt=againstTheEngine("(d)",node,uv);
        assertTrue(uv.classification()==EquilibriumResult.Classification.LIQUID_SOLID||uv.classification()==EquilibriumResult.Classification.VAPOR_LIQUID_SOLID,uv.toString());
        assertTrue(dt<.05,"temperature within 0.05 K of the engine's: "+dt);
        assertEquals(solid(uv),crystal,2e-3*co2,"crystal amount");
        var again=interval(result.graph());
        assertEquals(after,again.graph().reservoirs().getFirst().inventory(),"an equilibrated vessel is a fixed point");
    }

    /**
     * (h) Liquid methane with 2 % dissolved CO2 and its vapour, one mole charged at 172 K and 2.45 MPa (a vapour-liquid
     * split above the binary's three-phase line, 19 % vapour, no crystal), cooled by 1500 J through the network's energy
     * input (-150 W over two 5 s intervals): CO2 freezes out of the liquid at the refreshes, and the binary sits on its
     * three-phase line. CO2 conserved to 1e-12 and the engine's UV answer reproduced to the record's constants; warmed
     * back by 1500 J the crystal dissolves completely at the refresh and the vessel returns to its start (the heat carried
     * by a methane trace).
     */
    @Test void aLiquidMethaneVesselWithDissolvedCo2FreezesWhenCooledAndDissolvesWhenWarmed() {
        double[] z={0,.98,0,.02,0};
        var unit=model.flashTP(172,2.45e6,z,()->{});
        assertNotNull(unit.liquidProperties());assertNotNull(unit.vaporProperties(),"a liquid with its vapour");
        var start=closed(unit);var initial=start.reservoirs().getFirst();
        assertTrue(interval(start).graph().reservoirs().getFirst().inventory().solids().empty(),"above its three-phase line nothing freezes");
        double trace=1e-12;
        var cold=heat(heat(start,-150,trace,METHANE).graph(),-150,trace,METHANE);
        var cooled=refreshed(cold.graph()).reservoirs().getFirst();double crystal=crystal(cooled.inventory());
        double co2=initial.inventory().moles()[CO2];
        System.out.printf(Locale.ROOT,"(h) cooled by 1500 J: %.6f K, %.3f Pa, crystal %.9f mol, liquid x_CO2 %.6f, vapour y_CO2 %.6e, size %s m (%d substeps, %d rejected)%n",
                cooled.state().temperature(),cooled.state().pressure(),crystal,cooled.state().liquidView()[CO2]/Arrays.stream(cooled.state().liquidView()).sum(),
                cooled.state().vaporView()[CO2]/Arrays.stream(cooled.state().vaporView()).sum(),cooled.inventory().solids().empty()?"-":cooled.inventory().solids().size(CO2_I).metres(),
                cold.acceptedSubsteps(),cold.rejectedSubsteps());
        assertTrue(crystal>0,"CO2 froze out of the liquid");
        assertNotNull(cooled.state().liquidProperties(),"beside a liquid");
        assertEquals(co2,species(cooled.inventory())[CO2],1e-12*co2,"CO2 conserved");
        assertEquals(trueEnergy(initial.inventory())-1500,trueEnergy(cooled.inventory()),1e-9*1500);
        var uv=engineUv(cooled.inventory());
        double dt=againstTheEngine("(h)",cooled,uv);
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID_SOLID,uv.classification());
        assertTrue(dt<.05,"temperature within 0.05 K of the engine's: "+dt);
        assertEquals(solid(uv),crystal,2e-3*co2,"crystal amount");
        var warm=heat(heat(refreshed(cold.graph()),150,trace,METHANE).graph(),150,trace,METHANE);
        var warmed=refreshed(warm.graph()).reservoirs().getFirst();
        double dT=warmed.state().temperature()/initial.state().temperature()-1,dp=warmed.state().pressure()/initial.state().pressure()-1;
        System.out.printf(Locale.ROOT,"(h) warmed back by 1500 J: %.12f K (%+.2e), %.6f Pa (%+.2e), solids %s%n",warmed.state().temperature(),dT,
                warmed.state().pressure(),dp,warmed.inventory().solids());
        assertTrue(warmed.inventory().solids().empty(),"the crystal dissolved back completely");
        assertEquals(co2,warmed.inventory().moles()[CO2],1e-12*co2,"CO2 conserved through freezing and dissolution");
        assertEquals(initial.inventory().internalEnergy(),warmed.inventory().internalEnergy(),1e-9*1500);
        assertEquals(0,dT,1e-9,"reversible: temperature");
        assertEquals(0,dp,1e-9,"reversible: pressure");
    }

    /**
     * (h) A liquid-full vessel: 1 L of compressed liquid methane with 3 % dissolved CO2 charged at 170 K and 9 MPa (21.4 mol,
     * no vapour), cooled by 3000 J through the network's energy input (-200 W over three 5 s intervals, a methane trace):
     * the rigid liquid cools and its pressure falls, it passes its liquidus while still liquid-full and freezes CO2 beside
     * the liquid alone (LIQUID_SOLID, no vapour), conserving CO2 to 1e-12 and reproducing the engine's UV answer to the
     * record's constants; warmed back by 3000 J the crystal dissolves completely and the vessel returns to its start.
     */
    @Test void aLiquidFullVesselFreezesBelowItsLiquidusBesideTheLiquidAloneAndDissolvesWhenWarmed() {
        double[] z={0,.97,0,.03,0};
        var start=closed(charge(170,9e6,z,1e-3));var initial=start.reservoirs().getFirst();
        assertNotNull(initial.state().liquidProperties());assertNull(initial.state().vaporProperties(),"liquid-full");
        double trace=1e-12;
        var cold=heat(heat(heat(start,-200,trace,METHANE).graph(),-200,trace,METHANE).graph(),-200,trace,METHANE);
        var cooled=refreshed(cold.graph()).reservoirs().getFirst();double crystal=crystal(cooled.inventory());
        double co2=initial.inventory().moles()[CO2];
        System.out.printf(Locale.ROOT,"(h) liquid-full, cooled by 3000 J: %.6f K, %.3f Pa, crystal %.9f of %.9f mol CO2, liquid x_CO2 %.6f, vapour %s (%d substeps, %d rejected)%n",
                cooled.state().temperature(),cooled.state().pressure(),crystal,co2,cooled.state().liquidView()[CO2]/Arrays.stream(cooled.state().liquidView()).sum(),
                cooled.state().vaporProperties()==null?"none":"present",cold.acceptedSubsteps(),cold.rejectedSubsteps());
        assertTrue(crystal>0,"CO2 froze out of the liquid");
        assertNotNull(cooled.state().liquidProperties());assertNull(cooled.state().vaporProperties(),"still liquid-full: the liquid alone beside the crystal");
        assertEquals(co2,species(cooled.inventory())[CO2],1e-12*co2,"CO2 conserved");
        assertEquals(trueEnergy(initial.inventory())-3000,trueEnergy(cooled.inventory()),1e-9*3000);
        var uv=engineUv(cooled.inventory());
        double dt=againstTheEngine("(h) liquid-full",cooled,uv);
        assertEquals(EquilibriumResult.Classification.LIQUID_SOLID,uv.classification());
        assertTrue(dt<.05,"temperature within 0.05 K of the engine's: "+dt);
        assertEquals(solid(uv),crystal,2e-3*co2,"crystal amount");
        var warm=heat(heat(heat(refreshed(cold.graph()),200,trace,METHANE).graph(),200,trace,METHANE).graph(),200,trace,METHANE);
        var warmed=refreshed(warm.graph()).reservoirs().getFirst();
        double dT=warmed.state().temperature()/initial.state().temperature()-1,dp=warmed.state().pressure()/initial.state().pressure()-1;
        System.out.printf(Locale.ROOT,"(h) liquid-full, warmed back by 3000 J: %.12f K (%+.2e), %.6f Pa (%+.2e), solids %s, energy %+.3e J%n",warmed.state().temperature(),dT,
                warmed.state().pressure(),dp,warmed.inventory().solids(),warmed.inventory().internalEnergy()-initial.inventory().internalEnergy());
        assertTrue(warmed.inventory().solids().empty(),"the crystal dissolved back completely");
        assertEquals(co2,warmed.inventory().moles()[CO2],1e-12*co2,"CO2 conserved through freezing and dissolution");
        assertEquals(initial.inventory().internalEnergy(),warmed.inventory().internalEnergy(),1e-9*3000);
        assertEquals(0,dT,1e-9,"reversible: temperature");
        assertEquals(0,dp,1e-9,"reversible: pressure");
    }

    /**
     * The crystal-phase hold that remains (two liquids beside the crystal: N2 + C2H6 of type III with 5 % CO2 at 95 K and
     * 2 MPa). A vessel's refresh, the only place a crystal competes (D23), meets the typed CRYSTAL_PHASES hold and names its
     * node. A step's TP answer carries the crystal, so it meets the two liquids themselves: neither is crystal-rich, and
     * they are the typed LIQUID_LIQUID hold.
     * Both are phase holds ({@link com.wormzjl.icheme.science.fluid.thermo.ThermoHold}), which an island parks on
     * when its retry meets the same one.
     */
    @Test void aCrystalBesideTwoLiquidsIsTheTypedCrystalPhasesHold() {
        double[] feed={40,0,55,5,0};double[] none=new double[4];
        var liquid=model.state(95,2e6,Arrays.copyOf(feed,4),none,0,0,2e6);
        var island=closed(liquid);
        var direct=assertThrows(FluidThermodynamics.UnsupportedPhases.class,()->model.flashTP(95,2e6,feed,()->{}));
        System.out.println("TP at 95 K, 2 MPa: "+direct.getMessage());
        assertEquals(FluidThermodynamics.UnsupportedPhases.Kind.LIQUID_LIQUID,direct.kind());
        RuntimeException refused=assertThrows(RuntimeException.class,()->interval(island));
        System.out.println("refresh: "+refused.getMessage());
        assertInstanceOf(FluidThermodynamics.UnsupportedPhases.class,refused,"the typed hold: "+refused);
        var hold=(FluidThermodynamics.UnsupportedPhases)refused;
        assertEquals(FluidThermodynamics.UnsupportedPhases.Kind.CRYSTAL_PHASES,hold.kind());assertEquals(1,hold.node(),"names its node");
        assertTrue(hold.getMessage().contains("NOT_IMPLEMENTED (CRYSTAL_PHASES)"),hold.getMessage());
        assertTrue(hold.reasonKey().startsWith("phase-hold: phases CRYSTAL_PHASES"),hold.reasonKey());
        assertSame(hold,SparseNewton.phaseHold(refused),"a phase hold, which an island parks on");
    }
}
