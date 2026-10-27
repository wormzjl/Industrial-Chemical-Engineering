package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpGameplayRegressionTest {
    /**
     * The first island of an archived gameplay capture (see mcp-held-drain-checkpoint.md). It is solver input, not a save
     * that is loaded: no checkpoint format reads it any more, so only its graph is taken, in the shape the payloads still
     * use. The held drain is the checkpoint saved on 2026-09-16, kept byte for byte for reproducibility; the clamped
     * liquid-full island was re-archived under the engine's liquid water (D27).
     */
    private com.google.gson.JsonObject archivedIsland(String resource) throws java.io.IOException {
        try(var input=Objects.requireNonNull(getClass().getResourceAsStream(resource))) {
            return com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("islands").get(0).getAsJsonObject();
        }
    }
    private record Phase(double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {}
    private record Inventory(double volume,double[] moles,double internalEnergy) {}
    private record Node(long id,double elevation,String kind,Inventory inventory,Phase phase) {}
    private record Control(String kind,double target,double limit) {}
    /** A section as the capture saved it: it predates channel counts (state format 23), so each is one channel. */
    private record Section(double length,double diameter,double roughness,double minorLoss) {
        PipeResistance.Geometry geometry(){return new PipeResistance.Geometry(length,diameter,roughness,minorLoss);}
    }
    private record Pipe(long id,int first,int second,List<Section> sections,Control control) {}
    private record Graph(List<Node> nodes,List<Pipe> pipes) {}
    /** The graph of a capture in its format-3 payload JSON shape. The capture predates solids, blocked masks, filters and
     * phase ports (checkpoint format 6) and holds none of them, so they are empty here and every pipe end is a BULK end. */
    private static PassiveNetwork archivedGraph(com.google.gson.JsonObject island,FluidThermodynamics model) {
        var json=island.getAsJsonObject("graph");
        for(var node:json.getAsJsonArray("nodes"))assertFalse(node.getAsJsonObject().getAsJsonObject("inventory").has("solids"));
        for(var pipe:json.getAsJsonArray("pipes")){var p=pipe.getAsJsonObject();assertFalse(p.has("blockedDirections")||p.has("filter")||p.has("firstPort")||p.has("secondPort"));}
        var saved=new com.google.gson.Gson().fromJson(json,Graph.class);
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(var n:saved.nodes()) {
            var p=n.phase();var inventory=new PassiveNetwork.Inventory(n.inventory().volume(),n.inventory().moles(),n.inventory().internalEnergy(),com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY);
            nodes.add(new PassiveNetwork.Reservoir(n.id(),n.elevation(),model.state(p.temperature(),p.pressure(),p.liquid(),p.vapor(),p.waterLiquid(),p.waterVapor(),p.hydrocarbonPressure()).withSolids(inventory.solids()),
                    PassiveNetwork.NodeKind.valueOf(n.kind()),inventory));
        }
        return new PassiveNetwork(nodes,saved.pipes().stream().map(p->new PassiveNetwork.Pipe(p.id(),p.first(),p.second(),p.sections().stream().map(Section::geometry).toList(),switch(p.control().kind()) {
            case "passive"->new FlowControl.Passive();case "pump"->new FlowControl.Pump(p.control().target(),p.control().limit());
            case "valve"->new FlowControl.PressureValve(p.control().target());default->throw new IllegalArgumentException("Unknown saved control");
        },0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK)).toList());
    }
    /** The tank holds the capture's nitrogen trace and the water that fills its 1 m3 at the captured 298.16 K and
     * 150.66 kPa (997.06 kg, D27); 31.1 kg/s flows through it from the 200 kPa generator to the 1 atm void. */
    @Test void clampedDrainContinuesAfterTheTankBecomesAlmostLiquidFull() throws Exception {
        var model=FluidTestSupport.networkModel();
        var graph=archivedGraph(archivedIsland("/fluid/mcp-clamped-liquid-full-checkpoint.json"),model);
        var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(1,result.advancedSeconds());assertTrue(result.graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]>0);
        var refined=new PassiveIntervalSolver(model).solve(graph,1,new PassiveIntervalSolver.Settings(.005,.02,4096),()->{});
        for(int i=0;i<graph.reservoirs().size();i++) {
            var actual=result.graph().reservoirs().get(i);var expected=refined.graph().reservoirs().get(i);
            assertEquals(expected.state().pressure(),actual.state().pressure(),1+.001*expected.state().pressure());
            assertEquals(expected.state().temperature(),actual.state().temperature(),.1);
            assertArrayEquals(expected.inventory().moles(),actual.inventory().moles(),1e-8*Math.max(1,expected.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]));
        }
        for(int i=0;i<result.averageMassFlows().length;i++)assertEquals(refined.averageMassFlows()[i],result.averageMassFlows()[i],1e-10+.002*Math.abs(refined.averageMassFlows()[i]));
        // Continue past the first replay interval: the in-game failure moved a few seconds
        // later as the nitrogen trace shrank. No trace cutoff or invented gas is permitted.
        var continuing=result.graph();for(int i=0;i<200;i++)continuing=new PassiveIntervalSolver(model).solve(continuing,5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        assertTrue(continuing.reservoirs().getFirst().inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]>0);
    }
    @Test void savedSonicRefusalAdvancesWithVelocityClampingAndKeepsCanonicalBalances() throws Exception {
        var model=FluidTestSupport.networkModel();
        var saved=archivedIsland("/fluid/mcp-held-drain-checkpoint.json");var graph=archivedGraph(saved,model);
        assertTrue(saved.get("status").getAsString().contains("Unsupported sonic flow"));var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(5,result.advancedSeconds());assertTrue(Arrays.stream(result.averageMassFlows()).anyMatch(q->Math.abs(q)>1e-6));
        double[] before=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],after=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],external=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];double beforeEnergy=0,afterEnergy=0,externalEnergy=0;
        for(var boundary:result.boundaries()){var n=boundary.moles();for(int c=0;c<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;c++)external[c]+=n[c];externalEnergy+=boundary.totalEnergyJoule();}
        for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).kind()==PassiveNetwork.NodeKind.RESERVOIR) {
            var a=graph.reservoirs().get(i);var b=result.graph().reservoirs().get(i);var na=a.inventory().moles();var nb=b.inventory().moles();
            for(int c=0;c<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;c++){before[c]+=na[c];after[c]+=nb[c];}
            beforeEnergy+=a.inventory().internalEnergy();afterEnergy+=b.inventory().internalEnergy();
        }
        for(int c=0;c<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;c++)assertEquals(before[c]+external[c],after[c],1e-10+1e-8*Math.max(before[c],after[c]));
        assertEquals(beforeEnergy+externalEnergy+result.pumpWorkJoule(),afterEnergy,1e-4+1e-6*(Math.abs(beforeEnergy)+Math.abs(externalEnergy)));
    }
    @Test void belowSeaLevelNitrogenTankAcceptsWaterAfterAnIdlePeriodAndPressureEdit() {
        var model=FluidTestSupport.networkModel();
        var nitrogen=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var spec=FluidDeviceSpec.water();
        var device=new PhysicalFluidTopology.Device(3,new PhysicalFluidTopology.Position("minecraft:overworld",0,-60,3),com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR,PhysicalFluidTopology.Direction.NORTH,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
        var water=spec.initialize(device,model,()->{}).state();
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,-60,nitrogen),
                new PassiveNetwork.Reservoir(3,-60,water,PassiveNetwork.NodeKind.GENERATOR)),
                List.of(new PassiveNetwork.Pipe(2,0,1,new PipeResistance.Geometry(1,.05,.000045,0))));
        var solver=new PassiveIntervalSolver(model);
        for(int i=0;i<20;i++)graph=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        var higher=new FluidDeviceSpec(1,298.15,200000,spec.composition()).initialize(device,model,()->{}).state();
        graph=new PassiveNetwork(List.of(graph.reservoirs().getFirst(),new PassiveNetwork.Reservoir(3,-60,higher,PassiveNetwork.NodeKind.GENERATOR)),graph.pipes());
        var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(result.graph().reservoirs().getFirst().state().waterLiquid()>0);
        assertEquals(nitrogen.vapor()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN],result.graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN],1e-8);
        var refined=new PassiveIntervalSolver(model).solve(graph,5,new PassiveIntervalSolver.Settings(.00005,.02,4096),()->{});
        var actual=result.graph().reservoirs().getFirst().state();var expected=refined.graph().reservoirs().getFirst().state();
        System.out.println("MCP_BELOW_SEA_LEVEL pressure="+actual.pressure()+"/"+expected.pressure()+" temperature="+actual.temperature()+"/"+expected.temperature()
                +" mass="+actual.mass()+"/"+expected.mass()+" flow="+result.averageMassFlows()[0]+"/"+refined.averageMassFlows()[0]
                +" accepted="+result.acceptedSubsteps()+"/"+refined.acceptedSubsteps());
        // Restated 2026-09-27 (hold F7, owner decision: a vessel's mass change is measured against at least 900 kg/m3
        // times its volume). The 1.2 kg nitrogen tank no longer forces small steps while water enters, only the 5 %
        // pressure cap limits the step: 10 accepted steps in the 5 s slice against 260 refined. Measured against the
        // refined run: pressure 129960.05 against 130299.51 Pa (0.26 %; bound was 0.1 %), mass 201.288 against
        // 203.412 kg (1.04 %; was 203.164 kg, 0.125 %, bound 0.2 %), flow 40.029 against 40.453 kg/s (1.05 %; bound
        // was 0.2 %). First-order backward-Euler error at the larger step, accepted with the 5 s cycle. Since decision D22
        // (2026-09-28) the twenty idle intervals (generator and tank both at 1 atm, level) are at rest and not solved, so
        // the edited slice starts from a fresh solver history: 129967.48 Pa, 201.335 kg, 40.038 kg/s, 9 accepted steps.
        assertEquals(expected.pressure(),actual.pressure(),.003*expected.pressure());
        assertEquals(expected.temperature(),actual.temperature(),.1);
        assertEquals(expected.mass(),actual.mass(),.012*expected.mass());
        assertEquals(refined.averageMassFlows()[0],result.averageMassFlows()[0],.012*Math.abs(refined.averageMassFlows()[0]));
    }
}
