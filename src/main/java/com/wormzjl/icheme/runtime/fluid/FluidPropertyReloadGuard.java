package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Objects;
import java.util.Optional;

/** Owner-thread check against the qualified startup property snapshot. A new scientific model (a datapack reload while
 * the world runs) holds the network until the world is saved and reopened: the reopened world restates its contents on
 * the new data from its saved facts (AGENTS.md, "Saves and data formats"). Replacing names alone does not pause science. */
public final class FluidPropertyReloadGuard {
    public static final String HOLD="HELD: property data changed; save and reopen the world to restate the fluid network on the new data";
    private final String packageId,qualifiedRevision;
    private final double maximumVelocity,traceCutoff;
    private final com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solids;
    private final com.wormzjl.icheme.science.fluid.SimulationTime time;
    private MaterialCatalog observed;
    private String refusal;
    public FluidPropertyReloadGuard(MaterialCatalog initial,String packageId,FluidThermodynamics model) {
        solids=model.solidSettings;observed=Objects.requireNonNull(initial);this.packageId=Objects.requireNonNull(packageId);
        maximumVelocity=model.maximumVelocityMetresPerSecond();time=model.time();
        // Every numerical setting the qualified model was built with, so a reload of the same data
        // rebuilds the same model and the comparison isolates the property change it is looking for.
        traceCutoff=model.traceTruncation().cutoffMoleFraction();
        qualifiedRevision=model.revision();
    }
    public Optional<String> inspect(MaterialCatalog current) {
        Objects.requireNonNull(current);if(current==observed)return Optional.ofNullable(refusal);
        observed=current;
        try {
            var candidate=FluidThermodynamics.forNetwork(current,packageId,maximumVelocity,traceCutoff,solids,time);
            refusal=qualifiedRevision.equals(candidate.revision())?null:HOLD;
        } catch(IllegalArgumentException|IllegalStateException unavailable) {refusal=HOLD;}
        return Optional.ofNullable(refusal);
    }
    /** Validates the solid materials a saved world owns or references against {@code catalog}, never an unrelated added definition. */
    static void validateSolids(com.wormzjl.icheme.science.material.SolidMaterialCatalog catalog,FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world) {
        for(var island:checkpoint.islands()) {
            for(var node:island.snapshot().graph().reservoirs())catalog.validate(node.inventory().solids());
            for(var pipe:island.snapshot().graph().pipes())if(pipe.filter()!=null)catalog.validate(pipe.filter().captured());
        }
        for(var recovery:world.recoveries().values())catalog.validate(recovery.solids());
    }
}
