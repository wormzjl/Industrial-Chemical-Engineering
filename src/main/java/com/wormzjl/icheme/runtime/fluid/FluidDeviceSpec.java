package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import java.util.*;

/**
 * Validated persisted initialization/boundary settings. Finite reservoir contents live only in the world ledger. A
 * reservoir's {@code volume} and {@code levelHeight} are its vessel's (a one-block tank's capacity over 1 m; a drum's
 * design volume over its height, batch 2026-10-15-custom-equipment-rendering), fixed once it is placed; {@code open}
 * whether it is open to the atmosphere under a floating roof ({@code PassiveNetwork.Inventory#open}, batch
 * 2026-10-21-floating-roof-tanks).
 */
public record FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition,SlurryFeed solids,double levelHeight,boolean open,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry, com.wormzjl.icheme.science.fluid.network.CoolingTower tower) {
    public FluidDeviceSpec withTower(com.wormzjl.icheme.science.fluid.network.CoolingTower value) {return new FluidDeviceSpec(volume,temperature,pressure,composition,solids,levelHeight,open,geometry,value);}
    public FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition,SlurryFeed solids,double levelHeight,boolean open){this(volume,temperature,pressure,composition,solids,levelHeight,open,com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(volume,levelHeight),null);}
    public FluidDeviceSpec withGeometry(com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry){return new FluidDeviceSpec(volume,temperature,pressure,composition,solids,levelHeight,open,geometry,tower);}
    /** The largest vessel volume, m³ (a floating-roof tank 40 m across with 23 m of fill holds about 28,900 m³, owner
     * decision FR6), and the tallest level height, m. */
    public static final double MAX_VOLUME=com.wormzjl.icheme.science.equipment.EquipmentShape.MAX_VOLUME,MAX_LEVEL_HEIGHT=128;
    public FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition,SlurryFeed solids,double levelHeight){this(volume,temperature,pressure,composition,solids,levelHeight,false);}
    public FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition){this(volume,temperature,pressure,composition,SlurryFeed.NONE);}
    public FluidDeviceSpec(double volume,double temperature,double pressure,double[] composition,SlurryFeed solids){this(volume,temperature,pressure,composition,solids,com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.LEVEL_HEAD_HEIGHT);}
    public FluidDeviceSpec {
        if(tower!=null&&(!open||Math.abs(tower.basinVolume()-volume)>1e-8*volume||levelHeight!=com.wormzjl.icheme.science.fluid.network.CoolingTower.BASIN_DEPTH))throw new IllegalArgumentException("A cooling tower needs its own open basin geometry");
        Objects.requireNonNull(solids);Objects.requireNonNull(geometry);if(Math.abs(geometry.capacity()-volume)>volume*1e-8||Math.abs(geometry.height()-levelHeight)>levelHeight*1e-8)throw new IllegalArgumentException("Geometry differs from vessel capacity");composition=composition.clone();
        // Structural bounds only. The input box a setting must lie in is the property package's envelope, so it is checked
        // against the model (validate, initialize), not against constants here.
        if(!Double.isFinite(volume)||volume<=0||volume>MAX_VOLUME||!Double.isFinite(levelHeight)||levelHeight<=0||levelHeight>MAX_LEVEL_HEIGHT||!Double.isFinite(temperature)||!(temperature>0)||!Double.isFinite(pressure)||!(pressure>0)||(composition.length<1||composition.length>com.wormzjl.icheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS))throw new IllegalArgumentException("Device settings outside the fluid model's bounds");
        double sum=0;for(double amount:composition){if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid composition");sum+=amount;}
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Empty composition");
        if(Math.abs(sum-1)>1e-12)for(int i=0;i<composition.length;i++)composition[i]/=sum;
    }
    @Override public double[] composition(){return composition.clone();}
    public static FluidDeviceSpec nitrogen(){return nitrogen(com.wormzjl.icheme.science.material.MaterialRuntime.current());}
    public static FluidDeviceSpec water(){return water(com.wormzjl.icheme.science.material.MaterialRuntime.current());}
    public static FluidDeviceSpec nitrogen(com.wormzjl.icheme.science.material.MaterialCatalog catalog){return pure(catalog,"Nitrogen");}
    public static FluidDeviceSpec water(com.wormzjl.icheme.science.material.MaterialCatalog catalog){return pure(catalog,"Water");}
    private static FluidDeviceSpec pure(com.wormzjl.icheme.science.material.MaterialCatalog catalog,String id) {
        var ids=new ArrayList<>(catalog.requirePackage(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog)).components());ids.add("Water");
        var axis=new com.wormzjl.icheme.science.material.MaterialAxis(ids);double[] n=new double[axis.size()];n[axis.requireIndex(id)]=1;
        return new FluidDeviceSpec(1,298.15,101325,n);
    }
    /**
     * Refuses a void's or a generator's temperature and pressure outside the package envelope, when the player sets them
     * ({@link com.wormzjl.icheme.science.fluid.thermo.FluidDomain#requireInput}: an input box, plan decision 9, never
     * a hold of a state the network reaches by itself). A reservoir is placed empty (decision D54), and pipes and
     * actuators hold nothing.
     */
    public void validate(FluidThermodynamics model,TopologyCompiler.Kind kind) {
        if(tower!=null&&kind!=TopologyCompiler.Kind.RESERVOIR)throw new IllegalArgumentException("A cooling tower must be a reservoir");
        if(composition.length!=model.components().size())throw new IllegalArgumentException("Device composition differs from captured fluid axis");
        switch(kind) {
            case VOID,GENERATOR->model.domain().requireInput(temperature,pressure);
            default->{}
        }
    }
    /**
     * Whether {@code other} differs from these settings at most in the vessel's geometry (its volume, level height and
     * opening): the only edit a placed vessel takes, a layout refresh rebuilding it to another size (batch
     * 2026-10-26-backward-compat), which keeps what it holds.
     */
    public boolean sameCharge(FluidDeviceSpec other) {
        return temperature==other.temperature&&pressure==other.pressure&&Arrays.equals(composition,other.composition)&&solids.equals(other.solids);
    }
    /** Called for first activation, never to reconcile an already owned finite reservoir. */
    public PassiveNetwork.Reservoir initialize(PhysicalFluidTopology.Device device,FluidThermodynamics model,Runnable checkpoint) {
        validate(model,device.kind());
        var kind=switch(device.kind()) {case RESERVOIR->PassiveNetwork.NodeKind.RESERVOIR;case GENERATOR->PassiveNetwork.NodeKind.GENERATOR;case VOID->PassiveNetwork.NodeKind.VOID;default->throw new IllegalArgumentException("Pipes and actuators have no owned reservoir");};
        // Decision D54: a placed reservoir holds nothing; it fills from what its connections deliver.
        if(kind==PassiveNetwork.NodeKind.RESERVOIR){var n=PassiveNetwork.emptyVessel(device.id(),device.position().y(),volume,levelHeight,open,model);return new PassiveNetwork.Reservoir(n.id(),n.elevation(),n.state(),n.kind(),new PassiveNetwork.Inventory(volume,n.inventory().moles(),0,n.inventory().solids(),levelHeight,open,geometry,null,tower));}
        FluidThermodynamics.State state;
        // C1 B (batch 2026-11-10-chimney): a sink open to the air stands at the atmosphere of its own height, whatever its spec says.
        if(kind==PassiveNetwork.NodeKind.VOID)state=model.initialNitrogenCharge(volume,temperature,device.atmospheric()?com.wormzjl.icheme.science.fluid.solver.PhaseLayout.atmosphere(device.position().y()):pressure,checkpoint);
        else {
            var n=composition.clone();var unit=model.flashTP(temperature,pressure,n,checkpoint);
            var stock=solids.unitMass(model.solids);double liquidVolume=unit.liquidVolume()+unit.waterVolume();
            if(solids.volumeFraction()>0&&liquidVolume==0)throw new IllegalArgumentException("A slurry generator needs a liquid carrier");
            double solidVolume=solids.volumeFraction()/(1-solids.volumeFraction())*liquidVolume;
            double factor=volume/(unit.volume()+solidVolume);for(int c=0;c<n.length;c++)n[c]*=factor;
            state=model.flashTP(temperature,pressure,n,checkpoint).withSolids(solidVolume==0?com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY:stock.scale(solidVolume*factor/stock.volume()));
        }
        return new PassiveNetwork.Reservoir(device.id(),device.position().y(),state,kind,new PassiveNetwork.Inventory(volume,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()));
    }
    @Override public boolean equals(Object value){return value instanceof FluidDeviceSpec other&&Objects.equals(tower,other.tower)&&geometry.equals(other.geometry)&&volume==other.volume&&levelHeight==other.levelHeight&&open==other.open&&temperature==other.temperature&&pressure==other.pressure&&Arrays.equals(composition,other.composition)&&solids.equals(other.solids);}
    @Override public int hashCode(){return 31*Objects.hash(volume,levelHeight,open,temperature,pressure,solids,geometry,tower)+Arrays.hashCode(composition);}
}
