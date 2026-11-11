package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * A rigid vessel (batch 2026-11-10-rigid-reservoir, RIGID_RESERVOIR_PLAN.md section 2; owner: "Resevoir should be rigid when
 * it's filled once (except the floating roof tank)"): a closed vessel whose wall has once carried its contents keeps its
 * volume, so drawing from it lowers its pressure where a bag (decision D54) shrinks at the ambient. The plan's numbers: a
 * 100 m3 tank at 25 °C drawn 10 m3, liquid-full (water's vapour pressure, 3.17 kPa) and under nitrogen (68.6 kPa); a 1 m3
 * tank drained by gravity stops where its gas and level balance the ambient; a bag filled taut becomes rigid.
 */
class RigidVesselTest extends PortFixture {
    private static final double T=298.15;

    /** {@code state} scaled to {@code volume} m3, as a vessel's inventory, rigid when {@code rigid}. */
    private static PassiveNetwork.Inventory inventory(FluidThermodynamics.State state,double volume,boolean rigid) {
        return new PassiveNetwork.Inventory(volume,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy()).withRigid(rigid);
    }
    /** {@code state}'s amounts times {@code k}, flashed at its temperature and pressure. */
    private FluidThermodynamics.State scaled(FluidThermodynamics.State state,double k) {
        var n=PhaseLayout.totalAmounts(state);for(int c=0;c<n.length;c++)n[c]*=k;return model.flashTP(state.temperature(),state.pressure(),n,NOOP);
    }
    /** {@code full} less {@code cubic} m3 of its water at 25 °C, closed in its vessel. */
    private FluidThermodynamics.State drawn(FluidThermodynamics.State full,double volume,double cubic,boolean rigid) {
        double removed=cubic*997/mw[water];
        var one=new double[mw.length];one[water]=1;double u=model.flashTP(T,101325,one,NOOP).internalEnergy();
        var n=PhaseLayout.totalAmounts(full);n[water]-=removed;
        var inventory=new PassiveNetwork.Inventory(volume,n,full.internalEnergy()-removed*u).withRigid(rigid);
        return InventoryEquilibrium.solve(model,full,inventory,Double.NaN,NOOP);
    }

    /**
     * A liquid-full rigid 1 m3 water tank 9.5 m over a void at the ambient (a barometric leg): it drains until its steam,
     * at the water's vapour pressure, and its level balance the ambient, P_sat + rho g (h + 9.5 m) = P_amb, a 0.53 m level
     * under 3.17 kPa of steam. A bag would drain dry.
     */
    @Test void aLiquidFullRigidTankDrainsUntilItsSteamAndLevelBalanceTheAmbient() {
        var n=new double[mw.length];n[water]=997/mw[water];
        var full=model.flashTP(T,1.5e5,n,NOOP);full=scaled(full,1/full.volume());
        var vessel=new PassiveNetwork.Reservoir(1,9.5,full,PassiveNetwork.NodeKind.RESERVOIR,inventory(full,1,true));
        var exit=new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID);
        var line=new PassiveNetwork.Pipe(1,0,1,List.of(new PipeResistance.Geometry(10,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK);
        var result=new PassiveIntervalSolver(model).solve(new PassiveNetwork(List.of(vessel,exit),List.of(line)),600,PassiveIntervalSolver.Settings.defaults(),NOOP);
        var end=result.graph().reservoirs().getFirst();var s=end.state();double level=(TestPhases.liquidVolume(s)+TestPhases.waterVolume(s))/end.inventory().volume();
        System.out.println("RIGID_RUN barometric: level="+level+" P="+s.pressure()+" Pa steam="+TestPhases.waterVapor(s)*mw[water]+" kg advanced="+result.advancedSeconds());
        assertEquals(600,result.advancedSeconds(),1e-9);
        assertEquals(3170,s.pressure(),100,"the water's vapour pressure at 25 °C");
        assertEquals(.53,level,.05,"(101.3 - 3.2) kPa / rho g - 9.5 m");
    }

    /**
     * 80 m3 of water under 20 m3 of nitrogen at 1 atm drawn 10 m3: the gas expands to 30 m3; a bag stays at the ambient. W3b:
     * the water holds 48 mol of the nitrogen at 98.2 kPa (Henry, H = 9.1e9 Pa), which leaves it as the pressure falls: 66.5
     * kPa of nitrogen instead of 65.4, with the water's 3.2, 69.7 kPa (68.6 before, the free water dissolving none).
     */
    @Test void aBlanketedRigidTankDrawnTenPercentFallsBelowTheAmbientAndABagDoesNot() {
        var full=scaled(waterUnderNitrogen(.8,101325),100);
        var rigid=drawn(full,100,10,true);var bag=drawn(full,100,10,false);
        System.out.println("RIGID_RUN blanketed: rigid P="+rigid.pressure()+" Pa V="+rigid.volume()+"; bag P="+bag.pressure()+" Pa V="+bag.volume());
        assertEquals(100,rigid.volume(),1e-6);
        assertEquals(69.7e3,rigid.pressure(),.6e3,"98.2 kPa of nitrogen to 66.5 with what the water gives up, and the water's 3.2");
        assertEquals(101325,bag.pressure(),1,"the bag shrinks at the ambient");
        assertEquals(90,bag.volume(),.2);
    }

    /** The plan's 1 m3 tank of 0.8 m3 water under nitrogen at 1 atm, drained by gravity to a void at its bottom: rigid, it stops near a 0.783 m level, its gas at 91 kPa; a bag lets it run. */
    @Test void aRigidTankDrainedByGravityStopsWhereItsGasAndLevelBalanceTheAmbient() {
        double rigidLevel=drain(true),bagLevel=drain(false);
        System.out.println("RIGID_RUN gravity: rigid level="+rigidLevel+" bag level="+bagLevel);
        assertEquals(.783,rigidLevel,.01,"98.2 kPa x 0.2 / (1 - h) + 3.2 kPa + rho g h = 101.3 kPa");
        assertTrue(bagLevel<.4,"the bag drains on: "+bagLevel);
    }
    /** The liquid share left after 600 s of a gravity drain. */
    private double drain(boolean rigid) {
        var start=waterUnderNitrogen(.8,101325);
        // The bag 2 % larger than its contents, so it stays slack (full at the ambient it would be filled, and rigid).
        var vessel=new PassiveNetwork.Reservoir(1,0,start,PassiveNetwork.NodeKind.RESERVOIR,inventory(start,rigid?1:1.02,rigid));
        var exit=new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID);
        var line=new PassiveNetwork.Pipe(1,0,1,List.of(new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK);
        var graph=new PassiveNetwork(List.of(vessel,exit),List.of(line));
        var result=new PassiveIntervalSolver(model).solve(graph,600,PassiveIntervalSolver.Settings.defaults(),NOOP);
        assertEquals(600,result.advancedSeconds(),1e-9);
        var end=result.graph().reservoirs().getFirst();
        assertEquals(rigid,end.inventory().rigid(),"a rigid tank stays rigid, a slack bag a bag");
        return (TestPhases.liquidVolume(end.state())+TestPhases.waterVolume(end.state()))/end.inventory().volume();
    }

    /** A bag filled taut from a 2 bar nitrogen supply is rigid at the step's end; an open vessel never is. */
    @Test void aBagFilledTautBecomesRigidAndAnOpenVesselNever() {
        var half=nitrogenAt(101325);half=scaled(half,.5);
        var bag=new PassiveNetwork.Reservoir(1,0,half,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(half),half.internalEnergy()));
        var supply=new PassiveNetwork.Reservoir(2,0,nitrogenAt(2e5),PassiveNetwork.NodeKind.GENERATOR);
        var line=new PassiveNetwork.Pipe(1,1,0,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0));
        var graph=new PassiveNetwork(List.of(bag,supply),List.of(line));
        var result=new PassiveIntervalSolver(model).solve(graph,60,PassiveIntervalSolver.Settings.defaults(),NOOP);
        var end=result.graph().reservoirs().getFirst();
        System.out.println("RIGID_RUN filled: P="+end.state().pressure()+" rigid="+end.inventory().rigid());
        assertTrue(end.state().pressure()>1.5e5,"it fills toward the supply: "+end.state().pressure());
        assertTrue(end.inventory().rigid(),"its wall carried its contents: rigid from now on");
        assertThrows(IllegalArgumentException.class,()->new PassiveNetwork.Inventory(1,new double[mw.length],0,com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY,1,true,
                VesselGeometry.prism(1,1),null,null,true),"an open vessel is never rigid");
        var open=new PassiveNetwork.Inventory(1,new double[mw.length],0,com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY,1,true);
        assertSame(open,open.withRigid(true));
        assertEquals(-1,inventory(half,1,true).wallCapacity(),0,"the rigid wall's capacity token");
        assertEquals(1,inventory(half,1,true).emptyBag(),0);
        assertEquals(0,inventory(half,1,true).wallWorkPressure(),0);
    }
}
