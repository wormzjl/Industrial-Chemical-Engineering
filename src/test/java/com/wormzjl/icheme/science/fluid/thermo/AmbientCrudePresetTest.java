package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbientCrudePresetTest {
    @Test void crudePresetsFlashAndHaveFiniteTransportAtAmbientTemperature() {
        var catalog=MaterialCatalog.bundled();
        var model=FluidThermodynamics.forNetwork(catalog,FluidPresetCatalog.NETWORK_PACKAGE);
        for (var preset:FluidPresetCatalog.resolve(catalog).subList(2,6)) {
            for (double t:new double[]{293.15,298,298.15,300,350}) {
                var state=assertDoesNotThrow(()->model.flashTP(t,101325,preset.moleFractions(),()->{}),preset.name()+" at "+t+" K");
                assertEquals(t,state.temperature());
                assertTrue(state.volume()>0);
                assertTrue(Double.isFinite(state.internalEnergy()));
                assertTrue(model.viscosity.liquid(t,state.liquid()).pascalSeconds()>0);
                if(state.vaporVolume()>0)assertTrue(model.viscosity.vapor(t,state.vapor(),state.waterVapor())>0);
            }
        }
    }

    @Test void actualGeneratorInitializationAndPipeFlowAccept298Kelvin() {
        var catalog=MaterialCatalog.bundled();
        var model=FluidThermodynamics.forNetwork(catalog,FluidPresetCatalog.NETWORK_PACKAGE);
        var geometry=new PipeResistance.Geometry(1,.05,.000045,0);
        var device=new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),
                TopologyCompiler.Kind.GENERATOR,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());
        for(var preset:FluidPresetCatalog.resolve(catalog).subList(2,6)) {
            var generator=new FluidDeviceSpec(1,298,200000,preset.moleFractions()).initialize(device,model,()->{});
            assertEquals(298,generator.state().temperature());
            var liquid=generator.state().liquid();
            var viscosity=model.viscosity.liquid(298,liquid).pascalSeconds();
            var density=generator.state().mass()/generator.state().volume();
            assertTrue(PipeResistance.evaluate(geometry,.01,density,viscosity).pressureDrop()>0);
        }
    }

    /**
     * Colder than a component's declared range is continued, not refused (owner rule of 2026-10-16; F4 refused it). The
     * range is the record's own data - its fluid_domain block, 293.15 K for the bundled crude records (the ambient
     * continuation): an author who overrides a fit declares the range the new fit is valid over, and below it the fit's
     * cp is held at that end, never its polynomial carried past its data.
     */
    @Test void colderStatesAndPropertyOverridesAreContinuedFromTheirOwnRange() {
        var catalog=MaterialCatalog.bundled();
        var original=new HydrocarbonModel(catalog,FluidPresetCatalog.NETWORK_PACKAGE);
        double[] n=new double[original.componentCount()];n[10]=1;
        var colder=assertDoesNotThrow(()->original.phase(293,101325,n,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID));
        assertTrue(Double.isFinite(colder.molarEnthalpy()));
        var resources=new java.util.HashMap<>(catalog.resources());
        String path="data/icheme/materials/properties/crude_pc04.json";
        var p=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();
        var cp=p.getAsJsonObject("ideal_gas_cp").getAsJsonArray("coefficients");
        cp.set(0,new com.google.gson.JsonPrimitive(cp.get(0).getAsDouble()*1.001));
        // The override's author qualifies the new fit over its own range only, and says so in its data.
        p.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",298.15);resources.put(path,p.toString());
        var edited=new HydrocarbonModel(MaterialCatalog.parse(resources),FluidPresetCatalog.NETWORK_PACKAGE);
        assertDoesNotThrow(()->edited.phase(298,101325,n,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID));
        assertDoesNotThrow(()->edited.phase(300,101325,n,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID));
    }
}
