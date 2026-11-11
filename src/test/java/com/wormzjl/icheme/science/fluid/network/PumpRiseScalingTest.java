package com.wormzjl.icheme.science.fluid.network;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.ThermoHold;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F4, T3 (option P1 of the F1 review): a pump's "maximum pressure rise" is the rise for water at 298.15 K and 1 atm, and
 * on what it actually withdraws its limit is that setting times the suction's bulk density over water's - a head. And
 * T2, since the range-extrapolation batch for a phase hold only: a hold is its own rejection key and the reason a failed
 * interval gives; leaving a record's data range is no refusal at all (owner rule of 2026-10-16).
 *
 * <p>Since the phase-ports batch (decisions D1, D2, D7) a pump refuses gas, so the nitrogen transfer between closed tanks is
 * a compressor's, closing at its pressure-ratio limit; the pump's density scaling is kept in the water test.
 */
class PumpRiseScalingTest {
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(1,.05,.000045,0);
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),NETWORK);

    /** The compressor of the transfer: 0.1 m3/s of suction volume, ratio 1.05. */
    private static final double TARGET=.1,RATIO=1.05;
    /** Two closed 1 m3 nitrogen tanks at 298.15 K and 1 atm, a compressor from the first into the second. */
    private static PassiveNetwork transfer(FluidThermodynamics model){return transfer(model,101325);}
    private static PassiveNetwork transfer(FluidThermodynamics model,double pressure) {
        var a=model.initialNitrogenCharge(1,298.15,pressure,()->{});var b=model.initialNitrogenCharge(1,298.15,pressure,()->{});
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a),new PassiveNetwork.Reservoir(2,0,b)),
                List.of(new PassiveNetwork.Pipe(10,0,1,LINE,new FlowControl.Compressor(TARGET,RATIO))));
    }

    @Test void theReferenceIsThisModelsOwnWaterAtRoomConditions() {
        // W3b: the network's own water, the cubic's pure liquid at 298.15 K and 1 atm: 997.055 kg/m3 (IF97 Region 1 997.048,
        // IAPWS-95 997.047; the retired 2 MPa reference carried to 1 atm by the global 1e-9 1/Pa compressibility gave 996.0).
        assertEquals(997.055,model.pumpReferenceDensity(),.001);
    }

    /**
     * Nitrogen compressed out of a closed tank into another (decision D7): the compressor reaches its ratio limit within a
     * second, the discharge standing at {@code r_max} times the suction, and passes nothing there: it runs on its law with
     * its relief passing its whole displacement back (batch 2026-11-03-rotating-machinery, form F2e), or stands closed, as
     * the set-flow pair closed it at its shutoff. The suction tank is a
     * bag at the ambient (decision D54): it gives its gas at its own temperature and shrinks at the ambient pressure, the
     * atmosphere's work on it replacing the flow work of the gas that leaves, so it stays at 298.15 K and 101325 Pa; the
     * discharge, a full bag, is warmer by its compression and the shaft work booked as heat (decision D8) - and never
     * anywhere near the property domain's bounds. (Before D54 the suction tank was rigid and cooled a couple of kelvin by
     * its expansion; the strict "cooler" assertion of that time then held on a residue below 298.15 K until the semismooth
     * Jacobian of A423 left the suction 6e-12 K above it. Test name kept from the pump it was: the pump's 575 Pa
     * density-scaled shutoff became the ratio limit.)
     */
    @Test void aGasTransferBetweenClosedTanksReachesItsRatioLimitWithinASecond() {
        var graph=transfer(model);var solver=new PassiveIntervalSolver(model);double step=.05,work=0;
        PassiveIntervalSolver.Result result=null;int reachedAt=-1;
        for(int k=1;k<=40;k++) {
            result=solver.solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{},step);graph=result.graph();step=solver.nextStepEstimate();work+=result.pumpWorkJoule();
            if(reachedAt<0&&Math.abs(graph.reservoirs().get(1).state().pressure()-RATIO*graph.reservoirs().get(0).state().pressure())<=3)reachedAt=k;
        }
        var suction=graph.reservoirs().get(0).state();var discharge=graph.reservoirs().get(1).state();double rise=result.endpointHeads()[0];
        System.out.printf(Locale.ROOT,"gas transfer (compressor, ratio %.2f): at its limit after %d slices of 0.05 s, %s, flow %.3e kg/s, suction %.6f K %.3f Pa, discharge %.6f K %.3f Pa, rise %.4f Pa, limit %.4f Pa, work %.3f J%n",
                RATIO,reachedAt,result.endpointModes().getFirst(),result.averageMassFlows()[0],suction.temperature(),suction.pressure(),discharge.temperature(),discharge.pressure(),rise,(RATIO-1)*suction.pressure(),work);
        assertTrue(reachedAt>0&&reachedAt<=20,"the compressor reaches its ratio limit within a second: slice "+reachedAt);
        var mode=result.endpointModes().getFirst();
        assertTrue(mode==FlowControl.Mode.MOVER_LAW||mode==FlowControl.Mode.CLOSED,"on its law at the limit, or closed: "+mode);
        assertEquals(0,result.averageMassFlows()[0],1e-9*TARGET*suction.mass()/suction.volume(),"it passes nothing at its limit");
        // The pump's landing tolerance (3 Pa) on the limit; the head a closed device holds is the whole difference.
        assertEquals((RATIO-1)*suction.pressure(),rise,3,"the rise at shutoff is the ratio limit on the suction pressure");
        assertEquals(discharge.pressure()-suction.pressure(),rise,.05,"the compressor holds the whole pressure difference at shutoff");
        assertEquals(RATIO*suction.pressure(),discharge.pressure(),3,"the discharge stands at r_max times the suction");
        assertEquals(298.15,suction.temperature(),1e-6,"the suction bag gives its gas at its own temperature (D54): "+suction.temperature());
        assertEquals(101325,suction.pressure(),1e-3,"the suction bag stays at the ambient (D54): "+suction.pressure());
        assertTrue(discharge.temperature()>298.15,"the discharge is warmer: "+discharge.temperature());assertTrue(work>0);
        assertTrue(result.rejectionReasons().keySet().stream().noneMatch(k->k.startsWith(ThermoHold.REASON_PREFIX)),"no thermo hold: "+result.rejectionReasons());
    }

    /** On water the limit is the setting within water's compressibility: the same shutoff as before P1. */
    @Test void onWaterTheLimitIsTheSettingWithinWatersCompressibility() {
        // W3b: water on the refinery network, whose cubic holds it (the tjl20 packages hold none).
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        double[] water=new double[model.componentCount()];water[model.componentCount()-1]=1;
        for(double p:new double[]{101325,601325,2e6}) {
            var unit=model.flashTP(298.15,p,water,()->{});double rho=unit.mass()/unit.volume();
            assertEquals(1,rho/model.pumpReferenceDensity(),p*1e-9*1.01,"water at "+p+" Pa");
        }
    }

    /**
     * Leaving a record's data range is no refusal (owner rule of 2026-10-16; this test forced the thermo-domain hold
     * before): with the nitrogen record's fluid_domain narrowed to 298.05 K, the compressor's transfer cools the suction
     * past it (the ratio limit lets the suction expand) and runs on, nitrogen's ideal-gas cp held below the record, no
     * trial rejected under a thermo key. Physics: cp of nitrogen changes by 0.0001 J/mol K per kelvin there, so the
     * held value is the record's to rounding.
     */
    @Test void aSuctionCooledPastItsRecordRunsOnWithoutARefusal() {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());String key="data/icheme/materials/properties/nitrogen.json";
        var o=JsonParser.parseString(resources.get(key)).getAsJsonObject();o.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",298.05);resources.put(key,o.toString());
        var narrowed=FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),NETWORK);
        // Above the ambient, so the drawn suction tank expands and cools: a slack bag at 1 atm (decision D54) shrinks at
        // the ambient pressure instead and never reaches the narrowed floor.
        var graph=transfer(narrowed,150000);var solver=new PassiveIntervalSolver(narrowed);double coldest=Double.POSITIVE_INFINITY;
        for(int k=0;k<40;k++) {
            var result=solver.solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{},solver.nextStepEstimate()>0?solver.nextStepEstimate():.05);graph=result.graph();
            assertTrue(result.rejectionReasons().keySet().stream().noneMatch(r->r.startsWith(ThermoHold.REASON_PREFIX)),"no thermo hold: "+result.rejectionReasons());
            coldest=Math.min(coldest,graph.reservoirs().get(0).state().temperature());
        }
        System.out.println("suction past its narrowed record: coldest "+coldest+" K");
        assertTrue(coldest<298.05,"the suction left the narrowed record: "+coldest);
    }

    /** The cause of a failed interval is a phase hold met at its last trial or at most of its rejections (D21 option C). */
    @Test void anIntervalFailsOnAPhaseHoldWhenItsLastOrMostRejectionsWerePhaseHolds() {
        var v=new FluidThermodynamics.UnsupportedPhases(NETWORK,260,101325,FluidThermodynamics.UnsupportedPhases.Kind.THREE_PHASES,"a third fluid phase at 260 K");
        assertSame(v,PassiveIntervalSolver.phaseHoldCause(true,v,1,20),"the last rejection");
        assertSame(v,PassiveIntervalSolver.phaseHoldCause(false,v,11,20),"most rejections");
        assertNull(PassiveIntervalSolver.phaseHoldCause(false,v,10,20),"met on the way, failed for another reason");
        assertNull(PassiveIntervalSolver.phaseHoldCause(false,null,0,20));
    }
}
