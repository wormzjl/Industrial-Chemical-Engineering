package com.wormzjl.icheme.runtime;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A network island leaving a data range keeps running (owner rule of 2026-10-16; it replaces F4's end-to-end thermo-domain
 * hold), through the real solver and the real coordinator: a placed nitrogen transfer (tank, compressor, pipe, tank)
 * whose nitrogen record is narrowed to 300 K at the top, so the discharge's heating (302.3 K at the compressor's ratio
 * 1.05) leaves the record's ideal-gas data. Physics: nitrogen's cp at 300 K is 29.1 J/mol K and rises by 0.005 J/mol K
 * per kelvin, so holding it past the record changes the discharge enthalpy by about 0.01 J/mol: the transfer runs as it
 * would with the wider record. Every interval is accepted and no hold is warned (a hold would park the island until an input changes);
 * the status line carries the advisory notice, read by the worker on each accepted interval's end state (plan section 5).
 */
class FluidDataRangeIslandTest {
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";

    static FluidThermodynamics narrowed(double maximum) {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());String key="data/icheme/materials/properties/nitrogen.json";
        var o=JsonParser.parseString(resources.get(key)).getAsJsonObject();o.getAsJsonObject("fluid_domain").addProperty("temperature_max_kelvin",maximum);resources.put(key,o.toString());
        return FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),NETWORK);
    }
    /** R P C P R along +x, the WP5 rig's transfer with a compressor: devices 1 (tank), 2 (pipe), 3 (compressor), 4 (pipe), 5 (tank)
     * (the pipe between the suction tank and the compressor since only pipes connect, decision D20). */
    static PassiveNetwork transfer(FluidThermodynamics model) {
        var block=new PipeResistance.Geometry(1,.05,.000045,0);double[] nitrogen=new double[model.componentCount()];nitrogen[model.components().indexOf("Nitrogen")]=1;
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        String layout="RPCPR";
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'C'->TopologyCompiler.Kind.COMPRESSOR;case 'P'->TopologyCompiler.Kind.PIPE;default->TopologyCompiler.Kind.RESERVOIR;};
            var device=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,64,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),PhysicalFluidTopology.Direction.EAST,block,
                    kind==TopologyCompiler.Kind.COMPRESSOR?new FlowControl.Compressor(.01,1.05):new FlowControl.Passive());
            devices.add(device);
            if(kind==TopologyCompiler.Kind.RESERVOIR)boundaries.put(device.id(),FluidTestSupport.chargedBoundary(new FluidDeviceSpec(1,298.15,101325,nitrogen),device,model));
        }
        return PhysicalFluidTopology.compile(devices,boundaries).islands().stream().filter(i->i.physicalIds().size()==devices.size()).findFirst().orElseThrow().graph();
    }

    /** The coordinator of one island with an inline worker; {@link #run} solves every dispatched attempt at once. */
    static final class Rig {
        final IslandCoordinator coordinator;
        final List<ProcessSolveServices.FluidIslandSolveResult> results=new ArrayList<>();
        final List<String> warnings=new ArrayList<>();
        private final LinkedHashMap<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        private final HashMap<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        Rig(FluidThermodynamics model,PassiveNetwork graph,java.util.function.LongFunction<String> names) {
            long[] requests={0};
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 1-attempts.size();}
                public long nextRequestId(){return ++requests[0];}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){}
            },changed->{},()->0L,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false,100,CertificatePolicy.defaults()));
            coordinator.onPhaseHold((island,v,where,parked)->warnings.add(v.getMessage()+" | "+where));
            coordinator.nodeNames(names);
            coordinator.register(new IslandCoordinator.Snapshot(1,0,graph,new IslandClock.Snapshot(0,0,0,100),Optional.empty(),"READY"),model);
        }
        void run(int ticks) {
            for(int t=0;t<ticks;t++) {
                coordinator.tick();
                for(long request:List.copyOf(attempts.keySet())) {
                    var attempt=attempts.remove(request);var command=commands.remove(request);long[] clock={0};
                    var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(FluidTestSupport.NEVER,()->clock[0]+=1_000);
                    results.add(result);coordinator.completed(attempt,Optional.of(result));
                }
                coordinator.pump();
            }
        }
        double hottest() {
            double t=0;for(var node:coordinator.snapshot(1).graph().reservoirs())if(!node.empty())t=Math.max(t,node.state().temperature());return t;
        }
    }

    @Test void aCompressorHeatingNitrogenPastItsRecordKeepsRunning() {
        var model=narrowed(300);
        var rig=new Rig(model,transfer(model),id->switch((int)id){case 1->"reservoir at 0, 64, 0";case 3->"compressor at 2, 64, 0";case 5->"reservoir at 4, 64, 0";default->"pipe junction "+id;});
        double hottest=0;
        for(int round=0;round<40;round++){rig.run(10);hottest=Math.max(hottest,rig.hottest());}
        String status=rig.coordinator.snapshot(1).status();
        System.out.println("data range left: hottest "+hottest+" K | "+status);
        assertTrue(hottest>300,"the discharge left the record's 300 K: "+hottest);
        assertFalse(rig.results.isEmpty());
        for(var result:rig.results)assertTrue(result.candidate().isPresent()&&result.phaseHold().isEmpty(),"every interval accepted: "+result.detail());
        assertFalse(status.startsWith("HELD"),status);
        assertTrue(rig.warnings.isEmpty(),"no hold is warned: "+rig.warnings);
        assertTrue(rig.results.stream().anyMatch(result->!result.notices().isEmpty()),"an accepted interval carries the notice");
        assertTrue(status.contains(DataRangeNotices.STATUS_MARKER+"Nitrogen ideal-gas heat capacity extrapolated above 300 K at "),status);
    }
}
