package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The trace flush (owner decision 2026-09-27, option T1 with the removed amount booked;
 * {@code PassiveIntervalSolver.TRACE_FLUSH_FRACTION}) on the smallest island that shows it: two equal vessels of water
 * under nitrogen joined by one pipe, at rest, one of them holding a methane trace.
 *
 * <p>A trace below 1e-24 of its vessel is removed at the first accepted step and booked as a boundary transfer at the
 * vessel itself, so the interval's ledger (owned after - owned before = boundary transfers) closes; the removal carries
 * no energy (the vessel keeps its internal-energy field, and no stream carries potential energy since owner decision D37,
 * S1: before, the removed mass's m g z at the vessels' 5 m); the interval is not certified, the next one carries no flush. A trace above the
 * threshold stays.
 */
class TraceFlushLedgerTest {
    private static final Runnable NOOP=()->{};
    private static final double ELEVATION=5;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final double[] mw=model.molecularWeights();
    private final int water=model.components().indexOf("Water"),nitrogen=model.components().indexOf("Nitrogen"),methane=model.components().indexOf("Methane");

    /** A 1 m3 vessel at 298.15 K and 1 atm holding 0.3 m3 of water under nitrogen, with methane at {@code share} of its total amount. */
    private FluidThermodynamics.State vessel(double share) {
        double t=298.15,p=101325;double[] n=new double[mw.length];
        n[water]=.3*997/mw[water];n[nitrogen]=p*.7/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        n[methane]=share*(n[water]+n[nitrogen]);
        return model.flashTP(t,p,n,NOOP);
    }
    private PassiveNetwork pair(double share) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,ELEVATION,vessel(share)),new PassiveNetwork.Reservoir(2,ELEVATION,vessel(0))),
                List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0))));
    }
    private static List<ConservativeTransport.BoundaryTransfer> at(PassiveIntervalSolver.Result result,long node) {
        return result.boundaries().stream().filter(b->b.nodeId()==node).toList();
    }
    private double ownedEnergy(PassiveNetwork graph) {
        double total=0;
        for(var node:graph.reservoirs())total+=node.inventory().internalEnergy();
        return total;
    }

    @Test void aTraceBelowTheThresholdIsRemovedAndBookedExactly() {
        assertTrue(water>=0&&nitrogen>=0&&methane>=0,"the package has water, nitrogen and methane");
        var before=pair(1e-26);double held=before.reservoirs().getFirst().inventory().moles()[methane];
        assertTrue(held>0,"the vessel starts with the trace");
        var solver=new PassiveIntervalSolver(model);
        var result=solver.solve(before,5,PassiveIntervalSolver.Settings.defaults(),NOOP);
        var after=result.graph();
        assertEquals(0.0,after.reservoirs().getFirst().inventory().moles()[methane],"the trace is gone from the vessel");
        assertEquals(0.0,TestPhases.vapor(after.reservoirs().getFirst().state())[methane],"and from its state");
        var booked=at(result,1);
        assertEquals(1,booked.size(),"one transfer booked at the flushed vessel");
        var moles=booked.getFirst().moles();
        for(int c=0;c<moles.length;c++)if(c!=methane)assertEquals(0.0,moles[c],"only the trace component is booked, component "+c);
        assertTrue(moles[methane]<0,"removed material leaves the network");
        assertEquals(-held,moles[methane],1e-9*held,"what was removed is what the vessel held");
        assertEquals(0.0,booked.getFirst().totalEnergyJoule(),"the removal books no energy: the vessel keeps its internal energy");
        assertTrue(booked.getFirst().solids().empty());
        assertTrue(at(result,2).isEmpty(),"the vessel without a trace books nothing");
        // The interval's ledger: owned after - owned before = the boundary transfers, component by component and in energy.
        var external=new double[mw.length];double externalEnergy=0;
        for(var b:result.boundaries()){for(int c=0;c<external.length;c++)external[c]+=b.moles()[c];externalEnergy+=b.totalEnergyJoule();}
        for(int c=0;c<mw.length;c++) {
            double a=0,b=0;for(var node:before.reservoirs())a+=node.inventory().moles()[c];for(var node:after.reservoirs())b+=node.inventory().moles()[c];
            assertEquals(a+external[c],b,1e-12*Math.max(1e-300,Math.max(a,b)),"component "+c+" ledger");
        }
        double energyBefore=ownedEnergy(before),energyAfter=ownedEnergy(after);
        assertEquals(energyBefore+externalEnergy+result.pumpWorkJoule(),energyAfter,1e-12*Math.abs(energyBefore),"energy ledger");
        // A one-time removal is not a rate: the interval is not certified, the next one is an ordinary interval.
        var summary=new IslandCertificate.Summary(new IslandCertificate.Interval(0,100,before,result),mw);
        assertTrue(summary.flushed);
        var issued=IslandCertificate.issue(summary,CertificatePolicy.defaults());
        assertNull(issued.certificate());assertEquals("the interval flushed a trace component",issued.refusal());
        var next=solver.solve(after,5,PassiveIntervalSolver.Settings.defaults(),NOOP);
        assertTrue(at(next,1).isEmpty()&&at(next,2).isEmpty(),"nothing is left to flush");
        assertFalse(new IslandCertificate.Summary(new IslandCertificate.Interval(100,200,after,next),mw).flushed);
    }

    @Test void aTraceAboveTheThresholdStays() {
        var before=pair(1e-20);
        var result=new PassiveIntervalSolver(model).solve(before,5,PassiveIntervalSolver.Settings.defaults(),NOOP);
        assertTrue(result.graph().reservoirs().getFirst().inventory().moles()[methane]>0,"the trace is kept");
        assertTrue(at(result,1).isEmpty()&&at(result,2).isEmpty(),"and nothing is booked at a vessel");
    }
}
