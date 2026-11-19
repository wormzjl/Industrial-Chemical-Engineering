package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunctionPrecisionTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidTestSupport.model(PilotCryogenicTestCatalog.withPilot(),"icheme:pilot_cryogenic")
            .withTime(new SimulationTime(1));

    /** Replenishment changes the LNG throughout the interval, so the weighted enthalpy floor persists after the first
     * substep. A 50 microgram parcel previously made the step controller exhaust its attempts after 1.74 of 20 s. */
    @Test void reopeningABackedColdFittingKeepsItsDeliveryAndLedger() {
        var run=new PipeResistance.Geometry(10,.2,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var lines=List.of(new PassiveNetwork.Pipe(4,0,1,run),new PassiveNetwork.Pipe(5,1,2,run));
        var ambient=model.initialNitrogenCharge(1,298.15,101_325,NOOP);
        var fuel=liquid(107,101_325,100,.02);
        var tank=new PassiveNetwork.Reservoir(1,30,fuel,PassiveNetwork.NodeKind.RESERVOIR,
                new PassiveNetwork.Inventory(200,PhaseLayout.totalAmounts(fuel),fuel.internalEnergy(),fuel.solids(),5,true));
        var sink=new PassiveNetwork.Reservoir(3,0,ambient,PassiveNetwork.NodeKind.VOID);
        var original=new PassiveNetwork.Reservoir(2,10,ambient,PassiveNetwork.NodeKind.JUNCTION);
        var minted=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(List.of(tank,original,sink),lines),model)
                .reservoirs().get(1).inventory();

        // A gas-minted fitting now passes liquid. Keep the old gas and its enthalpy in the reserve.
        var cold=liquid(107.01,130_000,.001,.02);
        double parcel=5e-8;
        var parked=minted.moles();
        double parkedShare=1-parcel/mass(parked);
        for(int c=0;c<parked.length;c++)parked[c]*=parkedShare;
        double parkedHeat=parkedShare*minted.internalEnergy();
        var amounts=parked.clone();
        var incoming=PhaseLayout.totalAmounts(cold);
        for(int c=0;c<amounts.length;c++)amounts[c]+=incoming[c]*parcel/cold.mass();
        var stock=minted.with(amounts,parkedHeat+parcel*cold.enthalpy()/cold.mass(),minted.solids())
                .withReserve(new PassiveNetwork.JunctionReserve(parked,parkedHeat));
        var junction=new PassiveNetwork.Reservoir(2,10,cold,PassiveNetwork.NodeKind.JUNCTION,stock);

        var replenishment=liquid(111,130_000,.001,.07);
        var feed=PhaseLayout.totalAmounts(replenishment);
        for(int c=0;c<feed.length;c++)feed[c]*=100/replenishment.mass();
        double feedEnergy=100*(replenishment.enthalpy()/replenishment.mass()+PassiveStepSolver.GRAVITY*tank.elevation());
        var graph=new PassiveNetwork(List.of(tank,junction,sink),lines,List.of(new ScheduledTransfer.Injection(10,0,feed,feedEnergy)));

        var result=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),NOOP);
        assertEquals(20,result.advancedSeconds(),0);
        assertTrue(result.averageMassFlows()[1]>250,"substantial LNG reaches the vent");
        var after=result.graph().reservoirs().get(1);
        double released=mass(parked)-mass(after.inventory().reserve().moles());
        assertTrue(released>0&&released<.001*result.averageMassFlows()[0],
                "the reserve lends less than 0.1% of one second's delivery");
        assertEquals(mass(stock.moles()),mass(after.inventory().moles()),1e-9,"same lifetime junction stock");
        assertEquals(result.graph().reservoirs().get(0).state().temperature(),after.state().temperature(),.5);
        checkLedger(graph,result);
    }

    private FluidThermodynamics.State liquid(double temperature,double pressure,double volume,double ethane) {
        var amounts=new double[model.components().size()];
        amounts[model.components().indexOf("Methane")]=1-ethane;
        amounts[model.components().indexOf("Ethane")]=ethane;
        var unit=model.flashTP(temperature,pressure,amounts,NOOP);
        for(int c=0;c<amounts.length;c++)amounts[c]*=volume/unit.volume();
        return model.flashTP(temperature,pressure,amounts,NOOP);
    }

    private double mass(double[] amounts) {
        double total=0;
        for(int c=0;c<amounts.length;c++)total+=amounts[c]*model.molecularWeight(c);
        return total;
    }

    private void checkLedger(PassiveNetwork before,PassiveIntervalSolver.Result result) {
        double energy=0,energyScale=0;
        var components=new double[model.components().size()];
        var scales=new double[components.length];
        for(int i=0;i<before.reservoirs().size();i++) {
            if(before.reservoirs().get(i).fixed())continue;
            var start=before.reservoirs().get(i).inventory();
            var end=result.graph().reservoirs().get(i).inventory();
            energy+=end.internalEnergy()-start.internalEnergy();
            energyScale+=Math.abs(start.internalEnergy());
            var old=start.moles();
            var now=end.moles();
            for(int c=0;c<components.length;c++) {
                components[c]+=now[c]-old[c];
                scales[c]+=old[c];
            }
        }
        for(var transfer:result.boundaries()) {
            energy-=transfer.totalEnergyJoule();
            var moles=transfer.moles();
            for(int c=0;c<components.length;c++)components[c]-=moles[c];
        }
        assertEquals(0,energy,16*Math.ulp(energyScale),"owned and transferred energy");
        for(int c=0;c<components.length;c++)
            assertEquals(0,components[c],16*Math.ulp(scales[c]),model.components().get(c)+" ledger");
    }
}
