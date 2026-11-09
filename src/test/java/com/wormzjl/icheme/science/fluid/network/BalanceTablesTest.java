package com.wormzjl.icheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The quasi-steady balance reads a junction's density at the junction's own pressure wherever the junction flashes
 * ({@link PassiveStepSolver.BalanceNet}). The pilot LNG of cold seeds 651 and 907, 150 K and 2.7 % carbon dioxide: bubble
 * near 1.1 MPa, sixty times thinner at 1 atm, where the fluid phases alone refuse it (the two-liquid window).
 */
class BalanceTablesTest {
    private final FluidThermodynamics model=FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(),"icheme:pilot_cryogenic");
    private final PipeResistance.Geometry geometry=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private final double[] lng={.002242095,.9497475,.02145535,.02655503,0};

    private FluidThermodynamics.State liquid(double pressure){return model.flashTP(150,pressure,lng.clone(),()->{});}
    /** A generator of {@code supply} spilling through one junction stated as {@code held} into a void at height {@code z}. */
    private PassiveNetwork spill(FluidThermodynamics.State supply,FluidThermodynamics.State held,double z) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,supply,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,held,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,z,NetworkReduction.ambient(model),PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,1,2,geometry)));
    }
    private PassiveStepSolver.BalanceNet balance(PassiveNetwork graph) {
        var modes=new ArrayList<>(Collections.nCopies(graph.pipes().size(),FlowControl.Mode.PASSIVE));
        return new PassiveStepSolver(model).new BalanceNet(graph,null,modes,new boolean[graph.pipes().size()],()->{},new double[0]);
    }
    /** A liquid junction under a line the balance lets down past its bubble pressure: read as a liquid it stands halfway, at
     * 726 kPa and superheated; its density falling as it flashes holds it back at 965 kPa. */
    @Test void aLiquidJunctionReadsItsFlashBelowItsBubblePressure() {
        var supply=liquid(1_350_000);var held=liquid(1_200_000);
        assertFalse(FluidThermodynamics.holdsPhase(held,FluidThermodynamics.GAS),"stated as a liquid");
        var net=balance(spill(supply,held,0));double p=net.solve(()->{})[1];
        assertTrue(net.converged);assertNotNull(net.tableP[1],"a table");
        assertTrue(net.flashFrom[1]<Double.POSITIVE_INFINITY,"it stops flashing inside the hull");
        assertTrue(p>850_000,"held back by its flash: "+p);
    }
    /** A liquid junction stays a liquid from its stated pressure up, and one that does not flash under it has no table: the
     * balance reads its stated data as it did before there were tables. */
    @Test void aLiquidThatCannotFlashInsideTheHullHasNoTable() {
        var net=balance(spill(liquid(3_000_000),liquid(2_500_000),0));
        assertNotNull(net.tableP[1],"1 atm is inside this hull");
        var high=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,liquid(3_000_000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,liquid(2_500_000),PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,liquid(2_000_000),PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,1,2,geometry)));
        assertNull(balance(high).tableP[1],"liquid over the whole hull");
    }
    /** A two-phase junction at 1 atm, where the fluid phases alone refuse the LNG, still reads its density at its pressure:
     * at its stated 7 kg/m3 at every pressure it would stand at the generator's 2.77 MPa, not at 1.5 MPa (cold seed 651's job 88 stalled on such a start). */
    @Test void aTwoPhaseJunctionTheFluidPhasesRefuseHasATable() {
        var supply=liquid(2_766_000);
        var held=model.equilibriumPH(113,101_325,supply.enthalpy(),lng.clone(),()->{});
        assertTrue(FluidThermodynamics.holdsPhase(held,FluidThermodynamics.GAS)&&FluidThermodynamics.holdsPhase(held,FluidThermodynamics.OIL),"two-phase");
        var net=balance(spill(supply,held,2));double p=net.solve(()->{})[1];
        assertTrue(net.converged);assertNotNull(net.tableP[1],"a table");
        double bottom=net.tableP[1][0];assertNull(model.fluidPH(113,bottom,supply.enthalpy(),lng.clone(),()->{}),"the fluid phases refuse it at "+bottom);
        double[] rho=net.tableRho[1];assertTrue(rho[rho.length-1]>50*rho[0],Arrays.toString(rho));
        assertTrue(p>850_000&&p<2_000_000,"its density carries the flow: "+p);
    }
}
