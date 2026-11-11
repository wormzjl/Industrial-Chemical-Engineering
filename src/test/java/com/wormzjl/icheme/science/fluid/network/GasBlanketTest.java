package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A gas blanketing valve on a closed vessel ({@link GasBlanket}, batch 2026-11-11-gas-blanketing; owner: "Keep the drum
 * pressure to set value, pressure too high -> release fluid (gas), pressure too low -> introduce gas"): a 1 m3 drum set to
 * 150 kPa on a 500 kPa nitrogen supply. Placed empty it fills to its set pressure and stops; over its vent pressure
 * (157.5 kPa) it vents down to it with or without a supply; drained, its gas follows its liquid out, where a rigid drum
 * without the valve stops (RigidVesselTest: a 0.783 level); full of liquid it drains too.
 */
class GasBlanketTest extends PortFixture {
    private static final double SET=1.5e5,SUPPLY=5e5;

    private PassiveNetwork.Reservoir supply(){return new PassiveNetwork.Reservoir(2,0,nitrogenAt(SUPPLY),PassiveNetwork.NodeKind.GENERATOR);}
    private PassiveNetwork.Reservoir exit(){return new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID);}
    /** The supply line into node 0's gas space, carrying the valve. */
    private static PassiveNetwork.Pipe pad(int from) {
        return new PassiveNetwork.Pipe(1,from,0,List.of(new PipeResistance.Geometry(5,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),FlowControl.ControlValve.blanket(),0,null,PhasePort.BULK,PhasePort.VAPOR);
    }
    /** A 2 m liquid outlet of node 0 to node {@code to}. */
    private static PassiveNetwork.Pipe outlet(int to) {
        return new PassiveNetwork.Pipe(2,0,to,List.of(new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK);
    }
    /** {@code state} scaled to 1 m3 in a rigid 1 m3 drum blanketed at {@link #SET}. */
    private PassiveNetwork.Reservoir drum(FluidThermodynamics.State state) {
        var n=PhaseLayout.totalAmounts(state);for(int c=0;c<n.length;c++)n[c]/=state.volume();
        var unit=model.flashTP(state.temperature(),state.pressure(),n,NOOP);
        return new PassiveNetwork.Reservoir(1,0,unit,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(unit),unit.internalEnergy()).withRigid(true).withBlanket(SET));
    }
    /** {@code slices} slices of 1 s (the interval solver plans the valve at each step); the lowest and highest pressure the drum ended a slice at. */
    private PassiveNetwork run(PassiveNetwork graph,int slices,double[] range,List<ConservativeTransport.BoundaryTransfer> vents) {
        var solver=new PassiveIntervalSolver(model);range[0]=Double.POSITIVE_INFINITY;range[1]=0;
        for(int i=0;i<slices;i++) {
            var result=solver.solve(graph,1,PassiveIntervalSolver.Settings.defaults(),NOOP);
            assertEquals(1,result.advancedSeconds(),1e-9,"slice "+i);
            graph=result.graph();double p=graph.reservoirs().getFirst().state().pressure();
            range[0]=Math.min(range[0],p);range[1]=Math.max(range[1],p);
            // With a third entry: the lowest while liquid stands.
            if(range.length>2&&level(graph)>.05)range[2]=Math.min(range[2],p);
            for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.VENT)vents.add(b);
        }
        return graph;
    }
    private static double level(PassiveNetwork graph){var s=graph.reservoirs().getFirst().state();return (s.liquidVolume()+s.waterVolume())/graph.reservoirs().getFirst().inventory().volume();}
    private static double total(double[] n){double sum=0;for(double v:n)sum+=v;return sum;}

    @Test void aDrumPlacedEmptyFillsToItsSetPressureAndStops() {
        var empty=PassiveNetwork.emptyVessel(1,0,1,model);
        var drum=new PassiveNetwork.Reservoir(1,0,empty.state(),empty.kind(),empty.inventory().withBlanket(SET));
        var range=new double[2];var vents=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var end=run(new PassiveNetwork(List.of(drum,supply()),List.of(pad(1))),30,range,vents);
        var node=end.reservoirs().getFirst();
        System.out.println("BLANKET_RUN fill: P="+node.state().pressure()+" Pa highest="+range[1]+" rigid="+node.inventory().rigid()+" vents="+vents.size());
        assertTrue(node.inventory().rigid(),"its gas fills it");
        assertEquals(SET,node.state().pressure(),.02*SET,"at its set pressure");
        assertTrue(range[1]<=GasBlanket.ventPressure(SET),"the pad never passes the vent pressure: "+range[1]);
        assertTrue(vents.isEmpty(),"so nothing is vented");
        assertEquals(0,((FlowControl.ControlValve)GasBlanket.plan(end,model,1).pipes().getFirst().control()).massTarget(),1e-4,"the valve asks for nothing more");
    }

    @Test void aDrumOverItsVentPressureVentsDownToItWithoutASupply() {
        var drum=drum(nitrogenAt(3e5));double before=total(drum.inventory().moles());
        var range=new double[2];var vents=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var end=run(new PassiveNetwork(List.of(drum),List.of()),30,range,vents);
        var node=end.reservoirs().getFirst();double vented=0;for(var b:vents)vented-=total(b.moles());
        System.out.println("BLANKET_RUN vent: P="+node.state().pressure()+" Pa lowest="+range[0]+" vented="+vented+" mol of "+before);
        assertEquals(GasBlanket.ventPressure(SET),node.state().pressure(),.02*SET,"down to its vent pressure");
        assertTrue(range[0]>=SET,"the vent never passes the set pressure: "+range[0]);
        assertEquals(before-total(node.inventory().moles()),vented,1e-9*before,"what left is booked as vented");
        assertFalse(OpenVessel.releasing(node)&&node.state().pressure()<=GasBlanket.ventPressure(SET),"at its vent pressure it is at rest");
    }

    @Test void aDrainedDrumKeepsItsPressureAndRunsDry() {
        var range=new double[]{0,0,Double.POSITIVE_INFINITY};var vents=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var end=run(new PassiveNetwork(List.of(drum(waterUnderNitrogen(.8,SET)),supply(),exit()),List.of(pad(1),outlet(2))),150,range,vents);
        System.out.println("BLANKET_RUN drain: level="+level(end)+" P lowest="+range[0]+" highest="+range[1]+" lowest while draining="+range[2]);
        assertTrue(level(end)<.02,"its gas follows its liquid out: "+level(end));
        // 0.02 m3/s of water leaves a 0.2 m3 gas room. The first steps have no rate to read and sag by 3 %; then the pad
        // brings the gas for the room the liquid vacates, and the pressure stands. Dry, the drum's gas leaves by its open
        // outlet faster than the pad's response (the overall lowest).
        assertTrue(range[2]>.95*SET,"the pad holds its pressure while it drains: "+range[2]);
        assertTrue(range[0]>.75*SET,"and stays near it dry: "+range[0]);
        assertTrue(range[1]<=GasBlanket.ventPressure(SET)*1.001,"and never passes the vent pressure: "+range[1]);
    }

    /** A 4 bar water line of 2 m and 0.05 m into node 0. */
    private static PassiveNetwork.Pipe feed(int from) {
        return new PassiveNetwork.Pipe(3,from,0,List.of(new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,null,PhasePort.BULK,PhasePort.BULK);
    }

    @Test void aFastFillVentsAtItsVentPressure() {
        // Half full at the set pressure, filled at about 0.03 m3/s: at 90 % the liquid takes a sixth of the gas room each
        // second. The vent passes the gas the liquid displaces as it enters, so the drum stands near its vent pressure.
        var water=new PassiveNetwork.Reservoir(2,0,waterSupply(4e5),PassiveNetwork.NodeKind.GENERATOR);
        var graph=new PassiveNetwork(List.of(drum(waterUnderNitrogen(.5,SET)),water),List.of(feed(1)));
        var solver=new PassiveIntervalSolver(model);double peak=0,before=total(graph.reservoirs().getFirst().inventory().moles()),booked=0;var vents=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        for(int i=0;i<40&&level(graph)<.9;i++) {
            var result=solver.solve(graph,.5,PassiveIntervalSolver.Settings.defaults(),NOOP);
            graph=result.graph();peak=Math.max(peak,graph.reservoirs().getFirst().state().pressure());
            for(var b:result.boundaries()){booked+=total(b.moles());if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.VENT)vents.add(b);}
        }
        double after=total(graph.reservoirs().getFirst().inventory().moles());
        System.out.println("BLANKET_RUN fast fill: level="+level(graph)+" P="+graph.reservoirs().getFirst().state().pressure()+" peak="+peak+" vent="+GasBlanket.ventPressure(SET)+" vents="+vents.size()+" held "+before+" to "+after+" booked "+booked);
        assertEquals(after-before,booked,1e-9*after,"what entered and left is booked");
        assertTrue(level(graph)>=.9,"it fills: "+level(graph));
        assertFalse(vents.isEmpty(),"its vent passes the displaced gas");
        assertTrue(peak<1.03*GasBlanket.ventPressure(SET),"near its vent pressure: "+peak);
    }

    @Test void aDrumFullOfLiquidDrains() {
        var range=new double[2];var vents=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var end=run(new PassiveNetwork(List.of(drum(waterSupply(SET)),supply(),exit()),List.of(pad(1),outlet(2))),200,range,vents);
        System.out.println("BLANKET_RUN full: level="+level(end)+" P lowest="+range[0]+" highest="+range[1]+" vents="+vents.size());
        assertTrue(level(end)<.2,"the pad opens on a drum without gas room: "+level(end));
        assertTrue(range[1]<=SUPPLY,"never above its supply: "+range[1]);
    }
}
