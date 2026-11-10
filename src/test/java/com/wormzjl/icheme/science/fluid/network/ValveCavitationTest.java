package com.wormzjl.icheme.science.fluid.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PipeObservation.Cavitation.*;

/**
 * A flowing liquid valve's noise regime (batch 2026-11-10-custom-shader-system; owner 2026-11-10, option A): by
 * σ = (P1 − Pv) / (P1 − P2), plain above 1 / x_Fz, cavitating at or below it with the outlet above Pv, flashing with the
 * outlet below Pv.
 */
class ValveCavitationTest extends PortFixture {
    @Test void theRegimeFollowsSigma() {
        double p1=1e6,pv=3170,x=PassiveStepSolver.VALVE_INCIPIENT;
        // σ = 5: plain; σ = 1.66: cavitating; the outlet below Pv: flashing.
        assertEquals(PLAIN,PassiveStepSolver.regime(pv,8e5,p1-(p1-8e5)/x));
        assertEquals(CAVITATING,PassiveStepSolver.regime(pv,4e5,p1-(p1-4e5)/x));
        assertEquals(FLASHING,PassiveStepSolver.regime(pv,2e3,p1-(p1-2e3)/x));
        assertEquals(NONE,PassiveStepSolver.regime(Double.POSITIVE_INFINITY,4e5,0),"no vapour pressure, no read");
        // The onset: σ = 1 / x_Fz exactly cavitates.
        double p2=p1-x*(p1-pv);
        assertEquals(CAVITATING,PassiveStepSolver.regime(pv,p2,p1-(p1-p2)/x));
    }

    @Test void aWaterValveReadsItsDropAndItsTemperature() {
        assertEquals(PLAIN,cavitation(298.15,1e6,8e5),"cold water, 1 to 0.8 MPa: σ ≈ 5");
        assertEquals(CAVITATING,cavitation(298.15,1e6,4e5),"cold water, 1 to 0.4 MPa: σ ≈ 1.7");
        assertEquals(FLASHING,cavitation(423.15,1e6,3e5),"water at 150 °C (Pv 476 kPa) let down to 0.3 MPa");
    }

    /** The cavitation the solver reads on a valve from water at {@code t} and {@code p1} into a void at {@code p2}. */
    private PassiveStepSolver.PipeObservation.Cavitation cavitation(double t,double p1,double p2) {
        double[] n=new double[mw.length];n[water]=1;
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,model.flashTP(t,p1,n,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,waterSupply(p2),PassiveNetwork.NodeKind.VOID));
        var valve=new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.5,new java.util.UUID(1,1),1);
        var pipe=new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(5,.05,.000045,0),valve);
        var row=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,List.of(pipe)),.1,NOOP).observations().getFirst();
        assertTrue(row.flowing()&&row.massFlow()>0,"the valve passes flow");
        // The row's pressures include the valve's drop; its own drop is the node's pressure less the line's, nearly all of it.
        assertEquals(p1-p2,row.valveDrop(),.02*(p1-p2),"the valve's own drop, not the line's loss after it");
        return row.cavitation();
    }
}
