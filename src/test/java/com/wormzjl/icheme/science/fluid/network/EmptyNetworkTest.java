package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmptyNetworkTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private PassiveNetwork.Reservoir empty() {
        // The retained guess cannot become inventory or be used to infer a vacuum temperature.
        var guess=model.initialNitrogenCharge(1,298.15,101325,()->{});
        return new PassiveNetwork.Reservoir(1,0,guess,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],0));
    }
    @Test void isolatedEvacuatedReservoirAdvancesWithoutEosEvaluationOrInventedGas() {
        var initial=new PassiveNetwork(List.of(empty()),List.of());assertTrue(initial.reservoirs().getFirst().empty());
        var result=new PassiveIntervalSolver(model).solve(initial,20,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(20,result.advancedSeconds());assertTrue(result.graph().reservoirs().getFirst().empty());
        assertArrayEquals(new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],result.graph().reservoirs().getFirst().inventory().moles());assertEquals(0,result.graph().reservoirs().getFirst().inventory().internalEnergy());
        assertEquals(0,result.averageMassFlows().length);assertTrue(result.boundaries().isEmpty());assertEquals(initial,result.graph());
    }
    /**
     * Owner decision D54: a vessel that holds nothing is a slack bag at the ambient. A 2 bar nitrogen generator fills it
     * (it was refused before: an evacuated vessel had no state to fill from); it gives nothing back, and the step's ledger
     * books what arrived, less the wall's work on the ambient, in the vessel.
     */
    @Test void anEmptyVesselFillsFromWhatArrivesAndGivesNothingBack() {
        var empty=empty();var source=new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,200000,()->{}),PassiveNetwork.NodeKind.GENERATOR);
        var graph=new PassiveNetwork(List.of(empty,source),List.of(new PassiveNetwork.Pipe(3,1,0,new PipeResistance.Geometry(1,.05,0,0))));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});
        assertTrue(result.massFlows()[0]>0,"the generator fills the vessel: "+result.massFlows()[0]);
        var filled=result.inventories().get(0);double held=0;for(double n:filled.moles())held+=n;
        assertTrue(held>0,"the vessel holds what arrived");
        var state=result.states().get(0);assertTrue(state.pressure()>=PhaseLayout.AMBIENT_PRESSURE*(1-1e-9),"no vacuum: "+state.pressure());
        assertArrayEquals(new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],empty.inventory().moles());assertEquals(0,empty.inventory().internalEnergy());
    }

    // ---------------- from HoldsNothingTest ----------------
    /*
     * Owner decision D59 (2), t28: one meaning of holding nothing. A vessel holds nothing when its bag holds at most the bag's
     * resolution, 1e-6 of its capacity (t38 form C, 2026-10-18: a millilitre in a cubic metre; 1e-12 before, A311, the share
     * at which the bag's volume is already snapped to the capacity). Below it, what a vessel holds has no pressure of its
     * own (a residue a junction's buffer leaves in an emptied tank, the first drops a trickle delivers): an end holding it
     * gives nothing, and the rest test does not count it as stock. The residue stays booked in the inventory.
     */
    /** A 1 m3 vessel holding nitrogen at 298.15 K and 1 atm whose bag is {@code share} of its capacity. */
    private PassiveNetwork.Reservoir vessel(double share) {
        var state=model.initialNitrogenCharge(share,298.15,101325,()->{});
        return new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,
                new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()));
    }

    @Test void aBagAtOrBelowItsResolutionHoldsNothingAndGivesNothing() {
        for(double[] c:new double[][]{{2e-6,0},{1e-6,1},{.5e-6,1},{1e-12,1}}) {
            var node=vessel(c[0]);boolean nothing=c[1]==1;
            assertEquals(c[0],PhaseLayout.bagVolume(node.state(),1),1e-6*c[0],"the bag is that share of its capacity");
            assertEquals(nothing,PassiveStepSolver.holdsNoFluid(node),"share "+c[0]);
            assertEquals(nothing,PassiveStepSolver.dryEnd(node,PassiveNetwork.PhasePort.BULK),"a dry end at share "+c[0]);
            // Nothing is deleted: the residue is still the vessel's inventory, and the layout still carries it.
            assertFalse(node.empty(),"the inventory keeps its moles at share "+c[0]);
        }
        var empty=new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,101325,()->{}),PassiveNetwork.NodeKind.RESERVOIR,
                new PassiveNetwork.Inventory(1,new double[model.componentCount()],0));
        assertTrue(PassiveStepSolver.holdsNoFluid(empty),"a vessel with no moles holds nothing");
    }
    /**
     * Decision D33 with D54: solids ride the liquid and a dry gas carries none, so a vessel holding solids and no fluid has
     * nothing to carry anything out, and every end of it is dry ({@link PassiveStepSolver#dryEnd}: of a vessel holding no
     * fluid, not only of one holding nothing). A bed of 200 kg of particles at 310 K in a slack 1 m3 bag, 2 m above a tank
     * half full of water: the static drive, the water's column, points out of the bed. The connection carries nothing, the
     * bed keeps what it holds, and nothing read at its end is non-finite: a state with no fluid had a bulk viscosity of 0/0
     * and an infinite acoustic bound, the reads that refused every step of cold fuzz seed 85's dry tank ("Invalid pipe
     * transport properties"). A suction on it is dry (D58).
     */
    @Test void aVesselHoldingOnlySolidsIsADryEndAndGivesNothing() {
        var particles=MaterialCatalog.bundled().solids().require("icheme:demo_particle");
        var solids=new SolidInventory(List.of(new SolidInventory.Population(particles,ParticleSize.micrometres("100"),200)));
        var dry=model.solidState(310,PhaseLayout.AMBIENT_PRESSURE,solids);
        var bed=new PassiveNetwork.Reservoir(1,2,dry,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,new double[model.componentCount()],dry.internalEnergy(),solids));
        double[] n=new double[model.componentCount()];n[n.length-1]=.5/model.waterLiquid(298.15,PhaseLayout.AMBIENT_PRESSURE).molarVolume();
        var water=model.flashTP(298.15,PhaseLayout.AMBIENT_PRESSURE,n,()->{});
        var tank=new PassiveNetwork.Reservoir(2,0,water,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(water),water.internalEnergy()));
        var graph=new PassiveNetwork(List.of(bed,tank),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(1,.05,.000045,0))));
        var step=new PassiveStepSolver(model).solve(graph,.01,()->{});
        assertEquals(0,Math.abs(step.massFlows()[0]),"the bed gives nothing");
        keeps(bed,step.inventories().get(0),"in a step");
        var interval=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(5,interval.advancedSeconds(),"the interval runs");
        assertEquals(0,Math.abs(interval.averageMassFlows()[0]),"the bed gives nothing over the interval");
        for(var node:interval.graph().reservoirs()) {
            var s=node.state();
            assertTrue(Double.isFinite(s.temperature())&&Double.isFinite(s.pressure())&&Double.isFinite(s.internalEnergy()),"a finite state at node "+node.id());
        }
        keeps(bed,interval.graph().reservoirs().get(0).inventory(),"over the interval");
        assertTrue(interval.graph().reservoirs().get(1).inventory().solids().empty(),"no solid reaches the tank");
        assertTrue(PassiveStepSolver.holdsNoFluid(bed)&&!bed.inventory().solids().empty(),"the bed holds solids and no fluid");
        assertTrue(PassiveStepSolver.dryEnd(bed,PassiveNetwork.PhasePort.BULK),"its ends are dry");
        assertEquals(0,InletPhase.held(graph,0),"a suction on it is dry");
    }
    // ---------------- a dry vessel and what first arrives (W3b of batch 2026-11-11-aqueous-solvents) ----------------
    /**
     * A generator of {@code fluid} at 298.15 K standing {@code head} Pa over the ambient, a junction on its line, and
     * 100 m of {@code diameter} m pipe from the junction to an empty 1 m3 tank at the same height.
     */
    private PassiveNetwork trickle(FluidTestSupport.Mixture fluid,double head,double diameter) {
        var stream=FluidTestSupport.oneCubicMetreState(model,fluid,298.15,PhaseLayout.AMBIENT_PRESSURE+head);
        var source=new PassiveNetwork.Reservoir(2,0,stream,PassiveNetwork.NodeKind.GENERATOR);
        var joint=new PassiveNetwork.Reservoir(3,0,stream,PassiveNetwork.NodeKind.JUNCTION);
        return new PassiveNetwork(List.of(empty(),source,joint),List.of(new PassiveNetwork.Pipe(4,1,2,new PipeResistance.Geometry(1,.05,0,0)),
                new PassiveNetwork.Pipe(5,2,0,new PipeResistance.Geometry(100,diameter,0,0))));
    }
    private static double held(PassiveNetwork.Reservoir node){double total=0;for(double n:node.inventory().moles())total+=n;return total;}
    /**
     * Decisions D54 and D59 (2): a vessel that holds nothing is laid out dry, with no unknowns, until what is booked into it
     * reaches the bag's resolution, a millilitre in a cubic metre; under it the arrival stays booked and the vessel stays dry.
     * One rule judges that ({@code PassiveStepSolver.filled}). Since water is a fluid phase like any other (W3b) the phase
     * appearance rule of hold class E3 ({@code phaseAppeared}) saw every first booking into a dry vessel as a phase its
     * layout lacked and laid the vessel out with it, whatever its amount: 1.4e-30 mol of water in defaults fuzz seed 358,
     * whose energy moves by less than one rounding unit of its energy row over the temperature step, so the Jacobian's
     * temperature column was exactly zero and the island held at every step size ("Sparse LU rejected a singular matrix").
     */
    @Test void aDryVesselStaysDryUntilAMillilitreArrives() {
        for(var fluid:FluidTestSupport.Mixture.values()) {
            // A trickle: 5 Pa over 100 m of 2 mm pipe delivers far under a millilitre in a second.
            boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            PassiveIntervalSolver.Result slow;long folds;
            try{slow=new PassiveIntervalSolver(model).solve(trickle(fluid,5,.002),1,PassiveIntervalSolver.Settings.defaults(),()->{});folds=SolverDiagnostics.traceFoldPasses.sum();}
            finally{SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
            assertEquals(1,slow.advancedSeconds(),fluid+": the island is not held");
            assertEquals(0,folds,fluid+": the dry tank's placeholder is no solved phase, so a booking under its resolution folds nothing and no pass lays the tank out");
            var tank=slow.graph().reservoirs().get(0);
            assertTrue(held(tank)>0,fluid+": what arrived is booked: "+held(tank)+" mol");
            assertTrue(PassiveStepSolver.holdsNoFluidVessel(tank),fluid+": under a millilitre the tank holds nothing: "+held(tank)+" mol");
            // A stream: 2 kPa over 100 m of 2 cm pipe passes the millilitre within the interval, and the tank is laid out.
            var fast=new PassiveIntervalSolver(model).solve(trickle(fluid,2000,.02),5,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertEquals(5,fast.advancedSeconds(),fluid+": the island is not held while the tank fills");
            assertFalse(PassiveStepSolver.holdsNoFluidVessel(fast.graph().reservoirs().get(0)),fluid+": past a millilitre the tank holds its fluid: "+held(fast.graph().reservoirs().get(0))+" mol");
        }
    }
    /** The bed keeps its solids and its energy (to rounding), and still holds no fluid. */
    private static void keeps(PassiveNetwork.Reservoir bed,PassiveNetwork.Inventory after,String when) {
        assertEquals(bed.inventory().solids().massKg(),after.solids().massKg(),1e-9,"its solids "+when);
        assertEquals(bed.inventory().internalEnergy(),after.internalEnergy(),1e-6*Math.abs(bed.inventory().internalEnergy())+1e-6,"its energy "+when);
        assertArrayEquals(bed.inventory().moles(),after.moles(),"no fluid "+when);
    }
}
