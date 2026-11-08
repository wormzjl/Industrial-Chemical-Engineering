package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.ConservationAssertions;
import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fresh configured heavy-crude loop; no saved-world fixture or altered physical law. The heavy grade is liquid at 298.15 K
 * and 1 atm (bubble point 68.6 kPa), as WTI was; the light grade boils there (138 kPa). At 2 m³/s the pump outruns the
 * junction it draws from, so it stands on its suction limit at the oil's bubble pressure (owner decision D14 of the
 * rotating-machinery batch; the root used to flash the suction junction on the pump's law); at 1 m³/s it runs on its law.
 * The start is 1 m³/s: on WTI the NPSH3 floor (owner decisions D20 Q11 A and D21) made the pump cavitate from 1.5 m³/s,
 * and the more viscous heavy grade meets the pump's 5 bar head limit at 1.5 already (O3 of batch 2026-11-04-open-thermo-data).
 */
class PumpRateWarmStartTest extends PortFixture {
    private FluidThermodynamics.State oil(double volume) {
        var n=FluidPresetCatalog.resolve(MaterialCatalog.bundled()).stream().filter(p->p.id().equals("icheme:heavy_crude")).findFirst().orElseThrow().moleFractions();
        var unit=model.flashTP(298.15,101325,n,NOOP);for(int c=0;c<n.length;c++)n[c]*=volume/unit.volume();
        return model.flashTP(298.15,101325,n,NOOP);
    }
    private PassiveNetwork loop(double rate) {
        var source=oil(1);var fill=oil(24);var junction=oil(1/source.mass());
        var stock=new PassiveNetwork.Inventory(200,PhaseLayout.totalAmounts(fill),fill.internalEnergy(),SolidInventory.EMPTY,4,true);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,fill,PassiveNetwork.NodeKind.RESERVOIR,stock),
                new PassiveNetwork.Reservoir(2,0,junction,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID),
                new PassiveNetwork.Reservoir(4,1,junction,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(5,0,source,PassiveNetwork.NodeKind.GENERATOR));
        return PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(1,3,0,new PipeResistance.Geometry(12.5,.3,.000045,0),new FlowControl.Pump(rate,500000)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.LIQUID),
                new PassiveNetwork.Pipe(2,0,1,new PipeResistance.Geometry(3.5,.3,.000045,0)).withPorts(PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(3,1,2,new PipeResistance.Geometry(1.5,.3,.000045,0),new FlowControl.PressureValve(110000)),
                new PassiveNetwork.Pipe(4,3,4,new PipeResistance.Geometry(4.5,.3,.000045,0)))),model);
    }

    @Test void repeatedRateCallsKeepTheFlashedPumpLimitConvergent() {
        var graph=loop(2);var solver=new PassiveStepSolver(model);
        var first=solver.portRate(graph,NOOP);var repeated=solver.portRate(graph,NOOP);assertNotNull(first);assertNotNull(repeated);
        assertEquals(FlowControl.Mode.PUMP_SUCTION_LIMIT,first.modes().getFirst());assertEquals(first.modes(),repeated.modes());
        assertArrayEquals(first.massFlows(),repeated.massFlows(),1e-6);
        assertEquals(0,graph.reservoirs().get(3).state().vaporVolume(),0,"rate queries leave their input untouched");
    }

    @Test void increasingTheSetpointAdvancesOnItsLimitWithConservedMaterialAndEnergy() {
        var graph=loop(1.0);var solver=new PassiveIntervalSolver(model);
        var previous=solver.solve(graph,3.2,PassiveIntervalSolver.Settings.defaults(),NOOP);
        assertEquals(FlowControl.Mode.MOVER_LAW,previous.endpointModes().getFirst());graph=previous.graph();
        var pipes=new ArrayList<>(graph.pipes());var p=pipes.getFirst();pipes.set(0,new PassiveNetwork.Pipe(p.id(),p.first(),p.second(),p.sections(),new FlowControl.Pump(2,500000),p.blockedDirections(),p.filter(),p.firstPort(),p.secondPort()));
        graph=new PassiveNetwork(graph.reservoirs(),pipes);
        var before=owned(graph);double energy=ownedEnergy(graph),externalEnergy=0,energyTurnover=0;var external=new double[mw.length];var turnover=new double[mw.length];
        for(int interval=0;interval<11;interval++) {
            solver.replayStart(graph,previous);var result=solver.solve(graph,3.2,PassiveIntervalSolver.Settings.defaults(),NOOP);
            assertEquals(3.2,result.advancedSeconds(),0);assertEquals(FlowControl.Mode.PUMP_SUCTION_LIMIT,result.endpointModes().getFirst());
            for(var b:result.boundaries()){var n=b.moles();for(int c=0;c<n.length;c++){external[c]+=n[c];turnover[c]+=Math.abs(n[c]);}externalEnergy+=b.totalEnergyJoule();energyTurnover+=Math.abs(b.totalEnergyJoule());}
            externalEnergy+=result.pumpWorkJoule();energyTurnover+=Math.abs(result.pumpWorkJoule());graph=result.graph();previous=result;
            ConservationAssertions.components(before,owned(graph),external,turnover);ConservationAssertions.energy(energy,ownedEnergy(graph),externalEnergy,energyTurnover);
        }
        assertEquals(FlowControl.Mode.VALVE_REGULATING,previous.endpointModes().get(2));
    }
}
