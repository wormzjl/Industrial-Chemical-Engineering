package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.ConservationAssertions;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.SteamTurbine;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The steam turbine as a fluid connection (batch 2026-11-03-rotating-machinery, plan section 3.4, owner decision D22): a
 * driver of its own, separate from the machines it drives, whose control is a separate component in line. It passes steam
 * on its own resistance and takes {@code W = ṁ η_is Δh_is} out of the stream, with {@code Δh_is} on the inlet's real
 * {@code P/ρ} and k; the exhaust leaves at {@code h_in − W/ṁ}, and the work leaves the island as a {@code SHAFT} boundary
 * transfer at the connection.
 */
class SteamTurbineConnectionTest extends PortFixture {
    /** The turbine's own resistance: 2 m of 0.05 m bore. */
    private static final PipeResistance.Geometry CASING=new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(1,.1,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    /** A 1 MW machine: η_is 0.61 ({@link SteamTurbine#typicalEfficiency}). */
    private static final SteamTurbine ONE_MW=SteamTurbine.rated(1e6);
    private static final long TURBINE=10,VALVE=9;

    /** One mole of steam at {@code t} (K) and {@code p} (Pa). */
    private FluidThermodynamics.State steam(double t,double p){var n=new double[mw.length];n[water]=1;return model.flashTP(t,p,n,NOOP);}
    private static double density(FluidThermodynamics.State s){return s.mass()/s.volume();}
    /** The energy (J) the boundaries of kind {@code kind} at {@code node} carried. */
    private static double energy(List<ConservativeTransport.BoundaryTransfer> boundaries,long node,ConservativeTransport.BoundaryTransfer.Kind kind) {
        double e=0;for(var b:boundaries)if(b.nodeId()==node&&b.kind()==kind)e+=b.totalEnergyJoule();return e;
    }
    private ConservativeTransport.BoundaryTransfer exchange(List<ConservativeTransport.BoundaryTransfer> boundaries,long node) {
        return boundaries.stream().filter(b->b.nodeId()==node&&b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE).findFirst().orElseThrow();
    }
    /** {@code ṁ η Δh_is} on the inlet's density and pressure, the exhaust pressure and k. */
    private static double expectedPower(SteamTurbine design,double massFlow,FluidThermodynamics.State inlet,double exhaust,double k) {
        return massFlow*design.efficiency()*SteamTurbine.isentropicDrop(inlet.pressure(),density(inlet),exhaust,k);
    }

    /**
     * (a) 40 bar, 400 °C steam through the turbine alone into a 4 bar void, on its own resistance: open (VALVE_OPEN), its
     * shaft work {@code ṁ η Δh_is} on the generator's state (Δh_is about 528 kJ/kg on the model's k of 1.29; 524 at k 1.3),
     * about 322 kJ per kg at η 0.61, booked as a negative SHAFT transfer; the exhaust the void receives carries
     * {@code h_in − W/ṁ}, which the PH flash at 4 bar leaves superheated (about 2890 kJ/kg, near 490 K).
     */
    @Test void aTurbineAloneOnItsResistanceTakesItsIsentropicWorkOutOfTheStream() {
        var inlet=steam(673.15,4e6);double exhaust=4e5;
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,inlet,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,steam(450,exhaust),PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(TURBINE,0,1,CASING,new FlowControl.Turbine(ONE_MW))));
        var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
        double q=result.massFlows()[0],k=model.heatCapacityRatio(inlet),drop=SteamTurbine.isentropicDrop(inlet.pressure(),density(inlet),exhaust,k);
        double shaft=-energy(result.boundaries(),TURBINE,ConservativeTransport.BoundaryTransfer.Kind.SHAFT);
        var in=exchange(result.boundaries(),1);var out=exchange(result.boundaries(),2);
        double hIn=in.totalEnergyJoule()/mass(in.moles()),hOut=out.totalEnergyJoule()/mass(out.moles());
        System.out.println("TURBINE_RUN alone flow="+q+" kg/s k="+k+" drop="+drop+" J/kg work="+shaft+" J h_in="+hIn+" h_out="+hOut);
        assertEquals(FlowControl.Mode.VALVE_OPEN,result.modes().getFirst());
        assertTrue(q>0,"steam passes on the turbine's own resistance");
        assertEquals(528e3,drop,8e3,"the isentropic drop from 40 bar, 400 °C to 4 bar");
        assertEquals(.61,ONE_MW.efficiency(),.005);
        assertEquals(expectedPower(ONE_MW,q,inlet,exhaust,k),shaft,1e-9*shaft,"ṁ η Δh_is over the 1 s step");
        assertEquals(shaft/q,hIn-hOut,1e-9*shaft/q,"the exhaust leaves at h_in − W/ṁ");
        assertEquals(322e3,shaft/q,8e3,"about 322 kJ per kg at η 0.61");
        assertEquals(0,result.pumpWorkJoule(),0,"no mover work enters the stream");
        var n=new double[mw.length];n[water]=1;var flashed=model.equilibriumPH(450,exhaust,hOut*mass(n),n,NOOP);
        assertNotNull(flashed);assertEquals(0,flashed.waterLiquid(),1e-12,"superheated at 4 bar");
        assertTrue(flashed.temperature()>450&&flashed.temperature()<520,"the exhaust temperature "+flashed.temperature());
    }

    /** A 40 bar generator, a mass-target control valve with a sensor ({@code target} kg/s), a junction, the turbine and a
     * 4 bar void: the control is its own component in line, upstream of the turbine. */
    private PassiveNetwork governed(double target) {
        var inlet=steam(673.15,4e6);
        var valve=new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,new UUID(1,2),1);
        return PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,inlet,PassiveNetwork.NodeKind.GENERATOR),
                        new PassiveNetwork.Reservoir(2,0,steam(450,4e5),PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(3,0,inlet,PassiveNetwork.NodeKind.JUNCTION)),
                List.of(new PassiveNetwork.Pipe(VALVE,0,2,LINE,valve),new PassiveNetwork.Pipe(TURBINE,2,1,CASING,new FlowControl.Turbine(ONE_MW)))),model);
    }

    @Test void internalAdmissionLimitsSteamAtBothSupplyConditionsAndKeepsTheWorkInTheLedger() {
        for (double[] condition : List.of(new double[]{673.15,4e6},new double[]{773.15,8e6})) {
            var inlet=steam(condition[0],condition[1]);
            for (double target : List.of(0.0,.02)) {
                var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,inlet,PassiveNetwork.NodeKind.GENERATOR),
                        new PassiveNetwork.Reservoir(2,0,steam(450,4e5),PassiveNetwork.NodeKind.VOID)),
                        List.of(new PassiveNetwork.Pipe(TURBINE,0,1,CASING,new FlowControl.Turbine(ONE_MW,target))));
                var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
                double flow=result.massFlows()[0],work=-energy(result.boundaries(),TURBINE,ConservativeTransport.BoundaryTransfer.Kind.SHAFT);
                assertEquals(target,flow,1e-7,"the internal valve controls admission at either inlet pressure/temperature");
                assertEquals(target==0?FlowControl.Mode.CLOSED:FlowControl.Mode.VALVE_REGULATING,result.modes().getFirst());
                if(target==0){assertEquals(0,work,0);continue;}
                assertEquals(expectedPower(ONE_MW,flow,inlet,4e5,model.heatCapacityRatio(inlet)),work,1e-7*Math.max(1,work));
                var in=exchange(result.boundaries(),1);var out=exchange(result.boundaries(),2);
                assertEquals(0,mass(in.moles())+mass(out.moles()),1e-10,"the valve loses no steam");
                assertEquals(work,in.totalEnergyJoule()+out.totalEnergyJoule(),1e-7*Math.max(1,work),"all extracted energy is booked as shaft work");
            }
        }
    }

    @Test void aLowerInternalTargetWinsOverAnExternalValveWithoutDuplicateFlowRows() {
        var original=governed(.5);
        var pipes=new ArrayList<>(original.pipes());
        pipes.set(1,pipes.get(1).withControl(new FlowControl.Turbine(ONE_MW,.25)));
        var graph=new PassiveNetwork(original.reservoirs(),pipes);
        assertTrue(ControlConstraints.standing(graph).get(0),"the external .5 kg/s valve stands open behind the internal .25 kg/s limit");
        assertFalse(ControlConstraints.standing(graph).get(1));
        var result=drive("internal-governor-series",graph,5,6);
        assertEquals(.25,result.averageMassFlows()[1],1e-4*.25);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,result.endpointModes().get(1));
    }

    /**
     * (b) The same turbine behind a mass-target control valve at 0.5 kg/s, under the 2.7 kg/s its casing passes open: the
     * valve regulates and holds its target, the junction between them settles where the turbine passes it (about 7 bar,
     * throttled from 40), and the turbine's work follows that flow: {@code ṁ η Δh_is} on the junction's state.
     */
    @Test void aControlValveUpstreamSetsTheTurbinesFlowAndItsWorkFollows() {
        double target=.5;
        var last=drive("governed",governed(target),5,6);
        var junction=last.graph().reservoirs().get(2).state();
        double valve=last.averageMassFlows()[0],turbine=last.averageMassFlows()[1],shaft=-energy(last.boundaries(),TURBINE,ConservativeTransport.BoundaryTransfer.Kind.SHAFT)/5;
        double expected=expectedPower(ONE_MW,turbine,junction,4e5,model.heatCapacityRatio(junction));
        System.out.println("TURBINE_RUN governed valve="+valve+" turbine="+turbine+" kg/s junction="+junction.pressure()+" Pa "+junction.temperature()+" K power="+shaft+" W expected="+expected);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,last.endpointModes().get(0));
        assertEquals(FlowControl.Mode.VALVE_OPEN,last.endpointModes().get(1));
        assertEquals(target,valve,1e-6*target,"the valve holds its target");
        assertEquals(target,turbine,1e-4*target,"the turbine passes what the valve lets through");
        assertTrue(junction.pressure()>4e5&&junction.pressure()<4e6,"the junction stands between the exhaust and the supply");
        assertEquals(expected,shaft,1e-3*expected,"the turbine's power follows the valve's flow on the junction's state");
    }

    /** (c) The valve at a zero target closes: nothing reaches the turbine, which passes nothing and does no work. The junction
     * starts at the exhaust's pressure, so no stock drains through the turbine either, and holds throttled supply steam
     * (673 K). With 450 K steam in the pocket the zero-flow solve stalls at residual 0.97, with a passive line in the
     * turbine's place too and on main 0.20.0 alike: a solver defect outside the turbine (W2_TURBINE_REVIEW.md). */
    @Test void aClosedValveStopsTheTurbine() {
        var graph=governed(0);var nodes=new ArrayList<>(graph.reservoirs());var j=nodes.get(2);
        var still=steam(673.15,4e5);
        nodes.set(2,new PassiveNetwork.Reservoir(j.id(),j.elevation(),still,j.kind()));
        graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,graph.pipes()),model);
        var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
        assertEquals(FlowControl.Mode.CLOSED,result.modes().get(0));
        assertEquals(0,result.massFlows()[0],1e-12,"the closed valve passes nothing");
        assertEquals(0,result.massFlows()[1],1e-8,"the turbine passes nothing");
        assertEquals(0,energy(result.boundaries(),TURBINE,ConservativeTransport.BoundaryTransfer.Kind.SHAFT),1e-6,"no shaft work");
    }

    /** (d) Six 5 s slices of the governed turbine through the interval solver: components and energy close every slice with
     * the boundaries as the external energy, the shaft work leaving among them (negative, its magnitude the work the
     * turbine's flow does: 0.5 kg/s from the junction, about 7 bar, to 4 bar, near 100 kJ/kg, so about 0.25 MJ per slice). */
    @Test void aGovernedTurbineConservesComponentsAndEnergyWithItsShaftWorkLeaving() {
        var last=drive("conservation",governed(.5),5,6);
        double shaft=energy(last.boundaries(),TURBINE,ConservativeTransport.BoundaryTransfer.Kind.SHAFT);
        assertTrue(shaft<0,"the shaft work leaves the island");
        assertTrue(-shaft>.5*5*50e3&&-shaft<.5*5*400e3,"the slice's shaft work "+(-shaft)+" J");
    }

    /**
     * (e) Steam at 10 bar and 460 K (just superheated) through a 3 MW machine (η 0.76) into a junction and a 1 bar void: Δh_is
     * is about 354 kJ/kg, W about 270 kJ/kg, so the exhaust at about 2520 kJ/kg is wet at 1 bar, about 7 % liquid. The
     * junction's flash holds liquid water near the saturation temperature.
     */
    @Test void aDeepExpansionLeavesAWetExhaust() {
        var inlet=steam(460,1e6);var turbine=SteamTurbine.rated(3e6);
        var graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,inlet,PassiveNetwork.NodeKind.GENERATOR),
                        new PassiveNetwork.Reservoir(2,0,steam(380,1e5),PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(3,0,steam(380,1e5),PassiveNetwork.NodeKind.JUNCTION)),
                List.of(new PassiveNetwork.Pipe(TURBINE,0,2,CASING,new FlowControl.Turbine(turbine)),new PassiveNetwork.Pipe(11,2,1,LINE))),model);
        var last=drive("wet",graph,5,4);
        var exhaust=last.graph().reservoirs().get(2).state();
        double liquid=exhaust.waterLiquid()/(exhaust.waterLiquid()+exhaust.waterVapor());
        System.out.println("TURBINE_RUN wet flow="+last.averageMassFlows()[0]+" kg/s exhaust="+exhaust.pressure()+" Pa "+exhaust.temperature()+" K liquid share="+liquid);
        assertEquals(.76,turbine.efficiency(),1e-9);
        assertTrue(last.averageMassFlows()[0]>0);
        assertTrue(exhaust.waterLiquid()>0,"the exhaust is wet");
        assertTrue(liquid>.02&&liquid<.15,"about 7 % liquid: "+liquid);
        assertTrue(exhaust.temperature()>365&&exhaust.temperature()<400,"near the saturation temperature at the exhaust's pressure: "+exhaust.temperature());
    }

    /** {@code count} slices of {@code interval}, each a job from the committed interval, the component and energy ledgers
     * checked every slice ({@link ConservationAssertions}) with every boundary transfer, the turbine's SHAFT work among them,
     * as the external energy; the last slice. */
    private PassiveIntervalSolver.Result drive(String label,PassiveNetwork graph,double interval,int count) {
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        double[] before=owned(graph),external=new double[mw.length],turnover=new double[mw.length];double energy=ownedEnergy(graph),externalEnergy=0,energyTurnover=0;
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed);
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice);
            for(var b:result.boundaries()){var n=b.moles();for(int c=0;c<n.length;c++){external[c]+=n[c];turnover[c]+=Math.abs(n[c]);}externalEnergy+=b.totalEnergyJoule();energyTurnover+=Math.abs(b.totalEnergyJoule());}
            externalEnergy+=result.pumpWorkJoule();energyTurnover+=Math.abs(result.pumpWorkJoule());
            ConservationAssertions.components(before,owned(graph),external,turnover);
            ConservationAssertions.energy(energy,ownedEnergy(graph),externalEnergy,energyTurnover);
        }
        return committed;
    }
}
