package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JunctionSeedPhaseTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    @Test void anUncommittedSeedPhaseCanCollapseWithoutDroppingAnExistingPhase() {
        var liquid=methane(0);
        var seed=methane(.5);
        var collapsed=methane(5e-5);
        var stable=methane(.25);

        var folded=foldedIterate(liquid,seed,collapsed);
        assertNotNull(folded);
        assertFalse(FluidThermodynamics.holdsPhase(folded,FluidThermodynamics.GAS));
        assertArrayEquals(PhaseLayout.totalAmounts(seed),PhaseLayout.totalAmounts(folded));

        assertNull(foldedIterate(liquid,seed,stable),"a provisional phase that remains substantial stays active");
        var committedVapour=methane(1e-4);
        assertNull(foldedIterate(committedVapour,seed,collapsed),"a phase already in stock uses its committed share");
    }

    private FluidThermodynamics.State foldedIterate(FluidThermodynamics.State start,FluidThermodynamics.State seed,
                                                   FluidThermodynamics.State trial) {
        var solver=new PassiveStepSolver(model);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,start,PassiveNetwork.NodeKind.JUNCTION)),List.of());
        solver.constants=PassiveStepSolver.SolveConstants.of(List.of(start),0,()->{},new double[0],new BitSet(),new int[0],graph,.1);
        var folded=new PhaseActiveSet(solver,model).foldedIterate(graph,List.of(seed),List.of(trial));
        return folded==null?null:folded.getFirst();
    }

    private FluidThermodynamics.State methane(double vapourMoles) {
        double[] vapour=new double[model.componentCount()],liquid=vapour.clone(),water=vapour.clone();
        int methane=model.components().indexOf("Methane");
        vapour[methane]=vapourMoles;
        liquid[methane]=100-vapourMoles;
        return model.adoptingState(110,150_000,vapour,liquid,water,null);
    }
}
