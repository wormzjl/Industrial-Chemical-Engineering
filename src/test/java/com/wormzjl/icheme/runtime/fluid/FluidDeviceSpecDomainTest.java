package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * F4, T1.1, and the input box of the range-extrapolation plan (decision 9, owner rule of 2026-10-16): the device input
 * bounds follow the property package, not constants. A spec is structurally checked when it is built and checked against
 * the package envelope where it meets the model (validate, initialize; the edit handler and the world's placement
 * defaults call validate), with a plain refusal naming the envelope: an input box, never a hold of a state the network
 * reaches by itself.
 */
class FluidDeviceSpecDomainTest {
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,"icheme:tjl20_methane_nitrogen");
    /** Pure nitrogen on this model's axis (the package's own list, not the network's). */
    private double[] nitrogen(){double[] n=new double[model.componentCount()];n[model.components().indexOf("Nitrogen")]=1;return n;}
    private PhysicalFluidTopology.Device device(TopologyCompiler.Kind kind) {
        return new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,64,0),kind,PhysicalFluidTopology.Direction.EAST,
                new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
    }

    /** A void holds nitrogen at its settings; a reservoir is placed empty (owner decision D54), so its settings name no
     * fluid to check. */
    @Test void aNitrogenVoidAt200KelvinIsValidAndOneAt50KelvinIsRefusedByTheInputBoxWhileAReservoirIsPlacedEmpty() {
        var cold=new FluidDeviceSpec(1,200,101325,nitrogen());
        assertDoesNotThrow(()->cold.validate(model,TopologyCompiler.Kind.VOID));
        var vent=cold.initialize(device(TopologyCompiler.Kind.VOID),model,()->{});
        assertEquals(200,vent.state().temperature());
        var colder=new FluidDeviceSpec(1,50,101325,nitrogen());
        var refused=assertThrows(IllegalArgumentException.class,()->colder.validate(model,TopologyCompiler.Kind.VOID));
        assertEquals("Temperature 50.0 K outside the package envelope 63.151..900.0 K (package icheme:tjl20_methane_nitrogen)",refused.getMessage());
        assertFalse(refused instanceof com.wormzjl.icheme.science.fluid.thermo.ThermoHold,"an input box, not a hold");
        assertThrows(IllegalArgumentException.class,()->colder.initialize(device(TopologyCompiler.Kind.VOID),model,()->{}));
        assertDoesNotThrow(()->colder.validate(model,TopologyCompiler.Kind.RESERVOIR));
        var tank=colder.initialize(device(TopologyCompiler.Kind.RESERVOIR),model,()->{});
        assertTrue(tank.empty(),"a placed tank holds nothing");assertEquals(1,tank.inventory().volume());
    }

    /** A generator's settings are checked against the package envelope only: a crude fraction below its data at 280 K is
     * accepted and continued. Water at 260 K passes the input box and the generator supplies it, the stand-in of its ice
     * (WP4: free liquid water, which a vessel's refresh freezes), with no hold. */
    @Test void aGeneratorIsCheckedAgainstTheEnvelopeAndControlsAreOnlyStructural() {
        // W3b: water on the refinery network, whose cubic holds it (this package holds none).
        var wet=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        int water=wet.componentCount()-1;double[] n=new double[wet.componentCount()];n[water]=1;
        var ice=new FluidDeviceSpec(1,260,101325,n);
        assertDoesNotThrow(()->ice.validate(wet,TopologyCompiler.Kind.GENERATOR));
        var source=assertDoesNotThrow(()->ice.initialize(device(TopologyCompiler.Kind.GENERATOR),wet,()->{}));
        assertTrue(TestPhases.waterLiquid(source.state())>0&&TestPhases.waterVapor(source.state())==0,"condensed below the melting line, carried as its stand-in");
        double[] crude=new double[model.componentCount()];crude[model.components().indexOf("crude_pc02")]=1;
        assertDoesNotThrow(()->new FluidDeviceSpec(1,280,101325,crude).validate(model,TopologyCompiler.Kind.GENERATOR));
        assertThrows(IllegalArgumentException.class,()->new FluidDeviceSpec(1,950,101325,crude).validate(model,TopologyCompiler.Kind.GENERATOR),"above the 900 K envelope");
        assertDoesNotThrow(()->new FluidDeviceSpec(1,700,101325,crude).validate(model,TopologyCompiler.Kind.GENERATOR),"the old 600 K ceiling is gone; the envelope reaches 900 K");
        // The menu's controls carry no temperature or pressure range of their own: the server refuses with the envelope.
        assertDoesNotThrow(()->new FluidNetwork.Controls(50,101325,.05,0,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(-1,101325,.05,0,.01,500000,n),"but must be physical");
        assertThrows(IllegalArgumentException.class,()->new FluidDeviceSpec(1,0,101325,n));
        var valve=assertThrows(IllegalArgumentException.class,()->model.domain().requireInputPressure(50),"a valve target outside the envelope");
        assertTrue(valve.getMessage().contains("outside the package envelope 100.0..1.0E7 Pa"),valve.getMessage());
    }

    // ---------------- from FluidDeviceSpecTest ----------------
    @Test void allRequiredCrudePresetsShareTheExactQualifiedPropertyBasis() {
        var presets=FluidPresetCatalog.resolve(MaterialCatalog.bundled());assertEquals(8,presets.size(),"water, nitrogen, the four grades, CO2 (O11) and the demo slurry");assertTrue(presets.stream().anyMatch(p->p.id().equals("icheme:demo_slurry")&&p.solids().volumeFraction()==.1));
        for(var preset:presets){assertEquals(com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1,preset.moleFractions().length);assertEquals(1,java.util.Arrays.stream(preset.moleFractions()).sum(),1e-12);}
        assertTrue(presets.stream().anyMatch(p->p.name().equals("Oil sand dilbit")));assertTrue(presets.stream().anyMatch(p->p.name().equals("Light crude")));
    }
}
