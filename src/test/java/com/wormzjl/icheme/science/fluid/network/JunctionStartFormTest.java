package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Owner decisions D54 (nothing at the mint) and D55: a minted junction holds nothing a placement put there; its buffer
 * starts from the island-wide fixed point's last round ({@link PassiveStepSolver#balanceStarts}), whose round 0 is what
 * the connected suppliers would send it (swept hop by hop to the junctions none touches), and a round whose rate solve
 * fails ends the rounds with the last one completed. The ambient is an empty node's pressure, never a junction's fluid.
 * The start chain (D37, D39, D40) and the start switch of D48 (A280-A284) are gone.
 */
class JunctionStartFormTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PipeResistance.Geometry geometry=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);

    @Test void theAmbientIsNitrogenAt298KAnd101325Pa() {
        var air=NetworkReduction.ambient(model);
        assertEquals(298.15,air.temperature(),1e-9);
        assertEquals(101325,air.pressure(),1e-6);
        int nitrogen=model.hydrocarbon.components().indexOf(FluidMaterialCatalog.NITROGEN);
        var n=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(air);double total=0;for(double x:n)total+=x;
        assertEquals(total,n[nitrogen],0,"pure nitrogen");
        assertTrue(FluidThermodynamics.holdsPhase(air,FluidThermodynamics.GAS));
    }
    /** A generator spilling through one junction into a void. */
    private PassiveNetwork spill(FluidThermodynamics.State supply) {
        var air=NetworkReduction.ambient(model);
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,supply,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,air,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,air,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,1,2,geometry)));
    }
    /** Methane through the junction: the rounds drive it in, so the junction is minted as methane between the generator's
     * pressure and the void's, not as the ambient it started from. */
    @Test void aJunctionGasFlowsThroughIsMintedAsThatGas() {
        var n=new double[model.componentCount()];n[model.hydrocarbon.components().indexOf("Methane")]=1;
        var minted=PassiveNetwork.mintJunctions(spill(model.flashTP(298.15,150000,n,()->{})),model).reservoirs().get(1);
        int nitrogen=model.hydrocarbon.components().indexOf(FluidMaterialCatalog.NITROGEN),methane=model.hydrocarbon.components().indexOf("Methane");
        var held=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(minted.state());
        assertEquals(0,held[nitrogen],"no ambient left: "+Arrays.toString(held));assertTrue(held[methane]>0);
        double p=minted.state().pressure();assertTrue(p>101325&&p<150000,"between the generator and the void: "+p);
    }
    /** Water through the junction: round 0 is the generator's water at the ambient pressure, and the first round's rate
     * solve moves it to its solved pressure or, failing, keeps it. No nitrogen is minted either way (decision D55: the
     * ambient start it replaces put air there, and the first step evaporated the arriving water into it). */
    @Test void aJunctionWaterFlowsThroughIsMintedAsWater() {
        var n=new double[model.componentCount()];n[n.length-1]=1;
        var minted=PassiveNetwork.mintJunctions(spill(model.flashTP(298.15,150000,n,()->{})),model).reservoirs().get(1);
        var held=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(minted.state());double total=0;for(double x:held)total+=x;
        assertEquals(total,held[held.length-1],0,"water only: "+Arrays.toString(held));
        assertTrue(minted.state().waterLiquid()>0,"liquid water");
    }
    /** A run of two junctions: the one the generator touches takes its stream; the one beyond takes it from the first, one
     * hop on; neither is the ambient. */
    @Test void aJunctionNoSupplierTouchesTakesItsNeighboursSeed() {
        var n=new double[model.componentCount()];n[model.hydrocarbon.components().indexOf("Methane")]=1;
        var air=NetworkReduction.ambient(model);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.flashTP(298.15,150000,n,()->{}),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,air,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,air,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(4,0,air,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(5,0,1,geometry),new PassiveNetwork.Pipe(6,1,2,geometry),new PassiveNetwork.Pipe(7,2,3,geometry)));
        var minted=PassiveNetwork.mintJunctions(graph,model);
        int nitrogen=model.hydrocarbon.components().indexOf(FluidMaterialCatalog.NITROGEN);
        for(int j=1;j<=2;j++)assertEquals(0,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(minted.reservoirs().get(j).state())[nitrogen],"junction "+j);
    }
}
